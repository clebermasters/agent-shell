use anyhow::Result;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use std::process::Command;
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
        let mut jobs = self.jobs.write().await;

        if let Some(job) = jobs.get_mut(id) {
            job.enabled = enabled;
            job.updated_at = Some(Utc::now());

            // Always remove first to avoid duplicates
            self.remove_from_crontab(id).await?;

            // Then add back with updated status
            self.add_to_crontab(job).await?;

            info!(
                "Toggled cron job: {} ({}) - enabled: {}",
                job.name, id, enabled
            );
            Ok(job.clone())
        } else {
            Err(anyhow::anyhow!("Job not found: {}", id))
        }
    }

    pub async fn test_command(&self, command: &str) -> Result<String> {
        info!("Testing cron command: {}", command);

        // Basic security check - reject obvious dangerous patterns
        let dangerous_patterns = [
            "rm -rf /",
            "rm -fr /",
            "dd if=/dev/zero",
            ":(){ :|:& };:",
            "mkfs.",
            "format ",
            "> /dev/sda",
            "chmod -R 777 /",
        ];

        let command_lower = command.to_lowercase();
        for pattern in &dangerous_patterns {
            if command_lower.contains(pattern) {
                return Err(anyhow::anyhow!(
                    "Command contains potentially dangerous pattern"
                ));
            }
        }

        // Execute command in a shell with timeout
        let output = tokio::process::Command::new("timeout")
            .arg("10") // 10 second timeout
            .arg("sh")
            .arg("-c")
            .arg(command)
            .output()
            .await?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);

        if output.status.success() {
            Ok(format!("Success:\n{}", stdout))
        } else {
            Ok(format!("Failed:\nStdout: {}\nStderr: {}", stdout, stderr))
        }
    }

    // Private helper methods

    async fn load_from_crontab(&self) -> Result<()> {
        let output = Command::new("crontab").arg("-l").output()?;

        if !output.status.success() {
            // No crontab or empty crontab is fine
            return Ok(());
        }

        let crontab_content = String::from_utf8_lossy(&output.stdout);
        let mut jobs = self.jobs.write().await;

        // Parse AgentShell-managed jobs from crontab
        // Look for special comment markers
        let lines: Vec<&str> = crontab_content.lines().collect();
        let mut i = 0;

        while i < lines.len() {
            if lines[i].starts_with("# AgentShell-Job-Start:") {
                if let Some(job_id) = lines[i].strip_prefix("# AgentShell-Job-Start:") {
                    let job_id = job_id.trim();

                    // Parse job metadata from comments
                    let mut job_name = String::new();
                    let mut enabled = true;

                    i += 1;
                    while i < lines.len() && !lines[i].starts_with("# AgentShell-Job-End") {
                        if lines[i].starts_with("# Name:") {
                            job_name = lines[i]
                                .strip_prefix("# Name:")
                                .unwrap_or("")
                                .trim()
                                .to_string();
                        } else if lines[i].starts_with("# Enabled:") {
                            enabled = lines[i].strip_prefix("# Enabled:").unwrap_or("true").trim()
                                == "true";
                        } else if !lines[i].trim().is_empty() {
                            // This could be the actual cron line (active or commented out)
                            let line = if lines[i].starts_with("# ") && enabled == false {
                                // Disabled job - remove the comment prefix
                                lines[i].strip_prefix("# ").unwrap_or(lines[i])
                            } else if !lines[i].starts_with("#") {
                                // Active job
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
                                    schedule,
                                    command,
                                    enabled,
                                    last_run: None,
                                    next_run: self
                                        .calculate_next_run(&parts[0..5].join(" "))
                                        .unwrap_or(None),
                                    output: None,
                                    created_at: Some(Utc::now()),
                                    updated_at: Some(Utc::now()),
                                    environment: None,
                                    log_output: None,
                                    email_to: None,
                                    tmux_session: None,
                                };

                                jobs.insert(job_id.to_string(), job);
                            }
                        }
                        i += 1;
                    }
                }
            }
            i += 1;
        }

        info!("Loaded {} cron jobs from crontab", jobs.len());
        Ok(())
    }

    async fn add_to_crontab(&self, job: &CronJob) -> Result<()> {
        // Get current crontab
        let output = Command::new("crontab").arg("-l").output()?;

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
        let output = Command::new("crontab").arg("-l").output()?;

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
        use std::io::Write;
        use std::process::Stdio;

        let mut child = Command::new("crontab")
            .arg("-")
            .stdin(Stdio::piped())
            .spawn()?;

        if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(content.as_bytes())?;
            stdin.flush()?;
        }

        let status = child.wait()?;
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
