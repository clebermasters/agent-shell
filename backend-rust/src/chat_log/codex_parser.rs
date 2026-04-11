use chrono::{DateTime, Utc};
use tracing::warn;

use super::{ChatMessage, ContentBlock};

/// Context window info extracted from a Codex session log.
pub struct ContextInfo {
    pub usage_pct: f64,
    pub model: String,
}

/// Parse a single NDJSON line from a Codex CLI rollout file.
///
/// Codex stores sessions as `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`.
/// Each line is a [`RolloutLine`] with `timestamp`, `type`, and `payload` fields.
///
/// Returns `None` for blank lines, unknown event types, and malformed JSON.
pub fn parse_line(line: &str) -> Option<ChatMessage> {
    let trimmed = line.trim();
    if trimmed.is_empty() {
        return None;
    }

    let v: serde_json::Value = match serde_json::from_str(trimmed) {
        Ok(v) => v,
        Err(e) => {
            warn!("codex_parser: failed to parse line: {e}");
            return None;
        }
    };

    let timestamp = parse_timestamp(&v);
    let line_type = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
    let payload = v.get("payload");

    match line_type {
        "event_msg" => payload.and_then(|p| parse_event_msg(p, timestamp)),
        "response_item" => payload.and_then(|p| parse_response_item(p, timestamp)),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Event message parsing
// ---------------------------------------------------------------------------

fn parse_event_msg(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let event_type = payload.get("type").and_then(|t| t.as_str()).unwrap_or("");

    match event_type {
        "user_message" => parse_user_message(payload, timestamp),
        "agent_message" => parse_agent_message(payload, timestamp),
        "exec_command_end" => parse_exec_command_end(payload, timestamp),
        "patch_apply_end" => parse_patch_apply_end(payload, timestamp),
        _ => None,
    }
}

fn parse_user_message(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let message = payload.get("message")?.as_str()?.to_string();
    if message.is_empty() {
        return None;
    }

    Some(ChatMessage {
        role: "user".to_string(),
        timestamp,
        blocks: vec![ContentBlock::Text { text: message }],
    })
}

fn parse_agent_message(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let message = payload.get("message")?.as_str()?.to_string();
    if message.is_empty() {
        return None;
    }

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::Text { text: message }],
    })
}

fn parse_exec_command_end(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let command_parts: Vec<String> = payload
        .get("command")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default();

    let command = format_command(&command_parts);
    let output = payload
        .get("aggregated_output")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    let mut blocks = Vec::new();

    // Tool call block with the command
    if !command.is_empty() {
        blocks.push(ContentBlock::ToolCall {
            name: "Bash".to_string(),
            summary: truncate(&command, 120),
            input: Some(serde_json::json!({"command": command})),
        });
    }

    // Tool result block with the output
    if !output.is_empty() {
        blocks.push(ContentBlock::ToolResult {
            tool_name: "Bash".to_string(),
            summary: summarize_output(output),
            content: Some(output.to_string()),
        });
    }

    if blocks.is_empty() {
        return None;
    }

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks,
    })
}

fn parse_patch_apply_end(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let stdout = payload
        .get("stdout")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    let stderr = payload
        .get("stderr")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    let success = payload
        .get("success")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let content = combine_output(stdout, stderr);
    let summary = if success {
        summarize_output_or_placeholder(content.as_deref())
    } else if !stderr.is_empty() {
        truncate(stderr, 120)
    } else {
        "patch failed".to_string()
    };

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::ToolResult {
            tool_name: "apply_patch".to_string(),
            summary,
            content,
        }],
    })
}

// ---------------------------------------------------------------------------
// Response item parsing
// ---------------------------------------------------------------------------

fn parse_response_item(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let item_type = payload.get("type").and_then(|t| t.as_str()).unwrap_or("");

    match item_type {
        "function_call" => parse_function_call(payload, timestamp),
        "function_call_output" => parse_function_call_output(payload, timestamp),
        "custom_tool_call" => parse_custom_tool_call(payload, timestamp),
        "web_search_call" => parse_web_search_call(payload, timestamp),
        "reasoning" => parse_reasoning(payload, timestamp),
        _ => None,
    }
}

fn parse_function_call(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let name = payload.get("name")?.as_str()?.to_string();

    // Skip exec_command — we get full info from exec_command_end event
    if name == "exec_command" {
        return None;
    }

    let arguments = payload
        .get("arguments")
        .and_then(|v| v.as_str())
        .unwrap_or("{}");
    let input: Option<serde_json::Value> = serde_json::from_str(arguments).ok();
    let summary = generate_tool_summary(&name, input.as_ref());

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::ToolCall {
            name,
            summary,
            input,
        }],
    })
}

