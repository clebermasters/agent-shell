use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Notification {
    pub id: String,
    pub title: String,
    pub body: String,
    pub source: String,
    pub source_detail: Option<String>,
    pub timestamp_millis: i64,
    pub read: bool,
    pub file_count: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationFile {
    pub id: String,
    pub notification_id: String,
    pub filename: String,
    pub mime_type: String,
    pub size: i64,
    pub stored_path: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateNotificationRequest {
    pub title: String,
    pub body: String,
    pub source: String,
    #[serde(default)]
    pub source_detail: Option<String>,
    #[serde(default)]
    pub files: Vec<FileAttachment>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileAttachment {
    pub filename: String,
    pub mime_type: String,
    pub data: String,
}
