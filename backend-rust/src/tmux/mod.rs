use anyhow::Result;
use chrono::{DateTime, Utc};
use std::path::PathBuf;
use std::process::Stdio;
use tokio::process::Command;
use tracing::{debug, error, info, warn};

use crate::types::{TmuxSession, TmuxWindow};

/// Get the current working directory of a tmux session.
pub fn get_session_path(session_name: &str) -> Option<PathBuf> {
    let output = std::process::Command::new("tmux")
        .args([
            "display-message",
            "-p",
            "-F",
            "#{pane_current_path}",
            "-t",
            session_name,
        ])
        .output()
        .ok()?;

    if output.status.success() {
        let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Some(PathBuf::from(path))
    } else {
        None
    }
}

/// Metadata about a tmux pane, used for history capture.
pub struct PaneMetadata {
    pub history_size: i64,
    pub pane_height: u32,
    pub pane_width: u32,
}

/// Get pane metadata (history_size, pane dimensions) for a given session/window.
pub async fn get_pane_metadata(session: &str, window: u32) -> Result<PaneMetadata> {
    let target = format!("{}:{}", session, window);
    let output = Command::new("tmux")
        .args(&[
            "display-message",
            "-p",
            "-t",
            &target,
            "#{history_size}:#{pane_height}:#{pane_width}",
        ])
        .output()
        .await?;

    if !output.status.success() {
        anyhow::bail!("Failed to get pane metadata for {}", target);
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let parts: Vec<&str> = stdout.trim().split(':').collect();
    if parts.len() < 3 {
        anyhow::bail!("Unexpected pane metadata format: {}", stdout.trim());
    }

    Ok(PaneMetadata {
        history_size: parts[0].parse().unwrap_or(0),
        pane_height: parts[1].parse().unwrap_or(24),
        pane_width: parts[2].parse().unwrap_or(80),
    })
}

/// Capture all scrollback history above the visible viewport for a pane.
/// Uses a 2-second timeout to avoid blocking if tmux is slow.
/// The -E -1 argument excludes the current visible viewport to prevent duplicate lines.
pub async fn capture_history_above_viewport(session: &str, window: u32) -> Result<String> {
    let target = format!("{}:{}", session, window);

    // Get history size first to know bounds
    let meta = get_pane_metadata(session, window)
        .await
        .unwrap_or(PaneMetadata {
            history_size: 1000,
            pane_height: 24,
            pane_width: 80,
        });

    if meta.history_size <= 0 {
        return Ok(String::new());
    }

    let start_line = format!("-{}", meta.history_size);

    // Use a 2-second timeout to protect against slow tmux
    let capture_future = async {
        Command::new("tmux")
            .args(&[
                "capture-pane",
                "-p", // Print to stdout
                "-J", // Join wrapped lines
                "-t",
                &target,
                "-S",
                &start_line, // Start from beginning of history
                "-E",
                "-1", // End just before the visible viewport
            ])
            .output()
            .await
    };

    match tokio::time::timeout(tokio::time::Duration::from_secs(2), capture_future).await {
        Ok(Ok(output)) => {
            if output.status.success() {
                let raw = String::from_utf8_lossy(&output.stdout).to_string();
                // Filter out unwanted control sequences
                let filtered = crate::terminal_buffer::filter_control_sequences(&raw);
                Ok(filtered)
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr);
                anyhow::bail!("capture-pane failed: {}", stderr)
            }
        }
        Ok(Err(e)) => anyhow::bail!("capture-pane IO error: {}", e),
        Err(_) => {
            warn!("[history-bootstrap] capture-pane timed out for {}", target);
            anyhow::bail!("capture-pane timed out")
        }
    }
}

/// Split terminal output text into chunks, splitting only at line boundaries.
/// Returns a Vec of String chunks each no larger than max_bytes.
pub fn chunk_terminal_stream(text: &str, max_bytes: usize) -> Vec<String> {
    if text.is_empty() {
        return vec![];
    }

    let mut chunks = Vec::new();
    let mut current = String::new();

    for line in text.split_inclusive('\n') {
        // If this single line is bigger than max_bytes, split it anyway
        if line.len() >= max_bytes {
            if !current.is_empty() {
                chunks.push(current.clone());
                current.clear();
            }
            chunks.push(line.to_string());
            continue;
        }

        if current.len() + line.len() > max_bytes {
            chunks.push(current.clone());
            current.clear();
        }
        current.push_str(line);
    }

    if !current.is_empty() {
        chunks.push(current);
    }

    chunks
}

fn escape_single_quotes(s: &str) -> String {
    s.replace('\'', "'\\''")
}

