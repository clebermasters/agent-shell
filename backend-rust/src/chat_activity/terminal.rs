use super::ActivityStatus;
use std::hash::{Hash, Hasher};
pub(super) struct Signal {
    pub status: ActivityStatus,
    pub detail: &'static str,
    pub elapsed_ms: Option<i64>,
}
fn signal(status: ActivityStatus, detail: &'static str) -> Signal {
    Signal {
        status,
        detail,
        elapsed_ms: None,
    }
}

fn is_failure_line(line: &str) -> bool {
    let lower = line.trim().to_ascii_lowercase();
    [
        "• stream failed",
        "• conversation failed",
        "• task failed",
        "• reconnect failed",
        "• reconnecting",
        "■ stream disconnected",
    ]
    .iter()
    .any(|prefix| lower.starts_with(prefix))
}

/// A failure timer is a UI refresh, rather than new agent output. Preserve the error text.
pub(super) fn activity_fingerprint(text: &str) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    let line_count = text.lines().count();
    for (index, line) in text.lines().enumerate() {
        let trimmed = line.trim_end();
        let meaningful =
            if index + 12 >= line_count && is_failure_line(trimmed) && trimmed.ends_with(')') {
                trimmed
                    .rfind('(')
                    .and_then(|start| {
                        let timer = &trimmed[start + 1..trimmed.len() - 1];
                        let tokens: Vec<_> = timer.split_whitespace().collect();
                        let duration_only = !tokens.is_empty()
                            && tokens.iter().all(|token| {
                                token.len() > 1
                                    && matches!(token.as_bytes().last(), Some(b'h' | b'm' | b's'))
                                    && token[..token.len() - 1]
                                        .bytes()
                                        .all(|byte| byte.is_ascii_digit())
                            });
                        duration_only.then_some(trimmed[..start].trim_end())
                    })
                    .unwrap_or(line)
            } else {
                line
            };
        meaningful.hash(&mut hasher);
    }
    hasher.finish()
}

// Restrict recognition to the active footer; transcript text is not a runtime status.
pub(super) fn classify(text: &str, command: &str) -> Signal {
    let lines: Vec<_> = text.lines().rev().take(12).map(str::trim).collect();
    for line in &lines {
        if is_failure_line(line) {
            return signal(
                ActivityStatus::Failed,
                "Agent encountered a connection or task error",
            );
        }
    }
    let question = lines.iter().any(|line| {
        let lower = line.to_ascii_lowercase();
        lower.contains("do you want to proceed?")
            || lower.contains("allow this command?")
            || lower.contains("approve this")
            || lower.contains("permission required")
            || lower.contains("would you like to run")
            || lower.contains("do you want to make this edit")
    });
    let choices = lines.iter().any(|line| {
        line.trim_start_matches(|c: char| c == '›' || c == '❯' || c.is_whitespace())
            .starts_with("1.")
            || line.contains("enter to confirm")
            || line.contains("Allow once")
    });
    if question && choices {
        return signal(ActivityStatus::Waiting, "Waiting for your approval");
    }
    for line in &lines {
        let lower = line.to_ascii_lowercase();
        let interrupt = lower.contains("esc to interrupt") || lower.contains("esc interrupt");
        let status_prefix = line.starts_with('•')
            || line.starts_with('✻')
            || line.starts_with('✽')
            || line.starts_with('✢')
            || line.starts_with('✶')
            || line.starts_with('✳')
            || line.starts_with('⠋')
            || line.starts_with('⠙')
            || line.starts_with('⠹')
            || line.starts_with('⠸')
            || line.starts_with('⠼')
            || line.starts_with('⠴')
            || line.starts_with('⠦')
            || line.starts_with('⠧')
            || line.starts_with('⠇')
            || line.starts_with('⠏');
        if interrupt && status_prefix {
            let mut result = signal(ActivityStatus::Working, "Agent is working");
            result.elapsed_ms = line.split_once('(').and_then(|(_, rest)| elapsed_ms(rest));
            return result;
        }
    }
    // The prompt alone is insufficient: Codex displays it during active turns too.
    let codex_prompt = lines.iter().any(|line| line.starts_with('›'));
    let codex_footer = lines
        .iter()
        .any(|line| line.contains("context left") || line.contains("? for shortcuts"));
    let claude_prompt = command == "claude" && lines.iter().any(|line| line.starts_with('❯'));
    if (codex_prompt && codex_footer && matches!(command, "codex" | "node"))
        || (claude_prompt && codex_footer)
    {
        return signal(ActivityStatus::Idle, "Ready for a message");
    }
    signal(ActivityStatus::Unknown, "Agent status is not available")
}

