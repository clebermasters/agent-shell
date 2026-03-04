# Chat File/Image/Audio Support Implementation Plan

## Overview

Add support for sending and displaying files, images, and audio in the Flutter chat app. This enables external processes (like OpenCode) to send any type of content to a specific chat session via a webhook/API.

---

## Goals

1. **Display images** in chat with tap-to-expand functionality
2. **Play audio** files directly in chat
3. **Show file attachments** with download capability
4. **API endpoint** for external processes to send files to chat sessions

---

## Use Cases

### 1. OpenCode Integration
OpenCode can invoke a skill/webhook to send:
- Screenshots taken during AI work
- Generated files (PDF, HTML, etc.)
- Audio explanations
- Any file from the AI session

### 2. External Process Integration
Any external script/process can:
- Monitor tmux sessions
- Send notifications with attachments
- Share files between sessions

### 3. Direct File Sharing
Users can upload files directly in the chat UI.

---

## Architecture

### Message Flow

```
┌─────────────────┐     WebSocket/HTTP      ┌──────────────┐
│  External       │ ──────────────────────▶ │   Backend    │
│  Process        │   send-file-to-chat      │   (Rust)     │
│  (OpenCode)     │                         └──────┬───────┘
└─────────────────┘                                │
                                                    │ Broadcast
                                                    ▼
┌─────────────────┐     WebSocket      ┌──────────────────┐
│   Flutter App   │ ◀───────────────────│   WebSocket     │
│   (Display)     │   chat-file-message │   Handler        │
└─────────────────┘                     └──────────────────┘
```

---

## Implementation Details

### Phase 1: Backend Changes

#### 1.1 Update ContentBlock Enum

**File**: `backend-rust/src/chat_log/mod.rs`

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum ContentBlock {
    Text { text: String },
    Thinking { content: String },
    ToolCall { name: String, summary: String, input: Option<Value> },
    ToolResult { tool_name: String, summary: String, content: Option<String> },
    // NEW
    Image {
        id: String,           // Unique file ID
        mime_type: String,    // image/png, image/jpeg, etc.
        alt_text: Option<String>,
    },
    Audio {
        id: String,           // Unique file ID
        mime_type: String,    // audio/mpeg, audio/wav, etc.
        duration_seconds: Option<f32>,
    },
    File {
        id: String,           // Unique file ID
        filename: String,     // Original filename
        mime_type: String,    // application/pdf, etc.
        size_bytes: Option<u64>,
    },
}
```

#### 1.2 Add File Storage

**File**: `backend-rust/src/chat_file.rs` (new)

```rust
use std::path::PathBuf;
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

pub struct ChatFileStorage {
    storage_dir: PathBuf,
}

impl ChatFileStorage {
    pub fn new(base_dir: PathBuf) -> Self {
        let storage_dir = base_dir.join("chat_files");
        std::fs::create_dir_all(&storage_dir).ok();
        Self { storage_dir }
    }

    pub fn save_file(&self, data: &str, filename: &str, mime_type: &str) -> Result<String, String> {
        let id = uuid::Uuid::new_v4().to_string();
        let extension = mime_type.split('/').nth(1).unwrap_or("bin");
        let file_path = self.storage_dir.join(format!("{}.{}", id, extension));
        
        let decoded = BASE64.decode(data)
            .map_err(|e| format!("Failed to decode base64: {}", e))?;
        
        std::fs::write(&file_path, decoded)
            .map_err(|e| format!("Failed to write file: {}", e))?;
        
        Ok(id)
    }

    pub fn get_path(&self, id: &str) -> Option<PathBuf> {
        // Try common extensions
        for ext in &["png", "jpg", "jpeg", "gif", "webp", "pdf", "mp3", "wav", "ogg"] {
            let path = self.storage_dir.join(format!("{}.{}", id, ext));
            if path.exists() {
                return Some(path);
            }
        }
        None
    }
}
```

#### 1.3 Add WebSocket Message Types

**File**: `backend-rust/src/types/mod.rs`

```rust
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum WebSocketMessage {
    // ... existing ...
    
    // NEW: Send file to chat from external process
    SendFileToChat {
        session_name: String,
        window_index: u32,
        file: FileAttachment,
    },
}

