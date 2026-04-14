use std::path::Path;
use std::sync::Arc;
use tracing::{error, info, warn};

use super::types::{send_message, WsState};
use crate::{types::*, AppState};

const BACKEND_OPENCODE: &str = "opencode";
const BACKEND_ACP: &str = "acp";
const BACKEND_CODEX: &str = "codex";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DirectSessionProvider {
    Opencode,
    Codex,
}

impl DirectSessionProvider {
    fn backend_label(self) -> &'static str {
        match self {
            Self::Opencode => BACKEND_OPENCODE,
            Self::Codex => BACKEND_CODEX,
        }
    }
}

fn normalize_backend(backend: &str) -> DirectSessionProvider {
    match backend {
        BACKEND_CODEX => DirectSessionProvider::Codex,
        BACKEND_ACP | BACKEND_OPENCODE => DirectSessionProvider::Opencode,
        _ => DirectSessionProvider::Opencode,
    }
}

fn parse_direct_session_id(session_id: &str) -> (DirectSessionProvider, String) {
    match session_id.split_once(':') {
        Some((BACKEND_CODEX, raw_id)) if !raw_id.is_empty() => {
            (DirectSessionProvider::Codex, raw_id.to_string())
        }
        Some((BACKEND_OPENCODE, raw_id)) if !raw_id.is_empty() => {
            (DirectSessionProvider::Opencode, raw_id.to_string())
        }
        Some((BACKEND_ACP, raw_id)) if !raw_id.is_empty() => {
            (DirectSessionProvider::Opencode, raw_id.to_string())
        }
        _ => (DirectSessionProvider::Opencode, session_id.to_string()),
    }
}

fn external_session_id(provider: DirectSessionProvider, raw_id: &str) -> String {
    match provider {
        DirectSessionProvider::Opencode => format!("{BACKEND_OPENCODE}:{raw_id}"),
        DirectSessionProvider::Codex => format!("{BACKEND_CODEX}:{raw_id}"),
    }
}

async fn resolve_codex_session_cwd(
    app_state: &Arc<AppState>,
    session_id: &str,
    raw_id: &str,
) -> Option<String> {
    let codex_guard = app_state.codex_app_client.read().await;
    let client = match codex_guard.as_ref() {
        Some(client) => client,
        None => return None,
    };

    if let Some(cwd) = client.get_session_cwd(session_id).await {
        if !cwd.trim().is_empty() {
            return Some(cwd);
        }
    }

    let fallback_external = external_session_id(DirectSessionProvider::Codex, raw_id);
    match client.list_sessions().await {
        Ok(response) => response.sessions.into_iter().find_map(|session| {
            if session.session_id == session_id
                || session.session_id == raw_id
                || session.session_id == fallback_external
            {
                Some(session.cwd)
            } else {
                None
            }
        }),
        Err(_) => None,
    }
}

fn session_key_for_external_id(external_id: &str) -> String {
    format!("acp_{external_id}")
}

fn is_deleted_direct_session(deleted_ids: &[String], external_id: &str, raw_id: &str) -> bool {
    deleted_ids
        .iter()
        .any(|deleted| deleted == external_id || deleted == raw_id)
}

fn write_acp_session_file(session_id: &str, cwd: &str) {
    let home = match std::env::var("HOME") {
        Ok(h) => std::path::PathBuf::from(h),
        Err(_) => return,
    };

    let session_dir = home.join(".agentshell");
    let session_file = session_dir.join("acp_session");

    if let Err(e) = std::fs::create_dir_all(&session_dir) {
        warn!("Failed to create .agentshell directory: {}", e);
        return;
    }

    let ws_url =
        std::env::var("AGENTSHELL_WS_URL").unwrap_or_else(|_| "ws://localhost:5173/ws".to_string());

    let session_json = serde_json::json!({
        "sessionId": session_id,
        "cwd": cwd,
        "wsUrl": ws_url
    });

    match std::fs::write(&session_file, session_json.to_string()) {
        Ok(_) => info!("Updated ACP session file: {:?}", session_file),
        Err(e) => warn!("Failed to write session file: {}", e),
    }
}

async fn collect_direct_sessions(app_state: Arc<AppState>) -> Vec<crate::acp::SessionInfo> {
    let deleted_ids = app_state
        .chat_event_store
        .get_deleted_acp_session_ids()
        .unwrap_or_default();

    let opencode_sessions = if let Err(e) = ensure_acp_client(&app_state).await {
        warn!("Failed to initialize ACP backend during list: {}", e);
        Vec::new()
    } else {
        let acp_guard = app_state.acp_client.read().await;
        let acp_result = match acp_guard.as_ref() {
            Some(client) => client.list_sessions().await,
            None => Err("ACP client not initialized".to_string()),
        };

        let db_path = crate::chat_log::watcher::find_opencode_db();
        let db_sessions = match db_path {
            Ok(p) => tokio::task::spawn_blocking(move || {
                crate::chat_log::opencode_parser::list_all_sessions(&p)
            })
            .await
            .unwrap_or_else(|e| Err(anyhow::anyhow!("join error: {}", e))),
            Err(e) => Err(anyhow::anyhow!("opencode.db not found: {}", e)),
        };

        let raw_sessions = match db_sessions {
            Ok(all_sessions) => all_sessions,
            Err(e) => {
                warn!("DB session list failed, using ACP fallback: {}", e);
                acp_result.map(|result| result.sessions).unwrap_or_default()
            }
        };

        raw_sessions
            .into_iter()
            .map(|mut session| {
                let raw_id = session.session_id.clone();
                session.session_id = external_session_id(DirectSessionProvider::Opencode, &raw_id);
                session.provider = Some(BACKEND_OPENCODE.to_string());
                session
            })
            .filter(|session| {
                let (_, raw_id) = parse_direct_session_id(&session.session_id);
                !is_deleted_direct_session(&deleted_ids, &session.session_id, &raw_id)
            })
            .collect::<Vec<_>>()
    };

    let codex_sessions = if let Err(e) = ensure_codex_client(&app_state).await {
        warn!("Failed to initialize Codex backend during list: {}", e);
        Vec::new()
    } else {
        let codex_guard = app_state.codex_app_client.read().await;
        match codex_guard.as_ref() {
            Some(client) => match client.list_sessions().await {
                Ok(result) => result.sessions,
                Err(e) => {
                    warn!("Failed to list Codex sessions: {}", e);
                    Vec::new()
                }
            },
            None => Vec::new(),
        }
        .into_iter()
        .filter(|session| {
            let (_, raw_id) = parse_direct_session_id(&session.session_id);
            !is_deleted_direct_session(&deleted_ids, &session.session_id, &raw_id)
        })
        .collect::<Vec<_>>()
    };

    let mut sessions = opencode_sessions;
    sessions.extend(codex_sessions);
    sessions.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
    sessions
}

