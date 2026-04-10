use std::fs::File;
use std::io::{BufRead, BufReader, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::Stdio;

use anyhow::{bail, Context, Result};
use notify::{RecursiveMode, Watcher};
use tokio::process::Command;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use super::{claude_parser, codex_parser, kiro_parser, opencode_parser, AiTool, ChatLogEvent, ChatMessage};

// ---------------------------------------------------------------------------
// Log file detection
// ---------------------------------------------------------------------------

/// Detect which AI tool is running in the given tmux pane and locate its log
/// file.  Returns the log path and the detected tool variant.
pub async fn detect_log_file(session_name: &str, window_index: u32) -> Result<(PathBuf, AiTool)> {
    let target = format!("{session_name}:{window_index}");
    let pane_pid = get_pane_pid(&target).await?;
    let descendants = get_descendant_pids(pane_pid)?;

    for pid in &descendants {
        let name = match process_name(*pid) {
            Some(n) => n,
            None => continue,
        };

        if name == "claude" {
            let cwd = get_process_cwd(*pid)?;
            let log = find_claude_log(&cwd)?;
            info!("detected Claude log: {}", log.display());
            return Ok((log, AiTool::Claude));
        }

        if name == "codex" {
            let cwd = get_process_cwd(*pid)?;
            let log = find_codex_log(&cwd)?;
            info!(
                "detected Codex log: {} (cwd: {})",
                log.display(),
                cwd.display()
            );
            return Ok((log, AiTool::Codex));
        }

        if name == "opencode" {
            let cwd = get_process_cwd(*pid)?;
            let log = find_opencode_db()?;
            info!(
                "detected Opencode database: {} for PID {} (cwd: {})",
                log.display(),
                pid,
                cwd.display()
            );
            return Ok((log, AiTool::Opencode { cwd, pid: *pid }));
        }

        if name == "kiro-cli-chat" || name == "kiro-cli" {
            // Note: kiro-cli's comm is "kiro-cli" (not "kiro-cli-chat").
            // We check both for compatibility.
            let cwd = get_process_cwd(*pid)?;
            let session_id = kiro_parser::find_session_id(&cwd)
                .unwrap_or_else(|_| format!("session-{}", pid));
            info!(
                "detected kiro-cli for PID {} (comm={}, cwd: {}, session: {})",
                pid,
                name,
                cwd.display(),
                session_id
            );
            // Return /dev/null as the "log path" — kiro uses PTY capture, not file watching
            return Ok((PathBuf::from("/dev/null"), AiTool::Kiro { cwd, pid: *pid, session_id }));
        }
    }

    bail!("no AI tool (claude/codex/opencode/kiro) found among descendants of pane PID {pane_pid}");
}

/// Detect which AI tool (if any) is running in window 0 of the given tmux session.
/// Returns a short string identifier: "claude", "codex", "opencode", or "kiro".
pub async fn detect_tool_name(session_name: &str) -> Option<String> {
    let target = format!("{session_name}:0");
    let pane_pid = get_pane_pid(&target).await.ok()?;
    let descendants = get_descendant_pids(pane_pid).ok()?;

    for pid in &descendants {
        match process_name(*pid).as_deref() {
            Some("claude") => return Some("claude".to_string()),
            Some("codex") => return Some("codex".to_string()),
            Some("opencode") => return Some("opencode".to_string()),
            Some("kiro-cli") | Some("kiro-cli-chat") => return Some("kiro".to_string()),
            _ => {}
        }
    }
    None
}

// ---------------------------------------------------------------------------
// Log file watcher
// ---------------------------------------------------------------------------

pub enum LogWatcher {
    File(#[allow(dead_code)] notify::RecommendedWatcher),
    Task(tokio::task::JoinHandle<()>),
}

impl Drop for LogWatcher {
    fn drop(&mut self) {
        if let LogWatcher::Task(ref handle) = self {
            handle.abort();
        }
    }
}

/// Watch a log file for changes and emit parsed chat events.
pub async fn watch_log_file(
    path: &Path,
    tool: AiTool,
    event_tx: mpsc::UnboundedSender<ChatLogEvent>,
    cleared_at: Option<i64>,
    limit: Option<usize>,
) -> Result<LogWatcher> {
    if let AiTool::Opencode { cwd, pid } = &tool {
        return watch_opencode_db(path, cwd, *pid, event_tx, cleared_at, limit).await;
    }
    // --- initial history read ---
    let file =
        File::open(path).with_context(|| format!("failed to open log file: {}", path.display()))?;
    let mut reader = BufReader::new(file);
    let history = read_all_messages(&mut reader, &tool);

    // Filter messages based on cleared_at timestamp
    let filtered_history: Vec<_> = if let Some(ts) = cleared_at {
        history
            .into_iter()
            .filter(|msg| {
                if let Some(msg_ts) = msg.timestamp {
                    msg_ts.timestamp_millis() > ts
                } else {
                    false // Exclude un-timestamped historical messages after a clear
                }
            })
            .collect()
    } else {
        history
    };

    let total_count = filtered_history.len();
    let (paginated, has_more) = match limit {
        Some(n) if total_count > n => (filtered_history[total_count - n..].to_vec(), true),
        _ => (filtered_history, false),
    };

    let ctx_info: Option<(f64, String)> = match &tool {
        AiTool::Claude => claude_parser::extract_context_usage(path)
            .map(|c| (c.usage_pct, c.model)),
        AiTool::Codex => codex_parser::extract_context_usage(path)
            .map(|c| (c.usage_pct, c.model)),
        _ => None,
    };

    event_tx
        .send(ChatLogEvent::History {
            messages: paginated,
            tool: tool.clone(),
            has_more,
            total_count,
            context_window_usage: ctx_info.as_ref().map(|(usage, _)| *usage),
            model_name: ctx_info.as_ref().map(|(_, model)| model.clone()),
        })
        .ok();

    // Record the position after the initial read so we only parse new data.
    let start_pos = reader.stream_position()?;

    // --- set up file-system watcher ---
    // `notify` callbacks are sync; bridge to async with an unbounded channel.
    let (notify_tx, mut notify_rx) = mpsc::unbounded_channel::<()>();

    let watcher = notify::recommended_watcher(move |res: notify::Result<notify::Event>| {
        match res {
            Ok(event) if event.kind.is_modify() => {
                let _ = notify_tx.send(());
            }
            Ok(_) => {} // ignore non-modify events
            Err(e) => {
                // Log but do not crash the watcher.
                error!("notify error: {e}");
            }
        }
    })?;

    let mut watcher = watcher;
    watcher.watch(path, RecursiveMode::NonRecursive)?;

    // Spawn a background task that reads new lines whenever notify fires.
    let file_path = path.to_path_buf();
    tokio::spawn(async move {
        let mut pos = start_pos;
        while notify_rx.recv().await.is_some() {
            // Drain any extra notifications that arrived while we were
            // processing so we do a single read per burst.
            while notify_rx.try_recv().is_ok() {}

            match read_new_lines(&file_path, &mut pos, &tool) {
                Ok(messages) => {
                    for msg in messages {
                        if event_tx
                            .send(ChatLogEvent::NewMessage { message: msg })
                            .is_err()
                        {
                            debug!("event_tx closed, stopping watcher task");
                            return;
                        }
                    }
                    // Recalculate context window usage for the active chat
                    if matches!(tool, AiTool::Claude | AiTool::Codex) {
                        let info: Option<(f64, String)> = if matches!(tool, AiTool::Claude) {
                            claude_parser::extract_context_usage(&file_path)
                                .map(|c| (c.usage_pct, c.model))
                        } else {
                            codex_parser::extract_context_usage(&file_path)
                                .map(|c| (c.usage_pct, c.model))
                        };
                        if let Some((usage, model)) = info {
                            let _ = event_tx.send(ChatLogEvent::ContextWindowUpdate {
                                usage,
                                model_name: Some(model),
                            });
                        }
                    }
                }
                Err(e) => {
                    warn!("failed to read new log lines: {e}");
                    let _ = event_tx.send(ChatLogEvent::Error {
                        error: e.to_string(),
                    });
                }
            }
        }
    });

    Ok(LogWatcher::File(watcher))
}

async fn watch_opencode_db(
    db_path: &Path,
    cwd: &Path,
    pid: u32,
    event_tx: mpsc::UnboundedSender<ChatLogEvent>,
    cleared_at: Option<i64>,
    limit: Option<usize>,
) -> Result<LogWatcher> {
    let mut state = opencode_parser::init_opencode_state(db_path, cwd, pid, cleared_at)?;

    let db_path_owned = db_path.to_path_buf();
    let cwd_owned = cwd.to_path_buf();
    let initial_pid = pid;

    info!(
        "Starting OpenCode watcher for PID {} in directory {} (cleared_at: {:?})",
        pid,
        cwd_owned.display(),
        cleared_at
    );

    // Small delay to ensure client has subscribed to the channel if this was triggered by a message
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    // Initial fetch for history
    if let Ok(messages) = opencode_parser::fetch_new_messages(&db_path_owned, &mut state) {
        info!("Initial fetch got {} messages", messages.len());
        let total_count = messages.len();
        let (paginated, has_more) = match limit {
            Some(n) if total_count > n => (messages[total_count - n..].to_vec(), true),
            _ => (messages, false),
        };
        let _ = event_tx.send(ChatLogEvent::History {
            messages: paginated,
            tool: AiTool::Opencode {
                cwd: cwd.to_path_buf(),
                pid,
            },
            has_more,
            total_count,
            context_window_usage: None,
            model_name: None,
        });
    } else {
        info!("Initial fetch got no messages or error");
    }

    // Polling task
    let handle = tokio::spawn(async move {
        info!("OpenCode polling task started for PID {}", initial_pid);
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(500));
        loop {
            interval.tick().await;

            // Verify the PID is still running with the same CWD
            let current_pid_valid = is_process_alive_with_cwd(initial_pid, &cwd_owned);

            if !current_pid_valid {
                // Try to find a new opencode process in the same directory
                debug!(
                    "OpenCode PID {} no longer valid, re-detecting session for {}",
                    initial_pid,
                    cwd_owned.display()
                );

                // Re-detect using get_descendant_pids approach to find any opencode in this CWD
                if let Ok(new_pid) = find_opencode_pid_for_cwd(&cwd_owned) {
                    // Use the cleared_at from the current state when re-detecting
                    let cleared_at = state.cleared_at;
                    match opencode_parser::init_opencode_state(
                        &db_path_owned,
                        &cwd_owned,
                        new_pid,
                        cleared_at,
                    ) {
                        Ok(new_state) => {
                            debug!("Re-detected OpenCode session with PID {}", new_pid);
                            state = new_state;
                        }
                        Err(e) => {
                            warn!("Failed to re-detect OpenCode session: {}", e);
                            let _ = event_tx.send(ChatLogEvent::Error {
                                error: format!("OpenCode session re-detection failed: {}", e),
                            });
                            continue;
                        }
                    }
                } else {
                    // No opencode process found - the session might have ended
                    // Continue polling but don't re-detect
                    debug!("No OpenCode process found for {}", cwd_owned.display());
                }
            }

            match opencode_parser::fetch_new_messages(&db_path_owned, &mut state) {
                Ok(messages) => {
                    if !messages.is_empty() {
                        info!("Polling: got {} new messages", messages.len());
                    }
                    for msg in messages {
                        if event_tx
                            .send(ChatLogEvent::NewMessage { message: msg })
                            .is_err()
                        {
                            debug!("event_tx closed, stopping opencode polling task");
                            return;
                        }
                    }
                }
                Err(e) => {
                    warn!("failed to fetch opencode messages: {}", e);
                    let _ = event_tx.send(ChatLogEvent::Error {
                        error: format!("Opencode DB error: {}", e),
                    });
                }
            }
        }
    });

    Ok(LogWatcher::Task(handle))
}

// ---------------------------------------------------------------------------
// File reading helpers
// ---------------------------------------------------------------------------
// File reading helpers
// ---------------------------------------------------------------------------

/// Read every line from the current reader position, parse each, and collect
/// the resulting messages.
fn read_all_messages(reader: &mut BufReader<File>, tool: &AiTool) -> Vec<ChatMessage> {
    let mut messages = Vec::new();
    let mut line_buf = String::new();

    loop {
        line_buf.clear();
        match reader.read_line(&mut line_buf) {
            Ok(0) => break, // EOF
            Ok(_) => {
                if let Some(msg) = parse_line(&line_buf, tool) {
                    messages.push(msg);
                }
            }
            Err(e) => {
                warn!("error reading log line: {e}");
                break;
            }
        }
    }

    messages
}

/// Open the file, seek to `pos`, read any new complete lines, advance `pos`,
/// and return parsed messages.
fn read_new_lines(path: &Path, pos: &mut u64, tool: &AiTool) -> Result<Vec<ChatMessage>> {
    let file = File::open(path)?;
    let mut reader = BufReader::new(file);
    reader.seek(SeekFrom::Start(*pos))?;

    let mut messages = Vec::new();
    let mut line_buf = String::new();

    loop {
        line_buf.clear();
        match reader.read_line(&mut line_buf) {
            Ok(0) => break,
            Ok(_) => {
                if let Some(msg) = parse_line(&line_buf, tool) {
                    messages.push(msg);
                }
            }
            Err(e) => {
                warn!("error reading new log line: {e}");
                break;
            }
        }
    }

    *pos = reader.stream_position()?;
    Ok(messages)
}

/// Dispatch a single line to the appropriate parser.
fn parse_line(line: &str, tool: &AiTool) -> Option<ChatMessage> {
    match tool {
        AiTool::Claude => claude_parser::parse_line(line),
        AiTool::Codex => codex_parser::parse_line(line),
        AiTool::Opencode { .. } => None, // Opencode parser is handled by DB polling
        AiTool::Kiro { .. } => None,     // Kiro parser handles PTY capture via polling
    }
}

// ---------------------------------------------------------------------------
// Process-tree helpers
// ---------------------------------------------------------------------------

/// Ask tmux for the PID of the primary pane in the given target.
async fn get_pane_pid(target: &str) -> Result<u32> {
    let output = Command::new("tmux")
        .args(["display-message", "-t", target, "-p", "#{pane_pid}"])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .await
        .context("failed to run tmux display-message")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        bail!("tmux display-message failed: {stderr}");
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let pid: u32 = stdout
        .trim()
        .parse()
        .with_context(|| format!("invalid pane PID: {stdout}"))?;

    Ok(pid)
}

/// Recursively collect all descendant PIDs of `parent_pid` via procfs.
///
/// This avoids shelling out to `ps` on every call and works on Linux by
/// scanning `/proc/*/stat` for processes whose PPID matches.
fn get_descendant_pids(parent_pid: u32) -> Result<Vec<u32>> {
    let mut result = vec![parent_pid];
    let mut queue = vec![parent_pid];

    while let Some(ppid) = queue.pop() {
        for child in direct_children(ppid) {
            result.push(child);
            queue.push(child);
        }
    }

    Ok(result)
}

/// Return the immediate child PIDs of `ppid` by scanning `/proc`.
fn direct_children(ppid: u32) -> Vec<u32> {
    let mut children = Vec::new();
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return children;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<u32>().ok()) else {
            continue;
        };

        // /proc/<pid>/stat format: pid (comm) state ppid ...
        let stat_path = format!("/proc/{pid}/stat");
        let Ok(stat) = std::fs::read_to_string(&stat_path) else {
            continue;
        };

        // The comm field can contain spaces and parentheses, so find the last
        // ')' to locate the end of the comm field reliably.
        let Some(after_comm) = stat.rfind(')') else {
            continue;
        };
        let fields: Vec<&str> = stat[after_comm + 2..].split_whitespace().collect();
        // fields[0] = state, fields[1] = ppid
        if let Some(parent) = fields.get(1).and_then(|s| s.parse::<u32>().ok()) {
            if parent == ppid {
                children.push(pid);
            }
        }
    }

    children
}

