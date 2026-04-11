use std::process::Stdio;

use chrono::{DateTime, Utc};
use serde::Deserialize;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
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
        // Second refresh for accurate per-process CPU (sysinfo needs two snapshots)
        std::thread::sleep(std::time::Duration::from_millis(200));
        sys.refresh_processes();
        let load_avg = sysinfo::System::load_average();

        let disks = sysinfo::Disks::new_with_refreshed_list();
        let (disk_total, disk_free) = disks
            .iter()
            .find(|d| d.mount_point() == std::path::Path::new("/"))
            .map(|d| (d.total_space(), d.available_space()))
            .unwrap_or((0, 0));
        let disk_used = disk_total.saturating_sub(disk_free);

        // Collect all processes into a sortable Vec
        let mut processes: Vec<ProcessInfo> = sys
            .processes()
            .values()
            .map(|p| ProcessInfo {
                pid: p.pid().as_u32(),
                name: p.name().to_string(),
                cpu_usage: p.cpu_usage(),
                memory_bytes: p.memory(),
            })
            .collect();

        // Top 5 by CPU (descending)
        processes.sort_by(|a, b| {
            b.cpu_usage
                .partial_cmp(&a.cpu_usage)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        let top_processes_by_cpu: Vec<ProcessInfo> = processes.iter().take(5).cloned().collect();

        // Top 5 by memory (descending)
        processes.sort_by(|a, b| b.memory_bytes.cmp(&a.memory_bytes));
        let top_processes_by_memory: Vec<ProcessInfo> = processes.iter().take(5).cloned().collect();

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
            top_processes_by_cpu,
            top_processes_by_memory,
        }
    })
    .await?;
    send_message(tx, ServerMessage::Stats { stats }).await
}

/// Cached last successful Claude usage response so we can serve stale data on 429.
static CACHED_CLAUDE_USAGE: std::sync::OnceLock<tokio::sync::Mutex<Option<String>>> =
    std::sync::OnceLock::new();
/// Cached last successful Codex usage response so we can serve stale data on transient errors.
static CACHED_CODEX_USAGE: std::sync::OnceLock<tokio::sync::Mutex<Option<String>>> =
    std::sync::OnceLock::new();

fn get_claude_usage_cache() -> &'static tokio::sync::Mutex<Option<String>> {
    CACHED_CLAUDE_USAGE.get_or_init(|| tokio::sync::Mutex::new(None))
}

fn get_codex_usage_cache() -> &'static tokio::sync::Mutex<Option<String>> {
    CACHED_CODEX_USAGE.get_or_init(|| tokio::sync::Mutex::new(None))
}

pub(crate) async fn handle_get_claude_usage(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    let send_cached = |tx: &mpsc::UnboundedSender<BroadcastMessage>| {
        let cache = get_claude_usage_cache().try_lock().ok();
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
        if let Ok(mut cache) = get_claude_usage_cache().try_lock() {
            *cache = Some(json);
        }
    }

    send_message(tx, msg).await
}

pub(crate) async fn handle_get_codex_usage(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    let send_cached = |tx: &mpsc::UnboundedSender<BroadcastMessage>| {
        let cache = get_codex_usage_cache().try_lock().ok();
        if let Some(ref guard) = cache {
            if let Some(ref json) = **guard {
                let _ = tx.send(BroadcastMessage::Text(std::sync::Arc::new(json.clone())));
                return true;
            }
        }
        false
    };

    let usage_error = |tx: &mpsc::UnboundedSender<BroadcastMessage>, reason: String| {
        let msg = ServerMessage::CodexUsage {
            primary: None,
            secondary: None,
            plan_type: None,
            limit_id: None,
            limit_name: None,
            error: Some(reason),
        };
        let json = serde_json::to_string(&msg).unwrap_or_default();
        let _ = tx.send(BroadcastMessage::Text(std::sync::Arc::new(json)));
    };

    let usage = match fetch_codex_usage().await {
        Ok(usage) => usage,
        Err(err) => {
            error!("Codex usage request failed: {}", err);
            if !send_cached(tx) {
                usage_error(tx, normalize_codex_error(&err.to_string()));
            }
            return Ok(());
        }
    };

    let msg = ServerMessage::CodexUsage {
        primary: usage.primary,
        secondary: usage.secondary,
        plan_type: usage.plan_type,
        limit_id: usage.limit_id,
        limit_name: usage.limit_name,
        error: None,
    };

    if let Ok(json) = serde_json::to_string(&msg) {
        if let Ok(mut cache) = get_codex_usage_cache().try_lock() {
            *cache = Some(json);
        }
    }

    send_message(tx, msg).await
}

