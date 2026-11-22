use chrono::{DateTime, Utc};
use serde::Deserialize;
use serde_json::Value;
use std::{
    fs::File,
    io::{BufReader, Read, Seek, SeekFrom},
    path::Path,
};

use super::{truncate_utf8, ChatMessage, ContentBlock};
use crate::history::{archive_large, attachment_directory, Span, INLINE_RECORD_BYTES};
use anyhow::{ensure, Context, Result};

pub fn message_role(source: &str, kind: &str) -> Option<&'static str> {
    match (source, kind) {
        ("USER_EXPLICIT", "USER_INPUT") => Some("user"),
        ("MODEL", "PLANNER_RESPONSE" | "GENERIC") => Some("assistant"),
        _ => None,
    }
}

#[derive(Deserialize)]
struct Step {
    source: String,
    #[serde(rename = "type")]
    kind: String,
    created_at: Option<String>,
    content: Option<String>,
    #[serde(default)]
    tool_calls: Vec<ToolCall>,
}

#[derive(Deserialize)]
struct ToolCall {
    name: Option<String>,
    args: Option<Value>,
}

/// Read one step from Antigravity CLI's materialized JSONL transcript.
pub fn parse_line(line: &str) -> Option<ChatMessage> {
    let step: Step = serde_json::from_str(line).ok()?;
    let role = message_role(&step.source, &step.kind)?;
    let mut blocks = Vec::new();
    let content = step.content.filter(|text| !text.trim().is_empty());
    match step.kind.as_str() {
        "USER_INPUT" => {
            let content = content?;
            let text = user_request(&content)?;
            blocks.push(ContentBlock::Text { text: text.into() });
        }
        "PLANNER_RESPONSE" => {
            if let Some(text) = content {
                blocks.push(ContentBlock::Text { text });
            }
            for call in step.tool_calls {
                let Some(name) = call.name.filter(|name| !name.is_empty()) else {
                    continue;
                };
                let summary = call
                    .args
                    .as_ref()
                    .and_then(|args| args.get("Path").or_else(|| args.get("query")))
                    .and_then(Value::as_str)
                    .map(|s| truncate_utf8(s, 120))
                    .unwrap_or_else(|| name.clone());
                blocks.push(ContentBlock::ToolCall {
                    name,
                    summary,
                    input: call.args,
                });
            }
        }
        // The CLI emits one generic result per tool call, without the tool name.
        "GENERIC" => {
            let text = content?;
            let lines = text.lines().count();
            let summary = if lines > 1 {
                format!("{lines} lines")
            } else {
                truncate_utf8(&text, 120)
            };
            blocks.push(ContentBlock::ToolResult {
                tool_name: "Tool".into(),
                summary,
                content: Some(text),
            });
        }
        _ => return None,
    }
    if blocks.is_empty() {
        return None;
    }
    Some(ChatMessage {
        role: role.into(),
        timestamp: step
            .created_at
            .and_then(|s| s.parse::<DateTime<Utc>>().ok()),
        blocks,
    })
}

fn user_request(content: &str) -> Option<&str> {
    // USER_INPUT contains the CLI's environment and settings after the request.
    // Drop the wrapper's single newline on either side while retaining indentation.
    let trimmed = content.trim_start();
    let text = match trimmed.strip_prefix("<USER_REQUEST>") {
        Some(wrapped) => {
            let body = wrapped
                .trim_end()
                .strip_suffix("</USER_REQUEST>")
                .or_else(|| {
                    wrapped
                        .rsplit_once("</USER_REQUEST>\n<ADDITIONAL_METADATA>")
                        .map(|(body, _)| body)
                })
                .or_else(|| wrapped.rsplit_once("</USER_REQUEST>").map(|(body, _)| body))?;
            let body = body.strip_prefix('\n').unwrap_or(body);
            body.strip_suffix('\n').unwrap_or(body)
        }
        None if trimmed.starts_with("<ADDITIONAL_METADATA>")
            || trimmed.starts_with("<USER_SETTINGS_CHANGE>") =>
        {
            return None
        }
        None => content,
    };
    (!text.trim().is_empty()).then_some(text)
}

