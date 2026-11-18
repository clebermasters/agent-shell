//! Share failed-startup cooldowns across all requests for this backend instance.

use std::future::Future;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

const INITIAL_DELAY: Duration = Duration::from_secs(60);
const MAX_DELAY: Duration = Duration::from_secs(15 * 60);

#[derive(Default)]
struct RetryState {
    consecutive_failures: u32,
    next_attempt: Option<Instant>,
}

impl RetryState {
    fn remaining(&self, now: Instant) -> Duration {
        self.next_attempt
            .map(|next| next.saturating_duration_since(now))
            .unwrap_or_default()
    }

    fn failed(&mut self, now: Instant) -> Duration {
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        let multiplier = 1 << self.consecutive_failures.saturating_sub(1).min(4);
        let delay = (INITIAL_DELAY * multiplier).min(MAX_DELAY);
        self.next_attempt = Some(now + delay);
        delay
    }
}

/// The WebSocket request owning startup may disconnect while initialization is
/// awaiting a response. Record that interrupted attempt before releasing the
/// retry mutex, so reconnecting clients cannot bypass the cooldown.
struct StartupAttempt<'a, C: Fn() -> Instant> {
    state: &'a mut RetryState,
    clock: &'a C,
    finished: bool,
}

impl<C: Fn() -> Instant> StartupAttempt<'_, C> {
    fn failed(&mut self) -> Duration {
        let delay = self.state.failed((self.clock)());
        self.finished = true;
        delay
    }

    fn succeeded(&mut self) {
        *self.state = RetryState::default();
        self.finished = true;
    }
}

impl<C: Fn() -> Instant> Drop for StartupAttempt<'_, C> {
    fn drop(&mut self) {
        if !self.finished {
            let delay = self.failed();
            tracing::warn!(
                consecutive_failures = self.state.consecutive_failures,
                retry_after_seconds = delay.as_secs(),
                "OpenCode startup interrupted; deferring further process launches"
            );
        }
    }
}

/// A request may retry only after the prior failed startup's cooldown expires.
/// Holding the gate through initialization prevents queued callers from spawning
/// another process after the same failure. Successful startup clears the history.
#[derive(Default)]
pub struct StartupRetry {
    state: Mutex<RetryState>,
}

impl StartupRetry {
    pub(crate) async fn attempt<T, F, Fut>(&self, start: F) -> Result<T, String>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<T, String>>,
    {
        self.attempt_with_clock(start, Instant::now).await
    }