#[derive(Debug)]
struct CodexUsageSnapshot {
    primary: Option<ProviderUsageWindow>,
    secondary: Option<ProviderUsageWindow>,
    plan_type: Option<String>,
    limit_id: Option<String>,
    limit_name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct JsonRpcSuccess<T> {
    id: serde_json::Value,
    result: T,
}

#[derive(Debug, Deserialize)]
struct JsonRpcErrorEnvelope {
    id: serde_json::Value,
    error: JsonRpcErrorPayload,
}

#[derive(Debug, Deserialize)]
struct JsonRpcErrorPayload {
    code: i64,
    message: String,
}

#[derive(Debug, Deserialize)]
struct CodexRateLimitsResponse {
    #[serde(rename = "rateLimits")]
    rate_limits: CodexRateLimitSnapshot,
    #[serde(rename = "rateLimitsByLimitId")]
    rate_limits_by_limit_id: Option<std::collections::HashMap<String, CodexRateLimitSnapshot>>,
}

#[derive(Debug, Clone, Deserialize)]
struct CodexRateLimitSnapshot {
    #[serde(rename = "limitId")]
    limit_id: Option<String>,
    #[serde(rename = "limitName")]
    limit_name: Option<String>,
    #[serde(rename = "planType")]
    plan_type: Option<String>,
    primary: Option<CodexRateLimitWindow>,
    secondary: Option<CodexRateLimitWindow>,
}

#[derive(Debug, Clone, Deserialize)]
struct CodexRateLimitWindow {
    #[serde(rename = "usedPercent")]
    used_percent: u32,
    #[serde(rename = "resetsAt")]
    resets_at: Option<i64>,
    #[serde(rename = "windowDurationMins")]
    window_duration_mins: Option<i64>,
}

async fn fetch_codex_usage() -> anyhow::Result<CodexUsageSnapshot> {
    match fetch_codex_usage_from_app_server().await {
        Ok(usage) if usage.primary.is_some() || usage.secondary.is_some() => Ok(usage),
        Ok(_) => fetch_codex_usage_from_rollout(),
        Err(app_err) => fetch_codex_usage_from_rollout().map_err(|rollout_err| {
            anyhow::anyhow!(
                "app-server failed: {}; rollout fallback failed: {}",
                app_err,
                rollout_err
            )
        }),
    }
}

async fn fetch_codex_usage_from_app_server() -> anyhow::Result<CodexUsageSnapshot> {
    let codex_bin =
        resolve_codex_binary().ok_or_else(|| anyhow::anyhow!("codex CLI unavailable"))?;

    let mut child = Command::new(codex_bin)
        .args(["app-server"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()?;

    let mut stdin = child
        .stdin
        .take()
        .ok_or_else(|| anyhow::anyhow!("failed to open codex stdin"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| anyhow::anyhow!("failed to open codex stdout"))?;
    let mut reader = BufReader::new(stdout).lines();

    write_json_line(
        &mut stdin,
        &serde_json::json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "clientInfo": {
                    "name": "webmux",
                    "version": env!("CARGO_PKG_VERSION"),
                },
                "capabilities": {
                    "experimentalApi": true,
                }
            }
        }),
    )
    .await?;
    let _: serde_json::Value = read_jsonrpc_response(&mut reader, 1).await?;

    write_json_line(
        &mut stdin,
        &serde_json::json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "account/rateLimits/read",
            "params": serde_json::Value::Null,
        }),
    )
    .await?;
    let rate_limits: CodexRateLimitsResponse = read_jsonrpc_response(&mut reader, 2).await?;

    let _ = child.kill().await;
    let _ = child.wait().await;

    Ok(map_codex_rate_limits(rate_limits))
}

fn fetch_codex_usage_from_rollout() -> anyhow::Result<CodexUsageSnapshot> {
    let path = find_latest_codex_rollout_path()?;
    let usage = parse_codex_usage_from_rollout(&path)?;
    info!(
        "Using Codex usage from rollout fallback: {}",
        path.display()
    );
    Ok(usage)
}

async fn write_json_line(
    stdin: &mut tokio::process::ChildStdin,
    value: &serde_json::Value,
) -> anyhow::Result<()> {
    let mut line = serde_json::to_vec(value)?;
    line.push(b'\n');
    stdin.write_all(&line).await?;
    stdin.flush().await?;
    Ok(())
}

