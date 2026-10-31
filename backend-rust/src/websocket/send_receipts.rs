//! Durable acknowledgements, without storing another copy of message bodies.
//! An interrupted terminal write is uncertain: it must never be replayed.
use anyhow::{bail, Result};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use sha2::{Digest, Sha256};
use std::{io::Write, path::PathBuf, sync::OnceLock, time::Duration};

const RETENTION_MILLIS: i64 = 7 * 24 * 60 * 60 * 1000;
const INTERRUPTED: &str = "Delivery could not be verified. Check the conversation before retrying";

#[derive(Clone)]
pub(super) struct Store {
    path: PathBuf,
}

#[derive(Debug, Clone, PartialEq)]
pub(super) struct Receipt {
    pub status: String,
    pub success: Option<bool>,
    pub error: Option<String>,
}

pub(super) enum Reservation {
    New,
    Existing(Receipt),
}

#[derive(Clone)]
struct CurrentSubmission {
    store: Store,
    binding: String,
    request: String,
}
tokio::task_local! { static CURRENT: CurrentSubmission; }

pub(super) async fn during<F: std::future::Future<Output = Result<()>>>(
    store: Store,
    binding: String,
    request: String,
    operation: F,
) -> Result<()> {
    CURRENT
        .scope(
            CurrentSubmission {
                store,
                binding,
                request,
            },
            operation,
        )
        .await
}

/// The terminal write is complete. Persist that fact before broadcasting, even
/// when the submitting socket disconnects while the rest of the handler runs.
pub(super) async fn delivered() {
    let Ok(current) = CURRENT.try_with(Clone::clone) else {
        return;
    };
    let result = tokio::task::spawn_blocking(move || {
        current.store.settle(
            &current.binding,
            &current.request,
            &Receipt::complete(&Ok(())),
        )
    })
    .await;
    if !matches!(result, Ok(Ok(_))) {
        tracing::warn!("Could not checkpoint chat delivery receipt");
    }
}

fn owner() -> &'static str {
    static INSTANCE: OnceLock<String> = OnceLock::new();
    INSTANCE.get_or_init(|| uuid::Uuid::new_v4().to_string())
}

fn validate_id(value: &str, maximum: usize) -> Result<()> {
    if value.is_empty()
        || value.len() > maximum
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
    {
        bail!("Invalid submission identifier");
    }
    Ok(())
}

impl Receipt {
    pub fn unknown() -> Self {
        Self {
            status: "unknown".into(),
            success: None,
            error: Some(INTERRUPTED.into()),
        }
    }

    pub fn complete(result: &Result<()>) -> Self {
        Self {
            status: "complete".into(),
            success: Some(result.is_ok()),
            error: result
                .as_ref()
                .err()
                .map(|e| e.to_string().chars().take(2000).collect()),
        }
    }

    pub fn message(&self, binding: &str, request: &str) -> serde_json::Value {
        let mut value = serde_json::json!({"type":"chat-send-result", "bindingId":binding,
            "requestId":request,"status":self.status,"sendReceipts":true});
        if let Some(success) = self.success {
            value["success"] = success.into();
        }
        if let Some(error) = &self.error {
            value["error"] = error.clone().into();
        }
        value
    }
}

impl Store {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    fn connection(&self) -> Result<Connection> {
        let conn = Connection::open(&self.path)?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS submissions (
                binding_id TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
                state TEXT NOT NULL, success INTEGER, error TEXT, owner TEXT NOT NULL, updated_at INTEGER NOT NULL,
                PRIMARY KEY(binding_id,request_id));
            CREATE INDEX IF NOT EXISTS idx_submissions_time ON submissions(updated_at);")?;
        // A restarted process cannot know whether its predecessor's PTY write completed.
        conn.execute(
            "UPDATE submissions SET state='unknown',error=? WHERE state='pending' AND owner<>?",
            params![INTERRUPTED, owner()],
        )?;
        Ok(conn)
    }