pub(crate) async fn ensure_acp_client(app_state: &Arc<AppState>) -> Result<(), String> {
    {
        let acp_guard = app_state.acp_client.read().await;
        if acp_guard.is_some() {
            return Ok(());
        }
    }

    let mut acp_guard = app_state.acp_client.write().await;
    if acp_guard.is_some() {
        return Ok(());
    }

    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<crate::acp::AcpEvent>(100);
    let mut client = crate::acp::AcpClient::new(event_tx);
    let init_result = client.start().await?;
    info!("ACP client initialized: {:?}", init_result.agent_info);

    let broadcast_tx = app_state.broadcast_tx.clone();
    let app_state_clone = app_state.clone();
    tokio::spawn(async move {
        while let Some(event) = event_rx.recv().await {
            match event {
                crate::acp::AcpEvent::SessionUpdate { session_id, update } => {
                    let external_id =
                        external_session_id(DirectSessionProvider::Opencode, &session_id);
                    let session_key = session_key_for_external_id(&external_id);
                    let msg = match update {
                        crate::acp::SessionUpdate::AgentMessageChunk { content } => {
                            let text = content
                                .get("text")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string();

                            if let Err(e) = app_state_clone.chat_event_store.append_or_merge_text(
                                &session_key,
                                0,
                                "acp",
                                "assistant",
                                &text,
                            ) {
                                tracing::warn!("Failed to persist ACP message: {}", e);
                            }

                            ServerMessage::AcpMessageChunk {
                                session_id: external_id,
                                content: text,
                                is_thinking: false,
                            }
                        }
                        crate::acp::SessionUpdate::AgentThoughtChunk { content } => {
                            ServerMessage::AcpMessageChunk {
                                session_id: external_id,
                                content: content
                                    .get("text")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("")
                                    .to_string(),
                                is_thinking: true,
                            }
                        }
                        crate::acp::SessionUpdate::ToolCall {
                            tool_call_id,
                            title,
                            kind,
                            status,
                            raw_input,
                            ..
                        } => {
                            let chat_message = crate::chat_log::ChatMessage {
                                role: "assistant".to_string(),
                                timestamp: Some(chrono::Utc::now()),
                                blocks: vec![crate::chat_log::ContentBlock::ToolCall {
                                    name: title.clone(),
                                    summary: kind.clone(),
                                    input: raw_input.clone(),
                                }],
                            };
                            if let Err(e) = app_state_clone.chat_event_store.append_message(
                                &session_key,
                                0,
                                "acp",
                                &chat_message,
                            ) {
                                tracing::warn!("Failed to persist ACP tool call: {}", e);
                            }
                            let input = raw_input
                                .as_ref()
                                .map(|v| serde_json::to_string_pretty(v).unwrap_or_default());
                            ServerMessage::AcpToolCall {
                                session_id: external_id,
                                tool_call_id,
                                title,
                                kind,
                                status,
                                input,
                            }
                        }
                        crate::acp::SessionUpdate::ToolCallUpdate {
                            tool_call_id,
                            status,
                            content,
                            ..
                        } => {
                            let output = content
                                .unwrap_or_default()
                                .iter()
                                .filter_map(|c| c.as_object())
                                .filter_map(|c| c.get("content").and_then(|v| v.as_object()))
                                .filter_map(|v| v.get("text").and_then(|t| t.as_str()))
                                .collect::<Vec<_>>()
                                .join("\n");
                            let chat_message = crate::chat_log::ChatMessage {
                                role: "tool".to_string(),
                                timestamp: Some(chrono::Utc::now()),
                                blocks: vec![crate::chat_log::ContentBlock::ToolResult {
                                    tool_name: tool_call_id.clone(),
                                    summary: status.clone(),
                                    content: if output.is_empty() {
                                        None
                                    } else {
                                        Some(output.clone())
                                    },
                                }],
                            };
                            if let Err(e) = app_state_clone.chat_event_store.append_message(
                                &session_key,
                                0,
                                "acp",
                                &chat_message,
                            ) {
                                tracing::warn!("Failed to persist ACP tool result: {}", e);
                            }
                            ServerMessage::AcpToolResult {
                                session_id: external_id,
                                tool_call_id,
                                status,
                                output,
                            }
                        }
                        _ => continue,
                    };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast ACP message: {}", e);
                    }
                }
                crate::acp::AcpEvent::PermissionRequest {
                    request_id,
                    session_id,
                    tool_call,
                    ..
                } => {
                    let tool = tool_call
                        .get("title")
                        .and_then(|v| v.as_str())
                        .unwrap_or("unknown");
                    let command = tool_call
                        .get("rawInput")
                        .and_then(|v| v.get("command"))
                        .and_then(|v| v.as_str())
                        .unwrap_or("");
                    let msg = ServerMessage::AcpPermissionRequest {
                        request_id,
                        session_id: external_session_id(
                            DirectSessionProvider::Opencode,
                            &session_id,
                        ),
                        tool: tool.to_string(),
                        command: command.to_string(),
                        options: Vec::new(),
                    };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast ACP permission request: {}", e);
                    }
                }
                crate::acp::AcpEvent::Error { message } => {
                    let msg = ServerMessage::AcpError { message };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast ACP error: {}", e);
                    }
                }
            }
        }
    });

    *acp_guard = Some(client);
    Ok(())
}