#[derive(Debug, Deserialize)]
pub struct FileAttachment {
    pub filename: String,
    pub mime_type: String,
    pub data: String,  // base64 encoded
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ServerMessage {
    // ... existing ...
    
    // NEW: File message broadcast
    ChatFileMessage {
        session_name: String,
        window_index: u32,
        message: ChatMessage,
    },
}
```

#### 1.4 Handle WebSocket Message

**File**: `backend-rust/src/websocket/mod.rs`

```rust
match msg_type.as_str() {
    // ... existing ...
    
    "send-file-to-chat" => {
        handle_send_file_to_chat(ws, &msg).await;
    }
}
```

```rust
async fn handle_send_file_to_chat(
    ws: &WebSocket<WebSocketStream<TokioIo<Upgraded>>>,
    msg: &WebSocketMessage,
) {
    if let WebSocketMessage::SendFileToChat { session_name, window_index, file } = msg {
        // Save file to storage
        let file_id = chat_files.save_file(&file.data, &file.filename, &file.mime_type);
        
        // Create content block based on mime type
        let block = match file.mime_type.starts_with("image/") {
            true => ContentBlock::Image {
                id: file_id,
                mime_type: file.mime_type.clone(),
                alt_text: Some(file.filename.clone()),
            },
            true if file.mime_type.starts_with("audio/") => ContentBlock::Audio {
                id: file_id,
                mime_type: file.mime_type.clone(),
                duration_seconds: None,
            },
            _ => ContentBlock::File {
                id: file_id,
                filename: file.filename.clone(),
                mime_type: file.mime_type.clone(),
                size_bytes: Some(file.data.len() as u64 * 3/4), // approximate
            },
        };
        
        let chat_message = ChatMessage {
            role: "assistant".to_string(),
            timestamp: Utc::now(),
            blocks: vec![block],
        };
        
        // Broadcast to all connected clients
        broadcast_to_session(
            ws,
            &session_name,
            window_index,
            ServerMessage::ChatFileMessage {
                session_name: session_name.clone(),
                window_index: *window_index,
                message: chat_message,
            },
        ).await;
    }
}
```

#### 1.5 HTTP Endpoint Alternative

**File**: `backend-rust/src/api/mod.rs` (or new file)

```rust
use actix_web::{post, web, HttpResponse};

#[post("/api/chat/send-file")]
async fn send_file_to_chat(
    req: HttpRequest,
    payload: web::Json<SendFileRequest>,
) -> HttpResponse {
    // Similar to WebSocket handler but via HTTP
    // ... implementation
}

#[derive(Deserialize)]
pub struct SendFileRequest {
    session_name: String,
    window_index: u32,
    filename: String,
    mime_type: String,
    data: String,  // base64
}
```

---

### Phase 2: Flutter Model Changes

#### 2.1 Update ChatBlockType Enum

**File**: `flutter/lib/data/models/chat_message.dart`

```dart
enum ChatBlockType {
  text,
  toolCall,
  toolResult,
  thinking,
  // NEW
  image,
  audio,
  file,
}
```

#### 2.2 Update ChatBlock

```dart
class ChatBlock extends Equatable {
  final ChatBlockType type;
  final String? text;
  final String? content;
  // ... existing fields ...
  
  // NEW: Media fields
  final String? id;
  final String? mimeType;
  final String? altText;
  final String? filename;
  final int? sizeBytes;
  final double? durationSeconds;
  
  factory ChatBlock.image({
    required String id,
    required String mimeType,
    String? altText,
  }) => ChatBlock._(
    type: ChatBlockType.image,
    id: id,
    mimeType: mimeType,
    altText: altText,
  );
  
  factory ChatBlock.audio({
    required String id,
    required String mimeType,
    double? durationSeconds,
  }) => ChatBlock._(
    type: ChatBlockType.audio,
    id: id,
    mimeType: mimeType,
    durationSeconds: durationSeconds,
  );
  
