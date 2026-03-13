use tokio::sync::mpsc;
use tracing::{debug, error, info};

use super::types::{send_message, BroadcastMessage, WsState};
use crate::{tmux, types::*};

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
) -> anyhow::Result<()> {
    let session_name =
        name.unwrap_or_else(|| format!("session-{}", chrono::Utc::now().timestamp_millis()));
    info!("Creating session: {}", session_name);

    match tmux::create_session(&session_name).await {
        Ok(_) => {
            info!("Successfully created session: {}", session_name);
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
    session_name: String,
) -> anyhow::Result<()> {
    info!("Kill session request for: {}", session_name);

    match tmux::kill_session(&session_name).await {
        Ok(_) => {
            info!("Successfully killed session: {}", session_name);
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
