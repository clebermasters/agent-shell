use anyhow::{bail, Context, Result};
use std::collections::HashMap;
use std::process::{Output, Stdio};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

type PaneLocks = Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>;
static PANE_LOCKS: OnceLock<PaneLocks> = OnceLock::new();

/// Paste one complete message, then submit once to the same pane.
/// Explicit paste avoids AI terminals interpreting Enter as part of a typing burst.
pub async fn send_text_and_enter(session: &str, window: Option<u32>, text: &str) -> Result<()> {
    InputClient::default().send(session, window, text).await
}

#[cfg(test)]
mod tests {
    use super::*;

    // A real PTY receiver with the same 120 ms Enter suppression used by Codex paste bursts.
    // It never runs an AI agent, a shell command from the input, or a network request.
    const RECEIVER: &str = r#"import os,sys,tty,time,json
from pathlib import Path
output=Path(sys.argv[1])
tty.setraw(0)
if sys.argv[2]=='bracketed':
    sys.stdout.write('\x1b[?2004h');sys.stdout.flush()
output.with_suffix('.ready').write_text('ready')
draft=b'';escape=b'';paste=False;last=0;submissions=[]
while True:
    char=os.read(0,1)
    if escape or char==b'\x1b':
        escape+=char
        if escape==b'\x1b[200~':paste=True;escape=b'';continue
        if escape==b'\x1b[201~':paste=False;escape=b'';last=0;continue
        if len(escape)<6:continue
        draft+=escape;escape=b'';continue
    if char==b'\r' and not paste:
        if time.monotonic()-last>.12:
            submissions.append(draft.decode());draft=b''
        else:draft+=b'\n'
        output.write_text(json.dumps(submissions))
    else:
        draft+=char
        if not paste:last=time.monotonic()
"#;

    struct Receiver {
        client: InputClient,
        directory: tempfile::TempDir,
    }