  factory ChatBlock.file({
    required String id,
    required String filename,
    required String mimeType,
    int? sizeBytes,
  }) => ChatBlock._(
    type: ChatBlockType.file,
    id: id,
    filename: filename,
    mimeType: mimeType,
    sizeBytes: sizeBytes,
  );
}
```

---

### Phase 3: Flutter Dependencies

**File**: `flutter/pubspec.yaml`

```yaml
dependencies:
  # Existing
  flutter:
    sdk: flutter
  dio: ^5.8.0+1
  flutter_markdown: ^0.7.7+1
  # ... existing ...
  
  # NEW - Add these
  cached_network_image: ^3.3.1    # Image caching & display
  just_audio: ^0.9.36             # Audio playback
  file_picker: ^8.0.0             # File selection
  path_provider: ^2.1.5           # File storage paths
  mime: ^1.0.5                    # MIME type detection
```

---

### Phase 4: Flutter Service Layer

#### 4.1 ChatFileService

**File**: `flutter/lib/data/services/chat_file_service.dart` (new)

```dart
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';
import '../config/app_config.dart';

class ChatFileService {
  final Dio _dio = Dio();
  
  Future<String> getLocalFilePath(String fileId) async {
    final dir = await getApplicationDocumentsDirectory();
    final fileDir = Directory('${dir.path}/chat_files');
    if (!await fileDir.exists()) {
      await fileDir.create(recursive: true);
    }
    
    // Try common extensions
    for (final ext in ['png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf', 'mp3', 'wav', 'ogg']) {
      final path = '${fileDir.path}/$fileId.$ext';
      if (await File(path).exists()) {
        return path;
      }
    }
    throw Exception('File not found: $fileId');
  }
  
  Future<void> downloadFile(String fileId, String savePath) async {
    await _dio.download(
      '${AppConfig.wsUrl}/api/chat/files/$fileId',
      savePath,
    );
  }
  
  Future<String?> uploadFile(String filePath) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
    });
    final response = await _dio.post(
      '${AppConfig.wsUrl}/api/chat/send-file',
      data: formData,
    );
    return response.data['message_id'];
  }
}
```

---

### Phase 5: Flutter UI - Message Bubble

#### 5.1 Add Rendering Cases

**File**: `flutter/lib/features/chat/widgets/professional_message_bubble.dart`

```dart
Widget _buildMessageContent(...) {
  if (widget.message.blocks.isNotEmpty) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: widget.message.blocks.map((block) {
        switch (block.type) {
          case ChatBlockType.text:
            return _buildMarkdownBlock(block.text ?? '', textColor, isUser);
          case ChatBlockType.thinking:
            return _buildThinkingBlock(block.content ?? '', textColor);
          case ChatBlockType.toolCall:
            return _buildToolCallCard(block);
          case ChatBlockType.toolResult:
            return _buildToolResultCard(block);
          // NEW
          case ChatBlockType.image:
            return _buildImageBlock(block);
          case ChatBlockType.audio:
            return _buildAudioBlock(block);
          case ChatBlockType.file:
            return _buildFileBlock(block);
        }
      }).toList(),
    );
  }
  // ...
}
```

#### 5.2 Image Block Widget

```dart
Widget _buildImageBlock(ChatBlock block) {
  return Padding(
    padding: const EdgeInsets.only(top: 8),
    child: GestureDetector(
      onTap: () => _showImageFullScreen(block.id),
      child: Hero(
        tag: 'image_${block.id}',
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Image.file(
            File(_getLocalFilePath(block.id)),
            width: 200,
            height: 200,
            fit: BoxFit.cover,
            errorBuilder: (context, error, stack) => Container(
              width: 200,
              height: 200,
              color: Colors.grey[300],
              child: Icon(Icons.broken_image, size: 48),
            ),
          ),
        ),
      ),
    ),
  );
}

