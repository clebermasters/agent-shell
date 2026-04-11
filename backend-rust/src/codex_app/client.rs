use std::collections::HashMap;
use std::process::Stdio;
use std::sync::Arc;

use chrono::{TimeZone, Utc};
use serde::Deserialize;
use serde_json::{self as sj, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStderr, ChildStdin, Command};
use tokio::sync::{mpsc, Mutex, RwLock};

use crate::acp::session::{CreateSessionResult, ListSessionsResult, SessionInfo};
use crate::chat_log::{ChatMessage, ContentBlock};
use crate::types::PermissionOption;

type RequestCallback = Box<dyn FnOnce(Result<sj::Value, String>) + Send + 'static>;

const CODEX_PROVIDER: &str = "codex";
const CODEX_YOLO_APPROVAL_POLICY: &str = "never";
const CODEX_YOLO_SANDBOX: &str = "danger-full-access";

pub struct CodexAppClient {
    child: Arc<Mutex<Option<Child>>>,
    stdin: Arc<Mutex<Option<ChildStdin>>>,
    request_id: Arc<Mutex<u64>>,
    pending: Arc<Mutex<HashMap<u64, RequestCallback>>>,
    known_sessions: Arc<Mutex<HashMap<String, SessionInfo>>>,
    event_tx: mpsc::Sender<CodexEvent>,
    initialized: Arc<RwLock<bool>>,
    pending_permission_requests: Arc<Mutex<HashMap<String, PendingPermissionRequest>>>,
    permission_request_seq: Arc<Mutex<u64>>,
}

#[derive(Debug, Clone)]
pub enum CodexEvent {
    SessionUpdate {
        session_id: String,
        update: CodexSessionUpdate,
    },
    PermissionRequest {
        request_id: String,
        session_id: String,
        tool: String,
        command: String,
        options: Vec<PermissionOption>,
    },
    PromptDone {
        session_id: String,
        stop_reason: String,
        total_tokens: usize,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone)]
pub enum CodexSessionUpdate {
    AgentMessageChunk {
        content: String,
        is_thinking: bool,
    },
    ToolCall {
        tool_call_id: String,
        title: String,
        kind: String,
        status: String,
        raw_input: Option<sj::Value>,
    },
    ToolResult {
        tool_call_id: String,
        status: String,
        output: String,
    },
}

#[derive(Debug, Clone)]
enum PendingPermissionRequest {
    CommandExecution {
        jsonrpc_request_id: sj::Value,
        command: Option<String>,
    },
    FileChange {
        jsonrpc_request_id: sj::Value,
        paths: Vec<String>,
    },
    Permissions {
        jsonrpc_request_id: sj::Value,
        requested_permissions: sj::Value,
    },
}

impl CodexAppClient {
    pub fn new(event_tx: mpsc::Sender<CodexEvent>) -> Self {
        Self {
            child: Arc::new(Mutex::new(None)),
            stdin: Arc::new(Mutex::new(None)),
            request_id: Arc::new(Mutex::new(1)),
            pending: Arc::new(Mutex::new(HashMap::new())),
            known_sessions: Arc::new(Mutex::new(HashMap::new())),
            event_tx,
            initialized: Arc::new(RwLock::new(false)),
            pending_permission_requests: Arc::new(Mutex::new(HashMap::new())),
            permission_request_seq: Arc::new(Mutex::new(1)),
        }
    }

    pub async fn start(&mut self) -> Result<sj::Value, String> {
        tracing::info!("Starting Codex app-server client");

        let mut child = Command::new("codex")
            .arg("app-server")
            // Keep app-server away from user shell startup hooks. Codex direct
            // sessions can invoke shell tools, and inheriting BASH_ENV/ENV here
            // can trigger recursive third-party shell wrappers before the model
            // has even started working.
            .env("BASH_ENV", "/dev/null")
            .env("ENV", "/dev/null")
            .env_remove("PROMPT_COMMAND")
            .stdout(Stdio::piped())
            .stdin(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| format!("Failed to spawn codex app-server: {e}"))?;

        let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
        let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;
        let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

        {
            let mut child_guard = self.child.lock().await;
            *child_guard = Some(child);
        }
        {
            let mut stdin_guard = self.stdin.lock().await;
            *stdin_guard = Some(stdin);
        }

        self.spawn_reader_task(stdout);
        spawn_stderr_drain_task(stderr, "Codex app-server");

        let init_result = self
            .send_request_value(
                "initialize",
                json!({
                    "clientInfo": {
                        "name": "webmux",
                        "version": env!("CARGO_PKG_VERSION"),
                    },
                    "capabilities": {
                        "experimentalApi": true,
                    }
                }),
            )
            .await?;

        self.send_notification("initialized", None).await?;
        *self.initialized.write().await = true;

        Ok(init_result)
    }

    pub async fn list_sessions(&self) -> Result<ListSessionsResult, String> {
        let mut sessions = Vec::new();
        let mut cursor: Option<String> = None;

        loop {
            let params = json!({
                "limit": 100u32,
                "cursor": cursor,
            });

            let response = self
                .send_request_typed::<ThreadListResponse>("thread/list", params)
                .await?;
            sessions.extend(response.data.into_iter().map(map_thread_summary_to_session));

            match response.next_cursor {
                Some(next) if !next.is_empty() => cursor = Some(next),
                _ => break,
            }
        }

        let known_sessions = self.known_sessions.lock().await;
        for session in known_sessions.values() {
            if sessions
                .iter()
                .all(|existing| existing.session_id != session.session_id)
            {
                sessions.push(session.clone());
            }
        }

        sessions.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(ListSessionsResult {
            sessions,
            next_cursor: None,
        })
    }

