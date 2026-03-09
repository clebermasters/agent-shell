use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use bytes::Bytes;
use futures::{sink::SinkExt, stream::StreamExt};
use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use std::{
    cmp::Ordering,
    collections::HashMap,
    io::{Read, Write},
    sync::Arc,
};
use tokio::{
    sync::{mpsc, Mutex, RwLock},
    task::JoinHandle,
};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::{
    audio, chat_clear_store, chat_event_store, chat_file_storage, chat_log::ChatMessage, tmux,
    types::*, AppState,
};
use sysinfo::System;

type ClientId = String;

// Pre-serialized message for zero-copy broadcasting
#[derive(Clone)]
pub enum BroadcastMessage {
    Text(Arc<String>),
    Binary(Bytes),
}

// Client manager for broadcasting messages to all connected clients
pub struct ClientManager {
    clients: Arc<RwLock<HashMap<ClientId, mpsc::UnboundedSender<BroadcastMessage>>>>,
}

impl ClientManager {
    pub fn new() -> Self {
        Self {
            clients: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn add_client(
        &self,
        client_id: ClientId,
        tx: mpsc::UnboundedSender<BroadcastMessage>,
    ) {
        let mut clients = self.clients.write().await;
        clients.insert(client_id, tx);
        info!("Client added. Total clients: {}", clients.len());
    }

    pub async fn remove_client(&self, client_id: &str) {
        let mut clients = self.clients.write().await;
        clients.remove(client_id);
        info!("Client removed. Total clients: {}", clients.len());
    }

    pub async fn client_count(&self) -> usize {
        let clients = self.clients.read().await;
        clients.len()
    }

    pub async fn broadcast(&self, message: ServerMessage) {
        // Serialize once for all clients
        if let Ok(serialized) = serde_json::to_string(&message) {
            let msg = BroadcastMessage::Text(Arc::new(serialized));
            let clients = self.clients.read().await;
            for (client_id, tx) in clients.iter() {
                if let Err(e) = tx.send(msg.clone()) {
                    error!("Failed to send to client {}: {}", client_id, e);
                }
            }
        }
    }

    pub async fn broadcast_binary(&self, data: Bytes) {
        let msg = BroadcastMessage::Binary(data);
        let clients = self.clients.read().await;
        for (client_id, tx) in clients.iter() {
            if let Err(e) = tx.send(msg.clone()) {
                error!("Failed to send binary to client {}: {}", client_id, e);
            }
        }
    }
}

struct PtySession {
    writer: Arc<Mutex<Box<dyn Write + Send>>>,
    master: Arc<Mutex<Box<dyn portable_pty::MasterPty + Send>>>,
    reader_task: JoinHandle<()>,
    child: Arc<Mutex<Box<dyn portable_pty::Child + Send>>>,
    tmux_session: String,
}

struct WsState {
    client_id: ClientId,
    current_pty: Arc<Mutex<Option<PtySession>>>,
    current_session: Arc<Mutex<Option<String>>>,
    current_window: Arc<Mutex<Option<u32>>>,
    audio_tx: Option<mpsc::UnboundedSender<BroadcastMessage>>,
    message_tx: mpsc::UnboundedSender<BroadcastMessage>,
    chat_log_handle: Arc<Mutex<Option<JoinHandle<()>>>>,
    chat_file_storage: Arc<chat_file_storage::ChatFileStorage>,
    chat_event_store: Arc<chat_event_store::ChatEventStore>,
    chat_clear_store: Arc<chat_clear_store::ChatClearStore>,
    client_manager: Arc<ClientManager>,
    acp_client: Arc<tokio::sync::RwLock<Option<crate::acp::AcpClient>>>,
}

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

    let (mut sender, mut receiver) = socket.split();

    // Create channel for server messages
    let (tx, mut rx) = mpsc::unbounded_channel::<BroadcastMessage>();

    // Register client with the manager
    state
        .client_manager
        .add_client(client_id.clone(), tx.clone())
        .await;

    let mut ws_state = WsState {
        client_id: client_id.clone(),
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
    };

    // Clone client_id for the spawned task
    let _task_client_id = client_id.clone();

    // Spawn task to forward server messages to WebSocket with backpressure handling
    tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            match msg {
                BroadcastMessage::Text(json) => {
                    // Check if we can send without blocking
                    if let Err(e) = sender.send(Message::Text(json.to_string())).await {
                        error!("Failed to send message to WebSocket: {}", e);
                        break;
                    }
                    // Add small delay to prevent flooding
                    if json.contains("\"type\":\"output\"") && json.len() > 1000 {
                        tokio::time::sleep(tokio::time::Duration::from_micros(100)).await;
                    }
                }
                BroadcastMessage::Binary(data) => {
                    if let Err(e) = sender.send(Message::Binary(data.to_vec())).await {
                        error!("Failed to send binary to WebSocket: {}", e);
                        break;
                    }
                }
            }
        }
    });

    // Handle incoming messages
    while let Some(Ok(msg)) = receiver.next().await {
        match msg {
            Message::Text(text) => {
                info!("Received WebSocket message: {}", text);
                match serde_json::from_str::<WebSocketMessage>(&text) {
                    Ok(ws_msg) => {
                        info!("Parsed message type: {:?}", std::mem::discriminant(&ws_msg));
                        if let Err(e) = handle_message(ws_msg, &mut ws_state, state.clone()).await {
                            error!("Error handling message: {}", e);
                        }
                    }
                    Err(e) => {
                        error!("Failed to parse WebSocket message: {} - Error: {}", text, e);
                    }
                }
            }
            Message::Close(_) => {
                info!("WebSocket connection closed: {}", client_id);
                break;
            }
            _ => {
                debug!("Ignoring WebSocket message type: {:?}", msg);
            }
        }
    }

    // Cleanup
    cleanup_session(&ws_state).await;
    state.client_manager.remove_client(&client_id).await;
    info!("WebSocket connection closed: {}", client_id);
}

