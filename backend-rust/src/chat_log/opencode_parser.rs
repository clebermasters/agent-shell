use anyhow::{bail, Context, Result};
use chrono::{TimeZone, Utc};
use rusqlite::Connection;
use serde::Deserialize;
use serde_json::Value;
use std::path::Path;
use tracing::{debug, error, warn};

use super::{ChatMessage, ContentBlock};

use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct OpencodeState {
    #[allow(dead_code)]
    pub pid: u32,
    pub session_id: String,
    pub last_time_updated: i64,
    pub cleared_at: Option<i64>,
    pub seen_text_lengths: HashMap<String, usize>,
    pub seen_tool_calls: HashSet<String>,
    pub seen_tool_results: HashSet<String>,
}

#[derive(Deserialize, Debug)]
struct PartData {
    #[serde(rename = "type")]
    part_type: String,

    // For text parts
    text: Option<String>,

    // For tool parts
    tool: Option<String>,
    state: Option<ToolState>,
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct ToolState {
    status: Option<String>,
    input: Option<Value>,
    output: Option<Value>,
}

/// Start an opencode session tracking state
/// Uses process CWD and tries to match by recent activity
pub fn init_opencode_state(
    db_path: &Path,
    directory: &Path,
    pid: u32,
    cleared_at: Option<i64>,
) -> Result<OpencodeState> {
    let conn = Connection::open(db_path)?;
    // Set a busy timeout
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    let dir_str = directory.to_str().context("invalid directory path")?;

    debug!(
        "Looking for session in {} for PID {} (cleared_at: {:?})",
        dir_str, pid, cleared_at
    );

    // Get process start time to help match the right session
    let process_start_time = get_process_start_time(pid)?;
    debug!(
        "Process {} started at boot tick {}",
        pid, process_start_time
    );

    // Get process uptime - we'll use this to calculate actual start time
    let process_uptime_ms = get_process_uptime_ms(pid)?;
    debug!("Process {} uptime: {} ms", pid, process_uptime_ms);

    // Calculate approximate start time in epoch milliseconds
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let process_start_epoch_ms = now - process_uptime_ms;
    debug!(
        "Process {} estimated start epoch ms: {}",
        pid, process_start_epoch_ms
    );

    // Find session that was created around when the process started.
    // We prefer the MOST RECENTLY UPDATED session created within a window of the process start
    // because OpenCode often creates a "Greeting" session first, then the real one.
    // The real session will have more activity and a later time_updated.
    let mut stmt = conn.prepare(
        "SELECT id, time_created, time_updated FROM session 
         WHERE directory = ? AND time_created >= ? - 5000 AND time_created <= ? + 60000
         ORDER BY time_updated DESC LIMIT 1",
    )?;

    let result: Result<(String, i64, i64), _> = stmt.query_row(
        rusqlite::params![dir_str, process_start_epoch_ms, process_start_epoch_ms],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    );

    let (session_id, _time_created, time_updated) = match result {
        Ok(data) => {
            debug!(
                "Matched session {} (created: {}, updated: {}) to PID {} (started: {})",
                data.0, data.1, data.2, pid, process_start_epoch_ms
            );
            data
        }
        Err(_) => {
            // Fallback: get the most recently updated session
            debug!(
                "Could not match by start time window, falling back to most recent for directory"
            );
            let mut stmt = conn.prepare(
                "SELECT id, time_created, time_updated FROM session 
                 WHERE directory = ? 
                 ORDER BY time_updated DESC LIMIT 1",
            )?;
            stmt.query_row([dir_str], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
                .context(format!(
                    "No opencode session found for directory: {}",
                    dir_str
                ))?
        }
    };

    debug!(
        "Found Opencode session: {} (updated: {}) for PID {} (cwd: {})",
        session_id, time_updated, pid, dir_str
    );

    Ok(OpencodeState {
        pid,
        session_id,
        last_time_updated: 0, // Start from beginning to get full history
        cleared_at,
        seen_text_lengths: HashMap::new(),
        seen_tool_calls: HashSet::new(),
        seen_tool_results: HashSet::new(),
    })
}

/// Get process start time in clock ticks since boot
fn get_process_start_time(pid: u32) -> Result<i64> {
    use std::fs;

    let stat_path = format!("/proc/{}/stat", pid);
    let stat =
        fs::read_to_string(&stat_path).with_context(|| format!("failed to read {}", stat_path))?;

    let parts: Vec<&str> = stat.split_whitespace().collect();
    if parts.len() < 22 {
        bail!("invalid stat format for PID {}", pid);
    }

    let start_ticks: i64 = parts[21]
        .parse()
        .with_context(|| format!("failed to parse start time from {}", stat_path))?;

    Ok(start_ticks)
}

/// Get process uptime in milliseconds
fn get_process_uptime_ms(pid: u32) -> Result<i64> {
    use std::fs;

    // Read /proc/<pid>/stat to get start time
    let stat_path = format!("/proc/{}/stat", pid);
    let stat =
        fs::read_to_string(&stat_path).with_context(|| format!("failed to read {}", stat_path))?;

    let parts: Vec<&str> = stat.split_whitespace().collect();
    if parts.len() < 22 {
        bail!("invalid stat format for PID {}", pid);
    }

    let start_ticks: u64 = parts[21]
        .parse()
        .with_context(|| format!("failed to parse start time from {}", stat_path))?;

    // Read /proc/uptime to get system uptime
    let uptime_path = "/proc/uptime";
    let uptime_str = fs::read_to_string(uptime_path)
        .with_context(|| format!("failed to read {}", uptime_path))?;

    let uptime_seconds: f64 = uptime_str
        .split_whitespace()
        .next()
        .unwrap_or("0")
        .parse()
        .unwrap_or(0.0);

    // Get clock ticks per second
    let clk_tck = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };

    // Calculate process uptime = system_uptime - (start_time_in_clock_ticks / clk_tck)
    let start_seconds = start_ticks as f64 / clk_tck as f64;
    let uptime = uptime_seconds - start_seconds;

    Ok((uptime * 1000.0) as i64)
}

/// Fetch all new messages since the last fetch
pub fn fetch_new_messages(db_path: &Path, state: &mut OpencodeState) -> Result<Vec<ChatMessage>> {
    let conn = Connection::open(db_path)?;
    // Set a busy timeout to avoid hanging if the DB is locked by another process (like OpenCode)
    conn.busy_timeout(std::time::Duration::from_secs(5))?;

    // Calculate the minimum timestamp to fetch from
    // Use cleared_at if it's newer than last_time_updated
    let min_time = match state.cleared_at {
        Some(cleared) if cleared > state.last_time_updated => cleared,
        _ => state.last_time_updated,
    };

    debug!(
        "Fetching messages for session {} since time {} (cleared_at: {:?})",
        state.session_id, min_time, state.cleared_at
    );

    let mut stmt = conn.prepare(
        "SELECT p.id, p.data, p.time_updated, json_extract(m.data, '$.role') 
         FROM part p
         JOIN message m ON p.message_id = m.id
         WHERE p.session_id = ? AND p.time_updated > ?
         ORDER BY p.time_updated ASC",
    )?;

    let mut rows = match stmt.query(rusqlite::params![state.session_id, min_time]) {
        Ok(r) => r,
        Err(e) => {
            error!(
                "Failed to query opencode messages for session {}: {}",
                state.session_id, e
            );
            return Err(e.into());
        }
    };

    let mut messages = Vec::new();
    let mut new_last_time_updated = state.last_time_updated;

    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        let data: String = row.get(1)?;
        let time_updated: i64 = row.get(2)?;
        let parsed_role: Option<String> = row.get(3)?;

        let role = parsed_role.unwrap_or_else(|| "assistant".to_string());

        if time_updated > new_last_time_updated {
            new_last_time_updated = time_updated;
        }

        match serde_json::from_str::<PartData>(&data) {
            Ok(part) => {
                if let Some(msg) = parse_part(&id, &part, time_updated, state, &role) {
                    messages.push(msg);
                }
            }
            Err(e) => {
                warn!(
                    "Failed to deserialize opencode part data: {} - error: {}",
                    data, e
                );
            }
        }
    }