    pub async fn create_session(&self, cwd: &str) -> Result<CreateSessionResult, String> {
        let response = self
            .send_request_typed::<ThreadStartResponse>(
                "thread/start",
                codex_thread_start_params(cwd),
            )
            .await?;

        self.remember_thread_summary(&response.thread).await;

        Ok(CreateSessionResult {
            session_id: external_session_id(&response.thread.id),
            models: Some(crate::acp::session::ModelsInfo {
                current_model_id: response.model,
                available_models: Vec::new(),
            }),
            modes: None,
        })
    }

    pub async fn resume_session(
        &self,
        session_id: &str,
        cwd: &str,
    ) -> Result<CreateSessionResult, String> {
        let raw_id = decode_external_session_id(session_id).1;
        if self.is_thread_loaded(&raw_id).await? {
            return Ok(CreateSessionResult {
                session_id: external_session_id(&raw_id),
                models: None,
                modes: None,
            });
        }

        let response = self
            .send_request_typed::<ThreadResumeResponse>(
                "thread/resume",
                codex_thread_resume_params(&raw_id, cwd),
            )
            .await?;

        self.remember_thread_summary(&response.thread).await;

        Ok(CreateSessionResult {
            session_id: external_session_id(&response.thread.id),
            models: Some(crate::acp::session::ModelsInfo {
                current_model_id: response.model,
                available_models: Vec::new(),
            }),
            modes: None,
        })
    }

    pub async fn fork_session(
        &self,
        session_id: &str,
        cwd: &str,
    ) -> Result<CreateSessionResult, String> {
        let raw_id = decode_external_session_id(session_id).1;
        let response = self
            .send_request_typed::<ThreadForkResponse>(
                "thread/fork",
                codex_thread_fork_params(&raw_id, cwd),
            )
            .await?;

        self.remember_thread_summary(&response.thread).await;

        Ok(CreateSessionResult {
            session_id: external_session_id(&response.thread.id),
            models: Some(crate::acp::session::ModelsInfo {
                current_model_id: response.model,
                available_models: Vec::new(),
            }),
            modes: None,
        })
    }

    pub async fn send_prompt(&self, session_id: &str, message: &str) -> Result<sj::Value, String> {
        let raw_id = decode_external_session_id(session_id).1;
        self.send_request_value("turn/start", codex_turn_start_params(&raw_id, message))
            .await
    }

    pub async fn read_thread_messages(&self, session_id: &str) -> Result<Vec<ChatMessage>, String> {
        let raw_id = decode_external_session_id(session_id).1;
        let response = match self
            .send_request_typed::<ThreadReadResponse>(
                "thread/read",
                json!({
                    "threadId": raw_id,
                    "includeTurns": true,
                }),
            )
            .await
        {
            Ok(response) => response,
            Err(error)
                if error.contains("is not materialized yet; includeTurns is unavailable before first user message") =>
            {
                return Ok(Vec::new());
            }
            Err(error) => return Err(error),
        };

        let mut messages = Vec::new();
        for turn in response.thread.turns {
            let timestamp = turn_timestamp(&turn);
            for item in turn.items {
                messages.extend(item_to_history_messages(item, timestamp));
            }
        }
        Ok(messages)
    }

    pub async fn respond_to_permission(
        &self,
        request_id: &str,
        option_id: &str,
    ) -> Result<sj::Value, String> {
        let pending = {
            let mut guard = self.pending_permission_requests.lock().await;
            guard.remove(request_id)
        }
        .ok_or_else(|| format!("Unknown Codex permission request: {request_id}"))?;

        let option_id = option_id.to_lowercase();
        let envelope = match pending {
            PendingPermissionRequest::CommandExecution {
                jsonrpc_request_id, ..
            } => {
                let decision = if option_id.contains("deny")
                    || option_id.contains("decline")
                    || option_id.contains("cancel")
                {
                    "decline"
                } else {
                    "accept"
                };
                json!({
                    "id": jsonrpc_request_id,
                    "method": "item/commandExecution/requestApproval",
                    "response": {
                        "decision": decision,
                    }
                })
            }
            PendingPermissionRequest::FileChange {
                jsonrpc_request_id, ..
            } => {
                let decision = if option_id.contains("deny")
                    || option_id.contains("decline")
                    || option_id.contains("cancel")
                {
                    "decline"
                } else {
                    "accept"
                };
                json!({
                    "id": jsonrpc_request_id,
                    "method": "item/fileChange/requestApproval",
                    "response": {
                        "decision": decision,
                    }
                })
            }
            PendingPermissionRequest::Permissions {
                jsonrpc_request_id,
                requested_permissions,
            } => {
                let permissions = if option_id.contains("deny")
                    || option_id.contains("decline")
                    || option_id.contains("cancel")
                {
                    json!({})
                } else {
                    requested_permissions
                };
                json!({
                    "id": jsonrpc_request_id,
                    "method": "item/permissions/requestApproval",
                    "response": {
                        "permissions": permissions,
                        "scope": "turn",
                    }
                })
            }
        };

        self.write_value(&envelope).await?;
        Ok(json!({ "ok": true }))
    }

    pub async fn is_initialized(&self) -> bool {
        *self.initialized.read().await
    }