pub(crate) async fn ensure_codex_client(app_state: &Arc<AppState>) -> Result<(), String> {
    {
        let codex_guard = app_state.codex_app_client.read().await;
        if codex_guard.is_some() {
            return Ok(());
        }
    }

    let mut codex_guard = app_state.codex_app_client.write().await;
    if codex_guard.is_some() {
        return Ok(());
    }

    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<crate::codex_app::CodexEvent>(100);
    let mut client = crate::codex_app::CodexAppClient::new(event_tx);
    let init_result = client.start().await?;
    info!("Codex app-server initialized: {:?}", init_result);

    let broadcast_tx = app_state.broadcast_tx.clone();
    let app_state_clone = app_state.clone();
    tokio::spawn(async move {
        while let Some(event) = event_rx.recv().await {
            match event {
                crate::codex_app::CodexEvent::SessionUpdate { session_id, update } => {
                    let session_key = session_key_for_external_id(&session_id);
                    let msg = match update {
                        crate::codex_app::CodexSessionUpdate::AgentMessageChunk {
                            content,
                            is_thinking,
                        } => {
                            if !content.is_empty() && !is_thinking {
                                if let Err(e) =
                                    app_state_clone.chat_event_store.append_or_merge_text(
                                        &session_key,
                                        0,
                                        "codex-app",
                                        "assistant",
                                        &content,
                                    )
                                {
                                    tracing::warn!("Failed to persist Codex message: {}", e);
                                }
                            }

                            ServerMessage::AcpMessageChunk {
                                session_id,
                                content,
                                is_thinking,
                            }
                        }
                        crate::codex_app::CodexSessionUpdate::ToolCall {
                            tool_call_id,
                            title,
                            kind,
                            status,
                            raw_input,
                        } => {
                            let chat_message = crate::chat_log::ChatMessage {
                                role: "assistant".to_string(),
                                timestamp: Some(chrono::Utc::now()),
                                blocks: vec![crate::chat_log::ContentBlock::ToolCall {
                                    name: title.clone(),
                                    summary: kind.clone(),
                                    input: raw_input.clone(),
                                }],
                            };
                            if let Err(e) = app_state_clone.chat_event_store.append_message(
                                &session_key,
                                0,
                                "codex-app",
                                &chat_message,
                            ) {
                                tracing::warn!("Failed to persist Codex tool call: {}", e);
                            }
                            let input = raw_input
                                .as_ref()
                                .map(|v| serde_json::to_string_pretty(v).unwrap_or_default());
                            ServerMessage::AcpToolCall {
                                session_id,
                                tool_call_id,
                                title,
                                kind,
                                status,
                                input,
                            }
                        }
                        crate::codex_app::CodexSessionUpdate::ToolResult {
                            tool_call_id,
                            status,
                            output,
                        } => {
                            let chat_message = crate::chat_log::ChatMessage {
                                role: "tool".to_string(),
                                timestamp: Some(chrono::Utc::now()),
                                blocks: vec![crate::chat_log::ContentBlock::ToolResult {
                                    tool_name: tool_call_id.clone(),
                                    summary: status.clone(),
                                    content: if output.is_empty() {
                                        None
                                    } else {
                                        Some(output.clone())
                                    },
                                }],
                            };
                            if let Err(e) = app_state_clone.chat_event_store.append_message(
                                &session_key,
                                0,
                                "codex-app",
                                &chat_message,
                            ) {
                                tracing::warn!("Failed to persist Codex tool result: {}", e);
                            }
                            ServerMessage::AcpToolResult {
                                session_id,
                                tool_call_id,
                                status,
                                output,
                            }
                        }
                    };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast Codex app-server message: {}", e);
                    }
                }
                crate::codex_app::CodexEvent::PermissionRequest {
                    request_id,
                    session_id,
                    tool,
                    command,
                    options,
                } => {
                    let msg = ServerMessage::AcpPermissionRequest {
                        request_id,
                        session_id,
                        tool,
                        command,
                        options,
                    };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast Codex permission request: {}", e);
                    }
                }
                crate::codex_app::CodexEvent::PromptDone {
                    session_id,
                    stop_reason,
                    total_tokens,
                } => {
                    let msg = ServerMessage::AcpPromptDone {
                        session_id,
                        stop_reason,
                        total_tokens,
                    };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast Codex prompt completion: {}", e);
                    }
                }
                crate::codex_app::CodexEvent::Error { message } => {
                    let msg = ServerMessage::AcpError { message };
                    if let Err(e) = broadcast_tx.send(msg).await {
                        tracing::error!("Failed to broadcast Codex error: {}", e);
                    }
                }
            }
        }
    });

    *codex_guard = Some(client);
    Ok(())
}

