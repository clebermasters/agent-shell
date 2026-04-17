use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

use super::types::{merge_history_messages, send_message, WsState};
use crate::{types::*, AppState};

const BACKEND_CODEX: &str = "codex";

fn parse_direct_session_id(session_id: &str) -> (String, String) {
    match session_id.split_once(':') {
        Some((provider, raw_id)) if !provider.is_empty() && !raw_id.is_empty() => {
            (provider.to_string(), raw_id.to_string())
        }
        _ => ("opencode".to_string(), session_id.to_string()),
    }
}

fn session_key_for_external_id(external_id: &str) -> String {
    format!("acp_{external_id}")
}

fn resolve_chat_upload_target_dir(session_name: &str) -> Option<PathBuf> {
    #[cfg(test)]
    {
        let _ = session_name;
        None
    }

    #[cfg(not(test))]
    {
        crate::tmux::get_session_path(session_name)
    }
}

async fn load_codex_history(
    app_state: Arc<AppState>,
    external_session_id: String,
    session_key: String,
    cleared_at: Option<i64>,
) -> Result<Vec<crate::chat_log::ChatMessage>, String> {
    super::acp_cmds::ensure_codex_client(&app_state)
        .await
        .map_err(|e| e.to_string())?;

    let history = {
        let codex_guard = app_state.codex_app_client.read().await;
        match codex_guard.as_ref() {
            Some(client) => client.read_thread_messages(&external_session_id).await?,
            None => return Err("Codex client not initialized".to_string()),
        }
    };

    let filtered_history = if let Some(ts) = cleared_at {
        history
            .into_iter()
            .filter(|msg| {
                msg.timestamp
                    .map(|timestamp| timestamp.timestamp_millis() > ts)
                    .unwrap_or(false)
            })
            .collect::<Vec<_>>()
    } else {
        history
    };

    let overlay = app_state
        .chat_event_store
        .get_acp_overlay(&session_key, cleared_at)
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|event| (event.timestamp_millis, event.message))
        .collect::<Vec<_>>();

    let mut combined = filtered_history
        .into_iter()
        .map(|message| {
            let timestamp_millis = message
                .timestamp
                .map(|timestamp| timestamp.timestamp_millis())
                .unwrap_or(0);
            (timestamp_millis, message)
        })
        .chain(overlay)
        .collect::<Vec<_>>();
    combined.sort_by_key(|(timestamp, _)| *timestamp);
    Ok(combined.into_iter().map(|(_, message)| message).collect())
}