    async fn remember_thread_summary(&self, thread: &ThreadSummary) {
        let session = map_thread_summary_to_session(thread.clone());
        self.known_sessions
            .lock()
            .await
            .insert(session.session_id.clone(), session);
    }

    async fn is_thread_loaded(&self, raw_thread_id: &str) -> Result<bool, String> {
        let mut cursor: Option<String> = None;

        loop {
            let params = json!({
                "limit": 100u32,
                "cursor": cursor,
            });

            let response = self
                .send_request_typed::<ThreadLoadedListResponse>("thread/loaded/list", params)
                .await?;

            if response
                .data
                .iter()
                .any(|thread_id| thread_id == raw_thread_id)
            {
                return Ok(true);
            }

            match response.next_cursor {
                Some(next) if !next.is_empty() => cursor = Some(next),
                _ => return Ok(false),
            }
        }
    }

    pub async fn close(&mut self) {
        let mut child_guard = self.child.lock().await;
        if let Some(ref mut child) = child_guard.take() {
            let _ = child.kill().await;
        }
    }

    fn spawn_reader_task(&self, stdout: tokio::process::ChildStdout) {
        let stdin = self.stdin.clone();
        let pending = self.pending.clone();
        let event_tx = self.event_tx.clone();
        let initialized = self.initialized.clone();
        let permission_requests = self.pending_permission_requests.clone();
        let permission_request_seq = self.permission_request_seq.clone();

        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();

            while let Ok(Some(line)) = lines.next_line().await {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }

                tracing::debug!("Codex app-server stdout: {}", trimmed);

                let Ok(value) = serde_json::from_str::<sj::Value>(trimmed) else {
                    tracing::warn!("Failed to parse Codex app-server line: {}", trimmed);
                    continue;
                };

                if is_response_message(&value) {
                    handle_response_message(value, &pending).await;
                    continue;
                }

                if let Some(method) = value
                    .get("method")
                    .and_then(sj::Value::as_str)
                    .map(str::to_string)
                {
                    if value.get("id").is_some() {
                        handle_server_request(
                            value,
                            &method,
                            &stdin,
                            &permission_requests,
                            &permission_request_seq,
                            &event_tx,
                        )
                        .await;
                    } else {
                        handle_notification(&method, value, &event_tx).await;
                    }
                }
            }

            {
                let mut pending = pending.lock().await;
                for (_, callback) in pending.drain() {
                    callback(Err("Codex app-server exited".to_string()));
                }
            }
            *initialized.write().await = false;
            tracing::warn!("Codex app-server reader task ended");
        });
    }

    async fn send_request_value(
        &self,
        method: &str,
        params: sj::Value,
    ) -> Result<sj::Value, String> {
        let id = {
            let mut guard = self.request_id.lock().await;
            let id = *guard;
            *guard += 1;
            id
        };

        tracing::debug!(
            "Codex app-server request id={} method={} params={}",
            id,
            method,
            params
        );

        let (tx, rx) = tokio::sync::oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.insert(
                id,
                Box::new(move |result| {
                    let _ = tx.send(result);
                }),
            );
        }

        self.write_value(&json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }))
        .await?;

        tokio::select! {
            result = rx => {
                match result {
                    Ok(Ok(value)) => Ok(value),
                    Ok(Err(err)) => Err(err),
                    Err(_) => Err("Codex response channel closed".to_string()),
                }
            }
            _ = tokio::time::sleep(tokio::time::Duration::from_secs(120)) => {
                Err(format!("Codex request timed out: {method}"))
            }
        }
    }

    async fn send_request_typed<T: for<'de> Deserialize<'de>>(
        &self,
        method: &str,
        params: sj::Value,
    ) -> Result<T, String> {
        let value = self.send_request_value(method, params).await?;
        serde_json::from_value(value)
            .map_err(|e| format!("Failed to parse Codex {method} response: {e}"))
    }

    async fn send_notification(
        &self,
        method: &str,
        params: Option<sj::Value>,
    ) -> Result<(), String> {
        let mut value = json!({
            "jsonrpc": "2.0",
            "method": method,
        });
        if let Some(params) = params {
            value["params"] = params;
        }
        self.write_value(&value).await
    }

    async fn write_value(&self, value: &sj::Value) -> Result<(), String> {
        write_value_to_stdin(&self.stdin, value).await
    }
}

fn codex_thread_config() -> sj::Value {
    json!({
        "allow_login_shell": false,
        "shell_environment_policy": {
            "inherit": "core",
            "set": {
                "BASH_ENV": "/dev/null",
                "ENV": "/dev/null"
            }
        }
    })
}

fn codex_thread_start_params(cwd: &str) -> sj::Value {
    json!({
        "cwd": normalize_cwd(cwd),
        "persistFullHistory": true,
        "approvalPolicy": CODEX_YOLO_APPROVAL_POLICY,
        "sandbox": CODEX_YOLO_SANDBOX,
        "config": codex_thread_config(),
    })
}

fn codex_thread_resume_params(thread_id: &str, cwd: &str) -> sj::Value {
    json!({
        "threadId": thread_id,
        "cwd": normalize_cwd(cwd),
        "persistFullHistory": true,
        "approvalPolicy": CODEX_YOLO_APPROVAL_POLICY,
        "sandbox": CODEX_YOLO_SANDBOX,
        "config": codex_thread_config(),
    })
}

fn codex_thread_fork_params(thread_id: &str, cwd: &str) -> sj::Value {
    json!({
        "threadId": thread_id,
        "cwd": normalize_cwd(cwd),
        "persistFullHistory": true,
        "approvalPolicy": CODEX_YOLO_APPROVAL_POLICY,
        "sandbox": CODEX_YOLO_SANDBOX,
        "config": codex_thread_config(),
    })
}

