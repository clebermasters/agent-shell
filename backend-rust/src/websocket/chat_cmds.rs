use std::sync::Arc;
use tracing::{error, info, warn};

use super::types::{send_message, merge_history_messages, BroadcastMessage, WsState};
use crate::{types::*, AppState};

pub(crate) async fn handle(
    msg: WebSocketMessage,
    state: &mut WsState,
    _app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::WatchChatLog {
            session_name,
            window_index,
        } => {
            info!(
                "Starting chat log watch for {}:{}",
                session_name, window_index
            );
            let message_tx = state.message_tx.clone();
            let chat_event_store = state.chat_event_store.clone();

            // Set current session and window for input handling
            *state.current_session.lock().await = Some(session_name.clone());
            *state.current_window.lock().await = Some(window_index);

            // Cancel any existing watcher
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    tracing::info!("Stopping previous chat log watcher");
                    handle.abort();
                }
            }

            let chat_log_handle = state.chat_log_handle.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let handle = tokio::spawn(async move {
                tracing::info!(
                    "Detecting chat log for session '{}' window {}",
                    session_name,
                    window_index
                );

                // Get the clear timestamp if one exists
                let cleared_at = chat_clear_store
                    .get_cleared_at(&session_name, window_index)
                    .await;

                match crate::chat_log::watcher::detect_log_file(&session_name, window_index).await {
                    Ok((path, tool)) => {
                        let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel();

                        // Spawn the file watcher -- the returned
                        // RecommendedWatcher must be kept alive for as long
                        // as we want notifications.
                        let _watcher =
                            match crate::chat_log::watcher::watch_log_file(&path, tool, event_tx, cleared_at)
                                .await
                            {
                                Ok(w) => w,
                                Err(e) => {
                                    error!("Failed to start chat log watcher: {}", e);
                                    let _ = send_message(
                                        &message_tx,
                                        ServerMessage::ChatLogError {
                                            error: e.to_string(),
                                        },
                                    )
                                    .await;
                                    return;
                                }
                            };

                        // Forward events to WebSocket
                        let session_name_owned = session_name.clone();
                        while let Some(event) = event_rx.recv().await {
                            let msg = match event {
                                crate::chat_log::ChatLogEvent::History { messages, tool } => {
                                    let persisted_messages = match chat_event_store
                                        .list_messages(&session_name_owned, window_index)
                                    {
                                        Ok(stored) => stored,
                                        Err(e) => {
                                            warn!(
                                                "Failed to load persisted chat events for {}:{}: {}",
                                                session_name_owned, window_index, e
                                            );
                                            Vec::new()
                                        }
                                    };

                                    let merged_messages =
                                        merge_history_messages(messages, persisted_messages);
                                    tracing::info!(
                                        "Sending merged chat history: {} messages for session {}",
                                        merged_messages.len(),
                                        session_name_owned
                                    );
                                    ServerMessage::ChatHistory {
                                        session_name: session_name_owned.clone(),
                                        window_index,
                                        messages: merged_messages,
                                        tool: Some(tool),
                                    }
                                }
                                crate::chat_log::ChatLogEvent::NewMessage { message } => {
                                    tracing::info!(
                                        "Sending new chat message: role={} for session {}",
                                        message.role,
                                        session_name_owned
                                    );
                                    ServerMessage::ChatEvent {
                                        session_name: session_name_owned.clone(),
                                        window_index,
                                        message,
                                        source: None,
                                    }
                                }
                                crate::chat_log::ChatLogEvent::Error { error } => {
                                    tracing::warn!("Chat log error: {}", error);
                                    ServerMessage::ChatLogError { error }
                                }
                            };
                            if send_message(&message_tx, msg).await.is_err() {
                                break;
                            }
                        }
                    }
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: e.to_string(),
                            },
                        )
                        .await;
                    }
                }
            });

            {
                let mut handle_guard = chat_log_handle.lock().await;
                *handle_guard = Some(handle);
            }
        }
        WebSocketMessage::WatchAcpChatLog { session_id, window_index } => {
            let window_index = window_index.unwrap_or(0);
            let session_key = format!("acp_{}", session_id);

            tracing::debug!("Starting ACP chat log watch for session: {}", session_id);

            // Set current session for consistency
            *state.current_session.lock().await = Some(session_key.clone());
            *state.current_window.lock().await = Some(window_index);

            // Cancel any existing watcher
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    tracing::info!("Stopping previous chat log watcher");
                    handle.abort();
                }
            }

            let message_tx = state.message_tx.clone();
            let chat_event_store = state.chat_event_store.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let chat_log_handle = state.chat_log_handle.clone();

            let handle = tokio::spawn(async move {
                // 1. Find OpenCode DB
                let db_path = match crate::chat_log::watcher::find_opencode_db() {
                    Ok(p) => p,
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError { error: format!("OpenCode DB not found: {}", e) },
                        ).await;
                        return;
                    }
                };

                // 2. Get cleared_at
                let cleared_at = chat_clear_store.get_cleared_at(&session_key, window_index).await;

                // 3. Read full history from OpenCode DB
                let (opencode_messages, max_time_updated) = {
                    let db_path2 = db_path.clone();
                    let session_id2 = session_id.clone();
                    let t = std::time::Instant::now();
                    let result = tokio::task::spawn_blocking(move || {
                        crate::chat_log::opencode_parser::fetch_all_messages(&db_path2, &session_id2, cleared_at)
                    }).await.unwrap_or_else(|e| Err(anyhow::anyhow!("spawn_blocking failed: {}", e)))
                        .unwrap_or_else(|e| { tracing::warn!("Failed to read OpenCode history: {}", e); (vec![], 0) });
                    info!("[TIMING] fetch_all_messages({} msgs) took {:?}", result.0.len(), t.elapsed());
                    result
                };

                // 4. Read webhook/file overlay from AgentShell DB
                let t = std::time::Instant::now();
                let overlay = chat_event_store
                    .get_acp_overlay(&session_key, cleared_at)
                    .unwrap_or_else(|e| { tracing::warn!("Failed to read ACP overlay: {}", e); vec![] })
                    .into_iter()
                    .map(|e| (e.timestamp_millis, e.message))
                    .collect::<Vec<_>>();
                info!("[TIMING] get_acp_overlay({} msgs) took {:?}", overlay.len(), t.elapsed());

                // 5. Merge: interleave OpenCode messages and overlay by timestamp
                let mut combined: Vec<(i64, crate::chat_log::ChatMessage)> = opencode_messages
                    .into_iter()
                    .map(|m| {
                        let millis = m.timestamp
                            .map(|t| t.timestamp_millis())
                            .unwrap_or(0);
                        (millis, m)
                    })
                    .chain(overlay)
                    .collect();
                combined.sort_by_key(|(t, _)| *t);
                let messages: Vec<crate::chat_log::ChatMessage> = combined.into_iter().map(|(_, m)| m).collect();

                // 6. Send history
                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatHistory {
                        session_name: session_key.clone(),
                        window_index,
                        messages,
                        tool: Some(crate::chat_log::AiTool::Opencode {
                            cwd: std::path::PathBuf::from("/"),
                            pid: 0,
                        }),
                    },
                ).await;

                // 7. Poll for new messages from OpenCode DB (no persistence)
                let mut opencode_state = crate::chat_log::opencode_parser::OpencodeState {
                    session_id: session_id.clone(),
                    last_time_updated: max_time_updated,
                    cleared_at,
                    pid: 0,
                    seen_text_lengths: std::collections::HashMap::new(),
                    seen_tool_calls: std::collections::HashSet::new(),
                    seen_tool_results: std::collections::HashSet::new(),
                };

                let mut interval = tokio::time::interval(std::time::Duration::from_millis(1000));
                loop {
                    interval.tick().await;
                    let t = std::time::Instant::now();
                    match crate::chat_log::opencode_parser::fetch_new_messages(&db_path, &mut opencode_state) {
                        Ok(new_messages) => {
                            let elapsed = t.elapsed();
                            if elapsed.as_millis() > 100 || !new_messages.is_empty() {
                                info!("[TIMING] poll fetch_new_messages({} new) took {:?}", new_messages.len(), elapsed);
                            }
                            for msg in new_messages {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatEvent {
                                        session_name: session_key.clone(),
                                        window_index,
                                        message: msg,
                                        source: Some("acp".to_string()),
                                    },
                                ).await;
                            }
                        }
                        Err(e) => tracing::debug!("ACP poll error: {}", e),
                    }
                }
            });

            *chat_log_handle.lock().await = Some(handle);
        }
        WebSocketMessage::UnwatchChatLog => {
            info!("Stopping chat log watch");
            let mut handle_guard = state.chat_log_handle.lock().await;
            if let Some(handle) = handle_guard.take() {
                handle.abort();
            }
        }
        WebSocketMessage::ClearChatLog {
            session_name,
            window_index,
        } => {
            info!(
                "Clearing chat log for session {}:{}",
                session_name, window_index
            );

            // Stop current watcher if running
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    handle.abort();
                }
            }

            // Set the clear timestamp
            let timestamp = chrono::Utc::now().timestamp_millis();
            state
                .chat_clear_store
                .set_cleared_at(&session_name, window_index, timestamp)
                .await;

            // Also clear persisted messages
            if let Err(e) = state
                .chat_event_store
                .clear_messages(&session_name, window_index)
            {
                warn!(
                    "Failed to clear persisted messages for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            // Send confirmation to client
            let _ = send_message(
                &state.message_tx,
                ServerMessage::ChatLogCleared {
                    session_name: session_name.clone(),
                    window_index,
                    success: true,
                    error: None,
                },
            )
            .await;

            // Restart the watcher with the clear timestamp
            state
                .current_session
                .lock()
                .await
                .clone_from(&Some(session_name.clone()));
            state
                .current_window
                .lock()
                .await
                .clone_from(&Some(window_index));

            let message_tx = state.message_tx.clone();
            let chat_event_store = state.chat_event_store.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let session_name_owned = session_name.clone();
            let window_index_owned = window_index;

            let handle = tokio::spawn(async move {
                match crate::chat_log::watcher::detect_log_file(&session_name_owned, window_index_owned)
                    .await
                {
                    Ok((path, tool)) => {
                        let (event_tx, mut event_rx) =
                            tokio::sync::mpsc::unbounded_channel();

                        // Get the clear timestamp
                        let cleared_at = chat_clear_store
                            .get_cleared_at(&session_name_owned, window_index_owned)
                            .await;

                        let _watcher = match crate::chat_log::watcher::watch_log_file(
                            &path,
                            tool.clone(),
                            event_tx,
                            cleared_at,
                        )
                        .await
                        {
                            Ok(w) => w,
                            Err(e) => {
                                error!("Failed to start chat log watcher: {}", e);
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: e.to_string(),
                                    },
                                )
                                .await;
                                return;
                            }
                        };

                        // Forward events to WebSocket
                        while let Some(event) = event_rx.recv().await {
                            let msg = match event {
                                crate::chat_log::ChatLogEvent::History { messages, tool } => {
                                    let persisted_messages = match chat_event_store
                                        .list_messages(&session_name_owned, window_index_owned)
                                    {
                                        Ok(stored) => stored,
                                        Err(e) => {
                                            warn!(
                                                "Failed to load persisted chat events for {}:{}: {}",
                                                session_name_owned, window_index_owned, e
                                            );
                                            Vec::new()
                                        }
                                    };

                                    let merged_messages =
                                        merge_history_messages(
                                            messages,
                                            persisted_messages,
                                        );

                                    ServerMessage::ChatHistory {
                                        session_name: session_name_owned.clone(),
                                        window_index: window_index_owned,
                                        messages: merged_messages,
                                        tool: Some(tool),
                                    }
                                }
                                crate::chat_log::ChatLogEvent::NewMessage { message } => {
                                    ServerMessage::ChatEvent {
                                        session_name: session_name_owned.clone(),
                                        window_index: window_index_owned,
                                        message,
                                        source: None,
                                    }
                                }
                                crate::chat_log::ChatLogEvent::Error { error } => {
                                    tracing::warn!("Chat log error: {}", error);
                                    ServerMessage::ChatLogError { error }
                                }
                            };
                            if send_message(&message_tx, msg).await.is_err() {
                                break;
                            }
                        }
                    }
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: e.to_string(),
                            },
                        )
                        .await;
                    }
                }
            });

            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                *handle_guard = Some(handle);
            }
        }
        WebSocketMessage::SendFileToChat {
            session_name,
            window_index,
            file,
            prompt,
        } => {
            info!(
                "Received file to send to chat: {} ({})",
                file.filename, file.mime_type
            );

            // Get tmux session working directory
            let session_path = crate::tmux::get_session_path(&session_name);

            // Save file to session directory if we have a valid path
            let file_path_str = if let Some(ref session_path) = session_path {
                match state
                    .chat_file_storage
                    .save_file_to_directory(
                        &file.data,
                        &file.filename,
                        &file.mime_type,
                        session_path,
                    ) {
                    Ok(path) => {
                        info!("File saved to session directory: {:?}", path);
                        Some(path.to_string_lossy().to_string())
                    }
                    Err(e) => {
                        error!("Failed to save file to session directory: {}", e);
                        None
                    }
                }
            } else {
                error!("Could not get session path for {}", session_name);
                None
            };

            // Save file to chat_files for display purposes (UUID-based)
            let file_id = match state
                .chat_file_storage
                .save_file(&file.data, &file.filename, &file.mime_type)
            {
                Ok(id) => id,
                Err(e) => {
                    error!("Failed to save file for display: {}", e);
                    return Ok(());
                }
            };

            // Build combined text (prompt + file path)
            let combined_text = match (&prompt, &file_path_str) {
                (Some(text), Some(path)) if !text.trim().is_empty() => {
                    format!("{}\n\nHere is the file: {}", text.trim(), path)
                }
                (Some(_), Some(path)) => format!("Here is the file: {}", path),
                (Some(text), None) if !text.trim().is_empty() => text.trim().to_string(),
                (None, Some(path)) => format!("Here is the file: {}", path),
                _ => String::new(),
            };

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

            // Build message blocks: text (if prompt provided) + file
            let mut blocks = Vec::new();
            let combined_text_for_tmux = combined_text.clone();
            if !combined_text.is_empty() {
                blocks.push(crate::chat_log::ContentBlock::Text {
                    text: combined_text,
                });
            }
            blocks.push(block);

            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks,
            };

            if let Err(e) = state.chat_event_store.append_message(
                &session_name,
                window_index,
                "webhook-file",
                &chat_message,
            ) {
                warn!(
                    "Failed to persist chat file message for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            // Broadcast to all connected clients watching this session
            let msg = ServerMessage::ChatFileMessage {
                session_name: session_name.clone(),
                window_index,
                message: chat_message,
            };

            // Use client_manager.broadcast to send to ALL connected clients
            state.client_manager.broadcast(msg).await;

            // Send the combined text (prompt + file path) to the tmux session
            // so the AI tool (OpenCode/Claude) actually receives the file reference
            if !combined_text_for_tmux.is_empty() {
                let target = format!("{}:{}", session_name, window_index);
                let text = combined_text_for_tmux.trim_end_matches('\n');

                // Two separate tmux calls: text first, then Enter.
                let _ = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "-l", text])
                    .output()
                    .await;
                let result = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "Enter"])
                    .output()
                    .await;

                match result {
                    Ok(output) if output.status.success() => {
                        info!("Sent file prompt to tmux: {:?}", text);
                    }
                    Ok(output) => {
                        error!("tmux send-keys failed for {}: {}", target, output.status);
                    }
                    Err(e) => {
                        error!("Failed to send file prompt to tmux session {}: {}", target, e);
                    }
                }
            }
        }
        WebSocketMessage::SendChatMessage {
            session_name,
            window_index,
            message,
            notify,
        } => {
            info!(
                "Received chat message to send: {} (notify: {:?})",
                message, notify
            );

            if message.trim().is_empty() {
                warn!("Empty message received, skipping");
                return Ok(());
            }

            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: message.trim().to_string(),
                }],
            };

            if let Err(e) = state.chat_event_store.append_message(
                &session_name,
                window_index,
                "webhook",
                &chat_message,
            ) {
                warn!(
                    "Failed to persist chat message for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            let msg = ServerMessage::ChatEvent {
                session_name: session_name.clone(),
                window_index,
                message: chat_message,
                source: Some("webhook".to_string()),
            };

            state.client_manager.broadcast(msg).await;

            let should_notify = notify.unwrap_or(true);
            if should_notify {
                let notify_msg = ServerMessage::ChatNotification {
                    session_name: session_name.clone(),
                    window_index,
                    preview: if message.len() > 50 {
                        format!("{}...", &message[..50])
                    } else {
                        message.clone()
                    },
                };
                state.client_manager.broadcast(notify_msg).await;
            }

            let target = format!("{}:{}", session_name, window_index);
            let text = message.trim();

            // Two separate tmux calls: text first, then Enter.
            let _ = tokio::process::Command::new("tmux")
                .args(&["send-keys", "-t", &target, "-l", text])
                .output()
                .await;
            let result = tokio::process::Command::new("tmux")
                .args(&["send-keys", "-t", &target, "Enter"])
                .output()
                .await;

            match result {
                Ok(output) if output.status.success() => {
                    info!("Sent chat message to tmux: {:?}", text);
                }
                Ok(output) => {
                    error!("tmux send-keys failed for {}: {}", target, output.status);
                }
                Err(e) => {
                    error!("Failed to send chat message to tmux session {}: {}", target, e);
                }
            }
        }
        _ => {}
    }
    Ok(())
}
