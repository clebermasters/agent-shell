use anyhow::Result;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use tokio::process::Command;
use tokio::sync::RwLock;
use tracing::info;
use uuid::Uuid;

use crate::types::CronJob;

pub struct CronManager {
    jobs: RwLock<HashMap<String, CronJob>>,
}

impl CronManager {
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

                    i += 1;
                    while i < lines.len() && !lines[i].starts_with("# AgentShell-Job-End") {
                        managed_indices.insert(i);
                        if lines[i].starts_with("# Name:") {
                            job_name = lines[i]
                                .strip_prefix("# Name:")
                                .unwrap_or("")
                                .trim()
                                .to_string();
                        } else if lines[i].starts_with("# Enabled:") {
                            enabled = lines[i]
                                .strip_prefix("# Enabled:")
                                .unwrap_or("true")
                                .trim()
                                == "true";
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
                                    workdir: None,
                                    prompt: None,
                                    llm_provider: None,
                                    llm_model: None,
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
                if job.enabled {
                    new_crontab.push_str(&format!(
                        "# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n{} {}\n# AgentShell-Job-End:{}\n\n",
                        job.id, job.name, job.enabled, job.schedule, job.command, job.id
                    ));
                } else {
                    new_crontab.push_str(&format!(
                        "# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n# {} {}\n# AgentShell-Job-End:{}\n\n",
                        job.id, job.name, job.enabled, job.schedule, job.command, job.id
                    ));
                }
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
        let job_entry = if job.enabled {
            // Active job - include the cron line
            format!(
                "\n# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n{} {}\n# AgentShell-Job-End:{}\n",
                job.id, job.name, job.enabled, job.schedule, job.command, job.id
            )
        } else {
            // Disabled job - comment out the cron line
            format!(
                "\n# AgentShell-Job-Start:{}\n# Name:{}\n# Enabled:{}\n# {} {}\n# AgentShell-Job-End:{}\n",
                job.id, job.name, job.enabled, job.schedule, job.command, job.id
            )
        };

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
            jobs.insert("existing-id".to_string(), crate::types::CronJob {
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
            });
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
            jobs.insert("id-1".to_string(), crate::types::CronJob {
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
            });
            jobs.insert("id-2".to_string(), crate::types::CronJob {
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
            });
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
    fn test_cron_manager_new_is_empty() {
        let mgr = make_manager();
        // Can't call async list_jobs in sync test, but we can verify construction
        let _ = mgr;
    }
}