fn codex_turn_start_params(thread_id: &str, message: &str) -> sj::Value {
    json!({
        "threadId": thread_id,
        "approvalPolicy": CODEX_YOLO_APPROVAL_POLICY,
        "sandboxPolicy": {
            "type": "dangerFullAccess",
        },
        "input": [
            {
                "type": "text",
                "text": message,
            }
        ]
    })
}

fn normalize_cwd(cwd: &str) -> &str {
    let normalized = cwd.trim_end_matches('/');
    if normalized.is_empty() {
        "/"
    } else {
        normalized
    }
}

fn spawn_stderr_drain_task(stderr: ChildStderr, process_name: &'static str) {
    tokio::spawn(async move {
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let trimmed = line.trim();
            if !trimmed.is_empty() {
                tracing::debug!("{process_name} stderr: {trimmed}");
            }
        }
    });
}

async fn write_value_to_stdin(
    stdin: &Arc<Mutex<Option<ChildStdin>>>,
    value: &sj::Value,
) -> Result<(), String> {
    let mut line = serde_json::to_vec(value)
        .map_err(|e| format!("Failed to serialize Codex app-server message: {e}"))?;
    line.push(b'\n');

    let mut stdin_guard = stdin.lock().await;
    let Some(stdin) = stdin_guard.as_mut() else {
        return Err("Codex app-server stdin unavailable".to_string());
    };

    stdin
        .write_all(&line)
        .await
        .map_err(|e| format!("Failed to write to Codex app-server stdin: {e}"))?;
    stdin
        .flush()
        .await
        .map_err(|e| format!("Failed to flush Codex app-server stdin: {e}"))?;
    Ok(())
}

fn is_response_message(value: &sj::Value) -> bool {
    value.get("id").is_some()
        && (value.get("result").is_some() || value.get("error").is_some())
        && value.get("method").is_none()
}

async fn handle_response_message(
    value: sj::Value,
    pending: &Arc<Mutex<HashMap<u64, RequestCallback>>>,
) {
    let Some(id) = value.get("id").and_then(sj::Value::as_u64) else {
        return;
    };

    let callback = {
        let mut pending = pending.lock().await;
        pending.remove(&id)
    };

    let Some(callback) = callback else {
        return;
    };

    if let Some(result) = value.get("result") {
        tracing::debug!("Codex app-server response id={} result={}", id, result);
        callback(Ok(result.clone()));
        return;
    }

    let message = value
        .get("error")
        .and_then(|error| error.get("message"))
        .and_then(sj::Value::as_str)
        .unwrap_or("unknown Codex app-server error")
        .to_string();
    tracing::debug!("Codex app-server response id={} error={}", id, message);
    callback(Err(message));
}

async fn handle_notification(method: &str, value: sj::Value, event_tx: &mpsc::Sender<CodexEvent>) {
    let params = value.get("params").cloned().unwrap_or(sj::Value::Null);

    match method {
        "item/agentMessage/delta" => {
            if let Ok(notification) =
                serde_json::from_value::<AgentMessageDeltaNotification>(params)
            {
                let _ = event_tx
                    .send(CodexEvent::SessionUpdate {
                        session_id: external_session_id(&notification.thread_id),
                        update: CodexSessionUpdate::AgentMessageChunk {
                            content: notification.delta,
                            is_thinking: false,
                        },
                    })
                    .await;
            }
        }
        "item/reasoning/summaryTextDelta" => {
            if let Ok(notification) = serde_json::from_value::<ReasoningDeltaNotification>(params) {
                let _ = event_tx
                    .send(CodexEvent::SessionUpdate {
                        session_id: external_session_id(&notification.thread_id),
                        update: CodexSessionUpdate::AgentMessageChunk {
                            content: notification.delta,
                            is_thinking: true,
                        },
                    })
                    .await;
            }
        }
        "item/reasoning/textDelta" => {
            if let Ok(notification) = serde_json::from_value::<ReasoningDeltaNotification>(params) {
                let _ = event_tx
                    .send(CodexEvent::SessionUpdate {
                        session_id: external_session_id(&notification.thread_id),
                        update: CodexSessionUpdate::AgentMessageChunk {
                            content: notification.delta,
                            is_thinking: true,
                        },
                    })
                    .await;
            }
        }
        "item/started" => {
            if let Ok(notification) = serde_json::from_value::<ItemNotification>(params) {
                if let Some(update) = map_item_to_tool_call(notification.item) {
                    let _ = event_tx
                        .send(CodexEvent::SessionUpdate {
                            session_id: external_session_id(&notification.thread_id),
                            update,
                        })
                        .await;
                }
            }
        }
        "item/completed" => {
            if let Ok(notification) = serde_json::from_value::<ItemNotification>(params) {
                if let Some(update) = map_item_to_tool_result(notification.item) {
                    let _ = event_tx
                        .send(CodexEvent::SessionUpdate {
                            session_id: external_session_id(&notification.thread_id),
                            update,
                        })
                        .await;
                }
            }
        }
        "turn/completed" => {
            if let Ok(notification) = serde_json::from_value::<TurnCompletedNotification>(params) {
                let stop_reason = match notification.turn.status {
                    TurnStatus::Completed => "completed",
                    TurnStatus::Interrupted => "interrupted",
                    TurnStatus::Failed => "failed",
                    TurnStatus::InProgress => "in_progress",
                }
                .to_string();

                let _ = event_tx
                    .send(CodexEvent::PromptDone {
                        session_id: external_session_id(&notification.thread_id),
                        stop_reason,
                        total_tokens: 0,
                    })
                    .await;

                if let Some(error) = notification.turn.error.and_then(|error| error.message) {
                    let _ = event_tx.send(CodexEvent::Error { message: error }).await;
                }
            }
        }
        "error" => {
            if let Some(message) = params
                .get("error")
                .and_then(|error| error.get("message"))
                .and_then(sj::Value::as_str)
            {
                let _ = event_tx
                    .send(CodexEvent::Error {
                        message: message.to_string(),
                    })
                    .await;
            }
        }
        _ => {}
    }
}

