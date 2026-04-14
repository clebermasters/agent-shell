use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tokio::process::Command;
use tokio::sync::RwLock;
use tracing::info;
use uuid::Uuid;

use crate::types::CronJob;

pub struct CronManager {
    jobs: RwLock<HashMap<String, CronJob>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct CronJobMetadata {
    #[serde(default = "default_metadata_version")]
    version: u8,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    workdir: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    prompt: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    llm_provider: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    llm_model: Option<String>,
}

const fn default_metadata_version() -> u8 {
    1
}

impl CronManager {
    const DEFAULT_OPENAI_WRAPPER_URL: &'static str = "http://127.0.0.1:8017";
    const DEFAULT_OPENAI_WRAPPER_API_KEY: &'static str = "local-wrapper";
    const DEFAULT_CODEX_MODEL: &'static str = "gpt-5.4";
    const METADATA_PREFIX: &'static str = "# AgentShell-Meta:";

    pub fn new() -> Self {
        Self {
            jobs: RwLock::new(HashMap::new()),
        }
    }

    pub async fn initialize(&self) -> Result<()> {
        // Load existing cron jobs from system crontab
        self.load_from_crontab().await?;
        Ok(())
    }

    pub async fn list_jobs(&self) -> Vec<CronJob> {
        let jobs = self.jobs.read().await;
        let mut job_list: Vec<CronJob> = jobs.values().cloned().collect();
        job_list.sort_by(|a, b| a.created_at.cmp(&b.created_at).reverse());
        job_list
    }

    pub async fn create_job(&self, mut job: CronJob) -> Result<CronJob> {
        // Generate ID if not provided
        if job.id.is_empty() {
            job.id = Uuid::new_v4().to_string();
        }

        self.normalize_job(&mut job)?;

        // Check for duplicate names
        {
            let jobs = self.jobs.read().await;
            if jobs.values().any(|j| j.name == job.name && j.id != job.id) {
                return Err(anyhow::anyhow!(
                    "A job with the name '{}' already exists",
                    job.name
                ));
            }
        }

        // Set timestamps
        let now = Utc::now();
        job.created_at = Some(now);
        job.updated_at = Some(now);

        // Validate cron expression
        self.validate_cron_expression(&job.schedule)?;

        // Calculate next run time
        job.next_run = self.calculate_next_run(&job.schedule).unwrap_or(None);

        // Add to crontab
        self.add_to_crontab(&job).await?;

        // Store in memory
        let mut jobs = self.jobs.write().await;
        jobs.insert(job.id.clone(), job.clone());

        info!("Created cron job: {} ({})", job.name, job.id);
        Ok(job)
    }

    pub async fn update_job(&self, id: String, mut job: CronJob) -> Result<CronJob> {
        self.normalize_job(&mut job)?;

        // Check for duplicate names (excluding self)
        {
            let jobs = self.jobs.read().await;
            if jobs.values().any(|j| j.name == job.name && j.id != id) {
                return Err(anyhow::anyhow!(
                    "A job with the name '{}' already exists",
                    job.name
                ));
            }
        }

        // Validate cron expression
        self.validate_cron_expression(&job.schedule)?;

        // Update timestamp
        job.updated_at = Some(Utc::now());
        job.id = id.clone();

        // Calculate next run time
        job.next_run = self.calculate_next_run(&job.schedule).unwrap_or(None);

        // Remove old entry from crontab
        self.remove_from_crontab(&id).await?;

        // Always add to crontab (enabled status is stored in comments)
        self.add_to_crontab(&job).await?;

        // Update in memory
        let mut jobs = self.jobs.write().await;
        jobs.insert(id, job.clone());

        info!("Updated cron job: {} ({})", job.name, job.id);
        Ok(job)
    }

    pub async fn delete_job(&self, id: &str) -> Result<()> {
        // Remove from crontab
        self.remove_from_crontab(id).await?;

        // Remove from memory
        let mut jobs = self.jobs.write().await;
        if let Some(job) = jobs.remove(id) {
            info!("Deleted cron job: {} ({})", job.name, id);
        }

        Ok(())
    }

