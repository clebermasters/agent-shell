use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use std::{
    io::{Read, Write},
    sync::Arc,
};
use tokio::sync::{mpsc, Mutex};
use tracing::{debug, error, info, warn};

use super::types::{send_message, BroadcastMessage, PtySession, WsState};
use crate::{audio, tmux, types::*};

// ── Input handlers ────────────────────────────────────────────────────────

pub(crate) async fn handle_input(state: &WsState, data: String) -> anyhow::Result<()> {
    let pty_opt = state.current_pty.lock().await;
    if let Some(ref pty) = *pty_opt {
        let mut writer = pty.writer.lock().await;
        if let Err(e) = writer.write_all(data.as_bytes()) {
            error!("Failed to write to PTY: {}", e);
            return Err(e.into());
        }
        writer.flush()?;
    } else {
        debug!("No PTY session active, ignoring input");
    }
    Ok(())
}

pub(crate) async fn handle_input_via_tmux(
    state: &WsState,
    session_name: Option<String>,
    window_index: Option<u32>,
    data: String,
) -> anyhow::Result<()> {
    info!("Received InputViaTmux: {:?}", data);
    info!(
        "  session_name: {:?}, window_index: {:?}",
        session_name, window_index
    );

    // Get session and window from message, fallback to global state
    let global_session = state.current_session.lock().await.clone();
    let global_window = *state.current_window.lock().await;

    info!(
        "  global_session: {:?}, global_window: {:?}",
        global_session, global_window
    );

    let session = session_name.or(global_session.clone());
    let idx = window_index.or(global_window);

    info!("  Using session: {:?}, window: {:?}", session, idx);

    if let Some(ref session) = session {
        // Build target: try session:window first, fall back to session only
        // (lets tmux use the active window when the specified index doesn't exist).
        let target = if let Some(window_idx) = idx {
            format!("{}:{}", session, window_idx)
        } else {
            session.clone()
        };

        // Send text literally, then Enter as a separate tmux invocation.
        // Note: ";" in .args() is passed as a literal argument, not a tmux
        // command separator. Two separate calls are required.
        let text = data.trim_end_matches('\n');
        let send_text = tokio::process::Command::new("tmux")
            .args(&["send-keys", "-t", &target, "-l", text])
            .output()
            .await;

        // If session:window failed (window doesn't exist), retry with session only
        let send_text = match &send_text {
            Ok(output) if !output.status.success() && idx.is_some() => {
                warn!("InputViaTmux: target {} failed, retrying with session only ({})", target, session);
                tokio::process::Command::new("tmux")
                    .args(&["send-keys", "-t", session, "-l", text])
                    .output()
                    .await
            }
            _ => send_text,
        };

        // Small delay so the TUI (e.g. OpenCode) finishes processing the
        // typed characters before Enter arrives.  Without this, Enter can
        // be consumed by an in-progress redraw and the message is dropped.
        tokio::time::sleep(tokio::time::Duration::from_millis(80)).await;

        // Use the same target that succeeded for send-text
        let effective_target = match &send_text {
            Ok(output) if output.status.success() && idx.is_some() => {
                // Check if original target worked or we fell back
                target.clone()
            }
            _ => session.clone(),
        };
        let send_enter = tokio::process::Command::new("tmux")
            .args(&["send-keys", "-t", &effective_target, "Enter"])
            .output()
            .await;

        match (send_text, send_enter) {
            (Ok(t), Ok(e)) if t.status.success() && e.status.success() => {
                info!("InputViaTmux: OK sent {:?} to target {}", text, effective_target);
            }
            (Ok(t), Ok(e)) => {
                error!("InputViaTmux: tmux send-keys FAILED for {} — text_exit={}, enter_exit={}, text_stderr={:?}, enter_stderr={:?}",
                    effective_target, t.status, e.status,
                    String::from_utf8_lossy(&t.stderr),
                    String::from_utf8_lossy(&e.stderr));
            }
            (Err(e), _) | (_, Err(e)) => {
                error!(
                    "InputViaTmux: failed to spawn tmux for {}: {}",
                    effective_target, e
                );
            }
        }
    } else {
        warn!(
            "No session/window available. Message: {:?}, session: {:?}, window: {:?}",
            data, session, idx
        );
    }
    Ok(())
}

