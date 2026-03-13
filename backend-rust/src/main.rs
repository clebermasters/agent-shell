use anyhow::Result;
use axum::{
    extract::State,
    middleware,
    routing::get,
    Json, Router,
};
use axum_server::tls_rustls::RustlsConfig;
use clap::Parser;
use serde::Deserialize;
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::signal;
use tower_http::{
    cors::{Any, CorsLayer},
    services::{ServeDir, ServeFile},
};
use tracing::{error, info};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Deserialize)]
struct TmuxInput {
    session: String,
    text: String,
    window: Option<u32>,
}

mod audio;
mod auth;
mod chat_clear_store;
mod chat_event_store;
mod chat_file_storage;
mod chat_log;
mod cron;
mod dotfiles;
mod monitor;
mod terminal_buffer;
mod tmux;
mod types;
mod websocket;
mod acp;

// Global flag for audio logging
pub static ENABLE_AUDIO_LOGS: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

#[derive(Parser, Debug)]
#[command(name = "agentshell-backend")]
#[command(about = "AgentShell backend server", long_about = None)]
struct Args {
    /// Enable audio streaming debug logs
    #[arg(long)]
    audio: bool,
}

use crate::types::ServerMessage;
use tokio::sync::mpsc;

#[derive(Clone)]
pub struct AppState {
    pub enable_audio_logs: bool,
    pub broadcast_tx: mpsc::UnboundedSender<ServerMessage>,
    pub client_manager: Arc<websocket::ClientManager>,
    pub chat_file_storage: Arc<chat_file_storage::ChatFileStorage>,
    pub chat_event_store: Arc<chat_event_store::ChatEventStore>,
    pub chat_clear_store: Arc<chat_clear_store::ChatClearStore>,
    pub acp_client: Arc<tokio::sync::RwLock<Option<acp::AcpClient>>>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    // Initialize tracing
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "agentshell_backend=debug,tower_http=info".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Set the global audio logging flag
    ENABLE_AUDIO_LOGS.store(args.audio, std::sync::atomic::Ordering::Relaxed);

    if args.audio {
        info!("Audio debug logging enabled");
    }

    // Create broadcast channel for tmux updates
    let (broadcast_tx, mut broadcast_rx) = mpsc::unbounded_channel::<ServerMessage>();

    // Create client manager
    let client_manager = Arc::new(websocket::ClientManager::new());
    let client_manager_clone = client_manager.clone();

    // Spawn task to forward broadcasts to all clients
    tokio::spawn(async move {
        while let Some(msg) = broadcast_rx.recv().await {
            client_manager_clone.broadcast(msg).await;
        }
    });

    let base_dir = std::env::current_dir().unwrap_or_else(|_| std::path::PathBuf::from("."));

    let app_state = AppState {
        enable_audio_logs: args.audio,
        broadcast_tx: broadcast_tx.clone(),
        client_manager,
        chat_file_storage: Arc::new(chat_file_storage::ChatFileStorage::new(base_dir.clone())),
        chat_event_store: Arc::new(chat_event_store::ChatEventStore::new(base_dir.clone())?),
        chat_clear_store: Arc::new(chat_clear_store::ChatClearStore::new(&base_dir)),
        acp_client: Arc::new(tokio::sync::RwLock::new(None)),
    };

    // Initialize CRON manager
    if let Err(e) = crate::cron::CRON_MANAGER.initialize().await {
        error!("Failed to initialize CRON manager: {}", e);
    }

    // Start tmux monitor
    let monitor = monitor::TmuxMonitor::new(broadcast_tx);
    tokio::spawn(async move {
        monitor.start().await;
    });

    // Serve static files from dist directory
    let serve_dir =
        ServeDir::new("../dist").not_found_service(ServeFile::new("../dist/index.html"));

    // Log AUTH_TOKEN status at startup
    if std::env::var("AUTH_TOKEN").ok().filter(|t| !t.is_empty()).is_some() {
        info!("AUTH_TOKEN is set — all API/WebSocket requests require valid token");
    } else {
        info!("WARNING: AUTH_TOKEN not set — backend is open to all connections");
    }

