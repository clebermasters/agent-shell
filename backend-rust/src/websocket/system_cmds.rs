use tokio::sync::mpsc;
use tracing::info;

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
}
