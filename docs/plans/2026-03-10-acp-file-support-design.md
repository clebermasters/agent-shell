# ACP File Support Design

## Problem

The ACP chat mode doesn't support sending files (images, audio, documents) to the chat, unlike the tmux mode which has this feature via webhooks.

## Solution

Implement file sending capability for ACP chat similar to tmux, using a WebSocket message that:
1. Receives file data from Flutter
2. Saves file to storage
3. Creates ChatMessage with ContentBlock (Image/Audio/File)
4. Persists to chat_event_store
5. Broadcasts ChatFileMessage to Flutter
6. Sends prompt to ACP session (so AI can see the file)

## Architecture

```
Flutter App ──▶ WebSocket: send-file-to-acp-chat ──▶ Backend
                                                    │
                                                    ├── Save file to storage
                                                    ├── Persist to chat_event_store (key: acp_{sessionId})
                                                    ├── Broadcast ChatFileMessage to Flutter
                                                    └── Send prompt to ACP session (for AI context)
```

## Data Flow

### WebSocket Message (Flutter → Backend)
```json
{
  "type": "send-file-to-acp-chat",
  "sessionId": "ses_xxx",
  "file": {
    "filename": "image.png",
    "mimeType": "image/png",
    "data": "base64..."
  },
  "prompt": "optional description"
}
```

### Backend Processing
1. Parse message, extract sessionId, file data, prompt
2. Save file to chat_file_storage (get file_id)
3. Determine ContentBlock type based on mime_type:
   - image/* → ContentBlock::Image
   - audio/* → ContentBlock::Audio
   - other → ContentBlock::File
4. Build ChatMessage with blocks
5. Persist to chat_event_store with key `acp_{sessionId}`
6. Broadcast ChatFileMessage to all connected clients
7. Send prompt to ACP session with file reference (for AI context)

### Frontend
- Reuse existing file picker UI from tmux mode
- Add sendFileToAcpChat method to WebSocketService
- ChatFileMessage handler already exists and handles display