    // Build the router — protected routes require auth token
    let protected = Router::new()
        // API: Get connected clients count
        .route(
            "/api/clients",
            get(|State(s): State<Arc<AppState>>| async move {
                let count = s.client_manager.client_count().await;
                format!("{{\"clients\":{}}}", count)
            }),
        )
        // API: Get chat file by ID
        .route(
            "/api/chat/files/:id",
            get({
                let storage = app_state.chat_file_storage.clone();
                move |axum::extract::Path(id): axum::extract::Path<String>| async move {
                    if let Some(path) = storage.get_path(&id) {
                        if let Ok(data) = std::fs::read(&path) {
                            let mime = storage
                                .get_mime_type(&id)
                                .unwrap_or_else(|| "application/octet-stream".to_string());
                            let headers = [
                                (axum::http::header::CONTENT_TYPE, mime.to_string()),
                                (
                                    axum::http::header::CACHE_CONTROL,
                                    "public, max-age=3600".to_string(),
                                ),
                            ];
                            return Ok((headers, data));
                        }
                    }
                    Err(axum::http::StatusCode::NOT_FOUND)
                }
            }),
        )
        // API: Send input directly to tmux session
        .route(
            "/api/tmux/input",
            axum::routing::post(|Json(payload): Json<TmuxInput>| async move {
                let session = payload.session;
                let window = payload.window.unwrap_or(0);
                let text = payload.text;

                info!(
                    "Direct tmux input: session={}, window={}, text={}",
                    session, window, text
                );

                // Two separate tmux calls: text then Enter.
                let target = format!("{}:{}", session, window);
                let clean_text = text.trim_end_matches('\n');
                let _ = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "-l", clean_text])
                    .output()
                    .await;
                let result = tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", &target, "Enter"])
                    .output()
                    .await;

                match result {
                    Ok(output) if output.status.success() => {
                        format!("{{\"success\":true}}")
                    }
                    Ok(_) => {
                        format!("{{\"success\":false,\"error\":\"tmux command failed\"}}")
                    }
                    Err(e) => {
                        format!("{{\"success\":false,\"error\":\"{}\"}}", e)
                    }
                }
            }),
        )
        // WebSocket endpoint
        .route("/ws", get(websocket::ws_handler))
        // Apply auth middleware to all protected routes
        .layer(middleware::from_fn(auth::auth_middleware));

    let app = Router::new()
        .merge(protected)
        // Serve static files (Vue app) — no auth required
        .fallback_service(serve_dir)
        // Add CORS
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .with_state(Arc::new(app_state));

    // Dev branch uses different ports
    let http_port = 4010;
    let https_port = 4443;

    // Start HTTP server
    let http_addr = SocketAddr::from(([0, 0, 0, 0], http_port));
    info!("AgentShell HTTP server running on {}", http_addr);
    info!("  Local:    http://localhost:{}", http_port);
    info!("  Network:  http://0.0.0.0:{}", http_port);

    // Check if HTTPS certificates exist
    let cert_path = PathBuf::from("../certs/cert.pem");
    let key_path = PathBuf::from("../certs/key.pem");

    if cert_path.exists() && key_path.exists() {
        // Start HTTPS server in a separate task
        let https_app = app.clone();
        tokio::spawn(async move {
            let https_addr = SocketAddr::from(([0, 0, 0, 0], https_port));
            let config = match RustlsConfig::from_pem_file(&cert_path, &key_path).await {
                Ok(config) => config,
                Err(e) => {
                    error!("Failed to load TLS certificates: {}", e);
                    return;
                }
            };

            info!("AgentShell HTTPS server running on {}", https_addr);
            info!("  Local:    https://localhost:{}", https_port);
            info!("  Network:  https://0.0.0.0:{}", https_port);
            info!(
                "  Tailscale: Use your Tailscale IP with port {}",
                https_port
            );
            info!("  Note: You may need to accept the self-signed certificate");

            if let Err(e) = axum_server::bind_rustls(https_addr, config)
                .serve(https_app.into_make_service())
                .await
            {
                error!("HTTPS server error: {}", e);
            }
        });
    } else {
        info!("Warning: Could not load SSL certificates from certs/");
        info!("HTTPS server will not be available");
    }

    // Run HTTP server with graceful shutdown
    let socket = tokio::net::TcpSocket::new_v4()?;
    socket.set_reuseaddr(true)?;
    socket.bind(http_addr)?;
    let listener = socket.listen(1024)?;
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {
            info!("Received Ctrl+C, shutting down gracefully...");
        },
        _ = terminate => {
            info!("Received terminate signal, shutting down gracefully...");
        },
    }
}
