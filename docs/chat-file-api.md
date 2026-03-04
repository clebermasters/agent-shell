# Chat File/Image/Audio API Documentation

This document describes how to send files, images, and audio to the WebMux chat from external processes (like OpenCode, scripts, or other AI agents).

---

## Overview

External processes can send files to a specific chat session via WebSocket. The files are stored on the backend and displayed in the Flutter app as interactive blocks.

### Supported File Types

| Type | MIME Prefixes | Display |
|------|---------------|---------|
| Images | `image/png`, `image/jpeg`, `image/gif`, `image/webp` | Tap to expand |
| Audio | `audio/mpeg`, `audio/wav`, `audio/ogg` | Play button with duration |
| Files | All other types | Download button with file info |

---

## WebSocket Connection

### Connect to WebSocket

```
ws://<server-address>/ws
```

### Authentication

No authentication required for WebSocket connection (uses same auth as HTTP).

---

## Send File to Chat

### Message Format

Send a JSON message with type `send-file-to-chat`:

```json
{
  "type": "send-file-to-chat",
  "sessionName": "your-session-name",
  "windowIndex": 0,
  "file": {
    "filename": "screenshot.png",
    "mimeType": "image/png",
    "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
  }
}
```

### Parameters

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionName` | string | Yes | The tmux session name |
| `windowIndex` | number | Yes | The window index (usually 0) |
| `file.filename` | string | Yes | Original filename (used for display) |
| `file.mimeType` | string | Yes | MIME type (e.g., "image/png", "audio/mpeg") |
| `file.data` | string | Yes | Base64-encoded file content |

---

## Examples

### Python Example

```python
import asyncio
import json
import base64
import mimetypes
import websockets

async def send_file_to_chat(
    server_url: str,
    session_name: str,
    window_index: int,
    file_path: str
):
    """Send a file to the WebMux chat session."""
    
    # Detect MIME type
    mime_type = mimetypes.guess_type(file_path)[0] or 'application/octet-stream'
    
    # Read and encode file
    with open(file_path, 'rb') as f:
        file_data = base64.b64encode(f.read()).decode('utf-8')
    
    filename = file_path.split('/')[-1]
    
    # Build message
    message = {
        "type": "send-file-to-chat",
        "sessionName": session_name,
        "windowIndex": window_index,
        "file": {
            "filename": filename,
            "mimeType": mime_type,
            "data": file_data
        }
    }
    
    # Send via WebSocket
    async with websockets.connect(server_url) as websocket:
        await websocket.send(json.dumps(message))
        print(f"Sent {filename} to chat")

# Usage
asyncio.run(send_file_to_chat(
    server_url="ws://localhost:8080/ws",
    session_name="my-project",
    window_index=0,
    file_path="/tmp/screenshot.png"
))
```

### Node.js Example

```javascript
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const mime = require('mime-types');

function sendFileToChat(serverUrl, sessionName, windowIndex, filePath) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(serverUrl);
    
    ws.on('open', () => {
      const filename = path.basename(filePath);
      const mimeType = mime.lookup(filePath) || 'application/octet-stream';
      const fileData = fs.readFileSync(filePath).toString('base64');
      
      const message = {
        type: 'send-file-to-chat',
        sessionName,
        windowIndex,
        file: {
          filename,
          mimeType,
          data: fileData
        }
      };
      
      ws.send(JSON.stringify(message));
      console.log(`Sent ${filename} to chat`);
      ws.close();
      resolve();
    });
    
    ws.on('error', reject);
  });
}

// Usage
sendFileToChat(
  'ws://localhost:8080/ws',
  'my-project',
  0,
  '/tmp/screenshot.png'
).catch(console.error);
```

### cURL Example (via HTTP)

If you prefer HTTP over WebSocket, you can use this endpoint:

```bash
curl -X POST http://localhost:8080/api/chat/send-file \
  -H "Content-Type: application/json" \
  -d '{
    "sessionName": "my-project",
    "windowIndex": 0,
    "filename": "screenshot.png",
    "mimeType": "image/png",
    "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
  }'
```

---

## Common Use Cases

### 1. OpenCode Skill

Create a skill in OpenCode to send screenshots:

```python
# ~/.opencode/skills/send_screenshot.py
import asyncio
import websockets
import json
import base64
import subprocess

async def send_screenshot(session_name: str, window_index: int = 0):
    # Take screenshot
    subprocess.run(['gnome-screenshot', '-f', '/tmp/screenshot.png'])
    
    # Send to chat
    await send_file_to_chat(
        server_url="ws://localhost:8080/ws",
        session_name=session_name,
        window_index=window_index,
        file_path="/tmp/screenshot.png"
    )
```

### 2. AI Agent File Sharing

When AI generates a file, send it to the chat:

```python
async def on_ai_file_created(file_path: str, session_name: str):
    """Called when AI creates a file (e.g., generated diagram, report)."""
    await send_file_to_chat(
        server_url="ws://localhost:8080/ws",
        session_name=session_name,
        window_index=0,
        file_path=file_path
    )
```

### 3. Notification with Attachment

Send notifications with files:

```python
async def send_notification(session_name: str, message: str, attachment_path: str = None):
    """Send a notification to the chat, optionally with a file."""
    # First send the text message (existing functionality)
    # Then send the file if provided
    if attachment_path:
        await send_file_to_chat(
            server_url="ws://localhost:8080/ws",
            session_name=session_name,
            window_index=0,
            file_path=attachment_path
        )
```

---

## File Size Limits

- **Maximum file size**: 10 MB
- **Recommended size**: < 2 MB for better performance

If you need to send larger files, consider:
1. Compressing images before sending
2. Sending a link instead of the file itself
3. Chunking the file into smaller pieces

---

## Error Handling

### Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| Invalid session name | Session doesn't exist | Verify tmux session name |
| File too large | Exceeds 10MB limit | Compress or chunk the file |
| Invalid base64 | Malformed data | Ensure proper base64 encoding |
| Invalid MIME type | Type not recognized | Use standard MIME types |

### Connection Issues

```python
import asyncio

async def send_with_retry(max_retries=3):
    for attempt in range(max_retries):
        try:
            await send_file_to_chat(...)
            return
        except Exception as e:
            if attempt == max_retries - 1:
                raise e
            await asyncio.sleep(1)  # Wait before retry
```

---

## Testing

### Test with a Simple Image

```bash
# Create a test image
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==" | base64 -d > test.png

# Send via WebSocket (Python)
python3 -c "
import asyncio
import websockets
import json
import base64

async def test():
    with open('test.png', 'rb') as f:
        data = base64.b64encode(f.read()).decode()
    
    msg = {
        'type': 'send-file-to-chat',
        'sessionName': 'test-session',
        'windowIndex': 0,
        'file': {
            'filename': 'test.png',
            'mimeType': 'image/png',
            'data': data
        }
    }
    
    async with websockets.connect('ws://localhost:8080/ws') as ws:
        await ws.send(json.dumps(msg))
        print('Sent!')

asyncio.run(test())
"
```

---

## Notes

1. **Session must exist**: The tmux session must be active for the message to be delivered
2. **All clients receive**: All Flutter app instances connected to that session will see the file
3. **Files are local**: Files are stored on the backend server, not cloud storage
4. **Base64 overhead**: Base64 encoding increases size by ~33%

---

## Related Documentation

- [Chat File/Media Support Plan](../plans/chat-file-media-support.md)
- [WebMux Backend Architecture](../architecture/backend.md)
- [OpenCode Integration](./opencode-integration.md)
