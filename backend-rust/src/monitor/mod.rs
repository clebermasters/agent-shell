use std::{collections::HashMap, sync::Arc, time::Duration};
use tokio::{
    sync::{mpsc, RwLock},
    time::interval,
};
use tracing::{debug, error, info};

use crate::{
    tmux,
    types::{ServerMessage, TmuxSession},
};

#[derive(Debug, Clone, PartialEq)]
struct SessionState {
    sessions: Vec<TmuxSession>,
    // Map of session_name -> (window_count, pane_count)
    window_pane_counts: HashMap<String, (usize, usize)>,
}

pub struct TmuxMonitor {
    state: Arc<RwLock<SessionState>>,
    broadcast_tx: mpsc::UnboundedSender<ServerMessage>,
}

impl TmuxMonitor {
    pub fn new(broadcast_tx: mpsc::UnboundedSender<ServerMessage>) -> Self {
        Self {
            state: Arc::new(RwLock::new(SessionState {
                sessions: Vec::new(),
                window_pane_counts: HashMap::new(),
            })),
            broadcast_tx,
        }
    }

    pub async fn start(&self) {
        info!("Starting tmux monitor");

        // Initial state fetch
        self.check_for_changes().await;

        // Start monitoring loop
        let mut interval = interval(Duration::from_millis(250)); // Check every 250ms for better responsiveness

        loop {
            interval.tick().await;
            self.check_for_changes().await;
        }
    }

    async fn check_for_changes(&self) {
        let previous_state = self.state.read().await.clone();

        let current_sessions = match tmux::list_sessions_basic().await {
            Ok(sessions) => sessions,
            Err(e) => {
                error!("Failed to list tmux sessions: {}", e);
                return;
            }
        };

        // Get detailed window/pane counts for each session
        let mut current_window_pane_counts = HashMap::new();
        for session in &current_sessions {
            match tmux::list_windows(&session.name).await {
                Ok(windows) => {
                    let window_count = windows.len();
                    let pane_count: usize = windows.iter().map(|w| w.panes as usize).sum();
                    current_window_pane_counts
                        .insert(session.name.clone(), (window_count, pane_count));
                }
                Err(e) => {
                    error!("Failed to list windows for session {}: {}", session.name, e);
                }
            }
        }

        // Check if state has changed
        let sessions_changed =
            !sessions_equal_ignoring_tool(&previous_state.sessions, &current_sessions);
        let window_pane_changed = previous_state.window_pane_counts != current_window_pane_counts;

        let has_unknown_tools = current_sessions.iter().any(|session| session.tool.is_none());

        if sessions_changed || window_pane_changed || has_unknown_tools {
            debug!(
                "Tmux state changed - sessions: {}, windows/panes: {}, unknown tools: {}",
                sessions_changed, window_pane_changed, has_unknown_tools
            );

            let current_sessions =
                enrich_tools_from_previous(current_sessions, &previous_state.sessions).await;

            let mut state = self.state.write().await;
            state.sessions = current_sessions.clone();
            state.window_pane_counts = current_window_pane_counts.clone();

            // Broadcast sessions list update
            let message = ServerMessage::SessionsList {
                sessions: current_sessions,
            };

            if let Err(e) = self.broadcast_tx.send(message) {
                error!("Failed to broadcast session update: {}", e);
            }

            // Don't broadcast window updates - let clients request them per session
            // This prevents sessions from getting mixed up when multiple clients
            // are viewing different sessions
        }
    }
}

fn sessions_equal_ignoring_tool(left: &[TmuxSession], right: &[TmuxSession]) -> bool {
    if left.len() != right.len() {
        return false;
    }

    left.iter().zip(right.iter()).all(|(l, r)| {
        l.name == r.name
            && l.attached == r.attached
            && l.created == r.created
            && l.windows == r.windows
            && l.dimensions == r.dimensions
    })
}

async fn enrich_tools_from_previous(
    mut current_sessions: Vec<TmuxSession>,
    previous_sessions: &[TmuxSession],
) -> Vec<TmuxSession> {
    let tool_futures: Vec<_> = current_sessions
        .iter()
        .map(|session| {
            previous_sessions
                .iter()
                .find(|previous| {
                    previous.name == session.name && previous.created == session.created
                })
                .and_then(|previous| previous.tool.clone())
        })
        .enumerate()
        .map(|(index, existing_tool)| async move { (index, existing_tool) })
        .collect();

    for (index, existing_tool) in futures::future::join_all(tool_futures).await {
        if let Some(tool) = existing_tool {
            current_sessions[index].tool = Some(tool);
        }
    }

    let detect_futures: Vec<_> = current_sessions
        .iter()
        .enumerate()
        .filter(|(_, session)| session.tool.is_none())
        .map(|(index, session)| {
            let name = session.name.clone();
            async move {
                (
                    index,
                    crate::chat_log::watcher::detect_tool_name(&name).await,
                )
            }
        })
        .collect();

    for (index, tool) in futures::future::join_all(detect_futures).await {
        current_sessions[index].tool = tool;
    }

    current_sessions
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;

    #[tokio::test]
    async fn test_tmux_monitor_new() {
        let (tx, _rx) = mpsc::unbounded_channel();
        let _monitor = TmuxMonitor::new(tx);
        // Just ensure construction doesn't panic
    }

    #[tokio::test]
    async fn test_check_for_changes_with_running_tmux() {
        let (tx, mut rx) = mpsc::unbounded_channel::<crate::types::ServerMessage>();
        let monitor = TmuxMonitor::new(tx);
        // Run check_for_changes once — should succeed since tmux is running
        monitor.check_for_changes().await;
        // May or may not broadcast depending on whether state changed — just shouldn't panic
        let _ = rx.try_recv();
    }

    // Phase 10: State comparison tests

    #[test]
    fn test_session_state_equality() {
        let state1 = SessionState {
            sessions: vec![],
            window_pane_counts: HashMap::new(),
        };
        let state2 = SessionState {
            sessions: vec![],
            window_pane_counts: HashMap::new(),
        };
        assert_eq!(state1, state2);
    }

    #[test]
    fn test_session_state_inequality_sessions_differ() {
        let state1 = SessionState {
            sessions: vec![],
            window_pane_counts: HashMap::new(),
        };
        let state2 = SessionState {
            sessions: vec![TmuxSession {
                name: "test".to_string(),
                attached: false,
                created: chrono::Utc::now(),
                windows: 1,
                dimensions: "80x24".to_string(),
                tool: None,
            }],
            window_pane_counts: HashMap::new(),
        };
        assert_ne!(state1, state2);
    }

    #[test]
    fn test_session_state_inequality_window_counts_differ() {
        let mut counts1 = HashMap::new();
        counts1.insert("sess".to_string(), (1_usize, 1_usize));
        let mut counts2 = HashMap::new();
        counts2.insert("sess".to_string(), (2_usize, 3_usize));
        let state1 = SessionState {
            sessions: vec![],
            window_pane_counts: counts1,
        };
        let state2 = SessionState {
            sessions: vec![],
            window_pane_counts: counts2,
        };
        assert_ne!(state1, state2);
    }

    #[tokio::test]
    async fn test_new_monitor_starts_with_empty_state() {
        let (tx, _rx) = mpsc::unbounded_channel();
        let monitor = TmuxMonitor::new(tx);
        let state = monitor.state.read().await;
        assert!(state.sessions.is_empty());
        assert!(state.window_pane_counts.is_empty());
    }
}
