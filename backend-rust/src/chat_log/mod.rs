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
    },
    NewMessage {
        message: ChatMessage,
    },
    Error {
        error: String,
    },
}
