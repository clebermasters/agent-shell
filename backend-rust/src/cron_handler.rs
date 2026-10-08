use crate::cron::CRON_MANAGER;
use crate::types::CronJob;
use axum::{extract::Path, http::StatusCode, Json};
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

fn status_for_cron_error(message: &str) -> StatusCode {
    let message = message.to_ascii_lowercase();
    if message.contains("not found") {
        StatusCode::NOT_FOUND
    } else if message.contains("already exists")
        || message.contains("invalid")
        || message.contains("empty")
        || message.contains("require")
        || message.contains("single line")
        || message.contains("cannot contain")
    {
        StatusCode::BAD_REQUEST
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    }
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

pub async fn create_job(Json(req): Json<CreateJobRequest>) -> Result<Json<CronJob>, StatusCode> {
    let job = req.into_job(String::new(), Some(Utc::now()));
    let created = CRON_MANAGER
        .create_job(job)
        .await
        .map_err(|err| status_for_cron_error(&err.to_string()))?;
    Ok(Json(created))
}

pub async fn get_job(Path(id): Path<String>) -> Result<Json<CronJob>, StatusCode> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs
        .into_iter()
        .find(|j| j.id == id)
        .ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(job))
}

pub async fn update_job(
    Path(id): Path<String>,
    Json(req): Json<CreateJobRequest>,
) -> Result<Json<CronJob>, StatusCode> {
    let job = req.into_job(id.clone(), None);
    let updated = CRON_MANAGER
        .update_job(id, job)
        .await
        .map_err(|err| status_for_cron_error(&err.to_string()))?;
    Ok(Json(updated))
}

pub async fn delete_job(Path(id): Path<String>) -> Result<Json<String>, StatusCode> {
    CRON_MANAGER
        .delete_job(&id)
        .await
        .map_err(|err| status_for_cron_error(&err.to_string()))?;
    Ok(Json("deleted".to_string()))
}

pub async fn toggle_job(Path(id): Path<String>) -> Result<Json<CronJob>, StatusCode> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs
        .iter()
        .find(|j| j.id == id)
        .ok_or(StatusCode::NOT_FOUND)?;
    let toggled = CRON_MANAGER
        .toggle_job(&id, !job.enabled)
        .await
        .map_err(|err| status_for_cron_error(&err.to_string()))?;
    Ok(Json(toggled))
}

pub async fn test_job(Path(id): Path<String>) -> Result<Json<String>, StatusCode> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs
        .iter()
        .find(|j| j.id == id)
        .ok_or(StatusCode::NOT_FOUND)?;
    let output = CRON_MANAGER
        .test_command(&job.command)
        .await
        .map_err(|err| status_for_cron_error(&err.to_string()))?;
    Ok(Json(output))
}
