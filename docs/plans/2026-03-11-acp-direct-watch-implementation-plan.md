# ACP Direct Watch Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow Flutter app to watch ACP sessions directly by session ID, bypassing tmux-based detection

**Architecture:** Add new WebSocket message type `watch-acp-chat-log` that queries ACP session info, auto-resumes if needed, loads history from SQLite, and streams new messages from OpenCode DB

**Tech Stack:** Rust (backend), Dart/Flutter, SQLite

---

## Task 1: Add WatchAcpChatLog Message Type

**Files:**
- Modify: `backend-rust/src/types/mod.rs:259-264`

**Step 1: Add the new variant**

Find the `WatchChatLog` variant in `WebSocketMessage` enum (around line 259), add new variant after it:

```rust
WatchAcpChatLog {
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "windowIndex")]
    window_index: Option<u32>,
},
```

**Step 2: Verify compilation**

Run: `cd /home/cleber_rodrigues/project/webmux/backend-rust && cargo check`
Expected: Compiles without errors

---

## Task 2: Add get_acp_history Method to ChatEventStore

**Files:**
- Modify: `backend-rust/src/chat_event_store.rs`

**Step 1: Add method to ChatEventStore**

Find `impl ChatEventStore` block (after line 25), add new method:

```rust
/// Get chat history for an ACP session by session ID
pub fn get_acp_history(
    &self,
    session_id: &str,
    limit: Option<u32>,
    offset: Option<u32>,
) -> Result<Vec<StoredChatEvent>> {
    let conn = Connection::open(&self.db_path)
        .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
    conn.busy_timeout(Duration::from_secs(5))?;

    let session_key = format!("acp_{}", session_id);
    let limit = limit.unwrap_or(100) as i64;
    let offset = offset.unwrap_or(0) as i64;

    let mut stmt = conn.prepare(
        "SELECT event_id, session_name, window_index, source, timestamp_millis, message_json
         FROM chat_events
         WHERE session_name = ?1
         ORDER BY timestamp_millis ASC
         LIMIT ?2 OFFSET ?3"
    )?;

    let rows = stmt.query_map(params![session_key, limit, offset], |row| {
        let message_json: String = row.get(5)?;
        let message: ChatMessage = serde_json::from_str(&message_json)
            .unwrap_or(ChatMessage {
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

    let mut events = Vec::new();
    for row in rows {
        events.push(row?);
    }

    Ok(events)
}
```

**Step 3: Verify compilation**

Run: `cd /home/cleber_rodrigues/project/webmux/backend-rust && cargo check`
Expected: Compiles without errors

---

## Task 3: Add WatchAcpChatLog Handler

**Files:**
- Modify: `backend-rust/src/websocket/mod.rs:844-971`

**Step 1: Find the WatchChatLog handler**

The handler starts around line 844. Copy the entire handler block and modify for ACP.

Add new handler after the existing `WatchChatLog` handler (around line 971):

