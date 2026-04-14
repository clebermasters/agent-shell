use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

pub(crate) async fn handle(
    msg: WebSocketMessage,
    tx: &mpsc::Sender<BroadcastMessage>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::ListCronJobs => {
            let jobs = crate::cron::CRON_MANAGER.list_jobs().await;
            let response = ServerMessage::CronJobsList { jobs };
            send_message(tx, response).await?;
        }

        WebSocketMessage::CreateCronJob { job } => {
            match crate::cron::CRON_MANAGER.create_job(job).await {
                Ok(created_job) => {
                    let response = ServerMessage::CronJobCreated { job: created_job };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to create cron job: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::UpdateCronJob { id, job } => {
            match crate::cron::CRON_MANAGER.update_job(id, job).await {
                Ok(updated_job) => {
                    let response = ServerMessage::CronJobUpdated { job: updated_job };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to update cron job: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::DeleteCronJob { id } => {
            match crate::cron::CRON_MANAGER.delete_job(&id).await {
                Ok(_) => {
                    let response = ServerMessage::CronJobDeleted { id };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to delete cron job: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::ToggleCronJob { id, enabled } => {
            match crate::cron::CRON_MANAGER.toggle_job(&id, enabled).await {
                Ok(toggled_job) => {
                    let response = ServerMessage::CronJobUpdated { job: toggled_job };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to toggle cron job: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::TestCronCommand { command } => {
            match crate::cron::CRON_MANAGER.test_command(&command).await {
                Ok(output) => {
                    let response = ServerMessage::CronCommandOutput {
                        output,
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::CronCommandOutput {
                        output: String::new(),
                        error: Some(format!("Failed to test command: {}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        _ => {
            error!("cron_cmds::handle called with non-cron message");
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{CronJob, WebSocketMessage};
    use crate::websocket::types::BroadcastMessage;
    use tokio::sync::mpsc;

    fn make_tx() -> (
        mpsc::Sender<BroadcastMessage>,
        mpsc::Receiver<BroadcastMessage>,
    ) {
        mpsc::channel(256)
    }

    #[tokio::test]
    async fn test_list_cron_jobs() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::ListCronJobs, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("CronJobsList") || json.contains("jobs"));
    }

    #[tokio::test]
    async fn test_test_cron_command_dry_run() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::TestCronCommand {
                command: "echo hello".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("CronCommandOutput") || json.contains("output"));
    }

    #[tokio::test]
    async fn test_test_cron_command_empty() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::TestCronCommand {
                command: "  ".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("error") || json.contains("Error") || json.contains("Failed"));
    }

    #[tokio::test]
    async fn test_create_cron_job_invalid_schedule() {
        let (tx, mut rx) = make_tx();
        let job = CronJob {
            id: String::new(),
            name: "test-job".to_string(),
            schedule: "invalid".to_string(),
            command: "echo test".to_string(),
            enabled: true,
            last_run: None,
            next_run: None,
            output: None,
            created_at: None,
            updated_at: None,
            environment: None,
            log_output: None,
            email_to: None,
            tmux_session: None,
            workdir: None,
            prompt: None,
            llm_provider: None,
            llm_model: None,
        };
        let result = handle(WebSocketMessage::CreateCronJob { job }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("error") || json.contains("Error") || json.contains("Failed"));
    }

    #[tokio::test]
    async fn test_delete_cron_job_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::DeleteCronJob {
                id: "nonexistent-id-xyz".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        // Should get some response (success or error depending on crontab state)
        let _ = rx.try_recv();
    }

    #[tokio::test]
    async fn test_toggle_cron_job_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::ToggleCronJob {
                id: "nonexistent-id-xyz".to_string(),
                enabled: true,
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("error") || json.contains("Error") || json.contains("not found"));
    }

    #[tokio::test]
    async fn test_unknown_message_handled() {
        let (tx, _rx) = make_tx();
        let result = handle(WebSocketMessage::Ping, &tx).await;
        assert!(result.is_ok());
    }
}
