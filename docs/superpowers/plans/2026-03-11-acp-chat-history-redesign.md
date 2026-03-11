# ACP Chat History Redesign — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken dual-DB mirror with a clean architecture: OpenCode DB as primary source for AI conversation, AgentShell DB as overlay for webhook/file messages only.

**Architecture:** `WatchAcpChatLog` reads full history from OpenCode DB + webhook overlay from AgentShell DB, merges by timestamp, then polls OpenCode DB for new messages and forwards them directly to Flutter without persistence. The AgentShell DB only ever stores webhook/file messages.

**Tech Stack:** Rust (rusqlite, tokio), Dart/Flutter (Riverpod)

**Spec:** `docs/superpowers/specs/2026-03-11-acp-chat-history-redesign.md`

---

## Chunk 1: Backend — New OpenCode DB reader

### Task 1: Add `fetch_all_messages` to `opencode_parser.rs`

**Files:**
- Modify: `backend-rust/src/chat_log/opencode_parser.rs`

This function reads ALL parts for a given `session_id` from the OpenCode DB, ordered by `time_updated ASC`. It reuses the existing `parse_part` logic. Returns `(Vec<ChatMessage>, i64)` where the `i64` is `max(time_updated)` — used to initialize the poll loop.

- [ ] **Step 1: Add the function**

Add after the existing `fetch_new_messages` function:

```rust
/// Read all messages for a session from the OpenCode DB.
/// Returns (messages, max_time_updated). max_time_updated is 0 if no messages.
/// Respects cleared_at: messages with time_updated <= cleared_at are excluded.
pub fn fetch_all_messages(
    db_path: &Path,
    session_id: &str,
    cleared_at: Option<i64>,
) -> Result<(Vec<ChatMessage>, i64)> {
    let conn = Connection::open(db_path)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;

    let min_time = cleared_at.unwrap_or(0);

    let mut stmt = conn.prepare(
        "SELECT p.id, p.data, p.time_updated, json_extract(m.data, '$.role')
         FROM part p
         JOIN message m ON p.message_id = m.id
         WHERE p.session_id = ? AND p.time_updated > ?
         ORDER BY p.time_updated ASC",
    )?;

    let mut rows = stmt.query(rusqlite::params![session_id, min_time])?;

    // Reuse OpencodeState dedup logic
    let mut state = OpencodeState {
        pid: 0,
        session_id: session_id.to_string(),
        last_time_updated: min_time,
        cleared_at,
        seen_text_lengths: std::collections::HashMap::new(),
        seen_tool_calls: std::collections::HashSet::new(),
        seen_tool_results: std::collections::HashSet::new(),
    };

    let mut messages = Vec::new();
    let mut max_time_updated: i64 = 0;

    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        let data: String = row.get(1)?;
        let time_updated: i64 = row.get(2)?;
        let role: Option<String> = row.get(3)?;
        let role = role.unwrap_or_else(|| "assistant".to_string());

        if time_updated > max_time_updated {
            max_time_updated = time_updated;
        }

        if let Ok(part) = serde_json::from_str::<PartData>(&data) {
            if let Some(msg) = parse_part(&id, &part, time_updated, &mut state, &role) {
                messages.push(msg);
            }
        }
    }

    Ok((messages, max_time_updated))
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd backend-rust && cargo check 2>&1 | grep -E "^error"
```
Expected: no output (no errors)

- [ ] **Step 3: Commit**

```bash
git add backend-rust/src/chat_log/opencode_parser.rs
git commit -m "feat: add fetch_all_messages to opencode_parser"
```

---

## Chunk 2: Backend — AgentShell DB overlay reader

### Task 2: Add `get_acp_overlay` to `chat_event_store.rs`, remove `get_acp_history`

**Files:**
- Modify: `backend-rust/src/chat_event_store.rs`

`get_acp_overlay` returns only rows where `source IN ('webhook', 'webhook-file')` for a given session key, filtered by `cleared_at`. This replaces `get_acp_history` which returned all messages (including duplicated AI messages).

- [ ] **Step 1: Add `get_acp_overlay`, remove `get_acp_history`**

Replace the entire `get_acp_history` method with:

