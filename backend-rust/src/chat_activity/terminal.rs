use super::ActivityStatus;
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

// Restrict recognition to the active footer; transcript text is not a runtime status.
pub(super) fn classify(text: &str, command: &str) -> Signal {
    let lines: Vec<_> = text.lines().rev().take(12).map(str::trim).collect();
    for line in &lines {
        let lower = line.to_ascii_lowercase();
        if [
            "• stream failed",
            "• conversation failed",
            "• task failed",
            "• reconnecting",
            "■ stream disconnected",
        ]
        .iter()
        .any(|prefix| lower.starts_with(prefix))
        {
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