pub(crate) async fn handle(
    msg: WebSocketMessage,
    state: &mut WsState,
    app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::WatchChatLog {
            session_name,
            window_index,
            limit,
        } => {
            info!(
                "Starting chat log watch for client={} session={}:{}",
                state.client_id, session_name, window_index
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
                    tracing::info!(
                        "Stopping previous chat log watcher for client={} before starting session={}:{}",
                        state.client_id, session_name, window_index
                    );
                    handle.abort();
                }
            }

            // Clear any previous kiro sender — the PTY reader dynamically checks
            // the shared Arc, so clearing ensures no stale forwarding.
            *state.kiro_chat_output_tx.lock().unwrap() = None;
            // Clone the shared Arc so the spawned task can set the sender if kiro is detected.
            let kiro_shared_tx = state.kiro_chat_output_tx.clone();

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
                        // ── Kiro PTY capture ─────────────────────────────────────
                        // For kiro, the PTY reader forwards raw output to kiro_output_tx.
                        // We read from kiro_output_rx and parse with kiro_parser.
                        if matches!(&tool, crate::chat_log::AiTool::Kiro { .. }) {
                            // Create kiro channel now that kiro is confirmed.
                            // Store sender in the shared Arc so the PTY reader picks it up dynamically.
                            let (kiro_tx, kiro_rx) = mpsc::unbounded_channel();
                            *kiro_shared_tx.lock().unwrap() = Some(kiro_tx);

                            let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel();

                            // Build kiro parser state from the tool variant
                            let mut kiro_state = if let crate::chat_log::AiTool::Kiro {
                                cwd,
                                pid,
                                session_id,
                            } = &tool
                            {
                                crate::chat_log::kiro_parser::KiroState::new(
                                    *pid,
                                    session_id.clone(),
                                    cwd.clone(),
                                )
                            } else {
                                // Unreachable due to matches! check above, but compiler needs it
                                return;
                            };

                            // Load persisted kiro messages from previous sessions
                            let persisted_messages =
                                match chat_event_store.list_messages(&session_name, window_index) {
                                    Ok(stored) => stored,
                                    Err(e) => {
                                        tracing::warn!(
                                            "Failed to load persisted kiro events for {}:{}: {}",
                                            session_name,
                                            window_index,
                                            e
                                        );
                                        Vec::new()
                                    }
                                };
                            let total_count = persisted_messages.len();
                            tracing::info!("Kiro: loaded {} persisted messages", total_count);
                            let _ = event_tx.send(crate::chat_log::ChatLogEvent::History {
                                messages: persisted_messages,
                                tool: tool.clone(),
                                has_more: false,
                                total_count,
                                context_window_usage: None,
                                model_name: None,
                            });

                            // Forward parsed events to WebSocket + persist to store
                            let session_name_owned = session_name.clone();
                            let chat_event_store_kiro = chat_event_store.clone();
                            tokio::spawn(async move {
                                while let Some(event) = event_rx.recv().await {
                                    let msg = match event {
                                        crate::chat_log::ChatLogEvent::NewMessage { message } => {
                                            tracing::info!(
                                                "Kiro: sending new chat message: role={}",
                                                message.role
                                            );
                                            // Persist to chat_event_store so history survives reconnect
                                            if let Err(e) = chat_event_store_kiro.append_message(
                                                &session_name_owned,
                                                window_index,
                                                "kiro-pty",
                                                &message,
                                            ) {
                                                tracing::warn!(
                                                    "Kiro: failed to persist message: {}",
                                                    e
                                                );
                                            }
                                            ServerMessage::ChatEvent {
                                                session_name: session_name_owned.clone(),
                                                window_index,
                                                message,
                                                source: None,
                                            }
                                        }
                                        crate::chat_log::ChatLogEvent::History {
                                            messages,
                                            tool,
                                            has_more,
                                            total_count,
                                            context_window_usage,
                                            model_name,
                                        } => ServerMessage::ChatHistory {
                                            session_name: session_name_owned.clone(),
                                            window_index,
                                            messages,
                                            tool: Some(tool),
                                            has_more,
                                            total_count,
                                            context_window_usage,
                                            model_name,
                                        },
                                        crate::chat_log::ChatLogEvent::ContextWindowUpdate {
                                            usage,
                                            model_name,
                                        } => ServerMessage::ContextWindowUpdate {
                                            session_name: session_name_owned.clone(),
                                            window_index,
                                            context_window_usage: usage,
                                            model_name,
                                        },
                                        crate::chat_log::ChatLogEvent::Error { error } => {
                                            tracing::warn!("Kiro chat log error: {}", error);
                                            ServerMessage::ChatLogError { error }
                                        }
                                    };
                                    if send_message(&message_tx, msg).await.is_err() {
                                        break;
                                    }
                                }
                            });

                            // Spawn the PTY → kiro parser forwarder
                            // Read from kiro_output_rx, parse chunks, emit NewMessage events
                            let event_tx_for_parser = event_tx;
                            tokio::spawn(async move {
                                let mut kiro_output_rx = kiro_rx;
                                const POLL_INTERVAL_MS: u64 = 100;

                                tracing::info!("Kiro PTY forwarder started");
                                loop {
                                    tokio::time::sleep(tokio::time::Duration::from_millis(
                                        POLL_INTERVAL_MS,
                                    ))
                                    .await;

                                    // Receive all buffered chunks
                                    while let Ok(chunk) = kiro_output_rx.try_recv() {
                                        let preview = chunk.chars().take(150).collect::<String>();
                                        tracing::info!(
                                            "Kiro PTY chunk received ({} chars): {:?}",
                                            chunk.len(),
                                            preview
                                        );
                                        let messages =
                                            crate::chat_log::kiro_parser::parse_pty_chunk(
                                                &chunk,
                                                &mut kiro_state,
                                            );
                                        tracing::info!(
                                            "Kiro parse_pty_chunk returned {} messages",
                                            messages.len()
                                        );
                                        for msg in messages {
                                            tracing::info!(
                                                "Kiro: emitting message role={}",
                                                msg.role
                                            );
                                            let event = crate::chat_log::ChatLogEvent::NewMessage {
                                                message: msg,
                                            };
                                            if event_tx_for_parser.send(event).is_err() {
                                                tracing::warn!(
                                                    "Kiro PTY forwarder: event_tx closed, stopping"
                                                );
                                                return;
                                            }
                                        }
                                    }

                                    // Check idle timeout for response completeness
                                    if crate::chat_log::kiro_parser::is_response_complete(
                                        &kiro_state,
                                    ) {
                                        // Flush any remaining buffered text as a response
                                        let remaining =
                                            std::mem::take(&mut kiro_state.response_buffer);
                                        tracing::info!(
                                            "Kiro idle timeout hit, flushing buffer ({} chars)",
                                            remaining.len()
                                        );
                                        if !remaining.trim().is_empty() {
                                            let responses =
                                                crate::chat_log::kiro_parser::emit_response(
                                                    &remaining,
                                                    &mut kiro_state,
                                                );
                                            tracing::info!(
                                                "Kiro emit_response returned {} messages",
                                                responses.len()
                                            );
                                            for response in responses {
                                                let event =
                                                    crate::chat_log::ChatLogEvent::NewMessage {
                                                        message: response,
                                                    };
                                                if event_tx_for_parser.send(event).is_err() {
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                }
                            });

                            // The task runs forever until the client disconnects
                            tracing::info!(
                                "Kiro PTY capture started for session '{}'",
                                session_name
                            );
                            return;
                        }

                        // ── Standard file-based watcher ─────────────────────────
                        let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel();

                        // Spawn the file watcher -- the returned
                        // RecommendedWatcher must be kept alive for as long
                        // as we want notifications.
                        let _watcher = match crate::chat_log::watcher::watch_log_file(
                            &path, tool, event_tx, cleared_at, limit,
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
                        let session_name_owned = session_name.clone();
                        while let Some(event) = event_rx.recv().await {
                            let msg = match event {
                                crate::chat_log::ChatLogEvent::History {
                                    messages,
                                    tool,
                                    has_more,
                                    total_count,
                                    context_window_usage,
                                    model_name,
                                } => {
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
                                        has_more,
                                        total_count,
                                        context_window_usage,
                                        model_name,
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
                                crate::chat_log::ChatLogEvent::ContextWindowUpdate {
                                    usage,
                                    model_name,
                                } => ServerMessage::ContextWindowUpdate {
                                    session_name: session_name_owned.clone(),
                                    window_index,
                                    context_window_usage: usage,
                                    model_name,
                                },
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
        WebSocketMessage::WatchAcpChatLog {
            session_id,
            window_index,
            limit,
        } => {
            let window_index = window_index.unwrap_or(0);
            let session_key = session_key_for_external_id(&session_id);
            let (provider, raw_session_id) = parse_direct_session_id(&session_id);

            tracing::debug!(
                "Starting ACP chat log watch for client={} session={} session_key={} window_index={} provider={}",
                state.client_id,
                session_id,
                session_key,
                window_index,
                provider
            );

            // Set current session for consistency
            *state.current_session.lock().await = Some(session_key.clone());
            *state.current_window.lock().await = Some(window_index);

            // Cancel any existing watcher
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    tracing::info!(
                        "Stopping previous chat log watcher for client={} before starting ACP session={} window_index={}",
                        state.client_id, session_id, window_index
                    );
                    handle.abort();
                }
            }

            let message_tx = state.message_tx.clone();
            let chat_event_store = state.chat_event_store.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let chat_log_handle = state.chat_log_handle.clone();
            let app_state_clone = app_state.clone();

            let handle = tokio::spawn(async move {
                let cleared_at = chat_clear_store
                    .get_cleared_at(&session_key, window_index)
                    .await;

                if provider == BACKEND_CODEX {
                    match load_codex_history(
                        app_state_clone.clone(),
                        session_id.clone(),
                        session_key.clone(),
                        cleared_at,
                    )
                    .await
                    {
                        Ok(all_messages) => {
                            let limit_n = limit.unwrap_or(30);
                            let total_count = all_messages.len();
                            let has_more = total_count > limit_n;
                            let messages = if has_more {
                                all_messages[total_count - limit_n..].to_vec()
                            } else {
                                all_messages
                            };

                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatHistory {
                                    session_name: session_key.clone(),
                                    window_index,
                                    messages,
                                    tool: Some(crate::chat_log::AiTool::Codex),
                                    has_more,
                                    total_count,
                                    context_window_usage: None,
                                    model_name: None,
                                },
                            )
                            .await;
                        }
                        Err(e) => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatLogError {
                                    error: format!("Failed to read Codex history: {}", e),
                                },
                            )
                            .await;
                        }
                    }
                    return;
                }

                let db_path = match crate::chat_log::watcher::find_opencode_db() {
                    Ok(p) => p,
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: format!("OpenCode DB not found: {}", e),
                            },
                        )
                        .await;
                        return;
                    }
                };

                let (opencode_messages, max_time_updated) = {
                    let db_path2 = db_path.clone();
                    let session_id2 = raw_session_id.clone();
                    let t = std::time::Instant::now();
                    let result = tokio::task::spawn_blocking(move || {
                        crate::chat_log::opencode_parser::fetch_all_messages(
                            &db_path2,
                            &session_id2,
                            cleared_at,
                        )
                    })
                    .await
                    .unwrap_or_else(|e| Err(anyhow::anyhow!("spawn_blocking failed: {}", e)))
                    .unwrap_or_else(|e| {
                        tracing::warn!("Failed to read OpenCode history: {}", e);
                        (vec![], 0)
                    });
                    info!(
                        "[TIMING] fetch_all_messages({} msgs) took {:?}",
                        result.0.len(),
                        t.elapsed()
                    );
                    result
                };

                let t = std::time::Instant::now();
                let overlay = chat_event_store
                    .get_acp_overlay(&session_key, cleared_at)
                    .unwrap_or_else(|e| {
                        tracing::warn!("Failed to read ACP overlay: {}", e);
                        vec![]
                    })
                    .into_iter()
                    .map(|e| (e.timestamp_millis, e.message))
                    .collect::<Vec<_>>();
                info!(
                    "[TIMING] get_acp_overlay({} msgs) took {:?}",
                    overlay.len(),
                    t.elapsed()
                );

                let mut combined: Vec<(i64, crate::chat_log::ChatMessage)> = opencode_messages
                    .into_iter()
                    .map(|m| {
                        let millis = m.timestamp.map(|t| t.timestamp_millis()).unwrap_or(0);
                        (millis, m)
                    })
                    .chain(overlay)
                    .collect();
                combined.sort_by_key(|(t, _)| *t);
                let all_messages: Vec<crate::chat_log::ChatMessage> =
                    combined.into_iter().map(|(_, m)| m).collect();

                let limit_n = limit.unwrap_or(30);
                let total_count = all_messages.len();
                let has_more = total_count > limit_n;
                let messages = if has_more {
                    all_messages[total_count - limit_n..].to_vec()
                } else {
                    all_messages
                };

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
                        has_more,
                        total_count,
                        context_window_usage: None,
                        model_name: None,
                    },
                )
                .await;

                // 7. Poll for new messages from OpenCode DB (no persistence)
                let mut opencode_state = crate::chat_log::opencode_parser::OpencodeState {
                    session_id: raw_session_id.clone(),
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
                    match crate::chat_log::opencode_parser::fetch_new_messages(
                        &db_path,
                        &mut opencode_state,
                    ) {
                        Ok(new_messages) => {
                            let elapsed = t.elapsed();
                            if elapsed.as_millis() > 100 || !new_messages.is_empty() {
                                info!(
                                    "[TIMING] poll fetch_new_messages({} new) took {:?}",
                                    new_messages.len(),
                                    elapsed
                                );
                            }
                            for msg in new_messages {
                                if send_message(
                                    &message_tx,
                                    ServerMessage::ChatEvent {
                                        session_name: session_key.clone(),
                                        window_index,
                                        message: msg,
                                        source: Some("acp".to_string()),
                                    },
                                )
                                .await
                                .is_err()
                                {
                                    tracing::debug!(
                                        "ACP poll: client disconnected, stopping watcher"
                                    );
                                    return;
                                }
                            }
                        }
                        Err(e) => tracing::debug!("ACP poll error: {}", e),
                    }
                }
            });

            *chat_log_handle.lock().await = Some(handle);
        }
        WebSocketMessage::LoadMoreChatHistory {
            session_name,
            window_index,
            offset,
            limit,
        } => {
            let message_tx = state.message_tx.clone();
            let chat_clear_store = state.chat_clear_store.clone();

            if session_name.starts_with("acp_") {
                // ACP session: re-fetch full history and slice the requested page
                let external_session_id = session_name[4..].to_string();
                let (provider, raw_session_id) = parse_direct_session_id(&external_session_id);
                let session_key = session_name.clone();
                let app_state_clone = app_state.clone();

                tokio::spawn(async move {
                    let cleared_at = chat_clear_store
                        .get_cleared_at(&session_key, window_index)
                        .await;

                    let messages = if provider == BACKEND_CODEX {
                        match load_codex_history(
                            app_state_clone.clone(),
                            external_session_id.clone(),
                            session_key.clone(),
                            cleared_at,
                        )
                        .await
                        {
                            Ok(messages) => messages,
                            Err(e) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: format!("Failed to read Codex history: {}", e),
                                    },
                                )
                                .await;
                                return;
                            }
                        }
                    } else {
                        let db_path = match crate::chat_log::watcher::find_opencode_db() {
                            Ok(p) => p,
                            Err(e) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: format!("OpenCode DB not found: {}", e),
                                    },
                                )
                                .await;
                                return;
                            }
                        };

                        let (opencode_messages, _) = tokio::task::spawn_blocking(move || {
                            crate::chat_log::opencode_parser::fetch_all_messages(
                                &db_path,
                                &raw_session_id,
                                cleared_at,
                            )
                        })
                        .await
                        .unwrap_or_else(|e| Err(anyhow::anyhow!("spawn_blocking failed: {}", e)))
                        .unwrap_or_else(|e| {
                            tracing::warn!("Failed to read OpenCode history: {}", e);
                            (vec![], 0)
                        });
                        opencode_messages
                    };

                    let total_count = messages.len();
                    let start = total_count.saturating_sub(offset + limit);
                    let end = total_count.saturating_sub(offset);
                    let chunk: Vec<_> = messages[start..end].to_vec();
                    let has_more = start > 0;

                    let _ = send_message(
                        &message_tx,
                        ServerMessage::ChatHistoryChunk {
                            session_name: session_key,
                            window_index,
                            messages: chunk,
                            has_more,
                        },
                    )
                    .await;
                });
            } else {
                // Claude Code session: re-parse log file and slice the requested page
                let session_name_owned = session_name.clone();
                tokio::spawn(async move {
                    let cleared_at = chat_clear_store
                        .get_cleared_at(&session_name_owned, window_index)
                        .await;

                    let (path, tool) = match crate::chat_log::watcher::detect_log_file(
                        &session_name_owned,
                        window_index,
                    )
                    .await
                    {
                        Ok(r) => r,
                        Err(e) => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatLogError {
                                    error: format!("Failed to detect log file: {}", e),
                                },
                            )
                            .await;
                            return;
                        }
                    };

                    // Spawn a one-shot channel to collect the initial History event.
                    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
                    if crate::chat_log::watcher::watch_log_file(&path, tool, tx, cleared_at, None)
                        .await
                        .is_err()
                    {
                        return;
                    }
                    if let Some(crate::chat_log::ChatLogEvent::History { messages, .. }) =
                        rx.recv().await
                    {
                        let total_count = messages.len();
                        let start = total_count.saturating_sub(offset + limit);
                        let end = total_count.saturating_sub(offset);
                        let chunk: Vec<_> = messages[start..end].to_vec();
                        let has_more = start > 0;

                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatHistoryChunk {
                                session_name: session_name_owned,
                                window_index,
                                messages: chunk,
                                has_more,
                            },
                        )
                        .await;
                    }
                });
            }
        }
        WebSocketMessage::UnwatchChatLog => {
            info!(
                "Stopping chat log watch for client={} current_session={:?} current_window={:?}",
                state.client_id,
                *state.current_session.lock().await,
                *state.current_window.lock().await
            );
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
                match crate::chat_log::watcher::detect_log_file(
                    &session_name_owned,
                    window_index_owned,
                )
                .await
                {
                    Ok((path, tool)) => {
                        let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel();

                        // Get the clear timestamp
                        let cleared_at = chat_clear_store
                            .get_cleared_at(&session_name_owned, window_index_owned)
                            .await;

                        let _watcher = match crate::chat_log::watcher::watch_log_file(
                            &path,
                            tool.clone(),
                            event_tx,
                            cleared_at,
                            None,
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
                                crate::chat_log::ChatLogEvent::History {
                                    messages,
                                    tool,
                                    has_more,
                                    total_count,
                                    context_window_usage,
                                    model_name,
                                } => {
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
                                        merge_history_messages(messages, persisted_messages);

                                    ServerMessage::ChatHistory {
                                        session_name: session_name_owned.clone(),
                                        window_index: window_index_owned,
                                        messages: merged_messages,
                                        tool: Some(tool),
                                        has_more,
                                        total_count,
                                        context_window_usage,
                                        model_name,
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
                                crate::chat_log::ChatLogEvent::ContextWindowUpdate {
                                    usage,
                                    model_name,
                                } => ServerMessage::ContextWindowUpdate {
                                    session_name: session_name_owned.clone(),
                                    window_index: window_index_owned,
                                    context_window_usage: usage,
                                    model_name,
                                },
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
            let session_path = resolve_chat_upload_target_dir(&session_name);

            // Save file to session directory if we have a valid path
            let file_path_str = if let Some(ref session_path) = session_path {
                match state.chat_file_storage.save_file_to_directory(
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
            let file_id =
                match state
                    .chat_file_storage
                    .save_file(&file.data, &file.filename, &file.mime_type)
                {
                    Ok(id) => id,
                    Err(e) => {
                        error!("Failed to save file for display: {}", e);
                        return Ok(());
                    }
                };

            // Build combined text for chat log (prompt + file path, multi-line OK)
            let combined_text = match (&prompt, &file_path_str) {
                (Some(text), Some(path)) if !text.trim().is_empty() => {
                    format!("{}\n\nHere is the file: {}", text.trim(), path)
                }
                (Some(_), Some(path)) => format!("Here is the file: {}", path),
                (Some(text), None) if !text.trim().is_empty() => text.trim().to_string(),
                (None, Some(path)) => format!("Here is the file: {}", path),
                _ => String::new(),
            };

            // Build single-line text for tmux. Wrap file paths in backticks so
            // TUI apps like Claude Code don't auto-detect image extensions
            // (.jpg/.png) and convert them to [Image #N] inline attachments,
            // which swallows the path text. With backticks, the AI reads the
            // file via its Read tool instead.
            let tmux_text = match (&prompt, &file_path_str) {
                (Some(text), Some(path)) if !text.trim().is_empty() => {
                    format!("{} `{}`", text.trim(), path)
                }
                (Some(_), Some(path)) | (None, Some(path)) => {
                    format!("`{}`", path)
                }
                (Some(text), None) if !text.trim().is_empty() => text.trim().to_string(),
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

            // Send the single-line text (prompt + file path) to the tmux session
            // so the AI tool (OpenCode/Claude) receives the file reference.
            if !tmux_text.is_empty() {
                let target = format!("{}:{}", session_name, window_index);

                // Two separate tmux calls: text first, then Enter.
                let send_text = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "-l", &tmux_text])
                    .output()
                    .await;

                // If session:window failed (window doesn't exist), retry with session only.
                let (send_text, effective_target) = match &send_text {
                    Ok(output) if !output.status.success() => {
                        warn!(
                            "SendFileToChat: target {} failed, retrying with session only ({})",
                            target, session_name
                        );
                        let retry = tokio::process::Command::new("tmux")
                            .args(&["send-keys", "-t", &session_name, "-l", &tmux_text])
                            .output()
                            .await;
                        (retry, session_name.clone())
                    }
                    _ => (send_text, target.clone()),
                };

                // Delay so the TUI finishes processing the typed text before Enter.
                tokio::time::sleep(tokio::time::Duration::from_millis(80)).await;
                let send_enter = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &effective_target, "Enter"])
                    .output()
                    .await;

                match (send_text, send_enter) {
                    (Ok(t), Ok(e)) if t.status.success() && e.status.success() => {
                        info!(
                            "SendFileToChat: OK sent to tmux target {}: {:?}",
                            effective_target, tmux_text
                        );
                    }
                    (Ok(t), Ok(e)) => {
                        error!("SendFileToChat: tmux send-keys FAILED for {} — text_exit={}, enter_exit={}, text_stderr={:?}, enter_stderr={:?}",
                            effective_target, t.status, e.status,
                            String::from_utf8_lossy(&t.stderr),
                            String::from_utf8_lossy(&e.stderr));
                    }
                    (Err(e), _) | (_, Err(e)) => {
                        error!(
                            "SendFileToChat: failed to spawn tmux for {}: {}",
                            effective_target, e
                        );
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
            let send_text = tokio::process::Command::new("tmux")
                .args(&["send-keys", "-t", &target, "-l", text])
                .output()
                .await;

            // If session:window failed (window doesn't exist), retry with session only
            // so tmux targets the active window.
            let (_send_text, effective_target) = match &send_text {
                Ok(output) if !output.status.success() => {
                    warn!(
                        "SendChatMessage: target {} failed, retrying with session only ({})",
                        target, session_name
                    );
                    let retry = tokio::process::Command::new("tmux")
                        .args(&["send-keys", "-t", &session_name, "-l", text])
                        .output()
                        .await;
                    (retry, session_name.clone())
                }
                _ => (send_text, target.clone()),
            };

            tokio::time::sleep(tokio::time::Duration::from_millis(80)).await;
            let result = tokio::process::Command::new("tmux")
                .args(&["send-keys", "-t", &effective_target, "Enter"])
                .output()
                .await;

            match result {
                Ok(output) if output.status.success() => {
                    info!(
                        "Sent chat message to tmux: {:?} (target: {})",
                        text, effective_target
                    );
                }
                Ok(output) => {
                    error!(
                        "tmux send-keys failed for {}: {}",
                        effective_target, output.status
                    );
                }
                Err(e) => {
                    error!(
                        "Failed to send chat message to tmux session {}: {}",
                        effective_target, e
                    );
                }
            }
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
    use base64::Engine;
    use std::sync::Arc;
    use tempfile::TempDir;
    use tokio::sync::{mpsc, Mutex};

    fn make_ws_state(
        dir: &std::path::Path,
    ) -> (WsState, mpsc::Receiver<BroadcastMessage>) {
        let (tx, rx) = mpsc::channel::<BroadcastMessage>(256);
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None,
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            client_manager,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
            selected_backend: "acp".to_string(),
        };
        (state, rx)
    }

    #[tokio::test]
    async fn test_unwatch_chat_log() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::UnwatchChatLog,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_clear_chat_log() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-session".to_string(),
                window_index: 0,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Should have received ChatLogCleared response
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("Cleared") || json.contains("cleared"));
    }

    #[tokio::test]
    async fn test_load_more_chat_history_acp_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // acp_ prefix sessions try to read opencode DB — it won't exist, error handled gracefully
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_test-session-id".to_string(),
                window_index: 0,
                offset: 0,
                limit: 20,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_regular_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                offset: 0,
                limit: 20,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_image() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        // 1x1 PNG pixel in base64
        let png_data = base64::engine::general_purpose::STANDARD.encode(
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82"
        );
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data: png_data,
                    filename: "test.png".to_string(),
                    mime_type: "image/png".to_string(),
                },
                prompt: Some("Check this image".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Should broadcast the file message
        let _ = rx.try_recv();
    }

    #[tokio::test]
    async fn test_send_file_to_chat_document() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"document content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "doc.pdf".to_string(),
                    mime_type: "application/pdf".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_audio() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"audio bytes");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "sound.mp3".to_string(),
                    mime_type: "audio/mpeg".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_chat_message_empty_ignored() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "   ".to_string(),
                notify: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Empty messages are ignored — no response expected
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn test_send_chat_message_valid() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Hello from test".to_string(),
                notify: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_watch_chat_log_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "nonexistent-session-xyz".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Task is spawned asynchronously — give it a moment
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn test_unknown_message_no_op() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::Ping,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_clear_chat_log_sets_cleared_at() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let app = make_app_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-session".to_string(),
                window_index: 0,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(result.is_ok());
        // The handler uses state.chat_clear_store (not app's), verify on state's store
        let ts = state
            .chat_clear_store
            .get_cleared_at("test-session", 0)
            .await;
        assert!(ts.is_some());
    }

    #[tokio::test]
    async fn test_clear_chat_log_clears_persisted_messages() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate messages
        let msg = crate::chat_log::ChatMessage {
            role: "user".to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![crate::chat_log::ContentBlock::Text {
                text: "hello".to_string(),
            }],
        };
        app.chat_event_store
            .append_message("test-sess", 0, "webhook", &msg)
            .unwrap();
        assert_eq!(
            app.chat_event_store
                .list_messages("test-sess", 0)
                .unwrap()
                .len(),
            1
        );

        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-sess".to_string(),
                window_index: 0,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(result.is_ok());
        // Messages should be cleared
        let msgs = app.chat_event_store.list_messages("test-sess", 0).unwrap();
        assert_eq!(msgs.len(), 0);
    }

    #[tokio::test]
    async fn test_clear_chat_log_response_content() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "my-session".to_string(),
                window_index: 1,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("chat-log-cleared"));
        assert!(json.contains("true")); // success: true
        assert!(json.contains("my-session"));
    }

    #[tokio::test]
    async fn test_send_chat_message_with_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Test notification message".to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_chat_message_no_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "No notification".to_string(),
                notify: Some(false),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_chat_message_persists_to_store() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "persist-test".to_string(),
                window_index: 0,
                message: "Persisted message".to_string(),
                notify: None,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(result.is_ok());
        // The message uses the state's chat_event_store, not app's
        let msgs = state
            .chat_event_store
            .list_messages("persist-test", 0)
            .unwrap();
        assert!(!msgs.is_empty());
    }

    #[tokio::test]
    async fn test_watch_acp_chat_log_no_opencode_db() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchAcpChatLog {
                session_id: "test-acp-session".to_string(),
                window_index: Some(0),
                limit: Some(20),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Spawned task will fail to find opencode DB — should handle gracefully
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_load_more_chat_history_with_data() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate with messages using webhook source
        for i in 0..5 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_test-hist", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_test-hist".to_string(),
                window_index: 0,
                offset: 0,
                limit: 3,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
        // Should receive a response (chat-history-chunk or error)
        let _ = rx.try_recv();
    }

    // ===== New tests for additional coverage =====

    #[tokio::test]
    async fn test_send_file_to_chat_invalid_base64() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Invalid base64 data - should fail at save_file and return early
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data: "NOT_VALID_BASE64!!!".to_string(),
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Session doesn't exist - session_path will be None, but save_file for display should still work
        let data = base64::engine::general_purpose::STANDARD.encode(b"test content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "nonexistent-session-xyz-123".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_with_prompt_only() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // File with prompt only (no combined text path from file)
        let data = base64::engine::general_purpose::STANDARD.encode(b"content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: Some("Analyze this file".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_video() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Video file - falls through to generic File block
        let data = base64::engine::general_purpose::STANDARD.encode(b"video data");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "video.mp4".to_string(),
                    mime_type: "video/mp4".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_both_prompt_and_file_path() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // With prompt AND valid session (so file_path_str is Some)
        let data = base64::engine::general_purpose::STANDARD.encode(b"file content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "doc.pdf".to_string(),
                    mime_type: "application/pdf".to_string(),
                },
                prompt: Some("Review this document".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_watch_chat_log_detect_log_file_error() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // This will spawn a task that tries to detect log file for a nonexistent session
        // The test verifies the handle returns Ok and the spawned task handles the error gracefully
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "definitely-does-not-exist-12345".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Give the spawned task time to run and fail
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_watch_chat_log_sets_current_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Pre-existing watcher should be cancelled
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        // Verify current session was set
        let session = state.current_session.lock().await;
        assert_eq!(*session, Some("AgentShell".to_string()));
    }

    #[tokio::test]
    async fn test_send_chat_message_long_preview_truncation() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Message > 50 chars should be truncated in notification preview
        let long_msg =
            "This is a very long message that exceeds fifty characters and should be truncated";
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: long_msg.to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_chat_message_short_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Short message (< 50 chars) notification preview
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Short".to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_unwatch_chat_log_with_existing_handle() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // First start watching
        handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await
        .unwrap();
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        // Then unwatch - should abort the handle
        let result = handle(
            WebSocketMessage::UnwatchChatLog,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_offset_pagination() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate with messages
        for i in 0..10 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_paginate-test", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, _rx) = make_ws_state(dir.path());
        // Request with offset 5, limit 3
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_paginate-test".to_string(),
                window_index: 0,
                offset: 5,
                limit: 3,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_offset_exceeds_total() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Only 3 messages but request offset 10
        for i in 0..3 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_offset-test", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_offset-test".to_string(),
                window_index: 0,
                offset: 10,
                limit: 5,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_watch_chat_log_with_limit() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: Some(50),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn test_send_file_to_chat_whitespace_only_prompt() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: Some("   ".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_watch_acp_chat_log_with_limit() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchAcpChatLog {
                session_id: "test-acp-session".to_string(),
                window_index: Some(0),
                limit: Some(50),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_clear_chat_log_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "nonexistent-session-xyz-123".to_string(),
                window_index: 0,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_send_file_to_chat_app_state_uses_different_store() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        let (mut state, _rx) = make_ws_state(dir.path());
        // File gets saved to state.chat_file_storage, not app's
        let data = base64::engine::general_purpose::STANDARD.encode(b"test content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
    }

    fn make_app_state(dir: &std::path::Path) -> Arc<crate::AppState> {
        let (broadcast_tx, _) = mpsc::channel(256);
        let client_manager = Arc::new(ClientManager::new());
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let notification_store =
            Arc::new(crate::notification_store::NotificationStore::new(dir.to_path_buf()).unwrap());
        let favorite_store =
            Arc::new(crate::favorite_store::FavoriteStore::new(dir.to_path_buf()).unwrap());
        let tag_store = Arc::new(crate::tag_store::TagStore::new(dir.to_path_buf()).unwrap());
        Arc::new(crate::AppState {
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
        })
    }
}
