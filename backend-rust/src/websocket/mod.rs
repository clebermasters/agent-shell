mod acp_cmds;
mod chat_cmds;
mod client_manager;
mod cron_cmds;
mod dotfiles_cmds;
mod favorite_cmds;
mod file_cmds;
mod git_cmds;
mod session_cmds;
mod system_cmds;
mod tag_cmds;
mod terminal_cmds;
mod types;

pub use client_manager::ClientManager;
pub use types::BroadcastMessage;

use types::WsState;

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use futures::{sink::SinkExt, stream::StreamExt};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};
use tracing::{debug, error, info};
use uuid::Uuid;

use crate::{types::*, AppState};

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    info!("WebSocket upgrade request received");
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: Arc<AppState>) {
    let client_id = Uuid::new_v4().to_string();
    info!("New WebSocket connection established: {}", client_id);
    info!("[CONN] Backend socket opened client_id={}", client_id);

    let (mut sender, mut receiver) = socket.split();

    // Create channel for server messages
    let (tx, mut rx) = mpsc::channel::<BroadcastMessage>(256);

    // Register client with the manager
    state
        .client_manager
        .add_client(client_id.clone(), tx.clone())
        .await;

    let mut ws_state = WsState {
        client_id: client_id.clone(),
        selected_backend: "acp".to_string(),
        current_pty: Arc::new(Mutex::new(None)),
        current_session: Arc::new(Mutex::new(None)),
        current_window: Arc::new(Mutex::new(None)),
        audio_tx: None,
        message_tx: tx.clone(),
        chat_log_handle: Arc::new(Mutex::new(None)),
        chat_file_storage: state.chat_file_storage.clone(),
        chat_event_store: state.chat_event_store.clone(),
        chat_clear_store: state.chat_clear_store.clone(),
        client_manager: state.client_manager.clone(),
        acp_client: state.acp_client.clone(),
        kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
    };

    // Clone client_id for the spawned task
    let _task_client_id = client_id.clone();

    // Oneshot to signal the receiver loop when the sender dies
    let (close_tx, mut close_rx) = tokio::sync::oneshot::channel::<()>();

    // Spawn task to forward server messages to WebSocket with backpressure handling
    let sender_client_id = client_id.clone();
    tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            match msg {
                BroadcastMessage::Text(json) => {
                    // Check if we can send without blocking
                    if let Err(e) = sender.send(Message::Text(json.to_string())).await {
                        error!(
                            "[CONN] Backend→Flutter send FAILED ({}): {}",
                            sender_client_id, e
                        );
                        let _ = close_tx.send(());
                        break;
                    }
                    // Add small delay to prevent flooding
                    if json.contains("\"type\":\"output\"") && json.len() > 1000 {
                        tokio::time::sleep(tokio::time::Duration::from_micros(100)).await;
                    }
                }
                BroadcastMessage::Binary(data) => {
                    if let Err(e) = sender.send(Message::Binary(data.to_vec())).await {
                        error!(
                            "[CONN] Backend→Flutter binary send FAILED ({}): {}",
                            sender_client_id, e
                        );
                        let _ = close_tx.send(());
                        break;
                    }
                }
            }
        }
    });

    // Handle incoming messages
    let shutdown_token = state.shutdown_token.clone();
    loop {
        tokio::select! {
            msg = receiver.next() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        debug!("Received WebSocket message: {}", text);
                        match serde_json::from_str::<WebSocketMessage>(&text) {
                            Ok(ws_msg) => {
                                let msg_type = format!("{:?}", std::mem::discriminant(&ws_msg));
                                debug!("Parsed message type: {}", msg_type);
                                let t = std::time::Instant::now();
                                if let Err(e) = handle_message(ws_msg, &mut ws_state, state.clone()).await {
                                    error!("Error handling message: {}", e);
                                }
                                let elapsed = t.elapsed();
                                if elapsed.as_millis() > 50 {
                                    info!("[TIMING] handle_message({}) took {:?}", msg_type, elapsed);
                                }
                            }
                            Err(e) => {
                                error!("Failed to parse WebSocket message: {} - Error: {}", text, e);
                            }
                        }
                    }
                    Some(Ok(Message::Close(_))) => {
                        info!("[CONN] Flutter→Backend CLOSE frame received ({})", client_id);
                        break;
                    }
                    Some(Ok(_)) => {
                        debug!("Ignoring WebSocket message type");
                    }
                    Some(Err(e)) => {
                        error!("[CONN] Flutter→Backend recv ERROR ({}): {}", client_id, e);
                        break;
                    }
                    None => {
                        info!("[CONN] Flutter→Backend stream ENDED ({})", client_id);
                        break;
                    }
                }
            }
            _ = &mut close_rx => {
                info!("[CONN] Backend→Flutter sender DIED, closing receiver ({})", client_id);
                break;
            }
            _ = shutdown_token.cancelled() => {
                info!("[CONN] Server shutting down, gracefully closing client ({})", client_id);
                break;
            }
        }
    }

    // Cleanup - use graceful mode to preserve tmux sessions on server shutdown
    let is_shutdown = state.shutdown_token.is_cancelled();
    terminal_cmds::cleanup_session(&ws_state, is_shutdown).await;
    let had_chat_watcher = ws_state.chat_log_handle.lock().await.is_some();
    state.client_manager.remove_client(&client_id).await;
    info!(
        "WebSocket connection closed: {} (had_chat_watcher={})",
        client_id,
        had_chat_watcher
    );
}