async fn read_jsonrpc_response<T: serde::de::DeserializeOwned>(
    reader: &mut tokio::io::Lines<BufReader<tokio::process::ChildStdout>>,
    expected_id: i64,
) -> anyhow::Result<T> {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);

    loop {
        let timeout = tokio::time::sleep_until(deadline);
        tokio::pin!(timeout);

        let next_line = reader.next_line();
        tokio::pin!(next_line);

        let line = tokio::select! {
            _ = &mut timeout => {
                anyhow::bail!("timed out waiting for codex app-server response");
            }
            line = &mut next_line => line?,
        };

        let Some(line) = line else {
            anyhow::bail!("codex app-server exited before responding");
        };
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        if let Ok(success) = serde_json::from_str::<JsonRpcSuccess<T>>(trimmed) {
            if success.id == serde_json::Value::from(expected_id) {
                return Ok(success.result);
            }
            continue;
        }

        if let Ok(error) = serde_json::from_str::<JsonRpcErrorEnvelope>(trimmed) {
            if error.id == serde_json::Value::from(expected_id) {
                anyhow::bail!("{} ({})", error.error.message, error.error.code);
            }
            continue;
        }
    }
}

fn map_codex_rate_limits(payload: CodexRateLimitsResponse) -> CodexUsageSnapshot {
    let snapshot = payload
        .rate_limits_by_limit_id
        .as_ref()
        .and_then(|limits| limits.get("codex").cloned())
        .or_else(|| {
            payload.rate_limits_by_limit_id.as_ref().and_then(|limits| {
                limits
                    .iter()
                    .find(|(_, snapshot)| snapshot.limit_id.as_deref() == Some("codex"))
                    .map(|(_, snapshot)| snapshot.clone())
            })
        })
        .unwrap_or(payload.rate_limits);

    CodexUsageSnapshot {
        primary: snapshot.primary.map(map_codex_window),
        secondary: snapshot.secondary.map(map_codex_window),
        plan_type: snapshot.plan_type,
        limit_id: snapshot.limit_id,
        limit_name: snapshot.limit_name,
    }
}

fn map_codex_window(window: CodexRateLimitWindow) -> ProviderUsageWindow {
    ProviderUsageWindow {
        used_percent: window.used_percent,
        resets_at: window.resets_at.and_then(format_codex_reset_time),
        window_duration_mins: window.window_duration_mins,
    }
}

fn find_latest_codex_rollout_path() -> anyhow::Result<std::path::PathBuf> {
    let home = dirs::home_dir().ok_or_else(|| anyhow::anyhow!("home directory unavailable"))?;
    let state_db = home.join(".codex/state_5.sqlite");
    if state_db.exists() {
        if let Ok(path) = query_latest_codex_rollout_from_state_db(&state_db) {
            return Ok(path);
        }
    }

    let sessions_dir = home.join(".codex/sessions");
    find_newest_rollout(&sessions_dir)
        .ok_or_else(|| anyhow::anyhow!("no Codex rollout found under {}", sessions_dir.display()))
}

fn query_latest_codex_rollout_from_state_db(
    db_path: &std::path::Path,
) -> anyhow::Result<std::path::PathBuf> {
    let conn = rusqlite::Connection::open(db_path)
        .map_err(|e| anyhow::anyhow!("failed to open Codex state DB: {}", e))?;

    let mut stmt = conn
        .prepare(
            "SELECT rollout_path FROM threads WHERE archived = 0 ORDER BY updated_at DESC LIMIT 1",
        )
        .map_err(|e| anyhow::anyhow!("failed to prepare Codex state query: {}", e))?;

    let path_str: String = stmt
        .query_row([], |row| row.get(0))
        .map_err(|e| anyhow::anyhow!("no active Codex rollout in state DB: {}", e))?;

    let path = std::path::PathBuf::from(&path_str);
    if path.exists() {
        Ok(path)
    } else {
        Err(anyhow::anyhow!("rollout file does not exist: {}", path_str))
    }
}

fn find_newest_rollout(dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let mut best: Option<(std::time::SystemTime, std::path::PathBuf)> = None;

    fn scan(dir: &std::path::Path, best: &mut Option<(std::time::SystemTime, std::path::PathBuf)>) {
        let entries = match std::fs::read_dir(dir) {
            Ok(entries) => entries,
            Err(_) => return,
        };

        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                scan(&path, best);
                continue;
            }

            if path.extension().and_then(|ext| ext.to_str()) != Some("jsonl") {
                continue;
            }

            let modified = match entry.metadata().and_then(|meta| meta.modified()) {
                Ok(modified) => modified,
                Err(_) => continue,
            };

            if best.as_ref().is_none_or(|(ts, _)| modified > *ts) {
                *best = Some((modified, path));
            }
        }
    }

    scan(dir, &mut best);
    best.map(|(_, path)| path)
}

