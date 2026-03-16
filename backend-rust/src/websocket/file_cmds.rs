use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

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
