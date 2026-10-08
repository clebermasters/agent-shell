//! Shared, read-only activity observation. Terminal silence is never a completion event.
mod lifecycle;
mod protocol;
mod terminal;
pub(crate) use protocol::direct_state;
pub(crate) use protocol::{direct_snapshots, report_direct_completion, start_direct_broadcast};
pub(crate) use protocol::{invalidate_provider, report_direct, start_direct_watch};

use crate::{types::ServerMessage, websocket::BroadcastMessage};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock, Weak},
};
use tokio::{
    process::Command,
    sync::{mpsc, watch},
    task::JoinHandle,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ActivityStatus {
    Working,
    RecentActivity,
    Waiting,
    Idle,
    Failed,
    Unknown,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityState {
    pub status: ActivityStatus,
    pub detail: String,
    pub source: String,
    pub confidence: String,
    pub started_at: Option<i64>,
    pub last_activity_at: Option<i64>,
    pub observed_at: i64,
    pub observer_id: String,
    pub sequence: u64,
    pub turn_id: Option<String>,
    pub finished_at: Option<i64>,
    pub completion_reason: Option<String>,
    pub quiet_at: Option<i64>,
}

impl ActivityState {
    pub fn unknown(detail: &str) -> Self {
        Self {
            status: ActivityStatus::Unknown,
            detail: detail.into(),
            source: "none".into(),
            confidence: "unknown".into(),
            started_at: None,
            last_activity_at: None,
            observed_at: chrono::Utc::now().timestamp_millis(),
            observer_id: uuid::Uuid::new_v4().to_string(),
            sequence: 0,
            turn_id: None,
            finished_at: None,
            completion_reason: None,
            quiet_at: None,
        }
    }
}

#[derive(Clone, Debug)]
struct Pane {
    id: String,
    server_pid: u32,
    root_pid: u32,
    command: String,
    dead: bool,
    in_mode: bool,
    width: u32,
    height: u32,
}

const PANE_FORMAT: &str = "#{pane_id}\t#{pid}\t#{pane_pid}\t#{pane_current_command}\t#{pane_dead}\t#{pane_in_mode}\t#{pane_width}\t#{pane_height}";

fn parse_pane(line: &str) -> Option<Pane> {
    let p: Vec<_> = line.trim_end().split('\t').collect();
    if p.len() != 8 || !p[0].starts_with('%') {
        return None;
    }
    Some(Pane {
        id: p[0].into(),
        server_pid: p[1].parse().ok()?,
        root_pid: p[2].parse().ok()?,
        command: p[3].into(),
        dead: p[4] == "1",
        in_mode: p[5] == "1",
        width: p[6].parse().ok()?,
        height: p[7].parse().ok()?,
    })
}

async fn tmux_output(args: &[&str]) -> anyhow::Result<String> {
    let mut cmd = Command::new("tmux");
    cmd.args(args).kill_on_drop(true);
    let output = tokio::time::timeout(std::time::Duration::from_secs(2), cmd.output()).await??;
    anyhow::ensure!(output.status.success(), "tmux observation unavailable");
    anyhow::ensure!(
        output.stdout.len() <= 1024 * 1024,
        "terminal snapshot is too large"
    );
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

async fn resolve_pane(session: &str, window: u32) -> anyhow::Result<Pane> {
    let target = format!("{session}:{window}");
    let line = tmux_output(&["display-message", "-p", "-t", &target, PANE_FORMAT]).await?;
    parse_pane(&line).ok_or_else(|| anyhow::anyhow!("invalid tmux pane metadata"))
}

struct Observer {
    sender: watch::Sender<ActivityState>,
    task: JoinHandle<()>,
}
impl Drop for Observer {
    fn drop(&mut self) {
        self.task.abort();
    }
}
type Registry = Mutex<HashMap<(u32, String), Weak<Observer>>>;
fn registry() -> &'static Registry {
    static REGISTRY: OnceLock<Registry> = OnceLock::new();
    REGISTRY.get_or_init(Mutex::default)
}

fn shared_observer(pane: Pane) -> Arc<Observer> {
    let key = (pane.server_pid, pane.id.clone());
    let mut entries = registry().lock().unwrap_or_else(|e| e.into_inner());
    entries.retain(|_, observer| observer.strong_count() > 0);
    if let Some(existing) = entries.get(&key).and_then(Weak::upgrade) {
        return existing;
    }
    let (sender, _) = watch::channel(ActivityState::unknown("Checking agent status"));
    let task_sender = sender.clone();
    let task = tokio::spawn(observe_pane(pane, task_sender));
    let observer = Arc::new(Observer { sender, task });
    entries.insert(key, Arc::downgrade(&observer));
    observer
}

struct Tracker {
    state: ActivityState,
    previous_screen: Option<u64>,
    dimensions: Option<(u32, u32)>,
}
impl Tracker {
    fn new() -> Self {
        Self {
            state: ActivityState::unknown("Checking agent status"),
            previous_screen: None,
            dimensions: None,
        }
    }
    fn observe(&mut self, pane: &Pane, text: &str, now: i64) {
        let hash = terminal::activity_fingerprint(text);
        let dimensions = (pane.width, pane.height);
        let changed = self
            .previous_screen
            .is_some_and(|previous| previous != hash)
            && self.dimensions == Some(dimensions)
            && !pane.in_mode;
        if changed {
            self.state.last_activity_at = Some(now);
        }
        self.previous_screen = Some(hash);
        self.dimensions = Some(dimensions);
        self.state.observed_at = now;
        self.state.sequence += 1;
        self.state.finished_at = None;
        self.state.completion_reason = None;
        self.state.quiet_at = None;
        self.state.turn_id = None;
        let signal = terminal::classify(text, &pane.command);
        self.state.status = if pane.dead {
            ActivityStatus::Failed
        } else if pane.in_mode {
            ActivityStatus::Unknown
        } else {
            signal.status
        };
        self.state.detail = if pane.dead {
            "Terminal process exited".into()
        } else if pane.in_mode {
            "Terminal is in copy or selection mode".into()
        } else {
            signal.detail.into()
        };
        self.state.source = if signal.status == ActivityStatus::Unknown {
            "tmux-activity"
        } else {
            "tmux-status"
        }
        .into();
        self.state.confidence = if signal.status == ActivityStatus::Unknown {
            "estimated"
        } else {
            "inferred"
        }
        .into();
        if self.state.status == ActivityStatus::Working {
            if self.state.started_at.is_none() {
                self.state.started_at = Some(now - signal.elapsed_ms.unwrap_or(0));
            }
        } else if self.state.status != ActivityStatus::Waiting {
            self.state.started_at = None;
        }
        if self.state.status == ActivityStatus::Unknown && !pane.in_mode && !pane.dead {
            if let Some(last) = self.state.last_activity_at {
                if now - last < 10_000 {
                    self.state.status = ActivityStatus::RecentActivity;
                    self.state.detail = "Recent terminal activity".into();
                } else {
                    self.state.detail = "No recent terminal activity".into();
                    self.state.quiet_at = Some(last + 10_000);
                }
            }
        }
        if self.state.status == ActivityStatus::Idle && !pane.in_mode {
            self.state.quiet_at = self
                .state
                .last_activity_at
                .filter(|last| now - last >= 10_000)
                .map(|last| last + 10_000);
        }
    }
    fn unavailable(&mut self) {
        self.state.status = ActivityStatus::Unknown;
        self.state.detail = "Terminal status unavailable".into();
        self.state.source = "none".into();
        self.state.confidence = "unknown".into();
        self.state.observed_at = chrono::Utc::now().timestamp_millis();
        self.state.sequence += 1;
        self.state.started_at = None;
        self.state.quiet_at = None;
        self.state.finished_at = None;
        self.state.completion_reason = None;
    }
}

async fn observe_pane(original: Pane, sender: watch::Sender<ActivityState>) {
    let mut tracker = Tracker::new();
    tracker.state = sender.borrow().clone();
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));
    let mut native_reader = lifecycle::Reader::default();
    let mut ticks = 0u64;
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        let metadata =
            tmux_output(&["display-message", "-p", "-t", &original.id, PANE_FORMAT]).await;
        match metadata.ok().and_then(|line| parse_pane(&line)) {
            Some(pane)
                if pane.server_pid == original.server_pid && pane.root_pid == original.root_pid =>
            {
                match tmux_output(&["capture-pane", "-p", "-t", &pane.id]).await {
                    Ok(text) => {
                        tracker.observe(&pane, &text, chrono::Utc::now().timestamp_millis());
                        let root_pid = pane.root_pid;
                        let discover = ticks % 5 == 0;
                        let reader = std::mem::take(&mut native_reader);
                        if let Ok((reader, evidence)) =
                            tokio::task::spawn_blocking(move || reader.refresh(root_pid, discover))
                                .await
                        {
                            native_reader = reader;
                            if !pane.dead {
                                if let Some(native) = evidence {
                                    lifecycle::apply(&mut tracker.state, &native);
                                }
                            }
                        }
                    }
                    Err(_) => tracker.unavailable(),
                }
            }
            _ => tracker.unavailable(),
        }
        sender.send_replace(tracker.state.clone());
        ticks += 1;
    }
}

