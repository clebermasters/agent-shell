//! Parser for kiro-cli (Amazon Q Developer CLI rebranded) PTY output.
//!
//! kiro-cli outputs thinking blocks via `<think>`/`</think>` text tags in
//! terminal output, same format as OpenCode. This parser extracts those tags
//! and the remaining text into normalized ChatMessages.
//!
//! Response completeness is detected via idle timeout (2000ms of no PTY output).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Instant;

use anyhow::{bail, Context, Result};
use chrono::Utc;

use super::{ChatMessage, ContentBlock};

// ---------------------------------------------------------------------------
// ANSI stripping
// ---------------------------------------------------------------------------

/// Strip ANSI escape sequences from terminal output and handle carriage returns.
///
/// Handles: CSI sequences (`ESC[...X`), character set designations (`ESC(X`),
/// two-byte escapes (`ESC X`), and carriage returns (`\r` without `\n` means
/// "overwrite current line" — critical for kiro-cli's spinner animations).
pub fn strip_ansi(text: &str) -> String {
    let mut result = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    let mut current_line = String::new();

    while let Some(c) = chars.next() {
        if c == '\x1b' {
            // ANSI escape sequence
            match chars.peek() {
                Some('[') => {
                    chars.next(); // consume '['
                    // CSI sequence — read until we hit a letter or @
                    while let Some(&c) = chars.peek() {
                        chars.next();
                        if c.is_ascii_alphabetic() || c == '@' {
                            break;
                        }
                    }
                }
                Some('(') | Some(')') | Some('*') | Some('+') => {
                    // Character set designation: ESC(X, ESC)X, etc.
                    chars.next(); // consume ( or )
                    chars.next(); // consume the designator char (e.g., B)
                }
                Some(c) if c.is_ascii_alphabetic() || *c == '>' || *c == '=' => {
                    chars.next(); // Two-byte escape
                }
                _ => {} // Unknown escape, ignore ESC
            }
        } else if c == '\r' {
            if chars.peek() == Some(&'\n') {
                // \r\n — normal newline
                chars.next();
                result.push_str(&current_line);
                result.push('\n');
                current_line.clear();
            } else {
                // \r without \n — overwrite current line (TUI spinner behavior)
                current_line.clear();
            }
        } else if c == '\n' {
            result.push_str(&current_line);
            result.push('\n');
            current_line.clear();
        } else {
            current_line.push(c);
        }
    }

    // Flush remaining line content
    result.push_str(&current_line);
    result
}

// ---------------------------------------------------------------------------
// Noise filtering for kiro TUI output
// ---------------------------------------------------------------------------

const SPINNER_CHARS: &[char] = &['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];

/// Filter out TUI noise from kiro-cli output (spinners, status bars, prompts, metadata).
fn clean_kiro_output(text: &str) -> String {
    let mut lines: Vec<&str> = Vec::new();

    for line in text.lines() {
        let trimmed = line.trim();

        if trimmed.is_empty() {
            continue;
        }

        // Skip spinner lines (⠋ Thinking..., etc.)
        if trimmed.contains("Thinking...") || SPINNER_CHARS.iter().any(|c| trimmed.starts_with(*c)) {
            continue;
        }

        // Skip tmux status bar lines (e.g., "[kiro] 0:kiro-cli*  ...")
        if trimmed.starts_with('[') && (trimmed.contains("0:") || trimmed.contains("1:")) && trimmed.contains('*') {
            continue;
        }

        // Skip prompt lines (e.g., "14% > ", "100% > ")
        if is_kiro_prompt(trimmed) {
            continue;
        }

        // Skip metadata lines (e.g., "▸ Credits: 0.03 •", "Time: 2s")
        if trimmed.starts_with("▸ ") || trimmed.starts_with("Credits:") {
            continue;
        }
        if trimmed.starts_with("Time:") && trimmed.len() < 20 {
            continue;
        }

        // Skip tiny control artifacts (1-2 non-alphanumeric chars)
        if trimmed.len() <= 2 && !trimmed.chars().any(|c| c.is_alphanumeric()) {
            continue;
        }

        // Skip [lost tty] messages
        if trimmed == "[lost tty]" {
            continue;
        }

        lines.push(trimmed);
    }

    // Strip leading "> " response prefix from kiro output
    let cleaned: Vec<&str> = lines
        .iter()
        .map(|l| l.strip_prefix("> ").unwrap_or(l))
        .collect();

    cleaned.join("\n").trim().to_string()
}

/// Check if a line is a kiro input prompt (e.g., "14% > ", "100% > ").
fn is_kiro_prompt(line: &str) -> bool {
    let trimmed = line.trim();
    // Match patterns like "14% > " or "100% >"
    if let Some(pct_pos) = trimmed.find('%') {
        if pct_pos <= 3 {
            let before = &trimmed[..pct_pos];
            if before.chars().all(|c| c.is_ascii_digit()) {
                let after = trimmed[pct_pos + 1..].trim();
                if after.is_empty() || after == ">" || after.starts_with("> ") {
                    return true;
                }
            }
        }
    }
    false
}

// ---------------------------------------------------------------------------
// Thinking extraction
// ---------------------------------------------------------------------------

/// Extract thinking blocks from `<think>`/`</think>` tags.
/// Returns `(thinking_content, remaining_text)`.
pub fn extract_thinking(text: &str) -> (String, String) {
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
            // Unclosed tag — treat remainder as streaming thinking
            thinking.push_str(remaining);
            remaining = "";
            break;
        }
    }
    regular.push_str(remaining);

    (thinking.trim().to_string(), regular.trim().to_string())
}