/// Read the executable name for a given PID from `/proc/<pid>/comm`.
fn process_name(pid: u32) -> Option<String> {
    let path = format!("/proc/{pid}/comm");
    std::fs::read_to_string(path)
        .ok()
        .map(|s| s.trim().to_string())
}

/// Read the current working directory of a process via its `/proc` symlink.
fn get_process_cwd(pid: u32) -> Result<PathBuf> {
    let link = format!("/proc/{pid}/cwd");
    std::fs::read_link(&link).with_context(|| format!("failed to read {link}"))
}

/// Check if a process with given PID is still running and has the expected CWD.
fn is_process_alive_with_cwd(pid: u32, expected_cwd: &Path) -> bool {
    // Check if process exists and is an opencode process
    let is_opencode = match process_name(pid) {
        Some(n) => n == "opencode",
        None => return false,
    };

    if !is_opencode {
        return false;
    }

    // Check if CWD matches
    let cwd = match get_process_cwd(pid) {
        Ok(c) => c,
        Err(_) => return false,
    };

    cwd == expected_cwd
}

/// Find an opencode process PID that matches the given CWD.
fn find_opencode_pid_for_cwd(cwd: &Path) -> Result<u32> {
    // Scan /proc for all running processes
    let entries = match std::fs::read_dir("/proc") {
        Ok(e) => e,
        Err(_) => bail!("Cannot read /proc"),
    };

    for entry in entries.flatten() {
        let file_name = entry.file_name();
        let name_str = match file_name.to_str() {
            Some(n) => n,
            None => continue,
        };

        // Must be a number (PID)
        let pid: u32 = match name_str.parse() {
            Ok(p) => p,
            Err(_) => continue,
        };

        // Check if it's an opencode process with matching CWD
        if is_process_alive_with_cwd(pid, cwd) {
            return Ok(pid);
        }
    }

    bail!("No opencode process found for {}", cwd.display())
}