async fn forward_state(
    tx: &mpsc::Sender<BroadcastMessage>,
    session: &str,
    window: u32,
    pane_id: Option<String>,
    state: ActivityState,
) -> bool {
    let message = ServerMessage::ChatActivity {
        session_name: session.into(),
        window_index: window,
        pane_id,
        state,
    };
    match serde_json::to_string(&message) {
        Ok(json) => tx
            .send(BroadcastMessage::Text(Arc::new(json)))
            .await
            .is_ok(),
        Err(_) => false,
    }
}

pub(crate) fn start_tmux_watch(
    session: String,
    window: u32,
    tx: mpsc::Sender<BroadcastMessage>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            let pane = match resolve_pane(&session, window).await {
                Ok(pane) => pane,
                Err(_) => {
                    if !forward_state(
                        &tx,
                        &session,
                        window,
                        None,
                        ActivityState::unknown("Terminal status unavailable"),
                    )
                    .await
                    {
                        return;
                    }
                    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                    continue;
                }
            };
            let observer = shared_observer(pane.clone());
            let mut receiver = observer.sender.subscribe();
            loop {
                let snapshot = receiver.borrow_and_update().clone();
                if !forward_state(&tx, &session, window, Some(pane.id.clone()), snapshot).await {
                    return;
                }
                if receiver.changed().await.is_err() {
                    return;
                }
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pane() -> Pane {
        Pane {
            id: "%999999".into(),
            server_pid: 1,
            root_pid: 2,
            command: "unknown-agent".into(),
            dead: false,
            in_mode: false,
            width: 80,
            height: 24,
        }
    }
    #[test]
    fn silence_does_not_complete_an_unknown_agent() {
        let mut tracker = Tracker::new();
        tracker.observe(&pane(), "output", 1000);
        tracker.observe(&pane(), "output", 100_000);
        assert_eq!(tracker.state.status, ActivityStatus::Unknown);
    }

    #[test]
    fn activity_holds_for_ten_seconds_then_emits_one_stable_quiet_marker() {
        let mut tracker = Tracker::new();
        tracker.observe(&pane(), "baseline", 1000);
        tracker.observe(&pane(), "new output", 2000);
        assert_eq!(tracker.state.status, ActivityStatus::RecentActivity);
        tracker.observe(&pane(), "new output", 11_000);
        assert_eq!(tracker.state.status, ActivityStatus::RecentActivity);
        tracker.observe(&pane(), "new output", 12_000);
        assert_eq!(tracker.state.status, ActivityStatus::Unknown);
        assert_eq!(tracker.state.quiet_at, Some(12_000));
        tracker.observe(&pane(), "new output", 20_000);
        assert_eq!(tracker.state.quiet_at, Some(12_000));
        tracker.observe(&pane(), "another output", 21_000);
        assert_eq!(tracker.state.status, ActivityStatus::RecentActivity);
        assert_eq!(tracker.state.quiet_at, None);
    }

    #[test]
    fn reconnect_failure_is_not_a_recent_activity_or_quiet_transition() {
        let mut tracker = Tracker::new();
        tracker.observe(
            &pane(),
            "• Reconnect failed — waiting for feedback (13h 58m 49s)",
            1000,
        );
        tracker.observe(
            &pane(),
            "• Reconnect failed — waiting for feedback (13h 58m 50s)",
            2000,
        );
        assert_eq!(tracker.state.status, ActivityStatus::Failed);
        assert_eq!(tracker.state.last_activity_at, None);
        assert_eq!(tracker.state.quiet_at, None);
    }
    #[test]
    fn resizing_is_not_agent_activity() {
        let mut tracker = Tracker::new();
        let mut p = pane();
        tracker.observe(&p, "first", 1000);
        p.width = 40;
        tracker.observe(&p, "wrapped differently", 2000);
        assert_eq!(tracker.state.last_activity_at, None);
    }
    #[test]
    fn failures_clear_old_working_state() {
        let mut tracker = Tracker::new();
        tracker.state.status = ActivityStatus::Working;
        tracker.unavailable();
        assert_eq!(tracker.state.status, ActivityStatus::Unknown);
    }
    #[tokio::test]
    async fn observers_are_shared_and_released_after_last_subscriber() {
        let a = shared_observer(pane());
        let b = shared_observer(pane());
        assert!(Arc::ptr_eq(&a, &b));
        let weak = Arc::downgrade(&a);
        drop(a);
        assert!(weak.upgrade().is_some());
        drop(b);
        assert!(weak.upgrade().is_none());
    }
}
