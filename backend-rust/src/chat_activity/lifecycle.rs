use super::{ActivityState, ActivityStatus};
use serde_json::Value;
use std::{
    fs::{self, File},
    io::{BufRead, BufReader, Seek, SeekFrom},
    path::PathBuf,
};

#[derive(Clone, Debug, PartialEq, Eq)]
struct Source {
    pid: u32,
    path: PathBuf,
    tool: String,
}
#[derive(Clone, Debug)]
pub(super) struct Evidence {
    pub status: ActivityStatus,
    pub detail: &'static str,
    pub changed_at: i64,
    pub started_at: Option<i64>,
    pub turn_id: Option<String>,
    pub source: &'static str,
}

#[derive(Default)]
pub(super) struct Reader {
    source: Option<Source>,
    position: u64,
    evidence: Option<Evidence>,
}
impl Reader {
    pub fn refresh(mut self, root_pid: u32, discover: bool) -> (Self, Option<Evidence>) {
        if discover || self.source.is_none() {
            let found = discover_source(root_pid);
            if found != self.source {
                self.source = found;
                self.position = 0;
                self.evidence = None;
            }
        }
        let result = self.read();
        if result.is_err() {
            self.evidence = None;
        }
        let evidence = self.evidence.clone();
        (self, evidence)
    }
    fn read(&mut self) -> std::io::Result<()> {
        let Some(source) = &self.source else {
            return Ok(());
        };
        if !std::path::Path::new(&format!("/proc/{}", source.pid)).exists() {
            self.evidence = None;
            return Ok(());
        }
        let mut file = File::open(&source.path)?;
        let length = file.metadata()?.len();
        if length < self.position {
            self.position = 0;
            self.evidence = None;
        }
        if length == self.position {
            return Ok(());
        }
        // Bound initial reads and large appends. A missing lifecycle marker falls back to tmux.
        let limit = if self.position == 0 {
            8 * 1024 * 1024
        } else {
            1024 * 1024
        };
        let start = self.position.max(length.saturating_sub(limit));
        if start > self.position {
            self.evidence = None;
        }
        file.seek(SeekFrom::Start(start))?;
        let mut reader = BufReader::new(file);
        if start > self.position {
            let mut partial = String::new();
            reader.read_line(&mut partial)?;
        }
        let mut complete_position = reader.stream_position()?;
        loop {
            let mut line = String::new();
            if reader.read_line(&mut line)? == 0 {
                break;
            }
            if !line.ends_with('\n') {
                break;
            } // Re-read an incomplete append next time.
            complete_position = reader.stream_position()?;
            if let Ok(value) = serde_json::from_str::<Value>(&line) {
                if let Some(next) = parse(&source.tool, &value, self.evidence.as_ref()) {
                    self.evidence = Some(next);
                }
            }
        }
        self.position = complete_position;
        Ok(())
    }
}

// Never select the newest log by CWD: two agents may share the same directory.
fn discover_source(root_pid: u32) -> Option<Source> {
    let descendants = crate::chat_log::watcher::get_descendant_pids(root_pid).ok()?;
    for pid in std::iter::once(root_pid).chain(descendants) {
        let Some((tool, _)) = crate::chat_log::watcher::detect_tool_for_pid(pid) else {
            continue;
        };
        if tool != "codex" && tool != "claude" {
            continue;
        }
        let Ok(fds) = fs::read_dir(format!("/proc/{pid}/fd")) else {
            continue;
        };
        let mut paths = Vec::new();
        for fd in fds.flatten() {
            let Ok(path) = fs::read_link(fd.path()) else {
                continue;
            };
            let text = path.to_string_lossy();
            if text.ends_with(".jsonl")
                && ((tool == "codex" && text.contains("/.codex/sessions/"))
                    || (tool == "claude" && text.contains("/.claude/projects/")))
            {
                paths.push(path);
            }
        }
        paths.sort();
        paths.dedup();
        if paths.len() == 1 {
            return Some(Source {
                pid,
                path: paths.remove(0),
                tool,
            });
        }
    }
    None
}

fn timestamp(value: &Value) -> Option<i64> {
    value
        .as_i64()
        .and_then(|epoch| {
            // Codex lifecycle started_at uses epoch seconds; the WebSocket contract uses milliseconds.
            // Accept millisecond epochs too for compatibility with other log formats.
            if epoch <= 0 {
                None
            } else if epoch < 100_000_000_000 {
                epoch.checked_mul(1000)
            } else {
                Some(epoch)
            }
        })
        .or_else(|| {
            chrono::DateTime::parse_from_rfc3339(value.as_str()?)
                .ok()
                .map(|v| v.timestamp_millis())
        })
}

