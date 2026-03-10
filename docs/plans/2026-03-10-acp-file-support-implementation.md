# ACP File Support Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add file/image/audio support to ACP chat by implementing send-file-to-acp-chat WebSocket message.

**Architecture:** New WebSocket message that saves file, persists to chat_event_store, broadcasts to Flutter, and sends prompt to ACP for AI context.

**Tech Stack:** Rust backend, Flutter frontend, WebSocket protocol

---

## Task 1: Add Backend WebSocket Message Type

**Files:**
- Modify: `backend-rust/src/types/mod.rs`

**Step 1: Add SendFileToAcpChat request type**

Find where `WebSocketMessage::SendFileToChat` is defined (around line 274), add after the ACP messages:

```rust
// ACP file sending
SendFileToAcpChat {
    #[serde(rename = "sessionId")]
    session_id: String,
    file: FileAttachment,
    prompt: Option<String>,
},
```

**Step 2: Run cargo check**

Run: `cd backend-rust && cargo check`

Expected: Compiles successfully

---

## Task 2: Implement Backend Handler

**Files:**
- Modify: `backend-rust/src/websocket/mod.rs`

**Step 1: Add handler for SendFileToAcpChat**

Find the match arm for `WebSocketMessage::SendFileToAcpChat`, add handler after the `AcpSendPrompt` handler (around line 1620):

```rust
WebSocketMessage::SendFileToAcpChat {
    session_id,
    file,
    prompt,
} => {
    info!(
        "Received file to send to ACP chat: {} ({})",
        file.filename, file.mime_type
    );

    // Save file to storage
    let file_id = match state
        .chat_file_storage
        .save_file(&file.data, &file.filename, &file.mime_type)
    {
        Ok(id) => id,
        Err(e) => {
            error!("Failed to save file for ACP chat: {}", e);
            return Ok(());
        }
    };

    // Create content block based on mime type
    let block = if file.mime_type.starts_with("image/") {
        crate::chat_log::ContentBlock::Image {
            id: file_id,
            mime_type: file.mime_type.clone(),
            alt_text: Some(file.filename.clone()),
        }
    } else if file.mime_type.starts_with("audio/") {
        crate::chat_log::ContentBlock::Audio {
            id: file_id,
            mime_type: file.mime_type.clone(),
            duration_seconds: None,
        }
    } else {
        crate::chat_log::ContentBlock::File {
            id: file_id,
            filename: file.filename.clone(),
            mime_type: file.mime_type.clone(),
            size_bytes: Some((file.data.len() as f64 * 0.75) as u64),
        }
    };

    // Build message blocks
    let mut blocks = Vec::new();
    let mut prompt_text = String::new();
    
    // Add prompt if provided
    if let Some(ref p) = prompt {
        if !p.trim().is_empty() {
            prompt_text = p.trim().to_string();
            blocks.push(crate::chat_log::ContentBlock::Text {
                text: p.trim().to_string(),
            });
        }
    }
    blocks.push(block);

    let chat_message = crate::chat_log::ChatMessage {
        role: "user".to_string(),
        timestamp: Some(chrono::Utc::now()),
        blocks,
    };

    // Persist to chat_event_store
    let session_key = format!("acp_{}", session_id);
    if let Err(e) = state.chat_event_store.append_message(
        &session_key,
        0,
        "webhook-file",
        &chat_message,
    ) {
        warn!("Failed to persist ACP file message: {}", e);
    }

    // Broadcast to all connected clients
    let msg = ServerMessage::ChatFileMessage {
        session_name: session_id.clone(),
        window_index: 0,
        message: chat_message,
    };
    state.client_manager.broadcast(msg).await;

    // Send prompt to ACP session so AI can see the file
    if !prompt_text.is_empty() || file.mime_type.starts_with("image/") {
        let file_ref = format!("[File: {}]", file.filename);
        let combined = if prompt_text.is_empty() {
            file_ref
        } else {
            format!("{}\n\n{}", prompt_text, file_ref)
        };
        
        let acp_guard = app_state.acp_client.read().await;
        if let Some(client) = acp_guard.as_ref() {
            if let Err(e) = client.send_prompt(&session_id, &combined).await {
                error!("Failed to send file prompt to ACP: {}", e);
            }
        }
    }
}
```

**Step 2: Run cargo check**

Run: `cd backend-rust && cargo check`

Expected: Compiles successfully

---

## Task 3: Add Flutter WebSocket Method

**Files:**
- Modify: `flutter/lib/data/services/websocket_service.dart`

**Step 1: Add sendFileToAcpChat method**

Find where `sendFileToChat` is defined, add after it:

```dart
void sendFileToAcpChat(String sessionId, String filename, String mimeType, String base64Data, {String? prompt}) {
  send({
    'type': 'send-file-to-acp-chat',
    'sessionId': sessionId,
    'file': {
      'filename': filename,
      'mimeType': mimeType,
      'data': base64Data,
    },
    'prompt': prompt,
  });
}
```

**Step 2: Run flutter analyze**

Run: `cd flutter && flutter analyze lib/data/services/websocket_service.dart`

Expected: No errors

---

## Task 4: Wire Up File Sending in Chat Screen

**Files:**
- Modify: `flutter/lib/features/chat/screens/chat_screen.dart`

**Step 1: Find file sending code**

Look for where `_sendFileWithPrompt` is called. For ACP mode, we need to use `sendFileToAcpChat` instead.

Find the file sending logic and add ACP support (similar to how `_submitMessage` checks `widget.isAcp`).

**Step 2: Run flutter analyze**

Run: `cd flutter && flutter analyze lib/features/chat/screens/chat_screen.dart`

Expected: No errors

---

## Task 5: Commit All Changes

**Step 1: Check status**

Run: `git status`

**Step 2: Stage and commit**

Run:
```bash
git add backend-rust/src/types/mod.rs backend-rust/src/websocket/mod.rs flutter/lib/data/services/websocket_service.dart flutter/lib/features/chat/screens/chat_screen.dart
git commit -m "feat: add file/image/audio support to ACP chat

- Add SendFileToAcpChat WebSocket message type
- Implement backend handler to save file, persist to chat_event_store, broadcast to Flutter
- Send prompt to ACP session so AI can see the file
- Add Flutter WebSocket method and wire up file picker"
```

---

## Verification

After implementation:
1. Open ACP chat session
2. Attach an image/file using the paperclip icon
3. Verify file appears in chat
4. Verify prompt is sent to ACP (AI can see the file reference)
5. Check database: messages should be persisted with `acp_{sessionId}` key