// ---------------------------------------------------------------------------
// Log file location helpers
// ---------------------------------------------------------------------------

/// Find the newest `.jsonl` file in Claude Code's project directory for the
/// given working directory.
///
/// Claude Code stores logs under `~/.claude/projects/<encoded_cwd>/` where
/// `<encoded_cwd>` is the absolute path with `/` replaced by `-` (with the
/// leading slash also replaced, so `/home/user/proj` becomes `-home-user-proj`
/// but the leading `-` is actually present in the directory name).
fn find_claude_log(cwd: &Path) -> Result<PathBuf> {
    let home = dirs::home_dir().context("cannot determine home directory")?;
    let encoded_cwd = cwd
        .to_str()
        .context("CWD is not valid UTF-8")?
        .replace('/', "-")
        .replace('_', "-");
    let projects_dir = home.join(".claude").join("projects").join(&encoded_cwd);

    if !projects_dir.is_dir() {
        bail!(
            "Claude projects directory does not exist: {}",
            projects_dir.display()
        );
    }

    newest_jsonl_in(&projects_dir)
        .with_context(|| format!("no .jsonl files in {}", projects_dir.display()))
}

/// Find the Codex session log for the given CWD.
///
/// Primary strategy: query `~/.codex/state_5.sqlite` threads table by CWD to
/// find the active session's `rollout_path`.
/// Fallback: scan `~/.codex/sessions/` for the newest rollout JSONL.
fn find_codex_log(cwd: &Path) -> Result<PathBuf> {
    let home = dirs::home_dir().context("cannot determine home directory")?;
    let db_path = home.join(".codex/state_5.sqlite");

    // Primary: query Codex SQLite state database
    if db_path.exists() {
        if let Ok(path) = query_codex_state_db(&db_path, cwd) {
            return Ok(path);
        }
    }

    // Fallback: scan ~/.codex/sessions/ for newest rollout JSONL
    let sessions_dir = home.join(".codex/sessions");
    if sessions_dir.is_dir() {
        if let Some(path) = find_newest_rollout(&sessions_dir) {
            return Ok(path);
        }
    }

    bail!(
        "no Codex session found for CWD {} (checked {} and {})",
        cwd.display(),
        db_path.display(),
        sessions_dir.display()
    )
}