```rust
/// Get webhook/file overlay messages for an ACP session.
/// Returns only messages with source 'webhook' or 'webhook-file'.
/// Excludes messages with timestamp_millis <= cleared_at.
pub fn get_acp_overlay(
    &self,
    session_key: &str,
    cleared_at: Option<i64>,
) -> Result<Vec<StoredChatEvent>> {
    let conn = Connection::open(&self.db_path)
        .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
    conn.busy_timeout(Duration::from_secs(5))?;

    let min_time = cleared_at.unwrap_or(0);

    let mut stmt = conn.prepare(
        "SELECT event_id, session_name, window_index, source, timestamp_millis, message_json
         FROM chat_events
         WHERE session_name = ?1
           AND source IN ('webhook', 'webhook-file')
           AND timestamp_millis > ?2
         ORDER BY timestamp_millis ASC",
    )?;

    let rows = stmt.query_map(params![session_key, min_time], |row| {
        let message_json: String = row.get(5)?;
        let message: crate::chat_log::ChatMessage = serde_json::from_str(&message_json)
            .unwrap_or(crate::chat_log::ChatMessage {
                role: "unknown".to_string(),
                timestamp: None,
                blocks: vec![],
            });
        Ok(StoredChatEvent {
            event_id: row.get(0)?,
            session_name: row.get(1)?,
            window_index: row.get(2)?,
            source: row.get(3)?,
            timestamp_millis: row.get(4)?,
            message,
        })
    })?;

    rows.collect::<std::result::Result<Vec<_>, _>>().map_err(Into::into)
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd backend-rust && cargo check 2>&1 | grep -E "^error"
```
Expected: errors only for `get_acp_history` call sites (will fix in Task 3)

- [ ] **Step 3: Commit**

```bash
git add backend-rust/src/chat_event_store.rs
git commit -m "feat: add get_acp_overlay, remove get_acp_history"
```

---

## Chunk 3: Backend — Rewrite WatchAcpChatLog handler

### Task 3: Rewrite `WatchAcpChatLog` in `websocket/mod.rs`

**Files:**
- Modify: `backend-rust/src/websocket/mod.rs`

The new handler:
1. Finds the OpenCode DB path
2. Gets `cleared_at` from `chat_clear_store`
3. Calls `fetch_all_messages` (OpenCode DB) + `get_acp_overlay` (AgentShell DB)
4. Merges by timestamp, sends `ChatHistory`
5. Polls OpenCode DB from `max_time_updated` — sends `ChatEvent` directly, NO `append_message`

No longer needs `list_sessions` or `resume_session` — those are separate concerns.

- [ ] **Step 1: Replace the spawned task body**

Find the block starting at `let handle = tokio::spawn(async move {` inside `WatchAcpChatLog` and replace the entire spawned task with:

```rust
let handle = tokio::spawn(async move {
    // 1. Find OpenCode DB
    let db_path = match crate::chat_log::watcher::find_opencode_db() {
        Ok(p) => p,
        Err(e) => {
            let _ = send_message(
                &message_tx,
                ServerMessage::ChatLogError { error: format!("OpenCode DB not found: {}", e) },
            ).await;
            return;
        }
    };

    // 2. Get cleared_at
    let cleared_at = chat_clear_store.get_cleared_at(&session_key, window_index).await;

    // 3. Read full history from OpenCode DB
    let (opencode_messages, max_time_updated) =
        match crate::chat_log::opencode_parser::fetch_all_messages(&db_path, &session_id, cleared_at) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("Failed to read OpenCode history: {}", e);
                (vec![], 0)
            }
        };

    // 4. Read webhook/file overlay from AgentShell DB
    let overlay = chat_event_store
        .get_acp_overlay(&session_key, cleared_at)
        .unwrap_or_default()
        .into_iter()
        .map(|e| (e.timestamp_millis, e.message))
        .collect::<Vec<_>>();

    // 5. Merge: interleave OpenCode messages and overlay by timestamp
    // OpenCode messages carry their timestamp in the ChatMessage; overlay has explicit millis.
    // Strategy: collect all with millis, sort, extract messages.
    let mut combined: Vec<(i64, crate::chat_log::ChatMessage)> = opencode_messages
        .into_iter()
        .map(|m| {
            let millis = m.timestamp
                .map(|t| t.timestamp_millis())
                .unwrap_or(0);
            (millis, m)
        })
        .chain(overlay)
        .collect();
    combined.sort_by_key(|(t, _)| *t);
    let messages: Vec<crate::chat_log::ChatMessage> = combined.into_iter().map(|(_, m)| m).collect();

    // 6. Send history
    let _ = send_message(
        &message_tx,
        ServerMessage::ChatHistory {
            session_name: session_key.clone(),
            window_index,
            messages,
            tool: Some(crate::chat_log::AiTool::Opencode {
                cwd: std::path::PathBuf::from("/"),
                pid: 0,
            }),
        },
    ).await;

    // 7. Poll for new messages from OpenCode DB (no persistence)
    let mut opencode_state = crate::chat_log::opencode_parser::OpencodeState {
        session_id: session_id.clone(),
        last_time_updated: max_time_updated,
        cleared_at,
        pid: 0,
        seen_text_lengths: std::collections::HashMap::new(),
        seen_tool_calls: std::collections::HashSet::new(),
        seen_tool_results: std::collections::HashSet::new(),
    };

    let mut interval = tokio::time::interval(std::time::Duration::from_millis(1000));
    loop {
        interval.tick().await;
        match crate::chat_log::opencode_parser::fetch_new_messages(&db_path, &mut opencode_state) {
            Ok(new_messages) => {
                for msg in new_messages {
                    let _ = send_message(
                        &message_tx,
                        ServerMessage::ChatEvent {
                            session_name: session_key.clone(),
                            window_index,
                            message: msg,
                            source: Some("acp".to_string()),
                        },
                    ).await;
                }
            }
            Err(e) => tracing::debug!("ACP poll error: {}", e),
        }
    }
});
```

