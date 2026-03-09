use std::collections::HashMap;
use std::process::Stdio;
use std::sync::Arc;
use serde::{Deserialize, Serialize};
use serde_json as sj;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::{mpsc, Mutex, RwLock};

use crate::acp::messages::{JsonRpcMessage, JsonRpcRequest, parse_jsonrpc_message};
use crate::acp::session::*;

pub struct AcpClient {
    child: Arc<Mutex<Option<Child>>>,
    request_id: Arc<Mutex<usize>>,
    pending: Arc<Mutex<HashMap<usize, RequestCallback>>>,
    event_tx: mpsc::Sender<AcpEvent>,
    initialized: Arc<RwLock<bool>>,
    stdin: Arc<Mutex<Option<tokio::process::ChildStdin>>>,
}

type RequestCallback = Box<dyn FnOnce(Result<sj::Value, String>) + Send + 'static>;

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
        locations: Option<Vec<sj::Value>>,
        #[serde(rename = "rawInput")]
        raw_input: Option<sj::Value>,
    },
    #[serde(rename = "tool_call_update")]
    ToolCallUpdate {
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        status: String,
        kind: String,
        title: String,
        #[serde(rename = "rawInput")]
        raw_input: Option<sj::Value>,
        content: Option<Vec<sj::Value>>,
        locations: Option<Vec<sj::Value>>,
        #[serde(rename = "rawOutput")]
        raw_output: Option<sj::Value>,
    },
    #[serde(rename = "plan")]
    Plan { entries: Vec<sj::Value> },
    #[serde(rename = "usage_update")]
    UsageUpdate { usage: sj::Value },
    #[serde(rename = "available_commands_update")]
    AvailableCommandsUpdate { commands: Vec<sj::Value> },
    #[serde(other)]
    Unknown,
}

impl AcpClient {
    pub fn new(event_tx: mpsc::Sender<AcpEvent>) -> Self {
        Self {
            child: Arc::new(Mutex::new(None)),
            request_id: Arc::new(Mutex::new(1)),
            pending: Arc::new(Mutex::new(HashMap::new())),
            event_tx,
            initialized: Arc::new(RwLock::new(false)),
            stdin: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn start(&mut self) -> Result<InitializeResult, String> {
        tracing::info!("Starting ACP client - spawning opencode acp");

        let mut child = Command::new("opencode")
            .arg("acp")
            .stdout(Stdio::piped())
            .stdin(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| format!("Failed to spawn opencode: {}", e))?;

        let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
        let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;

        {
            let mut child_guard = self.child.lock().await;
            *child_guard = Some(child);
        }
        {
            let mut stdin_guard = self.stdin.lock().await;
            *stdin_guard = Some(stdin);
        }

        let pending = self.pending.clone();
        let event_tx = self.event_tx.clone();
        let initialized = self.initialized.clone();

        // Spawn reader task
        let reader = BufReader::new(stdout);
        let mut lines = reader.lines();
        
        tokio::spawn(async move {
            while let Ok(Some(line)) = lines.next_line().await {
                tracing::debug!("ACP received: {}", line);
                if let Some(msg) = parse_jsonrpc_message(&line) {
                    match msg {
                        JsonRpcMessage::Response(resp) => {
                            if let Some(id) = resp.id.as_u64().map(|id| id as usize) {
                                let mut pending = pending.lock().await;
                                if let Some(callback) = pending.remove(&id) {
                                    if let Some(error) = resp.error {
                                        callback(Err(error.message));
                                    } else if let Some(result) = resp.result {
                                        callback(Ok(result));
                                    } else {
                                        callback(Err("No result or error".to_string()));
                                    }
                                }
                            }
                        }
                        JsonRpcMessage::Notification(notif) => {
                            if notif.method == "session/update" {
                                if let Some(params) = notif.params {
                                    match serde_json::from_value::<SessionUpdateParams>(params.clone()) {
                                        Ok(update_params) => {
                                            tracing::info!("Sending session update event for {}", update_params.session_id);
                                            match event_tx.send(AcpEvent::SessionUpdate {
                                                session_id: update_params.session_id,
                                                update: update_params.update,
                                            }).await {
                                                Ok(_) => tracing::info!("Event sent successfully"),
                                                Err(e) => tracing::error!("Failed to send event: {}", e),
                                            }
                                        }
                                        Err(e) => {
                                            tracing::error!("Failed to parse SessionUpdateParams: {} - JSON: {}", e, serde_json::to_string(&params).unwrap_or_default());
                                        }
                                    }
                                }
                            } else if notif.method == "agent/requestPermission" {
                                if let Some(params) = notif.params {
                                    if let Ok(perm_params) = serde_json::from_value::<PermissionRequestParams>(params) {
                                        let _ = event_tx.send(AcpEvent::PermissionRequest {
                                            request_id: perm_params.request_id,
                                            session_id: perm_params.session_id,
                                            tool_call: perm_params.tool_call,
                                            options: perm_params.options,
                                        }).await;
                                    }
                                }
                            }
                        }
                        JsonRpcMessage::Request(_) => {}
                    }
                }
            }
            tracing::info!("ACP reader task ended");
        });

        // Send initialize request
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "initialize".to_string(),
            params: Some(serde_json::json!({
                "protocolVersion": 1,
                "clientCapabilities": {
                    "resources": { "subscribe": true, "list": true },
                    "prompts": { "list": true },
                    "tools": { "list": true, "call": true },
                    "_meta": { "terminal-auth": true }
                }
            })),
        };

        tracing::info!("Sending initialize request");
        let result = self.send_request_raw(id, request).await;
        
        match result {
            Ok(result) => {
                *initialized.write().await = true;
                tracing::info!("ACP client initialized successfully");
                let init_result: InitializeResult = serde_json::from_value(result)
                    .map_err(|e| format!("Failed to parse initialize result: {}", e))?;
                Ok(init_result)
            }
            Err(e) => {
                tracing::error!("Failed to initialize ACP client: {}", e);
                Err(e)
            }
        }
    }

    async fn send_request_raw(&self, id: usize, request: JsonRpcRequest) -> Result<sj::Value, String> {
        let pending = Arc::clone(&self.pending);
        let stdin = Arc::clone(&self.stdin);
        
        let (tx, rx) = tokio::sync::oneshot::channel();
        
        {
            let mut pending_guard = pending.lock().await;
            pending_guard.insert(id, Box::new(move |result| {
                let _ = tx.send(result);
            }));
        }

        let request_json = serde_json::to_string(&request)
            .map_err(|e| format!("Failed to serialize request: {}", e))?;
        
        let mut stdin_guard = stdin.lock().await;
        if let Some(ref mut stdin) = *stdin_guard {
            stdin.write_all(format!("{}\n", request_json).as_bytes())
                .await
                .map_err(|e| format!("Failed to write to stdin: {}", e))?;
            stdin.flush()
                .await
                .map_err(|e| format!("Failed to flush stdin: {}", e))?;
        }
        drop(stdin_guard);

        tokio::select! {
            result = rx => {
                match result {
                    Ok(Ok(value)) => Ok(value),
                    Ok(Err(e)) => Err(e),
                    Err(_) => Err("Channel closed".to_string()),
                }
            }
            _ = tokio::time::sleep(tokio::time::Duration::from_secs(120)) => {
                Err("Request timeout".to_string())
            }
        }
    }

    pub async fn create_session(&self, cwd: &str) -> Result<CreateSessionResult, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/new".to_string(),
            params: Some(serde_json::json!({
                "cwd": cwd,
                "mcpServers": []
            })),
        };

