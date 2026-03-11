# ACP Session Chat Feature - Troubleshooting Report

## Executive Summary

This document details the troubleshooting process for implementing a feature to watch ACP (OpenCode) sessions directly by session ID in the AgentShell Flutter app. The feature allows the app to display chat history for ACP sessions running outside of tmux, with chat messages persisted to a SQLite database.

**Current Status:** Partially working - backend is functional, but Flutter app has connection/reconnection issues causing session list refresh to fail.

---

## 1. Background

### 1.1 Original Problem
The existing `watch-chat-log` feature relied on detecting AI tools inside tmux panes. When ACP runs as a standalone process (not in tmux), the backend couldn't find it, returning error: "no AI tool (claude/codex/opencode) found among descendants of pane PID"

### 1.2 Solution Implemented
Added new `watch-acp-chat-log` WebSocket message type that:
- Takes an ACP session ID directly
- Auto-resumes the session if not running
- Loads history from SQLite database
- Polls for new messages in real-time
- Persists new messages to database for future retrieval

---

## 2. Architecture

### 2.1 Components Modified

**Backend (Rust):**
- `backend-rust/src/types/mod.rs` - Added `WatchAcpChatLog` variant (lines 265-271)
- `backend-rust/src/chat_event_store.rs` - Added `get_acp_history()` method and increased default limit to 500
- `backend-rust/src/websocket/mod.rs` - Added handler with polling and database persistence
- `backend-rust/src/chat_log/watcher.rs` - Made `find_opencode_db()` public (line 585)

**Flutter:**
- `flutter/lib/data/services/websocket_service.dart` - Added `watchAcpChatLog()` method (lines 279-286)
- `flutter/lib/features/chat/providers/chat_provider.dart` - Added `startAcpChat()` method

### 2.2 Data Flow

```
Flutter App                          Backend (Rust)
    |                                      |
    |--- select-backend("acp") ----->     |
    |--- acp-resume-session() ----->     |
    |--- watch-acp-chat-log() ----->     |
    |                                      |
    |                              get_acp_history() from SQLite
    |                                      |
    |<---- chat-history (500 msgs) ----    |
    |                                      |
    |                              Poll every 1 second:
    |                              - fetch_new_messages() from OpenCode DB
    |                              - append_message() to SQLite (persist)
    |                                      |
    |<---- chat-event (real-time) ----    |
```

---

## 3. Database Persistence Implementation

### 3.1 Database Schema

The chat events are stored in SQLite at `/opt/agentshell/backend/chat_events.db`

**Table: `chat_events`**
```sql
CREATE TABLE chat_events (
    event_id TEXT PRIMARY KEY,
    session_name TEXT NOT NULL,
    window_index INTEGER NOT NULL,
    source TEXT NOT NULL,
    timestamp_millis INTEGER NOT NULL,
    message_json TEXT NOT NULL
);

CREATE INDEX idx_session_name ON chat_events(session_name);
CREATE INDEX idx_timestamp ON chat_events(timestamp_millis);
```

### 3.2 Session Key Format

For ACP sessions, the session key format is: `acp_{session_id}`

Example: `acp_ses_3255fc8ddffemybyrtsYDTs2kg`

### 3.3 WebSocket Message Format

The client sends the session ID to the backend to watch ACP chat logs:

**Request (Flutter → Backend):**
```json
{
  "type": "watch-acp-chat-log",
  "sessionId": "ses_3255fc8ddemybyrtsYDTs2kg",
  "windowIndex": 0,
  "limit": 500
}
```

**Rust Type Definition** (`types/mod.rs` lines 265-271):
```rust
// ACP chat log watching (direct by session ID)
WatchAcpChatLog {
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "windowIndex")]
    window_index: Option<u32>,
},
```

**Flutter Implementation** (`websocket_service.dart` lines 299-306):
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

### 3.4 Key Methods in chat_event_store.rs