    state.last_time_updated = new_last_time_updated;
    Ok(messages)
}

/// Read all messages for a session from the OpenCode DB.
/// Returns (messages, max_time_updated). max_time_updated is 0 if no messages.
/// Respects cleared_at: messages with time_updated <= cleared_at are excluded.
pub fn fetch_all_messages(
    db_path: &Path,
    session_id: &str,
    cleared_at: Option<i64>,
) -> Result<(Vec<ChatMessage>, i64)> {
    let conn = Connection::open(db_path)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;

    let min_time = cleared_at.unwrap_or(0);

    let mut stmt = conn.prepare(
        "SELECT p.id, p.data, p.time_updated, json_extract(m.data, '$.role')
         FROM part p
         JOIN message m ON p.message_id = m.id
         WHERE p.session_id = ? AND p.time_updated > ?
         ORDER BY p.time_updated ASC",
    )?;

    let mut rows = stmt.query(rusqlite::params![session_id, min_time])?;

    // Reuse OpencodeState dedup logic
    let mut state = OpencodeState {
        pid: 0,
        session_id: session_id.to_string(),
        last_time_updated: min_time,
        cleared_at,
        seen_text_lengths: std::collections::HashMap::new(),
        seen_tool_calls: std::collections::HashSet::new(),
        seen_tool_results: std::collections::HashSet::new(),
    };

    let mut messages = Vec::new();
    let mut max_time_updated: i64 = 0;

    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        let data: String = row.get(1)?;
        let time_updated: i64 = row.get(2)?;
        let role: Option<String> = row.get(3)?;
        let role = role.unwrap_or_else(|| "assistant".to_string());

        if time_updated > max_time_updated {
            max_time_updated = time_updated;
        }

        if let Ok(part) = serde_json::from_str::<PartData>(&data) {
            if let Some(msg) = parse_part(&id, &part, time_updated, &mut state, &role) {
                messages.push(msg);
            }
        }
    }

    Ok((messages, max_time_updated))
}