    pub fn reserve(&self, binding: &str, request: &str, fingerprint: &str) -> Result<Reservation> {
        validate_id(binding, 256)?;
        validate_id(request, 128)?;
        let mut conn = self.connection()?;
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let previous: Option<(String,Receipt)> = tx.query_row(
            "SELECT fingerprint,state,success,error FROM submissions WHERE binding_id=? AND request_id=?",
            params![binding,request], |r| Ok((r.get(0)?,Receipt { status:r.get(1)?,success:r.get(2)?,error:r.get(3)? }))).optional()?;
        if let Some((original, receipt)) = previous {
            if original != fingerprint {
                bail!("This submission ID already belongs to a different message");
            }
            return Ok(Reservation::Existing(receipt));
        }
        let now = chrono::Utc::now().timestamp_millis();
        tx.execute(
            "DELETE FROM submissions WHERE updated_at<?",
            params![now - RETENTION_MILLIS],
        )?;
        tx.execute(
            "INSERT INTO submissions VALUES (?,?,?,'pending',NULL,NULL,?,?)",
            params![binding, request, fingerprint, owner(), now],
        )?;
        tx.commit()?;
        Ok(Reservation::New)
    }

    pub fn get(&self, binding: &str, request: &str) -> Result<Receipt> {
        validate_id(binding, 256)?;
        validate_id(request, 128)?;
        let conn = self.connection()?;
        Ok(conn
            .query_row(
                "SELECT state,success,error FROM submissions WHERE binding_id=? AND request_id=?",
                params![binding, request],
                |r| {
                    Ok(Receipt {
                        status: r.get(0)?,
                        success: r.get(1)?,
                        error: r.get(2)?,
                    })
                },
            )
            .optional()?
            .unwrap_or_else(Receipt::unknown))
    }

    pub fn finish(&self, binding: &str, request: &str, receipt: &Receipt) -> Result<()> {
        let conn = self.connection()?;
        let changed = conn.execute(
            "UPDATE submissions SET state=?,success=?,error=?,updated_at=?
            WHERE binding_id=? AND request_id=? AND owner=? AND state<>'complete'",
            params![
                receipt.status,
                receipt.success,
                receipt.error,
                chrono::Utc::now().timestamp_millis(),
                binding,
                request,
                owner()
            ],
        )?;
        if changed != 1 {
            bail!("Could not record this submission's delivery result");
        }
        Ok(())
    }

    pub fn settle(&self, binding: &str, request: &str, receipt: &Receipt) -> Result<Receipt> {
        let previous = self.get(binding, request)?;
        if previous.status == "complete" {
            return Ok(previous);
        }
        self.finish(binding, request, receipt)?;
        Ok(receipt.clone())
    }

    fn interrupted(&self, binding: &str, request: &str) -> Result<()> {
        self.connection()?.execute(
            "UPDATE submissions SET state='unknown',error=?,updated_at=?
            WHERE binding_id=? AND request_id=? AND owner=? AND state='pending'",
            params![
                INTERRUPTED,
                chrono::Utc::now().timestamp_millis(),
                binding,
                request,
                owner()
            ],
        )?;
        Ok(())
    }
}

/// Marks cancellation as uncertain; it never claims the terminal rejected input.
pub(super) struct InFlight {
    store: Store,
    binding: String,
    request: String,
    armed: bool,
}

impl InFlight {
    pub fn new(store: Store, binding: String, request: String) -> Self {
        Self {
            store,
            binding,
            request,
            armed: true,
        }
    }
    pub fn settled(&mut self) {
        self.armed = false;
    }
}

impl Drop for InFlight {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let (store, binding, request) = (
            self.store.clone(),
            self.binding.clone(),
            self.request.clone(),
        );
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn_blocking(move || {
                let _ = store.interrupted(&binding, &request);
            });
        }
    }
}