fn parse_codex_usage_from_rollout(path: &std::path::Path) -> anyhow::Result<CodexUsageSnapshot> {
    let data = std::fs::read_to_string(path)
        .map_err(|e| anyhow::anyhow!("failed to read rollout {}: {}", path.display(), e))?;

    let mut latest: Option<CodexUsageSnapshot> = None;

    for line in data.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let value: serde_json::Value = match serde_json::from_str(trimmed) {
            Ok(value) => value,
            Err(_) => continue,
        };

        if value.get("type").and_then(|v| v.as_str()) != Some("event_msg") {
            continue;
        }

        let Some(payload) = value.get("payload") else {
            continue;
        };

        if payload.get("type").and_then(|v| v.as_str()) != Some("token_count") {
            continue;
        }

        let Some(rate_limits) = payload.get("rate_limits") else {
            continue;
        };

        latest = Some(CodexUsageSnapshot {
            primary: rate_limits
                .get("primary")
                .and_then(map_rollout_usage_window),
            secondary: rate_limits
                .get("secondary")
                .and_then(map_rollout_usage_window),
            plan_type: rate_limits
                .get("plan_type")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
            limit_id: rate_limits
                .get("limit_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
            limit_name: rate_limits
                .get("limit_name")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string()),
        });
    }

    latest.ok_or_else(|| {
        anyhow::anyhow!(
            "no Codex rate limit snapshot found in rollout {}",
            path.display()
        )
    })
}

fn map_rollout_usage_window(value: &serde_json::Value) -> Option<ProviderUsageWindow> {
    let used_percent = value
        .get("used_percent")
        .and_then(|v| v.as_f64().or_else(|| v.as_i64().map(|n| n as f64)))
        .map(|v| v.round().clamp(0.0, 100.0) as u32)?;
    let resets_at = value
        .get("resets_at")
        .and_then(|v| v.as_i64())
        .and_then(format_codex_reset_time);
    let window_duration_mins = value
        .get("window_minutes")
        .and_then(|v| v.as_i64())
        .or_else(|| value.get("window_duration_mins").and_then(|v| v.as_i64()));

    Some(ProviderUsageWindow {
        used_percent,
        resets_at,
        window_duration_mins,
    })
}

fn format_codex_reset_time(raw: i64) -> Option<String> {
    let millis = if raw > 10_000_000_000 {
        raw
    } else {
        raw * 1000
    };
    DateTime::<Utc>::from_timestamp_millis(millis).map(|dt| dt.to_rfc3339())
}

fn normalize_codex_error(raw: &str) -> String {
    let lower = raw.to_lowercase();
    if lower.contains("not logged in") || lower.contains("login") || lower.contains("auth") {
        "not logged in".to_string()
    } else if lower.contains("codex cli unavailable") || lower.contains("no such file") {
        "codex unavailable".to_string()
    } else if lower.contains("rate limits") || lower.contains("error sending request") {
        "API unreachable".to_string()
    } else {
        "failed to fetch usage".to_string()
    }
}