fn parse_function_call_output(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let output = payload.get("output").and_then(|v| v.as_str())?.trim();
    if output.is_empty() || looks_like_exec_command_output(output) {
        return None;
    }

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::ToolResult {
            tool_name: payload
                .get("call_id")
                .and_then(|v| v.as_str())
                .unwrap_or("tool")
                .to_string(),
            summary: summarize_output_or_placeholder(Some(output)),
            content: Some(output.to_string()),
        }],
    })
}

fn parse_custom_tool_call(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let name = payload.get("name")?.as_str()?.to_string();
    let input = payload
        .get("input")
        .and_then(|v| v.as_str())
        .map(|s| serde_json::json!({ "input": truncate(s, 500) }));

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::ToolCall {
            summary: name.clone(),
            name,
            input,
        }],
    })
}

fn parse_web_search_call(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let query = payload
        .get("action")
        .and_then(|v| v.get("query"))
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())?;

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::ToolCall {
            name: "web_search".to_string(),
            summary: truncate(query, 120),
            input: Some(serde_json::json!({ "query": query })),
        }],
    })
}

fn parse_reasoning(
    payload: &serde_json::Value,
    timestamp: Option<DateTime<Utc>>,
) -> Option<ChatMessage> {
    let summary_text = payload
        .get("summary")
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    item.get("text")
                        .and_then(|v| v.as_str())
                        .map(str::trim)
                        .filter(|text| !text.is_empty())
                        .map(String::from)
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .filter(|text| !text.is_empty());

    let content_text = payload
        .get("content")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(String::from);

    let thinking = content_text.or(summary_text)?;

    Some(ChatMessage {
        role: "assistant".to_string(),
        timestamp,
        blocks: vec![ContentBlock::Thinking { content: thinking }],
    })
}

// ---------------------------------------------------------------------------
// Context window extraction
// ---------------------------------------------------------------------------

/// Extract the latest context window usage from a Codex rollout file.
///
/// Scans for `token_count` events to find total tokens and context window size,
/// and `turn_context` events for the model name.
pub fn extract_context_usage(path: &std::path::Path) -> Option<ContextInfo> {
    let data = std::fs::read_to_string(path).ok()?;

    let mut last_tokens: Option<u64> = None;
    let mut context_window: Option<u64> = None;
    let mut model_name: Option<String> = None;

    for line in data.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let v: serde_json::Value = match serde_json::from_str(trimmed) {
            Ok(v) => v,
            Err(_) => continue,
        };

        let line_type = v.get("type").and_then(|t| t.as_str()).unwrap_or("");

        match line_type {
            "event_msg" => {
                let Some(payload) = v.get("payload") else {
                    continue;
                };
                let event_type = payload.get("type").and_then(|t| t.as_str()).unwrap_or("");

                if event_type == "token_count" {
                    if let Some(info) = payload.get("info") {
                        last_tokens = info
                            .get("last_token_usage")
                            .and_then(extract_total_tokens)
                            .or_else(|| {
                                info.get("total_token_usage").and_then(extract_total_tokens)
                            })
                            .or(last_tokens);
                        context_window = info
                            .get("model_context_window")
                            .and_then(|v| v.as_u64())
                            .or(context_window);
                    }
                } else if event_type == "task_started" {
                    context_window = payload
                        .get("model_context_window")
                        .and_then(|v| v.as_u64())
                        .or(context_window);
                }
            }
            "turn_context" => {
                if let Some(payload) = v.get("payload") {
                    if let Some(model) = payload.get("model").and_then(|m| m.as_str()) {
                        model_name = Some(model.to_string());
                    }
                }
            }
            _ => {}
        }
    }

    let tokens = last_tokens?;
    let window = context_window.unwrap_or(200_000);
    let friendly_model = model_name.unwrap_or_else(|| "codex".to_string());

    Some(ContextInfo {
        usage_pct: (tokens as f64 / window as f64) * 100.0,
        model: friendly_model,
    })
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn parse_timestamp(value: &serde_json::Value) -> Option<DateTime<Utc>> {
    value
        .get("timestamp")
        .and_then(|v| v.as_str())
        .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
        .map(|dt| dt.with_timezone(&Utc))
}