pub(crate) fn large_user_message(
    file: &Path,
    content: Span,
    timestamp: Option<DateTime<Utc>>,
) -> Result<Option<ChatMessage>> {
    let Some(span) = prompt_span(file, content)? else {
        return Ok(None);
    };
    message_from_span(file, span, timestamp, &attachment_directory()?).map(Some)
}

fn message_from_span(
    file: &Path,
    span: Span,
    timestamp: Option<DateTime<Utc>>,
    attachments: &Path,
) -> Result<ChatMessage> {
    // The span contains JSON string bytes without the opening and closing quotes.
    let json_string = || -> Result<_> {
        let mut input = File::open(file)?;
        input.seek(SeekFrom::Start(span.start))?;
        Ok(b"\""
            .as_slice()
            .chain(input.take(span.len))
            .chain(b"\"".as_slice()))
    };
    // One decoded byte can occupy up to six bytes as a JSON unicode escape.
    if span.len <= (INLINE_RECORD_BYTES * 6) as u64 {
        let prompt: String = serde_json::from_reader(BufReader::new(json_string()?))?;
        if prompt.len() <= INLINE_RECORD_BYTES {
            return Ok(ChatMessage {
                role: "user".into(),
                timestamp,
                blocks: vec![ContentBlock::Text { text: prompt }],
            });
        }
    }
    archive_large(json_string()?, attachments, "user", timestamp)
}

