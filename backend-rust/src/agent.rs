use anyhow::Result;
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};

use crate::acp::{AcpClient, AcpEvent, SessionUpdate};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentBackend {
    Tmux,
    Acp,
}

impl Default for AgentBackend {
    fn default() -> Self {
        Self::Tmux
    }
}

#[derive(Debug, Clone)]
pub enum AgentEvent {
    MessageChunk {
        session_id: String,
        content: String,
        is_thinking: bool,
    },
    ToolCall {
        session_id: String,
        tool_call_id: String,
        title: String,
        kind: String,
        status: String,
        input: HashMap<String, serde_json::Value>,
    },
    ToolResult {
        session_id: String,
        tool_call_id: String,
        status: String,
        output: String,
    },
    Usage {
        session_id: String,
        total_tokens: usize,
    },
    Error {
        session_id: Option<String>,
        message: String,
    },
}

pub struct AgentState {
    pub backend: AgentBackend,
    pub active_sessions: RwLock<HashMap<String, AgentSession>>,
}

impl AgentState {
    pub fn new() -> Self {
        Self {
            backend: AgentBackend::Tmux,
            active_sessions: RwLock::new(HashMap::new()),
        }
    }

    pub fn set_backend(&mut self, backend: AgentBackend) {
        self.backend = backend;
    }
}

impl Default for AgentState {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone)]
pub struct AgentSession {
    pub session_id: String,
    pub cwd: String,
    pub backend: AgentBackend,
    pub model_id: Option<String>,
    pub mode_id: Option<String>,
}

pub trait AgentBackendTrait: Send + Sync {
    fn backend_type(&self) -> AgentBackend;

    fn create_session(
        &self,
        name: &str,
        cwd: &str,
    ) -> impl Future<Output = Result<AgentSession>> + Send;

    fn resume_session(
        &self,
        session_id: &str,
        cwd: &str,
    ) -> impl Future<Output = Result<AgentSession>> + Send;

    fn fork_session(
        &self,
        session_id: &str,
        cwd: &str,
    ) -> impl Future<Output = Result<AgentSession>> + Send;

    fn list_sessions(&self) -> impl Future<Output = Result<Vec<SessionInfo>>> + Send;

    fn send_message(
        &self,
        session_id: &str,
        message: &str,
    ) -> impl Future<Output = Result<()>> + Send;

    fn cancel_message(&self, session_id: &str) -> impl Future<Output = Result<()>> + Send;

    fn set_model(
        &self,
        session_id: &str,
        model_id: &str,
    ) -> impl Future<Output = Result<()>> + Send;

    fn set_mode(&self, session_id: &str, mode_id: &str) -> impl Future<Output = Result<()>> + Send;

    fn subscribe_events(
        &self,
        session_id: &str,
        tx: mpsc::Sender<AgentEvent>,
    ) -> impl Future<Output = Result<()>> + Send;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: String,
    pub cwd: String,
    pub title: String,
    pub updated_at: String,
}