**1. `append_message()` (Line 59-95)**
- Persists a single chat message to the database
- Used by the polling loop to save new messages
- Generates UUID for event_id
- Stores message as JSON in `message_json` column

```rust
pub fn append_message(
    &self,
    session_name: &str,
    window_index: u32,
    source: &str,
    message: &ChatMessage,
) -> Result<String>
```

**2. `get_acp_history()` (Line 268-324)**
- Retrieves chat history for an ACP session
- Changed default limit from 100 to 500
- Returns `Vec<StoredChatEvent>` ordered by timestamp

```rust
pub fn get_acp_history(
    &self,
    session_id: &str,
    limit: Option<u32>,  // Default: 500
    offset: Option<u32>,  // Default: 0
) -> Result<Vec<StoredChatEvent>>
```

### 3.5 Backend Session ID Processing Flow

When the backend receives `watch-acp-chat-log` with a session ID:

1. **Parse session ID** - Extract `session_id` from the request
2. **Build session key** - Create `acp_{session_id}` for database queries (line 987)
3. **Get session info** - Look up session from ACP client sessions list (line 1037)
4. **Resume session** - Call `client.resume_session(&session_id, &session_info.cwd)` (line 1053)
5. **Fetch history** - Call `chat_event_store.get_acp_history(&session_id, ...)` (line 1058)
6. **Return to client** - Send `ChatHistory` message with messages

**Key Code Locations:**

- **Handler entry** (`websocket/mod.rs:985-1005`):
  - Sets current session/window state
  - Cancels any existing watcher
  - Spawns async task for this watch

- **Session lookup** (`websocket/mod.rs:1037-1049`):
  ```rust
  let session_info = match sessions.sessions.iter().find(|s| s.session_id == session_id) {
      Some(s) => s,
      None => {
          // Return error: "Session not found: {session_id}"
      }
  };
  ```

- **History fetch** (`websocket/mod.rs:1055-1058`):
  ```rust
  // Get chat history from SQLite
  let history = match chat_event_store.get_acp_history(&session_id, None, None) {
      Ok(h) => h,
      Err(e) => {
          // Return error
      }
  };
  ```

### 3.4 Polling Loop Persistence (websocket/mod.rs)

The polling loop in the `WatchAcpChatLog` handler:

1. **Polls every 1 second** (line 1129)
2. **Fetches new messages** from OpenCode database using `fetch_new_messages()`
3. **Persists each new message** to SQLite (line 1139):
   ```rust
   if let Err(e) = chat_event_store.append_message(&session_key, window_index, "acp", &msg) {
       tracing::warn!("Failed to persist ACP message: {}", e);
   }
   ```
4. **Sends to Flutter** via WebSocket as `chat-event` message

### 3.5 Message Format

**StoredChatEvent struct:**
```rust
struct StoredChatEvent {
    event_id: String,
    session_name: String,
    window_index: u32,
    source: String,
    timestamp_millis: i64,
    message: ChatMessage,
}
```

**ChatMessage struct:**
```rust
struct ChatMessage {
    role: String,  // "user" | "assistant" | "system"
    timestamp: Option<DateTime<Utc>>,
    blocks: Vec<ContentBlock>,  // Text, ToolCall, ToolResult, etc.
}
```

---

## 4. Issues Identified

### 4.1 Issue #1: Backend Working, Flutter Not Using Correct Method

**Symptom:** Messages not being received by Flutter app for ACP sessions.

**Root Cause:** The Flutter app was calling `acpLoadHistory` instead of `watchAcpChatLog`. The `watchAcpChatLog` method existed but was never called.

**Fix Applied:**
- Changed `startAcpChat` in `chat_provider.dart` to call `watchAcpChatLog(sessionName, limit: 500)` instead of `acpLoadHistory`

**File:** `flutter/lib/features/chat/providers/chat_provider.dart:835`

### 4.2 Issue #2: Race Condition in WebSocket Calls

**Symptom:** ACP chat loading hangs when opened.

