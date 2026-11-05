use std::{collections::HashMap, sync::Arc, time::Duration};
use tokio::{
    sync::{mpsc, RwLock},
    time::interval,
};
use tracing::{debug, error, info, warn};

use crate::{
    tmux,
    types::{ServerMessage, TmuxSession, TmuxWindow},
};

const SESSION_POLL_INTERVAL: Duration = Duration::from_secs(1);
const WINDOW_DETAIL_REFRESH_TICKS: usize = 5;

#[derive(Debug, Clone, PartialEq)]
struct SessionState {
    sessions: Vec<TmuxSession>,
    // Map of session_name -> (window_count, pane_count)
    window_pane_counts: HashMap<String, (usize, usize)>,
}

pub struct TmuxMonitor {
    state: Arc<RwLock<SessionState>>,
    broadcast_tx: mpsc::Sender<ServerMessage>,
    source: Arc<dyn MonitorSource>,
}

#[async_trait::async_trait]
trait MonitorSource: Send + Sync {
    async fn sessions(&self) -> anyhow::Result<Vec<TmuxSession>>;
    async fn windows(&self, session: &str) -> anyhow::Result<Vec<TmuxWindow>>;
    async fn tool(&self, session: &str) -> anyhow::Result<Option<String>>;
}

struct SystemMonitorSource;

#[async_trait::async_trait]
impl MonitorSource for SystemMonitorSource {
    async fn sessions(&self) -> anyhow::Result<Vec<TmuxSession>> {
        tmux::list_sessions_basic().await
    }

    async fn windows(&self, session: &str) -> anyhow::Result<Vec<TmuxWindow>> {
        tmux::list_windows(session).await
    }

    async fn tool(&self, session: &str) -> anyhow::Result<Option<String>> {
        crate::chat_log::watcher::detect_tool_name_checked(session).await
    }
}

impl TmuxMonitor {
    pub fn new(broadcast_tx: mpsc::Sender<ServerMessage>) -> Self {
        Self::with_source(broadcast_tx, Arc::new(SystemMonitorSource))
    }

    fn with_source(
        broadcast_tx: mpsc::Sender<ServerMessage>,
        source: Arc<dyn MonitorSource>,
    ) -> Self {
        Self {
            state: Arc::new(RwLock::new(SessionState {
                sessions: Vec::new(),
                window_pane_counts: HashMap::new(),
            })),
            broadcast_tx,
            source,
        }
    }

    pub async fn start(&self) {
        info!("Starting tmux monitor");

        // Initial state fetch
        self.check_for_changes(true).await;

        // Start monitoring loop
        let mut interval = interval(SESSION_POLL_INTERVAL);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        // The explicit initial sample above already handled the immediate tick.
        interval.tick().await;
        let mut ticks_since_window_refresh = 0usize;

        loop {
            interval.tick().await;
            ticks_since_window_refresh += 1;
            let refresh_window_details = ticks_since_window_refresh >= WINDOW_DETAIL_REFRESH_TICKS;
            self.check_for_changes(refresh_window_details).await;
            if refresh_window_details {
                ticks_since_window_refresh = 0;
            }
        }
    }