        let result = self.send_request_raw(id, request).await?;
        
        serde_json::from_value(result)
            .map_err(|e| format!("Failed to parse session result: {}", e))
    }

    pub async fn resume_session(&self, session_id: &str, cwd: &str) -> Result<CreateSessionResult, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/resume".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id,
                "cwd": cwd
            })),
        };

        let result = self.send_request_raw(id, request).await?;
        
        serde_json::from_value(result)
            .map_err(|e| format!("Failed to parse resume result: {}", e))
    }

    pub async fn fork_session(&self, session_id: &str, cwd: &str) -> Result<CreateSessionResult, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/fork".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id,
                "cwd": cwd
            })),
        };

        let result = self.send_request_raw(id, request).await?;
        
        serde_json::from_value(result)
            .map_err(|e| format!("Failed to parse fork result: {}", e))
    }

    pub async fn list_sessions(&self) -> Result<ListSessionsResult, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/list".to_string(),
            params: Some(serde_json::json!({})),
        };

        let result = self.send_request_raw(id, request).await?;
        
        serde_json::from_value(result)
            .map_err(|e| format!("Failed to parse list sessions result: {}", e))
    }

    pub async fn set_model(&self, session_id: &str, model_id: &str) -> Result<sj::Value, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/setModel".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id,
                "modelId": model_id
            })),
        };

        self.send_request_raw(id, request).await
    }

    pub async fn set_mode(&self, session_id: &str, mode_id: &str) -> Result<sj::Value, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/setMode".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id,
                "modeId": mode_id
            })),
        };

        self.send_request_raw(id, request).await
    }

    pub async fn send_prompt(&self, session_id: &str, message: &str) -> Result<PromptResult, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/prompt".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id,
                "prompt": [
                    { "type": "text", "text": message }
                ]
            })),
        };

        let result = self.send_request_raw(id, request).await?;
        
        serde_json::from_value(result)
            .map_err(|e| format!("Failed to parse prompt result: {}", e))
    }

    pub async fn cancel_prompt(&self, session_id: &str) -> Result<sj::Value, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "session/cancel".to_string(),
            params: Some(serde_json::json!({
                "sessionId": session_id
            })),
        };

        self.send_request_raw(id, request).await
    }

    pub async fn respond_to_permission(&self, request_id: &str, option_id: &str) -> Result<sj::Value, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: serde_json::json!(id),
            method: "agent/requestPermission".to_string(),
            params: Some(serde_json::json!({
                "requestId": request_id,
                "outcome": {
                    "outcome": "selected",
                    "optionId": option_id
                }
            })),
        };

        self.send_request_raw(id, request).await
    }

    pub async fn is_initialized(&self) -> bool {
        *self.initialized.read().await
    }

    pub fn event_tx(&self) -> mpsc::Sender<AcpEvent> {
        self.event_tx.clone()
    }

    pub async fn close(&mut self) {
        let mut child_guard = self.child.lock().await;
        if let Some(ref mut child) = child_guard.take() {
            let _ = child.kill().await;
        }
    }
}

#[derive(Debug, Deserialize)]
struct SessionUpdateParams {
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "update")]
    update: SessionUpdate,
}

#[derive(Debug, Deserialize)]
struct PermissionRequestParams {
    #[serde(rename = "requestId")]
    request_id: String,
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "toolCall")]
    tool_call: sj::Value,
    options: Vec<sj::Value>,
}