    impl Receiver {
        async fn new(bracketed: bool) -> Self {
            let directory = tempfile::tempdir().unwrap();
            let script = directory.path().join("receiver.py");
            let result = directory.path().join("result.json");
            std::fs::write(&script, RECEIVER).unwrap();
            let client = InputClient {
                socket: Some(format!("agentshell-input-test-{}", uuid::Uuid::new_v4())),
            };
            let command = format!(
                "python3 '{}' '{}' {}",
                super::super::escape_single_quotes(script.to_str().unwrap()),
                super::super::escape_single_quotes(result.to_str().unwrap()),
                if bracketed { "bracketed" } else { "plain" }
            );
            let output = client
                .output(&["new-session", "-d", "-s", "receiver", &command])
                .await
                .unwrap();
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            let receiver = Self { client, directory };
            tokio::time::timeout(Duration::from_secs(5), async {
                while !result.with_extension("ready").exists() {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .expect("receiver should start");
            receiver
        }

        async fn submitted(&self, count: usize) -> Vec<String> {
            tokio::time::timeout(Duration::from_secs(5), async {
                loop {
                    let text = std::fs::read_to_string(self.directory.path().join("result.json"))
                        .unwrap_or_default();
                    if let Ok(messages) = serde_json::from_str::<Vec<String>>(&text) {
                        if messages.len() >= count {
                            return messages;
                        }
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .expect("input must submit without a second Enter")
        }
    }

    impl Drop for Receiver {
        fn drop(&mut self) {
            let _ = std::process::Command::new("tmux")
                .args(["-L", self.client.socket.as_ref().unwrap(), "kill-server"])
                .output();
        }
    }

    #[tokio::test]
    async fn long_unicode_multiline_text_is_pasted_and_submitted_once() {
        let receiver = Receiver::new(true).await;
        // This is larger than the platform's single-argument limit for send-keys -l.
        let text = "Revisão • ação 🧪\n".repeat(10_000);
        receiver
            .client
            .send("receiver", Some(0), &text)
            .await
            .unwrap();
        assert_eq!(receiver.submitted(1).await, vec![text]);
        let output = receiver.client.output(&["list-buffers"]).await.unwrap();
        assert!(!String::from_utf8_lossy(&output.stdout).contains("agentshell-input-"));
    }

    #[tokio::test]
    async fn receiver_without_bracketed_paste_has_time_to_leave_its_paste_window() {
        let receiver = Receiver::new(false).await;
        let text = "A dictated message arriving all at once";
        receiver
            .client
            .send("receiver", Some(0), text)
            .await
            .unwrap();
        assert_eq!(receiver.submitted(1).await, vec![text]);
    }

    #[tokio::test]
    async fn concurrent_messages_do_not_mix_their_text_or_enter() {
        let receiver = Receiver::new(true).await;
        let (a, b, c) = tokio::join!(
            receiver.client.send("receiver", Some(0), "first"),
            receiver.client.send("receiver", Some(0), "second"),
            receiver.client.send("receiver", Some(0), "third"),
        );
        a.unwrap();
        b.unwrap();
        c.unwrap();
        let mut messages = receiver.submitted(3).await;
        messages.sort();
        assert_eq!(messages, ["first", "second", "third"]);
    }

    #[tokio::test]
    async fn missing_window_falls_back_to_one_pinned_active_pane() {
        let receiver = Receiver::new(true).await;
        receiver
            .client
            .send("receiver", Some(99), "fallback")
            .await
            .unwrap();
        assert_eq!(receiver.submitted(1).await, vec!["fallback"]);
    }

    #[tokio::test]
    async fn missing_session_does_not_send_any_enter() {
        let receiver = Receiver::new(true).await;
        assert!(receiver
            .client
            .send("missing-session", Some(0), "must not send")
            .await
            .is_err());
        assert!(!receiver.directory.path().join("result.json").exists());
    }
}

#[derive(Default)]
struct InputClient {
    socket: Option<String>,
}

impl InputClient {
    fn command(&self) -> Command {
        let mut command = Command::new("tmux");
        if let Some(socket) = &self.socket {
            command.args(["-L", socket]);
        }
        command.kill_on_drop(true);
        command
    }

    async fn output(&self, args: &[&str]) -> Result<Output> {
        tokio::time::timeout(Duration::from_secs(5), self.command().args(args).output())
            .await
            .context("TMUX input command timed out")?
            .context("Unable to run TMUX input command")
    }

    async fn pane(&self, session: &str, window: Option<u32>) -> Result<String> {
        let target =
            window.map_or_else(|| session.to_owned(), |index| format!("{session}:{index}"));
        let mut output = self
            .output(&["display-message", "-p", "-t", &target, "#{pane_id}"])
            .await?;
        if !output.status.success() && window.is_some() {
            output = self
                .output(&["display-message", "-p", "-t", session, "#{pane_id}"])
                .await?;
        }
        if !output.status.success() {
            bail!(
                "Unable to find chat terminal: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        let pane = String::from_utf8(output.stdout)?.trim().to_owned();
        if !pane.starts_with('%')
            || pane.len() < 2
            || !pane[1..].chars().all(|c| c.is_ascii_digit())
        {
            bail!("TMUX returned an invalid chat pane");
        }
        Ok(pane)
    }

    async fn send(&self, session: &str, window: Option<u32>, text: &str) -> Result<()> {
        if text.is_empty() {
            return Ok(());
        }
        let pane = self.pane(session, window).await?;
        let key = format!("{}:{pane}", self.socket.as_deref().unwrap_or("default"));
        let lock = {
            let mut locks = PANE_LOCKS
                .get_or_init(|| Mutex::new(HashMap::new()))
                .lock()
                .map_err(|_| anyhow::anyhow!("TMUX input lock is unavailable"))?;
            locks.retain(|_, value| value.strong_count() > 0);
            match locks.get(&key).and_then(Weak::upgrade) {
                Some(lock) => lock,
                None => {
                    let lock = Arc::new(tokio::sync::Mutex::new(()));
                    locks.insert(key, Arc::downgrade(&lock));
                    lock
                }
            }
        };
        // Keep concurrent clients from interleaving their text and Enter in one pane.
        let _guard = lock.lock().await;
        let buffer = format!("agentshell-input-{}", uuid::Uuid::new_v4());
        let result = self.paste_and_submit(&pane, &buffer, text).await;
        // Also clean up a named buffer if loading, pasting, or submitting failed.
        let _ = self.output(&["delete-buffer", "-b", &buffer]).await;
        result
    }

    async fn paste_and_submit(&self, pane: &str, buffer: &str, text: &str) -> Result<()> {
        let mut child = self
            .command()
            .args(["load-buffer", "-b", buffer, "-"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        let mut stdin = child
            .stdin
            .take()
            .context("Unable to open TMUX paste input")?;
        tokio::time::timeout(Duration::from_secs(5), stdin.write_all(text.as_bytes()))
            .await
            .context("Writing TMUX paste timed out")??;
        drop(stdin);
        let output = tokio::time::timeout(Duration::from_secs(5), child.wait_with_output())
            .await
            .context("Loading TMUX paste timed out")??;
        if !output.status.success() {
            bail!(
                "Unable to load chat message: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        // -p uses bracketed paste only when the application requested it; -r preserves newlines.
        let output = self
            .output(&["paste-buffer", "-p", "-r", "-d", "-b", buffer, "-t", pane])
            .await?;
        if !output.status.success() {
            bail!(
                "Unable to paste chat message: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        // Leave room for receivers without bracketed-paste support to finish their paste window.
        tokio::time::sleep(Duration::from_millis(250)).await;
        let output = self.output(&["send-keys", "-t", pane, "Enter"]).await?;
        if !output.status.success() {
            bail!(
                "Unable to submit chat message: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        tracing::debug!(pane, bytes = text.len(), "Pasted and submitted chat input");
        Ok(())
    }
}