fn parse_part(
    id: &str,
    part: &PartData,
    time_updated: i64,
    state: &mut OpencodeState,
    message_role: &str,
) -> Option<ChatMessage> {
    let timestamp = Utc.timestamp_millis_opt(time_updated).single();

    let mut final_role = message_role.to_string();
    if part.part_type != "text" {
        final_role = "tool".to_string();
    }

    let block = match part.part_type.as_str() {
        "text" => {
            let full_text = part.text.as_deref().unwrap_or_default();
            let (thinking, regular) = extract_thinking(full_text);

            let mut blocks = Vec::new();

            if !thinking.is_empty() {
                let key = format!("thinking_{}", id);
                let last_len = state.seen_text_lengths.get(&key).copied().unwrap_or(0);
                if thinking.len() > last_len {
                    blocks.push(ContentBlock::Thinking {
                        content: thinking[last_len..].to_string(),
                    });
                    state.seen_text_lengths.insert(key, thinking.len());
                }
            }

            if !regular.is_empty() {
                let last_len = state.seen_text_lengths.get(id).copied().unwrap_or(0);
                if regular.len() > last_len {
                    blocks.push(ContentBlock::Text {
                        text: regular[last_len..].to_string(),
                    });
                    state.seen_text_lengths.insert(id.to_string(), regular.len());
                }
            }

            if blocks.is_empty() {
                return None;
            }

            return Some(ChatMessage {
                role: final_role,
                timestamp,
                blocks,
            });
        }
        "reasoning" => {
            // Extract thinking/reasoning content from AI models
            let content = part.text.as_deref().unwrap_or_default();
            let last_len = state
                .seen_text_lengths
                .get(&format!("reasoning_{}", id))
                .copied()
                .unwrap_or(0);

            if content.len() <= last_len {
                return None;
            }

            let new_chunk = &content[last_len..];
            state
                .seen_text_lengths
                .insert(format!("reasoning_{}", id), content.len());

            ContentBlock::Thinking {
                content: new_chunk.to_string(),
            }
        }
        "tool" => {
            let status = part
                .state
                .as_ref()
                .and_then(|s| s.status.as_deref())
                .unwrap_or("unknown");
            let tool_name = part.tool.clone().unwrap_or_else(|| "unknown".to_string());

            if status != "completed" {
                if !state.seen_tool_calls.insert(id.to_string()) {
                    return None; // already announced this running tool
                }

                let input = part.state.as_ref().and_then(|s| s.input.clone());
                ContentBlock::ToolCall {
                    name: tool_name.clone(),
                    summary: format!("Calling tool {}", tool_name),
                    input,
                }
            } else {
                if !state.seen_tool_results.insert(id.to_string()) {
                    return None; // already announced this completed result
                }

                let content_str = match part.state.as_ref().and_then(|s| s.output.as_ref()) {
                    Some(Value::String(s)) => Some(s.clone()),
                    Some(v) => Some(v.to_string()),
                    None => None,
                };

                ContentBlock::ToolResult {
                    tool_name: tool_name.clone(),
                    summary: format!("Tool {} finished", tool_name),
                    content: content_str,
                }
            }
        }
        _ => return None, // e.g., patch, snapshot, reasoning, etc. we skip formatting those as simple chat events for now
    };

    Some(ChatMessage {
        role: final_role,
        timestamp,
        blocks: vec![block],
    })
}