pub(crate) async fn handle_send_enter_key(state: &WsState) -> anyhow::Result<()> {
    info!("Received SendEnterKey");
    let session_name = state.current_session.lock().await.clone();
    let window_index = state.current_window.lock().await;
    if let (Some(session), Some(idx)) = (session_name, *window_index) {
        match tmux::send_special_key(&session, idx, "Enter").await {
            Ok(_) => debug!("Sent Enter key via tmux"),
            Err(e) => error!("Failed to send Enter via tmux: {}", e),
        }
    } else {
        debug!("No session/window active, ignoring Enter key");
    }
    Ok(())
}

pub(crate) async fn handle_resize(state: &WsState, cols: u16, rows: u16) -> anyhow::Result<()> {
    let pty_opt = state.current_pty.lock().await;
    if let Some(ref pty) = *pty_opt {
        let master = pty.master.lock().await;
        master.resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        debug!("Resized PTY to {}x{}", cols, rows);
    } else {
        debug!("No PTY session active, ignoring resize");
    }
    Ok(())
}

pub(crate) async fn handle_attach(
    state: &mut WsState,
    session_name: String,
    cols: u16,
    rows: u16,
    window_index: Option<u32>,
) -> anyhow::Result<()> {
    info!("Attaching to session: {}", session_name);

    // Set current session and window for input handling
    *state.current_session.lock().await = Some(session_name.clone());
    *state.current_window.lock().await = window_index;

    attach_to_session(state, &session_name, cols, rows).await
}

// ── PTY session lifecycle ─────────────────────────────────────────────────