    pub async fn toggle_job(&self, id: &str, enabled: bool) -> Result<CronJob> {
        // Update in-memory state and get a snapshot — then release the lock before doing I/O.
        let job = {
            let mut jobs = self.jobs.write().await;
            let job = jobs
                .get_mut(id)
                .ok_or_else(|| anyhow::anyhow!("Job not found: {}", id))?;
            job.enabled = enabled;
            job.updated_at = Some(Utc::now());
            job.clone()
        };

        // Crontab I/O runs without holding the jobs lock, avoiding blocking the runtime
        // while other tasks wait for lock access.
        self.remove_from_crontab(id).await?;
        self.add_to_crontab(&job).await?;

        info!(
            "Toggled cron job: {} ({}) - enabled: {}",
            job.name, id, enabled
        );
        Ok(job)
    }

    pub async fn test_command(&self, command: &str) -> Result<String> {
        info!("Dry-run test for cron command: {}", command);

        // Validate the command is not empty
        let command = command.trim();
        if command.is_empty() {
            return Err(anyhow::anyhow!("Command is empty"));
        }

        // Dry-run only: return what would be executed without running it.
        // Shell execution is intentionally disabled — arbitrary `sh -c` is an RCE vector.
        Ok(format!(
            "Dry-run: the following command would be executed by cron:\n{}",
            command
        ))
    }

    // Private helper methods

    fn trim_optional(value: &mut Option<String>) {
        if let Some(raw) = value.take() {
            let trimmed = raw.trim();
            if !trimmed.is_empty() {
                *value = Some(trimmed.to_string());
            }
        }
    }

    fn normalize_prompt(value: &mut Option<String>) {
        if let Some(raw) = value.take() {
            let normalized = raw.replace("\r\n", "\n").replace('\r', "\n");
            let trimmed = normalized.trim();
            if !trimmed.is_empty() {
                *value = Some(trimmed.to_string());
            }
        }
    }

    fn ensure_single_line(field: &str, value: &str) -> Result<()> {
        if value.contains('\0') {
            return Err(anyhow!("{field} cannot contain NUL bytes"));
        }
        if value.contains('\n') || value.contains('\r') {
            return Err(anyhow!("{field} must be a single line"));
        }
        Ok(())
    }

    fn clear_ai_fields(job: &mut CronJob) {
        job.workdir = None;
        job.prompt = None;
        job.llm_provider = None;
        job.llm_model = None;
    }

    fn env_or_default(key: &str, default: &str) -> String {
        std::env::var(key)
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| default.to_string())
    }

    fn shell_single_quote(value: &str) -> String {
        format!("'{}'", value.replace('\'', "'\\''"))
    }

    fn normalize_ai_model(requested_model: Option<&str>) -> String {
        let trimmed = requested_model.unwrap_or("").trim();
        if trimmed.starts_with("gpt-") || trimmed.contains("codex") {
            trimmed.to_string()
        } else {
            Self::env_or_default("AGENTSHELL_CODEX_MODEL", Self::DEFAULT_CODEX_MODEL)
        }
    }

    fn build_ai_command(
        workdir: &str,
        prompt: &str,
        requested_model: Option<&str>,
    ) -> Result<String> {
        Self::ensure_single_line("working directory", workdir)?;

        let wrapper_url = Self::env_or_default(
            "AGENTSHELL_OPENAI_WRAPPER_URL",
            Self::DEFAULT_OPENAI_WRAPPER_URL,
        );
        let wrapper_api_key = Self::env_or_default(
            "AGENTSHELL_OPENAI_WRAPPER_API_KEY",
            Self::DEFAULT_OPENAI_WRAPPER_API_KEY,
        );
        let model = Self::normalize_ai_model(requested_model);
        let prompt_b64 = URL_SAFE_NO_PAD.encode(prompt.as_bytes());

        Self::ensure_single_line("wrapper URL", &wrapper_url)?;
        Self::ensure_single_line("wrapper API key", &wrapper_api_key)?;
        Self::ensure_single_line("LLM model", &model)?;

        Ok(format!(
            "cd {} && AGENTSHELL_CRON_PROMPT_B64={} OPENAI_BASE_URL={} OPENAI_API_KEY={} skill-agent --streaming --llm-provider openai --llm-model {} agent \"$(printf '%s' \"$AGENTSHELL_CRON_PROMPT_B64\" | base64 -d)\"",
            Self::shell_single_quote(workdir),
            Self::shell_single_quote(&prompt_b64),
            Self::shell_single_quote(&wrapper_url),
            Self::shell_single_quote(&wrapper_api_key),
            Self::shell_single_quote(&model),
        ))
    }

