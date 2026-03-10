# ACP Chat History Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement chat history persistence for ACP backend so messages are saved and loaded when user returns to chat.

**Architecture:** Hybrid approach - backend persists messages to SQLite (reusing existing chat_event_store), frontend caches locally for quick re-entry, paginated loading.

**Tech Stack:** Rust backend (websocket, chat_event_store), Flutter frontend (Riverpod, WebSocket service)

---

## Task 1: Add Backend Types

**Files:**
- Modify: `backend-rust/src/types/mod.rs`

**Step 1: Add AcpLoadHistory request type**

Find where `AcpRespondPermission` is defined (around line 337), add after it:
```rust
AcpLoadHistory {
    #[serde(rename = "sessionId")]
    session_id: String,
    offset: Option<usize>,
    limit: Option<usize>,
},
```

**Step 2: Add AcpHistoryLoaded response type**

Find where `AcpPermissionResponse` is defined (around line 659), add after it:
```rust
AcpHistoryLoaded {
    #[serde(rename = "sessionId")]
    session_id: String,
    messages: Vec<crate::chat_log::ChatMessage>,
    has_more: bool,
},
```

**Step 3: Run cargo check**

Run: `cd backend-rust && cargo check`
Expected: Compiles successfully

---

## Task 2: Add WebSocket Service Method

**Files:**
- Modify: `flutter/lib/data/services/websocket_service.dart`

**Step 1: Add acpLoadHistory method**

Find where `acpSetMode` is defined (line 357), add after it:
```dart
void acpLoadHistory(String sessionId, {int offset = 0, int limit = 50}) {
  send({
    'type': 'acp-load-history',
    'sessionId': sessionId,
    'offset': offset,
    'limit': limit,
  });
}
```

**Step 2: Verify no syntax errors**

Run: `flutter analyze lib/data/services/websocket_service.dart`
Expected: No errors

---

## Task 3: Implement Backend Load History Handler

**Files:**
- Modify: `backend-rust/src/websocket/mod.rs`

**Step 1: Add handler for AcpLoadHistory**

Find the `handle_message` function match arm for `WebSocketMessage::AcpRespondPermission` (around line 1680), add before the closing brace of the match:
```rust
WebSocketMessage::AcpLoadHistory { session_id, offset, limit } => {
    info!("ACP load history: {} offset={:?} limit={:?}", session_id, offset, limit);
    
    let acp_guard = app_state.acp_client.read().await;
    if let Some(_client) = acp_guard.as_ref() {
        let offset = offset.unwrap_or(0);
        let limit = limit.unwrap_or(50);
        
        // Use acp_{sessionId} as session key
        let session_key = format!("acp_{}", session_id);
        
        let messages = match app_state.chat_event_store.list_messages(&session_key, 0) {
            Ok(msgs) => msgs,
            Err(e) => {
                error!("Failed to load ACP history: {}", e);
                let response = ServerMessage::AcpError {
                    message: format!("Failed to load history: {}", e),
                };
                return send_message(&state.message_tx, response).await;
            }
        };
        
        // Apply pagination
        let total = messages.len();
        let has_more = offset + limit < total;
        let paginated: Vec<_> = messages.into_iter().skip(offset).take(limit).collect();
        
        let response = ServerMessage::AcpHistoryLoaded {
            session_id,
            messages: paginated,
            has_more,
        };
        send_message(&state.message_tx, response).await?;
    } else {
        let response = ServerMessage::AcpError {
            message: "ACP client not initialized".to_string(),
        };
        send_message(&state.message_tx, response).await?;
    }
}
```

**Step 2: Run cargo check**

Run: `cd backend-rust && cargo check`
Expected: Compiles successfully

---

## Task 4: Persist ACP Messages on Receive

**Files:**
- Modify: `backend-rust/src/websocket/mod.rs`

**Step 1: Modify AcpMessageChunk handling to persist messages**

Find where `AcpMessageChunk` is handled (around line 1364). The current code broadcasts but doesn't persist. Modify to:

```rust
crate::acp::SessionUpdate::AgentMessageChunk { content } => {
    let text = content.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
    
    // Persist the assistant message
    let session_key = format!("acp_{}", session_id);
    let chat_message = crate::chat_log::ChatMessage {
        role: "assistant".to_string(),
        timestamp: Some(chrono::Utc::now()),
        blocks: vec![crate::chat_log::ContentBlock::Text { text: text.clone() }],
    };
    if let Err(e) = app_state.chat_event_store.append_message(
        &session_key,
        0,
        "acp",
        &chat_message,
    ) {
        tracing::warn!("Failed to persist ACP message: {}", e);
    }
    
    ServerMessage::AcpMessageChunk {
        session_id: session_id.clone(),
        content: text,
        is_thinking: false,
    }
}
```