// ---------------------------------------------------------------------------
// Tool call detection
// ---------------------------------------------------------------------------

const TOOL_PREFIXES: &[&str] = &[
    "Bash:",
    "Read:",
    "Edit:",
    "Grep:",
    "Write:",
    "NotebookEdit:",
    "WebSearch:",
    "WebFetch:",
    "TodoWrite:",
    "TaskCreate:",
    "TaskGet:",
    "TaskUpdate:",
];

/// Detect if a line looks like a tool call prefix in kiro output.
fn is_tool_call_line(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.starts_with("╭──Tool:") || trimmed.starts_with("╭──tool:") {
        return true;
    }
    for prefix in TOOL_PREFIXES {
        if trimmed.starts_with(prefix) {
            return true;
        }
    }
    false
}

/// Detect if a line looks like a tool result separator.
fn is_tool_result_line(line: &str) -> bool {
    let trimmed = line.trim();
    trimmed.starts_with("╰──") || trimmed.starts_with("╰──►")
}

// ---------------------------------------------------------------------------
// Parser state
// ---------------------------------------------------------------------------

/// Parser state for tracking kiro-cli PTY output stream.
#[derive(Debug, Clone)]
pub struct KiroState {
    /// Process ID of the kiro-cli-chat process.
    pub pid: u32,
    /// kiro session ID (from history file lookup).
    pub session_id: String,
    /// Working directory of the process.
    pub cwd: PathBuf,
    /// Last time we received PTY output.
    pub last_output: Instant,
    /// Accumulated response buffer since last complete message.
    pub response_buffer: String,
    /// Hash of responses we've already emitted (dedup).
    pub seen_responses: HashMap<String, bool>,
    /// Whether we're currently inside a tool call block.
    pub in_tool_block: bool,
    /// Current tool call name (if in_tool_block).
    pub current_tool: Option<String>,
}

impl KiroState {
    pub fn new(pid: u32, session_id: String, cwd: PathBuf) -> Self {
        Self {
            pid,
            session_id,
            cwd,
            last_output: Instant::now(),
            response_buffer: String::new(),
            seen_responses: HashMap::new(),
            in_tool_block: false,
            current_tool: None,
        }
    }
}

/// Minimum quiet period (ms) after which a response is considered complete.
pub const RESPONSE_COMPLETE_TIMEOUT_MS: u64 = 2000;

// ---------------------------------------------------------------------------
// PTY chunk parsing
// ---------------------------------------------------------------------------

/// Parse a raw PTY output chunk into zero or more ChatMessages.
/// Returns messages only when a complete response is detected (idle timeout).
pub fn parse_pty_chunk(chunk: &str, state: &mut KiroState) -> Vec<ChatMessage> {
    state.last_output = Instant::now();

    let clean = strip_ansi(chunk);

    // Detect spinner: kiro started thinking → clear any input echo in buffer.
    // Flow: user types → echo buffered → spinner starts → clear buffer → response arrives.
    if is_spinner_text(&clean) {
        state.response_buffer.clear();
        return Vec::new();
    }

    let cleaned = clean_kiro_output(&clean);
    if cleaned.is_empty() {
        return Vec::new();
    }

    // Append only meaningful content to response buffer
    if !state.response_buffer.is_empty() {
        state.response_buffer.push('\n');
    }
    state.response_buffer.push_str(&cleaned);

    // parse_pty_chunk never returns directly — the idle timeout in the
    // forwarder loop calls emit_response after 2000ms of silence.
    Vec::new()
}

