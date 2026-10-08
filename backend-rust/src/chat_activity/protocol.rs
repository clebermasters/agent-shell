use super::{forward_state, ActivityState, ActivityStatus};
use crate::websocket::BroadcastMessage;
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
};
use tokio::{
    sync::{mpsc, watch},
    task::JoinHandle,
};

type Sessions = Mutex<HashMap<String, watch::Sender<ActivityState>>>;
fn sessions() -> &'static Sessions {
    static SESSIONS: OnceLock<Sessions> = OnceLock::new();
    SESSIONS.get_or_init(Mutex::default)
}
fn canonical(id: &str) -> String {
    let id = id.strip_prefix("acp_").unwrap_or(id);
    if id.starts_with("codex:") || id.starts_with("opencode:") {
        id.into()
    } else {
        format!("opencode:{}", id.strip_prefix("acp:").unwrap_or(id))
    }
}
fn sender(id: &str) -> watch::Sender<ActivityState> {
    let mut entries = sessions().lock().unwrap_or_else(|e| e.into_inner());
    if entries.len() >= 256 {
        let oldest = entries
            .iter()
            .filter(|(_, sender)| sender.receiver_count() == 0)
            .min_by_key(|(_, sender)| sender.borrow().observed_at)
            .map(|(id, _)| id.clone());
        if let Some(id) = oldest {
            entries.remove(&id);
        }
    }
    entries
        .entry(canonical(id))
        .or_insert_with(|| watch::channel(ActivityState::unknown("Waiting for agent status")).0)
        .clone()
}

pub(crate) fn report_direct(
    id: &str,
    status: ActivityStatus,
    detail: &str,
    turn_id: Option<String>,
) {
    sender(id).send_modify(|state| {
        let now = chrono::Utc::now().timestamp_millis();
        if status == ActivityStatus::Working
            && (state.status != ActivityStatus::Working && state.status != ActivityStatus::Waiting
                || turn_id.is_some() && turn_id != state.turn_id)
        {
            state.started_at = Some(now);
        }
        if !matches!(status, ActivityStatus::Working | ActivityStatus::Waiting) {
            state.started_at = None;
        }
        state.status = status;
        state.detail = detail.into();
        state.source = "agent-protocol".into();
        state.confidence = "reported".into();
        state.observed_at = now;
        state.last_activity_at = Some(now);
        state.sequence += 1;
        if turn_id.is_some() {
            state.turn_id = turn_id;
        }
    });
}

pub(crate) fn invalidate_provider(prefix: &str) {
    let entries = sessions().lock().unwrap_or_else(|e| e.into_inner());
    for (_, sender) in entries.iter().filter(|(id, _)| id.starts_with(prefix)) {
        sender.send_modify(|state| {
            if state.status == ActivityStatus::Failed {
                return;
            }
            state.status = ActivityStatus::Unknown;
            state.started_at = None;
            state.detail = "Agent connection unavailable".into();
            state.source = "none".into();
            state.confidence = "unknown".into();
            state.observed_at = chrono::Utc::now().timestamp_millis();
            state.sequence += 1;
        });
    }
}

pub(crate) fn start_direct_watch(
    id: String,
    window: u32,
    tx: mpsc::Sender<BroadcastMessage>,
) -> JoinHandle<()> {
    let mut receiver = sender(&id).subscribe();
    tokio::spawn(async move {
        let session = format!("acp_{id}");
        let mut heartbeat = tokio::time::interval(std::time::Duration::from_secs(5));
        loop {
            let mut state = receiver.borrow_and_update().clone();
            state.observed_at = chrono::Utc::now().timestamp_millis();
            if !forward_state(&tx, &session, window, None, state).await {
                return;
            }
            tokio::select! {
                result = receiver.changed() => if result.is_err() { return; },
                _ = heartbeat.tick() => {},
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lost_provider_clears_busy_but_preserves_reported_failure() {
        let id = format!("codex:{}", uuid::Uuid::new_v4());
        report_direct(&id, ActivityStatus::Working, "Working", None);
        invalidate_provider(&id);
        assert_eq!(sender(&id).borrow().status, ActivityStatus::Unknown);
        report_direct(&id, ActivityStatus::Failed, "Failed", None);
        invalidate_provider(&id);
        assert_eq!(sender(&id).borrow().status, ActivityStatus::Failed);
    }
    #[test]
    fn start_wait_resume_and_completion_keep_one_turn() {
        let id = format!("codex:{}", uuid::Uuid::new_v4());
        report_direct(
            &id,
            ActivityStatus::Working,
            "Working",
            Some("turn-one".into()),
        );
        let started = sender(&id).borrow().started_at;
        report_direct(&id, ActivityStatus::Waiting, "Approval", None);
        report_direct(&id, ActivityStatus::Working, "Working", None);
        assert_eq!(sender(&id).borrow().started_at, started);
        report_direct(&id, ActivityStatus::Idle, "Ended", None);
        assert_eq!(sender(&id).borrow().started_at, None);
    }
    #[tokio::test]
    async fn reconnect_gets_snapshot_without_another_chunk() {
        let id = format!("opencode:{}", uuid::Uuid::new_v4());
        report_direct(&id, ActivityStatus::Working, "Working", None);
        let (tx, mut rx) = mpsc::channel(2);
        let task = start_direct_watch(id.clone(), 0, tx);
        let BroadcastMessage::Text(json) = rx.recv().await.unwrap() else {
            panic!("expected JSON")
        };
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(value["state"]["status"], "working");
        assert_eq!(value["sessionName"], format!("acp_{id}"));
        task.abort();
    }
}
