//! Assistant output publication, bound to the publisher's current native conversation.
//! This path never submits input to the agent or writes files into its project.
use crate::{
    chat_binding::ChatBinding,
    chat_file_storage::ChatFileStorage,
    chat_log::{ChatMessage, ContentBlock},
    types::ServerMessage,
    AppState,
};
use anyhow::{anyhow, ensure, Context, Result};
use axum::{
    extract::{rejection::JsonRejection, State},
    http::StatusCode,
    Json,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::Deserialize;
use std::{
    path::{Component, Path},
    sync::Arc,
};

const MAX_FILE_BYTES: usize = 10 * 1024 * 1024;
const MAX_TEXT_BYTES: usize = 64 * 1024;
pub const MAX_JSON_BYTES: usize = 16 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublishFile {
    filename: String,
    mime_type: String,
    data: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublishMessage {
    #[serde(default)]
    pane_id: Option<String>,
    #[serde(default)]
    codex_thread_id: Option<String>,
    agent_pid: u32,
    agent_start: u64,
    #[serde(default)]
    text: String,
    #[serde(default)]
    files: Vec<PublishFile>,
    #[serde(default)]
    expected_session_name: Option<String>,
    #[serde(default)]
    expected_window_index: Option<u32>,
}

pub(crate) struct PublisherTarget {
    pub binding: ChatBinding,
    pub session_name: String,
    pub window_index: u32,
}

/// Shared with interactive UI publication: names/CWD are not publisher identity.
pub(crate) async fn resolve_publisher(
    app: &AppState,
    pane_id: Option<&str>,
    codex_thread_id: Option<&str>,
    agent_pid: u32,
    agent_start: u64,
) -> Result<PublisherTarget> {
    let store = &app.chat_event_store.bindings;
    let binding = if let Some(id) = codex_thread_id {
        // A shared Codex MCP server's TMUX environment can belong to another
        // terminal. The per-call native thread ID is authoritative instead.
        crate::chat_binding::resolve_codex_publisher(store, agent_pid, agent_start, id).await?
    } else {
        let id = pane_id.context("The publisher has no terminal identity")?;
        ensure!(
            id.starts_with('%') && id.len() > 1 && id[1..].bytes().all(|b| b.is_ascii_digit()),
            "The publisher needs an exact TMUX pane ID"
        );
        let pane = crate::chat_binding::inspect_pane(id).await?;
        ensure!(
            pane.agent_pid == agent_pid && pane.agent_start == agent_start,
            "Publisher is not this pane's current agent"
        );
        store
            .resolve_inspected_pane(pane)
            .await?
            .binding
            .context("The agent has not reported its native conversation ID")?
    };
    store.validate(&binding).await?;
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        tokio::process::Command::new("tmux")
            .args([
                "display-message",
                "-p",
                "-t",
                &binding.pane.pane_id,
                "#{session_name}\t#{window_index}",
            ])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .context("Terminal lookup timed out")??;
    ensure!(output.status.success(), "Terminal disappeared");
    let location = String::from_utf8(output.stdout)?;
    let (session, window) = location
        .trim_end_matches(['\r', '\n'])
        .split_once('\t')
        .context("Invalid terminal location")?;
    let session_name = session.to_owned();
    let window_index = window.parse().context("Invalid terminal window")?;
    Ok(PublisherTarget {
        binding,
        session_name,
        window_index,
    })
}

struct DecodedFile {
    filename: String,
    mime_type: String,
    data: Vec<u8>,
}

fn decode_files(files: Vec<PublishFile>) -> Result<Vec<DecodedFile>> {
    let mut total = 0usize;
    files
        .into_iter()
        .map(|file| {
            let filename = file.filename.trim();
            ensure!(
                !filename.is_empty()
                    && filename.len() <= 255
                    && !filename.chars().any(char::is_control)
                    && !filename.contains('\\'),
                "Invalid attachment filename"
            );
            let mut components = Path::new(filename).components();
            ensure!(
                matches!(components.next(), Some(Component::Normal(_)))
                    && components.next().is_none(),
                "Attachment filename must be a single file name"
            );
            let mime_type = file.mime_type.trim().to_ascii_lowercase();
            ensure!(
                mime_type.len() <= 255 && !mime_type.chars().any(char::is_control),
                "Invalid attachment MIME type"
            );
            let base = mime_type.split(';').next().unwrap_or_default().trim();
            let (kind, subtype) = base
                .split_once('/')
                .context("Invalid attachment MIME type")?;
            let valid_token = |value: &str| {
                !value.is_empty()
                    && value
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"!#$&^_.+-".contains(&b))
            };
            ensure!(
                valid_token(kind) && valid_token(subtype),
                "Invalid attachment MIME type"
            );
            ensure!(
                file.data.len() <= MAX_FILE_BYTES.div_ceil(3) * 4,
                "Attachments exceed the 10 MiB limit"
            );
            let data = STANDARD
                .decode(&file.data)
                .context("Invalid attachment base64 data")?;
            total = total
                .checked_add(data.len())
                .context("Attachments exceed the 10 MiB limit")?;
            ensure!(
                total <= MAX_FILE_BYTES,
                "Attachments exceed the 10 MiB limit"
            );
            Ok(DecodedFile {
                filename: filename.into(),
                mime_type: base.into(),
                data,
            })
        })
        .collect()
}

fn validate_text(text: &str, has_files: bool) -> Result<()> {
    ensure!(
        text.len() <= MAX_TEXT_BYTES,
        "Text exceeds the 64 KiB limit"
    );
    ensure!(
        !text.trim().is_empty() || has_files,
        "A message or attachment is required"
    );
    Ok(())
}

fn save_files(
    storage: &ChatFileStorage,
    files: Vec<DecodedFile>,
) -> Result<(Vec<String>, Vec<ContentBlock>)> {
    let mut ids: Vec<String> = Vec::new();
    let mut blocks = Vec::new();
    for file in files {
        let id = match storage.save_bytes(&file.data, &file.filename, &file.mime_type) {
            Ok(id) => id,
            Err(error) => {
                for id in &ids {
                    let _ = storage.remove_file(id);
                }
                return Err(anyhow!(error));
            }
        };
        let block = if file.mime_type.starts_with("image/") {
            ContentBlock::Image {
                id: id.clone(),
                mime_type: file.mime_type,
                alt_text: Some(file.filename),
            }
        } else if file.mime_type.starts_with("audio/") {
            ContentBlock::Audio {
                id: id.clone(),
                mime_type: file.mime_type,
                duration_seconds: None,
            }
        } else {
            ContentBlock::File {
                id: id.clone(),
                filename: file.filename,
                mime_type: file.mime_type,
                size_bytes: Some(file.data.len() as u64),
            }
        };
        ids.push(id);
        blocks.push(block);
    }
    Ok((ids, blocks))
}

/// Cancelling an HTTP request must not leave staged files behind, or delete
/// files after the worker has committed their history message.
struct StagedPublication {
    storage: Arc<ChatFileStorage>,
    ids: Vec<String>,
    blocks: Vec<ContentBlock>,
    committed: bool,
}

impl StagedPublication {
    fn rollback(&mut self) {
        for id in &self.ids {
            let _ = self.storage.remove_file(id);
        }
        self.committed = true;
    }

    async fn discard(mut self) {
        let _ = tokio::task::spawn_blocking(move || self.rollback()).await;
    }
}

impl Drop for StagedPublication {
    fn drop(&mut self) {
        if self.committed {
            return;
        }
        let storage = self.storage.clone();
        let ids = std::mem::take(&mut self.ids);
        let cleanup = move || {
            for id in ids {
                let _ = storage.remove_file(&id);
            }
        };
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn_blocking(cleanup);
        } else {
            cleanup();
        }
    }
}

