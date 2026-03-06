# File Upload with Prompt Support - Implementation Plan

## Overview

Enable Flutter app users to upload files directly in chat with an optional text prompt. The backend will save files to the tmux session's working directory and append the file path to the user's message before processing.

## Goals

1. **Upload from Flutter App**: Users can select and send files from their device
2. **Optional Prompt**: Users can add text alongside file uploads (like WhatsApp/Telegram)
3. **Session-Aware Storage**: Files saved to the tmux session's working directory
4. **Path Appending**: Backend appends file location to user prompt for AI context

---

## Architecture

### Message Flow

```
┌─────────────────┐     WebSocket      ┌──────────────┐
│  Flutter App   │ ──────────────────▶ │   Backend    │
│  (File + Prompt)│  SendFileToChat    │   (Rust)     │
└─────────────────┘                     └──────┬───────┘
                                               │
                                               ▼
                                    ┌──────────────────────┐
                                    │  1. Save file to     │
                                    │     {session_path}/  │
                                    │  2. Append path to   │
                                    │     prompt           │
                                    │  3. Process as chat │
                                    │     message          │
                                    └──────────────────────┘
```

### Data Model Changes

#### Current `SendFileToChat` Structure
```rust
// backend-rust/src/types/mod.rs
SendFileToChat {
    session_name: String,
    window_index: u32,
    file: FileAttachment,
}
```

#### New Structure
```rust
SendFileToChat {
    session_name: String,
    window_index: u32,
    file: FileAttachment,
    prompt: Option<String>,  // NEW: User's text message
}
```

---

## Implementation Steps

### Phase 1: Backend Changes

#### 1.1 Update Types (backend-rust/src/types/mod.rs)

**Location**: Lines 267-273

**Changes**:
- Add optional `prompt` field to `SendFileToChat` variant

```rust
SendFileToChat {
    #[serde(rename = "sessionName")]
    session_name: String,
    #[serde(rename = "windowIndex")]
    window_index: u32,
    file: FileAttachment,
    prompt: Option<String>,  // NEW
},
```

#### 1.2 Update File Storage (backend-rust/src/chat_file_storage.rs)

**New Function**: Add method to save file to custom directory

```rust
impl ChatFileStorage {
    /// Save file to a specific directory (e.g., tmux session path)
    pub fn save_file_to_directory(
        &self,
        data: &str,
        filename: &str,
        mime_type: &str,
        target_dir: &Path,
    ) -> Result<PathBuf, String> {
        // Ensure directory exists
        std::fs::create_dir_all(target_dir)
            .map_err(|e| format!("Failed to create directory: {}", e))?;

        let extension = extension_from_filename(filename)
            .or_else(|| extension_from_mime_type(mime_type))
            .unwrap_or_else(|| "bin".to_string());

        // Use original filename to make it easily identifiable
        let target_path = target_dir.join(format!("{}_{}", 
            uuid::Uuid::new_v4(),
            filename
        ));

        let decoded = BASE64
            .decode(data)
            .map_err(|e| format!("Failed to decode base64: {}", e))?;

        std::fs::write(&target_path, decoded)
            .map_err(|e| format!("Failed to write file: {}", e))?;

        Ok(target_path)
    }
}
```

#### 1.3 Update WebSocket Handler (backend-rust/src/websocket/mod.rs)

**Location**: Lines 941-1013

**Changes**:

1. Add tmux session path resolution
2. Save file to session directory
3. Build combined prompt with file path
4. Process as regular chat message

