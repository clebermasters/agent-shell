use std::path::Path;

use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

fn validate_path(path: &str) -> Result<(), String> {
    if !path.starts_with('/') {
        return Err("Path must be absolute".to_string());
    }
    if path.contains("..") {
        return Err("Path traversal not allowed".to_string());
    }
    Ok(())
}

pub(crate) async fn handle(
    msg: WebSocketMessage,
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::ListFiles { path } => {
            match list_files(&path) {
                Ok(entries) => {
                    let response = ServerMessage::FilesList { path, entries };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to list files at {}: {}", path, e);
                    let response = ServerMessage::Error {
                        message: format!("Failed to list files: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::GetSessionCwd { session_name } => {
            match crate::tmux::get_session_path(&session_name) {
                Some(path) => {
                    let response = ServerMessage::SessionCwd {
                        session_name,
                        cwd: path.to_string_lossy().to_string(),
                    };
                    send_message(tx, response).await?;
                }
                None => {
                    let response = ServerMessage::Error {
                        message: format!("Could not get cwd for session '{}'", session_name),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::DeleteFiles { paths, recursive } => {
            let mut errors: Vec<String> = Vec::new();

            for path in &paths {
                if let Err(e) = validate_path(path) {
                    errors.push(format!("{}: {}", path, e));
                    continue;
                }

                let p = Path::new(path);
                if !p.exists() {
                    errors.push(format!("{}: not found", path));
                    continue;
                }

                let result = if p.is_dir() {
                    if recursive {
                        std::fs::remove_dir_all(p)
                    } else {
                        std::fs::remove_dir(p)
                    }
                } else {
                    std::fs::remove_file(p)
                };

                if let Err(e) = result {
                    errors.push(format!("{}: {}", path, e));
                }
            }

            let success = errors.is_empty();
            let error = if errors.is_empty() {
                None
            } else {
                Some(errors.join("; "))
            };

            let response = ServerMessage::FilesDeleted {
                success,
                paths: paths.clone(),
                error,
            };
            send_message(tx, response).await?;
        }

        WebSocketMessage::RenameFile { path, new_name } => {
            if let Err(e) = validate_path(&path) {
                let response = ServerMessage::FileRenamed {
                    success: false,
                    error: Some(e),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            if new_name.contains('/') || new_name.contains("..") {
                let response = ServerMessage::FileRenamed {
                    success: false,
                    error: Some("Invalid file name".to_string()),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let p = Path::new(&path);
            let new_path = match p.parent() {
                Some(parent) => parent.join(&new_name),
                None => {
                    let response = ServerMessage::FileRenamed {
                        success: false,
                        error: Some("Cannot determine parent directory".to_string()),
                    };
                    send_message(tx, response).await?;
                    return Ok(());
                }
            };

            match std::fs::rename(&path, &new_path) {
                Ok(()) => {
                    let response = ServerMessage::FileRenamed {
                        success: true,
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to rename {} to {}: {}", path, new_name, e);
                    let response = ServerMessage::FileRenamed {
                        success: false,
                        error: Some(format!("Rename failed: {}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        _ => {}
    }

    Ok(())
}

fn list_files(path: &str) -> anyhow::Result<Vec<FileEntry>> {
    let mut entries: Vec<FileEntry> = std::fs::read_dir(path)?
        .filter_map(|res| res.ok())
        .map(|entry| {
            let metadata = entry.metadata();
            let is_directory = metadata.as_ref().map(|m| m.is_dir()).unwrap_or(false);
            let size = metadata.as_ref().map(|m| if m.is_file() { m.len() } else { 0 }).unwrap_or(0);
            let modified = metadata
                .as_ref()
                .ok()
                .and_then(|m| m.modified().ok())
                .map(|t| {
                    let dt: chrono::DateTime<chrono::Utc> = t.into();
                    dt.to_rfc3339()
                });

            FileEntry {
                name: entry.file_name().to_string_lossy().to_string(),
                path: entry.path().to_string_lossy().to_string(),
                is_directory,
                size,
                modified,
            }
        })
        .collect();

    // Sort: directories first, then files; both alphabetically
    entries.sort_by(|a, b| {
        match (a.is_directory, b.is_directory) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
        }
    });

    Ok(entries)
}