async fn handle_message(
    msg: WebSocketMessage,
    state: &mut WsState,
    app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::ListSessions => {
            session_cmds::handle_list_sessions(&state.message_tx).await?;
        }

        WebSocketMessage::AttachSession {
            session_name,
            cols,
            rows,
            window_index,
        } => {
            terminal_cmds::handle_attach(state, session_name, cols, rows, window_index).await?;
        }

        WebSocketMessage::Input { data } => {
            terminal_cmds::handle_input(state, data).await?;
        }

        WebSocketMessage::InputViaTmux {
            session_name,
            window_index,
            data,
        } => {
            terminal_cmds::handle_input_via_tmux(state, session_name, window_index, data).await?;
        }

        WebSocketMessage::SendEnterKey => {
            terminal_cmds::handle_send_enter_key(state).await?;
        }

        WebSocketMessage::Resize { cols, rows } => {
            terminal_cmds::handle_resize(state, cols, rows).await?;
        }

        WebSocketMessage::ListWindows { session_name } => {
            session_cmds::handle_list_windows(&state.message_tx, session_name).await?;
        }

        WebSocketMessage::SelectWindow {
            session_name,
            window_index,
        } => {
            session_cmds::handle_select_window(state, session_name, window_index).await?;
        }

        WebSocketMessage::Ping => {
            system_cmds::handle_ping(&state.message_tx).await?;
        }

        WebSocketMessage::AudioControl { action } => {
            system_cmds::handle_audio_control(state, action).await?;
        }

        // Session management — delegated to session_cmds
        WebSocketMessage::CreateSession {
            name,
            start_directory,
            startup_command,
            startup_args,
        } => {
            session_cmds::handle_create_session(
                &state.message_tx,
                name,
                start_directory,
                startup_command,
                startup_args,
            )
            .await?;
        }

        WebSocketMessage::KillSession { session_name } => {
            session_cmds::handle_kill_session(
                &state.message_tx,
                Arc::clone(&app_state),
                session_name,
            )
            .await?;
        }

        WebSocketMessage::RenameSession {
            session_name,
            new_name,
        } => {
            session_cmds::handle_rename_session(&state.message_tx, session_name, new_name).await?;
        }

        // Window management — delegated to session_cmds
        WebSocketMessage::CreateWindow {
            session_name,
            window_name,
        } => {
            session_cmds::handle_create_window(&state.message_tx, session_name, window_name)
                .await?;
        }

        WebSocketMessage::KillWindow {
            session_name,
            window_index,
        } => {
            session_cmds::handle_kill_window(&state.message_tx, session_name, window_index).await?;
        }

        WebSocketMessage::RenameWindow {
            session_name,
            window_index,
            new_name,
        } => {
            session_cmds::handle_rename_window(
                &state.message_tx,
                session_name,
                window_index,
                new_name,
            )
            .await?;
        }

        // System stats
        WebSocketMessage::GetStats { include_containers } => {
            system_cmds::handle_get_stats(&state.message_tx, include_containers).await?;
        }

        WebSocketMessage::ContainerAction {
            runtime,
            container_id,
            action,
        } => {
            system_cmds::handle_container_action(
                &state.message_tx,
                runtime,
                container_id,
                action,
            )
            .await?;
        }

        WebSocketMessage::GetClaudeUsage => {
            system_cmds::handle_get_claude_usage(&state.message_tx).await?;
        }

        WebSocketMessage::GetCodexUsage => {
            system_cmds::handle_get_codex_usage(&state.message_tx).await?;
        }

        // Cron management — delegated to cron_cmds
        WebSocketMessage::ListCronJobs
        | WebSocketMessage::CreateCronJob { .. }
        | WebSocketMessage::UpdateCronJob { .. }
        | WebSocketMessage::DeleteCronJob { .. }
        | WebSocketMessage::ToggleCronJob { .. }
        | WebSocketMessage::TestCronCommand { .. } => {
            cron_cmds::handle(msg, &state.message_tx).await?;
        }

        // Dotfile management — delegated to dotfiles_cmds
        WebSocketMessage::ListDotfiles
        | WebSocketMessage::ReadDotfile { .. }
        | WebSocketMessage::WriteDotfile { .. }
        | WebSocketMessage::GetDotfileHistory { .. }
        | WebSocketMessage::RestoreDotfileVersion { .. }
        | WebSocketMessage::GetDotfileTemplates => {
            dotfiles_cmds::handle(msg, &state.message_tx).await?;
        }

        // Chat handlers — delegated to chat_cmds
        WebSocketMessage::WatchChatLog { .. }
        | WebSocketMessage::WatchAcpChatLog { .. }
        | WebSocketMessage::LoadMoreChatHistory { .. }
        | WebSocketMessage::UnwatchChatLog
        | WebSocketMessage::ClearChatLog { .. }
        | WebSocketMessage::SendFileToChat { .. }
        | WebSocketMessage::SendChatMessage { .. } => {
            chat_cmds::handle(msg, state, app_state).await?;
        }

        // File browser — delegated to file_cmds
        WebSocketMessage::ListFiles { .. }
        | WebSocketMessage::GetSessionCwd { .. }
        | WebSocketMessage::DeleteFiles { .. }
        | WebSocketMessage::RenameFile { .. }
        | WebSocketMessage::CopyFiles { .. }
        | WebSocketMessage::MoveFiles { .. }
        | WebSocketMessage::ReadBinaryFile { .. }
        | WebSocketMessage::WriteFile { .. } => {
            file_cmds::handle(msg, &state.message_tx).await?;
        }

        // Git operations — delegated to git_cmds
        WebSocketMessage::GitStatus { .. }
        | WebSocketMessage::GitDiff { .. }
        | WebSocketMessage::GitLog { .. }
        | WebSocketMessage::GitBranches { .. }
        | WebSocketMessage::GitCheckout { .. }
        | WebSocketMessage::GitCreateBranch { .. }
        | WebSocketMessage::GitDeleteBranch { .. }
        | WebSocketMessage::GitStage { .. }
        | WebSocketMessage::GitUnstage { .. }
        | WebSocketMessage::GitCommit { .. }
        | WebSocketMessage::GitPush { .. }
        | WebSocketMessage::GitPull { .. }
        | WebSocketMessage::GitStash { .. }
        | WebSocketMessage::GitCommitFiles { .. }
        | WebSocketMessage::GitCommitDiff { .. }
        | WebSocketMessage::GitSearch { .. }
        | WebSocketMessage::GitFileHistory { .. }
        | WebSocketMessage::GitCherryPick { .. }
        | WebSocketMessage::GitRevert { .. }
        | WebSocketMessage::GitMerge { .. }
        | WebSocketMessage::GitBlame { .. }
        | WebSocketMessage::GitCompare { .. }
        | WebSocketMessage::GitRepoInfo { .. }
        | WebSocketMessage::GitAmend { .. }
        | WebSocketMessage::GitListTags { .. }
        | WebSocketMessage::GitCreateTag { .. }
        | WebSocketMessage::GitDeleteTag { .. }
        | WebSocketMessage::GitResolveConflict { .. } => {
            git_cmds::handle(msg, &state.message_tx).await?;
        }

        // ACP handlers — delegated to acp_cmds
        WebSocketMessage::SelectBackend { .. }
        | WebSocketMessage::AcpCreateSession { .. }
        | WebSocketMessage::AcpResumeSession { .. }
        | WebSocketMessage::AcpForkSession { .. }
        | WebSocketMessage::AcpListSessions
        | WebSocketMessage::AcpSendPrompt { .. }
        | WebSocketMessage::SendFileToAcpChat { .. }
        | WebSocketMessage::AcpCancelPrompt { .. }
        | WebSocketMessage::AcpSetModel { .. }
        | WebSocketMessage::AcpSetMode { .. }
        | WebSocketMessage::AcpRespondPermission { .. }
        | WebSocketMessage::AcpLoadHistory { .. }
        | WebSocketMessage::AcpClearHistory { .. }
        | WebSocketMessage::AcpDeleteSession { .. } => {
            acp_cmds::handle(msg, state, app_state).await?;
        }

        // Favorites
        WebSocketMessage::GetFavorites => {
            favorite_cmds::handle_get_favorites(&state.message_tx, app_state).await?;
        }
        WebSocketMessage::AddFavorite {
            name,
            path,
            sort_order,
            startup_command,
            startup_args,
            tag_ids,
        } => {
            favorite_cmds::handle_add_favorite(
                &state.message_tx,
                app_state,
                name,
                path,
                sort_order,
                startup_command,
                startup_args,
                tag_ids,
            )
            .await?;
        }
        WebSocketMessage::UpdateFavorite {
            id,
            name,
            path,
            sort_order,
            startup_command,
            startup_args,
            tag_ids,
        } => {
            favorite_cmds::handle_update_favorite(
                &state.message_tx,
                app_state,
                id,
                name,
                path,
                sort_order,
                startup_command,
                startup_args,
                tag_ids,
            )
            .await?;
        }
        WebSocketMessage::DeleteFavorite { id } => {
            favorite_cmds::handle_delete_favorite(&state.message_tx, app_state, id).await?;
        }
        WebSocketMessage::SetFavoriteTags {
            favorite_id,
            tag_ids,
        } => {
            favorite_cmds::handle_set_favorite_tags(
                &state.message_tx,
                app_state,
                favorite_id,
                tag_ids,
            )
            .await?;
        }

        // Tags
        WebSocketMessage::GetTags => {
            tag_cmds::handle_get_tags(&state.message_tx, app_state).await?;
        }
        WebSocketMessage::AddTag { name, color_hex } => {
            tag_cmds::handle_add_tag(&state.message_tx, app_state, name, color_hex).await?;
        }
        WebSocketMessage::DeleteTag { id } => {
            tag_cmds::handle_delete_tag(&state.message_tx, app_state, id).await?;
        }
        WebSocketMessage::GetTagAssignments => {
            tag_cmds::handle_get_tag_assignments(&state.message_tx, app_state).await?;
        }
        WebSocketMessage::AssignTagToSession {
            session_name,
            tag_id,
        } => {
            tag_cmds::handle_assign_tag_to_session(
                &state.message_tx,
                app_state,
                session_name,
                tag_id,
            )
            .await?;
        }
        WebSocketMessage::RemoveTagFromSession {
            session_name,
            tag_id,
        } => {
            tag_cmds::handle_remove_tag_from_session(
                &state.message_tx,
                app_state,
                session_name,
                tag_id,
            )
            .await?;
        }
    }

    Ok(())
}
