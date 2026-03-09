# ACP Chat History Design

## Problem

When using ACP (OpenCode's Agentic Communication Protocol) backend, chat history is not persisted. Users lose their conversation when leaving and returning to the chat. The tmux backend already has working chat history via backend persistence.

## Solution

Implement hybrid persistence for ACP chat:
- Backend stores messages in SQLite (same `chat_event_store`)
- Frontend caches locally for quick re-entry
- Paginated loading when entering chat

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Flutter UI    │────▶│   Rust Backend  │────▶│  chat_event_store│
│                 │     │                 │     │   (SQLite)      │
│ - Cache locally │     │ - Persist msgs  │     │                 │
│ - Load history │     │ - Load paginated│     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
        │                       │
        │ AcpSendPrompt         │ AcpMessageChunk
        ▼                       ▼
┌─────────────────────────────────────┐
│          opencode ACP                │
│  (real-time message streaming)      │
└─────────────────────────────────────┘
```

## Data Flow

### Sending Messages
1. User types message in Flutter
2. `ws.acpSendPrompt(sessionId, message)` → Backend
3. Backend forwards to opencode ACP
4. Responses stream back via `AcpMessageChunk`
5. Backend persists each message to `chat_event_store` (key: `acp_{sessionId}`)
6. Backend broadcasts to all clients

### Loading History
1. User opens ACP chat
2. Flutter sends `ws.acpLoadHistory(sessionId, offset, limit)`
3. Backend reads from `chat_event_store` for `acp_{sessionId}`
4. Backend returns paginated messages
5. Flutter displays and caches locally

## Backend Changes

### 1. New WebSocket Message Type

**Request:**
```json
{"type": "acp-load-history", "sessionId": "ses_xxx", "offset": 0, "limit": 50}
```

**Response:**
```json
{
  "type": "acp-history-loaded",
  "sessionId": "ses_xxx",
  "messages": [...],
  "hasMore": true
}
```

### 2. Persist ACP Messages

In `websocket/mod.rs`, modify the `AcpMessageChunk` handler:
- Extract role (user/assistant) from message content
- Persist to `chat_event_store` with key format `acp_{sessionId}`
- Use same message format as tmux for consistency

### 3. Load History Handler

New handler for `AcpLoadHistory`:
- Read from `chat_event_store` using `acp_{sessionId}` key
- Return paginated results
- Return `hasMore` if more messages exist

## Frontend Changes

### 1. WebSocket Service

Add to `websocket_service.dart`:
```dart
void acpLoadHistory(String sessionId, int offset, int limit) {
  send({
    'type': 'acp-load-history',
    'sessionId': sessionId,
    'offset': offset,
    'limit': limit
  });
}
```

### 2. Chat Provider

- Add handler for `acp-history-loaded` message
- Load history when entering ACP chat (in `startAcpChat`)
- Cache messages in state
- Support pagination (load more on scroll)

### 3. Session Key Format

Use format `acp_{sessionId}` to distinguish from tmux sessions.

## Database Schema

Reuse existing `chat_events` table:
- `session_name`: `acp_{sessionId}` (e.g., `acp_ses_33baa6653ffespDOXr19dfoxuR`)
- `window_index`: 0 (always 0 for ACP)
- `message`: JSON-serialized chat message

## Error Handling

- If history load fails: show error, allow retry
- If persistence fails: log error, continue (don't block user)
- If ACP not initialized: return appropriate error

## Testing

1. Create ACP session, send messages
2. Leave chat, return - verify history loads
3. Send more messages, verify persistence
4. Test pagination (load more)
5. Verify hybrid: backend persisted + frontend cached