pub(super) fn fingerprint(message: &crate::types::WebSocketMessage) -> Result<String> {
    // Serialize directly into the digest instead of copying attachment base64.
    struct Writer(Sha256);
    impl Write for Writer {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.update(bytes);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut writer = Writer(Sha256::new());
    serde_json::to_writer(&mut writer, message)?;
    Ok(format!("{:x}", writer.0.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reconnect_recovers_success_without_resubmitting_or_storing_text() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("receipts.db");
        let first = Store::new(path.clone());
        assert!(matches!(
            first
                .reserve("binding", "request", "payload-digest")
                .unwrap(),
            Reservation::New
        ));
        first
            .finish("binding", "request", &Receipt::complete(&Ok(())))
            .unwrap();
        let reopened = Store::new(path);
        assert_eq!(
            reopened.get("binding", "request").unwrap().success,
            Some(true)
        );
        assert!(matches!(
            reopened
                .reserve("binding", "request", "payload-digest")
                .unwrap(),
            Reservation::Existing(_)
        ));
        assert!(reopened
            .reserve("binding", "request", "changed-digest")
            .is_err());
        assert_eq!(
            reopened.get("other-binding", "request").unwrap(),
            Receipt::unknown()
        );
    }

    #[test]
    fn concurrent_retries_only_reserve_one_terminal_write() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("receipts.db"));
        store.connection().unwrap();
        std::thread::scope(|scope| {
            let jobs: Vec<_> = (0..8)
                .map(|_| {
                    scope.spawn(|| {
                        matches!(store.reserve("b", "r", "same").unwrap(), Reservation::New)
                    })
                })
                .collect();
            assert_eq!(
                jobs.into_iter()
                    .map(|j| j.join().unwrap())
                    .filter(|new| *new)
                    .count(),
                1
            );
        });
        assert_eq!(store.get("b", "r").unwrap().status, "pending");
    }

    #[test]
    fn interrupted_or_previous_process_requests_are_never_replayed() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("receipts.db"));
        store.reserve("b", "r", "same").unwrap();
        store.interrupted("b", "r").unwrap();
        assert!(
            matches!(store.reserve("b","r","same").unwrap(),Reservation::Existing(r) if r.status=="unknown")
        );
        store.reserve("b", "previous-process", "same").unwrap();
        store
            .connection()
            .unwrap()
            .execute(
                "UPDATE submissions SET owner='old-process' WHERE request_id='previous-process'",
                [],
            )
            .unwrap();
        assert_eq!(
            store.get("b", "previous-process").unwrap().status,
            "unknown"
        );
    }

    #[test]
    fn rejection_keeps_its_error_and_unknown_is_not_a_false_rejection() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("receipts.db"));
        store.reserve("b", "r", "same").unwrap();
        let receipt = Receipt::complete(&Err(anyhow::anyhow!("Conversation changed")));
        store.finish("b", "r", &receipt).unwrap();
        assert_eq!(store.get("b", "r").unwrap(), receipt);
        assert!(Receipt::unknown()
            .message("b", "missing")
            .get("success")
            .is_none());
    }

    #[tokio::test]
    async fn checkpoint_survives_disconnect_or_failure_after_terminal_delivery() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("receipts.db"));
        store.reserve("b", "r", "same").unwrap();
        let mut guard = InFlight::new(store.clone(), "b".into(), "r".into());
        let result = during(store.clone(), "b".into(), "r".into(), async {
            delivered().await;
            anyhow::bail!("Socket disconnected after the terminal accepted input");
        })
        .await;
        let settled = store.settle("b", "r", &Receipt::complete(&result)).unwrap();
        guard.settled();
        assert_eq!(settled.success, Some(true));
        assert!(
            matches!(store.reserve("b","r","same").unwrap(),Reservation::Existing(r) if r.success==Some(true))
        );
    }

    #[tokio::test]
    async fn an_unclaimed_reservation_worker_does_not_leave_a_permanent_pending_send() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path().join("receipts.db"));
        let (release, wait) = std::sync::mpsc::channel();
        let (reserved, ready) = tokio::sync::oneshot::channel();
        let worker_store = store.clone();
        let worker = tokio::task::spawn_blocking(move || {
            worker_store.reserve("b", "r", "same").unwrap();
            let guard = InFlight::new(worker_store, "b".into(), "r".into());
            let _ = reserved.send(());
            wait.recv().unwrap();
            guard
        });
        ready.await.unwrap();
        // Match cancellation while the receiver awaits the blocking reservation:
        // the worker still finishes, but nobody can claim its armed guard.
        drop(worker);
        release.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            while store.get("b", "r").unwrap().status == "pending" {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(store.get("b", "r").unwrap().status, "unknown");
    }
}
