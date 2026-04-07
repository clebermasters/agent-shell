use tokio::sync::mpsc;
use tracing::{error, info};

use super::types::{send_message, BroadcastMessage, WsState};
use crate::{audio, types::*};

pub(crate) async fn handle_ping(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    send_message(tx, ServerMessage::Pong).await
}

pub(crate) async fn handle_get_stats(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    let stats = tokio::task::spawn_blocking(|| {
        let mut sys = sysinfo::System::new_with_specifics(
            sysinfo::RefreshKind::new()
                .with_memory(sysinfo::MemoryRefreshKind::everything())
                .with_cpu(sysinfo::CpuRefreshKind::everything()),
        );
        sys.refresh_memory();
        sys.refresh_cpu_usage();
        let load_avg = sysinfo::System::load_average();

        let disks = sysinfo::Disks::new_with_refreshed_list();
        let (disk_total, disk_free) = disks
            .iter()
            .find(|d| d.mount_point() == std::path::Path::new("/"))
            .map(|d| (d.total_space(), d.available_space()))
            .unwrap_or((0, 0));
        let disk_used = disk_total.saturating_sub(disk_free);

        SystemStats {
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
            disk: DiskInfo {
                total: disk_total,
                used: disk_used,
                free: disk_free,
                percent: if disk_total > 0 {
                    format!("{:.1}", disk_used as f64 / disk_total as f64 * 100.0)
                } else {
                    "0.0".to_string()
                },
            },
            uptime: sysinfo::System::uptime(),
            hostname: sysinfo::System::host_name().unwrap_or_default(),
            platform: std::env::consts::OS.to_string(),
            arch: std::env::consts::ARCH.to_string(),
        }
    })
    .await?;
    send_message(tx, ServerMessage::Stats { stats }).await
}

/// Cached last successful usage response so we can serve stale data on 429.
static CACHED_USAGE: std::sync::OnceLock<tokio::sync::Mutex<Option<String>>> =
    std::sync::OnceLock::new();

fn get_usage_cache() -> &'static tokio::sync::Mutex<Option<String>> {
    CACHED_USAGE.get_or_init(|| tokio::sync::Mutex::new(None))
}

pub(crate) async fn handle_get_claude_usage(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    let send_cached = |tx: &mpsc::UnboundedSender<BroadcastMessage>| {
        let cache = get_usage_cache().try_lock().ok();
        if let Some(ref guard) = cache {
            if let Some(ref json) = **guard {
                let _ = tx.send(BroadcastMessage::Text(std::sync::Arc::new(json.clone())));
                return true;
            }
        }
        false
    };

    let usage_error = |tx: &mpsc::UnboundedSender<BroadcastMessage>, reason: String| {
        let msg = ServerMessage::ClaudeUsage {
            five_hour: None,
            seven_day: None,
            seven_day_sonnet: None,
            error: Some(reason),
        };
        let json = serde_json::to_string(&msg).unwrap_or_default();
        let _ = tx.send(BroadcastMessage::Text(std::sync::Arc::new(json)));
    };

    let home = match dirs::home_dir() {
        Some(h) => h,
        None => {
            usage_error(tx, "no home directory".into());
            return Ok(());
        }
    };
    let creds_path = home.join(".claude/.credentials.json");

    let creds_data = match tokio::fs::read_to_string(&creds_path).await {
        Ok(d) => d,
        Err(_) => {
            usage_error(tx, "not logged in".into());
            return Ok(());
        }
    };

    let creds: serde_json::Value = match serde_json::from_str(&creds_data) {
        Ok(v) => v,
        Err(_) => {
            usage_error(tx, "invalid credentials".into());
            return Ok(());
        }
    };

    let token = match creds["claudeAiOauth"]["accessToken"].as_str() {
        Some(t) => t.to_string(),
        None => {
            usage_error(tx, "no OAuth token".into());
            return Ok(());
        }
    };

    let client = reqwest::Client::new();
    let resp = match client
        .get("https://api.anthropic.com/api/oauth/usage")
        .header("Authorization", format!("Bearer {}", token))
        .header("Content-Type", "application/json")
        .header("anthropic-beta", "oauth-2025-04-20")
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            error!("Claude usage API request failed: {}", e);
            // Serve cached data if available, otherwise error
            if !send_cached(tx) {
                usage_error(tx, "API unreachable".into());
            }
            return Ok(());
        }
    };

    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        if status == 429 {
            // Rate limited — serve cached data silently
            if send_cached(tx) {
                return Ok(());
            }
        }
        error!("Claude usage API returned {}", status);
        usage_error(tx, format!("API error {}", status));
        return Ok(());
    }

    let body: serde_json::Value = match resp.json().await {
        Ok(v) => v,
        Err(e) => {
            error!("Failed to parse Claude usage response: {}", e);
            usage_error(tx, "bad response".into());
            return Ok(());
        }
    };

    let parse_bucket = |key: &str| -> Option<UsageBucket> {
        let obj = body.get(key)?.as_object()?;
        Some(UsageBucket {
            utilization: obj.get("utilization")?.as_f64()?,
            resets_at: obj.get("resets_at")?.as_str()?.to_string(),
        })
    };

    let msg = ServerMessage::ClaudeUsage {
        five_hour: parse_bucket("five_hour"),
        seven_day: parse_bucket("seven_day"),
        seven_day_sonnet: parse_bucket("seven_day_sonnet"),
        error: None,
    };

    // Cache the successful response for 429 fallback
    if let Ok(json) = serde_json::to_string(&msg) {
        if let Ok(mut cache) = get_usage_cache().try_lock() {
            *cache = Some(json);
        }
    }

    send_message(tx, msg).await
}