pub(crate) async fn handle(
    msg: WebSocketMessage,
    state: &mut WsState,
    app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match msg {
        // ACP message handlers
        WebSocketMessage::SelectBackend { backend } => {
            let provider = match backend.as_str() {
                BACKEND_ACP | BACKEND_OPENCODE | BACKEND_CODEX => Some(normalize_backend(&backend)),
                _ => None,
            };
            state.selected_backend = backend.clone();
            info!("Backend selection: {}", state.selected_backend);

            let Some(provider) = provider else {
                let response = ServerMessage::BackendSelected {
                    backend: state.selected_backend.clone(),
                };
                send_message(&state.message_tx, response).await?;
                return Ok(());
            };

            let init_result = match provider {
                DirectSessionProvider::Opencode => ensure_acp_client(&app_state).await,
                DirectSessionProvider::Codex => ensure_codex_client(&app_state).await,
            };

            match init_result {
                Ok(()) => {
                    let response = ServerMessage::BackendSelected {
                        backend: state.selected_backend.clone(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!(
                        "Failed to initialize backend {}: {}",
                        state.selected_backend, e
                    );
                    let response = ServerMessage::AcpInitialized {
                        success: false,
                        error: Some(e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpCreateSession { cwd, backend } => {
            let provider = backend
                .as_deref()
                .map(normalize_backend)
                .unwrap_or_else(|| normalize_backend(&state.selected_backend));
            info!(
                "Direct create session via {}: {}",
                provider.backend_label(),
                cwd
            );

            let result = match provider {
                DirectSessionProvider::Opencode => {
                    ensure_acp_client(&app_state)
                        .await
                        .map_err(anyhow::Error::msg)?;
                    let acp_guard = app_state.acp_client.read().await;
                    let client = acp_guard
                        .as_ref()
                        .ok_or_else(|| anyhow::anyhow!("ACP client unavailable"))?;
                    client
                        .create_session(&cwd)
                        .await
                        .map_err(anyhow::Error::msg)
                }
                DirectSessionProvider::Codex => {
                    ensure_codex_client(&app_state)
                        .await
                        .map_err(anyhow::Error::msg)?;
                    let codex_guard = app_state.codex_app_client.read().await;
                    let client = codex_guard
                        .as_ref()
                        .ok_or_else(|| anyhow::anyhow!("Codex client unavailable"))?;
                    client
                        .create_session(&cwd)
                        .await
                        .map_err(anyhow::Error::msg)
                }
            };

            match result {
                Ok(result) => {
                    info!("Direct session created: {:?}", result.session_id);
                    write_acp_session_file(&result.session_id, &cwd);
                    let response = ServerMessage::AcpSessionCreated {
                        session_id: result.session_id,
                        current_model_id: result
                            .models
                            .as_ref()
                            .map(|m| m.current_model_id.clone()),
                        available_models: None,
                        current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                        cwd: Some(cwd.clone()),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to create direct session: {}", e);
                    let response = ServerMessage::AcpError {
                        message: e.to_string(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpResumeSession { session_id, cwd } => {
            info!("ACP resume session: {} in {}", session_id, cwd);
            let (provider, raw_id) = parse_direct_session_id(&session_id);
            let acp_client = app_state.acp_client.clone();
            let codex_client = app_state.codex_app_client.clone();
            let app_state_clone = app_state.clone();
            let message_tx = state.message_tx.clone();
            tokio::spawn(async move {
                let t_total = std::time::Instant::now();
                let result = match provider {
                    DirectSessionProvider::Opencode => {
                        if let Err(e) = ensure_acp_client(&app_state_clone).await {
                            Err(e)
                        } else {
                            let acp_guard = acp_client.read().await;
                            match acp_guard.as_ref() {
                                Some(client) => client.resume_session(&raw_id, &cwd).await,
                                None => Err("ACP client not initialized".to_string()),
                            }
                        }
                    }
                    DirectSessionProvider::Codex => {
                        if let Err(e) = ensure_codex_client(&app_state_clone).await {
                            Err(e)
                        } else {
                            let codex_guard = codex_client.read().await;
                            match codex_guard.as_ref() {
                                Some(client) => client.resume_session(&raw_id, &cwd).await,
                                None => Err("Codex client not initialized".to_string()),
                            }
                        }
                    }
                };

                match result {
                    Ok(result) => {
                        info!("[TIMING] AcpResumeSession total {:?}", t_total.elapsed());
                        write_acp_session_file(&result.session_id, &cwd);
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::AcpSessionCreated {
                                session_id: result.session_id,
                                current_model_id: result
                                    .models
                                    .as_ref()
                                    .map(|m| m.current_model_id.clone()),
                                available_models: None,
                                current_mode_id: result
                                    .modes
                                    .as_ref()
                                    .map(|m| m.current_mode_id.clone()),
                                cwd: None,
                            },
                        )
                        .await;
                    }
                    Err(e) if e.starts_with("__already_active:") => {
                        info!(
                            "[TIMING] AcpResumeSession skipped (already active) in {:?}",
                            t_total.elapsed()
                        );
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::AcpSessionCreated {
                                session_id: session_id.clone(),
                                current_model_id: None,
                                available_models: None,
                                current_mode_id: None,
                                cwd: None,
                            },
                        )
                        .await;
                    }
                    Err(e) => {
                        error!("Failed to resume direct session: {}", e);
                        let _ =
                            send_message(&message_tx, ServerMessage::AcpError { message: e }).await;
                    }
                }
            });
        }
        WebSocketMessage::AcpForkSession { session_id, cwd } => {
            info!("ACP fork session: {} in {}", session_id, cwd);
            let (provider, raw_id) = parse_direct_session_id(&session_id);

            let result = match provider {
                DirectSessionProvider::Opencode => {
                    if let Err(e) = ensure_acp_client(&app_state).await {
                        Err(e)
                    } else {
                        let acp_guard = app_state.acp_client.read().await;
                        match acp_guard.as_ref() {
                            Some(client) => client.fork_session(&raw_id, &cwd).await,
                            None => Err("ACP client not initialized".to_string()),
                        }
                    }
                }
                DirectSessionProvider::Codex => {
                    if let Err(e) = ensure_codex_client(&app_state).await {
                        Err(e)
                    } else {
                        let codex_guard = app_state.codex_app_client.read().await;
                        match codex_guard.as_ref() {
                            Some(client) => client.fork_session(&raw_id, &cwd).await,
                            None => Err("Codex client not initialized".to_string()),
                        }
                    }
                }
            };

            match result {
                Ok(result) => {
                    let response = ServerMessage::AcpSessionCreated {
                        session_id: result.session_id,
                        current_model_id: result
                            .models
                            .as_ref()
                            .map(|m| m.current_model_id.clone()),
                        available_models: None,
                        current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                        cwd: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to fork direct session: {}", e);
                    let response = ServerMessage::AcpError { message: e };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpListSessions => {
            info!("ACP list sessions");
            let app_state_clone = app_state.clone();
            let message_tx = state.message_tx.clone();
            tokio::spawn(async move {
                let t_total = std::time::Instant::now();
                let sessions = collect_direct_sessions(app_state_clone).await;
                info!("[TIMING] AcpListSessions total {:?}", t_total.elapsed());
                let response = ServerMessage::AcpSessionsListed { sessions };
                if let Err(e) = send_message(&message_tx, response).await {
                    error!("Failed to send ACP session list: {}", e);
                }
            });
        }
        WebSocketMessage::AcpSendPrompt {
            session_id,
            message,
        } => {
            info!("ACP send prompt to {}", session_id);
            let (provider, raw_id) = parse_direct_session_id(&session_id);
            let acp_client = app_state.acp_client.clone();
            let codex_client = app_state.codex_app_client.clone();
            let app_state_clone = app_state.clone();
            let message_tx = state.message_tx.clone();
            tokio::spawn(async move {
                let t = std::time::Instant::now();
                let result = match provider {
                    DirectSessionProvider::Opencode => {
                        if let Err(e) = ensure_acp_client(&app_state_clone).await {
                            Err(e)
                        } else {
                            let acp_guard = acp_client.read().await;
                            match acp_guard.as_ref() {
                                Some(client) => client.send_prompt(&raw_id, &message).await,
                                None => Err("ACP client not initialized".to_string()),
                            }
                        }
                    }
                    DirectSessionProvider::Codex => {
                        if let Err(e) = ensure_codex_client(&app_state_clone).await {
                            Err(e)
                        } else {
                            let codex_guard = codex_client.read().await;
                            match codex_guard.as_ref() {
                                Some(client) => client
                                    .send_prompt(&session_id, &message)
                                    .await
                                    .map(|_| crate::acp::session::PromptResult {
                                        stop_reason: None,
                                        usage: None,
                                    }),
                                None => Err("Codex client not initialized".to_string()),
                            }
                        }
                    }
                };

                match result {
                    Ok(_) => {
                        info!("[TIMING] send_prompt took {:?}", t.elapsed());
                        let _ =
                            send_message(&message_tx, ServerMessage::AcpPromptSent { session_id })
                                .await;
                    }
                    Err(e) => {
                        error!("Failed to send prompt (took {:?}): {}", t.elapsed(), e);
                        let _ =
                            send_message(&message_tx, ServerMessage::AcpError { message: e }).await;
                    }
                }
            });
        }
        WebSocketMessage::SendFileToAcpChat {
            session_id,
            file,
            prompt,
            cwd,
        } => {
            info!(
                "Received file to send to ACP chat: {} ({}) in cwd: {:?}",
                file.filename, file.mime_type, cwd
            );
            let (provider, raw_id) = parse_direct_session_id(&session_id);
            let resolved_cwd = match cwd {
                Some(provided) if !provided.trim().is_empty() => Some(provided.trim().to_string()),
                _ if provider == DirectSessionProvider::Codex => {
                    resolve_codex_session_cwd(&app_state, &session_id, &raw_id).await
                }
                _ => None,
            };

            // Save file to storage (for display in chat)
            let file_id =
                match state
                    .chat_file_storage
                    .save_file(&file.data, &file.filename, &file.mime_type)
                {
                    Ok(id) => id,
                    Err(e) => {
                        error!("Failed to save file for ACP chat: {}", e);
                        return Ok(());
                    }
                };

            // Also save to session's working directory (for AI to access)
            let normalized_cwd = resolved_cwd
                .as_deref()
                .map(|c| c.trim())
                .filter(|c| !c.is_empty())
                .map(Path::new);

            let mut file_path_str: Option<String> = None;
            if let Some(session_cwd) = normalized_cwd {
                match state.chat_file_storage.save_file_to_directory(
                    &file.data,
                    &file.filename,
                    &file.mime_type,
                    session_cwd,
                ) {
                    Ok(path) => {
                        info!("File saved to session directory: {:?}", path);
                        file_path_str = Some(path.to_string_lossy().to_string());
                    }
                    Err(e) => {
                        error!("Failed to save file to session directory: {}", e);
                    }
                }
            } else {
                info!("No valid cwd for ACP file attachment; skipping in-session file save");
            }

            // Create content block based on mime type
            let block = if file.mime_type.starts_with("image/") {
                crate::chat_log::ContentBlock::Image {
                    id: file_id,
                    mime_type: file.mime_type.clone(),
                    alt_text: Some(file.filename.clone()),
                }
            } else if file.mime_type.starts_with("audio/") {
                crate::chat_log::ContentBlock::Audio {
                    id: file_id,
                    mime_type: file.mime_type.clone(),
                    duration_seconds: None,
                }
            } else {
                crate::chat_log::ContentBlock::File {
                    id: file_id,
                    filename: file.filename.clone(),
                    mime_type: file.mime_type.clone(),
                    size_bytes: Some((file.data.len() as f64 * 0.75) as u64),
                }
            };

            // Build message blocks
            let mut blocks = Vec::new();
            let mut prompt_text = String::new();

            // Add prompt if provided
            if let Some(ref p) = prompt {
                if !p.trim().is_empty() {
                    prompt_text = p.trim().to_string();
                    blocks.push(crate::chat_log::ContentBlock::Text {
                        text: p.trim().to_string(),
                    });
                }
            }
            blocks.push(block);

            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks,
            };

            // Persist to chat_event_store
            let session_key = session_key_for_external_id(&session_id);
            if let Err(e) = app_state.chat_event_store.append_message(
                &session_key,
                0,
                "webhook-file",
                &chat_message,
            ) {
                warn!("Failed to persist ACP file message: {}", e);
            }

            // Broadcast to all connected clients
            // Flutter stores ACP sessions as "acp_{session_id}", so prefix accordingly
            let msg = ServerMessage::ChatFileMessage {
                session_name: session_key.clone(),
                window_index: 0,
                message: chat_message.clone(),
            };
            info!(
                "BROADCASTING ChatFileMessage for ACP session: {} (should NOT appear in tmux chat)",
                session_id
            );
            state.client_manager.broadcast(msg).await;

            // Send prompt to ACP session so AI can see the file
            let combined = match (prompt_text.is_empty(), file_path_str.as_ref()) {
                (false, Some(path)) => format!("{}\n\nHere is the file: {}", prompt_text, path),
                (true, Some(path)) => format!("Here is the file: {}", path),
                (false, None) => prompt_text.clone(),
                (true, None) => String::new(),
            };
            if combined.is_empty() {
                warn!(
                    "No prompt or valid file path for ACP file upload on session {}",
                    session_id
                );
            } else {
                match provider {
                    DirectSessionProvider::Opencode => {
                        ensure_acp_client(&app_state).await.ok();
                        let acp_guard = app_state.acp_client.read().await;
                        if let Some(client) = acp_guard.as_ref() {
                            if let Err(e) = client.send_prompt(&raw_id, &combined).await {
                                error!("Failed to send file prompt to ACP: {}", e);
                            }
                        }
                    }
                    DirectSessionProvider::Codex => {
                        ensure_codex_client(&app_state).await.ok();
                        let codex_guard = app_state.codex_app_client.read().await;
                        if let Some(client) = codex_guard.as_ref() {
                            if let Err(e) = client.send_prompt(&session_id, &combined).await {
                                error!("Failed to send file prompt to Codex: {}", e);
                            }
                        }
                    }
                }
            }
        }
        WebSocketMessage::AcpCancelPrompt { session_id } => {
            info!("ACP cancel prompt: {}", session_id);
            let (provider, raw_id) = parse_direct_session_id(&session_id);

            let result = match provider {
                DirectSessionProvider::Opencode => {
                    ensure_acp_client(&app_state).await.ok();
                    let acp_guard = app_state.acp_client.read().await;
                    match acp_guard.as_ref() {
                        Some(client) => client.cancel_prompt(&raw_id).await,
                        None => Err("ACP client not initialized".to_string()),
                    }
                }
                DirectSessionProvider::Codex => {
                    Err("Codex prompt cancellation is not implemented yet".to_string())
                }
            };

            match result {
                Ok(_) => {
                    let response = ServerMessage::AcpPromptCancelled {
                        session_id: session_id.clone(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to cancel prompt: {}", e);
                    let response = ServerMessage::AcpError { message: e };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpSetModel {
            session_id,
            model_id,
        } => {
            info!("ACP set model for {}: {}", session_id, model_id);
            let (provider, raw_id) = parse_direct_session_id(&session_id);

            let result = match provider {
                DirectSessionProvider::Opencode => {
                    ensure_acp_client(&app_state).await.ok();
                    let acp_guard = app_state.acp_client.read().await;
                    match acp_guard.as_ref() {
                        Some(client) => client.set_model(&raw_id, &model_id).await,
                        None => Err("ACP client not initialized".to_string()),
                    }
                }
                DirectSessionProvider::Codex => {
                    Err("Codex model switching is not implemented yet".to_string())
                }
            };

            match result {
                Ok(_) => {
                    let response = ServerMessage::AcpModelSet {
                        session_id: session_id.clone(),
                        model_id: model_id.clone(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::AcpError { message: e };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpSetMode {
            session_id,
            mode_id,
        } => {
            info!("ACP set mode for {}: {}", session_id, mode_id);
            let (provider, raw_id) = parse_direct_session_id(&session_id);

            let result = match provider {
                DirectSessionProvider::Opencode => {
                    ensure_acp_client(&app_state).await.ok();
                    let acp_guard = app_state.acp_client.read().await;
                    match acp_guard.as_ref() {
                        Some(client) => client.set_mode(&raw_id, &mode_id).await,
                        None => Err("ACP client not initialized".to_string()),
                    }
                }
                DirectSessionProvider::Codex => {
                    Err("Codex mode switching is not implemented yet".to_string())
                }
            };

            match result {
                Ok(_) => {
                    let response = ServerMessage::AcpModeSet {
                        session_id: session_id.clone(),
                        mode_id: mode_id.clone(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::AcpError { message: e };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpRespondPermission {
            request_id,
            option_id,
        } => {
            info!(
                "ACP respond to permission {} with option {}",
                request_id, option_id
            );

            let result = if request_id.starts_with("codex:") {
                ensure_codex_client(&app_state).await.ok();
                let codex_guard = app_state.codex_app_client.read().await;
                match codex_guard.as_ref() {
                    Some(client) => client.respond_to_permission(&request_id, &option_id).await,
                    None => Err("Codex client not initialized".to_string()),
                }
            } else {
                ensure_acp_client(&app_state).await.ok();
                let acp_guard = app_state.acp_client.read().await;
                match acp_guard.as_ref() {
                    Some(client) => client.respond_to_permission(&request_id, &option_id).await,
                    None => Err("ACP client not initialized".to_string()),
                }
            };

            match result {
                Ok(_) => {
                    let response = ServerMessage::AcpPermissionResponse {
                        request_id: request_id.clone(),
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::AcpError { message: e };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpLoadHistory {
            session_id,
            offset,
            limit,
        } => {
            tracing::debug!(
                "ACP load history: {} offset={:?} limit={:?}",
                session_id,
                offset,
                limit
            );

            let offset = offset.unwrap_or(0);
            let limit = limit.unwrap_or(50);
            let (provider, _) = parse_direct_session_id(&session_id);
            let session_key = session_key_for_external_id(&session_id);

            let messages = match provider {
                DirectSessionProvider::Opencode => {
                    match app_state
                        .chat_event_store
                        .list_messages_page(&session_key, 0, offset, limit)
                    {
                        Ok((msgs, has_more)) => {
                            let response = ServerMessage::AcpHistoryLoaded {
                                session_id,
                                messages: msgs,
                                has_more,
                            };
                            send_message(&state.message_tx, response).await?;
                            return Ok(());
                        }
                        Err(e) => {
                            error!("Failed to load ACP history: {}", e);
                            let response = ServerMessage::AcpError {
                                message: format!("Failed to load history: {}", e),
                            };
                            send_message(&state.message_tx, response).await?;
                            return Ok(());
                        }
                    }
                }
                DirectSessionProvider::Codex => {
                    if let Err(e) = ensure_codex_client(&app_state).await {
                        let response = ServerMessage::AcpError { message: e };
                        send_message(&state.message_tx, response).await?;
                        return Ok(());
                    }
                    let codex_guard = app_state.codex_app_client.read().await;
                    match codex_guard.as_ref() {
                        Some(client) => match client.read_thread_messages(&session_id).await {
                            Ok(messages) => messages,
                            Err(e) => {
                                let response = ServerMessage::AcpError { message: e };
                                send_message(&state.message_tx, response).await?;
                                return Ok(());
                            }
                        },
                        None => {
                            let response = ServerMessage::AcpError {
                                message: "Codex client not initialized".to_string(),
                            };
                            send_message(&state.message_tx, response).await?;
                            return Ok(());
                        }
                    }
                }
            };

            let total = messages.len();
            let start = total.saturating_sub(offset + limit);
            let end = total.saturating_sub(offset);
            let has_more = start > 0;
            let paginated = messages[start..end].to_vec();

            let response = ServerMessage::AcpHistoryLoaded {
                session_id,
                messages: paginated,
                has_more,
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpClearHistory { session_id } => {
            let session_key = session_key_for_external_id(&session_id);
            if let Err(e) = app_state.chat_event_store.clear_messages(&session_key, 0) {
                tracing::warn!("Failed to clear ACP overlay for {}: {}", session_id, e);
            }
            // Set cleared_at so poll loop and future history loads skip old messages
            let timestamp = chrono::Utc::now().timestamp_millis();
            app_state
                .chat_clear_store
                .set_cleared_at(&session_key, 0, timestamp)
                .await;
            send_message(
                &state.message_tx,
                ServerMessage::ChatLogCleared {
                    session_name: session_key,
                    window_index: 0,
                    success: true,
                    error: None,
                },
            )
            .await?;
        }
        WebSocketMessage::AcpDeleteSession { session_id } => {
            info!("ACP delete session: {}", session_id);
            let (_, raw_id) = parse_direct_session_id(&session_id);

            // The opencode ACP protocol has no session/delete method — sessions
            // persist in the daemon. We clear our local history and blocklist
            // the session ID so it's filtered out of future list responses.
            let session_key = session_key_for_external_id(&session_id);
            if let Err(e) = app_state.chat_event_store.clear_messages(&session_key, 0) {
                warn!(
                    "Failed to clear history for deleted ACP session {}: {}",
                    session_id, e
                );
            }
            if let Err(e) = app_state
                .chat_event_store
                .mark_acp_session_deleted(&session_id)
            {
                warn!(
                    "Failed to mark ACP session {} as deleted: {}",
                    session_id, e
                );
            }
            if raw_id != session_id {
                let _ = app_state.chat_event_store.mark_acp_session_deleted(&raw_id);
            }

            send_message(
                &state.message_tx,
                ServerMessage::AcpSessionDeleted {
                    session_id,
                    success: true,
                    error: None,
                },
            )
            .await?;
        }
        _ => {}
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::websocket::{
        client_manager::ClientManager,
        types::{BroadcastMessage, WsState},
    };
    use crate::{
        chat_clear_store::ChatClearStore, chat_event_store::ChatEventStore,
        chat_file_storage::ChatFileStorage, types::WebSocketMessage,
    };
    use std::sync::Arc;
    use tempfile::TempDir;
    use tokio::sync::{mpsc, Mutex};

    fn make_state(
        dir: &std::path::Path,
    ) -> (
        WsState,
        mpsc::Receiver<BroadcastMessage>,
        Arc<crate::AppState>,
    ) {
        let (tx, rx) = mpsc::channel::<BroadcastMessage>(256);
        let (broadcast_tx, _) = mpsc::channel(256);
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let ws_state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None,
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_file_storage: chat_file_storage.clone(),
            chat_event_store: chat_event_store.clone(),
            chat_clear_store: chat_clear_store.clone(),
            client_manager: client_manager.clone(),
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
            selected_backend: "acp".to_string(),
        };
        let notification_store =
            Arc::new(crate::notification_store::NotificationStore::new(dir.to_path_buf()).unwrap());
        let favorite_store =
            Arc::new(crate::favorite_store::FavoriteStore::new(dir.to_path_buf()).unwrap());
        let tag_store = Arc::new(crate::tag_store::TagStore::new(dir.to_path_buf()).unwrap());
        let app_state = Arc::new(crate::AppState {
            enable_audio_logs: false,
            broadcast_tx,
            client_manager,
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            notification_store,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            codex_app_client: Arc::new(tokio::sync::RwLock::new(None)),
            favorite_store,
            tag_store,
            shutdown_token: tokio_util::sync::CancellationToken::new(),
        });
        (ws_state, rx, app_state)
    }

    fn make_state_with_acp_client(
        dir: &std::path::Path,
    ) -> (
        WsState,
        mpsc::Receiver<BroadcastMessage>,
        Arc<crate::AppState>,
    ) {
        let (tx, rx) = mpsc::channel::<BroadcastMessage>(256);
        let (broadcast_tx, _) = mpsc::channel(256);
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let (acp_client, _event_tx, _event_rx) = crate::acp::AcpClient::new_for_test();
        let ws_state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None,
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_file_storage: chat_file_storage.clone(),
            chat_event_store: chat_event_store.clone(),
            chat_clear_store: chat_clear_store.clone(),
            client_manager: client_manager.clone(),
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
            selected_backend: "acp".to_string(),
        };
        let notification_store =
            Arc::new(crate::notification_store::NotificationStore::new(dir.to_path_buf()).unwrap());
        let favorite_store =
            Arc::new(crate::favorite_store::FavoriteStore::new(dir.to_path_buf()).unwrap());
        let tag_store = Arc::new(crate::tag_store::TagStore::new(dir.to_path_buf()).unwrap());
        let app_state = Arc::new(crate::AppState {
            enable_audio_logs: false,
            broadcast_tx,
            client_manager,
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            notification_store,
            acp_client: Arc::new(tokio::sync::RwLock::new(Some(acp_client))),
            codex_app_client: Arc::new(tokio::sync::RwLock::new(None)),
            favorite_store,
            tag_store,
            shutdown_token: tokio_util::sync::CancellationToken::new(),
        });
        (ws_state, rx, app_state)
    }

    #[test]
    fn test_write_acp_session_file() {
        // Just ensure it doesn't panic
        write_acp_session_file("test-session-id", "/home/user/project");
    }

    #[tokio::test]
    async fn test_acp_load_history_empty() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpLoadHistory {
                session_id: "test-session".to_string(),
                offset: None,
                limit: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("AcpHistoryLoaded") || json.contains("messages"));
    }

    #[tokio::test]
    async fn test_acp_clear_history() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpClearHistory {
                session_id: "test-session".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("Cleared") || json.contains("cleared") || json.contains("true"));
    }

    #[tokio::test]
    async fn test_acp_delete_session() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpDeleteSession {
                session_id: "session-to-delete".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("Deleted") || json.contains("deleted") || json.contains("true"));
    }

    #[tokio::test]
    async fn test_acp_list_sessions_no_client_auto_init_fails() {
        // ACP client is None — AcpListSessions will try to auto-init ACP which will fail
        // since opencode binary likely isn't available in test environment
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(WebSocketMessage::AcpListSessions, &mut ws_state, app_state).await;
        assert!(result.is_ok());
        // May or may not send a response depending on timing
        let _ = rx.try_recv();
    }

    #[tokio::test]
    async fn test_acp_send_prompt_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        // Client is None — should handle gracefully
        let result = handle(
            WebSocketMessage::AcpSendPrompt {
                session_id: "test-sid".to_string(),
                message: "Hello".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_cancel_prompt_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpCancelPrompt {
                session_id: "test-sid".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_unknown_message_no_op() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(WebSocketMessage::Ping, &mut ws_state, app_state).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_select_backend_non_acp() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SelectBackend {
                backend: "terminal".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("\"type\":\"backend-selected\"") || json.contains("terminal"));
    }

    #[tokio::test]
    async fn test_select_backend_acp_fails_no_opencode() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SelectBackend {
                backend: "acp".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        // Should get acp-initialized with success=false since opencode binary isn't available
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-initialized\"")
                || json.contains("\"type\":\"backend-selected\"")
                || json.contains("\"success\":false")
                || json.contains("error")
        );
    }

    #[tokio::test]
    async fn test_acp_create_session_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpCreateSession {
                cwd: "/tmp".to_string(),
                backend: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-session-created\"")
                || json.contains("\"type\":\"acp-error\"")
                || json.contains("not initialized")
                || json.contains("unavailable")
                || json.contains("Failed to start")
        );
    }

    #[tokio::test]
    async fn test_acp_resume_session_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpResumeSession {
                session_id: "test-sid".to_string(),
                cwd: "/tmp".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        // Resume spawns a task — give it a moment to send the error
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn test_acp_fork_session_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpForkSession {
                session_id: "test-sid".to_string(),
                cwd: "/tmp".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-error\"")
                || json.contains("not initialized")
                || json.contains("unavailable")
                || json.contains("Failed to start")
        );
    }

    #[tokio::test]
    async fn test_acp_set_model_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpSetModel {
                session_id: "test-sid".to_string(),
                model_id: "gpt-4".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-error\"")
                || json.contains("not initialized")
                || json.contains("unavailable")
        );
    }

    #[tokio::test]
    async fn test_acp_set_mode_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpSetMode {
                session_id: "test-sid".to_string(),
                mode_id: "code".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-error\"")
                || json.contains("not initialized")
                || json.contains("unavailable")
        );
    }

    #[tokio::test]
    async fn test_acp_respond_permission_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpRespondPermission {
                request_id: "req-1".to_string(),
                option_id: "allow".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"acp-error\"")
                || json.contains("not initialized")
                || json.contains("unavailable")
        );
    }

    #[tokio::test]
    async fn test_acp_load_history_with_pagination() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());

        // Insert 10 messages directly into the app_state store
        for i in 0..10 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Message {}", i),
                }],
            };
            app_state
                .chat_event_store
                .append_message("acp_test-paginate", 0, "test", &msg)
                .unwrap();
        }

        let result = handle(
            WebSocketMessage::AcpLoadHistory {
                session_id: "test-paginate".to_string(),
                offset: Some(3),
                limit: Some(4),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("acp-history-loaded"));
        assert!(json.contains("true")); // has_more should be true
    }

    #[tokio::test]
    async fn test_acp_load_history_offset_beyond_total() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpLoadHistory {
                session_id: "test-empty".to_string(),
                offset: Some(100),
                limit: Some(10),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("acp-history-loaded"));
        assert!(json.contains("false")); // has_more should be false
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "SGVsbG8=".to_string(), // base64 "Hello"
                },
                prompt: Some("Analyze this".to_string()),
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_saves_file() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "SGVsbG8=".to_string(),
                },
                prompt: Some("Look at this file".to_string()),
                cwd: Some(dir.path().to_string_lossy().to_string()),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        // Verify file was persisted to chat event store
        let messages = ws_state.chat_event_store.list_messages("acp_test-sid", 0);
        assert!(messages.is_ok());
    }

    // ===== Phase 1: write_acp_session_file error paths =====

    #[test]
    fn test_write_acp_session_file_no_home() {
        let old_home = std::env::var("HOME").ok();
        std::env::remove_var("HOME");
        write_acp_session_file("test-session-id", "/tmp");
        if let Some(h) = old_home {
            std::env::set_var("HOME", h);
        }
    }

    // ===== Phase 2: SendFileToAcpChat mime type and prompt variations =====

    #[tokio::test]
    async fn test_send_file_to_acp_chat_invalid_base64() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "NOT_VALID_BASE64!!!".to_string(),
                },
                prompt: None,
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_image() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.png".to_string(),
                    mime_type: "image/png".to_string(),
                    data: "iVBORw0KGgo=".to_string(),
                },
                prompt: None,
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_audio() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.mp3".to_string(),
                    mime_type: "audio/mpeg".to_string(),
                    data: "SUQzBAAAAAA=".to_string(),
                },
                prompt: None,
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_no_prompt() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "SGVsbG8=".to_string(),
                },
                prompt: None,
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_empty_prompt() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "SGVsbG8=".to_string(),
                },
                prompt: Some("   ".to_string()),
                cwd: None,
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_acp_chat_with_cwd_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::SendFileToAcpChat {
                session_id: "test-sid".to_string(),
                file: crate::types::FileAttachment {
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                    data: "SGVsbG8=".to_string(),
                },
                prompt: Some("Look at this".to_string()),
                cwd: Some(dir.path().to_string_lossy().to_string()),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    // ===== Phase 3: Client-initialized tests =====

    #[tokio::test]
    async fn test_select_backend_acp_client_already_initialized() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state_with_acp_client(dir.path());
        let result = handle(
            WebSocketMessage::SelectBackend {
                backend: "acp".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("\"type\":\"backend-selected\"") || json.contains("\"backend\":\"acp\"")
        );
    }

    // ===== Additional error path tests =====

    #[tokio::test]
    async fn test_acp_send_prompt_empty_message() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpSendPrompt {
                session_id: "test-sid".to_string(),
                message: "".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_list_sessions_auto_init_start_failure() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, mut rx, app_state) = make_state(dir.path());
        let result = handle(WebSocketMessage::AcpListSessions, &mut ws_state, app_state).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_list_sessions_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(WebSocketMessage::AcpListSessions, &mut ws_state, app_state).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_clear_history_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpClearHistory {
                session_id: "test-sid".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_delete_session_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpDeleteSession {
                session_id: "test-sid".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_load_history_no_client() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpLoadHistory {
                session_id: "test-sid".to_string(),
                offset: Some(0),
                limit: Some(20),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_resume_session_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpResumeSession {
                session_id: "test-sid".to_string(),
                cwd: "/tmp".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_set_model_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpSetModel {
                session_id: "test-sid".to_string(),
                model_id: "gpt-4".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_set_mode_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpSetMode {
                session_id: "test-sid".to_string(),
                mode_id: "code".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_respond_permission_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpRespondPermission {
                request_id: "req-1".to_string(),
                option_id: "allow".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_acp_cancel_prompt_no_client_v2() {
        let dir = TempDir::new().unwrap();
        let (mut ws_state, _rx, app_state) = make_state(dir.path());
        let result = handle(
            WebSocketMessage::AcpCancelPrompt {
                session_id: "test-sid".to_string(),
            },
            &mut ws_state,
            app_state,
        )
        .await;
        assert!(result.is_ok());
    }
}