    async fn attempt_with_clock<T, F, Fut, C>(&self, start: F, clock: C) -> Result<T, String>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<T, String>>,
        C: Fn() -> Instant,
    {
        let mut state = self.state.lock().await;
        let remaining = state.remaining(clock());
        if !remaining.is_zero() {
            let seconds = remaining.as_secs() + u64::from(remaining.subsec_nanos() != 0);
            return Err(format!(
                "OpenCode startup paused after {} consecutive failures; retry in {}s",
                state.consecutive_failures, seconds
            ));
        }

        let mut attempt = StartupAttempt {
            state: &mut state,
            clock: &clock,
            finished: false,
        };
        match start().await {
            Ok(result) => {
                attempt.succeeded();
                Ok(result)
            }
            Err(error) => {
                // Measure from the failure, not from startup: initialization may
                // itself time out after several minutes.
                let delay = attempt.failed();
                tracing::warn!(
                    consecutive_failures = attempt.state.consecutive_failures,
                    retry_after_seconds = delay.as_secs(),
                    "OpenCode startup failed; deferring further process launches"
                );
                Err(format!(
                    "OpenCode startup failed; next retry in {}s: {}",
                    delay.as_secs(),
                    error
                ))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    #[test]
    fn delay_doubles_and_stays_capped_without_overflow() {
        let now = Instant::now();
        let mut state = RetryState::default();
        for seconds in [60, 120, 240, 480, 900, 900] {
            assert_eq!(state.failed(now), Duration::from_secs(seconds));
            assert_eq!(state.remaining(now), Duration::from_secs(seconds));
        }
        state.consecutive_failures = u32::MAX;
        assert_eq!(state.failed(now), MAX_DELAY);
    }

    #[tokio::test]
    async fn minute_polling_cannot_launch_a_process_each_minute() {
        let gate = StartupRetry::default();
        let start = Instant::now();
        let mut launches = Vec::new();
        for minute in 0..=60 {
            let now = start + Duration::from_secs(minute * 60);
            let _ = gate
                .attempt_with_clock(
                    || async {
                        launches.push(minute);
                        Err::<(), _>("database version mismatch".to_string())
                    },
                    || now,
                )
                .await;
        }
        assert_eq!(launches, vec![0, 1, 3, 7, 15, 30, 45, 60]);
    }

    #[tokio::test]
    async fn repeated_polls_keep_the_original_deadline_and_report_remaining_time() {
        let gate = StartupRetry::default();
        let now = Instant::now();
        let first = gate
            .attempt_with_clock(|| async { Err::<(), _>("failure".to_string()) }, || now)
            .await
            .unwrap_err();
        assert!(first.contains("next retry in 60s"));
        for elapsed in [1, 30, 59] {
            let error = gate
                .attempt_with_clock::<(), _, _, _>(
                    || async { panic!("poll must not start a process") },
                    || now + Duration::from_secs(elapsed),
                )
                .await
                .unwrap_err();
            assert!(error.contains(&format!("retry in {}s", 60 - elapsed)));
        }
        assert!(gate
            .attempt_with_clock(|| async { Ok(()) }, || now + INITIAL_DELAY)
            .await
            .is_ok());
    }

    #[tokio::test]
    async fn a_successful_start_resets_the_failure_history() {
        let gate = StartupRetry::default();
        let now = Instant::now();
        let _ = gate
            .attempt_with_clock(|| async { Err::<(), _>("failure".to_string()) }, || now)
            .await;
        assert_eq!(
            gate.attempt_with_clock(|| async { Ok(42) }, || now + INITIAL_DELAY)
                .await,
            Ok(42)
        );
        let error = gate
            .attempt_with_clock(
                || async { Err::<(), _>("later failure".to_string()) },
                || now + INITIAL_DELAY,
            )
            .await
            .unwrap_err();
        assert!(error.contains("next retry in 60s"));
    }

    #[tokio::test]
    async fn cooldown_starts_after_slow_initialization_fails() {
        let gate = StartupRetry::default();
        let start = Instant::now();
        let clock = std::sync::Mutex::new(start);
        let _ = gate
            .attempt_with_clock(
                || async {
                    *clock.lock().unwrap() = start + Duration::from_secs(120);
                    Err::<(), _>("initialization timed out".to_string())
                },
                || *clock.lock().unwrap(),
            )
            .await;
        let state = gate.state.lock().await;
        assert_eq!(
            state.remaining(start + Duration::from_secs(120)),
            INITIAL_DELAY
        );
    }

    #[tokio::test]
    async fn concurrent_refreshes_share_one_failed_startup() {
        let gate = Arc::new(StartupRetry::default());
        let launches = Arc::new(AtomicUsize::new(0));
        let barrier = Arc::new(tokio::sync::Barrier::new(16));
        let now = Instant::now();
        let mut callers = Vec::new();
        for _ in 0..16 {
            let gate = gate.clone();
            let launches = launches.clone();
            let barrier = barrier.clone();
            callers.push(tokio::spawn(async move {
                barrier.wait().await;
                gate.attempt_with_clock(
                    || async {
                        launches.fetch_add(1, Ordering::SeqCst);
                        tokio::task::yield_now().await;
                        Err::<(), _>("startup failed".to_string())
                    },
                    || now,
                )
                .await
            }));
        }
        for caller in callers {
            assert!(caller.await.unwrap().is_err());
        }
        assert_eq!(launches.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn disconnected_startup_records_cooldown_and_eventual_success_resets_it() {
        let gate = Arc::new(StartupRetry::default());
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        let now = Instant::now();
        let starting_gate = gate.clone();
        let startup = tokio::spawn(async move {
            starting_gate
                .attempt_with_clock(
                    || async {
                        started_tx.send(()).unwrap();
                        std::future::pending::<Result<(), String>>().await
                    },
                    || now,
                )
                .await
        });
        started_rx.await.unwrap();
        startup.abort();
        assert!(startup.await.unwrap_err().is_cancelled());

        let error = gate
            .attempt_with_clock::<(), _, _, _>(
                || async { panic!("a reconnect must not bypass the cooldown") },
                || now + Duration::from_secs(30),
            )
            .await
            .unwrap_err();
        assert!(error.contains("retry in 30s"));
        assert!(gate
            .attempt_with_clock(|| async { Ok(()) }, || now + INITIAL_DELAY)
            .await
            .is_ok());
        let state = gate.state.lock().await;
        assert_eq!(state.consecutive_failures, 0);
        assert_eq!(state.next_attempt, None);
    }
}