pub(crate) async fn attach_to_session(
    state: &mut WsState,
    session_name: &str,
    cols: u16,
    rows: u16,
) -> anyhow::Result<()> {
    let tx = &state.message_tx;
    // Update current session
    {
        let mut current = state.current_session.lock().await;
        *current = Some(session_name.to_string());
    }
    // History bootstrap: use an AtomicBool so the blocking reader thread can
    // see when the async bootstrap task is finished without a Mutex.
    use std::sync::atomic::{AtomicBool, Ordering};
    let bootstrap_done = Arc::new(AtomicBool::new(false));
    let bootstrap_done_reader = bootstrap_done.clone();
    // Bounded live-output queue (256 msgs) — reader enqueues during bootstrap.
    const QUEUE_CAP: usize = 256;
    let (live_queue_tx, live_queue_rx) = mpsc::channel::<String>(QUEUE_CAP);
    let live_queue_tx_clone = live_queue_tx.clone();

    // Clean up any existing PTY session first
    let mut pty_guard = state.current_pty.lock().await;
    if let Some(old_pty) = pty_guard.take() {
        debug!(
            "Cleaning up previous PTY session for tmux: {}",
            old_pty.tmux_session
        );
        // Kill the child process
        {
            let mut child = old_pty.child.lock().await;
            let _ = child.kill();
            let _ = child.wait();
        }
        // Abort the reader task
        old_pty.reader_task.abort();
        let _ = old_pty.reader_task.await;
    }

    // Small delay to ensure cleanup is complete
    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    // Clone the shared kiro sender Arc — the PTY reader will check it dynamically
    // on each output chunk. WatchChatLog sets the sender when kiro is detected.
    let kiro_shared_tx = state.kiro_chat_output_tx.clone();

    // Create new PTY session with the exact dimensions requested by the client.
    // Previously we used max(tmux_window, requested) to avoid shrinking, but that
    // created a PTY larger than the client terminal.  When the client later resized
    // the PTY *down*, tmux's cursor positioning during the shrink transition was
    // unreliable — the cursor would land one row below the prompt.  Using the exact
    // requested size means the subsequent autoResize from xterm is a *grow* (24→N),
    // which tmux handles correctly.
    let (effective_cols, effective_rows) = (cols, rows);
    info!(
        "PTY size: requested {}x{}, effective {}x{}",
        cols, rows, effective_cols, effective_rows
    );

    let pty_system = native_pty_system();
    let pair = pty_system.openpty(PtySize {
        rows: effective_rows,
        cols: effective_cols,
        pixel_width: 0,
        pixel_height: 0,
    })?;

    let mut cmd = CommandBuilder::new("tmux");
    cmd.args(&["attach-session", "-t", session_name]);
    cmd.env("TERM", "xterm");
    cmd.env("COLORTERM", "truecolor");

    // Clear SSH-related environment variables that might confuse starship
    cmd.env_remove("SSH_CLIENT");
    cmd.env_remove("SSH_CONNECTION");
    cmd.env_remove("SSH_TTY");
    cmd.env_remove("SSH_AUTH_SOCK");

    // Set up proper environment for local terminal
    cmd.env("AGENTSHELL", "1");

    // Get reader before we move master
    let reader = pair.master.try_clone_reader()?;

    // Get writer and spawn command
    let writer = pair.master.take_writer()?;
    let writer = Arc::new(Mutex::new(writer));

    // First check if session exists, if not create it
    let check_output = tokio::process::Command::new("tmux")
        .args(&["has-session", "-t", session_name])
        .output()
        .await?;

    if !check_output.status.success() {
        // Create the session first
        info!("Session {} doesn't exist, creating it", session_name);
        tmux::create_session(session_name).await?;
    }

    let child = pair.slave.spawn_command(cmd)?;
    let child: Arc<Mutex<Box<dyn portable_pty::Child + Send>>> = Arc::new(Mutex::new(child));

    // Set up reader task — queues output during bootstrap, then direct-forwards
    let tx_clone = tx.clone();
    let kiro_shared_tx_reader = kiro_shared_tx.clone();
    let client_id = state.client_id.clone();
    let reader_task = tokio::task::spawn_blocking(move || {
        let mut reader = reader;
        let mut buffer = vec![0u8; 8192];
        let mut consecutive_errors = 0;
        let mut utf8_decoder = crate::terminal_buffer::Utf8StreamDecoder::new();
        let mut pending_output = String::with_capacity(16384);
        let mut last_send = std::time::Instant::now();
        let mut bytes_since_pause = 0usize;

        loop {
            match reader.read(&mut buffer) {
                Ok(0) => {
                    info!("PTY EOF for client {}", client_id);
                    if !pending_output.is_empty()
                        && bootstrap_done_reader.load(Ordering::Relaxed)
                    {
                        let output = ServerMessage::Output {
                            data: pending_output,
                        };
                        if let Ok(json) = serde_json::to_string(&output) {
                            let _ = tx_clone.send(BroadcastMessage::Text(Arc::new(json)));
                        }
                    }
                    break;
                }
                Ok(n) => {
                    consecutive_errors = 0;
                    let (text, _) = utf8_decoder.decode_chunk(&buffer[..n]);
                    if !text.is_empty() {
                        let filtered =
                            crate::terminal_buffer::filter_control_sequences(&text);
                        if !filtered.is_empty() {
                            pending_output.push_str(&filtered);
                            bytes_since_pause += filtered.len();
                        }

                        // Always flush pending output after each read.
                        // Previously we batched (>1KB || >10ms || has \n) for
                        // throughput, but that caused prompts without a trailing
                        // newline to stay buffered: the next read() blocks
                        // because the shell is waiting for input, so the 10ms
                        // deadline is never re-checked and the prompt never
                        // reaches the client until the user types.
                        let should_send = !pending_output.is_empty();

                        if should_send && !pending_output.is_empty() {
                            if bootstrap_done_reader.load(Ordering::Relaxed) {
                                // Direct path: bootstrap finished
                                let output = ServerMessage::Output {
                                    data: pending_output.clone(),
                                };
                                if let Ok(json) = serde_json::to_string(&output) {
                                    if tx_clone
                                        .send(BroadcastMessage::Text(Arc::new(json)))
                                        .is_err()
                                    {
                                        error!(
                                            "Client {} disconnected, stopping PTY reader",
                                            client_id
                                        );
                                        break;
                                    }
                                }
                                // Forward raw output to kiro parser if registered
                                if let Ok(guard) = kiro_shared_tx_reader.lock() {
                                    if let Some(ref kiro_tx) = *guard {
                                        let _ = kiro_tx.send(pending_output.clone());
                                    }
                                }
                            } else {
                                // Bootstrap path: queue or overflow to direct
                                match live_queue_tx_clone
                                    .try_send(pending_output.clone())
                                {
                                    Ok(_) => {
                                        // Also forward to kiro during bootstrap
                                        if let Ok(guard) = kiro_shared_tx_reader.lock() {
                                            if let Some(ref kiro_tx) = *guard {
                                                let _ = kiro_tx.send(pending_output.clone());
                                            }
                                        }
                                    }
                                    Err(_) => {
                                        tracing::warn!("[history-bootstrap] queue overflow, switching to direct forward for client {}", client_id);
                                        bootstrap_done_reader
                                            .store(true, Ordering::Relaxed);
                                        let output = ServerMessage::Output {
                                            data: pending_output.clone(),
                                        };
                                        if let Ok(json) =
                                            serde_json::to_string(&output)
                                        {
                                            if tx_clone
                                                .send(BroadcastMessage::Text(
                                                    Arc::new(json),
                                                ))
                                                .is_err()
                                            {
                                                error!(
                                                    "Client {} disconnected, stopping PTY reader",
                                                    client_id
                                                );
                                                break;
                                            }
                                        }
                                        // Forward raw output to kiro parser if registered
                                        if let Ok(guard) = kiro_shared_tx_reader.lock() {
                                            if let Some(ref kiro_tx) = *guard {
                                                let _ = kiro_tx.send(pending_output.clone());
                                            }
                                        }
                                    }
                                }
                            }

                            pending_output.clear();
                            last_send = std::time::Instant::now();

                            if bytes_since_pause > 65536 {
                                std::thread::sleep(std::time::Duration::from_millis(
                                    5,
                                ));
                                bytes_since_pause = 0;
                            }
                        }
                    }
                }
                Err(e) => {
                    consecutive_errors += 1;
                    if consecutive_errors > 5 {
                        error!(
                            "Too many consecutive PTY read errors for client {}: {}",
                            client_id, e
                        );
                        break;
                    }
                    error!(
                        "PTY read error for client {} (attempt {}): {}",
                        client_id, consecutive_errors, e
                    );
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
            }
        }

        let disconnected = ServerMessage::Disconnected;
        if let Ok(json) = serde_json::to_string(&disconnected) {
            let _ = tx_clone.send(BroadcastMessage::Text(Arc::new(json)));
        }
    });

    let pty_session = PtySession {
        writer: writer.clone(),
        master: Arc::new(Mutex::new(pair.master)),
        reader_task,
        child,
        tmux_session: session_name.to_string(),
    };

    *pty_guard = Some(pty_session);
    drop(pty_guard);

    // Send attached confirmation first
    let response = ServerMessage::Attached {
        session_name: session_name.to_string(),
    };
    send_message(tx, response).await?;

    // ── Async bootstrap task ─────────────────────────────────────────────────
    // Captures tmux scrollback history, streams it in chunks, then flushes
    // queued live output so nothing is lost or reordered.
    let tx_bootstrap = tx.clone();
    let session_owned = session_name.to_string();
    let window_index_val = *state.current_window.lock().await;
    let mut live_queue_rx = live_queue_rx;

    tokio::spawn(async move {
        let window = window_index_val.unwrap_or(0);
        let bootstrap_start = std::time::Instant::now();

        match tmux::capture_history_above_viewport(&session_owned, window).await {
            Ok(history_text) if !history_text.is_empty() => {
                let total_lines = history_text.lines().count() as i64;
                const CHUNK_SIZE: usize = 24 * 1024;
                let chunks = tmux::chunk_terminal_stream(&history_text, CHUNK_SIZE);
                let total_chunks = chunks.len();

                info!(
                    "[history-bootstrap] captured {} lines in {:.1}ms, {} chunks, {} bytes",
                    total_lines,
                    bootstrap_start.elapsed().as_secs_f64() * 1000.0,
                    total_chunks,
                    history_text.len()
                );

                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryStart {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines,
                        chunk_size: CHUNK_SIZE,
                        generated_at: chrono::Utc::now(),
                    },
                )
                .await;

                for (seq, chunk) in chunks.iter().enumerate() {
                    let line_count = chunk.lines().count();
                    let is_last = seq + 1 == total_chunks;
                    let _ = send_message(
                        &tx_bootstrap,
                        ServerMessage::TerminalHistoryChunk {
                            session_name: session_owned.clone(),
                            window_index: window,
                            seq,
                            data: chunk.clone(),
                            line_count,
                            is_last,
                        },
                    )
                    .await;
                }

                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines,
                        total_chunks,
                    },
                )
                .await;
            }
            Ok(_) => {
                info!(
                    "[history-bootstrap] no scrollback history for {}:{}",
                    session_owned, window
                );
                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines: 0,
                        total_chunks: 0,
                    },
                )
                .await;
            }
            Err(e) => {
                tracing::warn!(
                    "[history-bootstrap] failed for {}:{} — {}",
                    session_owned,
                    window,
                    e
                );
                let _ = send_message(
                    &tx_bootstrap,
                    ServerMessage::TerminalHistoryEnd {
                        session_name: session_owned.clone(),
                        window_index: window,
                        total_lines: 0,
                        total_chunks: 0,
                    },
                )
                .await;
            }
        }

        // Signal reader to switch to direct mode
        bootstrap_done.store(true, Ordering::Relaxed);
        // Drop sender so the receiver loop terminates
        drop(live_queue_tx);

        // Flush live output that arrived during bootstrap
        let mut flushed = 0usize;
        while let Some(data) = live_queue_rx.recv().await {
            flushed += data.len();
            let output = ServerMessage::Output { data };
            if let Ok(json) = serde_json::to_string(&output) {
                if tx_bootstrap
                    .send(BroadcastMessage::Text(Arc::new(json)))
                    .is_err()
                {
                    break;
                }
            }
        }

        if flushed > 0 {
            info!(
                "[history-bootstrap] flushed {} bytes of queued live output",
                flushed
            );
        }
        info!(
            "[history-bootstrap] complete in {:.1}ms",
            bootstrap_start.elapsed().as_secs_f64() * 1000.0
        );
    });

    Ok(())
}