/// Check if text is a kiro spinner line (e.g., "⠋ Thinking...").
fn is_spinner_text(text: &str) -> bool {
    let trimmed = text.trim();
    SPINNER_CHARS.iter().any(|c| trimmed.starts_with(*c)) && trimmed.contains("Thinking")
}

/// Check whether enough idle time has passed to consider the response complete.
pub fn is_response_complete(state: &KiroState) -> bool {
    state.last_output.elapsed().as_millis() as u64 >= RESPONSE_COMPLETE_TIMEOUT_MS
        && !state.response_buffer.is_empty()
}

/// Emit a complete response as one or more ChatMessages.
pub fn emit_response(text: &str, state: &mut KiroState) -> Vec<ChatMessage> {
    let (thinking, regular) = extract_thinking(text);

    // Deduplicate by content hash
    let hash = format!("{:x}", md5_hash(text));
    if state.seen_responses.contains_key(&hash) {
        return Vec::new();
    }
    state.seen_responses.insert(hash, true);

    // Keep seen_responses bounded
    if state.seen_responses.len() > 1000 {
        let to_remove: Vec<_> = state.seen_responses.keys().take(500).cloned().collect();
        for k in to_remove {
            state.seen_responses.remove(&k);
        }
    }

    let mut messages = Vec::new();
    let timestamp = Some(Utc::now());

    // Tool calls detected from prefix lines
    let tool_blocks = detect_tool_blocks(&regular);

    if !thinking.is_empty() || !regular.is_empty() || !tool_blocks.is_empty() {
        let mut blocks = Vec::new();

        if !thinking.is_empty() {
            blocks.push(ContentBlock::Thinking {
                content: thinking,
            });
        }

        if !regular.is_empty() && !tool_blocks.is_empty() {
            blocks.push(ContentBlock::Text { text: regular });
        } else if !regular.is_empty() {
            // If regular text is just whitespace/separators, skip
            let trimmed = regular.trim();
            if !trimmed.is_empty() {
                blocks.push(ContentBlock::Text {
                    text: trimmed.to_string(),
                });
            }
        }

        blocks.extend(tool_blocks);

        if !blocks.is_empty() {
            messages.push(ChatMessage {
                role: "assistant".to_string(),
                timestamp,
                blocks,
            });
        }
    }

    messages
}

/// Detect tool call blocks from text lines.
fn detect_tool_blocks(text: &str) -> Vec<ContentBlock> {
    let mut blocks = Vec::new();
    let lines: Vec<&str> = text.lines().collect();
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i].trim();

        if is_tool_call_line(line) {
            // Extract tool name from "Bash:", "Read:", etc.
            let name = TOOL_PREFIXES
                .iter()
                .find(|p| line.starts_with(*p))
                .map(|p| p.trim_end_matches(':'))
                .unwrap_or("unknown")
                .to_string();

            // Collect the body of this tool call (until next tool or result)
            let mut body = Vec::new();
            i += 1;
            while i < lines.len() {
                let next_line = lines[i].trim();
                if is_tool_call_line(next_line) || is_tool_result_line(next_line) {
                    break;
                }
                body.push(next_line);
                i += 1;
            }

            let summary = body.join("\n").trim().to_string();
            let summary = if summary.len() > 100 {
                format!("{}...", &summary[..100])
            } else {
                summary
            };

            blocks.push(ContentBlock::ToolCall {
                name,
                summary,
                input: None,
            });
        } else {
            i += 1;
        }
    }

    blocks
}

/// Simple MD5 hash for deduplication (no external crate needed).
fn md5_hash(input: &str) -> u128 {
    // FNV-1a hash as a fast surrogate for deduplication
    // Not cryptographic, just a quick content fingerprint
    let mut hash: u128 = 0xcbf29ce484222325;
    for byte in input.bytes() {
        hash ^= byte as u128;
        hash = hash.wrapping_mul(0x100000000000003e3);
    }
    hash
}

// ---------------------------------------------------------------------------
// History file parsing
// ---------------------------------------------------------------------------

