use chrono::{DateTime, NaiveDateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TmuxSession {
    pub name: String,
    pub attached: bool,
    pub created: DateTime<Utc>,
    pub windows: u32,
    pub dimensions: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TmuxWindow {
    pub index: u32,
    pub name: String,
    pub active: bool,
    pub panes: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateSessionRequest {
    pub name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenameSessionRequest {
    pub new_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateWindowRequest {
    pub window_name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenameWindowRequest {
    pub new_name: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemStats {
    pub cpu: CpuInfo,
    pub memory: MemoryInfo,
    pub uptime: u64,
    pub hostname: String,
    pub platform: String,
    pub arch: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CpuInfo {
    pub cores: usize,
    pub model: String,
    pub usage: f32,
    pub load_avg: [f32; 3],
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryInfo {
    pub total: u64,
    pub used: u64,
    pub free: u64,
    pub percent: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CronJob {
    pub id: String,
    pub name: String,
    pub schedule: String,
    pub command: String,
    pub enabled: bool,
    #[serde(
        alias = "lastRun",
        default,
        deserialize_with = "deserialize_optional_datetime"
    )]
    pub last_run: Option<DateTime<Utc>>,
    #[serde(
        alias = "nextRun",
        default,
        deserialize_with = "deserialize_optional_datetime"
    )]
    pub next_run: Option<DateTime<Utc>>,
    pub output: Option<String>,
    #[serde(
        alias = "createdAt",
        default,
        deserialize_with = "deserialize_optional_datetime"
    )]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(
        alias = "updatedAt",
        default,
        deserialize_with = "deserialize_optional_datetime"
    )]
    pub updated_at: Option<DateTime<Utc>>,
    pub environment: Option<HashMap<String, String>>,
    #[serde(alias = "logOutput")]
    pub log_output: Option<bool>,
    #[serde(alias = "emailTo")]
    pub email_to: Option<String>,
    #[serde(alias = "tmuxSession")]
    pub tmux_session: Option<String>,
}

fn deserialize_optional_datetime<'de, D>(deserializer: D) -> Result<Option<DateTime<Utc>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = Option::<String>::deserialize(deserializer)?;
    Ok(value.and_then(|raw| parse_datetime_lenient(&raw)))
}

fn parse_datetime_lenient(raw: &str) -> Option<DateTime<Utc>> {
    if let Ok(dt) = DateTime::parse_from_rfc3339(raw) {
        return Some(dt.with_timezone(&Utc));
    }

    // Flutter can send ISO-8601 strings without timezone; treat them as UTC.
    if let Ok(naive) = NaiveDateTime::parse_from_str(raw, "%Y-%m-%dT%H:%M:%S%.f") {
        return Some(DateTime::<Utc>::from_naive_utc_and_offset(naive, Utc));
    }

    if let Ok(naive) = NaiveDateTime::parse_from_str(raw, "%Y-%m-%d %H:%M:%S%.f") {
        return Some(DateTime::<Utc>::from_naive_utc_and_offset(naive, Utc));
    }

    None
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum WebSocketMessage {
    ListSessions,
    AttachSession {
        #[serde(rename = "sessionName")]
        session_name: String,
        cols: u16,
        rows: u16,
        #[serde(rename = "windowIndex")]
        window_index: Option<u32>,
    },
    Input {
        data: String,
    },
    #[serde(alias = "inputViaTmux")]
    InputViaTmux {
        #[serde(alias = "sessionName")]
        session_name: Option<String>,
        #[serde(alias = "windowIndex")]
        window_index: Option<u32>,
        data: String,
    },
    SendEnterKey,
    Resize {
        cols: u16,
        rows: u16,
    },
    ListWindows {
        #[serde(rename = "sessionName")]
        session_name: String,
    },
    SelectWindow {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
    },
    Ping,
    AudioControl {
        action: AudioAction,
    },
    // Session management
    CreateSession {
        name: Option<String>,
    },
    KillSession {
        #[serde(rename = "sessionName")]
        session_name: String,
    },
    RenameSession {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "newName")]
        new_name: String,
    },
    // Window management
    CreateWindow {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowName")]
        window_name: Option<String>,
    },
    KillWindow {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: String,
    },
    RenameWindow {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: String,
        #[serde(rename = "newName")]
        new_name: String,
    },
    // System stats
    GetStats,
    // Cron management
    ListCronJobs,
    CreateCronJob {
        job: CronJob,
    },
    UpdateCronJob {
        id: String,
        job: CronJob,
    },
    DeleteCronJob {
        id: String,
    },
    ToggleCronJob {
        id: String,
        enabled: bool,
    },
    TestCronCommand {
        command: String,
    },
    // Dotfile management
    ListDotfiles,
    ReadDotfile {
        path: String,
    },
    WriteDotfile {
        path: String,
        content: String,
    },
    GetDotfileHistory {
        path: String,
    },
    RestoreDotfileVersion {
        path: String,
        timestamp: DateTime<Utc>,
    },
    GetDotfileTemplates,
    // Chat log watching
    WatchChatLog {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
    },
    UnwatchChatLog,
    // Clear chat history for a session
    ClearChatLog {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
    },
    // Chat file sending
    SendFileToChat {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        file: FileAttachment,
        prompt: Option<String>,
    },
    // Chat message sending (webhook)
    SendChatMessage {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        message: String,
        #[serde(default)]
        notify: Option<bool>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AudioAction {
    Start,
    Stop,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileAttachment {
    pub filename: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    pub data: String, // base64 encoded
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ServerMessage {
    SessionsList {
        sessions: Vec<TmuxSession>,
    },
    Attached {
        #[serde(rename = "sessionName")]
        session_name: String,
    },
    Output {
        data: String,
    },
    Disconnected,
    WindowsList {
        #[serde(rename = "sessionName")]
        session_name: String,
        windows: Vec<TmuxWindow>,
    },
    WindowSelected {
        success: bool,
        #[serde(rename = "windowIndex", skip_serializing_if = "Option::is_none")]
        window_index: Option<u32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    Pong,
    AudioStatus {
        streaming: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    AudioStream {
        data: String, // base64 encoded audio data
    },
    // Session management responses
    SessionCreated {
        success: bool,
        #[serde(rename = "sessionName", skip_serializing_if = "Option::is_none")]
        session_name: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    SessionKilled {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    SessionRenamed {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // Window management responses
    WindowCreated {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    WindowKilled {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    WindowRenamed {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // System stats response
    Stats {
        stats: SystemStats,
    },
    // Generic error response
    Error {
        message: String,
    },
    // Cron management responses
    CronJobsList {
        jobs: Vec<CronJob>,
    },
    CronJobCreated {
        job: CronJob,
    },
    CronJobUpdated {
        job: CronJob,
    },
    CronJobDeleted {
        id: String,
    },
    CronCommandOutput {
        output: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // Dotfile management responses
    DotfilesList {
        files: Vec<crate::dotfiles::DotFile>,
    },
    DotfileContent {
        path: String,
        content: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    DotfileWritten {
        path: String,
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    DotfileHistory {
        path: String,
        versions: Vec<crate::dotfiles::FileVersion>,
    },
    DotfileRestored {
        path: String,
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    DotfileTemplates {
        templates: Vec<crate::dotfiles::DotFileTemplate>,
    },
    // Chat log responses
    ChatHistory {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        messages: Vec<crate::chat_log::ChatMessage>,
        tool: Option<crate::chat_log::AiTool>,
    },
    ChatEvent {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        message: crate::chat_log::ChatMessage,
    },
    ChatLogError {
        error: String,
    },
    ChatLogCleared {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    ChatFileMessage {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        message: crate::chat_log::ChatMessage,
    },
    ChatNotification {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        preview: String,
    },
    // Terminal history bootstrap
    TerminalHistoryStart {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        #[serde(rename = "totalLines")]
        total_lines: i64,
        #[serde(rename = "chunkSize")]
        chunk_size: usize,
        #[serde(rename = "generatedAt")]
        generated_at: DateTime<Utc>,
    },
    TerminalHistoryChunk {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        seq: usize,
        data: String,
        #[serde(rename = "lineCount")]
        line_count: usize,
        #[serde(rename = "isLast")]
        is_last: bool,
    },
    TerminalHistoryEnd {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        #[serde(rename = "totalLines")]
        total_lines: i64,
        #[serde(rename = "totalChunks")]
        total_chunks: usize,
    },
}