/// Split text into (thinking_content, regular_content) by extracting <think>...</think> tags.
/// Handles unclosed tags (still streaming) by treating the remainder as thinking.
fn extract_thinking(text: &str) -> (String, String) {
    let mut thinking = String::new();
    let mut regular = String::new();
    let mut remaining = text;

    while let Some(start) = remaining.find("<think>") {
        regular.push_str(&remaining[..start]);
        remaining = &remaining[start + 7..];
        if let Some(end) = remaining.find("</think>") {
            thinking.push_str(&remaining[..end]);
            remaining = &remaining[end + 8..];
        } else {
            thinking.push_str(remaining);
            remaining = "";
            break;
        }
    }
    regular.push_str(remaining);

    (thinking.trim().to_string(), regular.trim().to_string())
}

/// List all sessions from the OpenCode SQLite DB, across all projects.
/// Returns sessions sorted by time_updated descending.
pub fn list_all_sessions(db_path: &Path) -> Result<Vec<crate::acp::session::SessionInfo>> {
    let conn = Connection::open(db_path)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;

    let mut stmt = conn.prepare(
        "SELECT id, directory, title, time_updated FROM session \
         WHERE parent_id IS NULL \
         ORDER BY time_updated DESC \
         LIMIT 500"
    )?;

    let sessions = stmt.query_map([], |row| {
        let id: String = row.get(0)?;
        let directory: String = row.get(1)?;
        let title: String = row.get(2).unwrap_or_default();
        let time_updated: i64 = row.get(3)?;
        Ok((id, directory, title, time_updated))
    })?
    .filter_map(|r| r.ok())
    .map(|(id, directory, title, time_updated)| {
        let updated_at = chrono::DateTime::from_timestamp_millis(time_updated)
            .unwrap_or_default()
            .to_rfc3339();
        crate::acp::session::SessionInfo {
            session_id: id,
            cwd: directory,
            title,
            updated_at,
        }
    })
    .collect();

    Ok(sessions)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_extract_thinking_no_tags() {
        let (thinking, regular) = extract_thinking("Hello world");
        assert_eq!(thinking, "");
        assert_eq!(regular, "Hello world");
    }

    #[test]
    fn test_extract_thinking_with_tag() {
        let text = "Before<think>internal thought</think>After";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "internal thought");
        assert_eq!(regular, "BeforeAfter");
    }

    #[test]
    fn test_extract_thinking_multiple_blocks() {
        let text = "A<think>t1</think>B<think>t2</think>C";
        let (thinking, regular) = extract_thinking(text);
        assert!(thinking.contains("t1") || thinking.contains("t2"));
        assert!(regular.contains("A") && regular.contains("B") && regular.contains("C"));
    }

    #[test]
    fn test_extract_thinking_unclosed_tag() {
        let text = "Before<think>unclosed";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "unclosed");
        assert_eq!(regular, "Before");
    }

    #[test]
    fn test_get_process_start_time_self() {
        let pid = std::process::id();
        let result = get_process_start_time(pid);
        assert!(result.is_ok());
        assert!(result.unwrap() >= 0);
    }

    #[test]
    fn test_get_process_start_time_nonexistent() {
        let result = get_process_start_time(999999999);
        assert!(result.is_err());
    }

    #[test]
    fn test_get_process_uptime_ms_self() {
        let pid = std::process::id();
        let result = get_process_uptime_ms(pid);
        assert!(result.is_ok());
        assert!(result.unwrap() >= 0);
    }

    #[test]
    fn test_get_process_uptime_ms_nonexistent() {
        let result = get_process_uptime_ms(999999999);
        assert!(result.is_err());
    }

    #[test]
    fn test_list_all_sessions_nonexistent_db() {
        let dir = TempDir::new().unwrap();
        let db_path = dir.path().join("nonexistent.db");
        // Should fail because DB doesn't exist / has no tables
        let _ = list_all_sessions(&db_path); // May succeed or fail — just shouldn't panic
    }

    #[test]
    fn test_list_all_sessions_empty_db() {
        let dir = TempDir::new().unwrap();
        let db_path = dir.path().join("test.db");
        // Create a valid SQLite DB with the session table
        let conn = rusqlite::Connection::open(&db_path).unwrap();
        conn.execute_batch(
            "CREATE TABLE session (id TEXT, directory TEXT, title TEXT, time_updated INTEGER, parent_id TEXT);"
        ).unwrap();
        drop(conn);
        let result = list_all_sessions(&db_path).unwrap();
        assert!(result.is_empty());
    }

    fn make_state() -> OpencodeState {
        OpencodeState {
            pid: 0,
            session_id: "test-session".to_string(),
            last_time_updated: 0,
            cleared_at: None,
            seen_text_lengths: HashMap::new(),
            seen_tool_calls: HashSet::new(),
            seen_tool_results: HashSet::new(),
        }
    }

    #[test]
    fn test_parse_part_text_new() {
        let mut state = make_state();
        let part = PartData {
            part_type: "text".to_string(),
            text: Some("Hello world".to_string()),
            tool: None,
            state: None,
        };
        let result = parse_part("id1", &part, 1000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        assert_eq!(msg.role, "assistant");
        assert!(!msg.blocks.is_empty());
        match &msg.blocks[0] {
            ContentBlock::Text { text } => assert_eq!(text, "Hello world"),
            _ => panic!("Expected Text block"),
        }
    }

    #[test]
    fn test_parse_part_text_incremental() {
        let mut state = make_state();
        let part1 = PartData {
            part_type: "text".to_string(),
            text: Some("Hello".to_string()),
            tool: None,
            state: None,
        };
        let _ = parse_part("id1", &part1, 1000, &mut state, "assistant");

        // Same ID with longer text — should return only the delta
        let part2 = PartData {
            part_type: "text".to_string(),
            text: Some("Hello world".to_string()),
            tool: None,
            state: None,
        };
        let result = parse_part("id1", &part2, 2000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        match &msg.blocks[0] {
            ContentBlock::Text { text } => assert!(text.contains("world")), // delta contains new content
            _ => panic!("Expected Text block"),
        }
    }

    #[test]
    fn test_parse_part_text_no_change() {
        let mut state = make_state();
        let part = PartData {
            part_type: "text".to_string(),
            text: Some("Same text".to_string()),
            tool: None,
            state: None,
        };
        let _ = parse_part("id1", &part, 1000, &mut state, "assistant");
        // Same length text — should return None
        let result = parse_part("id1", &part, 2000, &mut state, "assistant");
        assert!(result.is_none());
    }

    #[test]
    fn test_parse_part_text_with_thinking() {
        let mut state = make_state();
        let part = PartData {
            part_type: "text".to_string(),
            text: Some("Before<think>internal thought</think>After".to_string()),
            tool: None,
            state: None,
        };
        let result = parse_part("id1", &part, 1000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        // Should have both Thinking and Text blocks
        assert!(msg.blocks.len() >= 2);
        let has_thinking = msg.blocks.iter().any(|b| matches!(b, ContentBlock::Thinking { .. }));
        let has_text = msg.blocks.iter().any(|b| matches!(b, ContentBlock::Text { .. }));
        assert!(has_thinking);
        assert!(has_text);
    }

    #[test]
    fn test_parse_part_tool_running() {
        let mut state = make_state();
        let part = PartData {
            part_type: "tool".to_string(),
            text: None,
            tool: Some("bash".to_string()),
            state: Some(ToolState {
                status: Some("running".to_string()),
                input: Some(Value::String("ls -la".to_string())),
                output: None,
            }),
        };
        let result = parse_part("tool1", &part, 1000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        assert_eq!(msg.role, "tool");
        match &msg.blocks[0] {
            ContentBlock::ToolCall { name, .. } => assert_eq!(name, "bash"),
            _ => panic!("Expected ToolCall block"),
        }
    }

    #[test]
    fn test_parse_part_tool_completed() {
        let mut state = make_state();
        let part = PartData {
            part_type: "tool".to_string(),
            text: None,
            tool: Some("bash".to_string()),
            state: Some(ToolState {
                status: Some("completed".to_string()),
                input: None,
                output: Some(Value::String("file.txt\n".to_string())),
            }),
        };
        let result = parse_part("tool2", &part, 1000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        match &msg.blocks[0] {
            ContentBlock::ToolResult { tool_name, .. } => assert_eq!(tool_name, "bash"),
            _ => panic!("Expected ToolResult block"),
        }
    }

    #[test]
    fn test_parse_part_tool_dedup() {
        let mut state = make_state();
        let part = PartData {
            part_type: "tool".to_string(),
            text: None,
            tool: Some("bash".to_string()),
            state: Some(ToolState {
                status: Some("running".to_string()),
                input: None,
                output: None,
            }),
        };
        let result1 = parse_part("tool3", &part, 1000, &mut state, "assistant");
        assert!(result1.is_some());
        // Same tool ID again — should be deduped
        let result2 = parse_part("tool3", &part, 2000, &mut state, "assistant");
        assert!(result2.is_none());
    }

    #[test]
    fn test_parse_part_reasoning() {
        let mut state = make_state();
        let part = PartData {
            part_type: "reasoning".to_string(),
            text: Some("Let me think about this...".to_string()),
            tool: None,
            state: None,
        };
        let result = parse_part("reason1", &part, 1000, &mut state, "assistant");
        assert!(result.is_some());
        let msg = result.unwrap();
        match &msg.blocks[0] {
            ContentBlock::Thinking { content } => assert_eq!(content, "Let me think about this..."),
            _ => panic!("Expected Thinking block"),
        }
    }

    #[test]
    fn test_fetch_all_messages_empty_db() {
        let dir = TempDir::new().unwrap();
        let db_path = dir.path().join("test_fetch.db");
        let conn = rusqlite::Connection::open(&db_path).unwrap();
        conn.execute_batch(
            "CREATE TABLE message (id TEXT, data TEXT);
             CREATE TABLE part (id TEXT, message_id TEXT, session_id TEXT, data TEXT, time_updated INTEGER);"
        ).unwrap();
        drop(conn);
        let (messages, max_time) = fetch_all_messages(&db_path, "test-session", None).unwrap();
        assert!(messages.is_empty());
        assert_eq!(max_time, 0);
    }
}
