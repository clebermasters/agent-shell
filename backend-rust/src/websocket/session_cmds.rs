use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{debug, error, info};

use super::types::{send_message, BroadcastMessage, WsState};
use crate::{tmux, types::*, AppState};

pub(crate) async fn handle_list_sessions(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    info!("Listing tmux sessions...");
    let sessions = tmux::list_sessions().await.unwrap_or_default();
    info!("Found {} tmux sessions", sessions.len());
    let response = ServerMessage::SessionsList { sessions };
    send_message(tx, response).await
}

pub(crate) async fn handle_create_session(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    name: Option<String>,
    start_directory: Option<String>,
    startup_command: Option<String>,
    startup_args: Option<String>,
) -> anyhow::Result<()> {
    let session_name =
        name.unwrap_or_else(|| format!("session-{}", chrono::Utc::now().timestamp_millis()));
    info!("Creating session: {} at {:?}", session_name, start_directory);

    match tmux::create_session_at(&session_name, start_directory.as_deref()).await {
        Ok(_) => {
            info!("Successfully created session: {}", session_name);

            if let Some(cmd) = startup_command.filter(|c| !c.trim().is_empty()) {
                let full_cmd = match startup_args.filter(|a| !a.trim().is_empty()) {
                    Some(args) => format!("{} {}", cmd, args),
                    None => cmd,
                };
                tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                if let Err(e) = tmux::send_command_to_session(&session_name, &full_cmd).await {
                    error!("Failed to send startup command to session {}: {}", session_name, e);
                }
            }

            let response = ServerMessage::SessionCreated {
                success: true,
                session_name: Some(session_name),
                error: None,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            error!("Failed to create session: {}", e);
            let response = ServerMessage::SessionCreated {
                success: false,
                session_name: None,
                error: Some(format!("Failed to create session: {}", e)),
            };
            send_message(tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_kill_session(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    session_name: String,
) -> anyhow::Result<()> {
    info!("Kill session request for: {}", session_name);

    match tmux::kill_session(&session_name).await {
        Ok(_) => {
            info!("Successfully killed session: {}", session_name);
            let _ = app_state.tag_store.clear_tags_for_session(&session_name);
            let response = ServerMessage::SessionKilled {
                success: true,
                error: None,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            error!("Failed to kill session: {}", e);
            let response = ServerMessage::SessionKilled {
                success: false,
                error: Some(format!("Failed to kill session: {}", e)),
            };
            send_message(tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_rename_session(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: String,
    new_name: String,
) -> anyhow::Result<()> {
    if new_name.trim().is_empty() {
        let response = ServerMessage::SessionRenamed {
            success: false,
            error: Some("Session name cannot be empty".to_string()),
        };
        send_message(tx, response).await?;
    } else {
        match tmux::rename_session(&session_name, &new_name).await {
            Ok(_) => {
                let response = ServerMessage::SessionRenamed {
                    success: true,
                    error: None,
                };
                send_message(tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::SessionRenamed {
                    success: false,
                    error: Some(format!("Failed to rename session: {}", e)),
                };
                send_message(tx, response).await?;
            }
        }
    }
    Ok(())
}

pub(crate) async fn handle_list_windows(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: String,
) -> anyhow::Result<()> {
    debug!("Listing windows for session: {}", session_name);
    match tmux::list_windows(&session_name).await {
        Ok(windows) => {
            let response = ServerMessage::WindowsList {
                session_name: session_name.clone(),
                windows,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            error!(
                "Failed to list windows for session {}: {}",
                session_name, e
            );
            let response = ServerMessage::Error {
                message: format!("Failed to list windows: {}", e),
            };
            send_message(tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_select_window(
    state: &mut WsState,
    session_name: String,
    window_index: u32,
) -> anyhow::Result<()> {
    debug!(
        "Selecting window {} in session {}",
        window_index, session_name
    );

    // First, ensure we're in the right session
    let current_session = state.current_session.lock().await;
    if current_session.as_ref() != Some(&session_name) {
        drop(current_session);
        // Need to switch sessions first
        info!(
            "Switching to session {} before selecting window",
            session_name
        );
        super::terminal_cmds::attach_to_session(state, &session_name, 80, 24).await?;
    }

    // Now select the window using tmux command
    match tmux::select_window(&session_name, &window_index.to_string()).await {
        Ok(_) => {
            let response = ServerMessage::WindowSelected {
                success: true,
                window_index: Some(window_index),
                error: None,
            };
            send_message(&state.message_tx, response).await?;
        }
        Err(e) => {
            let response = ServerMessage::WindowSelected {
                success: false,
                window_index: None,
                error: Some(e.to_string()),
            };
            send_message(&state.message_tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_create_window(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: String,
    window_name: Option<String>,
) -> anyhow::Result<()> {
    match tmux::create_window(&session_name, window_name.as_deref()).await {
        Ok(_) => {
            let response = ServerMessage::WindowCreated {
                success: true,
                error: None,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            let response = ServerMessage::WindowCreated {
                success: false,
                error: Some(format!("Failed to create window: {}", e)),
            };
            send_message(tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_kill_window(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: String,
    window_index: String,
) -> anyhow::Result<()> {
    match tmux::kill_window(&session_name, &window_index).await {
        Ok(_) => {
            let response = ServerMessage::WindowKilled {
                success: true,
                error: None,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            let response = ServerMessage::WindowKilled {
                success: false,
                error: Some(format!("Failed to kill window: {}", e)),
            };
            send_message(tx, response).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_rename_window(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: String,
    window_index: String,
    new_name: String,
) -> anyhow::Result<()> {
    if new_name.trim().is_empty() {
        let response = ServerMessage::WindowRenamed {
            success: false,
            error: Some("Window name cannot be empty".to_string()),
        };
        send_message(tx, response).await?;
    } else {
        match tmux::rename_window(&session_name, &window_index, &new_name).await {
            Ok(_) => {
                let response = ServerMessage::WindowRenamed {
                    success: true,
                    error: None,
                };
                send_message(tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::WindowRenamed {
                    success: false,
                    error: Some(format!("Failed to rename window: {}", e)),
                };
                send_message(tx, response).await?;
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tokio_util::sync::CancellationToken;

    use crate::AppState;
    use super::*;
    use tokio::sync::mpsc;
    use crate::websocket::types::BroadcastMessage;

    fn make_tx() -> (
        mpsc::UnboundedSender<BroadcastMessage>,
        mpsc::UnboundedReceiver<BroadcastMessage>,
    ) {
        mpsc::unbounded_channel()
    }

    fn make_app_state(dir: &std::path::Path) -> Arc<AppState> {
        let (broadcast_tx, _) = mpsc::unbounded_channel();
        let client_manager = Arc::new(crate::websocket::client_manager::ClientManager::new());
        Arc::new(AppState {
            enable_audio_logs: false,
            broadcast_tx,
            client_manager,
            chat_file_storage: Arc::new(crate::chat_file_storage::ChatFileStorage::new(dir.to_path_buf())),
            chat_event_store: Arc::new(crate::chat_event_store::ChatEventStore::new(dir.to_path_buf()).unwrap()),
            chat_clear_store: Arc::new(crate::chat_clear_store::ChatClearStore::new(&dir.to_path_buf())),
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            notification_store: Arc::new(crate::notification_store::NotificationStore::new(dir.to_path_buf()).unwrap()),
            favorite_store: Arc::new(crate::favorite_store::FavoriteStore::new(dir.to_path_buf()).unwrap()),
            tag_store: Arc::new(crate::tag_store::TagStore::new(dir.to_path_buf()).unwrap()),
            shutdown_token: CancellationToken::new(),
        })
    }

    #[tokio::test]
    async fn test_list_sessions() {
        let (tx, mut rx) = make_tx();
        let result = handle_list_sessions(&tx).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_list_windows_existing_session() {
        let (tx, mut rx) = make_tx();
        let result = handle_list_windows(&tx, "AgentShell".to_string()).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_list_windows_nonexistent_session() {
        let (tx, mut rx) = make_tx();
        let result = handle_list_windows(&tx, "nonexistent-xyz".to_string()).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_rename_session_empty_name_returns_error_response() {
        let (tx, mut rx) = make_tx();
        handle_rename_session(&tx, "AgentShell".to_string(), "".to_string()).await.unwrap();
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false") || json.contains("empty"));
    }

    #[tokio::test]
    async fn test_rename_window_empty_name_returns_error_response() {
        let (tx, mut rx) = make_tx();
        handle_rename_window(&tx, "AgentShell".to_string(), "0".to_string(), "".to_string()).await.unwrap();
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false") || json.contains("empty"));
    }

    #[tokio::test]
    async fn test_kill_session_nonexistent() {
        let dir = tempfile::TempDir::new().unwrap();
        let (tx, mut rx) = make_tx();
        let app_state = make_app_state(dir.path());
        let result = handle_kill_session(&tx, app_state, "nonexistent-session-xyz".to_string()).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_create_session_default_name() {
        let test_name = format!("test-{}", chrono::Utc::now().timestamp_millis());
        let (tx, mut rx) = make_tx();
        let result = handle_create_session(&tx, Some(test_name.clone()), None, None, None).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(!json.is_empty());
        assert!(json.contains(&test_name));
        // Cleanup: kill the tmux session we just created
        let _ = tmux::kill_session(&test_name).await;
    }

    #[tokio::test]
    async fn test_create_window_nonexistent_session() {
        let (tx, mut rx) = make_tx();
        let result = handle_create_window(&tx, "nonexistent-session".to_string(), None).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_kill_window_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle_kill_window(&tx, "nonexistent".to_string(), "99".to_string()).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }
}