pub(crate) async fn cleanup_session(state: &WsState, graceful: bool) {
    info!("Cleaning up session for client: {} (graceful={})", state.client_id, graceful);

    // Clean up PTY session
    let mut pty_guard = state.current_pty.lock().await;
    if let Some(pty) = pty_guard.take() {
        info!("Cleaning up PTY for tmux session: {}", pty.tmux_session);

        if graceful {
            // Graceful shutdown: don't kill child process, let PTY EOF naturally.
            // The tmux attach-session process will exit on its own when the PTY closes.
            // The tmux session itself is unaffected (owned by tmux server daemon).
            info!("Graceful cleanup: dropping PTY handles for session {}", pty.tmux_session);
        } else {
            // Normal disconnect: kill the child process
            {
                let mut child = pty.child.lock().await;
                let _ = child.kill();
                let _ = child.wait();
            }

            // Abort the reader task
            pty.reader_task.abort();
        }

        // Writer and master will be dropped automatically
    }
    drop(pty_guard);

    // Clean up chat log watcher
    {
        let mut handle_guard = state.chat_log_handle.lock().await;
        if let Some(handle) = handle_guard.take() {
            handle.abort();
        }
    }

    // Clean up audio streaming
    if let Some(ref audio_tx) = state.audio_tx {
        if let Err(e) = audio::stop_streaming_for_client(audio_tx).await {
            error!("Failed to stop audio streaming: {}", e);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use tokio::sync::{mpsc, Mutex};
    use crate::websocket::{
        client_manager::ClientManager,
        types::{BroadcastMessage, WsState},
    };
    use crate::{
        chat_clear_store::ChatClearStore,
        chat_event_store::ChatEventStore,
        chat_file_storage::ChatFileStorage,
    };

    fn make_ws_state(dir: &std::path::Path) -> (WsState, mpsc::UnboundedReceiver<BroadcastMessage>) {
        let (tx, rx) = mpsc::unbounded_channel::<BroadcastMessage>();
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let ws_state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None,
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            client_manager,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
        };
        (ws_state, rx)
    }

    #[tokio::test]
    async fn test_handle_input_no_pty() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        let result = handle_input(&ws_state, "hello".to_string()).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_handle_resize_no_pty() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        let result = handle_resize(&ws_state, 80, 24).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_handle_send_enter_key_no_session() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        let result = handle_send_enter_key(&ws_state).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_handle_attach_sets_state() {
        let dir = tempfile::TempDir::new().unwrap();
        let (mut ws_state, _rx) = make_ws_state(dir.path());
        let test_session = format!("test-attach-{}", chrono::Utc::now().timestamp_millis());
        *ws_state.current_session.lock().await = None;
        *ws_state.current_window.lock().await = None;

        // handle_attach sets current_session and current_window before attempting attach
        let _ = handle_attach(&mut ws_state, test_session.clone(), 80, 24, Some(0)).await;
        let session = ws_state.current_session.lock().await.clone();
        assert_eq!(session, Some(test_session.clone()));
        let window = *ws_state.current_window.lock().await;
        assert_eq!(window, Some(0));
        // Cleanup: kill the tmux session created by attach_to_session
        let _ = crate::tmux::kill_session(&test_session).await;
    }

    #[tokio::test]
    async fn test_handle_input_via_tmux_no_session() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        // No session or window in state or message
        let result = handle_input_via_tmux(&ws_state, None, None, "test".to_string()).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_cleanup_session_no_pty() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        // Should not panic with nothing active
        cleanup_session(&ws_state, false).await;
    }

    #[tokio::test]
    async fn test_cleanup_session_with_chat_log_handle() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        // Set a chat_log_handle to verify it gets aborted
        let handle = tokio::spawn(async { tokio::time::sleep(tokio::time::Duration::from_secs(60)).await });
        *ws_state.chat_log_handle.lock().await = Some(handle);
        cleanup_session(&ws_state, false).await;
        // After cleanup, handle should be taken (None)
        assert!(ws_state.chat_log_handle.lock().await.is_none());
    }

    #[tokio::test]
    async fn test_handle_input_via_tmux_with_session() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        // With a session but no window - should still handle gracefully
        let result = handle_input_via_tmux(&ws_state, Some("AgentShell".to_string()), Some(0), "test".to_string()).await;
        // May succeed or fail depending on tmux state - just verify no panic
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_handle_send_enter_key_via_tmux() {
        let dir = tempfile::TempDir::new().unwrap();
        let (ws_state, _rx) = make_ws_state(dir.path());
        // Without a session, it should handle gracefully
        let result = handle_send_enter_key(&ws_state).await;
        assert!(result.is_ok());
    }
}