fn elapsed_ms(text: &str) -> Option<i64> {
    let mut digits = String::new();
    let mut seconds = 0i64;
    let mut found = false;
    for c in text.chars() {
        if c.is_ascii_digit() {
            digits.push(c);
        } else if matches!(c, 'h' | 'm' | 's') && !digits.is_empty() {
            let n = digits.parse::<i64>().ok()?;
            digits.clear();
            found = true;
            seconds = seconds.checked_add(n.checked_mul(match c {
                'h' => 3600,
                'm' => 60,
                _ => 1,
            })?)?;
        } else if !c.is_whitespace() {
            break;
        }
    }
    if found {
        seconds.checked_mul(1000)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn codex_timer_is_working_and_has_elapsed_time() {
        let s = classify(
            "• Working (2m 14s • esc to interrupt)\n\n› new prompt\n  94% context left",
            "node",
        );
        assert_eq!(s.status, ActivityStatus::Working);
        assert_eq!(s.elapsed_ms, Some(134000));
    }
    #[test]
    fn failure_timer_is_not_working() {
        assert_eq!(
            classify(
                "• Stream failed — awaiting feedback (10h 46m 04s)\n›\ncontext left",
                "node"
            )
            .status,
            ActivityStatus::Failed
        );
    }

    #[test]
    fn reconnect_failure_takes_precedence_over_an_idle_prompt() {
        assert_eq!(
            classify(
                "• Reconnect failed — waiting for feedback (13h 58m 50s)\n›\n? for shortcuts",
                "node"
            )
            .status,
            ActivityStatus::Failed
        );
    }

    #[test]
    fn failure_timer_ticks_do_not_change_the_activity_fingerprint() {
        assert_eq!(
            activity_fingerprint(
                "response\n• Reconnect failed — waiting for feedback (13h 58m 49s)"
            ),
            activity_fingerprint(
                "response\n• Reconnect failed — waiting for feedback (13h 58m 50s)"
            )
        );
    }

    #[test]
    fn real_output_and_error_changes_remain_activity() {
        let previous = "response\n• Reconnect failed — waiting for feedback (13h 58m 49s)";
        assert_ne!(
            activity_fingerprint(previous),
            activity_fingerprint(
                "new response\n• Reconnect failed — waiting for feedback (13h 58m 50s)"
            )
        );
        assert_ne!(
            activity_fingerprint(previous),
            activity_fingerprint("response\n• Reconnect failed — a different error (13h 58m 50s)")
        );
        assert_ne!(
            activity_fingerprint("• Working (1s • esc to interrupt)"),
            activity_fingerprint("• Working (2s • esc to interrupt)")
        );
    }

    #[test]
    fn transcript_timers_and_error_codes_are_not_discarded() {
        assert_ne!(
            activity_fingerprint(&format!(
                "• Reconnect failed (1s)\n{}",
                "output\n".repeat(20)
            )),
            activity_fingerprint(&format!(
                "• Reconnect failed (2s)\n{}",
                "output\n".repeat(20)
            ))
        );
        assert_ne!(
            activity_fingerprint("• Stream failed (HTTP 429)"),
            activity_fingerprint("• Stream failed (HTTP 500)")
        );
    }
    #[test]
    fn permission_choices_take_precedence_over_spinner() {
        assert_eq!(
            classify(
                "Do you want to proceed?\n1. Yes\n2. No\n• Working (2s • esc to interrupt)",
                "codex"
            )
            .status,
            ActivityStatus::Waiting
        );
    }
    #[test]
    fn idle_codex_requires_prompt_and_footer() {
        assert_eq!(
            classify("Worked for 5m 4s\n›\n? for shortcuts", "node").status,
            ActivityStatus::Idle
        );
        assert_eq!(
            classify("Worked for 5m 4s", "node").status,
            ActivityStatus::Unknown
        );
    }
    #[test]
    fn transcript_statuses_outside_footer_are_ignored() {
        let transcript = format!(
            "• Working (2s • esc to interrupt)\n{}",
            "output\n".repeat(20)
        );
        assert_eq!(
            classify(&transcript, "node").status,
            ActivityStatus::Unknown
        );
    }
    #[test]
    fn unknown_tools_remain_unknown() {
        assert_eq!(
            classify("connecting...", "new-agent").status,
            ActivityStatus::Unknown
        );
    }
    #[test]
    fn claude_spinner_is_working() {
        assert_eq!(
            classify("✻ Cogitating… (esc to interrupt · 1m 2s)", "claude").status,
            ActivityStatus::Working
        );
    }
}
