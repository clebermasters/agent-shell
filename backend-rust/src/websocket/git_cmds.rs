use std::collections::HashMap;
use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

/// Resolve working directory: use explicit `path` if provided, otherwise
/// look up the tmux session's current pane path.
fn resolve_cwd(session_name: &Option<String>, path: &Option<String>) -> Result<String, String> {
    if let Some(p) = path {
        if !p.is_empty() {
            return Ok(p.clone());
        }
    }
    if let Some(name) = session_name {
        if let Some(p) = crate::tmux::get_session_path(name) {
            return Ok(p.to_string_lossy().to_string());
        }
        return Err(format!("Could not get cwd for session '{}'", name));
    }
    Err("No session name or path provided".to_string())
}

/// Check if a directory is a git repository.
fn is_git_repo(cwd: &str) -> bool {
    std::process::Command::new("git")
        .args(["rev-parse", "--is-inside-work-tree"])
        .current_dir(cwd)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

pub(crate) async fn handle(
    msg: WebSocketMessage,
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::GitStatus { session_name, path } => {
            handle_git_status(tx, session_name, path).await?;
        }
        WebSocketMessage::GitDiff {
            session_name,
            path,
            file_path,
            staged,
        } => {
            handle_git_diff(tx, session_name, path, file_path, staged).await?;
        }
        WebSocketMessage::GitLog {
            session_name,
            path,
            limit,
            offset,
        } => {
            handle_git_log(tx, session_name, path, limit, offset).await?;
        }
        WebSocketMessage::GitBranches { session_name, path } => {
            handle_git_branches(tx, session_name, path).await?;
        }
        WebSocketMessage::GitCheckout {
            session_name,
            path,
            branch,
        } => {
            handle_git_checkout(tx, session_name, path, branch).await?;
        }
        WebSocketMessage::GitCreateBranch {
            session_name,
            path,
            branch,
            start_point,
        } => {
            handle_git_create_branch(tx, session_name, path, branch, start_point).await?;
        }
        WebSocketMessage::GitDeleteBranch {
            session_name,
            path,
            branch,
        } => {
            handle_git_delete_branch(tx, session_name, path, branch).await?;
        }
        WebSocketMessage::GitStage {
            session_name,
            path,
            files,
        } => {
            handle_git_stage(tx, session_name, path, files).await?;
        }
        WebSocketMessage::GitUnstage {
            session_name,
            path,
            files,
        } => {
            handle_git_unstage(tx, session_name, path, files).await?;
        }
        WebSocketMessage::GitCommit {
            session_name,
            path,
            message,
        } => {
            handle_git_commit(tx, session_name, path, message).await?;
        }
        WebSocketMessage::GitPush {
            session_name,
            path,
            remote,
            branch,
        } => {
            handle_git_push(tx, session_name, path, remote, branch).await?;
        }
        WebSocketMessage::GitPull { session_name, path } => {
            handle_git_pull(tx, session_name, path).await?;
        }
        WebSocketMessage::GitStash {
            session_name,
            path,
            action,
        } => {
            handle_git_stash(tx, session_name, path, action).await?;
        }
        WebSocketMessage::GitCommitFiles {
            session_name,
            path,
            commit_hash,
        } => {
            handle_git_commit_files(tx, session_name, path, commit_hash).await?;
        }
        WebSocketMessage::GitCommitDiff {
            session_name,
            path,
            commit_hash,
            file_path,
        } => {
            handle_git_commit_diff(tx, session_name, path, commit_hash, file_path).await?;
        }
        WebSocketMessage::GitSearch {
            session_name,
            path,
            query,
            author,
            since,
            limit,
        } => {
            handle_git_search(tx, session_name, path, query, author, since, limit).await?;
        }
        WebSocketMessage::GitFileHistory {
            session_name,
            path,
            file_path,
            limit,
            offset,
        } => {
            handle_git_file_history(tx, session_name, path, file_path, limit, offset).await?;
        }
        WebSocketMessage::GitCherryPick {
            session_name,
            path,
            commit_hash,
        } => {
            handle_git_cherry_pick(tx, session_name, path, commit_hash).await?;
        }
        WebSocketMessage::GitRevert {
            session_name,
            path,
            commit_hash,
        } => {
            handle_git_revert(tx, session_name, path, commit_hash).await?;
        }
        WebSocketMessage::GitMerge {
            session_name,
            path,
            branch,
        } => {
            handle_git_merge(tx, session_name, path, branch).await?;
        }
        WebSocketMessage::GitBlame {
            session_name,
            path,
            file_path,
        } => {
            handle_git_blame(tx, session_name, path, file_path).await?;
        }
        WebSocketMessage::GitCompare {
            session_name,
            path,
            base_branch,
            compare_branch,
        } => {
            handle_git_compare(tx, session_name, path, base_branch, compare_branch).await?;
        }
        WebSocketMessage::GitRepoInfo { session_name, path } => {
            handle_git_repo_info(tx, session_name, path).await?;
        }
        WebSocketMessage::GitAmend {
            session_name,
            path,
            message,
        } => {
            handle_git_amend(tx, session_name, path, message).await?;
        }
        WebSocketMessage::GitListTags { session_name, path } => {
            handle_git_list_tags(tx, session_name, path).await?;
        }
        WebSocketMessage::GitCreateTag {
            session_name,
            path,
            name,
            commit_hash,
            message,
        } => {
            handle_git_create_tag(tx, session_name, path, name, commit_hash, message).await?;
        }
        WebSocketMessage::GitDeleteTag {
            session_name,
            path,
            name,
        } => {
            handle_git_delete_tag(tx, session_name, path, name).await?;
        }
        WebSocketMessage::GitResolveConflict {
            session_name,
            path,
            file_path,
            resolution,
        } => {
            handle_git_resolve_conflict(tx, session_name, path, file_path, resolution).await?;
        }
        _ => {}
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git status
// ---------------------------------------------------------------------------
async fn handle_git_status(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    if !is_git_repo(&cwd) {
        send_message(
            tx,
            ServerMessage::Error {
                message: format!("Not a git repository: {}", cwd),
            },
        )
        .await?;
        return Ok(());
    }

    // Get branch + upstream + ahead/behind via porcelain v2
    let output = tokio::process::Command::new("git")
        .args(["status", "--porcelain=v2", "--branch", "-u"])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let mut branch = String::new();
            let mut upstream = None;
            let mut ahead = 0i32;
            let mut behind = 0i32;
            let mut staged = Vec::new();
            let mut modified = Vec::new();
            let mut untracked = Vec::new();
            let mut conflicts = Vec::new();

            for line in stdout.lines() {
                if line.starts_with("# branch.head ") {
                    branch = line
                        .strip_prefix("# branch.head ")
                        .unwrap_or("")
                        .to_string();
                } else if line.starts_with("# branch.upstream ") {
                    upstream = Some(
                        line.strip_prefix("# branch.upstream ")
                            .unwrap_or("")
                            .to_string(),
                    );
                } else if line.starts_with("# branch.ab ") {
                    let ab = line.strip_prefix("# branch.ab ").unwrap_or("");
                    for part in ab.split_whitespace() {
                        if let Some(n) = part.strip_prefix('+') {
                            ahead = n.parse().unwrap_or(0);
                        } else if let Some(n) = part.strip_prefix('-') {
                            behind = n.parse().unwrap_or(0);
                        }
                    }
                } else if line.starts_with("1 ") || line.starts_with("2 ") {
                    // Changed entry: "1 XY sub mH mI mW hH hP path"
                    // or rename:      "2 XY sub mH mI mW hH hP X\tscore\tpath\torigPath"
                    let parts: Vec<&str> = line.splitn(9, ' ').collect();
                    if parts.len() >= 9 {
                        let xy = parts[1];
                        let file_path = if line.starts_with("2 ") {
                            // Rename entry has tab-separated paths
                            parts[8].split('\t').last().unwrap_or(parts[8])
                        } else {
                            parts[8]
                        };
                        let x = xy.chars().next().unwrap_or('.');
                        let y = xy.chars().nth(1).unwrap_or('.');

                        if x != '.' && x != '?' {
                            staged.push(GitFileChange {
                                path: file_path.to_string(),
                                status: x.to_string(),
                                additions: None,
                                deletions: None,
                            });
                        }
                        if y != '.' && y != '?' {
                            modified.push(GitFileChange {
                                path: file_path.to_string(),
                                status: y.to_string(),
                                additions: None,
                                deletions: None,
                            });
                        }
                    }
                } else if line.starts_with("? ") {
                    // Untracked
                    let file_path = line.strip_prefix("? ").unwrap_or("");
                    untracked.push(file_path.to_string());
                } else if line.starts_with("u ") {
                    // Unmerged/conflict
                    let parts: Vec<&str> = line.splitn(11, ' ').collect();
                    if let Some(p) = parts.last() {
                        conflicts.push(p.to_string());
                    }
                }
            }

            // Enrich with numstat for staged files
            if !staged.is_empty() {
                enrich_with_numstat(&cwd, &mut staged, true).await;
            }
            if !modified.is_empty() {
                enrich_with_numstat(&cwd, &mut modified, false).await;
            }

            let response = ServerMessage::GitStatusResult {
                branch,
                upstream,
                ahead,
                behind,
                staged,
                modified,
                untracked,
                conflicts,
            };
            send_message(tx, response).await?;
        }
        Err(e) => {
            error!("git status failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git status failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

/// Enrich file changes with addition/deletion counts from numstat.
async fn enrich_with_numstat(cwd: &str, changes: &mut [GitFileChange], staged: bool) {
    let args = if staged {
        vec!["diff", "--cached", "--numstat"]
    } else {
        vec!["diff", "--numstat"]
    };
    if let Ok(out) = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(cwd)
        .output()
        .await
    {
        let stdout = String::from_utf8_lossy(&out.stdout);
        for line in stdout.lines() {
            let parts: Vec<&str> = line.split('\t').collect();
            if parts.len() >= 3 {
                let adds = parts[0].parse::<u32>().ok();
                let dels = parts[1].parse::<u32>().ok();
                let file = parts[2];
                if let Some(c) = changes.iter_mut().find(|c| c.path == file) {
                    c.additions = adds;
                    c.deletions = dels;
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// git diff
// ---------------------------------------------------------------------------
async fn handle_git_diff(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    file_path: Option<String>,
    staged: Option<bool>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let mut args = vec!["diff".to_string()];
    if staged.unwrap_or(false) {
        args.push("--cached".to_string());
    }
    args.push("--".to_string());
    let target_file = file_path.clone().unwrap_or_default();
    if !target_file.is_empty() {
        args.push(target_file.clone());
    }

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let diff = String::from_utf8_lossy(&out.stdout).to_string();
            let mut additions = 0u32;
            let mut deletions = 0u32;
            for line in diff.lines() {
                if line.starts_with('+') && !line.starts_with("+++") {
                    additions += 1;
                } else if line.starts_with('-') && !line.starts_with("---") {
                    deletions += 1;
                }
            }
            send_message(
                tx,
                ServerMessage::GitDiffResult {
                    file_path: target_file,
                    diff,
                    additions,
                    deletions,
                },
            )
            .await?;
        }
        Err(e) => {
            error!("git diff failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git diff failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git log
// ---------------------------------------------------------------------------
async fn handle_git_log(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    limit: Option<usize>,
    offset: Option<usize>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let limit_val = limit.unwrap_or(50);
    let offset_val = offset.unwrap_or(0);
    // Fetch one extra to know if there are more
    let fetch_count = limit_val + 1;

    let output = tokio::process::Command::new("git")
        .args([
            "log",
            "--all",
            "--parents",
            &format!("--format=%H%x00%h%x00%s%x00%an%x00%aI%x00%P%x00%D"),
            &format!("--skip={}", offset_val),
            &format!("-n{}", fetch_count),
        ])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let mut commits: Vec<GitCommitInfo> = Vec::new();

            for line in stdout.lines() {
                if line.is_empty() {
                    continue;
                }
                let parts: Vec<&str> = line.split('\0').collect();
                if parts.len() >= 7 {
                    let parents: Vec<String> =
                        parts[5].split_whitespace().map(|s| s.to_string()).collect();
                    let refs: Vec<String> = if parts[6].is_empty() {
                        vec![]
                    } else {
                        parts[6].split(", ").map(|s| s.to_string()).collect()
                    };
                    commits.push(GitCommitInfo {
                        hash: parts[0].to_string(),
                        short_hash: parts[1].to_string(),
                        message: parts[2].to_string(),
                        author: parts[3].to_string(),
                        date: parts[4].to_string(),
                        parents,
                        refs,
                    });
                }
            }

            let has_more = commits.len() > limit_val;
            commits.truncate(limit_val);

            send_message(tx, ServerMessage::GitLogResult { commits, has_more }).await?;
        }
        Err(e) => {
            error!("git log failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git log failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git branches
// ---------------------------------------------------------------------------
async fn handle_git_branches(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args([
            "branch",
            "-vv",
            "--format=%(HEAD)%00%(refname:short)%00%(upstream:short)%00%(upstream:track,nobracket)%00%(objectname:short) %(subject)",
        ])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let mut branches: Vec<GitBranchInfo> = Vec::new();

            for line in stdout.lines() {
                if line.is_empty() {
                    continue;
                }
                let parts: Vec<&str> = line.split('\0').collect();
                if parts.len() >= 5 {
                    let current = parts[0] == "*";
                    let name = parts[1].to_string();
                    let tracking = if parts[2].is_empty() {
                        None
                    } else {
                        Some(parts[2].to_string())
                    };
                    let track_info = parts[3];
                    let last_commit = if parts[4].is_empty() {
                        None
                    } else {
                        Some(parts[4].to_string())
                    };

                    let (ahead, behind) = parse_track_info(track_info);

                    branches.push(GitBranchInfo {
                        name,
                        current,
                        tracking,
                        ahead,
                        behind,
                        last_commit,
                    });
                }
            }

            send_message(tx, ServerMessage::GitBranchesResult { branches }).await?;
        }
        Err(e) => {
            error!("git branch failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git branch failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

fn parse_track_info(info: &str) -> (Option<i32>, Option<i32>) {
    if info.is_empty() {
        return (None, None);
    }
    let mut ahead = None;
    let mut behind = None;
    for part in info.split(", ") {
        if let Some(n) = part.strip_prefix("ahead ") {
            ahead = n.parse().ok();
        } else if let Some(n) = part.strip_prefix("behind ") {
            behind = n.parse().ok();
        }
    }
    (ahead, behind)
}

// ---------------------------------------------------------------------------
// git checkout
// ---------------------------------------------------------------------------
async fn handle_git_checkout(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    branch: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args(["checkout", &branch])
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "checkout", output).await
}

// ---------------------------------------------------------------------------
// git create-branch
// ---------------------------------------------------------------------------
async fn handle_git_create_branch(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    branch: String,
    start_point: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let mut args = vec!["checkout", "-b"];
    args.push(&branch);
    let sp;
    if let Some(ref s) = start_point {
        sp = s.clone();
        args.push(&sp);
    }

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "create-branch", output).await
}

// ---------------------------------------------------------------------------
// git delete-branch
// ---------------------------------------------------------------------------
async fn handle_git_delete_branch(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    branch: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args(["branch", "-d", &branch])
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "delete-branch", output).await
}

// ---------------------------------------------------------------------------
// git stage
// ---------------------------------------------------------------------------
async fn handle_git_stage(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    files: Vec<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let mut args = vec!["add".to_string(), "--".to_string()];
    args.extend(files);

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "stage", output).await
}

// ---------------------------------------------------------------------------
// git unstage
// ---------------------------------------------------------------------------
async fn handle_git_unstage(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    files: Vec<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let mut args = vec!["reset".to_string(), "HEAD".to_string(), "--".to_string()];
    args.extend(files);

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "unstage", output).await
}

// ---------------------------------------------------------------------------
// git commit
// ---------------------------------------------------------------------------
async fn handle_git_commit(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    message: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args(["commit", "-m", &message])
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "commit", output).await
}

// ---------------------------------------------------------------------------
// git push
// ---------------------------------------------------------------------------
async fn handle_git_push(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    remote: Option<String>,
    branch: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let mut args = vec!["push".to_string()];
    if let Some(r) = remote {
        args.push(r);
        if let Some(b) = branch {
            args.push(b);
        }
    }

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "push", output).await
}

// ---------------------------------------------------------------------------
// git pull
// ---------------------------------------------------------------------------
async fn handle_git_pull(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args(["pull"])
        .current_dir(&cwd)
        .output()
        .await;

    send_operation_result(tx, "pull", output).await
}

// ---------------------------------------------------------------------------
// git stash
// ---------------------------------------------------------------------------
async fn handle_git_stash(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    action: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    match action.as_str() {
        "list" => {
            let output = tokio::process::Command::new("git")
                .args(["stash", "list", "--format=%gd%x00%s%x00%aI"])
                .current_dir(&cwd)
                .output()
                .await;
            match output {
                Ok(out) => {
                    let stdout = String::from_utf8_lossy(&out.stdout);
                    let entries: Vec<GitStashEntry> = stdout
                        .lines()
                        .filter(|l| !l.is_empty())
                        .enumerate()
                        .map(|(i, line)| {
                            let parts: Vec<&str> = line.split('\0').collect();
                            GitStashEntry {
                                index: i as u32,
                                message: parts.get(1).unwrap_or(&"").to_string(),
                                date: parts.get(2).unwrap_or(&"").to_string(),
                            }
                        })
                        .collect();
                    send_message(tx, ServerMessage::GitStashListResult { entries }).await?;
                }
                Err(e) => {
                    send_message(
                        tx,
                        ServerMessage::Error {
                            message: format!("git stash list failed: {}", e),
                        },
                    )
                    .await?;
                }
            }
            Ok(())
        }
        "push" | "pop" | "drop" | "apply" => {
            let args = vec!["stash", &action];
            let output = tokio::process::Command::new("git")
                .args(&args)
                .current_dir(&cwd)
                .output()
                .await;
            send_operation_result(tx, "stash", output).await
        }
        other => {
            send_message(
                tx,
                ServerMessage::GitOperationResult {
                    operation: "stash".to_string(),
                    success: false,
                    message: None,
                    error: Some(format!("Unknown stash action: {}", other)),
                },
            )
            .await?;
            Ok(())
        }
    }
}

// ---------------------------------------------------------------------------
// git search
// ---------------------------------------------------------------------------
async fn handle_git_search(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    query: String,
    author: Option<String>,
    since: Option<String>,
    limit: Option<usize>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let limit_val = limit.unwrap_or(50);
    let mut args = vec![
        "log".to_string(),
        "--all".to_string(),
        format!("--format=%H%x00%h%x00%s%x00%an%x00%aI%x00%P%x00%D"),
        format!("-n{}", limit_val),
        format!("--grep={}", query),
        "-i".to_string(),
    ];
    if let Some(ref a) = author {
        args.push(format!("--author={}", a));
    }
    if let Some(ref s) = since {
        args.push(format!("--since={}", s));
    }

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;
    match output {
        Ok(out) => {
            let commits = parse_log_output(&String::from_utf8_lossy(&out.stdout));
            send_message(tx, ServerMessage::GitSearchResult { query, commits }).await?;
        }
        Err(e) => {
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git search failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git file-history
// ---------------------------------------------------------------------------
async fn handle_git_file_history(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    file_path: String,
    limit: Option<usize>,
    offset: Option<usize>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let limit_val = limit.unwrap_or(50);
    let offset_val = offset.unwrap_or(0);
    let fetch_count = limit_val + 1;

    let output = tokio::process::Command::new("git")
        .args([
            "log",
            &format!("--format=%H%x00%h%x00%s%x00%an%x00%aI%x00%P%x00%D"),
            &format!("--skip={}", offset_val),
            &format!("-n{}", fetch_count),
            "--follow",
            "--",
            &file_path,
        ])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let mut commits = parse_log_output(&String::from_utf8_lossy(&out.stdout));
            let has_more = commits.len() > limit_val;
            commits.truncate(limit_val);
            send_message(
                tx,
                ServerMessage::GitFileHistoryResult {
                    file_path,
                    commits,
                    has_more,
                },
            )
            .await?;
        }
        Err(e) => {
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git file-history failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git cherry-pick
// ---------------------------------------------------------------------------
async fn handle_git_cherry_pick(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    commit_hash: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["cherry-pick", &commit_hash])
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "cherry-pick", output).await
}

// ---------------------------------------------------------------------------
// git revert
// ---------------------------------------------------------------------------
async fn handle_git_revert(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    commit_hash: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["revert", "--no-edit", &commit_hash])
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "revert", output).await
}

// ---------------------------------------------------------------------------
// git merge
// ---------------------------------------------------------------------------
async fn handle_git_merge(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    branch: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["merge", &branch])
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "merge", output).await
}

// ---------------------------------------------------------------------------
// git blame
// ---------------------------------------------------------------------------
async fn handle_git_blame(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    file_path: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["blame", "--porcelain", &file_path])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let lines = parse_blame_porcelain(&stdout);
            send_message(tx, ServerMessage::GitBlameResult { file_path, lines }).await?;
        }
        Err(e) => {
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git blame failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

fn parse_blame_porcelain(output: &str) -> Vec<GitBlameLine> {
    let mut lines = Vec::new();
    let mut current_hash = String::new();
    let mut current_author = String::new();
    let mut current_date = String::new();
    let mut current_line_num = 0u32;

    for line in output.lines() {
        if line.len() >= 40 && line.chars().take(40).all(|c| c.is_ascii_hexdigit()) {
            let parts: Vec<&str> = line.split_whitespace().collect();
            current_hash = parts.first().unwrap_or(&"").to_string();
            current_line_num = parts.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
        } else if let Some(author) = line.strip_prefix("author ") {
            current_author = author.to_string();
        } else if let Some(time) = line.strip_prefix("author-time ") {
            if let Ok(ts) = time.parse::<i64>() {
                current_date = chrono::DateTime::from_timestamp(ts, 0)
                    .map(|dt| dt.to_rfc3339())
                    .unwrap_or_default();
            }
        } else if let Some(content) = line.strip_prefix('\t') {
            lines.push(GitBlameLine {
                line_number: current_line_num,
                hash: current_hash.chars().take(8).collect(),
                author: current_author.clone(),
                date: current_date.clone(),
                content: content.to_string(),
            });
        }
    }
    lines
}

// ---------------------------------------------------------------------------
// git compare branches
// ---------------------------------------------------------------------------
async fn handle_git_compare(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    base_branch: String,
    compare_branch: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    // Count ahead/behind
    let rev_list = tokio::process::Command::new("git")
        .args([
            "rev-list",
            "--left-right",
            "--count",
            &format!("{}...{}", base_branch, compare_branch),
        ])
        .current_dir(&cwd)
        .output()
        .await;

    let (ahead, behind) = match rev_list {
        Ok(out) => {
            let s = String::from_utf8_lossy(&out.stdout);
            let parts: Vec<&str> = s.trim().split('\t').collect();
            let b = parts
                .first()
                .and_then(|s| s.parse::<i32>().ok())
                .unwrap_or(0);
            let a = parts
                .get(1)
                .and_then(|s| s.parse::<i32>().ok())
                .unwrap_or(0);
            (a, b)
        }
        Err(_) => (0, 0),
    };

    // Get commits in compare that aren't in base
    let log_output = tokio::process::Command::new("git")
        .args([
            "log",
            &format!("--format=%H%x00%h%x00%s%x00%an%x00%aI%x00%P%x00%D"),
            "-n50",
            &format!("{}..{}", base_branch, compare_branch),
        ])
        .current_dir(&cwd)
        .output()
        .await;

    let commits = match log_output {
        Ok(out) => parse_log_output(&String::from_utf8_lossy(&out.stdout)),
        Err(_) => vec![],
    };

    send_message(
        tx,
        ServerMessage::GitCompareResult {
            base_branch,
            compare_branch,
            ahead,
            behind,
            commits,
        },
    )
    .await?;
    Ok(())
}

// ---------------------------------------------------------------------------
// git repo-info
// ---------------------------------------------------------------------------
async fn handle_git_repo_info(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    // Remotes
    let remotes_out = tokio::process::Command::new("git")
        .args(["remote", "-v"])
        .current_dir(&cwd)
        .output()
        .await;
    let remotes = match remotes_out {
        Ok(out) => String::from_utf8_lossy(&out.stdout)
            .lines()
            .map(|line| {
                let parts: Vec<&str> = line.split_whitespace().collect();
                GitRemoteInfo {
                    name: parts.first().unwrap_or(&"").to_string(),
                    url: parts.get(1).unwrap_or(&"").to_string(),
                    remote_type: parts
                        .get(2)
                        .unwrap_or(&"")
                        .trim_matches(|c| c == '(' || c == ')')
                        .to_string(),
                }
            })
            .collect(),
        Err(_) => vec![],
    };

    // Total commits
    let count_out = tokio::process::Command::new("git")
        .args(["rev-list", "--count", "HEAD"])
        .current_dir(&cwd)
        .output()
        .await;
    let total_commits = count_out
        .ok()
        .map(|o| {
            String::from_utf8_lossy(&o.stdout)
                .trim()
                .parse::<u64>()
                .unwrap_or(0)
        })
        .unwrap_or(0);

    // Current branch
    let branch_out = tokio::process::Command::new("git")
        .args(["branch", "--show-current"])
        .current_dir(&cwd)
        .output()
        .await;
    let current_branch = branch_out
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default();

    // Branch count
    let bc_out = tokio::process::Command::new("git")
        .args(["branch", "--list"])
        .current_dir(&cwd)
        .output()
        .await;
    let branch_count = bc_out
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).lines().count() as u32)
        .unwrap_or(0);

    // Tag count
    let tc_out = tokio::process::Command::new("git")
        .args(["tag", "--list"])
        .current_dir(&cwd)
        .output()
        .await;
    let tag_count = tc_out
        .ok()
        .map(|o| {
            String::from_utf8_lossy(&o.stdout)
                .lines()
                .filter(|l| !l.is_empty())
                .count() as u32
        })
        .unwrap_or(0);

    // Repo size
    let size_out = tokio::process::Command::new("du")
        .args(["-sh", ".git"])
        .current_dir(&cwd)
        .output()
        .await;
    let repo_size = size_out
        .ok()
        .map(|o| {
            String::from_utf8_lossy(&o.stdout)
                .split('\t')
                .next()
                .unwrap_or("?")
                .trim()
                .to_string()
        })
        .unwrap_or_else(|| "?".to_string());

    // Last commit
    let last_out = tokio::process::Command::new("git")
        .args([
            "log",
            "-1",
            "--format=%H%x00%h%x00%s%x00%an%x00%aI%x00%P%x00%D",
        ])
        .current_dir(&cwd)
        .output()
        .await;
    let last_commit = last_out.ok().and_then(|o| {
        parse_log_output(&String::from_utf8_lossy(&o.stdout))
            .into_iter()
            .next()
    });

    send_message(
        tx,
        ServerMessage::GitRepoInfoResult {
            remotes,
            total_commits,
            current_branch,
            branch_count,
            tag_count,
            repo_size,
            last_commit,
        },
    )
    .await?;
    Ok(())
}

// ---------------------------------------------------------------------------
// git amend
// ---------------------------------------------------------------------------
async fn handle_git_amend(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    message: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let mut args = vec!["commit", "--amend"];
    let msg;
    if let Some(ref m) = message {
        msg = m.clone();
        args.push("-m");
        args.push(&msg);
    } else {
        args.push("--no-edit");
    }
    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "amend", output).await
}

// ---------------------------------------------------------------------------
// git tags
// ---------------------------------------------------------------------------
async fn handle_git_list_tags(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["tag", "-l", "--format=%(objectname:short)%00%(refname:short)%00%(contents:subject)%00%(creatordate:iso-strict)%00%(objecttype)"])
        .current_dir(&cwd).output().await;

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let tags: Vec<GitTagInfo> = stdout
                .lines()
                .filter(|l| !l.is_empty())
                .map(|line| {
                    let parts: Vec<&str> = line.split('\0').collect();
                    GitTagInfo {
                        hash: parts.first().unwrap_or(&"").to_string(),
                        name: parts.get(1).unwrap_or(&"").to_string(),
                        message: parts
                            .get(2)
                            .filter(|s| !s.is_empty())
                            .map(|s| s.to_string()),
                        date: parts
                            .get(3)
                            .filter(|s| !s.is_empty())
                            .map(|s| s.to_string()),
                        is_annotated: parts.get(4).map(|s| *s == "tag").unwrap_or(false),
                    }
                })
                .collect();
            send_message(tx, ServerMessage::GitTagsResult { tags }).await?;
        }
        Err(e) => {
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git tag list failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

async fn handle_git_create_tag(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    name: String,
    commit_hash: Option<String>,
    message: Option<String>,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let mut args = vec!["tag".to_string()];
    if let Some(ref m) = message {
        args.push("-a".to_string());
        args.push(name.clone());
        args.push("-m".to_string());
        args.push(m.clone());
    } else {
        args.push(name.clone());
    }
    if let Some(ref h) = commit_hash {
        args.push(h.clone());
    }

    let output = tokio::process::Command::new("git")
        .args(&args)
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "create-tag", output).await
}

async fn handle_git_delete_tag(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    name: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let output = tokio::process::Command::new("git")
        .args(["tag", "-d", &name])
        .current_dir(&cwd)
        .output()
        .await;
    send_operation_result(tx, "delete-tag", output).await
}

// ---------------------------------------------------------------------------
// git resolve-conflict
// ---------------------------------------------------------------------------
async fn handle_git_resolve_conflict(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    file_path: String,
    resolution: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };
    let strategy = match resolution.as_str() {
        "ours" => "--ours",
        "theirs" => "--theirs",
        _ => {
            send_message(
                tx,
                ServerMessage::GitOperationResult {
                    operation: "resolve-conflict".to_string(),
                    success: false,
                    message: None,
                    error: Some(format!(
                        "Unknown resolution: {}. Use 'ours' or 'theirs'",
                        resolution
                    )),
                },
            )
            .await?;
            return Ok(());
        }
    };
    let checkout = tokio::process::Command::new("git")
        .args(["checkout", strategy, "--", &file_path])
        .current_dir(&cwd)
        .output()
        .await;

    if let Ok(ref out) = checkout {
        if out.status.success() {
            // Stage the resolved file
            let _ = tokio::process::Command::new("git")
                .args(["add", "--", &file_path])
                .current_dir(&cwd)
                .output()
                .await;
        }
    }
    send_operation_result(tx, "resolve-conflict", checkout).await
}

// ---------------------------------------------------------------------------
// shared log parser
// ---------------------------------------------------------------------------
fn parse_log_output(stdout: &str) -> Vec<GitCommitInfo> {
    stdout
        .lines()
        .filter(|l| !l.is_empty())
        .filter_map(|line| {
            let parts: Vec<&str> = line.split('\0').collect();
            if parts.len() >= 7 {
                Some(GitCommitInfo {
                    hash: parts[0].to_string(),
                    short_hash: parts[1].to_string(),
                    message: parts[2].to_string(),
                    author: parts[3].to_string(),
                    date: parts[4].to_string(),
                    parents: parts[5].split_whitespace().map(|s| s.to_string()).collect(),
                    refs: if parts[6].is_empty() {
                        vec![]
                    } else {
                        parts[6].split(", ").map(|s| s.to_string()).collect()
                    },
                })
            } else {
                None
            }
        })
        .collect()
}

// ---------------------------------------------------------------------------
// git commit-files (files changed in a specific commit)
// ---------------------------------------------------------------------------
async fn handle_git_commit_files(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    commit_hash: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    // Get file list with status and numstat in one shot
    let output = tokio::process::Command::new("git")
        .args([
            "diff-tree",
            "--no-commit-id",
            "-r",
            "--name-status",
            &commit_hash,
        ])
        .current_dir(&cwd)
        .output()
        .await;

    let numstat_output = tokio::process::Command::new("git")
        .args([
            "diff-tree",
            "--no-commit-id",
            "-r",
            "--numstat",
            &commit_hash,
        ])
        .current_dir(&cwd)
        .output()
        .await;

    match (output, numstat_output) {
        (Ok(status_out), Ok(numstat_out)) => {
            let status_str = String::from_utf8_lossy(&status_out.stdout);
            let numstat_str = String::from_utf8_lossy(&numstat_out.stdout);

            // Parse numstat into a map: file -> (adds, dels)
            let mut stats: std::collections::HashMap<String, (Option<u32>, Option<u32>)> =
                std::collections::HashMap::new();
            for line in numstat_str.lines() {
                let parts: Vec<&str> = line.split('\t').collect();
                if parts.len() >= 3 {
                    let adds = parts[0].parse::<u32>().ok();
                    let dels = parts[1].parse::<u32>().ok();
                    stats.insert(parts[2].to_string(), (adds, dels));
                }
            }

            let mut files = Vec::new();
            for line in status_str.lines() {
                if line.is_empty() {
                    continue;
                }
                let parts: Vec<&str> = line.split('\t').collect();
                if parts.len() >= 2 {
                    let status = parts[0].chars().next().unwrap_or('M').to_string();
                    let file_path = parts[1].to_string();
                    let (additions, deletions) =
                        stats.get(&file_path).copied().unwrap_or((None, None));
                    files.push(GitFileChange {
                        path: file_path,
                        status,
                        additions,
                        deletions,
                    });
                }
            }

            send_message(
                tx,
                ServerMessage::GitCommitFilesResult { commit_hash, files },
            )
            .await?;
        }
        (Err(e), _) | (_, Err(e)) => {
            error!("git diff-tree failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git diff-tree failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// git commit-diff (diff for a specific file in a specific commit)
// ---------------------------------------------------------------------------
async fn handle_git_commit_diff(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    session_name: Option<String>,
    path: Option<String>,
    commit_hash: String,
    file_path: String,
) -> anyhow::Result<()> {
    let cwd = match resolve_cwd(&session_name, &path) {
        Ok(c) => c,
        Err(e) => {
            send_message(tx, ServerMessage::Error { message: e }).await?;
            return Ok(());
        }
    };

    let output = tokio::process::Command::new("git")
        .args([
            "diff",
            &format!("{}^..{}", commit_hash, commit_hash),
            "--",
            &file_path,
        ])
        .current_dir(&cwd)
        .output()
        .await;

    match output {
        Ok(out) => {
            let diff = String::from_utf8_lossy(&out.stdout).to_string();
            let mut additions = 0u32;
            let mut deletions = 0u32;
            for line in diff.lines() {
                if line.starts_with('+') && !line.starts_with("+++") {
                    additions += 1;
                } else if line.starts_with('-') && !line.starts_with("---") {
                    deletions += 1;
                }
            }
            send_message(
                tx,
                ServerMessage::GitDiffResult {
                    file_path,
                    diff,
                    additions,
                    deletions,
                },
            )
            .await?;
        }
        Err(e) => {
            error!("git diff for commit failed: {}", e);
            send_message(
                tx,
                ServerMessage::Error {
                    message: format!("git diff failed: {}", e),
                },
            )
            .await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

async fn send_operation_result(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    operation: &str,
    output: Result<std::process::Output, std::io::Error>,
) -> anyhow::Result<()> {
    match output {
        Ok(out) => {
            let success = out.status.success();
            let stdout = String::from_utf8_lossy(&out.stdout).to_string();
            let stderr = String::from_utf8_lossy(&out.stderr).to_string();
            let message = if stdout.is_empty() {
                None
            } else {
                Some(stdout)
            };
            let error = if success || stderr.is_empty() {
                None
            } else {
                Some(stderr)
            };

            send_message(
                tx,
                ServerMessage::GitOperationResult {
                    operation: operation.to_string(),
                    success,
                    message,
                    error,
                },
            )
            .await?;
        }
        Err(e) => {
            error!("git {} failed: {}", operation, e);
            send_message(
                tx,
                ServerMessage::GitOperationResult {
                    operation: operation.to_string(),
                    success: false,
                    message: None,
                    error: Some(format!("Failed to execute git: {}", e)),
                },
            )
            .await?;
        }
    }
    Ok(())
}