async fn handle_server_request(
    value: sj::Value,
    method: &str,
    stdin: &Arc<Mutex<Option<ChildStdin>>>,
    pending_permission_requests: &Arc<Mutex<HashMap<String, PendingPermissionRequest>>>,
    permission_request_seq: &Arc<Mutex<u64>>,
    event_tx: &mpsc::Sender<CodexEvent>,
) {
    let request_id = value.get("id").cloned().unwrap_or(sj::Value::Null);
    let params = value.get("params").cloned().unwrap_or(sj::Value::Null);

    let handled = match method {
        "item/commandExecution/requestApproval" => {
            if let Ok(params) =
                serde_json::from_value::<CommandExecutionRequestApprovalParams>(params)
            {
                let summary = params
                    .command
                    .clone()
                    .or_else(|| params.reason.clone())
                    .unwrap_or_else(|| "Command execution requires approval".to_string());

                let external_request_id = next_permission_request_id(permission_request_seq).await;
                let pending = PendingPermissionRequest::CommandExecution {
                    jsonrpc_request_id: request_id.clone(),
                    command: params.command,
                };

                Some((
                    external_request_id,
                    pending,
                    CodexEvent::PermissionRequest {
                        request_id: String::new(),
                        session_id: external_session_id(&params.thread_id),
                        tool: "Command Execution".to_string(),
                        command: summary,
                        options: approval_options("Approve", "Deny"),
                    },
                ))
            } else {
                None
            }
        }
        "item/fileChange/requestApproval" => {
            if let Ok(params) = serde_json::from_value::<FileChangeRequestApprovalParams>(params) {
                let command = if let Some(reason) = params.reason.clone() {
                    reason
                } else if let Some(grant_root) = params.grant_root.clone() {
                    grant_root
                } else {
                    "File changes require approval".to_string()
                };

                let external_request_id = next_permission_request_id(permission_request_seq).await;
                let pending = PendingPermissionRequest::FileChange {
                    jsonrpc_request_id: request_id.clone(),
                    paths: params.grant_root.into_iter().collect(),
                };

                Some((
                    external_request_id,
                    pending,
                    CodexEvent::PermissionRequest {
                        request_id: String::new(),
                        session_id: external_session_id(&params.thread_id),
                        tool: "File Change".to_string(),
                        command,
                        options: approval_options("Approve", "Deny"),
                    },
                ))
            } else {
                None
            }
        }
        "item/permissions/requestApproval" => {
            if let Ok(params) = serde_json::from_value::<PermissionsRequestApprovalParams>(params) {
                let command = params
                    .reason
                    .clone()
                    .unwrap_or_else(|| format_permission_request(&params.permissions));

                let external_request_id = next_permission_request_id(permission_request_seq).await;
                let pending = PendingPermissionRequest::Permissions {
                    jsonrpc_request_id: request_id.clone(),
                    requested_permissions: params.permissions,
                };

                Some((
                    external_request_id,
                    pending,
                    CodexEvent::PermissionRequest {
                        request_id: String::new(),
                        session_id: external_session_id(&params.thread_id),
                        tool: "Permissions".to_string(),
                        command,
                        options: approval_options("Allow", "Deny"),
                    },
                ))
            } else {
                None
            }
        }
        _ => None,
    };

    let Some((external_request_id, pending, mut event)) = handled else {
        let _ = write_value_to_stdin(
            stdin,
            &json!({
                "id": request_id,
                "error": {
                    "code": -32601,
                    "message": format!("unsupported Codex app-server request: {method}"),
                }
            }),
        )
        .await;
        return;
    };

    {
        let mut pending_map = pending_permission_requests.lock().await;
        pending_map.insert(external_request_id.clone(), pending);
    }

    if let CodexEvent::PermissionRequest { request_id, .. } = &mut event {
        *request_id = external_request_id;
    }

    let _ = event_tx.send(event).await;
}

async fn next_permission_request_id(permission_request_seq: &Arc<Mutex<u64>>) -> String {
    let mut guard = permission_request_seq.lock().await;
    let current = *guard;
    *guard += 1;
    format!("codex:{current}")
}

fn approval_options(approve_label: &str, deny_label: &str) -> Vec<PermissionOption> {
    vec![
        PermissionOption {
            id: "approve".to_string(),
            label: approve_label.to_string(),
        },
        PermissionOption {
            id: "deny".to_string(),
            label: deny_label.to_string(),
        },
    ]
}

fn external_session_id(raw_thread_id: &str) -> String {
    format!("{CODEX_PROVIDER}:{raw_thread_id}")
}

pub fn decode_external_session_id(session_id: &str) -> (&str, String) {
    match session_id.split_once(':') {
        Some((provider, raw_id)) if !provider.is_empty() && !raw_id.is_empty() => {
            (provider, raw_id.to_string())
        }
        _ => ("opencode", session_id.to_string()),
    }
}