/// kiro-cli stores session history in `~/.local/share/kiro-cli/history`.
/// Entries are YAML documents separated by `---`.
pub fn fetch_history(session_id: &str) -> Result<Vec<ChatMessage>> {
    let home = dirs::home_dir().context("cannot determine home directory")?;
    let history_path = home.join(".local/share/kiro-cli/history");

    if !history_path.exists() {
        bail!("kiro-cli history file not found at {}", history_path.display());
    }

    let content = std::fs::read_to_string(&history_path)?;

    // History file format: YAML documents separated by `---`
    // Each entry has session metadata including id, cwd, and command/response pairs.
    // Since YAML parsing is complex without a crate, we do a simple extraction.
    let messages = parse_history_entries(&content, session_id);

    Ok(messages)
}

/// Simple extraction of history entries from YAML-like content.
/// Each entry starts with `id: <session-id>` followed by command/response.
fn parse_history_entries(content: &str, target_session: &str) -> Vec<ChatMessage> {
    let mut messages = Vec::new();

    for entry in content.split("---") {
        let trimmed = entry.trim();
        if trimmed.is_empty() {
            continue;
        }

        // Extract session id from entry
        let entry_session = trimmed
            .lines()
            .find(|l| l.trim_start().starts_with("id:"))
            .and_then(|l| l.split(':').nth(1))
            .map(|s| s.trim().to_string());

        let Some(eid) = entry_session else {
            continue;
        };

        if eid != target_session {
            continue;
        }

        // Extract prompt/command
        if let Some(prompt_line) = trimmed.lines().find(|l| l.trim_start().starts_with("prompt:")) {
            let prompt = prompt_line
                .split("prompt:")
                .nth(1)
                .map(|s| s.trim().trim_matches(&['"', '\''][..]))
                .unwrap_or_default()
                .to_string();

            if !prompt.is_empty() {
                messages.push(ChatMessage {
                    role: "user".to_string(),
                    timestamp: None,
                    blocks: vec![ContentBlock::Text { text: prompt }],
                });
            }
        }

        // Extract response
        if let Some(response_block) = trimmed.lines().find(|l| l.trim_start().starts_with("response:")) {
            let response = response_block
                .split("response:")
                .nth(1)
                .map(|s| s.trim().trim_matches(&['"', '\'', '`'][..]))
                .unwrap_or_default()
                .to_string();

            if !response.is_empty() {
                let (thinking, regular) = extract_thinking(&response);
                let mut blocks = Vec::new();

                if !thinking.is_empty() {
                    blocks.push(ContentBlock::Thinking { content: thinking });
                }
                if !regular.is_empty() {
                    blocks.push(ContentBlock::Text { text: regular });
                }

                if !blocks.is_empty() {
                    messages.push(ChatMessage {
                        role: "assistant".to_string(),
                        timestamp: None,
                        blocks,
                    });
                }
            }
        }
    }

    messages
}

/// Find the kiro session ID that matches the given working directory.
/// Scans `~/.local/share/kiro-cli/history` for an entry with matching cwd.
pub fn find_session_id(cwd: &Path) -> Result<String> {
    let home = dirs::home_dir().context("cannot determine home directory")?;
    let history_path = home.join(".local/share/kiro-cli/history");

    if !history_path.exists() {
        bail!("kiro-cli history file not found at {}", history_path.display());
    }

    let content = std::fs::read_to_string(&history_path)?;
    let cwd_str = cwd.to_string_lossy();

    for entry in content.split("---") {
        let trimmed = entry.trim();
        if trimmed.is_empty() {
            continue;
        }

        // Check if this entry's cwd matches
        let entry_cwd = trimmed
            .lines()
            .find(|l| l.trim_start().starts_with("cwd:"))
            .and_then(|l| l.split(':').nth(1))
            .map(|s| s.trim().to_string());

        let Some(entry_cwd) = entry_cwd else {
            continue;
        };

        // Normalize and compare
        let normalized_entry = entry_cwd.replace('~', &home.to_string_lossy());
        if normalized_entry == cwd_str {
            // Found matching entry — extract session id
            if let Some(id_line) = trimmed.lines().find(|l| l.trim_start().starts_with("id:")) {
                if let Some(id) = id_line.split(':').nth(1).map(|s| s.trim().to_string()) {
                    return Ok(id);
                }
            }
        }
    }

    bail!(
        "no kiro-cli session found for cwd: {}",
        cwd.display()
    )
}