**Root Cause:** In `chat_screen.dart`, WebSocket messages were sent asynchronously without awaiting:
```dart
ws.selectBackend('acp');      // async - not awaited
ws.acpResumeSession(...);   // not awaited
ref.read(chatProvider...).startAcpChat(...)  // called immediately
```

**Fix Applied:**
- Changed `startAcpChat` to be `async` and added 500ms delay before sending message

**File:** `flutter/lib/features/chat/providers/chat_provider.dart:823-841`

### 4.3 Issue #3: WebSocket Connection State Not Updated (CURRENT)

**Symptom:** 
- Session list loads initially
- On refresh, session list never loads
- Backend shows CLOSE_WAIT connections from device
- Flutter thinks connection is still connected (`isConnected: true`)

**Root Cause:** Multiple issues:
1. When WebSocket disconnects, `_isConnected` flag not properly synchronized
2. When `send()` is called while disconnected, messages are silently dropped
3. Connection marked as "connected" before actually establishing

**Evidence:**
```
Backend log shows:
- WebSocket connection from device (192.168.0.120)
- Connection state: CLOSE_WAIT (connection closed from device side)
- No new requests received when refresh pressed

Flutter logs show:
- SESSION_DEBUG refresh() called, isConnected: true (but connection is actually closed!)
```

**Fixes Attempted:**

1. **Attempt 1:** Added reconnect trigger when send() is called while disconnected
```dart
void send(Map<String, dynamic> message) {
  if (_isConnected && _channel != null) {
    _channel!.sink.add(jsonEncode(message));
  } else {
    _scheduleReconnect();  // Trigger reconnect
  }
}
```
Result: Not sufficient - messages still not sent after reconnect

2. **Attempt 2:** Added retry after delay
```dart
Future.delayed(const Duration(seconds: 3), () {
  if (_isConnected && _channel != null) {
    _channel!.sink.add(jsonEncode(message));  // Retry after 3s
  }
});
```
Result: Still not working

3. **Attempt 3:** Added proper connection establishment check
```dart
await Future.delayed(const Duration(milliseconds: 500));
_isConnected = true;  // Wait before marking connected
```
Result: App crashes on reinstall

4. **Attempt 4:** Proper cleanup before reconnect
```dart
await _subscription?.cancel();
await _channel?.sink.close();
_channel = WebSocketChannel.connect(Uri.parse(_currentUrl!));
```
Result: Still not connecting properly

---

## 5. Troubleshooting Steps Performed

### 5.1 Backend Verification
- Created test scripts to verify backend functionality
- Backend correctly responds with 500 messages and real-time events
- Verified with Python WebSocket client
- Backend is working correctly

**Test Script:** `scripts/test_flutter_simulation.py`

### 5.2 Flutter Debug Logging Added
Added extensive debug logging to both backend and Flutter:

**Backend:**
- Added `[ACP_DEBUG]` prefix for key events
- Logs show: "Received WatchAcpChatLog", "Fetching history", "ChatHistory sent"

**Flutter:**
- Added `[FLUTTER_DEBUG]` logs in:
  - `startAcpChat()`
  - `_handleChatHistory()`
  - `_handleChatEvent()`
- Added `[SESSION_DEBUG]` logs in `refresh()`

### 5.3 ADB Log Analysis
- Captured device logs using `adb logcat`
- Identified WebSocket connection states
- Found CLOSE_WAIT connections on backend from device IP

### 5.4 Network Analysis
- Used `lsof -i :4010` to check connection states
- Identified that connections are closing but Flutter state not updating

---

## 6. Current State

### 6.1 What's Working
1. ✅ Backend `watch-acp-chat-log` endpoint returns 500 messages
2. ✅ Backend sends real-time chat events via polling
3. ✅ Messages persisted to SQLite database
4. ✅ Flutter `watchAcpChatLog` method is called
5. ✅ Backend receives and processes messages
6. ✅ Initial session list loads (first time)
7. ✅ Initial ACP chat loads (first time)