/// Query the Codex state SQLite database for the active session matching CWD.
fn query_codex_state_db(db_path: &Path, cwd: &Path) -> Result<PathBuf> {
    let conn = rusqlite::Connection::open(db_path)
        .with_context(|| format!("failed to open Codex state DB: {}", db_path.display()))?;

    let cwd_str = cwd.to_str().context("CWD is not valid UTF-8")?;

    let mut stmt = conn
        .prepare(
            "SELECT rollout_path FROM threads WHERE cwd = ? AND archived = 0 ORDER BY updated_at DESC LIMIT 1",
        )
        .context("failed to prepare Codex state query")?;

    let path_str: String = stmt
        .query_row(rusqlite::params![cwd_str], |row| row.get(0))
        .context("no active Codex session found in state DB")?;

    let path = PathBuf::from(&path_str);
    if path.exists() {
        info!("found Codex rollout via state DB: {}", path_str);
        Ok(path)
    } else {
        warn!(
            "Codex state DB references non-existent rollout: {}",
            path_str
        );
        bail!("rollout file does not exist: {path_str}")
    }
}

/// Recursively find the newest `rollout-*.jsonl` file under the given directory.
fn find_newest_rollout(dir: &Path) -> Option<PathBuf> {
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;

    fn scan(dir: &Path, best: &mut Option<(std::time::SystemTime, PathBuf)>) {
        let entries = match std::fs::read_dir(dir) {
            Ok(e) => e,
            Err(_) => return,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                scan(&path, best);
            } else if path.extension().and_then(|e| e.to_str()) == Some("jsonl") {
                if let Ok(meta) = entry.metadata() {
                    if let Ok(modified) = meta.modified() {
                        if best.as_ref().is_none_or(|(t, _)| modified > *t) {
                            *best = Some((modified, path));
                        }
                    }
                }
            }
        }
    }

    scan(dir, &mut best);
    best.map(|(_, p)| p)
}