fn map_thread_summary_to_session(thread: ThreadSummary) -> SessionInfo {
    SessionInfo {
        session_id: external_session_id(&thread.id),
        cwd: thread.cwd,
        title: thread
            .name
            .filter(|name| !name.trim().is_empty())
            .unwrap_or_else(|| {
                thread
                    .preview
                    .lines()
                    .next()
                    .unwrap_or("")
                    .trim()
                    .to_string()
            }),
        updated_at: timestamp_to_rfc3339(thread.updated_at),
        provider: Some(CODEX_PROVIDER.to_string()),
    }
}

fn timestamp_to_rfc3339(seconds: i64) -> String {
    Utc.timestamp_opt(seconds, 0)
        .single()
        .unwrap_or_else(Utc::now)
        .to_rfc3339()
}

fn turn_timestamp(turn: &ReadTurn) -> Option<chrono::DateTime<Utc>> {
    turn.completed_at
        .or(turn.started_at)
        .and_then(|seconds| Utc.timestamp_opt(seconds, 0).single())
}

fn item_to_history_messages(
    item: ThreadItem,
    timestamp: Option<chrono::DateTime<Utc>>,
) -> Vec<ChatMessage> {
    match item {
        ThreadItem::UserMessage { content, .. } => {
            let blocks = content
                .into_iter()
                .filter_map(|input| match input {
                    UserInput::Text { text, .. } if !text.is_empty() => {
                        Some(ContentBlock::Text { text })
                    }
                    UserInput::Image { url } => Some(ContentBlock::Text {
                        text: format!("Image: {url}"),
                    }),
                    UserInput::LocalImage { path } => Some(ContentBlock::Text {
                        text: format!("Image: {path}"),
                    }),
                    UserInput::Skill { name, path } => Some(ContentBlock::Text {
                        text: format!("Skill: {name} ({path})"),
                    }),
                    UserInput::Mention { name, path } => Some(ContentBlock::Text {
                        text: format!("@{name}: {path}"),
                    }),
                    _ => None,
                })
                .collect::<Vec<_>>();

            if blocks.is_empty() {
                Vec::new()
            } else {
                vec![ChatMessage {
                    role: "user".to_string(),
                    timestamp,
                    blocks,
                }]
            }
        }
        ThreadItem::AgentMessage { text, .. } if !text.is_empty() => {
            vec![ChatMessage {
                role: "assistant".to_string(),
                timestamp,
                blocks: vec![ContentBlock::Text { text }],
            }]
        }
        ThreadItem::Reasoning {
            summary, content, ..
        } => {
            let thinking = if !summary.is_empty() {
                summary.join("")
            } else {
                content.join("")
            };
            if thinking.is_empty() {
                Vec::new()
            } else {
                vec![ChatMessage {
                    role: "assistant".to_string(),
                    timestamp,
                    blocks: vec![ContentBlock::Thinking { content: thinking }],
                }]
            }
        }
        other => {
            let mut messages = Vec::new();
            if let Some(call) = map_item_to_tool_call(other.clone()) {
                if let CodexSessionUpdate::ToolCall {
                    title,
                    kind,
                    raw_input,
                    ..
                } = call
                {
                    messages.push(ChatMessage {
                        role: "assistant".to_string(),
                        timestamp,
                        blocks: vec![ContentBlock::ToolCall {
                            name: title,
                            summary: kind,
                            input: raw_input,
                        }],
                    });
                }
            }
            if let Some(result) = map_item_to_tool_result(other) {
                if let CodexSessionUpdate::ToolResult {
                    tool_call_id,
                    status,
                    output,
                } = result
                {
                    messages.push(ChatMessage {
                        role: "tool".to_string(),
                        timestamp,
                        blocks: vec![ContentBlock::ToolResult {
                            tool_name: tool_call_id,
                            summary: status,
                            content: if output.is_empty() {
                                None
                            } else {
                                Some(output)
                            },
                        }],
                    });
                }
            }
            messages
        }
    }
}

fn map_item_to_tool_call(item: ThreadItem) -> Option<CodexSessionUpdate> {
    match item {
        ThreadItem::CommandExecution {
            id,
            command,
            cwd,
            status,
            ..
        } => Some(CodexSessionUpdate::ToolCall {
            tool_call_id: id,
            title: "Command".to_string(),
            kind: format_command_kind(status),
            status: "running".to_string(),
            raw_input: Some(json!({
                "command": command,
                "cwd": cwd,
            })),
        }),
        ThreadItem::FileChange {
            id,
            changes,
            status,
        } => Some(CodexSessionUpdate::ToolCall {
            tool_call_id: id,
            title: "File Change".to_string(),
            kind: format_patch_kind(status),
            status: "running".to_string(),
            raw_input: Some(json!({
                "changes": changes.into_iter().map(|change| change.path).collect::<Vec<_>>(),
            })),
        }),
        ThreadItem::McpToolCall {
            id,
            server,
            tool,
            arguments,
            ..
        } => Some(CodexSessionUpdate::ToolCall {
            tool_call_id: id,
            title: tool,
            kind: format!("mcp:{server}"),
            status: "running".to_string(),
            raw_input: Some(arguments),
        }),
        ThreadItem::DynamicToolCall {
            id,
            tool,
            arguments,
            ..
        } => Some(CodexSessionUpdate::ToolCall {
            tool_call_id: id,
            title: tool,
            kind: "dynamic-tool".to_string(),
            status: "running".to_string(),
            raw_input: Some(arguments),
        }),
        ThreadItem::WebSearch { id, query, .. } => Some(CodexSessionUpdate::ToolCall {
            tool_call_id: id,
            title: "Web Search".to_string(),
            kind: "web-search".to_string(),
            status: "running".to_string(),
            raw_input: Some(json!({ "query": query })),
        }),
        _ => None,
    }
}

