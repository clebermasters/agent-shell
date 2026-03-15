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

        WebSocketMessage::WriteDotfile { path, content } => {
            match crate::dotfiles::DOTFILES_MANAGER
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
            }
        }

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
            match crate::dotfiles::DOTFILES_MANAGER.read_binary_file(&path).await {
                Ok((bytes, mime_type)) => {
                    use base64::Engine;
                    let content_base64 =
                        base64::engine::general_purpose::STANDARD.encode(&bytes);
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