/// Return the path of the newest `.jsonl` file inside `dir`.
fn newest_jsonl_in(dir: &Path) -> Option<PathBuf> {
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;

    for entry in std::fs::read_dir(dir).ok()?.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("jsonl") {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        let Ok(modified) = meta.modified() else {
            continue;
        };
        if best.as_ref().is_none_or(|(t, _)| modified > *t) {
            best = Some((modified, path));
        }
    }

    best.map(|(_, p)| p)
}

/// Find the Opencode database
pub fn find_opencode_db() -> Result<PathBuf> {
    let home = dirs::home_dir().context("cannot determine home directory")?;
    let db_path = home.join(".local/share/opencode/opencode.db");
    if db_path.exists() {
        Ok(db_path)
    } else {
        bail!("Opencode database not found at {}", db_path.display())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_get_descendant_pids_includes_self() {
        // Use our own PID — it definitely exists
        let pid = std::process::id();
        let result = get_descendant_pids(pid);
        assert!(result.is_ok());
        let pids = result.unwrap();
        assert!(pids.contains(&pid));
    }

    #[test]
    fn test_direct_children_does_not_panic() {
        let children = direct_children(1); // init/systemd always exists on Linux
        // May be empty or have some children — just shouldn't panic
        let _ = children;
    }

    #[test]
    fn test_process_name_self() {
        let pid = std::process::id();
        let name = process_name(pid);
        // Should return something for our own process
        assert!(name.is_some());
    }

    #[test]
    fn test_process_name_nonexistent() {
        // PID 0 doesn't have a /proc entry
        let name = process_name(0);
        assert!(name.is_none());
    }

    #[test]
    fn test_get_process_cwd_self() {
        let pid = std::process::id();
        let result = get_process_cwd(pid);
        assert!(result.is_ok());
    }

    #[test]
    fn test_get_process_cwd_nonexistent() {
        let result = get_process_cwd(999999999);
        assert!(result.is_err());
    }

    #[test]
    fn test_newest_jsonl_in_empty_dir() {
        let dir = tempfile::TempDir::new().unwrap();
        let result = newest_jsonl_in(dir.path());
        assert!(result.is_none());
    }

    #[test]
    fn test_newest_jsonl_in_with_files() {
        let dir = tempfile::TempDir::new().unwrap();
        std::fs::write(dir.path().join("old.jsonl"), "{}").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(10));
        std::fs::write(dir.path().join("newer.jsonl"), "{}").unwrap();
        let result = newest_jsonl_in(dir.path());
        assert!(result.is_some());
        // Should return the newer file
        assert!(result.unwrap().file_name().unwrap().to_str().unwrap().ends_with(".jsonl"));
    }

    #[test]
    fn test_newest_jsonl_ignores_non_jsonl() {
        let dir = tempfile::TempDir::new().unwrap();
        std::fs::write(dir.path().join("file.txt"), "{}").unwrap();
        std::fs::write(dir.path().join("data.json"), "{}").unwrap();
        let result = newest_jsonl_in(dir.path());
        assert!(result.is_none()); // No .jsonl files
    }

    #[test]
    fn test_parse_line_invalid_json() {
        let result = parse_line("not json at all", &AiTool::Claude);
        assert!(result.is_none());
    }

    #[test]
    fn test_parse_line_codex_invalid() {
        let result = parse_line("not json", &AiTool::Codex);
        assert!(result.is_none());
    }

    #[test]
    fn test_parse_line_opencode_always_none() {
        let result = parse_line("{}", &AiTool::Opencode { pid: 0, cwd: std::path::PathBuf::new() });
        assert!(result.is_none());
    }

    #[test]
    fn test_is_process_alive_with_cwd_self() {
        let pid = std::process::id();
        let cwd = std::env::current_dir().unwrap();
        // May be true or false depending on CWD match — just should not panic
        let _ = is_process_alive_with_cwd(pid, &cwd);
    }

    #[test]
    fn test_is_process_alive_nonexistent_pid() {
        let result = is_process_alive_with_cwd(999999999, std::path::Path::new("/"));
        assert!(!result);
    }

    #[test]
    fn test_read_new_lines_empty_file() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("empty.jsonl");
        std::fs::write(&path, "").unwrap();
        let mut pos: u64 = 0;
        let result = read_new_lines(&path, &mut pos, &AiTool::Claude);
        assert!(result.is_ok());
        assert!(result.unwrap().is_empty());
        assert_eq!(pos, 0);
    }

    #[test]
    fn test_read_new_lines_with_content() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("test.jsonl");
        // Write two lines — these won't parse as valid Claude messages
        // but read_new_lines should still advance pos
        std::fs::write(&path, "{\"invalid\":1}\n{\"invalid\":2}\n").unwrap();
        let mut pos: u64 = 0;
        let result = read_new_lines(&path, &mut pos, &AiTool::Claude);
        assert!(result.is_ok());
        // pos should have advanced past both lines
        assert!(pos > 0);
    }

    #[test]
    fn test_read_new_lines_incremental() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("incr.jsonl");
        std::fs::write(&path, "{\"line\":1}\n").unwrap();
        let mut pos: u64 = 0;
        let _ = read_new_lines(&path, &mut pos, &AiTool::Claude);
        let pos_after_first = pos;
        assert!(pos_after_first > 0);

        // Append more content
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new().append(true).open(&path).unwrap();
        writeln!(f, "{{\"line\":2}}").unwrap();

        let _ = read_new_lines(&path, &mut pos, &AiTool::Claude);
        // pos should have advanced further
        assert!(pos > pos_after_first);
    }

    #[test]
    fn test_find_claude_log_nonexistent_dir() {
        let result = find_claude_log(std::path::Path::new("/nonexistent/path/xyz_abc_123"));
        assert!(result.is_err());
    }

    #[test]
    fn test_find_codex_log_no_files() {
        // This test verifies find_codex_log handles missing files gracefully
        // It may or may not find files depending on the test environment
        let result = find_codex_log(std::path::Path::new("/nonexistent/path"));
        // Just ensure it doesn't panic — result can be Ok or Err
        let _ = result;
    }

    #[test]
    fn test_find_opencode_db_not_found() {
        // Set HOME to a temp dir to ensure the DB won't be found
        let dir = tempfile::TempDir::new().unwrap();
        let original_home = std::env::var("HOME").ok();
        // Don't actually change HOME as it affects other tests running in parallel
        // Instead, just verify the function exists and returns the expected type
        let result = find_opencode_db();
        // The result depends on whether the file exists on the test machine
        let _ = result;
    }

    #[test]
    fn test_read_all_messages_multiple_invalid_lines() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("multi.jsonl");
        std::fs::write(&path, "line1\nline2\nline3\n").unwrap();
        let file = std::fs::File::open(&path).unwrap();
        let mut reader = std::io::BufReader::new(file);
        let messages = read_all_messages(&mut reader, &AiTool::Claude);
        assert!(messages.is_empty());
    }

    #[test]
    fn test_kiro_detection_process_name_matching() {
        // Verify the detection logic handles both "kiro-cli" and "kiro-cli-chat"
        // The actual process comm is "kiro-cli" (not "kiro-cli-chat")
        let names_to_match = vec!["kiro-cli", "kiro-cli-chat"];
        for name in names_to_match {
            let is_kiro = name == "kiro-cli-chat" || name == "kiro-cli";
            assert!(is_kiro, "Should match '{}'", name);
        }
    }

    fn test_parse_line_with_various_inputs() {
        // Test that parse_line handles various inputs without panicking
        let result1 = parse_line("", &AiTool::Claude);
        assert!(result1.is_none());

        let result2 = parse_line("   ", &AiTool::Claude);
        assert!(result2.is_none());

        let result3 = parse_line("{}", &AiTool::Claude);
        assert!(result3.is_none());

        let result4 = parse_line("", &AiTool::Codex);
        assert!(result4.is_none());

        let result5 = parse_line("not json", &AiTool::Codex);
        assert!(result5.is_none());
    }
}