/// Locate the request within a spooled JSON string without decoding its potentially
/// large contents into memory. Offsets point to the original escaped bytes, so the
/// extracted string can be copied directly into a valid JSON attachment.
fn prompt_span(path: &Path, content: Span) -> Result<Option<Span>> {
    let mut file = File::open(path)?;
    file.seek(SeekFrom::Start(content.start))?;
    let mut bytes = BufReader::new(file.take(content.len)).bytes();
    ensure!(
        bytes.next().transpose()? == Some(b'"'),
        "Expected agy content string"
    );
    let mut pos = content.start + 1;
    let mut start = pos;
    let mut first_nonspace = None;
    let mut tag = Vec::new();
    let mut tag_start = pos;
    let mut previous_newline = None;
    let mut end_before_tag = pos;
    let mut closing_tag = None;
    let mut boundary = None;
    let mut previous_nonspace_end = pos;
    let mut before_tag = pos;
    let mut wrapped = false;
    let mut after_open = false;
    let nonempty_span = |start, end, first: Option<u64>| {
        first.filter(|&first| first < end).map(|_| Span {
            start,
            len: end - start,
        })
    };
    while let Some(raw) = bytes.next().transpose()? {
        let at = pos;
        pos += 1;
        if raw == b'"' {
            ensure!(
                pos == content.start + content.len,
                "Invalid agy content string"
            );
            let end = if wrapped {
                closing_tag.map(|(after, end)| {
                    if after == previous_nonspace_end {
                        end
                    } else {
                        boundary.unwrap_or(end)
                    }
                })
            } else {
                Some(at)
            };
            return Ok(end.and_then(|end| nonempty_span(start, end, first_nonspace)));
        }
        let c = if raw == b'\\' {
            let escaped = bytes.next().context("Incomplete JSON escape")??;
            pos += 1;
            match escaped {
                b'u' => {
                    let mut scalar = 0;
                    for _ in 0..4 {
                        let digit = bytes.next().context("Incomplete JSON unicode escape")??;
                        scalar = scalar * 16
                            + (digit as char)
                                .to_digit(16)
                                .context("Invalid JSON unicode escape")?;
                    }
                    pos += 4;
                    u8::try_from(scalar).unwrap_or(0xff)
                }
                b'n' => b'\n',
                b't' => b'\t',
                b'r' => b'\r',
                b'f' => 12,
                b'b' => 8,
                b'"' | b'/' | b'\\' => escaped,
                _ => anyhow::bail!("Invalid JSON escape"),
            }
        } else {
            ensure!(raw >= 0x20, "Invalid control character in agy content");
            raw
        };
        if after_open {
            if c == b'\n' {
                start = pos;
            }
            after_open = false;
        }
        if !c.is_ascii_whitespace() {
            first_nonspace.get_or_insert(at);
        }
        if c == b'<' {
            tag.clear();
            tag_start = at;
            end_before_tag = previous_newline.unwrap_or(at);
            before_tag = previous_nonspace_end;
        }
        if c == b'<' || !tag.is_empty() {
            tag.push(c);
            if c == b'>' {
                match tag.as_slice() {
                    b"<USER_REQUEST>" if !wrapped && first_nonspace == Some(tag_start) => {
                        wrapped = true;
                        start = pos;
                        first_nonspace = None;
                        after_open = true;
                    }
                    b"</USER_REQUEST>" if wrapped => {
                        closing_tag = Some((pos, end_before_tag.max(start)));
                    }
                    b"<ADDITIONAL_METADATA>" | b"<USER_SETTINGS_CHANGE>"
                        if !wrapped && first_nonspace == Some(tag_start) =>
                    {
                        return Ok(None);
                    }
                    b"<ADDITIONAL_METADATA>" | b"<USER_SETTINGS_CHANGE>" => {
                        if let Some((_, end)) =
                            closing_tag.filter(|(after, _)| *after == before_tag)
                        {
                            // A user can quote the envelope itself. Keep scanning for
                            // the outer closing request before the CLI's own metadata.
                            boundary = Some(end);
                        }
                    }
                    _ => (),
                }
                tag.clear();
            } else if tag.len() > b"<USER_SETTINGS_CHANGE>".len() {
                tag.clear();
            }
        }
        if !c.is_ascii_whitespace() {
            previous_nonspace_end = pos;
        }
        previous_newline = (c == b'\n').then_some(at);
    }
    anyhow::bail!("Unterminated agy content string")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn unwraps_only_the_request_and_preserves_indentation() {
        let msg = parse_line(&json!({
            "step_index": 0, "source": "USER_EXPLICIT", "type": "USER_INPUT",
            "status": "DONE", "created_at": "2026-11-21T14:05:24Z",
            "content": "<USER_REQUEST>\n  first line\nsecond line\n</USER_REQUEST>\n<ADDITIONAL_METADATA>workspace</ADDITIONAL_METADATA>\n<USER_SETTINGS_CHANGE>settings</USER_SETTINGS_CHANGE>"
        }).to_string()).unwrap();
        assert_eq!(msg.role, "user");
        assert!(msg.timestamp.is_some());
        assert!(
            matches!(&msg.blocks[..], [ContentBlock::Text { text }] if text == "  first line\nsecond line")
        );
    }

    #[test]
    fn planner_text_and_calls_share_a_message_and_results_stay_tool_blocks() {
        let msg = parse_line(
            &json!({
                "source": "MODEL", "type": "PLANNER_RESPONSE", "content": "I'll check.",
                "tool_calls": [{"name":"read_file","args":{"Path":"src/lib.rs"}},
                               {"name":"search_web","args":{"query":"API reference"}}]
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(msg.role, "assistant");
        assert!(matches!(&msg.blocks[..],
            [ContentBlock::Text { text }, ContentBlock::ToolCall { name, input: Some(args), .. }, ContentBlock::ToolCall { .. }]
            if text == "I'll check." && name == "read_file" && args["Path"] == "src/lib.rs"));
        let result =
            parse_line(r#"{"source":"MODEL","type":"GENERIC","content":"line 1\nline 2"}"#)
                .unwrap();
        assert!(
            matches!(&result.blocks[..], [ContentBlock::ToolResult { summary, content: Some(text), .. }]
            if summary == "2 lines" && text == "line 1\nline 2")
        );
        let answer =
            parse_line(r#"{"source":"MODEL","type":"PLANNER_RESPONSE","content":"Finished."}"#)
                .unwrap();
        assert!(matches!(&answer.blocks[..], [ContentBlock::Text { text }] if text == "Finished."));
    }

    #[test]
    fn ignores_injected_empty_and_invalid_records() {
        for line in [
            r#"{"source":"SYSTEM","type":"USER_INPUT","content":"injected"}"#,
            r#"{"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<ADDITIONAL_METADATA>hidden</ADDITIONAL_METADATA>"}"#,
            r#"{"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<USER_REQUEST>unfinished"}"#,
            r#"{"source":"MODEL","type":"PLANNER_RESPONSE","tool_calls":[{}]}"#,
            r#"{"source":"MODEL","type":"UNKNOWN","content":"internal"}"#,
            "invalid",
        ] {
            assert!(parse_line(line).is_none());
        }
    }

    #[test]
    fn oversized_metadata_leaves_a_small_prompt_inline() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("record");
        let raw = format!(
            r#""\u003cUSER_REQUEST\u003e\n\n  Ω\t\"quoted\"\n\u003c\/USER_REQUEST\u003e\n<ADDITIONAL_METADATA>{}</ADDITIONAL_METADATA>""#,
            "x".repeat(INLINE_RECORD_BYTES)
        );
        std::fs::write(&path, &raw).unwrap();
        let span = Span {
            start: 0,
            len: raw.len() as u64,
        };
        let msg = large_user_message(&path, span, None).unwrap().unwrap();
        assert!(
            matches!(&msg.blocks[..], [ContentBlock::Text { text }] if text == "\n  Ω\t\"quoted\"")
        );
    }

    #[test]
    fn preserves_cli_markup_quoted_inside_a_request_on_both_read_paths() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("record");
        let prompt = "Explain this XML:\n<USER_REQUEST>example</USER_REQUEST>\n<ADDITIONAL_METADATA>example</ADDITIONAL_METADATA>\nincluding the closing tag.";
        for content in [
            prompt.to_owned(),
            format!("<USER_REQUEST>\n{prompt}\n</USER_REQUEST>"),
            format!("<USER_REQUEST>\n{prompt}\n</USER_REQUEST>\n<ADDITIONAL_METADATA>private</ADDITIONAL_METADATA>"),
        ] {
            assert_eq!(user_request(&content), Some(prompt));
            let mut record = json!({"source":"USER_EXPLICIT", "type":"USER_INPUT", "content":content});
            record["padding"] = json!("x".repeat(INLINE_RECORD_BYTES));
            std::fs::write(&path, serde_json::to_vec(&record).unwrap()).unwrap();
            let mut file = crate::history::JsonFile::open(&path).unwrap();
            let root = file.root().unwrap();
            let span = file.field(root, "content").unwrap().unwrap();
            let msg = large_user_message(&path, span, None).unwrap().unwrap();
            assert!(matches!(&msg.blocks[..], [ContentBlock::Text { text }] if text == prompt));
        }
    }

    #[test]
    fn archives_large_prompt_as_json_without_attached_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("record");
        let prompt = "Ω\n\"hello\"!".repeat(INLINE_RECORD_BYTES / 8);
        let wire = serde_json::to_vec(&format!(
            "<USER_REQUEST>\n{prompt}\n</USER_REQUEST>\n<ADDITIONAL_METADATA>private context</ADDITIONAL_METADATA>"
        )).unwrap();
        std::fs::write(&path, &wire).unwrap();
        let span = prompt_span(
            &path,
            Span {
                start: 0,
                len: wire.len() as u64,
            },
        )
        .unwrap()
        .unwrap();
        let msg = message_from_span(&path, span, None, dir.path()).unwrap();
        let ContentBlock::File { id, .. } = &msg.blocks[1] else {
            panic!("expected full-prompt attachment");
        };
        let archived = File::open(dir.path().join(format!("{id}.json"))).unwrap();
        assert_eq!(
            serde_json::from_reader::<_, String>(archived).unwrap(),
            prompt
        );
    }
}