    async fn check_for_changes(&self, refresh_window_details: bool) {
        let previous_state = self.state.read().await.clone();

        let current_sessions = match self.source.sessions().await {
            Ok(sessions) => sessions,
            Err(e) => {
                error!("Failed to list tmux sessions: {}", e);
                return;
            }
        };

        let sessions_changed =
            !sessions_equal_ignoring_tool(&previous_state.sessions, &current_sessions);
        let should_refresh_window_details = refresh_window_details || sessions_changed;

        let current_window_pane_counts = if should_refresh_window_details {
            let mut counts = HashMap::new();
            for session in &current_sessions {
                match self.source.windows(&session.name).await {
                    Ok(windows) => {
                        let window_count = windows.len();
                        let pane_count: usize = windows.iter().map(|w| w.panes as usize).sum();
                        counts.insert(session.name.clone(), (window_count, pane_count));
                    }
                    Err(e) => {
                        error!("Failed to list windows for session {}: {}", session.name, e);
                        // An unsuccessful sample is not evidence of removed panes.
                        // Recreated sessions must not inherit an old session's counts.
                        if matching_session(session, &previous_state.sessions).is_some() {
                            if let Some(count) =
                                previous_state.window_pane_counts.get(&session.name)
                            {
                                counts.insert(session.name.clone(), *count);
                            }
                        }
                    }
                }
            }
            counts
        } else {
            previous_state.window_pane_counts.clone()
        };
        // Basic samples intentionally omit tools. None is a valid detection result,
        // not a reason to broadcast or re-scan every second. Refresh known tools as
        // well as ordinary shells on the bounded detail cadence so exits/switches
        // are still discovered.
        let current_sessions = enrich_tools_from_previous(
            current_sessions,
            &previous_state.sessions,
            refresh_window_details,
            self.source.as_ref(),
        )
        .await;
        let next_state = SessionState {
            sessions: current_sessions.clone(),
            window_pane_counts: current_window_pane_counts,
        };

        if next_state != previous_state {
            debug!("Tmux session or pane metadata changed");
            // Do not hold the state write lock while waiting for a receiver.
            *self.state.write().await = next_state;

            // Broadcast sessions list update
            let message = ServerMessage::SessionsList {
                sessions: current_sessions,
            };

            if let Err(e) = self.broadcast_tx.send(message).await {
                error!("Failed to broadcast session update: {}", e);
            }

            // Don't broadcast window updates - let clients request them per session
            // This prevents sessions from getting mixed up when multiple clients
            // are viewing different sessions
        }
    }
}