fn resolve_codex_binary() -> Option<std::path::PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(path) = std::env::var("CODEX_BIN") {
        candidates.push(std::path::PathBuf::from(path));
    }
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(".npm-global/bin/codex"));
        candidates.push(home.join(".local/bin/codex"));
    }
    candidates.push(std::path::PathBuf::from("/usr/local/bin/codex"));
    candidates.push(std::path::PathBuf::from("/opt/homebrew/bin/codex"));
    candidates.push(std::path::PathBuf::from("codex"));

    candidates.into_iter().find(|path| {
        if path.is_absolute() {
            path.exists()
        } else {
            true
        }
    })
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
    use crate::websocket::types::BroadcastMessage;
    use tokio::sync::mpsc;

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
        use crate::websocket::{client_manager::ClientManager, types::WsState};
        use crate::{
            chat_clear_store::ChatClearStore, chat_event_store::ChatEventStore,
            chat_file_storage::ChatFileStorage,
        };
        use std::sync::Arc;
        use tokio::sync::Mutex;

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
            selected_backend: "acp".to_string(),
        };
        // AudioAction::Start will attempt to start streaming — may fail without audio provider
        let result = handle_audio_control(&mut ws_state, AudioAction::Start).await;
        // Result depends on audio availability — just verify no panic
        let _ = result;
    }

    #[tokio::test]
    async fn test_handle_audio_control_stop_no_tx() {
        use crate::websocket::{client_manager::ClientManager, types::WsState};
        use crate::{
            chat_clear_store::ChatClearStore, chat_event_store::ChatEventStore,
            chat_file_storage::ChatFileStorage,
        };
        use std::sync::Arc;
        use tokio::sync::Mutex;

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
            selected_backend: "acp".to_string(),
        };
        // Stop with audio_tx=None should be a no-op
        let result = handle_audio_control(&mut ws_state, AudioAction::Stop).await;
        assert!(result.is_ok());
        assert!(ws_state.audio_tx.is_none());
    }

    #[test]
    fn test_map_codex_rate_limits_prefers_codex_bucket() {
        let payload = CodexRateLimitsResponse {
            rate_limits: CodexRateLimitSnapshot {
                limit_id: Some("default".into()),
                limit_name: Some("Default".into()),
                plan_type: Some("plus".into()),
                primary: Some(CodexRateLimitWindow {
                    used_percent: 10,
                    resets_at: Some(1_746_216_000),
                    window_duration_mins: Some(60),
                }),
                secondary: None,
            },
            rate_limits_by_limit_id: Some(std::collections::HashMap::from([(
                "codex".into(),
                CodexRateLimitSnapshot {
                    limit_id: Some("codex".into()),
                    limit_name: Some("Codex".into()),
                    plan_type: Some("pro".into()),
                    primary: Some(CodexRateLimitWindow {
                        used_percent: 42,
                        resets_at: Some(1_746_216_000),
                        window_duration_mins: Some(300),
                    }),
                    secondary: Some(CodexRateLimitWindow {
                        used_percent: 64,
                        resets_at: Some(1_746_820_800),
                        window_duration_mins: Some(10_080),
                    }),
                },
            )])),
        };

        let usage = map_codex_rate_limits(payload);
        assert_eq!(usage.plan_type.as_deref(), Some("pro"));
        assert_eq!(usage.limit_id.as_deref(), Some("codex"));
        assert_eq!(usage.primary.as_ref().map(|w| w.used_percent), Some(42));
        assert_eq!(usage.secondary.as_ref().map(|w| w.used_percent), Some(64));
        assert!(usage.primary.and_then(|w| w.resets_at).is_some());
    }

    #[test]
    fn test_format_codex_reset_time_supports_seconds_and_millis() {
        let from_seconds = format_codex_reset_time(1_746_216_000).expect("seconds");
        let from_millis = format_codex_reset_time(1_746_216_000_000).expect("millis");
        assert_eq!(from_seconds, from_millis);
    }

    #[test]
    fn test_parse_codex_usage_from_rollout() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("rollout.jsonl");
        std::fs::write(
            &path,
            r#"{"timestamp":"2026-04-10T22:20:31.832Z","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex","limit_name":null,"primary":{"used_percent":3.0,"window_minutes":300,"resets_at":1775875952},"secondary":{"used_percent":1.0,"window_minutes":10080,"resets_at":1776462752},"credits":null,"plan_type":"unknown"}}}
"#,
        )
        .unwrap();

        let usage = parse_codex_usage_from_rollout(&path).expect("rollout usage");
        assert_eq!(usage.limit_id.as_deref(), Some("codex"));
        assert_eq!(usage.plan_type.as_deref(), Some("unknown"));
        assert_eq!(usage.primary.as_ref().map(|w| w.used_percent), Some(3));
        assert_eq!(
            usage.primary.as_ref().and_then(|w| w.window_duration_mins),
            Some(300)
        );
        assert_eq!(usage.secondary.as_ref().map(|w| w.used_percent), Some(1));
        assert_eq!(
            usage
                .secondary
                .as_ref()
                .and_then(|w| w.window_duration_mins),
            Some(10_080)
        );
        assert!(usage
            .primary
            .as_ref()
            .and_then(|w| w.resets_at.clone())
            .is_some());
    }

    #[test]
    fn test_find_newest_rollout_prefers_latest_modified_file() {
        let dir = tempfile::TempDir::new().unwrap();
        let older = dir.path().join("older.jsonl");
        let nested_dir = dir.path().join("nested");
        std::fs::create_dir_all(&nested_dir).unwrap();
        let newer = nested_dir.join("newer.jsonl");

        std::fs::write(&older, "{}\n").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        std::fs::write(&newer, "{}\n").unwrap();

        let found = find_newest_rollout(dir.path()).expect("newest rollout");
        assert_eq!(found, newer);
    }
}