**Step 2: Also persist user messages when sending**

Find where `WebSocketMessage::AcpSendPrompt` is handled (around line 1572). After sending to ACP, persist the user message:

```rust
WebSocketMessage::AcpSendPrompt { session_id, message } => {
    info!("ACP send prompt to {}: {}", session_id, message);
    
    // Persist user message first
    let session_key = format!("acp_{}", session_id);
    let chat_message = crate::chat_log::ChatMessage {
        role: "user".to_string(),
        timestamp: Some(chrono::Utc::now()),
        blocks: vec![crate::chat_log::ContentBlock::Text { text: message.clone() }],
    };
    if let Err(e) = app_state.chat_event_store.append_message(
        &session_key,
        0,
        "acp",
        &chat_message,
    ) {
        tracing::warn!("Failed to persist user message: {}", e);
    }
    
    // ... rest of existing code
}
```

**Step 3: Run cargo check**

Run: `cd backend-rust && cargo check`
Expected: Compiles successfully

---

## Task 5: Add Flutter Handler for History Response

**Files:**
- Modify: `flutter/lib/features/chat/providers/chat_provider.dart`

**Step 1: Add handler for acp-history-loaded**

Find where `_handleChatHistory` is defined (around line 155). Add new method after it:
```dart
void _handleAcpHistoryLoaded(Map<String, dynamic> message) {
  try {
    final sessionId = message['sessionId'] as String?;
    if (sessionId != state.sessionName) {
      print('DEBUG: Ignoring history for session $sessionId (current: ${state.sessionName})');
      return;
    }

    final messagesData = message['messages'] as List<dynamic>? ?? [];
    final hasMore = message['hasMore'] as bool? ?? false;

    final messages = messagesData
        .map((msg) => _parseMessage(msg as Map<String, dynamic>))
        .toList();

    state = state.copyWith(
      messages: messages,
      isLoading: false,
      hasMoreMessages: hasMore,
    );
    print('DEBUG: ACP history loaded: ${messages.length} messages, hasMore: $hasMore');
  } catch (e, stack) {
    print('ERROR parsing ACP history: $e\n$stack');
    state = state.copyWith(
      isLoading: false,
      error: 'Failed to parse history',
    );
  }
}
```

**Step 2: Register the handler in _init**

Find where other handlers are registered (around line 113), add:
```dart
'acp-history-loaded': (message) => _handleAcpHistoryLoaded(message),
```

**Step 3: Run flutter analyze**

Run: `cd flutter && flutter analyze lib/features/chat/providers/chat_provider.dart`
Expected: No errors

---

## Task 6: Load History on ACP Chat Entry

**Files:**
- Modify: `flutter/lib/features/chat/providers/chat_provider.dart`

**Step 1: Load history in startAcpChat**

Find the `startAcpChat` method. After setting up the message handler, add history loading:

```dart
Future<void> startAcpChat(String sessionId) async {
  state = state.copyWith(
    sessionName: sessionId,
    windowIndex: 0,
    isLoading: true,
    messages: [],
    error: null,
  );

  _ws!.acpLoadHistory(sessionId, offset: 0, limit: 50);
}
```

**Step 2: Run flutter analyze**

Run: `cd flutter && flutter analyze lib/features/chat/providers/chat_provider.dart`
Expected: No errors

---

## Task 7: Commit All Changes

**Step 1: Check status**

Run: `git status`

**Step 2: Stage and commit**

Run:
```bash
git add backend-rust/src/types/mod.rs backend-rust/src/websocket/mod.rs flutter/lib/data/services/websocket_service.dart flutter/lib/features/chat/providers/chat_provider.dart
git commit -m "feat: add ACP chat history persistence

- Add AcpLoadHistory request and AcpHistoryLoaded response types
- Add acpLoadHistory method to Flutter WebSocket service
- Implement backend handler to load paginated history from chat_event_store
- Persist ACP messages (user and assistant) to SQLite
- Load history automatically when entering ACP chat
- Use acp_{sessionId} as session key format"
```

Expected: Commit successful

---

## Verification

After implementation:
1. Create new ACP session, send messages
2. Leave chat, return - history should load
3. Send more messages, verify persistence
4. Check database: `SELECT * FROM chat_events WHERE session_name LIKE 'acp_%';`