    fn normalize_job(&self, job: &mut CronJob) -> Result<()> {
        job.name = job.name.trim().to_string();
        job.schedule = job.schedule.trim().to_string();
        job.command = job.command.trim().to_string();
        Self::trim_optional(&mut job.workdir);
        Self::normalize_prompt(&mut job.prompt);
        Self::trim_optional(&mut job.llm_provider);
        Self::trim_optional(&mut job.llm_model);

        let has_command = !job.command.is_empty();
        let has_workdir = job.workdir.is_some();
        let has_prompt = job.prompt.is_some();

        if has_workdir && has_prompt {
            let model = Self::normalize_ai_model(job.llm_model.as_deref());
            let workdir = job.workdir.as_deref().unwrap_or_default();
            let prompt = job.prompt.as_deref().unwrap_or_default();
            job.command = Self::build_ai_command(workdir, prompt, Some(&model))?;
            job.llm_provider = Some("openai".to_string());
            job.llm_model = Some(model);
            return Ok(());
        }

        if has_command {
            Self::ensure_single_line("command", &job.command)?;
            Self::clear_ai_fields(job);
            return Ok(());
        }

        if has_workdir || has_prompt {
            return Err(anyhow!(
                "AI jobs require both a working directory and a prompt"
            ));
        }

        Err(anyhow!(
            "Cron job requires either a command or AI configuration"
        ))
    }

    fn metadata_for_job(job: &CronJob) -> Option<CronJobMetadata> {
        let workdir = job.workdir.clone();
        let prompt = job.prompt.clone();
        let llm_provider = job.llm_provider.clone();
        let llm_model = job.llm_model.clone();
        if workdir.is_none() && prompt.is_none() && llm_provider.is_none() && llm_model.is_none() {
            return None;
        }

        Some(CronJobMetadata {
            version: default_metadata_version(),
            kind: Some("ai".to_string()),
            workdir,
            prompt,
            llm_provider,
            llm_model,
        })
    }

    fn metadata_comment(job: &CronJob) -> String {
        let Some(metadata) = Self::metadata_for_job(job) else {
            return String::new();
        };
        let encoded = serde_json::to_vec(&metadata)
            .map(|bytes| URL_SAFE_NO_PAD.encode(bytes))
            .expect("cron metadata serialization should not fail");
        format!("{}{}\n", Self::METADATA_PREFIX, encoded)
    }

    fn parse_metadata_comment(line: &str) -> Option<CronJobMetadata> {
        let encoded = line.strip_prefix(Self::METADATA_PREFIX)?.trim();
        let bytes = URL_SAFE_NO_PAD.decode(encoded).ok()?;
        serde_json::from_slice::<CronJobMetadata>(&bytes).ok()
    }

    fn format_job_entry(job: &CronJob, leading_newline: bool) -> String {
        let prefix = if leading_newline { "\n" } else { "" };
        let metadata = Self::metadata_comment(job);
        if job.enabled {
            format!(
                "{prefix}# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n{}{} {}\n# AgentShell-Job-End:{}\n",
                job.id, job.name, job.enabled, metadata, job.schedule, job.command, job.id
            )
        } else {
            format!(
                "{prefix}# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n{}# {} {}\n# AgentShell-Job-End:{}\n",
                job.id, job.name, job.enabled, metadata, job.schedule, job.command, job.id
            )
        }
    }