fn parse(tool: &str, value: &Value, previous: Option<&Evidence>) -> Option<Evidence> {
    let changed_at = timestamp(value.get("timestamp")?)?;
    if tool == "codex" {
        if value.get("type")?.as_str()? != "event_msg" {
            return None;
        }
        let payload = value.get("payload")?;
        let kind = payload.get("type")?.as_str()?;
        let status = match kind {
            "task_started" => ActivityStatus::Working,
            "task_complete" | "turn_aborted" => ActivityStatus::Idle,
            _ => return None,
        };
        let turn_id = payload
            .get("turn_id")
            .and_then(Value::as_str)
            .map(str::to_owned);
        // A late completion from an older turn must not end the active turn.
        if kind != "task_started" {
            if let Some(old) = previous {
                if old.status == ActivityStatus::Working
                    && old.turn_id.is_some()
                    && turn_id != old.turn_id
                {
                    return None;
                }
            }
        }
        return Some(Evidence {
            status,
            detail: match kind {
                "task_started" => "Agent is working",
                "turn_aborted" => "Agent turn interrupted",
                _ => "Agent turn ended",
            },
            changed_at,
            started_at: if status == ActivityStatus::Working {
                Some(
                    payload
                        .get("started_at")
                        .and_then(timestamp)
                        .unwrap_or(changed_at),
                )
            } else {
                None
            },
            turn_id,
            source: "codex-log",
        });
    }
    if tool == "claude" && value.get("isSidechain") != Some(&Value::Bool(true)) {
        let kind = value.get("type")?.as_str()?;
        let completed = kind == "system"
            && value.get("subtype").and_then(Value::as_str) == Some("turn_duration");
        let is_input = kind == "user"
            && value.get("isMeta") != Some(&Value::Bool(true))
            && value.get("isCompactSummary") != Some(&Value::Bool(true))
            && value.get("isVisibleInTranscriptOnly") != Some(&Value::Bool(true))
            && value.pointer("/message/content").is_some_and(|content| {
                content.is_string()
                    || content.as_array().is_some_and(|blocks| {
                        blocks
                            .iter()
                            .any(|block| block.get("type").and_then(Value::as_str) == Some("text"))
                    })
            });
        if completed || is_input {
            return Some(Evidence {
                status: if completed {
                    ActivityStatus::Idle
                } else {
                    ActivityStatus::Working
                },
                detail: if completed {
                    "Agent turn ended"
                } else {
                    "Agent is working"
                },
                changed_at,
                started_at: if is_input { Some(changed_at) } else { None },
                turn_id: value
                    .get("promptId")
                    .or_else(|| value.get("uuid"))
                    .and_then(Value::as_str)
                    .map(str::to_owned),
                source: "claude-log",
            });
        }
    }
    None
}