fn map_item_to_tool_result(item: ThreadItem) -> Option<CodexSessionUpdate> {
    match item {
        ThreadItem::CommandExecution {
            id,
            status,
            aggregated_output,
            exit_code,
            ..
        } => Some(CodexSessionUpdate::ToolResult {
            tool_call_id: id,
            status: format_command_status(status, exit_code),
            output: aggregated_output.unwrap_or_default(),
        }),
        ThreadItem::FileChange {
            id,
            changes,
            status,
        } => Some(CodexSessionUpdate::ToolResult {
            tool_call_id: id,
            status: format_patch_status(status),
            output: changes
                .into_iter()
                .map(|change| format!("{}:\n{}", change.path, change.diff))
                .collect::<Vec<_>>()
                .join("\n\n"),
        }),
        ThreadItem::McpToolCall {
            id,
            status,
            result,
            error,
            ..
        } => Some(CodexSessionUpdate::ToolResult {
            tool_call_id: id,
            status: format_mcp_status(status),
            output: format_mcp_output(result, error),
        }),
        ThreadItem::DynamicToolCall {
            id,
            status,
            content_items,
            success,
            ..
        } => Some(CodexSessionUpdate::ToolResult {
            tool_call_id: id,
            status: format_dynamic_status(status, success),
            output: format_dynamic_output(content_items),
        }),
        ThreadItem::WebSearch { id, query, .. } => Some(CodexSessionUpdate::ToolResult {
            tool_call_id: id,
            status: "completed".to_string(),
            output: query,
        }),
        _ => None,
    }
}

fn format_command_kind(status: CommandExecutionStatus) -> String {
    match status {
        CommandExecutionStatus::InProgress => "command".to_string(),
        CommandExecutionStatus::Completed => "command".to_string(),
        CommandExecutionStatus::Failed => "command".to_string(),
        CommandExecutionStatus::Declined => "command".to_string(),
    }
}

fn format_command_status(status: CommandExecutionStatus, exit_code: Option<i32>) -> String {
    match status {
        CommandExecutionStatus::InProgress => "running".to_string(),
        CommandExecutionStatus::Completed => format!(
            "completed{}",
            exit_code
                .map(|code| format!(" ({code})"))
                .unwrap_or_default()
        ),
        CommandExecutionStatus::Failed => format!(
            "failed{}",
            exit_code
                .map(|code| format!(" ({code})"))
                .unwrap_or_default()
        ),
        CommandExecutionStatus::Declined => "declined".to_string(),
    }
}

fn format_patch_kind(_status: PatchApplyStatus) -> String {
    "file-change".to_string()
}

fn format_patch_status(status: PatchApplyStatus) -> String {
    match status {
        PatchApplyStatus::InProgress => "running".to_string(),
        PatchApplyStatus::Completed => "completed".to_string(),
        PatchApplyStatus::Failed => "failed".to_string(),
        PatchApplyStatus::Declined => "declined".to_string(),
    }
}

fn format_mcp_status(status: McpToolCallStatus) -> String {
    match status {
        McpToolCallStatus::InProgress => "running".to_string(),
        McpToolCallStatus::Completed => "completed".to_string(),
        McpToolCallStatus::Failed => "failed".to_string(),
    }
}

fn format_dynamic_status(status: DynamicToolCallStatus, success: Option<bool>) -> String {
    match status {
        DynamicToolCallStatus::InProgress => "running".to_string(),
        DynamicToolCallStatus::Completed => {
            if success == Some(false) {
                "failed".to_string()
            } else {
                "completed".to_string()
            }
        }
        DynamicToolCallStatus::Failed => "failed".to_string(),
    }
}