async fn handle_message(msg: WebSocketMessage, state: &mut WsState, app_state: Arc<AppState>) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::ListSessions => {
            info!("Listing tmux sessions...");
            let sessions = tmux::list_sessions().await.unwrap_or_default();
            info!("Found {} tmux sessions", sessions.len());
            let response = ServerMessage::SessionsList { sessions };
            send_message(&state.message_tx, response).await?;
        }

        WebSocketMessage::AttachSession {
            session_name,
            cols,
            rows,
            window_index,
        } => {
            info!("Attaching to session: {}", session_name);

            // Set current session and window for input handling
            *state.current_session.lock().await = Some(session_name.clone());
            *state.current_window.lock().await = window_index;

            attach_to_session(state, &session_name, cols, rows).await?;
        }

        WebSocketMessage::Input { data } => {
            let pty_opt = state.current_pty.lock().await;
            if let Some(ref pty) = *pty_opt {
                let mut writer = pty.writer.lock().await;
                if let Err(e) = writer.write_all(data.as_bytes()) {
                    error!("Failed to write to PTY: {}", e);
                    return Err(e.into());
                }
                writer.flush()?;
            } else {
                debug!("No PTY session active, ignoring input");
            }
        }

        WebSocketMessage::InputViaTmux {
            session_name,
            window_index,
            data,
        } => {
            info!("Received InputViaTmux: {:?}", data);
            info!(
                "  session_name: {:?}, window_index: {:?}",
                session_name, window_index
            );

            // Get session and window from message, fallback to global state
            let global_session = state.current_session.lock().await.clone();
            let global_window = *state.current_window.lock().await;

            info!(
                "  global_session: {:?}, global_window: {:?}",
                global_session, global_window
            );

            let session = session_name.or(global_session);
            let idx = window_index.or(global_window);

            info!("  Using session: {:?}, window: {:?}", session, idx);

            if let (Some(session), Some(window_idx)) = (session, idx) {
                let target = format!("{}:{}", session, window_idx);

                // Use direct tmux command - this works!
                let text = data.trim_end_matches('\n');
                let result = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, text])
                    .output()
                    .await;

                if result.is_ok() {
                    // Also send Enter
                    let _ = tokio::process::Command::new("tmux")
                        .args(&["send-keys", "-t", &target, "Enter"])
                        .output()
                        .await;
                    debug!("Sent keys via tmux: {:?}", data);
                } else {
                    error!("Failed to send keys via tmux");
                }
            } else {
                debug!("No session/window active, ignoring input via tmux");
            }
        }

        WebSocketMessage::SendEnterKey => {
            info!("Received SendEnterKey");
            let session_name = state.current_session.lock().await.clone();
            let window_index = state.current_window.lock().await;
            if let (Some(session), Some(idx)) = (session_name, *window_index) {
                match tmux::send_special_key(&session, idx, "Enter").await {
                    Ok(_) => debug!("Sent Enter key via tmux"),
                    Err(e) => error!("Failed to send Enter via tmux: {}", e),
                }
            } else {
                debug!("No session/window active, ignoring Enter key");
            }
        }

        WebSocketMessage::Resize { cols, rows } => {
            let pty_opt = state.current_pty.lock().await;
            if let Some(ref pty) = *pty_opt {
                let master = pty.master.lock().await;
                master.resize(PtySize {
                    rows,
                    cols,
                    pixel_width: 0,
                    pixel_height: 0,
                })?;
                debug!("Resized PTY to {}x{}", cols, rows);
            } else {
                debug!("No PTY session active, ignoring resize");
            }
        }

        WebSocketMessage::ListWindows { session_name } => {
            debug!("Listing windows for session: {}", session_name);
            match tmux::list_windows(&session_name).await {
                Ok(windows) => {
                    let response = ServerMessage::WindowsList {
                        session_name: session_name.clone(),
                        windows,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to list windows for session {}: {}", session_name, e);
                    let response = ServerMessage::Error {
                        message: format!("Failed to list windows: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::SelectWindow {
            session_name,
            window_index,
        } => {
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
                attach_to_session(state, &session_name, 80, 24).await?;
            }

            // Now select the window using tmux command
            match tmux::select_window(&session_name, &window_index.to_string()).await {
                Ok(_) => {
                    // Don't send keys to PTY - just use tmux command
                    // Sending keys can interfere with running programs like Claude Code

                    let response = ServerMessage::WindowSelected {
                        success: true,
                        window_index: Some(window_index),
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;

                    // Don't broadcast windows list - let frontend handle refreshing
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
        }

        WebSocketMessage::Ping => {
            send_message(&state.message_tx, ServerMessage::Pong).await?;
        }

        WebSocketMessage::AudioControl { action } => {
            info!("Received audio control: {:?}", action);
            match action {
                AudioAction::Start => {
                    info!("Starting audio streaming for client");
                    let tx = state.message_tx.clone();
                    state.audio_tx = Some(tx.clone());
                    audio::start_streaming(tx).await?;
                }
                AudioAction::Stop => {
                    info!("Stopping audio streaming for client");
                    if let Some(ref tx) = state.audio_tx {
                        audio::stop_streaming_for_client(tx).await?;
                    }
                    state.audio_tx = None;
                }
            }
        }

        // Session management
        WebSocketMessage::CreateSession { name } => {
            let session_name = name
                .unwrap_or_else(|| format!("session-{}", chrono::Utc::now().timestamp_millis()));
            info!("Creating session: {}", session_name);

            match tmux::create_session(&session_name).await {
                Ok(_) => {
                    info!("Successfully created session: {}", session_name);
                    let response = ServerMessage::SessionCreated {
                        success: true,
                        session_name: Some(session_name),
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to create session: {}", e);
                    let response = ServerMessage::SessionCreated {
                        success: false,
                        session_name: None,
                        error: Some(format!("Failed to create session: {}", e)),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::KillSession { session_name } => {
            info!("Kill session request for: {}", session_name);

            match tmux::kill_session(&session_name).await {
                Ok(_) => {
                    info!("Successfully killed session: {}", session_name);
                    let response = ServerMessage::SessionKilled {
                        success: true,
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to kill session: {}", e);
                    let response = ServerMessage::SessionKilled {
                        success: false,
                        error: Some(format!("Failed to kill session: {}", e)),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::RenameSession {
            session_name,
            new_name,
        } => {
            if new_name.trim().is_empty() {
                let response = ServerMessage::SessionRenamed {
                    success: false,
                    error: Some("Session name cannot be empty".to_string()),
                };
                send_message(&state.message_tx, response).await?;
            } else {
                match tmux::rename_session(&session_name, &new_name).await {
                    Ok(_) => {
                        let response = ServerMessage::SessionRenamed {
                            success: true,
                            error: None,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        let response = ServerMessage::SessionRenamed {
                            success: false,
                            error: Some(format!("Failed to rename session: {}", e)),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            }
        }

        // Window management
        WebSocketMessage::CreateWindow {
            session_name,
            window_name,
        } => match tmux::create_window(&session_name, window_name.as_deref()).await {
            Ok(_) => {
                let response = ServerMessage::WindowCreated {
                    success: true,
                    error: None,
                };
                send_message(&state.message_tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::WindowCreated {
                    success: false,
                    error: Some(format!("Failed to create window: {}", e)),
                };
                send_message(&state.message_tx, response).await?;
            }
        },

        WebSocketMessage::KillWindow {
            session_name,
            window_index,
        } => match tmux::kill_window(&session_name, &window_index).await {
            Ok(_) => {
                let response = ServerMessage::WindowKilled {
                    success: true,
                    error: None,
                };
                send_message(&state.message_tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::WindowKilled {
                    success: false,
                    error: Some(format!("Failed to kill window: {}", e)),
                };
                send_message(&state.message_tx, response).await?;
            }
        },

        WebSocketMessage::RenameWindow {
            session_name,
            window_index,
            new_name,
        } => {
            if new_name.trim().is_empty() {
                let response = ServerMessage::WindowRenamed {
                    success: false,
                    error: Some("Window name cannot be empty".to_string()),
                };
                send_message(&state.message_tx, response).await?;
            } else {
                match tmux::rename_window(&session_name, &window_index, &new_name).await {
                    Ok(_) => {
                        let response = ServerMessage::WindowRenamed {
                            success: true,
                            error: None,
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        let response = ServerMessage::WindowRenamed {
                            success: false,
                            error: Some(format!("Failed to rename window: {}", e)),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                }
            }
        }

        // System stats
        WebSocketMessage::GetStats => {
            let mut sys = System::new_all();
            sys.refresh_all();

            let load_avg = System::load_average();
            let stats = SystemStats {
                cpu: CpuInfo {
                    cores: sys.cpus().len(),
                    model: sys
                        .cpus()
                        .first()
                        .map(|c| c.brand().to_string())
                        .unwrap_or_default(),
                    usage: load_avg.one as f32,
                    load_avg: [
                        load_avg.one as f32,
                        load_avg.five as f32,
                        load_avg.fifteen as f32,
                    ],
                },
                memory: MemoryInfo {
                    total: sys.total_memory(),
                    used: sys.used_memory(),
                    free: sys.available_memory(),
                    percent: format!(
                        "{:.1}",
                        (sys.used_memory() as f64 / sys.total_memory() as f64) * 100.0
                    ),
                },
                uptime: System::uptime(),
                hostname: System::host_name().unwrap_or_default(),
                platform: std::env::consts::OS.to_string(),
                arch: std::env::consts::ARCH.to_string(),
            };

            let response = ServerMessage::Stats { stats };
            send_message(&state.message_tx, response).await?;
        }

        // Cron management
        WebSocketMessage::ListCronJobs => {
            let jobs = crate::cron::CRON_MANAGER.list_jobs().await;
            let response = ServerMessage::CronJobsList { jobs };
            send_message(&state.message_tx, response).await?;
        }

        WebSocketMessage::CreateCronJob { job } => {
            match crate::cron::CRON_MANAGER.create_job(job).await {
                Ok(created_job) => {
                    let response = ServerMessage::CronJobCreated { job: created_job };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to create cron job: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::UpdateCronJob { id, job } => {
            match crate::cron::CRON_MANAGER.update_job(id, job).await {
                Ok(updated_job) => {
                    let response = ServerMessage::CronJobUpdated { job: updated_job };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to update cron job: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::DeleteCronJob { id } => {
            match crate::cron::CRON_MANAGER.delete_job(&id).await {
                Ok(_) => {
                    let response = ServerMessage::CronJobDeleted { id };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to delete cron job: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::ToggleCronJob { id, enabled } => {
            match crate::cron::CRON_MANAGER.toggle_job(&id, enabled).await {
                Ok(toggled_job) => {
                    let response = ServerMessage::CronJobUpdated { job: toggled_job };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to toggle cron job: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::TestCronCommand { command } => {
            match crate::cron::CRON_MANAGER.test_command(&command).await {
                Ok(output) => {
                    let response = ServerMessage::CronCommandOutput {
                        output,
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::CronCommandOutput {
                        output: String::new(),
                        error: Some(format!("Failed to test command: {}", e)),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        // Dotfile management
        WebSocketMessage::ListDotfiles => {
            match crate::dotfiles::DOTFILES_MANAGER.list_dotfiles().await {
                Ok(files) => {
                    let response = ServerMessage::DotfilesList { files };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to list dotfiles: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::ReadDotfile { path } => {
            match crate::dotfiles::DOTFILES_MANAGER.read_dotfile(&path).await {
                Ok(content) => {
                    let response = ServerMessage::DotfileContent {
                        path,
                        content,
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::DotfileContent {
                        path,
                        content: String::new(),
                        error: Some(format!("{}", e)),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::WriteDotfile { path, content } => match crate::dotfiles::DOTFILES_MANAGER
            .write_dotfile(&path, &content)
            .await
        {
            Ok(_) => {
                let response = ServerMessage::DotfileWritten {
                    path,
                    success: true,
                    error: None,
                };
                send_message(&state.message_tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::DotfileWritten {
                    path,
                    success: false,
                    error: Some(format!("{}", e)),
                };
                send_message(&state.message_tx, response).await?;
            }
        },

        WebSocketMessage::GetDotfileHistory { path } => {
            match crate::dotfiles::DOTFILES_MANAGER
                .get_file_history(&path)
                .await
            {
                Ok(versions) => {
                    let response = ServerMessage::DotfileHistory { path, versions };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to get dotfile history: {}", e),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::RestoreDotfileVersion { path, timestamp } => {
            match crate::dotfiles::DOTFILES_MANAGER
                .restore_version(&path, timestamp)
                .await
            {
                Ok(_) => {
                    let response = ServerMessage::DotfileRestored {
                        path,
                        success: true,
                        error: None,
                    };
                    send_message(&state.message_tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::DotfileRestored {
                        path,
                        success: false,
                        error: Some(format!("{}", e)),
                    };
                    send_message(&state.message_tx, response).await?;
                }
            }
        }

        WebSocketMessage::GetDotfileTemplates => {
            let templates = crate::dotfiles::DOTFILES_MANAGER.get_templates();
            let response = ServerMessage::DotfileTemplates { templates };
            send_message(&state.message_tx, response).await?;
        }

        // Chat log watching
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
                                        crate::websocket::merge_history_messages(
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

                let result = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "-l", text])
                    .output()
                    .await;

                if result.is_ok() {
                    let _ = tokio::process::Command::new("tmux")
                        .args(&["send-keys", "-t", &target, "C-m"])
                        .output()
                        .await;
                    info!("Sent file prompt to tmux: {:?}", text);
                } else {
                    error!("Failed to send file prompt to tmux session {}", target);
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

            let result = tokio::process::Command::new("tmux")
                .args(&["send-keys", "-t", &target, "-l", text])
                .output()
                .await;

            if result.is_ok() {
                let _ = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "C-m"])
                    .output()
                    .await;
                info!("Sent webhook message to tmux: {:?}", text);
            } else {
                error!("Failed to send webhook message to tmux session {}", target);
            }
        }
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
                                                    
                                                    // Persist the assistant message
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "assistant".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::Text { text: text.clone() }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(
                                                        &session_key,
                                                        0,
                                                        "acp",
                                                        &chat_message,
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
                        let response = ServerMessage::AcpSessionCreated {
                            session_id: result.session_id,
                            current_model_id: result.models.as_ref().map(|m| m.current_model_id.clone()),
                            available_models: None,
                            current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
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
            
            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.resume_session(&session_id, &cwd).await {
                    Ok(result) => {
                        info!("ACP session resumed: {:?}", result.session_id);
                        let response = ServerMessage::AcpSessionCreated {
                            session_id: result.session_id,
                            current_model_id: result.models.as_ref().map(|m| m.current_model_id.clone()),
                            available_models: None,
                            current_mode_id: result.modes.as_ref().map(|m| m.current_mode_id.clone()),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        error!("Failed to resume ACP session: {}", e);
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
            
            // Auto-initialize ACP client if not initialized
            let list_result = {
                let mut acp_guard = app_state.acp_client.write().await;
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
                                                    
                                                    // Persist the assistant message
                                                    let session_key = format!("acp_{}", session_id);
                                                    let chat_message = crate::chat_log::ChatMessage {
                                                        role: "assistant".to_string(),
                                                        timestamp: Some(chrono::Utc::now()),
                                                        blocks: vec![crate::chat_log::ContentBlock::Text { text: text.clone() }],
                                                    };
                                                    if let Err(e) = app_state_clone.chat_event_store.append_message(
                                                        &session_key,
                                                        0,
                                                        "acp",
                                                        &chat_message,
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
                    info!("ACP sessions: {:?}", result.sessions);
                    let response = ServerMessage::AcpSessionsListed {
                        sessions: result.sessions,
                    };
                    send_message(&state.message_tx, response).await?;
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
            info!("ACP send prompt to {}: {}", session_id, message);
            
            // Persist user message first
            let session_key = format!("acp_{}", session_id);
            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text { text: message.clone() }],
            };
            if let Err(e) = app_state.chat_event_store.append_message(
                &session_key,
                0,
                "acp",
                &chat_message,
            ) {
                tracing::warn!("Failed to persist user message: {}", e);
            }
            
            let acp_guard = app_state.acp_client.read().await;
            if let Some(client) = acp_guard.as_ref() {
                match client.send_prompt(&session_id, &message).await {
                    Ok(_result) => {
                        let response = ServerMessage::AcpPromptSent {
                            session_id: session_id.clone(),
                        };
                        send_message(&state.message_tx, response).await?;
                    }
                    Err(e) => {
                        error!("Failed to send prompt: {}", e);
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
        WebSocketMessage::AcpResumeSession { session_id, cwd } => {
            info!("ACP resume session: {} in {}", session_id, cwd);
            let response = ServerMessage::AcpError {
                message: "ACP not yet implemented".to_string(),
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpForkSession { session_id, cwd } => {
            info!("ACP fork session: {} in {}", session_id, cwd);
            let response = ServerMessage::AcpError {
                message: "ACP not yet implemented".to_string(),
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpListSessions => {
            info!("ACP list sessions");
            let response = ServerMessage::AcpError {
                message: "ACP not yet implemented".to_string(),
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpSendPrompt { session_id, message } => {
            info!("ACP send prompt to {}: {}", session_id, message);
            let response = ServerMessage::AcpError {
                message: "ACP not yet implemented".to_string(),
            };
            send_message(&state.message_tx, response).await?;
        }
        WebSocketMessage::AcpCancelPrompt { session_id } => {
            info!("ACP cancel prompt: {}", session_id);
        }
        WebSocketMessage::AcpSetModel { session_id, model_id } => {
            info!("ACP set model: {} -> {}", session_id, model_id);
        }
        WebSocketMessage::AcpSetMode { session_id, mode_id } => {
            info!("ACP set mode: {} -> {}", session_id, mode_id);
        }
        WebSocketMessage::AcpRespondPermission { request_id, option_id } => {
            info!("ACP permission response: {} -> {}", request_id, option_id);
        }
        WebSocketMessage::AcpLoadHistory { session_id, offset, limit } => {
            info!("ACP load history: {} offset={:?} limit={:?}", session_id, offset, limit);
            
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
    }

    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum HistoryMessageSource {
    Tool,
    Persisted,
}

#[derive(Clone, Debug)]
struct HistoryMessageEntry {
    message: ChatMessage,
    source: HistoryMessageSource,
    sequence: usize,
}

fn merge_history_messages(
    tool_messages: Vec<ChatMessage>,
    persisted_messages: Vec<ChatMessage>,
) -> Vec<ChatMessage> {
    if persisted_messages.is_empty() {
        return tool_messages;
    }
    if tool_messages.is_empty() {
        return persisted_messages;
    }

    let mut entries = Vec::with_capacity(tool_messages.len() + persisted_messages.len());
    entries.extend(
        tool_messages
            .into_iter()
            .enumerate()
            .map(|(sequence, message)| HistoryMessageEntry {
                message,
                source: HistoryMessageSource::Tool,
                sequence,
            }),
    );
    entries.extend(
        persisted_messages
            .into_iter()
            .enumerate()
            .map(|(sequence, message)| HistoryMessageEntry {
                message,
                source: HistoryMessageSource::Persisted,
                sequence,
            }),
    );

    entries.sort_by(|left, right| {
        if let (Some(left_ts), Some(right_ts)) = (
            left.message.timestamp.as_ref(),
            right.message.timestamp.as_ref(),
        ) {
            let ts_cmp = left_ts.cmp(right_ts);
            if ts_cmp != Ordering::Equal {
                return ts_cmp;
            }
        }

        if left.source == right.source {
            return left.sequence.cmp(&right.sequence);
        }

        match (left.source, right.source) {
            (HistoryMessageSource::Tool, HistoryMessageSource::Persisted) => Ordering::Less,
            (HistoryMessageSource::Persisted, HistoryMessageSource::Tool) => Ordering::Greater,
            (HistoryMessageSource::Tool, HistoryMessageSource::Tool)
            | (HistoryMessageSource::Persisted, HistoryMessageSource::Persisted) => Ordering::Equal,
        }
    });

    entries.into_iter().map(|entry| entry.message).collect()
}

async fn send_message(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    msg: ServerMessage,
) -> anyhow::Result<()> {
    if let Ok(json) = serde_json::to_string(&msg) {
        tx.send(BroadcastMessage::Text(Arc::new(json)))?;
    }
    Ok(())
}

async fn attach_to_session(
    state: &mut WsState,
    session_name: &str,
    cols: u16,
    rows: u16,
) -> anyhow::Result<()> {
    let tx = &state.message_tx;
    // Update current session
    {
        let mut current = state.current_session.lock().await;
        *current = Some(session_name.to_string());
    }
    // History bootstrap: use an AtomicBool so the blocking reader thread can
    // see when the async bootstrap task is finished without a Mutex.
    use std::sync::atomic::{AtomicBool, Ordering};
    let bootstrap_done = Arc::new(AtomicBool::new(false));
    let bootstrap_done_reader = bootstrap_done.clone();
    // Bounded live-output queue (256 msgs) — reader enqueues during bootstrap.
    const QUEUE_CAP: usize = 256;
    let (live_queue_tx, live_queue_rx) = mpsc::channel::<String>(QUEUE_CAP);
    let live_queue_tx_clone = live_queue_tx.clone();

    // Clean up any existing PTY session first
    let mut pty_guard = state.current_pty.lock().await;
    if let Some(old_pty) = pty_guard.take() {
        debug!(
            "Cleaning up previous PTY session for tmux: {}",
            old_pty.tmux_session
        );
        // Kill the child process
        {
            let mut child = old_pty.child.lock().await;
            let _ = child.kill();
            let _ = child.wait();
        }
        // Abort the reader task
        old_pty.reader_task.abort();
        let _ = old_pty.reader_task.await;
    }

    // Small delay to ensure cleanup is complete
    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    // Create new PTY session
    debug!("Creating new PTY session for: {}", session_name);

    let pty_system = native_pty_system();
    let pair = pty_system.openpty(PtySize {
        rows,
        cols,
        pixel_width: 0,
        pixel_height: 0,
    })?;

    let mut cmd = CommandBuilder::new("tmux");
    cmd.args(&["attach-session", "-t", session_name]);
    cmd.env("TERM", "xterm");
    cmd.env("COLORTERM", "truecolor");

    // Clear SSH-related environment variables that might confuse starship
    cmd.env_remove("SSH_CLIENT");
    cmd.env_remove("SSH_CONNECTION");
    cmd.env_remove("SSH_TTY");
    cmd.env_remove("SSH_AUTH_SOCK");

    // Set up proper environment for local terminal
    cmd.env("WEBMUX", "1");

    // Get reader before we move master
    let reader = pair.master.try_clone_reader()?;

    // Get writer and spawn command
    let writer = pair.master.take_writer()?;
    let writer = Arc::new(Mutex::new(writer));

    // First check if session exists, if not create it
    let check_output = tokio::process::Command::new("tmux")
        .args(&["has-session", "-t", session_name])
        .output()
        .await?;

    if !check_output.status.success() {
        // Create the session first
        info!("Session {} doesn't exist, creating it", session_name);
        tmux::create_session(session_name).await?;
    }

    let child = pair.slave.spawn_command(cmd)?;
    let child: Arc<Mutex<Box<dyn portable_pty::Child + Send>>> = Arc::new(Mutex::new(child));

    // Set up reader task — queues output during bootstrap, then direct-forwards
    let tx_clone = tx.clone();
    let client_id = state.client_id.clone();
    let reader_task = tokio::task::spawn_blocking(move || {
        let mut reader = reader;
        let mut buffer = vec![0u8; 8192];
        let mut consecutive_errors = 0;
        let mut utf8_decoder = crate::terminal_buffer::Utf8StreamDecoder::new();
        let mut pending_output = String::with_capacity(16384);
        let mut last_send = std::time::Instant::now();
        let mut bytes_since_pause = 0usize;

        loop {
            match reader.read(&mut buffer) {
                Ok(0) => {
                    info!("PTY EOF for client {}", client_id);
                    if !pending_output.is_empty() && bootstrap_done_reader.load(Ordering::Relaxed) {
                        let output = ServerMessage::Output {
                            data: pending_output,
                        };
                        if let Ok(json) = serde_json::to_string(&output) {
                            let _ = tx_clone.send(BroadcastMessage::Text(Arc::new(json)));
                        }
                    }
                    break;
                }
                Ok(n) => {
                    consecutive_errors = 0;
                    let (text, _) = utf8_decoder.decode_chunk(&buffer[..n]);
                    if !text.is_empty() {
                        pending_output.push_str(&text);
                        bytes_since_pause += text.len();

                        let should_send = pending_output.len() > 1024
                            || last_send.elapsed() > std::time::Duration::from_millis(10)
                            || pending_output.contains('\n');

                        if should_send && !pending_output.is_empty() {
                            if bootstrap_done_reader.load(Ordering::Relaxed) {
                                // Direct path: bootstrap finished
                                let output = ServerMessage::Output {
                                    data: pending_output.clone(),
                                };
                                if let Ok(json) = serde_json::to_string(&output) {
                                    if tx_clone
                                        .send(BroadcastMessage::Text(Arc::new(json)))
                                        .is_err()
                                    {
                                        error!(
                                            "Client {} disconnected, stopping PTY reader",
                                            client_id
                                        );
                                        break;
                                    }
                                }
                            } else {
                                // Bootstrap path: queue or overflow to direct
                                match live_queue_tx_clone.try_send(pending_output.clone()) {
                                    Ok(_) => {}
                                    Err(_) => {
                                        tracing::warn!("[history-bootstrap] queue overflow, switching to direct forward for client {}", client_id);
                                        bootstrap_done_reader.store(true, Ordering::Relaxed);
                                        let output = ServerMessage::Output {
                                            data: pending_output.clone(),
                                        };
                                        if let Ok(json) = serde_json::to_string(&output) {
                                            if tx_clone
                                                .send(BroadcastMessage::Text(Arc::new(json)))
                                                .is_err()
                                            {
                                                error!(
                                                    "Client {} disconnected, stopping PTY reader",
                                                    client_id
                                                );
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            pending_output.clear();
                            last_send = std::time::Instant::now();

                            if bytes_since_pause > 65536 {
                                std::thread::sleep(std::time::Duration::from_millis(5));
                                bytes_since_pause = 0;
                            }
                        }
                    }
                }
                Err(e) => {
                    consecutive_errors += 1;
                    if consecutive_errors > 5 {
                        error!(
                            "Too many consecutive PTY read errors for client {}: {}",
                            client_id, e
                        );
                        break;
                    }
                    error!(
                        "PTY read error for client {} (attempt {}): {}",
                        client_id, consecutive_errors, e
                    );
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
            }
        }

        let disconnected = ServerMessage::Disconnected;
        if let Ok(json) = serde_json::to_string(&disconnected) {
            let _ = tx_clone.send(BroadcastMessage::Text(Arc::new(json)));
        }
    });

    let pty_session = PtySession {
        writer: writer.clone(),
        master: Arc::new(Mutex::new(pair.master)),
        reader_task,
        child,
        tmux_session: session_name.to_string(),
    };

    *pty_guard = Some(pty_session);
    drop(pty_guard);

    // Send attached confirmation first
    let response = ServerMessage::Attached {
        session_name: session_name.to_string(),
    };
    send_message(tx, response).await?;

    // ── Async bootstrap task ─────────────────────────────────────────────────
    // Captures tmux scrollback history, streams it in chunks, then flushes
    // queued live output so nothing is lost or reordered.
    let tx_bootstrap = tx.clone();
    let session_owned = session_name.to_string();
    let window_index_val = *state.current_window.lock().await;
    let mut live_queue_rx = live_queue_rx;

    tokio::spawn(async move {
        let window = window_index_val.unwrap_or(0);
        let bootstrap_start = std::time::Instant::now();

        match tmux::capture_history_above_viewport(&session_owned, window).await {
            Ok(history_text) if !history_text.is_empty() => {
                let total_lines = history_text.lines().count() as i64;
                const CHUNK_SIZE: usize = 24 * 1024;
                let chunks = tmux::chunk_terminal_stream(&history_text, CHUNK_SIZE);
                let total_chunks = chunks.len();

                info!(
                    "[history-bootstrap] captured {} lines in {:.1}ms, {} chunks, {} bytes",
                    total_lines,
                    bootstrap_start.elapsed().as_secs_f64() * 1000.0,
                    total_chunks,
                    history_text.len()
                );

                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryStart {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines,
                        chunk_size: CHUNK_SIZE,
                        generated_at: chrono::Utc::now(),
                    },
                )
                .await;

                for (seq, chunk) in chunks.iter().enumerate() {
                    let line_count = chunk.lines().count();
                    let is_last = seq + 1 == total_chunks;
                    let _ = send_message(
                        &tx_bootstrap,
                        ServerMessage::TerminalHistoryChunk {
                            session_name: session_owned.clone(),
                            window_index: window,
                            seq,
                            data: chunk.clone(),
                            line_count,
                            is_last,
                        },
                    )
                    .await;
                }

                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines,
                        total_chunks,
                    },
                )
                .await;
            }
            Ok(_) => {
                info!(
                    "[history-bootstrap] no scrollback history for {}:{}",
                    session_owned, window
                );
                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines: 0,
                        total_chunks: 0,
                    },
                )
                .await;
            }
            Err(e) => {
                tracing::warn!(
                    "[history-bootstrap] failed for {}:{} — {}",
                    session_owned,
                    window,
                    e
                );
                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines: 0,
                        total_chunks: 0,
                    },
                )
                .await;
            }
        }

        // Signal reader to switch to direct mode
        bootstrap_done.store(true, Ordering::Relaxed);
        // Drop sender so the receiver loop terminates
        drop(live_queue_tx);

        // Flush live output that arrived during bootstrap
        let mut flushed = 0usize;
        while let Some(data) = live_queue_rx.recv().await {
            flushed += data.len();
            let output = ServerMessage::Output { data };
            if let Ok(json) = serde_json::to_string(&output) {
                if tx_bootstrap
                    .send(BroadcastMessage::Text(Arc::new(json)))
                    .is_err()
                {
                    break;
                }
            }
        }

        if flushed > 0 {
            info!(
                "[history-bootstrap] flushed {} bytes of queued live output",
                flushed
            );
        }
        info!(
            "[history-bootstrap] complete in {:.1}ms",
            bootstrap_start.elapsed().as_secs_f64() * 1000.0
        );
    });

    Ok(())
}

async fn cleanup_session(state: &WsState) {
    info!("Cleaning up session for client: {}", state.client_id);

    // Clean up PTY session
    let mut pty_guard = state.current_pty.lock().await;
    if let Some(pty) = pty_guard.take() {
        info!("Cleaning up PTY for tmux session: {}", pty.tmux_session);

        // Kill the child process first
        {
            let mut child = pty.child.lock().await;
            let _ = child.kill();
            let _ = child.wait();
        }

        // Abort the reader task
        pty.reader_task.abort();

        // Writer and master will be dropped automatically
    }
    drop(pty_guard);

    // Clean up chat log watcher
    {
        let mut handle_guard = state.chat_log_handle.lock().await;
        if let Some(handle) = handle_guard.take() {
            handle.abort();
        }
    }

    // Clean up audio streaming
    if let Some(ref audio_tx) = state.audio_tx {
        if let Err(e) = audio::stop_streaming_for_client(audio_tx).await {
            error!("Failed to stop audio streaming: {}", e);
        }
    }
}