```rust
WebSocketMessage::SendFileToChat {
    session_name,
    window_index,
    file,
    prompt,  // NEW
} => {
    info!(
        "Received file to send to chat: {} ({})",
        file.filename, file.mime_type
    );

    // 1. Get tmux session working directory
    let session_path = state
        .tmux_manager
        .get_session_path(&session_name)
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());

    // 2. Save file to session directory
    let file_path = state
        .chat_file_storage
        .save_file_to_directory(
            &file.data,
            &file.filename,
            &file.mime_type,
            &session_path,
        )
        .map_err(|e| {
            error!("Failed to save file: {}", e);
        })?;

    info!("File saved to: {:?}", file_path);

    // 3. Create file path string for appending
    let file_path_str = file_path.to_string_lossy().to_string();

    // 4. Build combined prompt (user prompt + file location)
    let combined_text = match &prompt {
        Some(text) if !text.trim().is_empty() => {
            format!("{}\n\nHere is the file: {}", text.trim(), file_path_str)
        }
        Some(_) => format!("Here is the file: {}", file_path_str),
        None => format!("Here is the file: {}", file_path_str),
    };

    // 5. Save to chat_files for display purposes (UUID-based)
    let file_id = state
        .chat_file_storage
        .save_file(&file.data, &file.filename, &file.mime_type)
        .map_err(|e| {
            error!("Failed to save file for display: {}", e);
        })?;

    // 6. Create content block (unchanged)
    let block = create_content_block(&file, &file_id);

    // 7. Create chat message with the combined text
    let chat_message = crate::chat_log::ChatMessage {
        role: "user".to_string(),  // Changed from "assistant" to "user"
        timestamp: Some(chrono::Utc::now()),
        blocks: vec![
            crate::chat_log::ContentBlock::Text {
                content: combined_text,
            },
            block,
        ],
    };

    // 8. Persist and broadcast (existing logic)
    // ...
}
```

#### 1.4 Add TmuxManager Method

**Location**: Create or find tmux manager module

```rust
// In tmux manager module
pub fn get_session_path(&self, session_name: &str) -> Option<PathBuf> {
    // Run: tmux display-message -p -F '#{pane_current_path}' -t <session>
    let output = std::process::Command::new("tmux")
        .args([
            "display-message",
            "-p",
            "-F",
            "#{pane_current_path}",
            "-t",
            session_name,
        ])
        .output()
        .ok()?;

    if output.status.success() {
        let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Some(PathBuf::from(path))
    } else {
        None
    }
}
```

---

### Phase 2: Flutter App Changes

#### 2.1 Add Dependencies (flutter/pubspec.yaml)

```yaml
dependencies:
  file_picker: ^8.0.0  # Already in plans
  flutter_file_dialog: ^3.0.2  # Alternative for better mobile support
```

#### 2.2 Create File Upload Service (flutter/lib/services/file_upload_service.dart)

```dart
class FileUploadService {
  Future<FilePickerResult?> pickFile() async {
    return await FilePicker.platform.pickFiles(
      type: FileType.any,
      allowMultiple: false,
    );
  }

  Future<({String filename, String mimeType, String base64Data})?> 
      encodeFile(XFile file) async {
    final bytes = await file.readAsBytes();
    final base64Data = base64Encode(bytes);
    
    return (
      filename: file.name,
      mimeType: _getMimeType(file.name),
      base64Data: base64Data,
    );
  }

  String _getMimeType(String filename) {
    final ext = filename.split('.').last.toLowerCase();
    return _mimeTypeMap[ext] ?? 'application/octet-stream';
  }
}
```

#### 2.3 Update Chat Input Widget (flutter/lib/features/chat/widgets/)

**New UI Components**:
1. **Attachment Button**: Icon button to trigger file picker
2. **Selected File Preview**: Show filename with remove option
3. **Text Field**: Existing input field remains for prompt
4. **Send Button**: Handles combined file + prompt

**State Management**:
```dart
class ChatInputState {
  XFile? selectedFile;
  String prompt = '';
  bool isUploading = false;
}
```

**Widget Flow**:
```
┌─────────────────────────────────────────────────────┐
│  [📎] │ [Text input....................] │ [Send]  │
└─────────────────────────────────────────────────────┘

When file selected:
┌─────────────────────────────────────────────────────┐
│  [📎] │ [Text input....................] │ [Send]  │
│        ┌─────────────────────────────┐            │
│        │ 📄 filename.pdf        [✕]  │            │
│        └─────────────────────────────┘            │
└─────────────────────────────────────────────────────┘
```