fn format_mcp_output(result: Option<McpToolCallResult>, error: Option<McpToolCallError>) -> String {
    if let Some(error) = error {
        return error.message;
    }

    result
        .map(|result| {
            result
                .content
                .into_iter()
                .map(|item| {
                    item.get("text")
                        .and_then(sj::Value::as_str)
                        .map(str::to_string)
                        .unwrap_or_else(|| item.to_string())
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default()
}

fn format_dynamic_output(content_items: Option<Vec<DynamicToolCallOutputContentItem>>) -> String {
    content_items
        .unwrap_or_default()
        .into_iter()
        .map(|item| match item {
            DynamicToolCallOutputContentItem::InputText { text } => text,
            DynamicToolCallOutputContentItem::InputImage { image_url } => image_url,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn format_permission_request(permissions: &sj::Value) -> String {
    serde_json::to_string_pretty(permissions).unwrap_or_else(|_| "Permissions request".to_string())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadListResponse {
    data: Vec<ThreadSummary>,
    next_cursor: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadLoadedListResponse {
    data: Vec<String>,
    next_cursor: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadSummary {
    id: String,
    preview: String,
    cwd: String,
    updated_at: i64,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadStartResponse {
    thread: ThreadSummary,
    model: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadResumeResponse {
    thread: ThreadSummary,
    model: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadForkResponse {
    thread: ThreadSummary,
    model: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadReadResponse {
    thread: ReadThread,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadThread {
    turns: Vec<ReadTurn>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadTurn {
    items: Vec<ThreadItem>,
    started_at: Option<i64>,
    completed_at: Option<i64>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
enum ThreadItem {
    UserMessage {
        id: String,
        content: Vec<UserInput>,
    },
    AgentMessage {
        id: String,
        text: String,
    },
    Reasoning {
        id: String,
        #[serde(default)]
        summary: Vec<String>,
        #[serde(default)]
        content: Vec<String>,
    },
    CommandExecution {
        id: String,
        command: String,
        cwd: String,
        status: CommandExecutionStatus,
        aggregated_output: Option<String>,
        exit_code: Option<i32>,
    },
    FileChange {
        id: String,
        changes: Vec<FileUpdateChange>,
        status: PatchApplyStatus,
    },
    McpToolCall {
        id: String,
        server: String,
        tool: String,
        status: McpToolCallStatus,
        arguments: sj::Value,
        result: Option<McpToolCallResult>,
        error: Option<McpToolCallError>,
    },
    DynamicToolCall {
        id: String,
        tool: String,
        arguments: sj::Value,
        status: DynamicToolCallStatus,
        content_items: Option<Vec<DynamicToolCallOutputContentItem>>,
        success: Option<bool>,
    },
    WebSearch {
        id: String,
        query: String,
    },
    #[serde(other)]
    Other,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
enum UserInput {
    Text { text: String },
    Image { url: String },
    LocalImage { path: String },
    Skill { name: String, path: String },
    Mention { name: String, path: String },
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum CommandExecutionStatus {
    InProgress,
    Completed,
    Failed,
    Declined,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum PatchApplyStatus {
    InProgress,
    Completed,
    Failed,
    Declined,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum McpToolCallStatus {
    InProgress,
    Completed,
    Failed,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
enum DynamicToolCallStatus {
    InProgress,
    Completed,
    Failed,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct FileUpdateChange {
    path: String,
    diff: String,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct McpToolCallResult {
    content: Vec<sj::Value>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct McpToolCallError {
    message: String,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
enum DynamicToolCallOutputContentItem {
    InputText { text: String },
    InputImage { image_url: String },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentMessageDeltaNotification {
    thread_id: String,
    delta: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReasoningDeltaNotification {
    thread_id: String,
    delta: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ItemNotification {
    thread_id: String,
    item: ThreadItem,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TurnCompletedNotification {
    thread_id: String,
    turn: CompletedTurn,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompletedTurn {
    status: TurnStatus,
    error: Option<TurnError>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
enum TurnStatus {
    Completed,
    Interrupted,
    Failed,
    InProgress,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TurnError {
    message: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CommandExecutionRequestApprovalParams {
    thread_id: String,
    command: Option<String>,
    reason: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FileChangeRequestApprovalParams {
    thread_id: String,
    reason: Option<String>,
    grant_root: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PermissionsRequestApprovalParams {
    thread_id: String,
    reason: Option<String>,
    permissions: sj::Value,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codex_thread_start_params_enable_yolo_mode() {
        let params = codex_thread_start_params("/tmp/project/");

        assert_eq!(
            params.get("cwd").and_then(sj::Value::as_str),
            Some("/tmp/project")
        );
        assert_eq!(
            params.get("approvalPolicy").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_APPROVAL_POLICY)
        );
        assert_eq!(
            params.get("sandbox").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_SANDBOX)
        );
        assert_eq!(
            params
                .get("persistFullHistory")
                .and_then(sj::Value::as_bool),
            Some(true)
        );
    }

    #[test]
    fn codex_thread_resume_params_enable_yolo_mode() {
        let params = codex_thread_resume_params("thread_123", "/tmp/project/");

        assert_eq!(
            params.get("threadId").and_then(sj::Value::as_str),
            Some("thread_123")
        );
        assert_eq!(
            params.get("approvalPolicy").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_APPROVAL_POLICY)
        );
        assert_eq!(
            params.get("sandbox").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_SANDBOX)
        );
    }

    #[test]
    fn codex_thread_fork_params_enable_yolo_mode() {
        let params = codex_thread_fork_params("thread_456", "/tmp/project/");

        assert_eq!(
            params.get("threadId").and_then(sj::Value::as_str),
            Some("thread_456")
        );
        assert_eq!(
            params.get("approvalPolicy").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_APPROVAL_POLICY)
        );
        assert_eq!(
            params.get("sandbox").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_SANDBOX)
        );
    }

    #[test]
    fn codex_turn_start_params_enable_yolo_mode() {
        let params = codex_turn_start_params("thread_789", "Run the tests");

        assert_eq!(
            params.get("threadId").and_then(sj::Value::as_str),
            Some("thread_789")
        );
        assert_eq!(
            params.get("approvalPolicy").and_then(sj::Value::as_str),
            Some(CODEX_YOLO_APPROVAL_POLICY)
        );
        assert_eq!(
            params
                .get("sandboxPolicy")
                .and_then(|policy| policy.get("type"))
                .and_then(sj::Value::as_str),
            Some("dangerFullAccess")
        );
        assert_eq!(
            params
                .get("input")
                .and_then(sj::Value::as_array)
                .and_then(|input| input.first())
                .and_then(|value| value.get("text"))
                .and_then(sj::Value::as_str),
            Some("Run the tests")
        );
    }

    #[test]
    fn normalize_cwd_preserves_root_directory() {
        assert_eq!(normalize_cwd("/"), "/");
    }
}
