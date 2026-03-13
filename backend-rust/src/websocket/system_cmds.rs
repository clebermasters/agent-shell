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