pub(crate) async fn handle_audio_control(
    state: &mut WsState,
    action: AudioAction,
) -> anyhow::Result<()> {
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
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;
    use crate::websocket::types::BroadcastMessage;

    fn make_tx() -> (
        mpsc::UnboundedSender<BroadcastMessage>,
        mpsc::UnboundedReceiver<BroadcastMessage>,
    ) {
        mpsc::unbounded_channel()
    }

    #[tokio::test]
    async fn test_handle_ping_sends_pong() {
        let (tx, mut rx) = make_tx();
        let result = handle_ping(&tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("pong"));
    }

    #[tokio::test]
    async fn test_handle_get_stats_sends_response() {
        let (tx, mut rx) = make_tx();
        let result = handle_get_stats(&tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("cpu") || json.contains("stats"));
    }

    #[tokio::test]
    async fn test_handle_audio_control_start() {
        use std::sync::Arc;
        use tokio::sync::Mutex;
        use crate::websocket::{
            client_manager::ClientManager,
            types::WsState,
        };
        use crate::{
            chat_clear_store::ChatClearStore,
            chat_event_store::ChatEventStore,
            chat_file_storage::ChatFileStorage,
        };

        let dir = tempfile::TempDir::new().unwrap();
        let (tx, _rx) = make_tx();
        let chat_event_store = Arc::new(ChatEventStore::new(dir.path().to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.path().to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.path().to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let mut ws_state = WsState {
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
        };
        // AudioAction::Start will attempt to start streaming — may fail without audio provider
        let result = handle_audio_control(&mut ws_state, AudioAction::Start).await;
        // Result depends on audio availability — just verify no panic
        let _ = result;
    }

    #[tokio::test]
    async fn test_handle_audio_control_stop_no_tx() {
        use std::sync::Arc;
        use tokio::sync::Mutex;
        use crate::websocket::{
            client_manager::ClientManager,
            types::WsState,
        };
        use crate::{
            chat_clear_store::ChatClearStore,
            chat_event_store::ChatEventStore,
            chat_file_storage::ChatFileStorage,
        };

        let dir = tempfile::TempDir::new().unwrap();
        let (tx, _rx) = make_tx();
        let chat_event_store = Arc::new(ChatEventStore::new(dir.path().to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.path().to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.path().to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let mut ws_state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None, // No audio_tx set
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            client_manager,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
        };
        // Stop with audio_tx=None should be a no-op
        let result = handle_audio_control(&mut ws_state, AudioAction::Stop).await;
        assert!(result.is_ok());
        assert!(ws_state.audio_tx.is_none());
    }
}
