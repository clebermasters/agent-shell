# ACP Chat Log Direct Watch - Design Document

**Date**: 2026-03-11  
**Feature**: Watch ACP sessions directly by session ID (bypassing tmux detection)  
**Status**: Draft - Awaiting Implementation

---

## Problem Statement

Currently, the backend's `watch-chat-log` feature relies on detecting an AI tool (Claude, Codex, or OpenCode) running inside a tmux pane. This works when:

- The AI tool is running inside a tmux session
- The backend can traverse the process tree to find the AI tool's PID

However, when using ACP (AgentShell's ACP protocol) as a standalone process (not inside tmux), the current detection fails with:

```
error: "no AI tool (claude/codex/opencode) found among descendants of pane PID"
```

This prevents the Flutter app from receiving chat history and live messages for ACP sessions running outside tmux.

---

## Goals

1. Allow Flutter app to watch any ACP session directly by its session ID
2. Auto-start (resume) ACP sessions if they're not currently running
3. Continue persisting messages to SQLite for direct ACP watches
4. Keep ACP watching independent from tmux session management

---

## Architecture

### Components Affected

| Component | Changes |
|-----------|---------|
| `types/mod.rs` | Add `WatchAcpChatLog` variant to `WebSocketMessage` enum |
| `websocket/mod.rs` | Add handler for `WatchAcpChatLog` message |
| `chat_event_store.rs` | Add method to query messages by ACP session ID |
| `flutter/lib/data/services/websocket_service.dart` | Add `watchAcpChatLog` method |

### Data Flow

```
Flutter App                          Backend (Rust)                          SQLite
    |                                      |                                      |
    |  watch-acp-chat-log                  |                                      |
    |  { sessionId: "ses_xxx" }          |                                      |
    |------------------------------------>|                                      |
    |                                      |  1. List ACP sessions               |
    |                                      |  2. Find session by ID              |
    |                                      |  3. If not running, resume it      |
    |                                      |  4. Query history from SQLite       |
    |                                      |     (key: "acp_ses_xxx")          |
    |                                      |------------------------------------>|
    |                                      |                                      |
    |  chat-history { messages: [...] }   |                                      |
    |<------------------------------------|                                      |
    |                                      |                                      |
    |                                      |  5. Start polling OpenCode DB      |
    |                                      |     for new messages                |
    |                                      |                                      |
    |  chat-event { message: ... }       |                                      |
    |<------------------------------------|                                      |
    |                                      |  6. Persist new messages           |
    |                                      |------------------------------------>|
```

---

## API Design

### New WebSocket Message: `watch-acp-chat-log`

**Direction**: Flutter → Backend

```json
{
  "type": "watch-acp-chat-log",
  "sessionId": "ses_3255fc8ddffemybyrtsYDTs2kg",
  "windowIndex": 0
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | ACP session ID to watch |
| `windowIndex` | number | No | Window index (default: 0) |

### Responses

**Success - Chat History:**

```json
{
  "type": "chat-history",
  "sessionName": "acp_ses_3255fc8ddffemybyrtsYDTs2kg",
  "windowIndex": 0,
  "messages": [...],
  "tool": {
    "type": "opencode",
    "cwd": "/home/cleber_rodrigues/kiro-bot",
    "pid": 110047
  }
}
```

**Success - New Message:**

```json
{
  "type": "chat-event",
  "sessionName": "acp_ses_3255fc8ddffemybyrtsYDTs2kg",
  "windowIndex": 0,
  "message": {...},
  "source": "acp"
}
```

**Error:**

```json
{
  "type": "chat-log-error",
  "error": "Session not found: ses_xxx"
}
```

---

## Implementation Details

### 1. Backend Changes

#### 1.1 Add Message Type (`types/mod.rs`)

```rust
// In WebSocketMessage enum, after WatchChatLog variant
WatchAcpChatLog {
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "windowIndex")]
    window_index: Option<u32>,
}
```

#### 1.2 Add ChatEventStore Method (`chat_event_store.rs`)

```rust
impl ChatEventStore {
    /// Get chat history for an ACP session
    pub fn get_acp_history(
        &self,
        session_id: &str,
        limit: Option<u32>,
        offset: Option<u32>,
    ) -> Result<Vec<StoredChatEvent>> {
        let session_key = format!("acp_{}", session_id);
        // Query from SQLite with session_key
    }
}
```

#### 1.3 Handler Implementation (`websocket/mod.rs`)

```rust
WebSocketMessage::WatchAcpChatLog { session_id, window_index } => {
    let window_index = window_index.unwrap_or(0);
    let session_key = format!("acp_{}", session_id);
    
    // 1. Get ACP client and list sessions
    let acp_guard = state.acp_client.read().await;
    let client = acp_guard.as_ref()
        .ok_or_else(|| "ACP backend not initialized")?;
    
    let sessions = client.list_sessions().await?;
    let target = sessions.sessions.iter()
        .find(|s| s.session_id == session_id);
    
    // 2. If not found, return error
    let session_info = target
        .ok_or_else(|| format!("Session not found: {}", session_id))?;
    
    // 3. Check if running, resume if needed
    let is_running = /* check if session is in active sessions */;
    if !is_running {
        client.resume_session(&session_id, &session_info.cwd).await?;
    }
    
    // 4. Get history from SQLite
    let history = state.chat_event_store.get_acp_history(&session_id, None, None)?;
    
    // 5. Send chat history response
    // 6. Spawn watcher for new messages
}
```

### 2. Flutter Changes

#### 2.1 WebSocket Service (`websocket_service.dart`)

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

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| Session ID doesn't exist | Return `chat-log-error` with "Session not found" |
| Session exists but ACP not initialized | Return error "ACP backend not initialized" |
| Resume fails | Return error with resume failure reason |
| SQLite query fails | Log error, return empty history, continue polling |
| Session stops during watch | Continue polling (will detect new session on next resume attempt) |

---

## Testing Plan

### Unit Tests

- `ChatEventStore.get_acp_history()` - Query with various limit/offset values
- Message parsing - Ensure `WatchAcpChatLog` deserializes correctly

### Integration Tests

1. Watch existing ACP session (already running)
2. Watch non-running ACP session (should auto-resume)
3. Watch non-existent session (should error)
4. Switch between tmux and ACP watching
5. Verify messages persist to SQLite

### Manual Testing

1. Start Flutter app, connect to backend
2. Send `watch-acp-chat-log` with valid session ID
3. Verify chat history loads
4. Send message in ACP session, verify real-time update
5. Close ACP session, verify graceful handling

---

## Success Criteria

1. Flutter can watch any ACP session by ID without requiring tmux
2. History loads from SQLite correctly (206 messages in current DB)
3. New messages stream in real-time
4. Messages persist to SQLite
5. Auto-resume works for stopped sessions
6. Appropriate error messages for invalid sessions

---

## Related Files

| File | Changes |
|------|---------|
| `backend-rust/src/types/mod.rs` | Add `WatchAcpChatLog` variant |
| `backend-rust/src/websocket/mod.rs` | Add handler |
| `backend-rust/src/chat_event_store.rs` | Add `get_acp_history()` |
| `flutter/lib/data/services/websocket_service.dart` | Add `watchAcpChatLog()` |

---

## Future Considerations

- Add `unwatch-acp-chat-log` for explicit cleanup
- Add `load-more-acp-history` for pagination
- Add support for multiple simultaneous ACP watches
- Consider caching ACP session list to reduce list_sessions calls
