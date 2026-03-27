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

        WebSocketMessage::CopyFiles { source_paths, destination_path } => {
            if let Err(e) = validate_path(&destination_path) {
                let response = ServerMessage::FilesCopied {
                    success: false,
                    error: Some(e),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let dest = Path::new(&destination_path);
            if !dest.is_dir() {
                let response = ServerMessage::FilesCopied {
                    success: false,
                    error: Some("Destination is not a directory".to_string()),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let mut errors: Vec<String> = Vec::new();
            for src in &source_paths {
                if let Err(e) = validate_path(src) {
                    errors.push(format!("{}: {}", src, e));
                    continue;
                }
                let src_path = Path::new(src);
                if !src_path.exists() {
                    errors.push(format!("{}: not found", src));
                    continue;
                }
                let file_name = match src_path.file_name() {
                    Some(n) => n,
                    None => { errors.push(format!("{}: invalid path", src)); continue; }
                };
                let target = dest.join(file_name);
                if let Err(e) = copy_recursive(src_path, &target) {
                    errors.push(format!("{}: {}", src, e));
                }
            }

            let success = errors.is_empty();
            let error = if errors.is_empty() { None } else { Some(errors.join("; ")) };
            let response = ServerMessage::FilesCopied { success, error };
            send_message(tx, response).await?;
        }

        WebSocketMessage::MoveFiles { source_paths, destination_path } => {
            if let Err(e) = validate_path(&destination_path) {
                let response = ServerMessage::FilesMoved {
                    success: false,
                    error: Some(e),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let dest = Path::new(&destination_path);
            if !dest.is_dir() {
                let response = ServerMessage::FilesMoved {
                    success: false,
                    error: Some("Destination is not a directory".to_string()),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let mut errors: Vec<String> = Vec::new();
            for src in &source_paths {
                if let Err(e) = validate_path(src) {
                    errors.push(format!("{}: {}", src, e));
                    continue;
                }
                let src_path = Path::new(src);
                if !src_path.exists() {
                    errors.push(format!("{}: not found", src));
                    continue;
                }
                let file_name = match src_path.file_name() {
                    Some(n) => n,
                    None => { errors.push(format!("{}: invalid path", src)); continue; }
                };
                let target = dest.join(file_name);
                if let Err(e) = std::fs::rename(src_path, &target) {
                    // rename fails across filesystems — fall back to copy + delete
                    if let Err(e2) = copy_recursive(src_path, &target)
                        .and_then(|_| if src_path.is_dir() {
                            std::fs::remove_dir_all(src_path)
                        } else {
                            std::fs::remove_file(src_path)
                        })
                    {
                        errors.push(format!("{}: rename: {}, copy-fallback: {}", src, e, e2));
                    }
                }
            }

            let success = errors.is_empty();
            let error = if errors.is_empty() { None } else { Some(errors.join("; ")) };
            let response = ServerMessage::FilesMoved { success, error };
            send_message(tx, response).await?;
        }

        WebSocketMessage::ReadBinaryFile { path } => {
            if let Err(e) = validate_path(&path) {
                let response = ServerMessage::BinaryFileContent {
                    path,
                    content_base64: String::new(),
                    mime_type: "application/octet-stream".to_string(),
                    error: Some(e),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            let file_path = Path::new(&path);
            if !file_path.exists() || !file_path.is_file() {
                let response = ServerMessage::BinaryFileContent {
                    path,
                    content_base64: String::new(),
                    mime_type: "application/octet-stream".to_string(),
                    error: Some("File not found".to_string()),
                };
                send_message(tx, response).await?;
                return Ok(());
            }

            match std::fs::read(file_path) {
                Ok(bytes) => {
                    use base64::Engine;
                    let content_base64 =
                        base64::engine::general_purpose::STANDARD.encode(&bytes);
                    let mime_type = detect_mime_type(file_path);
                    let response = ServerMessage::BinaryFileContent {
                        path,
                        content_base64,
                        mime_type: mime_type.to_string(),
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    error!("Failed to read file {}: {}", path, e);
                    let response = ServerMessage::BinaryFileContent {
                        path,
                        content_base64: String::new(),
                        mime_type: "application/octet-stream".to_string(),
                        error: Some(format!("Failed to read file: {}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        _ => {}
    }

    Ok(())
}

fn copy_recursive(src: &Path, dest: &Path) -> std::io::Result<()> {
    if src.is_dir() {
        std::fs::create_dir_all(dest)?;
        for entry in std::fs::read_dir(src)? {
            let entry = entry?;
            let child_dest = dest.join(entry.file_name());
            copy_recursive(&entry.path(), &child_dest)?;
        }
        Ok(())
    } else {
        std::fs::copy(src, dest)?;
        Ok(())
    }
}

fn detect_mime_type(path: &Path) -> &'static str {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "svg" => "image/svg+xml",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "m4a" | "aac" => "audio/aac",
        "flac" => "audio/flac",
        "html" | "htm" => "text/html",
        "md" => "text/markdown",
        "json" => "application/json",
        "xml" => "application/xml",
        "yaml" | "yml" => "text/yaml",
        "toml" => "text/toml",
        "txt" | "log" | "csv" => "text/plain",
        "rs" | "py" | "js" | "ts" | "go" | "java" | "kt" | "c" | "cpp" | "h"
        | "sh" | "bash" | "dart" | "rb" | "php" | "swift" | "css" | "scss"
        | "sql" | "jsx" | "tsx" => "text/plain",
        _ => "application/octet-stream",
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;
    use crate::websocket::types::BroadcastMessage;
    use crate::types::WebSocketMessage;
    use tempfile::TempDir;

    fn make_tx() -> (
        mpsc::UnboundedSender<BroadcastMessage>,
        mpsc::UnboundedReceiver<BroadcastMessage>,
    ) {
        mpsc::unbounded_channel()
    }

    #[test]
    fn test_validate_path_absolute() {
        assert!(validate_path("/home/user/file.txt").is_ok());
    }

    #[test]
    fn test_validate_path_relative_fails() {
        assert!(validate_path("relative/path").is_err());
    }

    #[test]
    fn test_validate_path_traversal_fails() {
        assert!(validate_path("/home/../etc/passwd").is_err());
    }

    #[tokio::test]
    async fn test_list_files_existing_dir() {
        let dir = TempDir::new().unwrap();
        std::fs::write(dir.path().join("test.txt"), "content").unwrap();
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::ListFiles {
            path: dir.path().to_string_lossy().to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("test.txt") || json.contains("entries"));
    }

    #[tokio::test]
    async fn test_list_files_nonexistent_dir() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::ListFiles {
            path: "/nonexistent/directory/xyz".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok()); // Error response
    }

    #[tokio::test]
    async fn test_get_session_cwd_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::GetSessionCwd {
            session_name: "nonexistent-session-xyz".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_get_session_cwd_existing() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::GetSessionCwd {
            session_name: "AgentShell".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_delete_nonexistent_file() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::DeleteFiles {
            paths: vec!["/tmp/nonexistent_file_xyz_abc.txt".to_string()],
            recursive: false,
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false") || json.contains("not found"));
    }

    #[tokio::test]
    async fn test_delete_file_path_traversal_blocked() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::DeleteFiles {
            paths: vec!["/tmp/../etc/passwd".to_string()],
            recursive: false,
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("traversal") || json.contains("false"));
    }

    #[tokio::test]
    async fn test_delete_file_relative_path_blocked() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::DeleteFiles {
            paths: vec!["relative/path.txt".to_string()],
            recursive: false,
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false") || json.contains("absolute"));
    }

    #[tokio::test]
    async fn test_delete_existing_file() {
        let dir = TempDir::new().unwrap();
        let file_path = dir.path().join("delete_me.txt");
        std::fs::write(&file_path, "data").unwrap();
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::DeleteFiles {
            paths: vec![file_path.to_string_lossy().to_string()],
            recursive: false,
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("true"));
        assert!(!file_path.exists());
    }

    #[tokio::test]
    async fn test_rename_relative_path_blocked() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::RenameFile {
            path: "relative/path.txt".to_string(),
            new_name: "new.txt".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false"));
    }

    #[tokio::test]
    async fn test_rename_invalid_new_name() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::RenameFile {
            path: "/tmp/somefile.txt".to_string(),
            new_name: "../../etc/passwd".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("false") || json.contains("Invalid"));
    }

    #[tokio::test]
    async fn test_rename_existing_file() {
        let dir = TempDir::new().unwrap();
        let file_path = dir.path().join("old.txt");
        std::fs::write(&file_path, "data").unwrap();
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::RenameFile {
            path: file_path.to_string_lossy().to_string(),
            new_name: "new.txt".to_string(),
        }, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("true"));
    }

    #[tokio::test]
    async fn test_unknown_message_no_op() {
        let (tx, _rx) = make_tx();
        let result = handle(WebSocketMessage::Ping, &tx).await;
        assert!(result.is_ok());
    }
}