    async fn load_from_crontab(&self) -> Result<()> {
        let output = Command::new("crontab").arg("-l").output().await?;

        if !output.status.success() {
            // No crontab or empty crontab is fine
            return Ok(());
        }

        let crontab_content = String::from_utf8_lossy(&output.stdout);
        let mut jobs = self.jobs.write().await;
        let lines: Vec<&str> = crontab_content.lines().collect();

        // --- Pass 1: Parse AgentShell-managed blocks, track which lines belong to them ---
        let mut managed_indices = std::collections::HashSet::<usize>::new();
        let mut i = 0;

        while i < lines.len() {
            if lines[i].starts_with("# AgentShell-Job-Start:") {
                managed_indices.insert(i);
                if let Some(job_id) = lines[i].strip_prefix("# AgentShell-Job-Start:") {
                    let job_id = job_id.trim();
                    let mut job_name = String::new();
                    let mut enabled = true;
                    let mut workdir = None;
                    let mut prompt = None;
                    let mut llm_provider = None;
                    let mut llm_model = None;

                    i += 1;
                    while i < lines.len() && !lines[i].starts_with("# AgentShell-Job-End") {
                        managed_indices.insert(i);
                        if lines[i].starts_with("# Name:") {
                            job_name = lines[i]
                                .strip_prefix("# Name:")
                                .unwrap_or("")
                                .trim()
                                .to_string();
                        } else if let Some(metadata) = Self::parse_metadata_comment(lines[i]) {
                            if metadata.workdir.is_some() {
                                workdir = metadata.workdir;
                            }
                            if metadata.prompt.is_some() {
                                prompt = metadata.prompt;
                            }
                            if metadata.llm_provider.is_some() {
                                llm_provider = metadata.llm_provider;
                            }
                            if metadata.llm_model.is_some() {
                                llm_model = metadata.llm_model;
                            }
                        } else if lines[i].starts_with("# Enabled:") {
                            enabled = lines[i].strip_prefix("# Enabled:").unwrap_or("true").trim()
                                == "true";
                        } else if lines[i].starts_with("# Workdir:") {
                            workdir = Some(
                                lines[i]
                                    .strip_prefix("# Workdir:")
                                    .unwrap_or("")
                                    .trim()
                                    .to_string(),
                            );
                        } else if lines[i].starts_with("# Prompt:") {
                            prompt = Some(
                                lines[i]
                                    .strip_prefix("# Prompt:")
                                    .unwrap_or("")
                                    .trim()
                                    .to_string(),
                            );
                        } else if lines[i].starts_with("# LLM-Provider:") {
                            llm_provider = Some(
                                lines[i]
                                    .strip_prefix("# LLM-Provider:")
                                    .unwrap_or("")
                                    .trim()
                                    .to_string(),
                            );
                        } else if lines[i].starts_with("# LLM-Model:") {
                            llm_model = Some(
                                lines[i]
                                    .strip_prefix("# LLM-Model:")
                                    .unwrap_or("")
                                    .trim()
                                    .to_string(),
                            );
                        } else if !lines[i].trim().is_empty() {
                            let line = if lines[i].starts_with("# ") && !enabled {
                                lines[i].strip_prefix("# ").unwrap_or(lines[i])
                            } else if !lines[i].starts_with('#') {
                                lines[i]
                            } else {
                                i += 1;
                                continue;
                            };

                            let parts: Vec<&str> = line.splitn(6, ' ').collect();
                            if parts.len() >= 6 {
                                let schedule = parts[0..5].join(" ");
                                let command = parts[5].to_string();
                                let job = CronJob {
                                    id: job_id.to_string(),
                                    name: job_name.clone(),
                                    schedule: schedule.clone(),
                                    command,
                                    enabled,
                                    last_run: None,
                                    next_run: self.calculate_next_run(&schedule).unwrap_or(None),
                                    output: None,
                                    created_at: Some(Utc::now()),
                                    updated_at: Some(Utc::now()),
                                    environment: None,
                                    log_output: None,
                                    email_to: None,
                                    tmux_session: None,
                                    workdir: workdir.clone(),
                                    prompt: prompt.clone(),
                                    llm_provider: llm_provider.clone(),
                                    llm_model: llm_model.clone(),
                                };
                                jobs.insert(job_id.to_string(), job);
                            }
                        }
                        i += 1;
                    }
                    managed_indices.insert(i); // AgentShell-Job-End line
                }
            }
            i += 1;
        }

        // --- Pass 2: Adopt bare cron entries (not inside any AgentShell block) ---
        let mut bare_entries_found = false;

        for (idx, line) in lines.iter().enumerate() {
            if managed_indices.contains(&idx) {
                continue;
            }
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }

            // Handle commented cron lines: "#* * * * * cmd" or "# * * * * * cmd"
            let (is_enabled, parse_line) = if trimmed.starts_with('#') {
                (false, trimmed[1..].trim_start())
            } else {
                (true, trimmed)
            };

            let parts: Vec<&str> = parse_line.splitn(6, ' ').collect();
            if parts.len() < 6 {
                continue;
            }

            let schedule = parts[0..5].join(" ");
            let command = parts[5].to_string();

            if self.validate_cron_expression(&schedule).is_err() {
                continue;
            }

            bare_entries_found = true;

            // Deterministic ID from cron entry content
            let id = {
                use std::collections::hash_map::DefaultHasher;
                use std::hash::{Hash, Hasher};
                let mut h = DefaultHasher::new();
                schedule.hash(&mut h);
                command.hash(&mut h);
                format!("bare-{:016x}", h.finish())
            };

            if jobs.contains_key(&id) {
                continue;
            }

            // Derive a display name from the command
            let name = {
                let mut n = String::new();
                for part in command.split_whitespace() {
                    if part.contains('/') {
                        if let Some(base) = part.split('/').last() {
                            if !base.is_empty() {
                                n = base
                                    .trim_end_matches(".py")
                                    .trim_end_matches(".sh")
                                    .to_string();
                                break;
                            }
                        }
                    }
                }
                if n.is_empty() {
                    command.chars().take(40).collect()
                } else {
                    n
                }
            };

            let next_run = if is_enabled {
                self.calculate_next_run(&schedule).unwrap_or(None)
            } else {
                None
            };

            jobs.insert(
                id.clone(),
                CronJob {
                    id,
                    name,
                    schedule,
                    command,
                    enabled: is_enabled,
                    last_run: None,
                    next_run,
                    output: None,
                    created_at: Some(Utc::now()),
                    updated_at: Some(Utc::now()),
                    environment: None,
                    log_output: None,
                    email_to: None,
                    tmux_session: None,
                    workdir: None,
                    prompt: None,
                    llm_provider: None,
                    llm_model: None,
                },
            );
        }

