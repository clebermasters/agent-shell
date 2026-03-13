use std::path::Path;
use std::sync::Arc;
use tracing::{error, info, warn};

use super::types::{send_message, WsState};
use crate::{types::*, AppState};

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

    let ws_url = std::env::var("AGENTSHELL_WS_URL")
        .unwrap_or_else(|_| "ws://localhost:5173/ws".to_string());

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

pub(crate) async fn handle(
    msg: WebSocketMessage,
    state: &mut WsState,
    app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match msg {
        // ACP message handlers
        WebSocketMessage::SelectBackend { backend } => {
            info!("Backend selection: {}", backend);

            if backend == "acp" {
                // Initialize ACP client globally in AppState
                let mut acp_guard = app_state.acp_client.write().await;
                if acp_guard.is_none() {
                    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<crate::acp::AcpEvent>(100);
                    let mut client = crate::acp::AcpClient::new(event_tx);

                    match client.start().await {
                        Ok(init_result) => {
                            info!("ACP client initialized: {:?}", init_result.agent_info);
                            *acp_guard = Some(client);

                            // Spawn event handler
                            let broadcast_tx = app_state.broadcast_tx.clone();
                            let app_state_clone = app_state.clone();
                            tokio::spawn(async move {
                                while let Some(event) = event_rx.recv().await {
                                    match event {
                                        crate::acp::AcpEvent::SessionUpdate { session_id, update } => {
                                            let msg = match update {
                                                crate::acp::SessionUpdate::AgentMessageChunk { content } => {
                                                    let text = content.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();

                                                    // Merge into last assistant text row instead of inserting a new row per chunk
                                                    let session_key = format!("acp_{}", session_id);
                                                    match app_state_clone.chat_event_store.append_or_merge_text(
                                                        &session_key,
                                                        0,
                                                        "acp",
                                                        "assistant",
                                                        &text,
                                                    ) {
                                                        Ok(_) => {}
                                                        Err(e) => {
                                                            tracing::warn!("Failed to persist ACP message: {}", e);
                                                        }
                                                    }

                                                    ServerMessage::AcpMessageChunk {
                                                        session_id: session_id.clone(),
                                                        content: text,
                                                        is_thinking: false,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::AgentThoughtChunk { content } => {
                                                    ServerMessage::AcpMessageChunk {
                                                        session_id: session_id.clone(),
                                                        content: content.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                                                        is_thinking: true,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::ToolCall { tool_call_id, title, kind, status, raw_input, .. } => {
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "assistant".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::ToolCall {
                                                            name: title.clone(),
                                                            summary: kind.clone(),
                                                            input: raw_input.clone(),
                                                        }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(&session_key, 0, "acp", &chat_message) {
                                                        tracing::warn!("Failed to persist ACP tool call: {}", e);
                                                    }
                                                    let input = raw_input.as_ref().map(|v| serde_json::to_string_pretty(v).unwrap_or_default());
                                                    ServerMessage::AcpToolCall {
                                                        session_id: session_id.clone(),
                                                        tool_call_id,
                                                        title,
                                                        kind,
                                                        status,
                                                        input,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::ToolCallUpdate { tool_call_id, status, content, .. } => {
                                                    let output = content.unwrap_or_default().iter()
                                                        .filter_map(|c| c.as_object())
                                                        .filter_map(|c| c.get("content").and_then(|v| v.as_object()))
                                                        .filter_map(|v| v.get("text").and_then(|t| t.as_str()))
                                                        .collect::<Vec<_>>()
                                                        .join("\n");
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "tool".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::ToolResult {
                                                            tool_name: tool_call_id.clone(),
                                                            summary: status.clone(),
                                                            content: if output.is_empty() { None } else { Some(output.clone()) },
                                                        }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(&session_key, 0, "acp", &chat_message) {
                                                        tracing::warn!("Failed to persist ACP tool result: {}", e);
                                                    }
                                                    ServerMessage::AcpToolResult {
                                                        session_id: session_id.clone(),
                                                        tool_call_id,
                                                        status,
                                                        output,
                                                    }
                                                }
                                                _ => continue,
                                            };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP message: {}", e);
                                            }
                                        }
                                        crate::acp::AcpEvent::PermissionRequest { request_id, session_id, tool_call, .. } => {
                                            let tool = tool_call.get("title").and_then(|v| v.as_str()).unwrap_or("unknown");
                                            let command = tool_call.get("rawInput")
                                                .and_then(|v| v.get("command"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            let msg = ServerMessage::AcpPermissionRequest {
                                                request_id,
                                                session_id,
                                                tool: tool.to_string(),
                                                command: command.to_string(),
                                            };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP permission request: {}", e);
                                            }
                                        }
                                        crate::acp::AcpEvent::Error { message } => {
                                            tracing::error!("ACP error: {}", message);
                                            let msg = ServerMessage::AcpError { message };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP error: {}", e);
                                            }
                                        }
                                    }
                                }
                            });

                            let response = ServerMessage::AcpInitialized { success: true, error: None };
                            send_message(&state.message_tx, response).await?;
                        }
                        Err(e) => {
                            error!("Failed to initialize ACP client: {}", e);
                            let response = ServerMessage::AcpInitialized { success: false, error: Some(e) };
                            send_message(&state.message_tx, response).await?;
                        }
                    }
                } else {
                    let response = ServerMessage::BackendSelected { backend: backend.clone() };
                    send_message(&state.message_tx, response).await?;
                }
            } else {
                let response = ServerMessage::BackendSelected { backend: backend.clone() };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpCreateSession { cwd } => {
            info!("ACP create session: {}", cwd);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.create_session(&cwd).await {
                    Ok(result) => {
                        info!("ACP session created: {:?}", result.session_id);
                        write_acp_session_file(&result.session_id, &cwd);
                        let response = ServerMessage::AcpSessionCreated {
                            session_id: result.session_id,
                            current_model_id: result.models.as_ref().map(|m| m.current_model_id.clone()),
                            available_models: None,
                            current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                            cwd: Some(cwd.clone()),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        error!("Failed to create ACP session: {}", e);
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized. Select ACP backend first.".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpResumeSession { session_id, cwd } => {
            info!("ACP resume session: {} in {}", session_id, cwd);
            let acp_client = app_state.acp_client.clone();
            let message_tx = state.message_tx.clone();
            tokio::spawn(async move {
                let t_total = std::time::Instant::now();
                let acp_guard = acp_client.read().await;
                if let Some(client) = acp_guard.as_ref() {
                    match client.resume_session(&session_id, &cwd).await {
                        Ok(result) => {
                            info!("[TIMING] AcpResumeSession total {:?}", t_total.elapsed());
                            write_acp_session_file(&session_id, &cwd);
                            let _ = send_message(&message_tx, ServerMessage::AcpSessionCreated {
                                session_id: result.session_id,
                                current_model_id: result.models.as_ref().map(|m| m.current_model_id.clone()),
                                available_models: None,
                                current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                                cwd: None,
                            }).await;
                        }
                        Err(e) if e.starts_with("__already_active:") => {
                            // Session already active — skip resume, still notify Flutter
                            info!("[TIMING] AcpResumeSession skipped (already active) in {:?}", t_total.elapsed());
                            let _ = send_message(&message_tx, ServerMessage::AcpSessionCreated {
                                session_id: session_id.clone(),
                                current_model_id: None,
                                available_models: None,
                                current_mode_id: None,
                                cwd: None,
                            }).await;
                        }
                        Err(e) => {
                            error!("Failed to resume ACP session: {}", e);
                            let _ = send_message(&message_tx, ServerMessage::AcpError { message: e }).await;
                        }
                    }
                } else {
                    let _ = send_message(&message_tx, ServerMessage::AcpError {
                        message: "ACP client not initialized".to_string(),
                    }).await;
                }
            });
        }
        WebSocketMessage::AcpForkSession { session_id, cwd } => {
            info!("ACP fork session: {} in {}", session_id, cwd);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.fork_session(&session_id, &cwd).await {
                    Ok(result) => {
                        let response = ServerMessage::AcpSessionCreated {
                            session_id: result.session_id,
                            current_model_id: result.models.as_ref().map(|m| m.current_model_id.clone()),
                            available_models: None,
                            current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                            cwd: None,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        error!("Failed to fork ACP session: {}", e);
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpListSessions => {
            info!("ACP list sessions");
            let t_total = std::time::Instant::now();

            // Auto-initialize ACP client if not initialized
            let t_lock = std::time::Instant::now();
            let list_result = {
                let mut acp_guard = app_state.acp_client.write().await;
                info!("[TIMING] AcpListSessions write lock in {:?}", t_lock.elapsed());
                if acp_guard.is_none() {
                    info!("ACP client not initialized, auto-initializing...");
                    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<crate::acp::AcpEvent>(100);
                    let mut client = crate::acp::AcpClient::new(event_tx);
                    match client.start().await {
                        Ok(init_result) => {
                            info!("ACP client auto-initialized: {:?}", init_result.agent_info);

                            // Spawn event handler
                            let broadcast_tx = app_state.broadcast_tx.clone();
                            let app_state_clone = app_state.clone();
                            tokio::spawn(async move {
                                while let Some(event) = event_rx.recv().await {
                                    match event {
                                        crate::acp::AcpEvent::SessionUpdate { session_id, update } => {
                                            let msg = match update {
                                                crate::acp::SessionUpdate::AgentMessageChunk { content } => {
                                                    let text = content.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();

                                                    // Merge into last assistant text row instead of inserting a new row per chunk
                                                    let session_key = format!("acp_{}", session_id);
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
                                                        session_id: session_id.clone(),
                                                        content: text,
                                                        is_thinking: false,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::AgentThoughtChunk { content } => {
                                                    ServerMessage::AcpMessageChunk {
                                                        session_id: session_id.clone(),
                                                        content: content.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                                                        is_thinking: true,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::ToolCall { tool_call_id, title, kind, status, raw_input, .. } => {
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "assistant".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::ToolCall {
                                                            name: title.clone(),
                                                            summary: kind.clone(),
                                                            input: raw_input.clone(),
                                                        }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(&session_key, 0, "acp", &chat_message) {
                                                        tracing::warn!("Failed to persist ACP tool call: {}", e);
                                                    }
                                                    let input = raw_input.as_ref().map(|v| serde_json::to_string_pretty(v).unwrap_or_default());
                                                    ServerMessage::AcpToolCall {
                                                        session_id: session_id.clone(),
                                                        tool_call_id,
                                                        title,
                                                        kind,
                                                        status,
                                                        input,
                                                    }
                                                }
                                                crate::acp::SessionUpdate::ToolCallUpdate { tool_call_id, status, content, .. } => {
                                                    let output = content.unwrap_or_default().iter()
                                                        .filter_map(|c| c.as_object())
                                                        .filter_map(|c| c.get("content").and_then(|v| v.as_object()))
                                                        .filter_map(|v| v.get("text").and_then(|t| t.as_str()))
                                                        .collect::<Vec<_>>()
                                                        .join("\n");
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "tool".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::ToolResult {
                                                            tool_name: tool_call_id.clone(),
                                                            summary: status.clone(),
                                                            content: if output.is_empty() { None } else { Some(output.clone()) },
                                                        }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(&session_key, 0, "acp", &chat_message) {
                                                        tracing::warn!("Failed to persist ACP tool result: {}", e);
                                                    }
                                                    ServerMessage::AcpToolResult {
                                                        session_id: session_id.clone(),
                                                        tool_call_id,
                                                        status,
                                                        output,
                                                    }
                                                }
                                                _ => continue,
                                            };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP message: {}", e);
                                            }
                                        }
                                        crate::acp::AcpEvent::PermissionRequest { request_id, session_id, tool_call, .. } => {
                                            let tool = tool_call.get("title").and_then(|v| v.as_str()).unwrap_or("unknown");
                                            let command = tool_call.get("rawInput")
                                                .and_then(|v| v.get("command"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            let msg = ServerMessage::AcpPermissionRequest {
                                                request_id,
                                                session_id,
                                                tool: tool.to_string(),
                                                command: command.to_string(),
                                            };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP permission request: {}", e);
                                            }
                                        }
                                        crate::acp::AcpEvent::Error { message } => {
                                            tracing::error!("ACP error: {}", message);
                                            let msg = ServerMessage::AcpError { message };
                                            if let Err(e) = broadcast_tx.send(msg) {
                                                tracing::error!("Failed to broadcast ACP error: {}", e);
                                            }
                                        }
                                    }
                                }
                            });

                            *acp_guard = Some(client);
                            acp_guard.as_mut().unwrap().list_sessions().await
                        }
                        Err(e) => {
                            error!("Failed to auto-initialize ACP client: {}", e);
                            let response = ServerMessage::AcpError {
                                message: format!("Failed to initialize ACP: {}", e),
                            };
                            send_message(&state.message_tx, response).await?;
                            return Ok(());
                        }
                    }
                } else {
                    acp_guard.as_mut().unwrap().list_sessions().await
                }
            };

            match list_result {
                Ok(result) => {
                    info!("[TIMING] AcpListSessions total {:?}", t_total.elapsed());
                    // Use DB directly to list all sessions across all projects
                    let db_path = crate::chat_log::watcher::find_opencode_db();
                    let sessions_result = match db_path {
                        Ok(p) => tokio::task::spawn_blocking(move || crate::chat_log::opencode_parser::list_all_sessions(&p))
                            .await.unwrap_or_else(|e| Err(anyhow::anyhow!("join error: {}", e))),
                        Err(e) => Err(anyhow::anyhow!("opencode.db not found: {}", e)),
                    };

                    match sessions_result {
                        Ok(all_sessions) => {
                            let deleted_ids = app_state.chat_event_store
                                .get_deleted_acp_session_ids()
                                .unwrap_or_default();
                            let sessions = all_sessions.into_iter()
                                .filter(|s| !deleted_ids.contains(&s.session_id))
                                .collect();
                            let response = ServerMessage::AcpSessionsListed { sessions };
                            send_message(&state.message_tx, response).await?;
                        }
                        Err(e) => {
                            // Fall back to ACP result
                            warn!("DB session list failed, using ACP fallback: {}", e);
                            let deleted_ids = app_state.chat_event_store
                                .get_deleted_acp_session_ids()
                                .unwrap_or_default();
                            let sessions = result.sessions.into_iter()
                                .filter(|s| !deleted_ids.contains(&s.session_id))
                                .collect();
                            let response = ServerMessage::AcpSessionsListed { sessions };
                            send_message(&state.message_tx, response).await?;
                        }
                    }
                }
                Err(e) => {
                    error!("Failed to list ACP sessions: {}", e);
                    let response = ServerMessage::AcpError {
                        message: e,
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }
        WebSocketMessage::AcpSendPrompt { session_id, message } => {
            info!("ACP send prompt to {}", session_id);
            // Spawn so the main loop is never blocked waiting for OpenCode's response
            let acp_client = app_state.acp_client.clone();
            let message_tx = state.message_tx.clone();
            tokio::spawn(async move {
                let acp_guard = acp_client.read().await;
                if let Some(client) = acp_guard.as_ref() {
                    let t = std::time::Instant::now();
                    match client.send_prompt(&session_id, &message).await {
                        Ok(_) => {
                            info!("[TIMING] send_prompt took {:?}", t.elapsed());
                            let _ = send_message(&message_tx, ServerMessage::AcpPromptSent { session_id }).await;
                        }
                        Err(e) => {
                            error!("Failed to send prompt (took {:?}): {}", t.elapsed(), e);
                            let _ = send_message(&message_tx, ServerMessage::AcpError { message: e }).await;
                        }
                    }
                } else {
                    let _ = send_message(&message_tx, ServerMessage::AcpError {
                        message: "ACP client not initialized".to_string(),
                    }).await;
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

            // Save file to storage (for display in chat)
            let file_id = match state
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
            let mut file_path_str: Option<String> = None;
            if let Some(ref session_cwd) = cwd {
                match state
                    .chat_file_storage
                    .save_file_to_directory(&file.data, &file.filename, &file.mime_type, Path::new(session_cwd))
                {
                    Ok(path) => {
                        info!("File saved to session directory: {:?}", path);
                        file_path_str = Some(path.to_string_lossy().to_string());
                    }
                    Err(e) => {
                        error!("Failed to save file to session directory: {}", e);
                    }
                }
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
            let session_key = format!("acp_{}", session_id);
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
                session_name: format!("acp_{}", session_id),
                window_index: 0,
                message: chat_message.clone(),
            };
            info!("BROADCASTING ChatFileMessage for ACP session: {} (should NOT appear in tmux chat)", session_id);
            state.client_manager.broadcast(msg).await;

            // Send prompt to ACP session so AI can see the file
            let file_ref = file_path_str.clone().unwrap_or_else(|| format!("[File: {}]", file.filename));
            let combined = if prompt_text.is_empty() {
                file_ref
            } else {
                format!("{}\n\nHere is the file: {}", prompt_text, file_ref)
            };
            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                if let Err(e) = client.send_prompt(&session_id, &combined).await {
                    error!("Failed to send file prompt to ACP: {}", e);
                }
            }
        }
        WebSocketMessage::AcpCancelPrompt { session_id } => {
            info!("ACP cancel prompt: {}", session_id);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.cancel_prompt(&session_id).await {
                    Ok(_) => {
                        let response = ServerMessage::AcpPromptCancelled {
                            session_id: session_id.clone(),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        error!("Failed to cancel prompt: {}", e);
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpSetModel { session_id, model_id } => {
            info!("ACP set model for {}: {}", session_id, model_id);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.set_model(&session_id, &model_id).await {
                    Ok(_) => {
                        let response = ServerMessage::AcpModelSet {
                            session_id: session_id.clone(),
                            model_id: model_id.clone(),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpSetMode { session_id, mode_id } => {
            info!("ACP set mode for {}: {}", session_id, mode_id);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.set_mode(&session_id, &mode_id).await {
                    Ok(_) => {
                        let response = ServerMessage::AcpModeSet {
                            session_id: session_id.clone(),
                            mode_id: mode_id.clone(),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpRespondPermission { request_id, option_id } => {
            info!("ACP respond to permission {} with option {}", request_id, option_id);

            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.respond_to_permission(&request_id, &option_id).await {
                    Ok(_) => {
                        let response = ServerMessage::AcpPermissionResponse {
                            request_id: request_id.clone(),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        let response = ServerMessage::AcpError {
                            message: e,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            } else {
                let response = ServerMessage::AcpError {
                    message: "ACP client not initialized".to_string(),
                };
                send_message(&state.message_tx, response).await?;
            }
        }
        WebSocketMessage::AcpLoadHistory { session_id, offset, limit } => {
            tracing::debug!("ACP load history: {} offset={:?} limit={:?}", session_id, offset, limit);

            // History loading doesn't require ACP client to be initialized - just read from database
            let offset = offset.unwrap_or(0);
            let limit = limit.unwrap_or(50);

            let session_key = format!("acp_{}", session_id);

            let messages = match app_state.chat_event_store.list_messages(&session_key, 0) {
                Ok(msgs) => msgs,
                Err(e) => {
                    error!("Failed to load ACP history: {}", e);
                    let response = ServerMessage::AcpError {
                        message: format!("Failed to load history: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                    return Ok(());
                }
            };

            let total = messages.len();
            let has_more = offset + limit < total;
            let paginated: Vec<_> = messages.into_iter().skip(offset).take(limit).collect();

            let response = ServerMessage::AcpHistoryLoaded {
                session_id,
                messages: paginated,
                has_more,
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpClearHistory { session_id } => {
            let session_key = format!("acp_{}", session_id);
            if let Err(e) = app_state.chat_event_store.clear_messages(&session_key, 0) {
                tracing::warn!("Failed to clear ACP overlay for {}: {}", session_id, e);
            }
            // Set cleared_at so poll loop and future history loads skip old messages
            let timestamp = chrono::Utc::now().timestamp_millis();
            app_state.chat_clear_store.set_cleared_at(&session_key, 0, timestamp).await;
            send_message(
                &state.message_tx,
                ServerMessage::ChatLogCleared { session_name: session_key, window_index: 0, success: true, error: None },
            ).await?;
        }
        WebSocketMessage::AcpDeleteSession { session_id } => {
            info!("ACP delete session: {}", session_id);

            // The opencode ACP protocol has no session/delete method — sessions
            // persist in the daemon. We clear our local history and blocklist
            // the session ID so it's filtered out of future list responses.
            let session_key = format!("acp_{}", session_id);
            if let Err(e) = app_state.chat_event_store.clear_messages(&session_key, 0) {
                warn!("Failed to clear history for deleted ACP session {}: {}", session_id, e);
            }
            if let Err(e) = app_state.chat_event_store.mark_acp_session_deleted(&session_id) {
                warn!("Failed to mark ACP session {} as deleted: {}", session_id, e);
            }

            send_message(
                &state.message_tx,
                ServerMessage::AcpSessionDeleted { session_id, success: true, error: None },
            )
            .await?;
        }
        _ => {}
    }

    Ok(())
}
