pub mod claude_parser;
pub mod codex_parser;
pub mod opencode_parser;
pub mod watcher;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Normalized content block — shared format for Claude Code and Codex.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ContentBlock {
    Text {
        text: String,
    },
    Thinking {
        content: String,
    },
    ToolCall {
        name: String,
        summary: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        input: Option<serde_json::Value>,
    },
    ToolResult {
        #[serde(rename = "toolName")]
        tool_name: String,
        summary: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        content: Option<String>,
    },
    Image {
        id: String,
        #[serde(rename = "mimeType")]
        mime_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        alt_text: Option<String>,
    },
    Audio {
        id: String,
        #[serde(rename = "mimeType")]
        mime_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        duration_seconds: Option<f32>,
    },
    File {
        id: String,
        filename: String,
        #[serde(rename = "mimeType")]
        mime_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        size_bytes: Option<u64>,
    },
}

/// Normalized chat message.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub role: String,
    pub timestamp: Option<DateTime<Utc>>,
    pub blocks: Vec<ContentBlock>,
}

use std::path::PathBuf;

/// Which AI tool is running.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum AiTool {
    Claude,
    Codex,
    Opencode { cwd: PathBuf, pid: u32 },
}

/// Events emitted by the log watcher.
#[derive(Debug, Clone)]
pub enum ChatLogEvent {
    History {
        messages: Vec<ChatMessage>,
        tool: AiTool,
        has_more: bool,
        total_count: usize,
    },
    NewMessage {
        message: ChatMessage,
    },
    Error {
        error: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_content_block_text_roundtrip() {
        let block = ContentBlock::Text { text: "hello world".to_string() };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"type\":\"text\""));
        let parsed: ContentBlock = serde_json::from_str(&json).unwrap();
        match parsed {
            ContentBlock::Text { text } => assert_eq!(text, "hello world"),
            _ => panic!("Expected Text variant"),
        }
    }

    #[test]
    fn test_content_block_tool_call_roundtrip() {
        let block = ContentBlock::ToolCall {
            name: "bash".to_string(),
            summary: "Running command".to_string(),
            input: Some(serde_json::json!({"command": "ls -la"})),
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"type\":\"tool_call\""));
        let parsed: ContentBlock = serde_json::from_str(&json).unwrap();
        match parsed {
            ContentBlock::ToolCall { name, summary, input } => {
                assert_eq!(name, "bash");
                assert_eq!(summary, "Running command");
                assert!(input.is_some());
            }
            _ => panic!("Expected ToolCall variant"),
        }
    }

    #[test]
    fn test_content_block_tool_result_roundtrip() {
        let block = ContentBlock::ToolResult {
            tool_name: "bash".to_string(),
            summary: "Completed".to_string(),
            content: None, // should be skipped in serialization
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"toolName\":\"bash\""));
        assert!(!json.contains("\"content\"")); // skip_serializing_if None
        // Deserialize with content present
        let json_with_content = r#"{"type":"tool_result","toolName":"bash","summary":"Done","content":"output"}"#;
        let parsed: ContentBlock = serde_json::from_str(json_with_content).unwrap();
        match parsed {
            ContentBlock::ToolResult { content, .. } => assert_eq!(content, Some("output".to_string())),
            _ => panic!("Expected ToolResult variant"),
        }
    }

    #[test]
    fn test_content_block_image_roundtrip() {
        let block = ContentBlock::Image {
            id: "img-1".to_string(),
            mime_type: "image/png".to_string(),
            alt_text: None,
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"mimeType\":\"image/png\""));
        assert!(!json.contains("\"alt_text\"")); // skip_serializing_if None
        let parsed: ContentBlock = serde_json::from_str(&json).unwrap();
        match parsed {
            ContentBlock::Image { id, .. } => assert_eq!(id, "img-1"),
            _ => panic!("Expected Image variant"),
        }
    }

    #[test]
    fn test_chat_message_full_roundtrip() {
        let msg = ChatMessage {
            role: "assistant".to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![
                ContentBlock::Text { text: "Hello".to_string() },
                ContentBlock::Thinking { content: "Let me think...".to_string() },
            ],
        };
        let json = serde_json::to_string(&msg).unwrap();
        let parsed: ChatMessage = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.role, "assistant");
        assert!(parsed.timestamp.is_some());
        assert_eq!(parsed.blocks.len(), 2);
    }

    #[test]
    fn test_ai_tool_enum_roundtrip() {
        // Claude
        let tool = AiTool::Claude;
        let json = serde_json::to_string(&tool).unwrap();
        assert_eq!(json, "\"claude\"");
        let parsed: AiTool = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, AiTool::Claude);

        // Codex
        let tool = AiTool::Codex;
        let json = serde_json::to_string(&tool).unwrap();
        assert_eq!(json, "\"codex\"");

        // Opencode
        let tool = AiTool::Opencode { cwd: PathBuf::from("/tmp"), pid: 1234 };
        let json = serde_json::to_string(&tool).unwrap();
        assert!(json.contains("opencode"));
        let parsed: AiTool = serde_json::from_str(&json).unwrap();
        match parsed {
            AiTool::Opencode { cwd, pid } => {
                assert_eq!(cwd, PathBuf::from("/tmp"));
                assert_eq!(pid, 1234);
            }
            _ => panic!("Expected Opencode variant"),
        }
    }
}