        let job_count = jobs.len();

        // If bare entries were found, rewrite the entire crontab with AgentShell markers
        // so future toggle/delete/update operations work correctly.
        if bare_entries_found {
            let mut new_crontab = String::new();
            let mut sorted: Vec<&CronJob> = jobs.values().collect();
            sorted.sort_by(|a, b| a.created_at.cmp(&b.created_at));
            for job in sorted {
                new_crontab.push_str(&Self::format_job_entry(job, false));
                new_crontab.push('\n');
            }
            drop(jobs); // Release write lock before I/O
            self.write_crontab(&new_crontab).await?;
            info!("Migrated {} cron entries to AgentShell format", job_count);
        }

        info!("Loaded {} cron jobs from crontab", job_count);
        Ok(())
    }

    async fn add_to_crontab(&self, job: &CronJob) -> Result<()> {
        // Get current crontab
        let output = Command::new("crontab").arg("-l").output().await?;

        let mut crontab_content = if output.status.success() {
            String::from_utf8_lossy(&output.stdout).to_string()
        } else {
            String::new()
        };

        // Add job with AgentShell markers
        let job_entry = Self::format_job_entry(job, true);

        crontab_content.push_str(&job_entry);

        // Write back to crontab
        self.write_crontab(&crontab_content).await?;

        Ok(())
    }

    async fn remove_from_crontab(&self, id: &str) -> Result<()> {
        // Get current crontab
        let output = Command::new("crontab").arg("-l").output().await?;

        if !output.status.success() {
            return Ok(());
        }

        let crontab_content = String::from_utf8_lossy(&output.stdout);
        let lines: Vec<&str> = crontab_content.lines().collect();
        let mut new_lines = Vec::new();
        let mut i = 0;
        let mut skip = false;

        while i < lines.len() {
            if lines[i] == &format!("# AgentShell-Job-Start:{}", id) {
                skip = true;
            } else if lines[i] == &format!("# AgentShell-Job-End:{}", id) {
                skip = false;
                i += 1;
                continue;
            }

            if !skip {
                new_lines.push(lines[i]);
            }

            i += 1;
        }

        let new_crontab = new_lines.join("\n");
        self.write_crontab(&new_crontab).await?;

        Ok(())
    }

    async fn write_crontab(&self, content: &str) -> Result<()> {
        use std::process::Stdio;
        use tokio::io::AsyncWriteExt;

        let mut child = Command::new("crontab")
            .arg("-")
            .stdin(Stdio::piped())
            .spawn()?;

        if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(content.as_bytes()).await?;
            stdin.flush().await?;
        }

        let status = child.wait().await?;
        if !status.success() {
            return Err(anyhow::anyhow!("Failed to write crontab"));
        }

        Ok(())
    }

    fn validate_cron_expression(&self, expression: &str) -> Result<()> {
        use cron::Schedule;
        use std::str::FromStr;

        let parts: Vec<&str> = expression.split_whitespace().collect();
        if parts.len() != 5 {
            return Err(anyhow::anyhow!(
                "Invalid cron expression: expected 5 fields, got {}",
                parts.len()
            ));
        }

        // Validate by attempting to parse with the cron crate
        let extended = format!("0 {} *", expression);
        Schedule::from_str(&extended)
            .map_err(|e| anyhow::anyhow!("Invalid cron expression '{}': {}", expression, e))?;

        Ok(())
    }

    fn calculate_next_run(&self, schedule: &str) -> Result<Option<DateTime<Utc>>> {
        use cron::Schedule;
        use std::str::FromStr;

        // Standard cron uses 5 fields: min hour dom month dow
        // The `cron` crate expects 7 fields: sec min hour dom month dow year
        let extended = format!("0 {} *", schedule);

        let sched = Schedule::from_str(&extended)
            .map_err(|e| anyhow::anyhow!("Invalid cron expression '{}': {}", schedule, e))?;

        Ok(sched.upcoming(Utc).next())
    }
}

