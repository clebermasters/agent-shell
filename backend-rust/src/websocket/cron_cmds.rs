use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

pub(crate) async fn handle(
    msg: WebSocketMessage,
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
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
