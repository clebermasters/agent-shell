# ACP Chat History Redesign

**Date:** 2026-03-11  
**Status:** Approved

## Problem

The current `WatchAcpChatLog` handler mirrors all OpenCode DB messages into the AgentShell SQLite DB. Because `last_time_updated` is initialized to `0` on every connection, the polling loop re-fetches all messages from the beginning on every tick, creating 461 duplicates per second and flooding Flutter with redundant `chat-event` messages.

## Root Cause

`OpencodeState.last_time_updated = 0` → `fetch_new_messages` returns all 461 messages every 1s tick → each is persisted to AgentShell DB (duplicates) and sent to Flutter (flood).

## Design: Option B — OpenCode DB as Primary, AgentShell DB as Overlay

### Principle

- **OpenCode DB** is the source of truth for all AI conversation (user prompts, assistant responses, tool calls).
- **AgentShell DB** stores only messages that OpenCode does not know about: webhook text messages and webhook file messages.
- The polling loop forwards new OpenCode messages directly to Flutter — no persistence.

### Data Flow

#### `watch-acp-chat-log`

```
1. find_opencode_db()
2. fetch_all_messages(opencode_db, session_id)
   → Vec<ChatMessage> sorted by time_updated ASC
   → max_time_updated (for poll initialization)
3. get_acp_overlay(agentshell_db, "acp_{session_id}", cleared_at)
   → webhook/file messages only (source IN 'webhook','webhook-file')
   → filtered by cleared_at if set
4. merge both lists by timestamp → send ChatHistory
5. poll OpenCode DB every 1s from max_time_updated:
   → fetch_new_messages() → send ChatEvent directly (NO persistence)
```

#### `acp-clear-history`

```
- clear_messages("acp_{session_id}", 0)  ← clears overlay only
- set cleared_at = now in chat_clear_store
- send ChatLogCleared to client
```

**Bug fixed:** Flutter was sending `state.sessionName = "acp_ses_..."` to `clearAcpHistory`, causing the backend to double-prefix it as `"acp_acp_ses_..."`. Fix: Flutter strips `"acp_"` prefix before sending.

#### `acp-delete-session`

Unchanged — already correct:
- `clear_messages("acp_{session_id}", 0)`
- `mark_acp_session_deleted(session_id)`
- Send `AcpSessionDeleted`

#### `acp-send-prompt`

Remove `append_message()` call — OpenCode DB will have the user message; Flutter adds it locally immediately.

#### `send-file-to-acp-chat` / `send-chat-message` (webhook)

Unchanged — continue persisting to AgentShell DB with `source: 'webhook'/'webhook-file'`.

## Files Changed

| File | Change |
|------|--------|
| `backend-rust/src/chat_log/opencode_parser.rs` | Add `fetch_all_messages(db, session_id, cleared_at) → (Vec<ChatMessage>, i64)` |
| `backend-rust/src/chat_event_store.rs` | Add `get_acp_overlay(session_id, cleared_at)`, remove `get_acp_history` |
| `backend-rust/src/websocket/mod.rs` | Rewrite `WatchAcpChatLog`, fix `AcpClearHistory`, simplify `AcpSendPrompt` |
| `flutter/lib/features/chat/providers/chat_provider.dart` | Fix `clear()` to strip `acp_` prefix |