### 6.2 What's NOT Working
1. ❌ Session list refresh (after first load)
2. ❌ ACP chat loads after navigating away and back

### 6.3 Root Cause (Suspected)
The WebSocket connection management in Flutter has issues:
1. Connection state (`_isConnected`) not synchronized with actual connection
2. When device sleeps/backgrounds, connection drops but Flutter thinks it's connected
3. `send()` silently fails when connection is down
4. Reconnection logic not properly triggering

---

## 7. Files Modified During Debugging

### Backend:
- `backend-rust/src/types/mod.rs` - Added WatchAcpChatLog variant
- `backend-rust/src/chat_event_store.rs` - Added get_acp_history(), increased limit to 500
- `backend-rust/src/websocket/mod.rs` - Added handler with polling and database persistence
- `backend-rust/src/chat_log/watcher.rs` - Made find_opencode_db() public

### Flutter:
- `flutter/lib/data/services/websocket_service.dart` - Added watchAcpChatLog(), modified send() for reconnect
- `flutter/lib/features/chat/providers/chat_provider.dart` - Added startAcpChat(), debug logs
- `flutter/lib/features/sessions/providers/sessions_provider.dart` - Added debug logs

---

## 8. Test Commands Used

```bash
# Test backend directly
python3 -c "
import asyncio, websockets, json
async def test():
    async with websockets.connect('ws://localhost:4010/ws') as ws:
        await ws.send(json.dumps({'type': 'acp-list-sessions'}))
        resp = json.loads(await ws.recv())
        print(f'Sessions: {len(resp.get(\"sessions\", []))}')
asyncio.run(test())
"

# Check backend connections
lsof -i :4010

# View backend logs
tail -f /tmp/backend.log

# Capture Flutter logs
adb logcat -d | grep -i 'flutter'

# Install APK
adb install -r agentshell-flutter-debug.apk

# Query database
sqlite3 /opt/agentshell/backend/chat_events.db "SELECT session_name, COUNT(*) FROM chat_events GROUP BY session_name"
```

---

## 9. Recommended Next Steps

### Option A: Fix WebSocket Connection Management (Recommended)
1. Implement heartbeat/ping mechanism to detect dead connections
2. Add connection state verification before sending
3. Implement exponential backoff for reconnection
4. Add connection state indicator in UI

### Option B: Alternative Architecture
1. Use a shared WebSocket connection manager service
2. Implement connection pooling
3. Add automatic reconnection with state sync

### Option C: Quick Fix (Workaround)
1. Force reconnect on every user action (refresh)
2. Show "connecting" UI while reconnecting
3. Retry up to 3 times with delays

---

## 10. Database Reference

- **Location:** `/opt/agentshell/backend/chat_events.db`
- **Schema:** SQLite with `chat_events` table
- **Session Key Format:** `acp_{session_id}`
- **Sample Session:** `acp_ses_3255fc8ddffemybyrtsYDTs2kg` (206 messages)

### Query Examples

```bash
# List all sessions with message counts
sqlite3 /opt/agentshell/backend/chat_events.db \
  "SELECT session_name, COUNT(*) as msg_count FROM chat_events GROUP BY session_name ORDER BY msg_count DESC"

# Get messages for a specific session
sqlite3 /opt/agentshell/backend/chat_events.db \
  "SELECT message_json FROM chat_events WHERE session_name = 'acp_ses_3255fc8ddffemybyrtsYDTs2kg' LIMIT 5"
```

---

## 11. Versions/Environment

- **Backend:** Rust (agentshell-backend)
- **Flutter:** Dart with Riverpod state management
- **WebSocket Library:** `web_socket_channel` (Flutter)
- **Database:** SQLite (rusqlite)
- **Backend Port:** 4010
- **Test Device:** Android (192.168.0.120)
- **Build:** Debug APK

---

*Document created: March 11, 2026*
*Last updated: March 11, 2026*