pub async fn ensure_tmux_server() -> Result<()> {
    // Check if tmux server is running
    let output = Command::new("tmux")
        .args(&["list-sessions"])
        .stderr(Stdio::null())
        .output()
        .await?;

    if !output.status.success() {
        // Start tmux server with a dummy session
        debug!("Starting TMUX server...");
        Command::new("tmux")
            .args(&["new-session", "-d", "-s", "__dummy__", "-c", "~", "exit"])
            .output()
            .await?;

        // Small delay to ensure server is fully started
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    Ok(())
}

pub async fn list_sessions() -> Result<Vec<TmuxSession>> {
    // Always use fallback for now - control mode needs more testing
    list_sessions_fallback().await
}

async fn list_sessions_fallback() -> Result<Vec<TmuxSession>> {
    // First ensure tmux server is running
    let check = Command::new("tmux")
        .args(&["list-sessions"])
        .stderr(Stdio::null())
        .output()
        .await?;

    if !check.status.success() {
        // TMUX not running, return empty list
        return Ok(vec![]);
    }

    let output = Command::new("tmux")
        .args(&[
            "list-sessions",
            "-F",
            "#{session_name}:#{session_attached}:#{session_created}:#{session_windows}:#{session_width}x#{session_height}",
        ])
        .output()
        .await?;

    if !output.status.success() {
        return Ok(vec![]);
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let sessions: Vec<TmuxSession> = stdout
        .lines()
        .filter(|line| !line.is_empty())
        .filter_map(|line| {
            let parts: Vec<&str> = line.split(':').collect();
            if parts.len() >= 5 {
                let created_timestamp = parts[2].parse::<i64>().ok()?;
                Some(TmuxSession {
                    name: parts[0].to_string(),
                    attached: parts[1] == "1",
                    created: DateTime::from_timestamp(created_timestamp, 0)
                        .unwrap_or_else(|| Utc::now()),
                    windows: parts[3].parse().unwrap_or(0),
                    dimensions: parts[4].to_string(),
                })
            } else {
                None
            }
        })
        .collect();

    Ok(sessions)
}

pub async fn create_session(name: &str) -> Result<()> {
    ensure_tmux_server().await?;

    // Get the home directory to start sessions there
    let home_dir = std::env::var("HOME").unwrap_or_else(|_| "/".to_string());

    info!(
        "Executing tmux new-session for: {} in directory: {}",
        name, home_dir
    );
    let status = Command::new("tmux")
        .args(&["new-session", "-d", "-s", name, "-c", &home_dir])
        .env("HOME", &home_dir)
        .status()
        .await?;

    if !status.success() {
        error!("tmux new-session failed for: {}", name);
        anyhow::bail!("Failed to create session");
    }

    info!("tmux new-session succeeded for: {}", name);
    Ok(())
}

pub async fn kill_session(name: &str) -> Result<()> {
    info!("Executing tmux kill-session for: {}", name);

    // First try regular kill-session
    let status = Command::new("tmux")
        .args(&["kill-session", "-t", name])
        .status()
        .await?;

    if !status.success() {
        // If that fails, try with -C flag to kill all clients
        error!(
            "tmux kill-session failed, trying with -C flag for: {}",
            name
        );
        let status2 = Command::new("tmux")
            .args(&["kill-session", "-C", "-t", name])
            .status()
            .await?;

        if !status2.success() {
            error!("tmux kill-session -C also failed for: {}", name);
            anyhow::bail!("Failed to kill session");
        }
    }

    info!("tmux kill-session succeeded for: {}", name);
    Ok(())
}

pub async fn rename_session(old_name: &str, new_name: &str) -> Result<()> {
    let output = Command::new("sh")
        .arg("-c")
        .arg(format!(
            "tmux rename-session -t '{}' '{}'",
            escape_single_quotes(old_name),
            escape_single_quotes(new_name)
        ))
        .output()
        .await?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Failed to rename session: {}", stderr);
    }

    Ok(())
}

pub async fn list_windows(session_name: &str) -> Result<Vec<TmuxWindow>> {
    let output = Command::new("tmux")
        .args(&[
            "list-windows",
            "-t",
            session_name,
            "-F",
            "#{window_index}:#{window_name}:#{window_active}:#{window_panes}",
        ])
        .output()
        .await?;

    if !output.status.success() {
        anyhow::bail!("Session not found");
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let windows: Vec<TmuxWindow> = stdout
        .lines()
        .filter(|line| !line.is_empty())
        .filter_map(|line| {
            let parts: Vec<&str> = line.split(':').collect();
            if parts.len() >= 4 {
                Some(TmuxWindow {
                    index: parts[0].parse().ok()?,
                    name: parts[1].to_string(),
                    active: parts[2] == "1",
                    panes: parts[3].parse().unwrap_or(1),
                })
            } else {
                None
            }
        })
        .collect();

    Ok(windows)
}

pub async fn create_window(session_name: &str, window_name: Option<&str>) -> Result<()> {
    // Try to get the current pane's working directory
    let current_dir = get_current_pane_directory(session_name).await.ok();

    let args = vec!["new-window", "-a", "-t", session_name];

    // Store the directory in a variable that lives long enough
    let dir_args: Vec<String>;
    if let Some(dir) = current_dir {
        dir_args = vec!["-c".to_string(), dir];
    } else {
        dir_args = vec![];
    }

    // Convert args to the correct format
    let mut final_args: Vec<&str> = args.into_iter().collect();
    for arg in &dir_args {
        final_args.push(arg);
    }

    if let Some(name) = window_name {
        final_args.push("-n");
        final_args.push(name);
    }

    let status = Command::new("tmux").args(&final_args).status().await?;

    if !status.success() {
        anyhow::bail!("Failed to create window");
    }

    Ok(())
}

/// Get the current pane's working directory
async fn get_current_pane_directory(session_name: &str) -> Result<String> {
    let output = Command::new("tmux")
        .args(&[
            "display-message",
            "-p",
            "-t",
            session_name,
            "#{pane_current_path}",
        ])
        .output()
        .await?;

    if !output.status.success() {
        anyhow::bail!("Failed to get current pane directory");
    }

    let dir = String::from_utf8_lossy(&output.stdout).trim().to_string();
    Ok(dir)
}

pub async fn kill_window(session_name: &str, window_index: &str) -> Result<()> {
    let target = format!("{}:{}", session_name, window_index);
    let status = Command::new("tmux")
        .args(&["kill-window", "-t", &target])
        .status()
        .await?;

    if !status.success() {
        anyhow::bail!("Failed to kill window");
    }

    Ok(())
}

pub async fn rename_window(session_name: &str, window_index: &str, new_name: &str) -> Result<()> {
    let target = format!("{}:{}", session_name, window_index);
    let output = Command::new("sh")
        .arg("-c")
        .arg(format!(
            "tmux rename-window -t '{}' '{}'",
            target,
            escape_single_quotes(new_name)
        ))
        .output()
        .await?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Failed to rename window: {}", stderr);
    }

    Ok(())
}

pub async fn select_window(session_name: &str, window_index: &str) -> Result<()> {
    let target = format!("{}:{}", session_name, window_index);
    let status = Command::new("tmux")
        .args(&["select-window", "-t", &target])
        .status()
        .await?;

    if !status.success() {
        anyhow::bail!("Failed to select window");
    }

    Ok(())
}

// Alternative session management functions that avoid direct attachment

pub async fn capture_pane(session_name: &str) -> Result<String> {
    let output = Command::new("tmux")
        .args(&[
            "capture-pane",
            "-t",
            session_name,
            "-p", // Print to stdout
            "-e", // Include escape sequences
            "-J", // Join wrapped lines
            "-S",
            "-", // Start from beginning of visible area
            "-E",
            "-", // End at bottom
        ])
        .output()
        .await?;

    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Failed to capture pane: {}", stderr)
    }
}