fn check_expected_target(payload: &PublishMessage, target: &PublisherTarget) -> Result<()> {
    ensure!(
        !payload
            .expected_session_name
            .as_ref()
            .is_some_and(|name| name != &target.session_name)
            && !payload
                .expected_window_index
                .is_some_and(|index| index != target.window_index),
        "The publisher is not in the expected terminal session/window"
    );
    Ok(())
}

pub async fn publish(
    State(app): State<Arc<AppState>>,
    payload: std::result::Result<Json<PublishMessage>, JsonRejection>,
) -> std::result::Result<Json<serde_json::Value>, (StatusCode, String)> {
    let Json(mut payload) = payload.map_err(|error| {
        let status = if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
            error.status()
        } else {
            StatusCode::BAD_REQUEST
        };
        (status, error.body_text())
    })?;
    validate_text(&payload.text, !payload.files.is_empty())
        .map_err(|error| (StatusCode::BAD_REQUEST, error.to_string()))?;
    if payload
        .expected_session_name
        .as_ref()
        .is_some_and(|name| name.trim().is_empty())
    {
        return Err((
            StatusCode::BAD_REQUEST,
            "Expected session name must not be blank".into(),
        ));
    }
    // Validate every attachment before writing any bytes or pinning a live target.
    let encoded_files = std::mem::take(&mut payload.files);
    let files = tokio::task::spawn_blocking(move || decode_files(encoded_files))
        .await
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Attachment preparation failed".into(),
            )
        })?
        .map_err(|error| (StatusCode::BAD_REQUEST, error.to_string()))?;
    let original = resolve_publisher(
        &app,
        payload.pane_id.as_deref(),
        payload.codex_thread_id.as_deref(),
        payload.agent_pid,
        payload.agent_start,
    )
    .await
    .map_err(|error| (StatusCode::CONFLICT, error.to_string()))?;
    let storage = app.chat_file_storage.clone();
    let mut staged = tokio::task::spawn_blocking(move || {
        let (ids, blocks) = save_files(&storage, files)?;
        Ok::<_, anyhow::Error>(StagedPublication {
            storage,
            ids,
            blocks,
            committed: false,
        })
    })
    .await
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Attachment storage worker failed".into(),
        )
    })?
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Unable to store the chat attachments".into(),
        )
    })?;
    // Disk IO may take time. Re-resolve the publisher and location afterward;
    // never send to a replacement conversation or a stale session alias.
    let verified = async {
        let latest = resolve_publisher(
            &app,
            payload.pane_id.as_deref(),
            payload.codex_thread_id.as_deref(),
            payload.agent_pid,
            payload.agent_start,
        )
        .await?;
        ensure!(
            latest.binding.binding_id == original.binding.binding_id,
            "The terminal switched conversations while preparing the publication"
        );
        check_expected_target(&payload, &latest)?;
        app.chat_event_store
            .bindings
            .validate(&latest.binding)
            .await?;
        Ok::<_, anyhow::Error>(latest)
    }
    .await;
    let target = match verified {
        Ok(target) => target,
        Err(error) => {
            staged.discard().await;
            return Err((StatusCode::CONFLICT, error.to_string()));
        }
    };
    let store = app.chat_event_store.clone();
    let key = target.binding.conversation_key.clone();
    let text = payload.text;
    let (file_ids, message) = tokio::task::spawn_blocking(move || {
        let mut blocks = std::mem::take(&mut staged.blocks);
        if !text.trim().is_empty() {
            blocks.insert(0, ContentBlock::Text { text });
        }
        let message = ChatMessage {
            role: "assistant".into(),
            timestamp: Some(chrono::Utc::now()),
            blocks,
        };
        if let Err(error) = store.append_message(&key, 0, "agent-publication", &message) {
            staged.rollback();
            return Err(error);
        }
        staged.committed = true;
        Ok::<_, anyhow::Error>((staged.ids.clone(), message))
    })
    .await
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Publication worker failed".into(),
        )
    })?
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Unable to store the chat publication".into(),
        )
    })?;
    app.client_manager
        .broadcast_bound(
            ServerMessage::ChatEvent {
                session_name: target.session_name.clone(),
                window_index: target.window_index,
                message,
                source: Some("agent-publication".into()),
            },
            &target.binding,
        )
        .await;
    Ok(Json(
        serde_json::json!({"published":true,"bindingId":target.binding.binding_id,
        "conversationKey":target.binding.conversation_key,"sessionName":target.session_name,
        "windowIndex":target.window_index,"fileIds":file_ids}),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(name: &str, mime: &str, data: &[u8]) -> PublishFile {
        PublishFile {
            filename: name.into(),
            mime_type: mime.into(),
            data: STANDARD.encode(data),
        }
    }

    #[test]
    fn rejects_bad_content_before_any_attachment_is_saved() {
        assert!(validate_text("  ", false).is_err());
        assert!(validate_text(&"a".repeat(MAX_TEXT_BYTES + 1), true).is_err());
        assert!(decode_files(vec![
            file("valid.txt", "text/plain", b"valid"),
            file("../bad.png", "image/png", b"bad")
        ])
        .is_err());
        assert!(decode_files(vec![file("bad\\name.png", "image/png", b"bad")]).is_err());
        assert!(decode_files(vec![file("photo.png", "not-a-mime", b"data")]).is_err());
        assert!(decode_files(vec![PublishFile {
            filename: "photo.png".into(),
            mime_type: "image/png".into(),
            data: "!".into()
        }])
        .is_err());
        assert!(decode_files(vec![
            file(
                "large.bin",
                "application/octet-stream",
                &vec![0; MAX_FILE_BYTES]
            ),
            file("extra.bin", "application/octet-stream", b"x")
        ])
        .is_err());
    }

    #[test]
    fn saves_assistant_attachment_blocks_with_exact_sizes_and_reversible_files() {
        let directory = tempfile::tempdir().unwrap();
        let storage = ChatFileStorage::new(directory.path().into());
        let files = decode_files(vec![
            file("photo.png", "image/png", b"png"),
            file("notes.txt", "text/plain; charset=utf-8", b"hello"),
        ])
        .unwrap();
        let (ids, blocks) = save_files(&storage, files).unwrap();
        assert_eq!(ids.len(), 2);
        assert!(matches!(&blocks[0], ContentBlock::Image { id, .. } if id == &ids[0]));
        assert!(
            matches!(&blocks[1], ContentBlock::File { size_bytes: Some(5), mime_type, .. } if mime_type == "text/plain")
        );
        assert_eq!(storage.get_file_data(&ids[0]).unwrap(), b"png");
        for id in &ids {
            storage.remove_file(id).unwrap();
            assert!(storage.get_path(id).is_none());
        }
    }
}