pub(super) fn apply(state: &mut ActivityState, native: &Evidence) {
    if matches!(
        state.status,
        ActivityStatus::Failed | ActivityStatus::Waiting
    ) {
        return;
    }
    if state.status == ActivityStatus::Working
        && native.status == ActivityStatus::Idle
        && native.changed_at < state.started_at.unwrap_or(state.observed_at) - 1000
    {
        return;
    }
    state.status = native.status;
    state.detail = native.detail.into();
    state.started_at = native.started_at;
    state.turn_id = native.turn_id.clone();
    state.source = native.source.into();
    state.confidence = "reported".into();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn epoch_seconds_milliseconds_and_iso_timestamps_agree() {
        let millis = 1_791_471_112_000i64;
        assert_eq!(timestamp(&serde_json::json!(1_791_471_112)), Some(millis));
        assert_eq!(timestamp(&serde_json::json!(millis)), Some(millis));
        assert_eq!(
            timestamp(&serde_json::json!("2026-10-08T14:51:52Z")),
            Some(millis)
        );
    }

    #[test]
    fn codex_numeric_started_at_produces_a_real_elapsed_duration() {
        let event = serde_json::json!({"timestamp":"2026-10-08T14:51:52.281Z","type":"event_msg","payload":{"type":"task_started","turn_id":"one","started_at":1_791_471_112}});
        let evidence = parse("codex", &event, None).unwrap();
        let mut state = ActivityState::unknown("Checking");
        state.observed_at = evidence.changed_at + 60_000;
        apply(&mut state, &evidence);
        assert_eq!(state.observed_at - state.started_at.unwrap(), 60_281);
    }
    fn codex(kind: &str, id: &str) -> Value {
        serde_json::json!({"timestamp":"2026-10-08T13:00:00Z","type":"event_msg","payload":{"type":kind,"turn_id":id}})
    }
    #[test]
    fn codex_start_completion_and_cancellation_are_explicit() {
        let start = parse("codex", &codex("task_started", "one"), None).unwrap();
        assert_eq!(start.status, ActivityStatus::Working);
        assert_eq!(
            parse("codex", &codex("task_complete", "one"), Some(&start))
                .unwrap()
                .status,
            ActivityStatus::Idle
        );
        assert_eq!(
            parse("codex", &codex("turn_aborted", "one"), Some(&start))
                .unwrap()
                .detail,
            "Agent turn interrupted"
        );
    }
    #[test]
    fn another_turn_cannot_finish_current_work() {
        let start = parse("codex", &codex("task_started", "one"), None).unwrap();
        assert!(parse("codex", &codex("task_complete", "two"), Some(&start)).is_none());
    }
    #[test]
    fn claude_tool_results_and_compaction_do_not_start_turns() {
        for extra in [
            serde_json::json!({"message":{"content":[{"type":"tool_result"}]}}),
            serde_json::json!({"isCompactSummary":true,"message":{"content":"summary"}}),
        ] {
            let mut v = serde_json::json!({"type":"user","timestamp":"2026-10-08T13:00:00Z"});
            for (key, value) in extra.as_object().unwrap() {
                v[key] = value.clone();
            }
            assert!(parse("claude", &v, None).is_none());
        }
    }
    #[test]
    fn claude_duration_is_completion_but_tool_use_is_not() {
        let v = serde_json::json!({"type":"system","subtype":"turn_duration","timestamp":"2026-10-08T13:00:00Z"});
        assert_eq!(
            parse("claude", &v, None).unwrap().status,
            ActivityStatus::Idle
        );
        assert!(parse("claude", &serde_json::json!({"type":"assistant","timestamp":"2026-10-08T13:00:00Z","message":{"stop_reason":"tool_use"}}), None).is_none());
    }
    #[test]
    fn partial_json_is_not_consumed_and_log_truncation_resets_state() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let mut reader = Reader {
            source: Some(Source {
                pid: std::process::id(),
                path: file.path().into(),
                tool: "codex".into(),
            }),
            ..Reader::default()
        };
        fs::write(file.path(), "{\"timestamp\":").unwrap();
        reader.read().unwrap();
        assert_eq!(reader.position, 0);
        fs::write(file.path(), format!("{}\n", codex("task_started", "one"))).unwrap();
        reader.read().unwrap();
        assert!(reader.evidence.is_some());
        fs::write(file.path(), "\n").unwrap();
        reader.read().unwrap();
        assert!(reader.evidence.is_none());
    }
    #[test]
    fn a_silent_native_turn_stays_working() {
        let evidence = parse("codex", &codex("task_started", "one"), None).unwrap();
        let mut state = ActivityState::unknown("No output");
        state.observed_at = evidence.changed_at + 600_000;
        apply(&mut state, &evidence);
        assert_eq!(state.status, ActivityStatus::Working);
    }
    #[test]
    fn fast_turn_between_polls_still_has_a_completion_snapshot() {
        let file = tempfile::NamedTempFile::new().unwrap();
        fs::write(
            file.path(),
            format!(
                "{}\n{}\n",
                codex("task_started", "one"),
                codex("task_complete", "one")
            ),
        )
        .unwrap();
        let mut reader = Reader {
            source: Some(Source {
                pid: std::process::id(),
                path: file.path().into(),
                tool: "codex".into(),
            }),
            ..Reader::default()
        };
        reader.read().unwrap();
        assert_eq!(reader.evidence.unwrap().status, ActivityStatus::Idle);
    }

    #[test]
    fn cold_start_recovers_a_long_running_turn_before_the_last_megabyte() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let noise =
            serde_json::json!({"type":"unrelated","padding":"x".repeat(16 * 1024)}).to_string();
        fs::write(
            file.path(),
            format!(
                "{}\n{}",
                codex("task_started", "long-turn"),
                format!("{noise}\n").repeat(80)
            ),
        )
        .unwrap();
        let mut reader = Reader {
            source: Some(Source {
                pid: std::process::id(),
                path: file.path().into(),
                tool: "codex".into(),
            }),
            ..Reader::default()
        };
        reader.read().unwrap();
        assert_eq!(reader.evidence.unwrap().status, ActivityStatus::Working);
    }
    #[test]
    fn permission_and_failure_footer_override_incomplete_native_turns() {
        let evidence = parse("codex", &codex("task_started", "one"), None).unwrap();
        for status in [ActivityStatus::Waiting, ActivityStatus::Failed] {
            let mut state = ActivityState::unknown("Status");
            state.status = status;
            apply(&mut state, &evidence);
            assert_eq!(state.status, status);
        }
    }
}