#### 2.4 Update WebSocket Service

**Location**: flutter/lib/services/websocket_service.dart

**Changes**: Update `sendFileToChat` method signature

```dart
Future<void> sendFileToChat({
  required String sessionName,
  required int windowIndex,
  required String filename,
  required String mimeType,
  required String base64Data,
  String? prompt,  // NEW: Optional user message
}) async {
  final message = {
    'type': 'send-file-to-chat',
    'sessionName': sessionName,
    'windowIndex': windowIndex,
    'file': {
      'filename': filename,
      'mimeType': mimeType,
      'data': base64Data,
    },
    'prompt': prompt,  // NEW
  };
  
  await send(message);
}
```

#### 2.5 Update Chat Message Handler

**Process combined message**:
1. User enters prompt text
2. User selects file
3. User taps send
4. App sends: `{ prompt: "Check this", file: {...} }`
5. Backend saves file, appends path, processes as chat

---

### Phase 3: API Compatibility

#### 3.1 Backward Compatibility

The `prompt` field is optional - existing OpenCode integrations continue to work:

```json
// Existing format (still works)
{
  "type": "send-file-to-chat",
  "sessionName": "webmux",
  "windowIndex": 0,
  "file": { ... }
}

// New format with prompt
{
  "type": "send-file-to-chat",
  "sessionName": "webmux",
  "windowIndex": 0,
  "file": { ... },
  "prompt": "Please analyze this document"
}
```

---

## File Structure Changes

```
backend-rust/
├── src/
│   ├── main.rs              # Update ChatFileStorage init if needed
│   ├── types/
│   │   └── mod.rs           # Add prompt field
│   ├── chat_file_storage.rs # Add save_file_to_directory method
│   ├── websocket/
│   │   └── mod.rs           # Update handler
│   └── tmux/
│       └── mod.rs           # Add get_session_path method (if exists)
└── chat_files/              # Display storage (unchanged)
    └── ...

flutter/
├── lib/
│   ├── services/
│   │   ├── file_upload_service.dart  # NEW
│   │   └── websocket_service.dart   # Update
│   └── features/
│       └── chat/
│           └── widgets/
│               └── chat_input.dart   # Update UI
└── pubspec.yaml             # Add dependencies
```

---

## Testing Plan

### Unit Tests
1. `ChatFileStorage::save_file_to_directory` - Save to custom path
2. File path resolution from tmux session
3. Prompt + path concatenation logic

### Integration Tests
1. Flutter file picker → WebSocket → Backend save
2. Verify file exists at tmux session path
3. Verify chat message contains appended path

### Manual Testing
1. Select file in Flutter app
2. Add prompt text
3. Send message
4. Verify file at tmux session directory
5. Verify AI response references the file

---

## Configuration

### Environment Variables (Optional)

```bash
# Backend
WEBMUX_FILE_STORAGE_MODE=session  # Default: session-based
# Or: WEBMUX_FILE_STORAGE_MODE=chat_files  # Legacy behavior
```

### Default Behavior
- **Storage Location**: `{tmux_session_path}/`
- **Filename**: `{uuid}_{original_filename}`
- **Path Append Format**: `Here is the file: /path/to/file.pdf`

---

## Edge Cases

1. **No tmux session path**: Fall back to `chat_files/` directory
2. **Permission denied**: Return error to user
3. **Large files**: Implement chunked upload for files > 10MB
4. **Invalid filename**: Sanitize and generate safe name
5. **Empty prompt**: Still append file path
6. **Network interruption**: Queue upload for retry

---

## Open Questions

1. **Should we keep both storage locations?**
   - Session directory (for AI context)
   - chat_files/ (for display via API)
   
2. **Filename collision handling?**
   - Currently: UUID prefix prevents collision
   - Alternative: Add timestamp suffix

3. **File cleanup policy?**
   - Manual deletion only?
   - Auto-cleanup after N days?

4. **Maximum file size?**
   - Current: No limit
   - Recommended: 50MB limit
