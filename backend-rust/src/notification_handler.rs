use std::path::PathBuf;
use std::sync::Arc;

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::notification::{CreateNotificationRequest, Notification, NotificationFile};
use crate::types::ServerMessage;
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct ListQuery {
    limit: Option<usize>,
    before: Option<i64>,
}

/// File metadata exposed to clients — omits stored_path.
#[derive(Debug, Serialize)]
pub struct FileMeta {
    pub id: String,
    pub filename: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    pub size: i64,
}

impl From<NotificationFile> for FileMeta {
    fn from(f: NotificationFile) -> Self {
        FileMeta {
            id: f.id,
            filename: f.filename,
            mime_type: f.mime_type,
            size: f.size,
        }
    }
}

#[derive(Debug, Serialize)]
pub struct NotificationListItem {
    #[serde(flatten)]
    pub notification: Notification,
    pub files: Vec<FileMeta>,
}

#[derive(Debug, Serialize)]
pub struct ListResponse {
    notifications: Vec<NotificationListItem>,
    #[serde(rename = "hasMore")]
    has_more: bool,
}

#[derive(Debug, Serialize)]
pub struct CreateResponse {
    id: String,
    received: bool,
}

#[derive(Debug, Serialize)]
pub struct MarkReadResponse {
    success: bool,
}

pub async fn list_notifications(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ListQuery>,
) -> Json<ListResponse> {
    let limit = query.limit.unwrap_or(50);
    let notifications = state.notification_store.list(limit + 1, query.before).unwrap_or_default();
    let has_more = notifications.len() > limit;

    let notifications: Vec<NotificationListItem> = notifications
        .into_iter()
        .take(limit)
        .map(|n| {
            let files = if n.file_count > 0 {
                state
                    .notification_store
                    .list_files_for_notification(&n.id)
                    .unwrap_or_default()
                    .into_iter()
                    .map(FileMeta::from)
                    .collect()
            } else {
                vec![]
            };
            NotificationListItem { notification: n, files }
        })
        .collect();

    Json(ListResponse {
        notifications,
        has_more,
    })
}

pub async fn create_notification(
    State(state): State<Arc<AppState>>,
    Json(req): Json<CreateNotificationRequest>,
) -> Result<(StatusCode, Json<CreateResponse>), StatusCode> {
    let id = Uuid::new_v4().to_string();
    let timestamp_millis = chrono::Utc::now().timestamp_millis();
    let base_dir = state.notification_store.base_dir();

    let mut files = Vec::new();
    for file_req in &req.files {
        let file_id = Uuid::new_v4().to_string();
        let file_dir = PathBuf::from(&base_dir)
            .join("notifications")
            .join(&id);
        
        std::fs::create_dir_all(&file_dir).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        let file_path = file_dir.join(&file_req.filename);
        let decoded = STANDARD.decode(&file_req.data)
            .map_err(|_| StatusCode::BAD_REQUEST)?;
        
        std::fs::write(&file_path, &decoded).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        let notification_file = NotificationFile {
            id: file_id,
            notification_id: id.clone(),
            filename: file_req.filename.clone(),
            mime_type: file_req.mime_type.clone(),
            size: decoded.len() as i64,
            stored_path: file_path.to_string_lossy().to_string(),
        };
        files.push(notification_file);
    }

    let notification = Notification {
        id: id.clone(),
        title: req.title.clone(),
        body: req.body.clone(),
        source: req.source.clone(),
        source_detail: req.source_detail.clone(),
        timestamp_millis,
        read: false,
        file_count: req.files.len() as i32,
    };

    state.notification_store.insert(&notification, files)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let broadcast_msg = ServerMessage::NotificationEvent {
        notification: Notification {
            id: id.clone(),
            title: req.title.clone(),
            body: req.body.clone(),
            source: req.source.clone(),
            source_detail: req.source_detail.clone(),
            timestamp_millis,
            read: false,
            file_count: req.files.len() as i32,
        },
    };
    state.client_manager.broadcast(broadcast_msg).await;

    Ok((StatusCode::CREATED, Json(CreateResponse { id, received: true })))
}

pub async fn mark_read(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Json<MarkReadResponse> {
    let _ = state.notification_store.mark_read(&id);
    Json(MarkReadResponse { success: true })
}