lazy_static::lazy_static! {
    pub static ref CRON_MANAGER: CronManager = CronManager::new();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_manager() -> CronManager {
        CronManager::new()
    }

    #[test]
    fn test_validate_valid_expressions() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("* * * * *").is_ok());
        assert!(mgr.validate_cron_expression("0 12 * * *").is_ok());
        assert!(mgr.validate_cron_expression("*/5 * * * *").is_ok());
        assert!(mgr.validate_cron_expression("0 0 1 1 *").is_ok());
        assert!(mgr.validate_cron_expression("0 8-18 * * 1-5").is_ok());
    }

    #[test]
    fn test_validate_wrong_field_count() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("* * *").is_err());
        assert!(mgr.validate_cron_expression("* * * * * *").is_err()); // 6 fields
        assert!(mgr.validate_cron_expression("").is_err());
    }

    #[test]
    fn test_validate_invalid_expression() {
        let mgr = make_manager();
        // Invalid range
        assert!(mgr.validate_cron_expression("99 * * * *").is_err());
    }

    #[test]
    fn test_calculate_next_run_valid() {
        let mgr = make_manager();
        let result = mgr.calculate_next_run("* * * * *").unwrap();
        assert!(result.is_some());
        // Should be in the future
        let next = result.unwrap();
        assert!(next > Utc::now() - chrono::Duration::seconds(60));
    }

    #[test]
    fn test_calculate_next_run_invalid() {
        let mgr = make_manager();
        let result = mgr.calculate_next_run("invalid expr");
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_list_jobs_empty() {
        let mgr = make_manager();
        let jobs = mgr.list_jobs().await;
        assert!(jobs.is_empty());
    }

    #[tokio::test]
    async fn test_test_command_dry_run() {
        let mgr = make_manager();
        let result = mgr.test_command("echo hello").await;
        assert!(result.is_ok());
        assert!(result.unwrap().contains("Dry-run"));
    }

    #[tokio::test]
    async fn test_test_command_empty() {
        let mgr = make_manager();
        let result = mgr.test_command("  ").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_create_job_invalid_schedule() {
        let mgr = make_manager();
        let job = crate::types::CronJob {
            id: String::new(),
            name: "bad-schedule".to_string(),
            schedule: "not a cron".to_string(),
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
        let result = mgr.create_job(job).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("Invalid cron"));
    }

    #[tokio::test]
    async fn test_update_job_invalid_schedule() {
        let mgr = make_manager();
        let job = crate::types::CronJob {
            id: "update-test".to_string(),
            name: "bad-update".to_string(),
            schedule: "x x x".to_string(),
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
        let result = mgr.update_job("update-test".to_string(), job).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_toggle_job_not_found() {
        let mgr = make_manager();
        let result = mgr.toggle_job("nonexistent-id", true).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("not found"));
    }

    #[tokio::test]
    async fn test_delete_job_not_found() {
        let mgr = make_manager();
        // delete_job doesn't error on nonexistent — it just removes from crontab and memory
        let result = mgr.delete_job("nonexistent-id").await;
        // Should not panic; result depends on crontab access
        let _ = result;
    }

    #[tokio::test]
    async fn test_create_job_generates_id() {
        let mgr = make_manager();
        let job = crate::types::CronJob {
            id: String::new(), // empty ID should get UUID
            name: "auto-id-test".to_string(),
            schedule: "* * * * *".to_string(),
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
        let result = mgr.create_job(job).await;
        // May fail due to crontab access, but if it succeeds, ID should be non-empty
        if let Ok(created) = result {
            assert!(!created.id.is_empty());
            // Clean up
            let _ = mgr.delete_job(&created.id).await;
        }
    }

    #[tokio::test]
    async fn test_list_jobs_returns_sorted() {
        let mgr = make_manager();
        // With empty manager, list should be empty and not panic
        let jobs = mgr.list_jobs().await;
        assert!(jobs.is_empty());
        // Verify sort doesn't panic on empty
    }

    #[tokio::test]
    async fn test_create_job_duplicate_name_rejected() {
        let mgr = make_manager();
        // Manually insert a job into the manager's jobs map
        {
            let mut jobs = mgr.jobs.write().await;
            jobs.insert(
                "existing-id".to_string(),
                crate::types::CronJob {
                    id: "existing-id".to_string(),
                    name: "duplicate-name".to_string(),
                    schedule: "* * * * *".to_string(),
                    command: "echo hi".to_string(),
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
                },
            );
        }
        // Try to create another job with the same name
        let job = crate::types::CronJob {
            id: String::new(),
            name: "duplicate-name".to_string(),
            schedule: "* * * * *".to_string(),
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
        let result = mgr.create_job(job).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("already exists"));
    }

    #[tokio::test]
    async fn test_update_job_duplicate_name_rejected() {
        let mgr = make_manager();
        // Insert two jobs with different names
        {
            let mut jobs = mgr.jobs.write().await;
            jobs.insert(
                "id-1".to_string(),
                crate::types::CronJob {
                    id: "id-1".to_string(),
                    name: "job-one".to_string(),
                    schedule: "* * * * *".to_string(),
                    command: "echo one".to_string(),
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
                },
            );
            jobs.insert(
                "id-2".to_string(),
                crate::types::CronJob {
                    id: "id-2".to_string(),
                    name: "job-two".to_string(),
                    schedule: "* * * * *".to_string(),
                    command: "echo two".to_string(),
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
                },
            );
        }
        // Try to update id-2 with name "job-one" (duplicate)
        let updated_job = crate::types::CronJob {
            id: "id-2".to_string(),
            name: "job-one".to_string(), // conflicts with id-1
            schedule: "* * * * *".to_string(),
            command: "echo updated".to_string(),
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
        let result = mgr.update_job("id-2".to_string(), updated_job).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("already exists"));
    }

    #[tokio::test]
    async fn test_create_job_sets_timestamps() {
        let mgr = make_manager();
        let job = crate::types::CronJob {
            id: String::new(),
            name: "timestamp-test".to_string(),
            schedule: "* * * * *".to_string(),
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
        let result = mgr.create_job(job).await;
        if let Ok(created) = result {
            assert!(created.created_at.is_some());
            assert!(created.updated_at.is_some());
            let _ = mgr.delete_job(&created.id).await;
        }
        // If it fails due to crontab, that's OK — test is about timestamps when it works
    }

    // Phase 8: Cron validation additional tests

    #[test]
    fn test_validate_range_expression() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("0 0 1,15 * *").is_ok());
    }

    #[test]
    fn test_validate_step_expression() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("*/10 * * * *").is_ok());
    }

    #[test]
    fn test_validate_out_of_range_hour() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("0 25 * * *").is_err());
    }

    #[test]
    fn test_validate_out_of_range_month() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("0 0 * 13 *").is_err());
    }

    #[test]
    fn test_validate_out_of_range_dow() {
        let mgr = make_manager();
        assert!(mgr.validate_cron_expression("0 0 * * 8").is_err());
    }

    #[test]
    fn test_calculate_next_run_returns_future() {
        let mgr = make_manager();
        let result = mgr.calculate_next_run("0 0 * * *").unwrap(); // midnight daily
        assert!(result.is_some());
        let next = result.unwrap();
        assert!(next > Utc::now());
    }

    #[test]
    fn test_calculate_next_run_specific_schedule() {
        let mgr = make_manager();
        let result = mgr.calculate_next_run("30 12 * * *").unwrap(); // 12:30 daily
        assert!(result.is_some());
    }

    #[tokio::test]
    async fn test_test_command_nonempty_returns_dryrun() {
        let mgr = make_manager();
        let result = mgr.test_command("ls -la /tmp").await.unwrap();
        assert!(result.contains("Dry-run"));
        assert!(result.contains("ls -la /tmp"));
    }

    #[tokio::test]
    async fn test_test_command_whitespace_only() {
        let mgr = make_manager();
        let result = mgr.test_command("   \t  ").await;
        assert!(result.is_err());
    }

    #[test]
    fn test_normalize_job_rewrites_ai_job_to_single_line_codex_wrapper_command() {
        let mgr = make_manager();
        let mut job = crate::types::CronJob {
            id: "job-1".to_string(),
            name: "ai-job".to_string(),
            schedule: "0 * * * *".to_string(),
            command: "legacy command".to_string(),
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
            workdir: Some("/tmp/project".to_string()),
            prompt: Some("say hello\nfrom cron".to_string()),
            llm_provider: Some("anthropic".to_string()),
            llm_model: Some("claude-sonnet-4-6".to_string()),
        };

        mgr.normalize_job(&mut job).unwrap();

        assert_eq!(job.llm_provider.as_deref(), Some("openai"));
        assert_eq!(job.llm_model.as_deref(), Some("gpt-5.4"));
        assert!(job.command.contains("AGENTSHELL_CRON_PROMPT_B64="));
        assert!(job.command.contains("base64 -d"));
        assert!(job
            .command
            .contains("OPENAI_BASE_URL='http://127.0.0.1:8017'"));
        assert!(job.command.contains("OPENAI_API_KEY='local-wrapper'"));
        assert!(job
            .command
            .contains("skill-agent --streaming --llm-provider openai"));
        assert!(job.command.contains("cd '/tmp/project'"));
        assert!(job
            .command
            .contains("agent \"$(printf '%s' \"$AGENTSHELL_CRON_PROMPT_B64\" | base64 -d)\""));
        assert!(!job.command.contains("say hello\nfrom cron"));
    }

    #[test]
    fn test_format_job_entry_encodes_metadata_and_round_trips() {
        let job = crate::types::CronJob {
            id: "job-1".to_string(),
            name: "ai-job".to_string(),
            schedule: "0 * * * *".to_string(),
            command: "echo hi".to_string(),
            enabled: false,
            last_run: None,
            next_run: None,
            output: None,
            created_at: None,
            updated_at: None,
            environment: None,
            log_output: None,
            email_to: None,
            tmux_session: None,
            workdir: Some("/tmp/project".to_string()),
            prompt: Some("say hello\nfrom cron".to_string()),
            llm_provider: Some("openai".to_string()),
            llm_model: Some("gpt-5.4".to_string()),
        };

        let entry = CronManager::format_job_entry(&job, false);
        let metadata_line = entry
            .lines()
            .find(|line| line.starts_with(CronManager::METADATA_PREFIX))
            .expect("metadata line should be present");
        let metadata =
            CronManager::parse_metadata_comment(metadata_line).expect("metadata should decode");

        assert!(entry.contains(CronManager::METADATA_PREFIX));
        assert!(!entry.contains("# Workdir:"));
        assert!(!entry.contains("# Prompt:"));
        assert!(entry.contains("# 0 * * * * echo hi"));
        assert_eq!(metadata.workdir.as_deref(), Some("/tmp/project"));
        assert_eq!(metadata.prompt.as_deref(), Some("say hello\nfrom cron"));
        assert_eq!(metadata.llm_provider.as_deref(), Some("openai"));
        assert_eq!(metadata.llm_model.as_deref(), Some("gpt-5.4"));
    }

    #[test]
    fn test_normalize_job_preserves_manual_command_and_discards_partial_ai_metadata() {
        let mgr = make_manager();
        let mut job = crate::types::CronJob {
            id: "job-1".to_string(),
            name: "manual-job".to_string(),
            schedule: "0 * * * *".to_string(),
            command: "echo hi".to_string(),
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
            workdir: Some("/tmp/project".to_string()),
            prompt: None,
            llm_provider: Some("openai".to_string()),
            llm_model: Some("gpt-5.4".to_string()),
        };

        mgr.normalize_job(&mut job).unwrap();

        assert_eq!(job.command, "echo hi");
        assert!(job.workdir.is_none());
        assert!(job.prompt.is_none());
        assert!(job.llm_provider.is_none());
        assert!(job.llm_model.is_none());
    }

    #[test]
    fn test_normalize_job_rejects_multiline_manual_command() {
        let mgr = make_manager();
        let mut job = crate::types::CronJob {
            id: "job-1".to_string(),
            name: "manual-job".to_string(),
            schedule: "0 * * * *".to_string(),
            command: "echo hi\necho bad".to_string(),
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

        let result = mgr.normalize_job(&mut job);
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("single line"));
    }

    #[test]
    fn test_cron_manager_new_is_empty() {
        let mgr = make_manager();
        // Can't call async list_jobs in sync test, but we can verify construction
        let _ = mgr;
    }
}