Also remove the now-unused clones at the top of the handler (the ones for `state_acp_client` — no longer needed):
- Remove: `let state_acp_client = state.acp_client.clone();`

- [ ] **Step 2: Fix `AcpClearHistory` — add `chat_clear_store` call**

Find `WebSocketMessage::AcpClearHistory { session_id }` and replace with:

```rust
WebSocketMessage::AcpClearHistory { session_id } => {
    let session_key = format!("acp_{}", session_id);
    if let Err(e) = app_state.chat_event_store.clear_messages(&session_key, 0) {
        tracing::warn!("Failed to clear ACP overlay for {}: {}", session_id, e);
    }
    // Set cleared_at so the poll loop and future history loads skip old messages
    let timestamp = chrono::Utc::now().timestamp_millis();
    app_state.chat_clear_store.set_cleared_at(&session_key, 0, timestamp).await;
    send_message(
        &state.message_tx,
        ServerMessage::ChatLogCleared { session_name: session_key, window_index: 0, success: true, error: None },
    ).await?;
}
```

- [ ] **Step 3: Simplify `AcpSendPrompt` — remove `append_message` call**

Find `WebSocketMessage::AcpSendPrompt { session_id, message }` and remove the block:
```rust
// Persist user message first
let session_key = format!("acp_{}", session_id);
let chat_message = ...;
if let Err(e) = app_state.chat_event_store.append_message(...) { ... }
```
Keep only the `send_prompt` call and response handling.

- [ ] **Step 4: Verify it compiles**

```bash
cd backend-rust && cargo build --release 2>&1 | grep -E "^error"
```
Expected: no errors

- [ ] **Step 5: Smoke test**

```bash
python3 -c "
import asyncio, websockets, json

async def test():
    async with websockets.connect('ws://localhost:4010/ws') as ws:
        await ws.send(json.dumps({'type': 'select-backend', 'backend': 'acp'}))
        await asyncio.wait_for(ws.recv(), timeout=5)
        await ws.send(json.dumps({'type': 'acp-list-sessions'}))
        resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        sid = resp['sessions'][0]['sessionId']
        await ws.send(json.dumps({'type': 'watch-acp-chat-log', 'sessionId': sid}))
        resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        msgs = resp.get('messages', [])
        seen = set()
        dupes = sum(1 for m in msgs if str(m) in seen or seen.add(str(m)))
        print(f'messages: {len(msgs)}, duplicates: {dupes}')
        # Collect events for 3s
        events = 0
        try:
            while True:
                await asyncio.wait_for(ws.recv(), timeout=3)
                events += 1
        except: pass
        print(f'events in 3s: {events}')
asyncio.run(test())
"
```
Expected: `duplicates: 0`, `events in 3s: 0` (no active session) or small number of genuinely new messages

- [ ] **Step 6: Deploy and commit**

```bash
sudo systemctl stop agentshell
sudo cp backend-rust/target/release/agentshell-backend /opt/agentshell/backend/agentshell-backend
sudo systemctl start agentshell
git add backend-rust/src/websocket/mod.rs
git commit -m "feat: rewrite WatchAcpChatLog — OpenCode DB primary, no mirroring"
```

---

## Chunk 4: Flutter — Fix `clear()` double-prefix bug

### Task 4: Fix `clear()` in `chat_provider.dart`

**Files:**
- Modify: `flutter/lib/features/chat/providers/chat_provider.dart`

`state.sessionName` is now `"acp_ses_..."` but `clearAcpHistory` expects the raw session ID. Strip the prefix before sending.

- [ ] **Step 1: Fix `clear()`**

Find the `clear()` method and change:
```dart
_ws!.clearAcpHistory(state.sessionName!);
```
to:
```dart
final rawId = state.sessionName!.replaceFirst('acp_', '');
_ws!.clearAcpHistory(rawId);
```

- [ ] **Step 2: Verify with flutter analyze**

```bash
cd flutter && flutter analyze lib/features/chat/providers/chat_provider.dart 2>&1 | grep error
```
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add flutter/lib/features/chat/providers/chat_provider.dart
git commit -m "fix: strip acp_ prefix before sending clearAcpHistory"
```

---

## Final verification

- [ ] Build APK and install on device
- [ ] Open ACP session → history loads with no duplicates
- [ ] Send a message → appears in real-time
- [ ] Send a webhook file → appears in chat and persists across reconnects
- [ ] Clear chat → history clears; new messages still appear
- [ ] Delete session → disappears from session list
- [ ] Reopen session → history loads correctly (respects cleared_at)