void _showImageFullScreen(String? imageId) {
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (context) => Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          backgroundColor: Colors.transparent,
          iconTheme: IconThemeData(color: Colors.white),
        ),
        body: Center(
          child: Hero(
            tag: 'image_$imageId',
            child: InteractiveViewer(
              child: Image.file(File(_getLocalFilePath(imageId))),
            ),
          ),
        ),
      ),
    ),
  );
}
```

#### 5.3 Audio Block Widget

```dart
Widget _buildAudioBlock(ChatBlock block) {
  return Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Container(
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1E293B) : const Color(0xFFF1F5F9),
        borderRadius: BorderRadius.circular(12),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: Icon(
              _isPlaying ? Icons.pause_circle : Icons.play_circle,
              color: isDark ? const Color(0xFF6EE7B7) : const Color(0xFF047857),
            ),
            iconSize: 36,
            onPressed: () => _toggleAudioPlayback(block.id),
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Audio',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: textColor,
                ),
              ),
              if (block.durationSeconds != null)
                Text(
                  _formatDuration(block.durationSeconds!),
                  style: TextStyle(
                    fontSize: 10,
                    color: textColor.withValues(alpha: 0.6),
                  ),
                ),
            ],
          ),
        ],
      ),
    ),
  );
}

String _formatDuration(double seconds) {
  final mins = (seconds / 60).floor();
  final secs = (seconds % 60).floor();
  return '${mins.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
}
```

#### 5.4 File Block Widget

```dart
Widget _buildFileBlock(ChatBlock block) {
  return Padding(
    padding: const EdgeInsets.only(top: 8),
    child: InkWell(
      onTap: () => _downloadAndOpenFile(block),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        decoration: BoxDecoration(
          color: isDark ? const Color(0xFF1E293B) : const Color(0xFFF1F5F9),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark ? const Color(0xFF334155) : const Color(0xFFE2E8F0),
          ),
        ),
        padding: const EdgeInsets.all(12),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              _getFileIcon(block.mimeType),
              size: 32,
              color: isDark ? const Color(0xFF6EE7B7) : const Color(0xFF047857),
            ),
            const SizedBox(width: 12),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  block.filename ?? 'File',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: textColor,
                  ),
                ),
                if (block.sizeBytes != null)
                  Text(
                    _formatFileSize(block.sizeBytes!),
                    style: TextStyle(
                      fontSize: 11,
                      color: textColor.withValues(alpha: 0.6),
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 8),
            Icon(
              Icons.download,
              size: 18,
              color: textColor.withValues(alpha: 0.6),
            ),
          ],
        ),
      ),
    ),
  );
}

IconData _getFileIcon(String? mimeType) {
  if (mimeType == null) return Icons.insert_drive_file;
  if (mimeType.contains('pdf')) return Icons.picture_as_pdf;
  if (mimeType.contains('word') || mimeType.contains('document')) return Icons.description;
  if (mimeType.contains('excel') || mimeType.contains('spreadsheet')) return Icons.table_chart;
  if (mimeType.contains('zip') || mimeType.contains('archive')) return Icons.folder_zip;
  if (mimeType.contains('text')) return Icons.text_snippet;
  return Icons.insert_drive_file;
}

String _formatFileSize(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}
```

---

### Phase 6: Chat Provider Update

#### 6.1 Parse New Block Types

**File**: `flutter/lib/features/chat/providers/chat_provider.dart`

```dart
ChatMessage _parseMessage(Map<String, dynamic> data) {
  // ... existing ...
  
  final blocks = blocksData.map((b) {
    final block = b as Map<String, dynamic>;
    final blockType = block['type'] as String? ?? 'text';

    switch (blockType) {
      case 'tool_call':
        return ChatBlock.toolCall(/* ... */);
      case 'tool_result':
        return ChatBlock.toolResult(/* ... */);
      case 'thinking':
        return ChatBlock.thinking(/* ... */);
      // NEW
      case 'image':
        return ChatBlock.image(
          id: block['id'] as String,
          mimeType: block['mime_type'] as String,
          altText: block['alt_text'] as String?,
        );
      case 'audio':
        return ChatBlock.audio(
          id: block['id'] as String,
          mimeType: block['mime_type'] as String,
          durationSeconds: (block['duration_seconds'] as num?)?.toDouble(),
        );
      case 'file':
        return ChatBlock.file(
          id: block['id'] as String,
          filename: block['filename'] as String,
          mimeType: block['mime_type'] as String,
          sizeBytes: block['size_bytes'] as int?,
        );
      default:
        return ChatBlock.text(block['text'] as String? ?? '');
    }
  }).toList();
  
  // ...
}
```

---

## API Usage Examples

### OpenCode Skill Example

```python
# opencode-skill/send_to_chat.py
import json
import base64
import os

