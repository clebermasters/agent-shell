use anyhow::{bail, Context, Result};
use std::collections::HashMap;
use std::process::{Output, Stdio};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

type WindowLocks = Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>;
static WINDOW_LOCKS: OnceLock<WindowLocks> = OnceLock::new();

/// Paste one complete message, then submit once to the same pane.
/// Explicit paste avoids AI terminals interpreting Enter as part of a typing burst.
pub async fn send_text_and_enter(session: &str, window: Option<u32>, text: &str) -> Result<()> {
    InputClient::default().send(session, window, text).await
}

pub async fn send_bound_text_and_enter(
    binding: &crate::chat_binding::ChatBinding,
    store: &crate::chat_binding::BindingStore,
    text: &str,
) -> Result<()> {
    InputClient::default()
        .send_pane(
            &binding.pane.pane_id,
            text,
            Some((store.clone(), binding.clone())),
        )
        .await
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

    async fn viewport(client: &InputClient) -> String {
        let output = client
            .output(&[
                "display-message",
                "-p",
                "-t",
                "receiver",
                "#{window_width} #{window_height} #{window-size}",
            ])
            .await
            .unwrap();
        String::from_utf8(output.stdout).unwrap().trim().into()
    }

    async fn short_viewport(receiver: &Receiver) -> (String, InputGuard) {
        receiver
            .client
            .output(&["resize-window", "-t", "receiver", "-x", "47", "-y", "16"])
            .await
            .unwrap();
        let pane = receiver.client.pane("receiver", None).await.unwrap();
        let guard = InputGuard {
            client: receiver.client.clone(),
            viewport: None,
            lock: Some(Arc::new(tokio::sync::Mutex::new(())).lock_owned().await),
        };
        (pane, guard)
    }

    #[tokio::test]
    async fn short_codex_viewport_restores_size_and_local_policy_on_cancellation() {
        let receiver = Receiver::new(true).await;
        let (pane, mut guard) = short_viewport(&receiver).await;
        let original = viewport(&receiver.client).await;
        let lock = tokio::sync::OwnedMutexGuard::mutex(guard.lock.as_ref().unwrap()).clone();
        let (ready, resized) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            guard.reserve_codex_footer(&pane).await.unwrap();
            ready.send(()).unwrap();
            std::future::pending::<()>().await;
            guard.restore().await;
        });
        resized.await.unwrap();
        assert_eq!(viewport(&receiver.client).await, "47 32 manual");
        task.abort();
        let _ = task.await;
        tokio::time::timeout(Duration::from_secs(5), async {
            while viewport(&receiver.client).await != original || lock.try_lock().is_err() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("cancelled submission must restore its viewport and release the input lock");
    }

    #[tokio::test]
    async fn short_codex_viewport_preserves_a_newer_user_resize() {
        let receiver = Receiver::new(true).await;
        let (pane, mut guard) = short_viewport(&receiver).await;
        guard.reserve_codex_footer(&pane).await.unwrap();
        receiver
            .client
            .output(&["resize-window", "-t", "receiver", "-y", "18"])
            .await
            .unwrap();
        guard.restore().await;
        assert_eq!(viewport(&receiver.client).await, "47 18 manual");
    }

    #[tokio::test]
    async fn short_codex_viewport_restores_an_inherited_sizing_policy() {
        let receiver = Receiver::new(true).await;
        let (pane, mut guard) = short_viewport(&receiver).await;
        receiver
            .client
            .output(&["set-option", "-wu", "-t", "receiver", "window-size"])
            .await
            .unwrap();
        let original = viewport(&receiver.client).await;
        guard.reserve_codex_footer(&pane).await.unwrap();
        guard.restore().await;
        assert_eq!(viewport(&receiver.client).await, original);
        let local = receiver
            .client
            .output(&["show-options", "-wv", "-t", "receiver", "window-size"])
            .await
            .unwrap();
        assert!(
            local.stdout.is_empty(),
            "inherited policy must stay inherited"
        );
    }

    #[tokio::test]
    async fn cancelled_viewport_cleanup_finishes_policy_restoration_and_keeps_its_lock() {
        let receiver = Receiver::new(true).await;
        let (pane, mut guard) = short_viewport(&receiver).await;
        receiver
            .client
            .output(&["set-option", "-wu", "-t", "receiver", "window-size"])
            .await
            .unwrap();
        let original = viewport(&receiver.client).await;
        guard.reserve_codex_footer(&pane).await.unwrap();
        let lock = tokio::sync::OwnedMutexGuard::mutex(guard.lock.as_ref().unwrap()).clone();

        // Pause the real resize command after its side effect, before its client
        // returns and the inherited sizing policy can be restored. A TMUX
        // command list alone would also stop here if its client disconnected.
        receiver
            .client
            .output(&["wait-for", "-L", "viewport-cleanup"])
            .await
            .unwrap();
        receiver
            .client
            .output(&[
                "set-hook",
                "-g",
                "after-resize-window",
                "wait-for -L viewport-cleanup",
            ])
            .await
            .unwrap();
        let cleanup = tokio::spawn(async move { guard.restore().await });
        tokio::time::timeout(Duration::from_secs(5), async {
            while viewport(&receiver.client).await != "47 16 manual" {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("cleanup must reach the paused resize hook");

        cleanup.abort();
        assert!(cleanup.await.unwrap_err().is_cancelled());
        assert!(
            lock.try_lock().is_err(),
            "the detached cleanup must retain the input lock while its hook waits"
        );
        receiver
            .client
            .output(&["wait-for", "-U", "viewport-cleanup"])
            .await
            .unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while viewport(&receiver.client).await != original || lock.try_lock().is_err() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("cancelled cleanup must finish the original size and policy restoration");
        let local = receiver
            .client
            .output(&["show-options", "-wv", "-t", "receiver", "window-size"])
            .await
            .unwrap();
        assert!(
            local.stdout.is_empty(),
            "the restored policy must stay inherited"
        );
    }
}

#[derive(Clone, Default)]
struct InputClient {
    socket: Option<String>,
}

// A small split viewport can let Codex's expanded composer consume the entire
// footer. Keep its native ID visible through the pre-Enter validation, then
// restore the original window size and inherited/local sizing policy.
struct InputGuard {
    client: InputClient,
    viewport: Option<InputViewport>,
    lock: Option<tokio::sync::OwnedMutexGuard<()>>,
}

struct InputViewport {
    window: String,
    width: u32,
    height: u32,
    expanded_height: u32,
    size_option: String,
}

impl InputGuard {
    async fn reserve_codex_footer(&mut self, pane: &str) -> Result<()> {
        let output = self
            .client
            .output(&[
                "display-message",
                "-p",
                "-t",
                pane,
                "#{window_id} #{window_width} #{window_height} #{pane_height}",
            ])
            .await?;
        if !output.status.success() {
            bail!("Chat terminal disappeared before submission");
        }
        let snapshot = String::from_utf8(output.stdout)?;
        let fields: Vec<_> = snapshot.split_whitespace().collect();
        if fields.len() != 4 {
            bail!("Invalid chat terminal viewport");
        }
        let width: u32 = fields[1].parse()?;
        let height: u32 = fields[2].parse()?;
        let pane_height: u32 = fields[3].parse()?;
        if pane_height >= 32 {
            return Ok(());
        }
        if pane_height == 0 {
            bail!("Invalid chat terminal height");
        }
        let option = self
            .client
            .output(&["show-options", "-wv", "-t", fields[0], "window-size"])
            .await?;
        if !option.status.success() {
            bail!("Unable to preserve chat terminal sizing policy");
        }
        let expanded_height = height.saturating_mul(32).div_ceil(pane_height);
        self.viewport = Some(InputViewport {
            window: fields[0].into(),
            width,
            height,
            expanded_height,
            size_option: String::from_utf8(option.stdout)?.trim().into(),
        });
        let output = self
            .client
            .output(&[
                "resize-window",
                "-t",
                fields[0],
                "-y",
                &expanded_height.to_string(),
            ])
            .await?;
        if !output.status.success() {
            bail!("Unable to reserve space for the conversation footer");
        }
        Ok(())
    }

    async fn restore(&mut self) {
        if let Some(cleanup) = self.start_restore() {
            // Dropping a JoinHandle detaches its task. If the caller disconnects
            // after resize-window but before policy restoration, cleanup must
            // still finish while retaining the window's input lock.
            let _ = cleanup.await;
        }
    }

    fn start_restore(&mut self) -> Option<tokio::task::JoinHandle<()>> {
        let viewport = self.viewport.take()?;
        let client = self.client.clone();
        let lock = self.lock.take();
        Some(tokio::spawn(async move {
            let _lock = lock;
            viewport.restore(&client).await;
        }))
    }
}

impl InputViewport {
    async fn restore(&self, client: &InputClient) {
        let result = async {
            let output = client
                .output(&[
                    "display-message",
                    "-p",
                    "-t",
                    &self.window,
                    "#{window_width} #{window_height}",
                ])
                .await?;
            let current = String::from_utf8(output.stdout)?;
            // A destroyed window or a newer explicit resize belongs to the user.
            if !output.status.success()
                || current.trim() != format!("{} {}", self.width, self.expanded_height)
            {
                return Ok::<_, anyhow::Error>(());
            }
            let resized = client
                .output(&[
                    "resize-window",
                    "-t",
                    &self.window,
                    "-x",
                    &self.width.to_string(),
                    "-y",
                    &self.height.to_string(),
                ])
                .await?;
            let policy = if self.size_option.is_empty() {
                client
                    .output(&["set-option", "-wu", "-t", &self.window, "window-size"])
                    .await?
            } else {
                client
                    .output(&[
                        "set-option",
                        "-w",
                        "-t",
                        &self.window,
                        "window-size",
                        &self.size_option,
                    ])
                    .await?
            };
            if !resized.status.success() || !policy.status.success() {
                bail!("Unable to restore chat terminal viewport");
            }
            Ok(())
        }
        .await;
        if let Err(error) = result {
            tracing::warn!(%error, "Chat terminal viewport restoration failed");
        }
    }
}

impl Drop for InputGuard {
    fn drop(&mut self) {
        // Cancellation before restore() starts uses the same owned cleanup.
        // If it already started, the detached task owns the viewport and lock.
        drop(self.start_restore());
    }
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
        self.send_pane(&pane, text, None).await
    }

    async fn send_pane(
        &self,
        pane: &str,
        text: &str,
        binding: Option<(
            crate::chat_binding::BindingStore,
            crate::chat_binding::ChatBinding,
        )>,
    ) -> Result<()> {
        let output = self
            .output(&["display-message", "-p", "-t", pane, "#{window_id}"])
            .await?;
        if !output.status.success() {
            bail!("Chat terminal disappeared before submission");
        }
        let window = String::from_utf8(output.stdout)?;
        let key = format!(
            "{}:{}",
            self.socket.as_deref().unwrap_or("default"),
            window.trim()
        );
        let lock = {
            let mut locks = WINDOW_LOCKS
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
        // Serialize text/Enter and temporary sizing across all panes sharing
        // this window; restoring one pane must not hide another sender's ID.
        let mut guard = InputGuard {
            client: self.clone(),
            viewport: None,
            lock: Some(lock.lock_owned().await),
        };
        let result = async {
            if let Some((store, binding)) = &binding {
                if binding.pane.tool == "codex" {
                    guard.reserve_codex_footer(pane).await?;
                }
                if guard.viewport.is_some() {
                    // TMUX acknowledges its resize before the TUI redraws.
                    // Wait for a fresh, exact identity before writing any text.
                    let deadline = tokio::time::Instant::now() + Duration::from_secs(1);
                    loop {
                        match store.validate(binding).await {
                            Ok(()) => break,
                            Err(error) if tokio::time::Instant::now() >= deadline => {
                                return Err(error)
                            }
                            Err(_) => tokio::time::sleep(Duration::from_millis(20)).await,
                        }
                    }
                } else {
                    store.validate(binding).await?;
                }
            }
            let buffer = format!("agentshell-input-{}", uuid::Uuid::new_v4());
            let result = self
                .paste_and_submit(pane, &buffer, text, binding.as_ref())
                .await;
            // Also clean up a named buffer if loading, pasting, or submitting failed.
            let _ = self.output(&["delete-buffer", "-b", &buffer]).await;
            result
        }
        .await;
        guard.restore().await;
        result
    }

    async fn paste_and_submit(
        &self,
        pane: &str,
        buffer: &str,
        text: &str,
        binding: Option<&(
            crate::chat_binding::BindingStore,
            crate::chat_binding::ChatBinding,
        )>,
    ) -> Result<()> {
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
        if let Some((store, binding)) = binding {
            store.validate(binding).await?;
        }
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
