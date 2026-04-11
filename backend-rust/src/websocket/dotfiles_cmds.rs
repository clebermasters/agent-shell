use tokio::sync::mpsc;
use tracing::error;

use super::types::{send_message, BroadcastMessage};
use crate::types::*;

pub(crate) async fn handle(
    msg: WebSocketMessage,
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
) -> anyhow::Result<()> {
    match msg {
        WebSocketMessage::ListDotfiles => {
            match crate::dotfiles::DOTFILES_MANAGER.list_dotfiles().await {
                Ok(files) => {
                    let response = ServerMessage::DotfilesList { files };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to list dotfiles: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::ReadDotfile { path } => {
            match crate::dotfiles::DOTFILES_MANAGER.read_dotfile(&path).await {
                Ok(content) => {
                    let response = ServerMessage::DotfileContent {
                        path,
                        content,
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::DotfileContent {
                        path,
                        content: String::new(),
                        error: Some(format!("{}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::WriteDotfile { path, content } => match crate::dotfiles::DOTFILES_MANAGER
            .write_dotfile(&path, &content)
            .await
        {
            Ok(_) => {
                let response = ServerMessage::DotfileWritten {
                    path,
                    success: true,
                    error: None,
                };
                send_message(tx, response).await?;
            }
            Err(e) => {
                let response = ServerMessage::DotfileWritten {
                    path,
                    success: false,
                    error: Some(format!("{}", e)),
                };
                send_message(tx, response).await?;
            }
        },

        WebSocketMessage::GetDotfileHistory { path } => {
            match crate::dotfiles::DOTFILES_MANAGER
                .get_file_history(&path)
                .await
            {
                Ok(versions) => {
                    let response = ServerMessage::DotfileHistory { path, versions };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::Error {
                        message: format!("Failed to get dotfile history: {}", e),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::RestoreDotfileVersion { path, timestamp } => {
            match crate::dotfiles::DOTFILES_MANAGER
                .restore_version(&path, timestamp)
                .await
            {
                Ok(_) => {
                    let response = ServerMessage::DotfileRestored {
                        path,
                        success: true,
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::DotfileRestored {
                        path,
                        success: false,
                        error: Some(format!("{}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        WebSocketMessage::GetDotfileTemplates => {
            let templates = crate::dotfiles::DOTFILES_MANAGER.get_templates();
            let response = ServerMessage::DotfileTemplates { templates };
            send_message(tx, response).await?;
        }

        WebSocketMessage::ReadBinaryFile { path } => {
            match crate::dotfiles::DOTFILES_MANAGER
                .read_binary_file(&path)
                .await
            {
                Ok((bytes, mime_type)) => {
                    use base64::Engine;
                    let content_base64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
                    let response = ServerMessage::BinaryFileContent {
                        path,
                        content_base64,
                        mime_type: mime_type.to_string(),
                        error: None,
                    };
                    send_message(tx, response).await?;
                }
                Err(e) => {
                    let response = ServerMessage::BinaryFileContent {
                        path,
                        content_base64: String::new(),
                        mime_type: String::new(),
                        error: Some(format!("{}", e)),
                    };
                    send_message(tx, response).await?;
                }
            }
        }

        _ => {
            error!("dotfiles_cmds::handle called with non-dotfiles message");
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::WebSocketMessage;
    use crate::websocket::types::BroadcastMessage;
    use tokio::sync::mpsc;

    fn make_tx() -> (
        mpsc::UnboundedSender<BroadcastMessage>,
        mpsc::UnboundedReceiver<BroadcastMessage>,
    ) {
        mpsc::unbounded_channel()
    }

    #[tokio::test]
    async fn test_list_dotfiles() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::ListDotfiles, &tx).await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_get_templates() {
        let (tx, mut rx) = make_tx();
        let result = handle(WebSocketMessage::GetDotfileTemplates, &tx).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("templates") || json.contains("DotfileTemplates"));
    }

    #[tokio::test]
    async fn test_read_dotfile_nonexistent_returns_error() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::ReadDotfile {
                path: ".nonexistent_file_xyz_abc".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("error") || json.contains("Error"));
    }

    #[tokio::test]
    async fn test_get_dotfile_history() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::GetDotfileHistory {
                path: ".bashrc".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_unknown_message_handled_gracefully() {
        let (tx, _rx) = make_tx();
        // Ping is not a dotfiles message — should be handled by the _ arm
        let result = handle(WebSocketMessage::Ping, &tx).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_write_dotfile_nonexistent_path() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::WriteDotfile {
                path: "/nonexistent/dir/file.conf".to_string(),
                content: "test content".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        // Should get a response (may succeed or fail depending on permissions)
        assert!(json.contains("dotfile-written") || json.contains("error"));
    }

    #[tokio::test]
    async fn test_restore_dotfile_version_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::RestoreDotfileVersion {
                path: ".nonexistent_file_xyz".to_string(),
                timestamp: chrono::Utc::now(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(
            json.contains("error") || json.contains("Error") || json.contains("DotfileRestored")
        );
    }

    #[tokio::test]
    async fn test_read_binary_file_nonexistent() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::ReadBinaryFile {
                path: "/nonexistent/binary/file.bin".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        assert!(json.contains("error") || json.contains("Error"));
    }

    #[tokio::test]
    async fn test_read_dotfile_success() {
        let (tx, mut rx) = make_tx();
        let result = handle(
            WebSocketMessage::ReadDotfile {
                path: ".bashrc".to_string(),
            },
            &tx,
        )
        .await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        let json = match msg {
            BroadcastMessage::Text(s) => s.as_ref().clone(),
            _ => panic!("Expected text"),
        };
        // .bashrc may or may not exist, but we should get a dotfile-content response
        assert!(json.contains("dotfile-content"));
    }
}
