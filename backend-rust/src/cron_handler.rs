use crate::cron::CRON_MANAGER;
use crate::types::CronJob;
use axum::{
    extract::Path,
    Json,
};
use chrono::Utc;
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateJobRequest {
    pub name: String,
    pub workdir: String,
    pub prompt: String,
    pub llm_provider: String,
    pub llm_model: String,
    pub schedule: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

fn default_true() -> bool {
    true
}

pub async fn list_jobs() -> Json<Vec<CronJob>> {
    Json(CRON_MANAGER.list_jobs().await)
}

pub async fn create_job(Json(req): Json<CreateJobRequest>) -> Json<CronJob> {
    let command = format!(
        "cd {} && skill-agent --streaming --llm-provider {} --llm-model {} agent \"{}\"",
        req.workdir, req.llm_provider, req.llm_model, req.prompt
    );
    let job = CronJob {
        id: String::new(),
        name: req.name,
        schedule: req.schedule,
        command,
        enabled: req.enabled,
        last_run: None,
        next_run: None,
        output: None,
        created_at: Some(Utc::now()),
        updated_at: Some(Utc::now()),
        environment: None,
        log_output: Some(true),
        email_to: None,
        tmux_session: None,
        workdir: Some(req.workdir),
        prompt: Some(req.prompt),
        llm_provider: Some(req.llm_provider),
        llm_model: Some(req.llm_model),
    };
    let created = CRON_MANAGER.create_job(job).await.expect("Failed to create job");
    Json(created)
}

pub async fn get_job(Path(id): Path<String>) -> Json<CronJob> {
    let jobs = CRON_MANAGER.list_jobs().await;
    let job = jobs.into_iter().find(|j| j.id == id).expect("Job not found");
    Json(job)
}

pub async fn update_job(Path(id): Path<String>, Json(req): Json<CreateJobRequest>) -> Json<CronJob> {
    let command = format!(
        "cd {} && skill-agent --streaming --llm-provider {} --llm-model {} agent \"{}\"",
        req.workdir, req.llm_provider, req.llm_model, req.prompt
    );
    let job = CronJob {
        id: id.clone(),
        name: req.name,
        schedule: req.schedule,
        command,
        enabled: req.enabled,
        last_run: None,
        next_run: None,
        output: None,
        created_at: None,
        updated_at: Some(Utc::now()),
        environment: None,
        log_output: Some(true),
        email_to: None,
        tmux_session: None,
        workdir: Some(req.workdir),
        prompt: Some(req.prompt),
        llm_provider: Some(req.llm_provider),
        llm_model: Some(req.llm_model),
    };
    let updated = CRON_MANAGER.update_job(id, job).await.expect("Failed to update job");
    Json(updated)
}

pub async fn delete_job(Path(id): Path<String>) -> Json<String> {
    CRON_MANAGER.delete_job(&id).await.expect("Failed to delete job");
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