```rust
WebSocketMessage::WatchAcpChatLog { session_id, window_index } => {
    let window_index = window_index.unwrap_or(0);
    let session_key = format!("acp_{}", session_id);
    
    info!("Starting ACP chat log watch for session: {}", session_id);
    
    // Set current session for consistency
    *state.current_session.lock().await = Some(session_key.clone());
    *state.current_window.lock().await = Some(window_index);
    
    // Cancel any existing watcher
    {
        let mut handle_guard = state.chat_log_handle.lock().await;
        if let Some(handle) = handle_guard.take() {
            tracing::info!("Stopping previous chat log watcher");
            handle.abort();
        }
    }
    
    let message_tx = state.message_tx.clone();
    let chat_event_store = state.chat_event_store.clone();
    let chat_clear_store = state.chat_clear_store.clone();
    let chat_log_handle = state.chat_log_handle.clone();
    let state_acp_client = state.acp_client.clone();
    
    let handle = tokio::spawn(async move {
        // 1. Get ACP client
        let acp_guard = state_acp_client.read().await;
        let client = match acp_guard.as_ref() {
            Some(c) => c,
            None => {
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatLogError {
                        error: "ACP backend not initialized".to_string(),
                    },
                )
                .await;
                return;
            }
        };
        
        // 2. List ACP sessions and find target
        let sessions = match client.list_sessions().await {
            Ok(s) => s,
            Err(e) => {
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatLogError {
                        error: format!("Failed to list ACP sessions: {}", e),
                    },
                )
                .await;
                return;
            }
        };
        
        let session_info = match sessions.sessions.iter().find(|s| s.session_id == session_id) {
            Some(s) => s,
            None => {
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatLogError {
                        error: format!("Session not found: {}", session_id),
                    },
                )
                .await;
                return;
            }
        };
        
        // 3. Resume session if not running (check if we can send prompts)
        // For simplicity, we'll try to resume - ACP will error if already running
        let _ = client.resume_session(&session_id, &session_info.cwd).await;
        
        // 4. Get chat history from SQLite
        let history = match chat_event_store.get_acp_history(&session_id, None, None) {
            Ok(h) => h,
            Err(e) => {
                tracing::warn!("Failed to get ACP history: {}", e);
                vec![]
            }
        };
        
        // Convert StoredChatEvent to ChatMessage
        let messages: Vec<ChatMessage> = history
            .into_iter()
            .map(|e| e.message)
            .collect();
        
        info!("Sending ACP chat history: {} messages", messages.len());
        
        // 5. Send history
        let _ = send_message(
            &message_tx,
            ServerMessage::ChatHistory {
                session_name: session_key.clone(),
                window_index,
                messages,
                tool: Some(AiTool::Opencode {
                    cwd: std::path::PathBuf::from(&session_info.cwd),
                    pid: 0, // Unknown PID for direct ACP watch
                }),
            },
        )
        .await;
        
        // 6. Start watching for new messages using OpenCode parser
        // This is similar to the existing opencode watching logic
        let db_path = match find_opencode_db() {
            Ok(p) => p,
            Err(e) => {
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatLogError {
                        error: format!("Failed to find OpenCode DB: {}", e),
                    },
                )
                .await;
                return;
            }
        };
        
        // Get cleared_at timestamp
        let cleared_at = chat_clear_store
            .get_cleared_at(&session_key, window_index)
            .await;
        
        // We need the PID of the running ACP session - for now use 0
        // The opencode_parser will handle this
        let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel();
        
        let watcher = match crate::chat_log::watcher::watch_log_file(
            &db_path,
            AiTool::Opencode {
                cwd: std::path::PathBuf::from(&session_info.cwd),
                pid: 0,
            },
            event_tx,
            cleared_at,
        )
        .await
        {
            Ok(w) => w,
            Err(e) => {
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatLogError {
                        error: format!("Failed to start watcher: {}", e),
                    },
                )
                .await;
                return;
            }
        };
        
        // Keep watcher alive
        std::mem::forget(watcher);
        
        // Forward new messages
        while let Some(event) = event_rx.recv().await {
            match event {
                crate::chat_log::ChatLogEvent::NewMessage { message } => {
                    // Persist to SQLite
                    if let Err(e) = chat_event_store.append_message(&session_key, window_index, "acp", &message) {
                        tracing::warn!("Failed to persist ACP message: {}", e);
                    }
                    
                    let _ = send_message(
                        &message_tx,
                        ServerMessage::ChatEvent {
                            session_name: session_key.clone(),
                            window_index,
                            message,
                            source: Some("acp".to_string()),
                        },
                    )
                    .await;
                }
                crate::chat_log::ChatLogEvent::History { .. } => {
                    // Already sent history above
                }
                crate::chat_log::ChatLogEvent::Error { error } => {
                    let _ = send_message(
                        &message_tx,
                        ServerMessage::ChatLogError { error },
                    )
                    .await;
                }
            }
        }
    });
    
    *chat_log_handle.lock().await = Some(handle);
}
```

**Step 2: Add import for AiTool**

Make sure `AiTool` is imported at the top of the file. It should already be there.

**Step 3: Verify compilation**

Run: `cd /home/cleber_rodrigues/project/webmux/backend-rust && cargo check`
Expected: Compiles without errors

---

## Task 4: Add Flutter WebSocket Service Method

**Files:**
- Modify: `flutter/lib/data/services/websocket_service.dart:270-281`

**Step 1: Add watchAcpChatLog method**

After the existing `watchChatLog` method (around line 277), add:

```dart
void watchAcpChatLog(String sessionId, {int? windowIndex, int? limit}) {
  send({
    'type': 'watch-acp-chat-log',
    'sessionId': sessionId,
    if (windowIndex != null) 'windowIndex': windowIndex,
    if (limit != null) 'limit': limit,
  });
}
```

**Step 2: Verify Flutter compiles**

Run: `cd /home/cleber_rodrigues/project/webmux/flutter && flutter analyze lib/data/services/websocket_service.dart`
Expected: No errors

---

## Task 5: Test Manually

**Step 1: Restart backend**

Run: `cd /home/cleber_rodrigues/project/webmux/backend-rust && cargo run`
Or restart the running backend service

**Step 2: Test with Python WebSocket client**

```python
import asyncio
import websockets
import json

async def test():
    uri = "ws://localhost:4010/ws"
    async with websockets.connect(uri) as ws:
        # Select ACP backend
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()  # Skip response
        
        # List sessions
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        response = await ws.recv()
        data = json.loads(response)
        sessions = data.get("sessions", [])
        print(f"Found {len(sessions)} sessions")
        
        if sessions:
            session_id = sessions[0]["sessionId"]
            print(f"Watching session: {session_id}")
            
            # Watch ACP chat
            await ws.send(json.dumps({
                "type": "watch-acp-chat-log",
                "sessionId": session_id
            }))
            
            # Wait for responses
            for i in range(5):
                response = await asyncio.wait_for(ws.recv(), timeout=10)
                data = json.loads(response)
                print(f"Response {i+1}: {data.get('type')}")
                if data.get("type") in ["chat-history", "chat-log-error"]:
                    break

asyncio.run(test())
```

Expected: Should receive chat-history with messages

---

## Verification Checklist

- [ ] Backend compiles without errors
- [ ] Flutter compiles without errors
- [ ] WebSocket connection works
- [ ] `watch-acp-chat-log` returns chat history
- [ ] New messages stream in real-time
- [ ] Error handling works for invalid session IDs
- [ ] Messages persist to SQLite

---

## Plan complete

Saved to: `docs/plans/2026-03-11-acp-direct-watch-design.md`