fn matching_session<'a>(
    session: &TmuxSession,
    previous_sessions: &'a [TmuxSession],
) -> Option<&'a TmuxSession> {
    previous_sessions
        .iter()
        .find(|previous| previous.name == session.name && previous.created == session.created)
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
    current_sessions: Vec<TmuxSession>,
    previous_sessions: &[TmuxSession],
    refresh_tools: bool,
    source: &dyn MonitorSource,
) -> Vec<TmuxSession> {
    let detect_futures: Vec<_> = current_sessions
        .into_iter()
        .map(|mut session| {
            let previous = matching_session(&session, previous_sessions);
            async move {
                if !refresh_tools && previous.is_some() {
                    session.tool = previous.and_then(|old| old.tool.clone());
                    return session;
                }
                session.tool = match source.tool(&session.name).await {
                    Ok(tool) => tool,
                    Err(error) => {
                        warn!(
                            "Failed to inspect tool for session {}: {}",
                            session.name, error
                        );
                        previous.and_then(|old| old.tool.clone())
                    }
                };
                session
            }
        })
        .collect();
    futures::future::join_all(detect_futures).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        collections::HashSet,
        sync::{
            atomic::{AtomicUsize, Ordering},
            Mutex,
        },
    };
    use tokio::sync::mpsc;

    #[derive(Default)]
    struct Fixture {
        sessions: Vec<TmuxSession>,
        windows: HashMap<String, Vec<TmuxWindow>>,
        tools: HashMap<String, Option<String>>,
        fail_sessions: bool,
        fail_windows: HashSet<String>,
        fail_tools: HashSet<String>,
    }

    #[derive(Default)]
    struct FakeSource {
        fixture: Mutex<Fixture>,
        session_calls: AtomicUsize,
        window_calls: AtomicUsize,
        tool_calls: AtomicUsize,
    }

    impl FakeSource {
        fn edit(&self, update: impl FnOnce(&mut Fixture)) {
            update(&mut self.fixture.lock().unwrap());
        }
    }

    #[async_trait::async_trait]
    impl MonitorSource for FakeSource {
        async fn sessions(&self) -> anyhow::Result<Vec<TmuxSession>> {
            self.session_calls.fetch_add(1, Ordering::SeqCst);
            let fixture = self.fixture.lock().unwrap();
            anyhow::ensure!(!fixture.fail_sessions, "synthetic session sampling failure");
            Ok(fixture.sessions.clone())
        }

        async fn windows(&self, session: &str) -> anyhow::Result<Vec<TmuxWindow>> {
            self.window_calls.fetch_add(1, Ordering::SeqCst);
            let fixture = self.fixture.lock().unwrap();
            anyhow::ensure!(
                !fixture.fail_windows.contains(session),
                "synthetic window sampling failure"
            );
            fixture
                .windows
                .get(session)
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("missing fixture windows"))
        }

        async fn tool(&self, session: &str) -> anyhow::Result<Option<String>> {
            self.tool_calls.fetch_add(1, Ordering::SeqCst);
            let fixture = self.fixture.lock().unwrap();
            anyhow::ensure!(
                !fixture.fail_tools.contains(session),
                "synthetic tool sampling failure"
            );
            Ok(fixture.tools.get(session).cloned().flatten())
        }
    }

    fn fixture_session(index: usize) -> TmuxSession {
        TmuxSession {
            name: format!("session-{index}"),
            attached: false,
            created: chrono::DateTime::from_timestamp(1_700_000_000 + index as i64, 0).unwrap(),
            windows: 1,
            dimensions: "80x24".to_owned(),
            // Deliberately match list_sessions_basic: every tool is omitted.
            tool: None,
        }
    }

    fn setup(count: usize) -> (TmuxMonitor, Arc<FakeSource>, mpsc::Receiver<ServerMessage>) {
        let source = Arc::new(FakeSource::default());
        source.edit(|fixture| {
            for index in 0..count {
                let session = fixture_session(index);
                fixture.windows.insert(
                    session.name.clone(),
                    vec![TmuxWindow {
                        index: 0,
                        name: "main".to_owned(),
                        active: true,
                        panes: 1,
                    }],
                );
                fixture.tools.insert(
                    session.name.clone(),
                    if index == 0 {
                        Some("codex".to_owned())
                    } else {
                        None
                    },
                );
                fixture.sessions.push(session);
            }
        });
        let (tx, rx) = mpsc::channel(128);
        (TmuxMonitor::with_source(tx, source.clone()), source, rx)
    }

    fn next_sessions(rx: &mut mpsc::Receiver<ServerMessage>) -> Vec<TmuxSession> {
        match rx
            .try_recv()
            .expect("one metadata change should publish a snapshot")
        {
            ServerMessage::SessionsList { sessions } => sessions,
            _ => panic!("unexpected monitor message"),
        }
    }

    fn assert_quiet(rx: &mut mpsc::Receiver<ServerMessage>) {
        assert!(
            matches!(rx.try_recv(), Err(mpsc::error::TryRecvError::Empty)),
            "unchanged state must not broadcast"
        );
    }

    #[tokio::test]
    async fn test_tmux_monitor_new() {
        let (tx, _rx) = mpsc::channel(256);
        let _monitor = TmuxMonitor::new(tx);
        // Just ensure construction doesn't panic
    }

    #[tokio::test]
    async fn unchanged_sessions_with_missing_basic_tools_are_quiet_for_sixty_ticks() {
        let (monitor, source, mut rx) = setup(8);
        monitor.check_for_changes(true).await;
        let initial = next_sessions(&mut rx);
        assert_eq!(initial.len(), 8);
        assert_eq!(initial[0].tool.as_deref(), Some("codex"));
        assert!(initial[1..].iter().all(|session| session.tool.is_none()));
        for tick in 1..=60 {
            monitor
                .check_for_changes(tick % WINDOW_DETAIL_REFRESH_TICKS == 0)
                .await;
            assert_quiet(&mut rx);
        }
        assert_eq!(source.session_calls.load(Ordering::SeqCst), 61);
        assert_eq!(source.tool_calls.load(Ordering::SeqCst), 8 * 13);
        assert_eq!(source.window_calls.load(Ordering::SeqCst), 8 * 13);
    }

    #[tokio::test]
    async fn periodic_refresh_detects_agent_start_switch_and_exit() {
        let (monitor, source, mut rx) = setup(1);
        source.edit(|fixture| {
            fixture.tools.insert("session-0".to_owned(), None);
        });
        monitor.check_for_changes(true).await;
        assert!(next_sessions(&mut rx)[0].tool.is_none());
        for expected in [Some("codex"), Some("claude"), None] {
            source.edit(|fixture| {
                fixture
                    .tools
                    .insert("session-0".to_owned(), expected.map(str::to_owned));
            });
            monitor.check_for_changes(false).await;
            assert_quiet(&mut rx);
            monitor.check_for_changes(true).await;
            assert_eq!(next_sessions(&mut rx)[0].tool.as_deref(), expected);
            monitor.check_for_changes(true).await;
            assert_quiet(&mut rx);
        }
    }

    #[tokio::test]
    async fn recreated_session_does_not_inherit_the_previous_tool() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        assert_eq!(next_sessions(&mut rx)[0].tool.as_deref(), Some("codex"));
        source.edit(|fixture| {
            fixture.sessions[0].created += chrono::Duration::seconds(1);
            fixture.tools.insert("session-0".to_owned(), None);
        });
        monitor.check_for_changes(false).await;
        assert!(next_sessions(&mut rx)[0].tool.is_none());
        assert_eq!(source.tool_calls.load(Ordering::SeqCst), 2);
        monitor.check_for_changes(false).await;
        assert_quiet(&mut rx);
    }

    #[tokio::test]
    async fn new_sessions_are_detected_without_waiting_for_the_periodic_refresh() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        next_sessions(&mut rx);
        source.edit(|fixture| {
            let session = fixture_session(1);
            fixture.windows.insert(
                session.name.clone(),
                vec![TmuxWindow {
                    index: 0,
                    name: "second".to_owned(),
                    active: true,
                    panes: 1,
                }],
            );
            fixture
                .tools
                .insert(session.name.clone(), Some("claude".to_owned()));
            fixture.sessions.push(session);
        });
        monitor.check_for_changes(false).await;
        let sessions = next_sessions(&mut rx);
        assert_eq!(sessions.len(), 2);
        assert_eq!(sessions[1].tool.as_deref(), Some("claude"));
        assert_eq!(source.tool_calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn pane_changes_publish_once_even_when_session_fields_do_not_change() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        let original = next_sessions(&mut rx);
        source.edit(|fixture| {
            fixture.windows.get_mut("session-0").unwrap()[0].panes = 2;
        });
        monitor.check_for_changes(false).await;
        assert_quiet(&mut rx);
        monitor.check_for_changes(true).await;
        assert_eq!(next_sessions(&mut rx), original);
        monitor.check_for_changes(true).await;
        assert_quiet(&mut rx);
    }

    #[tokio::test]
    async fn structural_changes_publish_without_discarding_cached_tools() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        next_sessions(&mut rx);
        source.edit(|fixture| {
            fixture.sessions[0].attached = true;
        });
        monitor.check_for_changes(false).await;
        let attached = next_sessions(&mut rx);
        assert!(attached[0].attached);
        assert_eq!(attached[0].tool.as_deref(), Some("codex"));
        source.edit(|fixture| {
            fixture.sessions[0].dimensions = "100x30".to_owned();
        });
        monitor.check_for_changes(false).await;
        assert_eq!(next_sessions(&mut rx)[0].dimensions, "100x30");
        assert_eq!(source.tool_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn removing_the_last_session_publishes_one_empty_list_then_remains_quiet() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        next_sessions(&mut rx);
        source.edit(|fixture| {
            fixture.sessions.clear();
        });
        monitor.check_for_changes(false).await;
        assert!(next_sessions(&mut rx).is_empty());
        monitor.check_for_changes(true).await;
        assert_quiet(&mut rx);
    }

    #[tokio::test]
    async fn failed_samples_do_not_publish_false_deletions_or_tool_exits() {
        let (monitor, source, mut rx) = setup(1);
        monitor.check_for_changes(true).await;
        next_sessions(&mut rx);
        source.edit(|fixture| {
            fixture.fail_sessions = true;
        });
        monitor.check_for_changes(true).await;
        assert_quiet(&mut rx);
        source.edit(|fixture| {
            fixture.fail_sessions = false;
            fixture.fail_windows.insert("session-0".to_owned());
            fixture.fail_tools.insert("session-0".to_owned());
        });
        monitor.check_for_changes(true).await;
        assert_quiet(&mut rx);
        let state = monitor.state.read().await;
        assert_eq!(state.sessions[0].tool.as_deref(), Some("codex"));
        assert_eq!(state.window_pane_counts["session-0"], (1, 1));
        drop(state);
        source.edit(|fixture| {
            fixture.fail_windows.clear();
            fixture.fail_tools.clear();
            fixture.tools.insert("session-0".to_owned(), None);
        });
        monitor.check_for_changes(true).await;
        assert!(next_sessions(&mut rx)[0].tool.is_none());
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
        let (tx, _rx) = mpsc::channel(256);
        let monitor = TmuxMonitor::new(tx);
        let state = monitor.state.read().await;
        assert!(state.sessions.is_empty());
        assert!(state.window_pane_counts.is_empty());
    }
}