fn extract_total_tokens(total_usage: &serde_json::Value) -> Option<u64> {
    total_usage
        .get("total_tokens")
        .and_then(|v| v.as_u64())
        .or_else(|| {
            let input = total_usage
                .get("input_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let cached_input = total_usage
                .get("cached_input_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let output = total_usage
                .get("output_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let reasoning_output = total_usage
                .get("reasoning_output_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let total = input + cached_input + output + reasoning_output;
            (total > 0).then_some(total)
        })
}

fn format_command(parts: &[String]) -> String {
    if parts.is_empty() {
        return String::new();
    }
    // Strip common shell prefixes: /bin/bash -lc, /bin/sh -c, etc.
    let mut start = 0;
    for (i, part) in parts.iter().enumerate() {
        if part.contains("/bash") || part.contains("/sh") || part.contains("/zsh") {
            start = i + 1;
        } else if part == "-lc" || part == "-c" || part == "-l" {
            start = i + 1;
        } else {
            break;
        }
    }
    if start >= parts.len() {
        parts.join(" ")
    } else {
        parts[start..].join(" ")
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}...", &s[..max])
    }
}

fn summarize_output(text: &str) -> String {
    let line_count = text.lines().count();
    if line_count > 1 {
        return format!("{line_count} lines");
    }
    truncate(text, 120)
}

fn summarize_output_or_placeholder(text: Option<&str>) -> String {
    match text {
        Some(text) if !text.trim().is_empty() => summarize_output(text.trim()),
        _ => "(empty)".to_string(),
    }
}

fn combine_output(stdout: &str, stderr: &str) -> Option<String> {
    match (stdout.is_empty(), stderr.is_empty()) {
        (true, true) => None,
        (false, true) => Some(stdout.to_string()),
        (true, false) => Some(stderr.to_string()),
        (false, false) => Some(format!("{stdout}\n\nstderr:\n{stderr}")),
    }
}

fn looks_like_exec_command_output(output: &str) -> bool {
    output.starts_with("Command: ")
        && output.contains("Process exited with code")
        && output.contains("\nOutput:")
}

fn generate_tool_summary(name: &str, input: Option<&serde_json::Value>) -> String {
    match name {
        "read_file" | "write_file" | "create_file" => input
            .and_then(|v| v.get("path"))
            .and_then(|v| v.as_str())
            .map(|s| truncate(s, 120))
            .unwrap_or_else(|| name.to_string()),
        "search_query" => input
            .and_then(|v| v.get("q"))
            .and_then(|v| v.as_str())
            .map(|s| truncate(s, 120))
            .unwrap_or_else(|| name.to_string()),
        _ => name.to_string(),
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_user_message() {
        let line = r#"{"timestamp":"2025-05-07T17:24:21.123Z","type":"event_msg","payload":{"type":"user_message","message":"hello","images":[],"local_images":[],"text_elements":[]}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.role, "user");
        assert!(msg.timestamp.is_some());
        assert_eq!(msg.blocks.len(), 1);
        match &msg.blocks[0] {
            ContentBlock::Text { text } => assert_eq!(text, "hello"),
            other => panic!("expected Text, got {other:?}"),
        }
    }

    #[test]
    fn parse_agent_message() {
        let line = r#"{"timestamp":"2025-05-07T17:24:22.456Z","type":"event_msg","payload":{"type":"agent_message","message":"I'll fix this.","phase":"commentary","memory_citation":null}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.role, "assistant");
        assert!(msg.timestamp.is_some());
        assert_eq!(msg.blocks.len(), 1);
        match &msg.blocks[0] {
            ContentBlock::Text { text } => assert_eq!(text, "I'll fix this."),
            other => panic!("expected Text, got {other:?}"),
        }
    }

    #[test]
    fn parse_exec_command_end_with_output() {
        let line = r#"{"timestamp":"2025-05-07T17:24:23.789Z","type":"event_msg","payload":{"type":"exec_command_end","call_id":"call_abc123","command":["/bin/bash","-lc","npm test"],"aggregated_output":"All tests passed","exit_code":0}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.role, "assistant");
        assert_eq!(msg.blocks.len(), 2);

        match &msg.blocks[0] {
            ContentBlock::ToolCall {
                name,
                summary,
                input,
            } => {
                assert_eq!(name, "Bash");
                assert_eq!(summary, "npm test");
                assert!(input.is_some());
            }
            other => panic!("expected ToolCall, got {other:?}"),
        }

        match &msg.blocks[1] {
            ContentBlock::ToolResult {
                tool_name,
                summary,
                content,
            } => {
                assert_eq!(tool_name, "Bash");
                assert_eq!(summary, "All tests passed");
                assert_eq!(content.as_deref(), Some("All tests passed"));
            }
            other => panic!("expected ToolResult, got {other:?}"),
        }
    }

    #[test]
    fn parse_exec_command_end_without_output() {
        let line = r#"{"timestamp":"2025-05-07T17:24:23.789Z","type":"event_msg","payload":{"type":"exec_command_end","call_id":"call_abc456","command":["/bin/bash","-lc","mkdir -p build"],"aggregated_output":"","exit_code":0}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.blocks.len(), 1);
        match &msg.blocks[0] {
            ContentBlock::ToolCall { name, summary, .. } => {
                assert_eq!(name, "Bash");
                assert_eq!(summary, "mkdir -p build");
            }
            other => panic!("expected ToolCall, got {other:?}"),
        }
    }

    #[test]
    fn parse_function_call_read_file() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.000Z","type":"response_item","payload":{"type":"function_call","name":"read_file","arguments":"{\"path\":\"src/main.rs\"}","call_id":"call_def456"}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.role, "assistant");
        assert_eq!(msg.blocks.len(), 1);
        match &msg.blocks[0] {
            ContentBlock::ToolCall { name, summary, .. } => {
                assert_eq!(name, "read_file");
                assert_eq!(summary, "src/main.rs");
            }
            other => panic!("expected ToolCall, got {other:?}"),
        }
    }

    #[test]
    fn parse_function_call_output() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.100Z","type":"response_item","payload":{"type":"function_call_output","call_id":"call_123","output":"src/main.rs\nsrc/lib.rs"}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.role, "assistant");
        match &msg.blocks[0] {
            ContentBlock::ToolResult {
                tool_name,
                summary,
                content,
            } => {
                assert_eq!(tool_name, "call_123");
                assert_eq!(summary, "2 lines");
                assert_eq!(content.as_deref(), Some("src/main.rs\nsrc/lib.rs"));
            }
            other => panic!("expected ToolResult, got {other:?}"),
        }
    }

    #[test]
    fn skip_exec_command_function_call_output() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.200Z","type":"response_item","payload":{"type":"function_call_output","call_id":"call_123","output":"Command: /bin/bash -lc pwd\nChunk ID: abc\nWall time: 0.0\nProcess exited with code 0\nOutput:\n/tmp\n"}}"#;
        assert!(parse_line(line).is_none());
    }

    #[test]
    fn parse_custom_tool_call() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.300Z","type":"response_item","payload":{"type":"custom_tool_call","name":"apply_patch","call_id":"call_456","input":"*** Begin Patch\n*** End Patch\n"}}"#;
        let msg = parse_line(line).expect("should parse");
        match &msg.blocks[0] {
            ContentBlock::ToolCall {
                name,
                summary,
                input,
            } => {
                assert_eq!(name, "apply_patch");
                assert_eq!(summary, "apply_patch");
                assert!(input.is_some());
            }
            other => panic!("expected ToolCall, got {other:?}"),
        }
    }

    #[test]
    fn parse_web_search_call() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.400Z","type":"response_item","payload":{"type":"web_search_call","status":"completed","action":{"type":"search","query":"codex usage"}}}"#;
        let msg = parse_line(line).expect("should parse");
        match &msg.blocks[0] {
            ContentBlock::ToolCall {
                name,
                summary,
                input,
            } => {
                assert_eq!(name, "web_search");
                assert_eq!(summary, "codex usage");
                assert!(input.is_some());
            }
            other => panic!("expected ToolCall, got {other:?}"),
        }
    }

    #[test]
    fn parse_patch_apply_end() {
        let line = r#"{"timestamp":"2025-05-07T17:24:25.000Z","type":"event_msg","payload":{"type":"patch_apply_end","stdout":"Success. Updated the following files:\nM src/main.rs\n","stderr":"","success":true}}"#;
        let msg = parse_line(line).expect("should parse");
        match &msg.blocks[0] {
            ContentBlock::ToolResult {
                tool_name,
                summary,
                content,
            } => {
                assert_eq!(tool_name, "apply_patch");
                assert_eq!(summary, "2 lines");
                assert!(content.as_deref().unwrap_or("").contains("src/main.rs"));
            }
            other => panic!("expected ToolResult, got {other:?}"),
        }
    }

    #[test]
    fn parse_reasoning_summary_as_thinking() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.500Z","type":"response_item","payload":{"type":"reasoning","summary":[{"type":"summary_text","text":"**Inspecting parser flow**"}],"content":null}}"#;
        let msg = parse_line(line).expect("should parse");
        match &msg.blocks[0] {
            ContentBlock::Thinking { content } => {
                assert_eq!(content, "**Inspecting parser flow**");
            }
            other => panic!("expected Thinking, got {other:?}"),
        }
    }

    #[test]
    fn skip_reasoning_without_visible_content() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.600Z","type":"response_item","payload":{"type":"reasoning","summary":[],"content":null,"encrypted_content":"opaque"}}"#;
        assert!(parse_line(line).is_none());
    }

    #[test]
    fn skip_exec_command_function_call() {
        let line = r#"{"timestamp":"2025-05-07T17:24:24.000Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\"cmd\":\"ls\"}","call_id":"call_xyz"}}"#;
        assert!(
            parse_line(line).is_none(),
            "exec_command function_call should be skipped"
        );
    }

    #[test]
    fn skip_session_meta() {
        let line = r#"{"timestamp":"2025-05-07T17:24:21.000Z","type":"session_meta","payload":{"id":"abc-123","cwd":"/home/user/project","model_provider":"o3"}}"#;
        assert!(parse_line(line).is_none());
    }

    #[test]
    fn skip_turn_context() {
        let line = r#"{"timestamp":"2025-05-07T17:24:21.500Z","type":"turn_context","payload":{"model":"o3","cwd":"/home/user/project"}}"#;
        assert!(parse_line(line).is_none());
    }

    #[test]
    fn skip_unknown_event() {
        let line =
            r#"{"timestamp":"...","type":"event_msg","payload":{"type":"some_future_event"}}"#;
        assert!(parse_line(line).is_none());
    }

    #[test]
    fn skip_empty_lines() {
        assert!(parse_line("").is_none());
        assert!(parse_line("   ").is_none());
        assert!(parse_line("\n").is_none());
    }

    #[test]
    fn skip_malformed_json() {
        assert!(parse_line("{not valid json}").is_none());
    }

    #[test]
    fn multiline_output_summarized() {
        let line = r#"{"timestamp":"2025-05-07T17:24:23.789Z","type":"event_msg","payload":{"type":"exec_command_end","call_id":"call_123","command":["cat","log.txt"],"aggregated_output":"line 1\nline 2\nline 3","exit_code":0}}"#;
        let msg = parse_line(line).expect("should parse");
        assert_eq!(msg.blocks.len(), 2);
        match &msg.blocks[1] {
            ContentBlock::ToolResult { summary, .. } => {
                assert_eq!(summary, "3 lines");
            }
            other => panic!("expected ToolResult, got {other:?}"),
        }
    }

    #[test]
    fn extract_context_from_token_count() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("rollout.jsonl");
        std::fs::write(
            &path,
            r#"{"timestamp":"2025-05-07T17:24:00Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":50000,"cached_input_tokens":10000,"output_tokens":5000,"reasoning_output_tokens":2000,"total_tokens":67000},"total_token_usage":{"input_tokens":300000,"cached_input_tokens":10000,"output_tokens":5000,"reasoning_output_tokens":2000,"total_tokens":317000},"model_context_window":200000}}}
"#,
        ).unwrap();

        let info = extract_context_usage(&path).expect("should extract");
        assert!((info.usage_pct - 33.5).abs() < 0.1); // 67000/200000 * 100
    }

    #[test]
    fn extract_context_with_model_from_turn_context() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("rollout.jsonl");
        std::fs::write(
            &path,
            r#"{"timestamp":"2025-05-07T17:24:00Z","type":"event_msg","payload":{"type":"task_started","model_context_window":258400}}
{"timestamp":"2025-05-07T17:24:01Z","type":"turn_context","payload":{"model":"o3","cwd":"/tmp"}}
{"timestamp":"2025-05-07T17:24:02Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":10000,"output_tokens":2000,"total_tokens":12000}}}}
"#,
        ).unwrap();

        let info = extract_context_usage(&path).expect("should extract");
        assert_eq!(info.model, "o3");
        assert!((info.usage_pct - (12000.0 / 258400.0 * 100.0)).abs() < 0.1);
    }

    #[test]
    fn extract_context_empty_file() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("rollout.jsonl");
        std::fs::write(&path, "").unwrap();
        assert!(extract_context_usage(&path).is_none());
    }

    #[test]
    fn extract_context_no_token_count() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("rollout.jsonl");
        std::fs::write(
            &path,
            r#"{"timestamp":"...","type":"event_msg","payload":{"type":"user_message","message":"hello"}}
"#,
        )
        .unwrap();
        assert!(extract_context_usage(&path).is_none());
    }

    #[test]
    fn format_command_strips_shell_prefix() {
        assert_eq!(
            format_command(&[
                "/bin/bash".to_string(),
                "-lc".to_string(),
                "npm test".to_string()
            ]),
            "npm test"
        );
        assert_eq!(
            format_command(&["cat".to_string(), "file.txt".to_string()]),
            "cat file.txt"
        );
        assert_eq!(format_command(&[]), "");
    }
}