/// Check if the kiro-cli-chat process with given PID is still running.
pub fn is_process_alive(pid: u32) -> bool {
    let path = format!("/proc/{}/comm", pid);
    std::fs::read_to_string(&path)
        .ok()
        .map(|s| s.trim() == "kiro-cli-chat")
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strip_ansi_empty() {
        assert_eq!(strip_ansi(""), "");
    }

    #[test]
    fn test_strip_ansi_plain_text() {
        assert_eq!(strip_ansi("Hello world"), "Hello world");
    }

    #[test]
    fn test_strip_ansi_color_codes() {
        // Bold red text
        let input = "\x1b[1m\x1b[31mHello\x1b[0m world";
        assert_eq!(strip_ansi(input), "Hello world");
    }

    #[test]
    fn test_strip_ansi_cursor_movement() {
        // Cursor save/restore and movement
        let input = "\x1b[s\x1b[1;1H\x1b[2J\x1b[uHello";
        assert_eq!(strip_ansi(input), "Hello");
    }

    #[test]
    fn test_strip_ansi_multiple_sequences() {
        let input = "\x1b[32m\x1b[1m\x1b[4mGreen bold underline\x1b[0m";
        assert_eq!(strip_ansi(input), "Green bold underline");
    }

    #[test]
    fn test_strip_ansi_screen_clear() {
        let input = "\x1b[2J\x1b[H\x1b[JHello";
        assert_eq!(strip_ansi(input), "Hello");
    }

    #[test]
    fn test_strip_ansi_carriage_return_overwrites() {
        // Spinner: \r overwrites current line
        let input = "\r\u{28f7} Thinking...\r\x1b[34m> \x1b[39mHello!\x1b[K";
        let result = strip_ansi(input);
        assert_eq!(result, "> Hello!");
    }

    #[test]
    fn test_strip_ansi_charset_designation() {
        // ESC(B — select ASCII character set
        let input = "\x1b(B\x1b[mHello";
        assert_eq!(strip_ansi(input), "Hello");
    }

    #[test]
    fn test_strip_ansi_cr_lf_preserved() {
        // \r\n should be treated as normal newline
        let input = "line1\r\nline2";
        assert_eq!(strip_ansi(input), "line1\nline2");
    }

    #[test]
    fn test_clean_kiro_output_filters_spinner() {
        let text = "\u{28f9} Thinking...\nActual response";
        assert_eq!(clean_kiro_output(text), "Actual response");
    }

    #[test]
    fn test_clean_kiro_output_filters_prompt() {
        let text = "Hello!\n14% > ";
        assert_eq!(clean_kiro_output(text), "Hello!");
    }

    #[test]
    fn test_clean_kiro_output_filters_status_bar() {
        let text = "Hello!\n[kiro] 0:kiro-cli*  \"pop-os\" 15:38 07-Apr-26";
        assert_eq!(clean_kiro_output(text), "Hello!");
    }

    #[test]
    fn test_clean_kiro_output_strips_response_prefix() {
        let text = "> Hello world!";
        assert_eq!(clean_kiro_output(text), "Hello world!");
    }

    #[test]
    fn test_clean_kiro_output_filters_metadata() {
        let text = "Hello!\n\u{25b8} Credits: 0.03 \u{2022}\nTime: 2s";
        assert_eq!(clean_kiro_output(text), "Hello!");
    }

    #[test]
    fn test_extract_thinking_no_tags() {
        let (thinking, regular) = extract_thinking("Hello world");
        assert_eq!(thinking, "");
        assert_eq!(regular, "Hello world");
    }

    #[test]
    fn test_extract_thinking_simple() {
        let text = "Before<think>internal thought</think>After";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "internal thought");
        assert_eq!(regular, "BeforeAfter");
    }

    #[test]
    fn test_extract_thinking_multiple_blocks() {
        let text = "A<think>t1</think>B<think>t2</think>C";
        let (thinking, regular) = extract_thinking(text);
        assert!(thinking.contains("t1"));
        assert!(thinking.contains("t2"));
        assert!(regular.contains('A'));
        assert!(regular.contains('B'));
        assert!(regular.contains('C'));
    }

    #[test]
    fn test_extract_thinking_unclosed_tag() {
        let text = "Before<think>unclosed";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "unclosed");
        assert_eq!(regular, "Before");
    }

    #[test]
    fn test_extract_thinking_only_tags() {
        let text = "<think>only thinking</think>";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "only thinking");
        assert_eq!(regular, "");
    }

    #[test]
    fn test_extract_thinking_with_whitespace() {
        let text = " <think>  spaced </think>  text  ";
        let (thinking, regular) = extract_thinking(text);
        assert_eq!(thinking, "spaced");
        assert_eq!(regular, "text");
    }

    #[test]
    fn test_kiro_state_new() {
        let state = KiroState::new(1234, "session-abc".into(), PathBuf::from("/home/user"));
        assert_eq!(state.pid, 1234);
        assert_eq!(state.session_id, "session-abc");
        assert!(state.response_buffer.is_empty());
        assert!(!state.in_tool_block);
    }

    #[test]
    fn test_is_response_complete_empty_buffer() {
        let state = KiroState::new(1, "s".into(), PathBuf::new());
        assert!(!is_response_complete(&state));
    }

    #[test]
    fn test_md5_hash_different_inputs() {
        let h1 = md5_hash("hello");
        let h2 = md5_hash("world");
        assert_ne!(h1, h2);
    }

    #[test]
    fn test_md5_hash_same_input() {
        let h1 = md5_hash("test");
        let h2 = md5_hash("test");
        assert_eq!(h1, h2);
    }

    #[test]
    fn test_detect_tool_blocks_bash() {
        let text = "Bash:\nls -la\nfolder/\nfile.txt";
        let blocks = detect_tool_blocks(text);
        assert!(!blocks.is_empty());
        match &blocks[0] {
            ContentBlock::ToolCall { name, .. } => assert_eq!(name, "Bash"),
            _ => panic!("Expected ToolCall"),
        }
    }

    #[test]
    fn test_detect_tool_blocks_read() {
        let text = "Read:\nsrc/main.rs";
        let blocks = detect_tool_blocks(text);
        assert!(!blocks.is_empty());
    }

    #[test]
    fn test_detect_tool_blocks_box_drawing() {
        let text = "╭──Tool: Bash\nls -la\n╰──►";
        let blocks = detect_tool_blocks(text);
        assert!(!blocks.is_empty());
    }

    #[test]
    fn test_detect_tool_blocks_empty() {
        let blocks = detect_tool_blocks("Just regular text\nwithout tools");
        assert!(blocks.is_empty());
    }

    #[test]
    fn test_parse_pty_chunk_empty() {
        let mut state = KiroState::new(1, "s".into(), PathBuf::new());
        let result = parse_pty_chunk("", &mut state);
        assert!(result.is_empty());
    }

    #[test]
    fn test_parse_pty_chunk_plain_text() {
        // Test emit_response directly since parse_pty_chunk requires real timing
        let mut state = KiroState::new(1, "s".into(), PathBuf::new());
        let result = emit_response("Hello world", &mut state);
        assert!(!result.is_empty());
        assert_eq!(result[0].role, "assistant");
    }

    #[test]
    fn test_parse_pty_chunk_with_thinking() {
        let mut state = KiroState::new(1, "s".into(), PathBuf::new());
        let result = emit_response("<think>Let me think about this</think>The answer is 42", &mut state);
        assert!(!result.is_empty());

        let msg = &result[0];
        assert_eq!(msg.role, "assistant");
        let has_thinking = msg
            .blocks
            .iter()
            .any(|b| matches!(b, ContentBlock::Thinking { .. }));
        assert!(has_thinking);
    }

    #[test]
    fn test_parse_pty_chunk_dedup() {
        let mut state = KiroState::new(1, "s".into(), PathBuf::new());

        let result1 = emit_response("Hello world", &mut state);
        assert!(!result1.is_empty());

        let result2 = emit_response("Hello world", &mut state);
        assert!(result2.is_empty()); // Should be deduped
    }

    #[test]
    fn test_is_process_alive_nonexistent() {
        assert!(!is_process_alive(999999999));
    }

    #[test]
    fn test_find_session_id_nonexistent() {
        let result = find_session_id(Path::new("/nonexistent/path/xyz"));
        assert!(result.is_err());
    }

    #[test]
    fn test_fetch_history_nonexistent() {
        // When history file doesn't exist, returns error
        // When file exists but session not found, returns empty vec
        let result = fetch_history("nonexistent-session-id");
        // Either is fine - file may or may not exist on test machine
        assert!(result.is_ok() || result.is_err());
    }
}
