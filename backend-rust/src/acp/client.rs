use std::collections::HashMap;
use std::sync::Arc;
use serde::{Deserialize, Serialize};
use serde_json as sj;
use tokio::sync::{mpsc, Mutex, RwLock};

use crate::acp::session::*;

pub struct AcpClient {
    request_id: Arc<Mutex<usize>>,
    pending: Arc<Mutex<HashMap<usize, RequestCallback>>>,
    event_tx: mpsc::Sender<AcpEvent>,
    initialized: Arc<RwLock<bool>>,
}

pub enum AcpEvent {
    SessionUpdate {
        session_id: String,
        update: SessionUpdate,
    },
    PermissionRequest {
        request_id: String,
        session_id: String,
        tool_call: sj::Value,
        options: Vec<sj::Value>,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "sessionUpdate")]
pub enum SessionUpdate {
    #[serde(rename = "agent_message_chunk")]
    AgentMessageChunk { content: sj::Value },
    #[serde(rename = "agent_thought_chunk")]
    AgentThoughtChunk { content: sj::Value },
    #[serde(rename = "user_message_chunk")]
    UserMessageChunk { content: sj::Value },
    #[serde(rename = "tool_call")]
    ToolCall {
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        title: String,
        kind: String,
        status: String,
        locations: Vec<sj::Value>,
        #[serde(rename = "rawInput")]
        raw_input: sj::Value,
    },
    #[serde(rename = "tool_call_update")]
    ToolCallUpdate {
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        status: String,
        kind: String,
        title: String,
        #[serde(rename = "rawInput")]
        raw_input: sj::Value,
        content: Vec<sj::Value>,
        #[serde(rename = "rawOutput")]
        raw_output: Option<sj::Value>,
    },
    #[serde(rename = "plan")]
    Plan { entries: Vec<sj::Value> },
    #[serde(rename = "usage_update")]
    UsageUpdate { usage: sj::Value },
    #[serde(rename = "available_commands_update")]
    AvailableCommandsUpdate { commands: Vec<sj::Value> },
}

type RequestCallback = Box<dyn FnOnce(Result<sj::Value, String>) + Send>;

impl AcpClient {
    pub fn new(event_tx: mpsc::Sender<AcpEvent>) -> Self {
        Self {
            request_id: Arc::new(Mutex::new(1)),
            pending: Arc::new(Mutex::new(HashMap::new())),
            event_tx,
            initialized: Arc::new(RwLock::new(false)),
        }
    }

    pub async fn start(&mut self) -> Result<InitializeResult, String> {
        *self.initialized.write().await = true;
        
        Ok(InitializeResult {
            protocol_version: 1,
            capabilities: ServerCapabilities { tools: sj::Value::Null },
            server_info: ServerInfo { name: "OpenCode".to_string(), version: "1.0.0".to_string() },
            auth_methods: vec![],
            agent_capabilities: None,
            agent_info: AgentInfo { name: "OpenCode".to_string(), version: "1.0.0".to_string() },
        })
    }

    pub async fn create_session(&self, _cwd: &str) -> Result<CreateSessionResult, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn resume_session(&self, _session_id: &str, _cwd: &str) -> Result<CreateSessionResult, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn fork_session(&self, _session_id: &str, _cwd: &str) -> Result<CreateSessionResult, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn list_sessions(&self) -> Result<ListSessionsResult, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn set_model(&self, _session_id: &str, _model_id: &str) -> Result<sj::Value, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn set_mode(&self, _session_id: &str, _mode_id: &str) -> Result<sj::Value, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn send_prompt(&self, _session_id: &str, _message: &str) -> Result<PromptResult, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn cancel_prompt(&self, _session_id: &str) -> Result<sj::Value, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn respond_to_permission(&self, _request_id: &str, _option_id: &str) -> Result<sj::Value, String> {
        Err("ACP not yet implemented".to_string())
    }

    pub async fn is_initialized(&self) -> bool {
        *self.initialized.read().await
    }

    pub fn close(&mut self) {}
}
