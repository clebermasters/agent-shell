use anyhow::Result;
use axum::{
    extract::State,
    middleware,
    routing::{delete, get, post, put},
    Json, Router,
};
use axum_server::tls_rustls::RustlsConfig;
use clap::Parser;
use serde::Deserialize;
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::signal;
use tower_http::{
    cors::CorsLayer,
    services::{ServeDir, ServeFile},
};
use tracing::{error, info, warn};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Deserialize)]
struct TmuxInput {
    session: String,
    text: String,
    window: Option<u32>,
}

mod acp;
mod audio;
mod auth;
mod chat_clear_store;
mod chat_event_store;
mod chat_file_storage;
mod chat_log;
mod codex_app;
mod cron;
mod cron_handler;
mod dotfiles;
mod favorite_store;
mod monitor;
mod notification;
mod notification_handler;
mod notification_store;
mod tag_store;
mod terminal_buffer;
mod tmux;
mod types;
mod websocket;

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
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub struct AppState {
    pub enable_audio_logs: bool,
    pub broadcast_tx: mpsc::Sender<ServerMessage>,
    pub client_manager: Arc<websocket::ClientManager>,
    pub chat_file_storage: Arc<chat_file_storage::ChatFileStorage>,
    pub chat_event_store: Arc<chat_event_store::ChatEventStore>,
    pub chat_clear_store: Arc<chat_clear_store::ChatClearStore>,
    pub acp_client: Arc<tokio::sync::RwLock<Option<acp::AcpClient>>>,
    pub codex_app_client: Arc<tokio::sync::RwLock<Option<codex_app::CodexAppClient>>>,
    pub notification_store: Arc<notification_store::NotificationStore>,
    pub favorite_store: Arc<favorite_store::FavoriteStore>,
    pub tag_store: Arc<tag_store::TagStore>,
    pub shutdown_token: CancellationToken,
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
    let (broadcast_tx, mut broadcast_rx) = mpsc::channel::<ServerMessage>(256);

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
        notification_store: Arc::new(notification_store::NotificationStore::new(
            base_dir.clone(),
        )?),
        favorite_store: Arc::new(favorite_store::FavoriteStore::new(base_dir.clone())?),
        tag_store: Arc::new(tag_store::TagStore::new(base_dir.clone())?),
        acp_client: Arc::new(tokio::sync::RwLock::new(None)),
        codex_app_client: Arc::new(tokio::sync::RwLock::new(None)),
        shutdown_token: CancellationToken::new(),
    };

    // Initialize CRON manager
    if let Err(e) = crate::cron::CRON_MANAGER.initialize().await {
        error!("Failed to initialize CRON manager: {}", e);
    }

    // Start tmux monitor
    let monitor = monitor::TmuxMonitor::new(broadcast_tx.clone());
    tokio::spawn(async move {
        monitor.start().await;
    });

    // Serve static files from dist directory
    let serve_dir =
        ServeDir::new("../dist").not_found_service(ServeFile::new("../dist/index.html"));

    // Require AUTH_TOKEN at startup — exits with a clear error if not set
    auth::require_auth_token();
    info!("AUTH_TOKEN is set — all API/WebSocket requests require valid token");

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
                        if let Ok(data) = tokio::fs::read(&path).await {
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
        // API: Notifications
        .route(
            "/api/notifications",
            axum::routing::get(notification_handler::list_notifications),
        )
        .route(
            "/api/notifications",
            axum::routing::post(notification_handler::create_notification),
        )
        .route(
            "/api/notifications/:id/read",
            axum::routing::post(notification_handler::mark_read),
        )
        .route(
            "/api/notifications/:id",
            axum::routing::delete(notification_handler::delete_notification),
        )
        .route(
            "/api/notifications",
            axum::routing::delete(notification_handler::delete_all_notifications),
        )
        .route(
            "/api/notifications/files/:id",
            get({
                let notification_store = app_state.notification_store.clone();
                move |axum::extract::Path(id): axum::extract::Path<String>| async move {
                    if let Ok(Some(file)) = notification_store.get_file(&id) {
                        if let Ok(path) = notification_store.resolve_file_path(&file.stored_path) {
                            if let Ok(data) = tokio::fs::read(path).await {
                                let headers = [
                                    (axum::http::header::CONTENT_TYPE, file.mime_type.clone()),
                                    (
                                        axum::http::header::CACHE_CONTROL,
                                        "public, max-age=3600".to_string(),
                                    ),
                                ];
                                return Ok((headers, data));
                            }
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
        // API: Cron jobs
        .route("/api/cron/jobs", get(cron_handler::list_jobs))
        .route("/api/cron/jobs", post(cron_handler::create_job))
        .route("/api/cron/jobs/:id", get(cron_handler::get_job))
        .route("/api/cron/jobs/:id", put(cron_handler::update_job))
        .route("/api/cron/jobs/:id", delete(cron_handler::delete_job))
        .route("/api/cron/jobs/:id/toggle", post(cron_handler::toggle_job))
        .route("/api/cron/jobs/:id/test", post(cron_handler::test_job))
        // WebSocket endpoint
        .route("/ws", get(websocket::ws_handler))
        // Apply auth middleware to all protected routes
        .layer(middleware::from_fn(auth::auth_middleware));

    // Clone shutdown token and broadcast_tx before moving app_state into Arc
    let shutdown_token_clone = app_state.shutdown_token.clone();
    let broadcast_tx_clone = broadcast_tx.clone();

    // Allow local smoke tests to run against a second backend instance without
    // colliding with an already-installed server on the default ports.
    let http_port = read_port_from_env("AGENTSHELL_HTTP_PORT", 4010);
    let https_port = read_port_from_env("AGENTSHELL_HTTPS_PORT", 4443);

    let app = Router::new()
        .merge(protected)
        // Serve static files (Vue app) — no auth required
        .fallback_service(serve_dir)
        // Add CORS — restrict to localhost and optional configured domain
        .layer(build_cors_layer(http_port, https_port))
        .with_state(Arc::new(app_state));

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
        .with_graceful_shutdown(shutdown_signal(shutdown_token_clone, broadcast_tx_clone))
        .await?;

    Ok(())
}

/// Build a CORS layer that only allows localhost origins plus an optional
/// domain configured via the `ALLOWED_ORIGIN` environment variable.
fn build_cors_layer(http_port: u16, https_port: u16) -> CorsLayer {
    use axum::http::{header, HeaderValue, Method};

    let mut origins: Vec<HeaderValue> = vec![
        "http://localhost".parse().unwrap(),
        format!("http://localhost:{http_port}").parse().unwrap(),
        format!("https://localhost:{https_port}").parse().unwrap(),
        format!("http://127.0.0.1:{http_port}").parse().unwrap(),
    ];

    if let Ok(domain) = std::env::var("ALLOWED_ORIGIN") {
        if !domain.is_empty() {
            if let Ok(hv) = domain.parse::<HeaderValue>() {
                origins.push(hv);
            }
        }
    }

    CorsLayer::new()
        .allow_origin(origins)
        .allow_methods([Method::GET, Method::POST, Method::OPTIONS])
        .allow_headers([
            header::CONTENT_TYPE,
            header::AUTHORIZATION,
            "X-Auth-Token".parse().unwrap(),
        ])
}

fn read_port_from_env(name: &str, default: u16) -> u16 {
    match std::env::var(name) {
        Ok(raw) if !raw.trim().is_empty() => match raw.parse::<u16>() {
            Ok(port) => port,
            Err(err) => {
                warn!(
                    "Invalid {} value {:?}: {}. Falling back to {}",
                    name, raw, err, default
                );
                default
            }
        },
        _ => default,
    }
}

async fn shutdown_signal(
    shutdown_token: CancellationToken,
    broadcast_tx: mpsc::Sender<ServerMessage>,
) {
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

    // Notify all clients that the server is shutting down
    info!("Notifying clients of graceful shutdown...");
    let _ = broadcast_tx
        .send(ServerMessage::ServerShuttingDown {
            reconnect_delay_ms: 2000,
        })
        .await;

    // Give clients time to receive the notification
    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

    // Signal all WebSocket handlers to close
    shutdown_token.cancel();
    info!("Shutdown token cancelled, closing connections...");
}
