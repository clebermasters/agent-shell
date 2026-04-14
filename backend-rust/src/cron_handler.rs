use crate::cron::CRON_MANAGER;
use crate::types::CronJob;
use axum::{extract::Path, Json};
use chrono::Utc;
use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateJobRequest {
    pub name: String,
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub workdir: Option<String>,
    #[serde(default)]
    pub prompt: Option<String>,
    #[serde(default)]
    pub llm_provider: Option<String>,
    #[serde(default)]
    pub llm_model: Option<String>,
    pub schedule: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub environment: Option<HashMap<String, String>>,
    #[serde(default)]
    pub log_output: Option<bool>,
    #[serde(default)]
    pub email_to: Option<String>,
    #[serde(default)]
    pub tmux_session: Option<String>,
}

fn default_true() -> bool {
    true
}

impl CreateJobRequest {
    fn into_job(self, id: String, created_at: Option<chrono::DateTime<Utc>>) -> CronJob {
        CronJob {
            id,
            name: self.name,
            schedule: self.schedule,
            command: self.command.unwrap_or_default(),
            enabled: self.enabled,
            last_run: None,
            next_run: None,
            output: None,
            created_at,
            updated_at: Some(Utc::now()),
            environment: self.environment,
            log_output: self.log_output.or(Some(true)),
            email_to: self.email_to,
            tmux_session: self.tmux_session,
            workdir: self.workdir,
            prompt: self.prompt,
            llm_provider: self.llm_provider,
            llm_model: self.llm_model,
        }
    }
}

pub async fn list_jobs() -> Json<Vec<CronJob>> {
    Json(CRON_MANAGER.list_jobs().await)
}

pub async fn create_job(Json(req): Json<CreateJobRequest>) -> Json<CronJob> {
    let job = req.into_job(String::new(), Some(Utc::now()));
    let created = CRON_MANAGER
        .create_job(job)
        .await
        .expect("Failed to create job");
    Json(created)
}

pub async fn get_job(Path(id): Path<String>) -> Json<CronJob> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs
        .into_iter()
        .find(|j| j.id == id)
        .expect("Job not found");
    Json(job)
}

pub async fn update_job(
    Path(id): Path<String>,
    Json(req): Json<CreateJobRequest>,
) -> Json<CronJob> {
    let job = req.into_job(id.clone(), None);
    let updated = CRON_MANAGER
        .update_job(id, job)
        .await
        .expect("Failed to update job");
    Json(updated)
}

pub async fn delete_job(Path(id): Path<String>) -> Json<String> {
    CRON_MANAGER
        .delete_job(&id)
        .await
        .expect("Failed to delete job");
    Json("deleted".to_string())
}

pub async fn toggle_job(Path(id): Path<String>) -> Json<CronJob> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs.iter().find(|j| j.id == id).expect("Job not found");
    let toggled = CRON_MANAGER
        .toggle_job(&id, !job.enabled)
        .await
        .expect("Failed to toggle job");
    Json(toggled)
}

pub async fn test_job(Path(id): Path<String>) -> Json<String> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs.iter().find(|j| j.id == id).expect("Job not found");
    let output = CRON_MANAGER
        .test_command(&job.command)
        .await
        .expect("Failed to test");
    Json(output)
}
