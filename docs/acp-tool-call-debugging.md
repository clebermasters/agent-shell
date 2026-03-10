# ACP Chat Tool Call Widget - Debugging Document

## Goal

Make tool calls from ACP mode appear as collapsible widgets (like they do for tmux mode) instead of plain text in the Flutter app chat.

## Background

The WebMux Flutter app has two chat modes:
1. **tmux mode** - Terminal sessions via tmux
2. **ACP mode** - AI agent sessions via OpenCode's Agentic Communication Protocol

Both modes should display tool calls as collapsible widgets showing:
- Tool name and icon
- Tool input (expandable)
- Tool result

## Current Behavior

- **tmux mode**: Tool calls display correctly as collapsible widgets ✓
- **ACP mode**: Tool calls display as plain text ✗

## Architecture Overview

### Message Flow (ACP Mode)

```
Backend (Rust)
    │
    ├─► WebSocket: "acp-tool-call" message
    │       {type: "acp-tool-call", sessionId, toolCallId, title, kind, input}
    │
    └─► WebSocket: "acp-tool-result" message
            {type: "acp-tool-result", sessionId, toolCallId, status, output}
```

### Flutter Processing

```
WebSocket Service (websocket_service.dart)
    │
    └─► _messageController.add(message)
            │
ChatProvider (chat_provider.dart)
    │
    ├─► _handleAcpToolCall() → creates ChatMessage with blocks
    │
    └─► _handleAcpToolResult() → creates ChatMessage without blocks
```

### Flutter Rendering

```
ProfessionalMessageBubble (professional_message_bubble.dart)
    │
    ├─► isTool = message.type == tool || toolCall || toolResult
    │
    └─► if (blocks.isNotEmpty) → render blocks as widgets
        else → render as plain text
```

## What We've Tried

### Attempt 1: Fix ChatMessageType mismatch
**Issue found:** In `_parseMessage()`, tool calls were using `ChatMessageType.tool` instead of `ChatMessageType.toolCall`.

**Fix:** Changed `type = ChatMessageType.tool` to `type = ChatMessageType.toolCall`

**Result:** Did not fix the issue

### Attempt 2: Add debug logging to trace data flow
**Added logging to:**
- `_handleAcpToolCall()` - to see if messages were being received
- `_parseMessage()` - to see how messages were being parsed
- `ProfessionalMessageBubble.build()` - to see widget state

**Finding:** The data IS correct - `message.type=toolCall, blocks=1` was being logged, but rendering still showed plain text.

### Attempt 3: Check rendering logic
**Investigation:** Found that `_buildMessageContent()` checks `blocks.isNotEmpty` to decide whether to render widgets or plain text.

**Finding:** Some tool messages had `blocks=0`, others had `blocks=1`

### Attempt 4: Discover dual message paths
**Major finding:** ACP mode has TWO different paths for tool messages:

1. **Path A: `acp-tool-call` WebSocket message**
   - Handled by `_handleAcpToolCall()`
   - Creates ChatMessage with `type: toolCall` and `blocks: [ChatBlock.toolCall]`
   - **This works correctly**

2. **Path B: Parsed from terminal output** 
   - Handled by `parseClaudeOutput()` → `_flushBlock()` → `addAssistantMessage()`
   - Creates ChatMessage with `type: tool` (NOT toolCall) and **no blocks**
   - **This is the broken path**

### Attempt 5: Fix addAssistantMessage
**Issue:** `addAssistantMessage(content, {toolName})` was:
- Setting `type: tool` instead of `toolCall`
- Not passing any blocks

**Fix:** Modified to:
- Use `ChatMessageType.toolCall` when toolName is present
- Accept and pass blocks parameter

**Result:** Still not working as expected

## Current Suspicions

### Suspicion 1: Message type mismatch persists
Even after fixing `addAssistantMessage`, there may be other places creating tool messages without blocks. The logs showed:
- `message.type=toolCall, blocks=1` (works)
- `message.type=tool, blocks=0` (doesn't work)
- `message.type=toolResult, blocks=0` (expected - tool results don't have blocks)

### Suspicion 2: The widget check may be wrong
In `ProfessionalMessageBubble`, the check is:
```dart
if (widget.message.blocks.isNotEmpty) {
    // render as widgets
} else {
    // render as plain text
}
```

Even if we set blocks correctly, there might be an issue with how the message is being reconstructed or the widget is being rebuilt.

### Suspicion 3: ACP tool calls may not be going through the expected handler
Looking at logs, we saw messages with `blocks=1` for `toolCall` type, suggesting the `acp-tool-call` path works. But other tool messages still appear as plain text, possibly coming from a different source.

### Suspicion 4: Race condition or message ordering
Tool calls and results might be processed in the wrong order, causing the display logic to fail.

## Data Structures

### ChatMessageType (enum)
```dart
enum ChatMessageType {
  user,
  assistant,
  system,
  tool,        // Old type, still used in some paths
  toolCall,   // New type for tool calls with blocks
  toolResult, // For tool results
  error,
}
```

### ChatBlockType (enum)
```dart
enum ChatBlockType { 
  text, 
  toolCall, 
  toolResult, 
  thinking, 
  image, 
  audio, 
  file 
}
```

### ChatBlock (model)
```dart
class ChatBlock extends Equatable {
  final ChatBlockType type;
  final String? text;
  final String? toolName;
  final String? summary;
  final Map<String, dynamic>? input;
  final String? content;
  // ... other fields
}
```

## Next Steps Recommendations

1. **Add more granular logging**: Log exactly which handler creates each message
2. **Check all message creation paths**: Find all places where `ChatMessage` is created with type `tool` and ensure blocks are passed
3. **Test with a specific tool**: Use one specific tool (like Bash) and trace its entire message flow
4. **Consider simplifying**: Merge the dual paths into one consistent path for tool messages
5. **Check widget rebuilds**: Ensure the message widget is rebuilding when blocks are added

## Files to Review

- `flutter/lib/features/chat/providers/chat_provider.dart` - Message handling
- `flutter/lib/features/chat/widgets/professional_message_bubble.dart` - Rendering
- `flutter/lib/data/models/chat_message.dart` - Data models
- `backend-rust/src/websocket/mod.rs` - Backend message sending