def send_file_to_chat(session_name: str, window_index: int, file_path: str):
    """Send a file to the WebMux chat session."""
    
    # Detect MIME type
    import mimetypes
    mime_type = mimetypes.guess_type(file_path)[0] or 'application/octet-stream'
    
    # Read and encode file
    with open(file_path, 'rb') as f:
        file_data = base64.b64encode(f.read()).decode('utf-8')
    
    filename = os.path.basename(file_path)
    
    # Send via WebSocket
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
    
    # Connect to WebSocket and send
    # ... (WebSocket connection code)
```

### cURL Example

```bash
# Send file via HTTP POST
curl -X POST https://your-server/api/chat/send-file \
  -F "session_name=my-session" \
  -F "window_index=0" \
  -F "file=@screenshot.png"
```

---

## Testing Plan

### Unit Tests
- Backend: Test file storage save/load
- Backend: Test WebSocket message parsing
- Flutter: Test ChatBlock factory constructors
- Flutter: Test MIME type detection

### Integration Tests
- Send image via WebSocket → verify it appears in Flutter
- Send audio via WebSocket → verify playback works
- Upload file via HTTP → verify file downloads correctly

### Manual Testing
1. Test with various image formats (PNG, JPEG, GIF, WebP)
2. Test with various audio formats (MP3, WAV, OGG)
3. Test with PDF and other document types
4. Test image tap-to-expand
5. Test audio play/pause
6. Test file download

---

## Files to Modify

### Backend (Rust)
| File | Changes |
|------|---------|
| `backend-rust/src/chat_log/mod.rs` | Add Image/Audio/File variants to ContentBlock |
| `backend-rust/src/chat_file.rs` | NEW - File storage service |
| `backend-rust/src/types/mod.rs` | Add SendFileToChat, ChatFileMessage types |
| `backend-rust/src/websocket/mod.rs` | Handle new message type |
ust/src/api/mod.rs` | Add| `backend-r HTTP endpoint (optional) |

### Flutter (Dart)
| File | Changes |
|------|---------|
| `flutter/lib/data/models/chat_message.dart` | Add ChatBlockType variants, fields |
| `flutter/lib/data/services/chat_file_service.dart` | NEW - File service |
| `flutter/lib/features/chat/providers/chat_provider.dart` | Parse new block types |
| `flutter/lib/features/chat/widgets/professional_message_bubble.dart` | Render image/audio/file |
| `flutter/pubspec.yaml` | Add dependencies |

---

## Implementation Order

1. **Backend: ContentBlock enum** - Add Image/Audio/File variants
2. **Backend: File storage** - Create ChatFileStorage service
3. **Backend: WebSocket handler** - Handle send-file-to-chat
4. **Flutter: Models** - Add ChatBlockType and fields
5. **Flutter: Dependencies** - Add cached_network_image, just_audio
6. **Flutter: UI** - Add rendering for image, audio, file
7. **Testing** - Manual testing with various file types

---

## Success Criteria

- [ ] Images display in chat with tap-to-expand
- [ ] Audio plays inline with play/pause controls
- [ ] Files show with download capability
- [ ] External processes can send files via WebSocket
- [ ] HTTP endpoint available for file uploads
- [ ] Works with common formats: PNG, JPG, GIF, WebP, PDF, MP3, WAV

---

## Notes

- Files stored locally on device/backend - no cloud storage needed initially
- Base64 encoding increases size by ~33%, but acceptable for typical use
- Consider adding file size limits (e.g., 10MB) to prevent abuse
- For large files, consider chunked upload or direct file path reference
