# OpenCode Thinking/Reasoning Display - Technical Analysis

## Overview

This document explains how OpenCode's thinking/reasoning process works and how to display it in the Flutter app.

## How OpenCode Handles Thinking

### 1. AI Provider Events

When AI models with reasoning capabilities (like Claude, OpenAI o1, etc.) are used, OpenCode receives special events:

```typescript
// From provider/transform.ts and session/processor.ts
- "reasoning-start" - AI starts thinking
- "reasoning-delta" - Chunk of thinking text
- "reasoning-end"   - Thinking completed
```

### 2. Message Part Structure

In `session/message-v2.ts`:

```typescript
export const ReasoningPart = PartBase.extend({
  type: z.literal("reasoning"),
  text: z.string(),
  metadata: z.record(z.string(), z.any()).optional(),
  time: z.object({
    start: z.number(),
    end: z.number().optional(),
  }),
})
```

A message can have multiple parts:
- `text` - Normal response text
- `reasoning` - Thinking/thinking process
- `tool` - Tool calls
- `tool-result` - Tool results
- `step-start` - Step boundaries

### 3. Processor Flow

From `session/processor.ts`:

```typescript
case "reasoning-start":
  // Create new reasoning part
  const reasoningPart = {
    type: "reasoning" as const,
    text: "",
    time: { start: Date.now() },
  }
  await Session.updatePart(reasoningPart)
  break

case "reasoning-delta":
  // Append thinking text
  part.text += value.text
  await Session.updatePartDelta({ field: "text", delta: value.text })
  break

case "reasoning-end":
  // Finalize thinking
  part.time.end = Date.now()
  await Session.updatePart(part)
  break
```

## Current Implementation in WebMux

### Backend (Rust)

In `backend-rust/src/chat_log/opencode_parser.rs`:

```rust
_ => return None, // reasoning, patch, snapshot are skipped
```

The backend currently **ignores** reasoning content entirely.

### Flutter App

In `flutter/lib/data/models/chat_message.dart`:

```dart
enum ChatMessageType {
  user,
  assistant,
  system,
  tool,
  toolCall,
  toolResult,
  error,
}
```

There's **no thinking/reasoning type** currently defined.

## Proposed Implementation

### 1. Add Thinking Type to Flutter Model

```dart
// flutter/lib/data/models/chat_message.dart

enum ChatMessageType {
  user,
  assistant,
  thinking,  // NEW - for AI thinking
  system,
  tool,
  toolCall,
  toolResult,
  error,
}

// Also add to ChatMessage
class ChatMessage extends Equatable {
  // ...
  final String? thinking;  // NEW - store thinking text
}
```

### 2. Update Backend to Include Reasoning

Modify `backend-rust/src/chat_log/opencode_parser.rs`:

```rust
// Instead of ignoring reasoning, extract and include it
"reasoning" => {
    let thinking_text = part.text.unwrap_or_default();
    Some(ChatMessage {
        role: Role::Assistant,
        blocks: vec![ContentBlock::Thinking { 
            content: thinking_text 
        }],
    })
}
```

### 3. Add ContentBlock Type

```rust
// backend-rust/src/chat_log/mod.rs

pub enum ContentBlock {
    Text { content: String },
    Thinking { content: String },  // NEW
    ToolCall { ... },
    ToolResult { ... },
}
```

### 4. Update Flutter UI

Create a special widget to display thinking with animation:

```dart
// Thinking message bubble
class ThinkingBubble extends StatelessWidget {
  final String thinking;
  
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.grey.shade800,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.psychology, size: 16, color: Colors.amber),
              SizedBox(width: 8),
              Text('Thinking...', 
                style: TextStyle(color: Colors.amber)),
            ],
          ),
          SizedBox(height: 8),
          SelectableText(thinking),
        ],
      ),
    );
  }
}
```

### 5. WebSocket Message Format

The backend should send thinking updates to Flutter via WebSocket:

```json
{
  "type": "thinking",
  "content": "Let me analyze this problem...",
  "complete": false
}
```

## Alternative: Real-time Streaming

For real-time thinking display, the WebSocket could stream thinking as it happens:

1. Flutter connects to WebSocket
2. Backend monitors OpenCode session for reasoning events  
3. Backend streams thinking chunks to Flutter via WebSocket
4. Flutter displays thinking with typewriter effect

## Files to Modify

| File | Change |
|------|--------|
| `flutter/lib/data/models/chat_message.dart` | Add `thinking` type and field |
| `flutter/lib/features/chat/providers/chat_provider.dart` | Handle thinking messages |
| `flutter/lib/features/chat/widgets/professional_message_bubble.dart` | Add thinking UI |
| `backend-rust/src/chat_log/opencode_parser.rs` | Parse and include reasoning |
| `backend-rust/src/chat_log/mod.rs` | Add Thinking content block |
| `backend-rust/src/websocket.rs` | Stream thinking to Flutter |

## Complexity Assessment

This is a **medium-high complexity** change requiring:
- Backend modifications to extract reasoning
- WebSocket streaming for real-time display  
- Flutter model updates
- UI components for thinking display
- Testing with reasoning-capable models

## Notes

- Only works with AI providers that support reasoning (Claude, OpenAI o1, etc.)
- Thinking content can be large - consider limiting display length
- May want to collapse thinking by default with "Show thinking" option