pub async fn send_keys_to_session(session_name: &str, window_index: u32, keys: &str) -> Result<()> {
    let target = format!("{}:{}", session_name, window_index);

    // Use send-keys WITHOUT -l flag (literal mode) to send plain text
    // The -l flag interprets keys as literal characters (like special keys)
    let status = Command::new("tmux")
        .args(&["send-keys", "-t", &target, keys])
        .status()
        .await?;

    if !status.success() {
        anyhow::bail!("Failed to send keys to session");
    }

    Ok(())
}

pub async fn send_special_key(session_name: &str, window_index: u32, key: &str) -> Result<()> {
    let target = format!("{}:{}", session_name, window_index);
    let status = Command::new("tmux")
        .args(&["send-keys", "-t", &target, key])
        .status()
        .await?;

    if !status.success() {
        anyhow::bail!("Failed to send special key");
    }

    Ok(())
}

// Batch command execution for better performance
pub struct TmuxCommandBatch {
    commands: Vec<String>,
}

impl TmuxCommandBatch {
    pub fn new() -> Self {
        Self {
            commands: Vec::new(),
        }
    }

    pub fn add_command(&mut self, args: &[&str]) {
        let cmd = args.join(" ");
        self.commands.push(cmd);
    }

    pub async fn execute(&self) -> Result<Vec<Result<String>>> {
        if self.commands.is_empty() {
            return Ok(vec![]);
        }

        // Execute multiple commands in a single tmux invocation
        let script = self.commands.join(" \\; ");
        let output = Command::new("tmux")
            .args(&["-C"]) // Control mode
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;

        let mut child = output;

        // Write commands
        if let Some(mut stdin) = child.stdin.take() {
            use tokio::io::AsyncWriteExt;
            stdin.write_all(script.as_bytes()).await?;
            stdin.write_all(b"\nexit\n").await?;
        }

        let output = child.wait_with_output().await?;

        // Parse results
        let stdout = String::from_utf8_lossy(&output.stdout);
        let results: Vec<Result<String>> =
            stdout.lines().map(|line| Ok(line.to_string())).collect();

        Ok(results)
    }
}
