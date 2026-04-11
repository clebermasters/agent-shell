use chrono::{DateTime, NaiveDateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::notification::Notification;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TmuxSession {
    pub name: String,
    pub attached: bool,
    pub created: DateTime<Utc>,
    pub windows: u32,
    pub dimensions: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TmuxWindow {
    pub index: u32,
    pub name: String,
    pub active: bool,
    pub panes: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageBucket {
    pub utilization: f64,
    pub resets_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderUsageWindow {
    pub used_percent: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resets_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub window_duration_mins: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PermissionOption {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessInfo {
    pub pid: u32,
    pub name: String,
    pub cpu_usage: f32,
    pub memory_bytes: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemStats {
    pub cpu: CpuInfo,
    pub memory: MemoryInfo,
    pub disk: DiskInfo,
    pub uptime: u64,
    pub hostname: String,
    pub platform: String,
    pub arch: String,
    pub top_processes_by_cpu: Vec<ProcessInfo>,
    pub top_processes_by_memory: Vec<ProcessInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskInfo {
    pub total: u64,
    pub used: u64,
    pub free: u64,
    pub percent: String,
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
    #[serde(alias = "workdir", default)]
    pub workdir: Option<String>,
    #[serde(alias = "prompt", default)]
    pub prompt: Option<String>,
    #[serde(alias = "llmProvider", default)]
    pub llm_provider: Option<String>,
    #[serde(alias = "llmModel", default)]
    pub llm_model: Option<String>,
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
        #[serde(rename = "startDirectory", default)]
        start_directory: Option<String>,
        #[serde(rename = "startupCommand", default)]
        startup_command: Option<String>,
        #[serde(rename = "startupArgs", default)]
        startup_args: Option<String>,
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
    GetClaudeUsage,
    GetCodexUsage,
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
        limit: Option<usize>,
    },
    // ACP chat log watching (direct by session ID)
    WatchAcpChatLog {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "windowIndex")]
        window_index: Option<u32>,
        limit: Option<usize>,
    },
    // Load more chat history (pagination)
    LoadMoreChatHistory {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        offset: usize,
        limit: usize,
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
    // ACP backend selection
    SelectBackend {
        backend: String,
    },
    // ACP session management
    AcpCreateSession {
        cwd: String,
        #[serde(default)]
        backend: Option<String>,
    },
    AcpResumeSession {
        #[serde(rename = "sessionId")]
        session_id: String,
        cwd: String,
    },
    AcpForkSession {
        #[serde(rename = "sessionId")]
        session_id: String,
        cwd: String,
    },
    AcpListSessions,
    AcpSendPrompt {
        #[serde(rename = "sessionId")]
        session_id: String,
        message: String,
    },
    AcpCancelPrompt {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    AcpSetModel {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "modelId")]
        model_id: String,
    },
    AcpSetMode {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "modeId")]
        mode_id: String,
    },
    AcpRespondPermission {
        #[serde(rename = "requestId")]
        request_id: String,
        #[serde(rename = "optionId")]
        option_id: String,
    },
    AcpLoadHistory {
        #[serde(rename = "sessionId")]
        session_id: String,
        offset: Option<usize>,
        limit: Option<usize>,
    },
    AcpClearHistory {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    AcpDeleteSession {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    // ACP file sending
    SendFileToAcpChat {
        #[serde(rename = "sessionId")]
        session_id: String,
        file: FileAttachment,
        prompt: Option<String>,
        cwd: Option<String>,
    },
    // File browser
    ListFiles {
        path: String,
    },
    GetSessionCwd {
        #[serde(rename = "sessionName")]
        session_name: String,
    },
    // Binary file reading (images, audio, etc.)
    ReadBinaryFile {
        path: String,
    },
    // Binary file writing (creates or overwrites)
    WriteFile {
        path: String,
        #[serde(rename = "contentBase64")]
        content_base64: String,
    },
    // File management
    DeleteFiles {
        paths: Vec<String>,
        recursive: bool,
    },
    RenameFile {
        path: String,
        #[serde(rename = "newName")]
        new_name: String,
    },
    CopyFiles {
        #[serde(rename = "sourcePaths")]
        source_paths: Vec<String>,
        #[serde(rename = "destinationPath")]
        destination_path: String,
    },
    MoveFiles {
        #[serde(rename = "sourcePaths")]
        source_paths: Vec<String>,
        #[serde(rename = "destinationPath")]
        destination_path: String,
    },
    // Git operations
    GitStatus {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
    },
    GitDiff {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "filePath")]
        file_path: Option<String>,
        staged: Option<bool>,
    },
    GitLog {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        limit: Option<usize>,
        offset: Option<usize>,
    },
    GitBranches {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
    },
    GitCheckout {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        branch: String,
    },
    GitCreateBranch {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        branch: String,
        #[serde(rename = "startPoint")]
        start_point: Option<String>,
    },
    GitDeleteBranch {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        branch: String,
    },
    GitStage {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        files: Vec<String>,
    },
    GitUnstage {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        files: Vec<String>,
    },
    GitCommit {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        message: String,
    },
    GitPush {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        remote: Option<String>,
        branch: Option<String>,
    },
    GitPull {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
    },
    GitStash {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        action: String,
    },
    GitCommitFiles {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "commitHash")]
        commit_hash: String,
    },
    GitCommitDiff {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "commitHash")]
        commit_hash: String,
        #[serde(rename = "filePath")]
        file_path: String,
    },
    // Search commits
    GitSearch {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        query: String,
        author: Option<String>,
        since: Option<String>,
        limit: Option<usize>,
    },
    // File history
    GitFileHistory {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "filePath")]
        file_path: String,
        limit: Option<usize>,
        offset: Option<usize>,
    },
    // Cherry-pick
    GitCherryPick {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "commitHash")]
        commit_hash: String,
    },
    // Revert
    GitRevert {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "commitHash")]
        commit_hash: String,
    },
    // Merge
    GitMerge {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        branch: String,
    },
    // Blame
    GitBlame {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "filePath")]
        file_path: String,
    },
    // Compare branches
    GitCompare {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "baseBranch")]
        base_branch: String,
        #[serde(rename = "compareBranch")]
        compare_branch: String,
    },
    // Repo info
    GitRepoInfo {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
    },
    // Amend last commit
    GitAmend {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        message: Option<String>,
    },
    // Tags
    GitListTags {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
    },
    GitCreateTag {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        name: String,
        #[serde(rename = "commitHash")]
        commit_hash: Option<String>,
        message: Option<String>,
    },
    GitDeleteTag {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        name: String,
    },
    // Resolve conflict
    GitResolveConflict {
        #[serde(rename = "sessionName")]
        session_name: Option<String>,
        path: Option<String>,
        #[serde(rename = "filePath")]
        file_path: String,
        resolution: String,
    },
    // Favorites
    GetFavorites,
    AddFavorite {
        name: String,
        path: String,
        #[serde(rename = "sortOrder", default)]
        sort_order: i32,
        #[serde(rename = "startupCommand")]
        startup_command: Option<String>,
        #[serde(rename = "startupArgs")]
        startup_args: Option<String>,
        #[serde(rename = "tagIds", default)]
        tag_ids: Vec<String>,
    },
    UpdateFavorite {
        id: String,
        name: String,
        path: String,
        #[serde(rename = "sortOrder", default)]
        sort_order: i32,
        #[serde(rename = "startupCommand")]
        startup_command: Option<String>,
        #[serde(rename = "startupArgs")]
        startup_args: Option<String>,
        #[serde(rename = "tagIds", default)]
        tag_ids: Vec<String>,
    },
    DeleteFavorite {
        id: String,
    },
    SetFavoriteTags {
        #[serde(rename = "favoriteId")]
        favorite_id: String,
        #[serde(rename = "tagIds")]
        tag_ids: Vec<String>,
    },
    // Tags
    GetTags,
    AddTag {
        name: String,
        #[serde(rename = "colorHex")]
        color_hex: String,
    },
    DeleteTag {
        id: String,
    },
    GetTagAssignments,
    AssignTagToSession {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "tagId")]
        tag_id: String,
    },
    RemoveTagFromSession {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "tagId")]
        tag_id: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AudioAction {
    Start,
    Stop,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_directory: bool,
    pub size: u64,
    pub modified: Option<String>, // ISO 8601
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
    ServerShuttingDown {
        #[serde(rename = "reconnectDelayMs")]
        reconnect_delay_ms: u64,
    },
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
    // Claude usage response
    ClaudeUsage {
        five_hour: Option<UsageBucket>,
        seven_day: Option<UsageBucket>,
        seven_day_sonnet: Option<UsageBucket>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    CodexUsage {
        primary: Option<ProviderUsageWindow>,
        secondary: Option<ProviderUsageWindow>,
        #[serde(skip_serializing_if = "Option::is_none")]
        plan_type: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        limit_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        limit_name: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
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
        #[serde(rename = "hasMore")]
        has_more: bool,
        #[serde(rename = "totalCount")]
        total_count: usize,
        #[serde(rename = "contextWindowUsage", skip_serializing_if = "Option::is_none")]
        context_window_usage: Option<f64>,
        #[serde(rename = "modelName", skip_serializing_if = "Option::is_none")]
        model_name: Option<String>,
    },
    ChatHistoryChunk {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        messages: Vec<crate::chat_log::ChatMessage>,
        #[serde(rename = "hasMore")]
        has_more: bool,
    },
    ChatEvent {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        message: crate::chat_log::ChatMessage,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        source: Option<String>,
    },
    ContextWindowUpdate {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "windowIndex")]
        window_index: u32,
        #[serde(rename = "contextWindowUsage")]
        context_window_usage: f64,
        #[serde(rename = "modelName", skip_serializing_if = "Option::is_none")]
        model_name: Option<String>,
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
    NotificationEvent {
        notification: Notification,
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
    // ACP responses
    BackendSelected {
        backend: String,
    },
    AcpInitialized {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    AcpSessionCreated {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "currentModelId")]
        current_model_id: Option<String>,
        #[serde(rename = "availableModels")]
        available_models: Option<Vec<crate::acp::ModelInfo>>,
        #[serde(rename = "currentModeId")]
        current_mode_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        cwd: Option<String>,
    },
    AcpSessionResumed {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "currentModelId")]
        current_model_id: String,
        #[serde(rename = "availableModels")]
        available_models: Vec<crate::acp::ModelInfo>,
        #[serde(rename = "currentModeId")]
        current_mode_id: String,
    },
    AcpSessionForked {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "currentModelId")]
        current_model_id: String,
    },
    AcpSessionsListed {
        sessions: Vec<crate::acp::SessionInfo>,
    },
    AcpMessageChunk {
        #[serde(rename = "sessionId")]
        session_id: String,
        content: String,
        #[serde(rename = "isThinking")]
        is_thinking: bool,
    },
    AcpToolCall {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        title: String,
        kind: String,
        status: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        input: Option<String>,
    },
    AcpToolResult {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        status: String,
        output: String,
    },
    AcpPromptDone {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "stopReason")]
        stop_reason: String,
        #[serde(rename = "totalTokens")]
        total_tokens: usize,
    },
    AcpPermissionRequest {
        #[serde(rename = "requestId")]
        request_id: String,
        #[serde(rename = "sessionId")]
        session_id: String,
        tool: String,
        command: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        options: Vec<PermissionOption>,
    },
    AcpError {
        message: String,
    },
    AcpPromptSent {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    AcpPromptCancelled {
        #[serde(rename = "sessionId")]
        session_id: String,
    },
    AcpModelSet {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "modelId")]
        model_id: String,
    },
    AcpModeSet {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "modeId")]
        mode_id: String,
    },
    AcpPermissionResponse {
        #[serde(rename = "requestId")]
        request_id: String,
    },
    AcpHistoryLoaded {
        #[serde(rename = "sessionId")]
        session_id: String,
        messages: Vec<crate::chat_log::ChatMessage>,
        has_more: bool,
    },
    AcpSessionDeleted {
        #[serde(rename = "sessionId")]
        session_id: String,
        success: bool,
        error: Option<String>,
    },
    // File browser responses
    FilesList {
        path: String,
        entries: Vec<FileEntry>,
    },
    SessionCwd {
        #[serde(rename = "sessionName")]
        session_name: String,
        cwd: String,
    },
    // File management responses
    FilesDeleted {
        success: bool,
        paths: Vec<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    FileRenamed {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    FilesCopied {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    FilesMoved {
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // Binary file content response
    BinaryFileContent {
        path: String,
        #[serde(rename = "contentBase64")]
        content_base64: String,
        #[serde(rename = "mimeType")]
        mime_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // Write file response
    FileWritten {
        path: String,
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    // Git responses
    GitStatusResult {
        branch: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        upstream: Option<String>,
        ahead: i32,
        behind: i32,
        staged: Vec<GitFileChange>,
        modified: Vec<GitFileChange>,
        untracked: Vec<String>,
        conflicts: Vec<String>,
    },
    GitDiffResult {
        #[serde(rename = "filePath")]
        file_path: String,
        diff: String,
        additions: u32,
        deletions: u32,
    },
    GitLogResult {
        commits: Vec<GitCommitInfo>,
        #[serde(rename = "hasMore")]
        has_more: bool,
    },
    GitBranchesResult {
        branches: Vec<GitBranchInfo>,
    },
    GitGraphResult {
        nodes: Vec<GitGraphNode>,
        #[serde(rename = "hasMore")]
        has_more: bool,
    },
    GitOperationResult {
        operation: String,
        success: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    GitCommitFilesResult {
        #[serde(rename = "commitHash")]
        commit_hash: String,
        files: Vec<GitFileChange>,
    },
    GitSearchResult {
        query: String,
        commits: Vec<GitCommitInfo>,
    },
    GitFileHistoryResult {
        #[serde(rename = "filePath")]
        file_path: String,
        commits: Vec<GitCommitInfo>,
        #[serde(rename = "hasMore")]
        has_more: bool,
    },
    GitBlameResult {
        #[serde(rename = "filePath")]
        file_path: String,
        lines: Vec<GitBlameLine>,
    },
    GitCompareResult {
        #[serde(rename = "baseBranch")]
        base_branch: String,
        #[serde(rename = "compareBranch")]
        compare_branch: String,
        ahead: i32,
        behind: i32,
        commits: Vec<GitCommitInfo>,
    },
    GitRepoInfoResult {
        remotes: Vec<GitRemoteInfo>,
        #[serde(rename = "totalCommits")]
        total_commits: u64,
        #[serde(rename = "currentBranch")]
        current_branch: String,
        #[serde(rename = "branchCount")]
        branch_count: u32,
        #[serde(rename = "tagCount")]
        tag_count: u32,
        #[serde(rename = "repoSize")]
        repo_size: String,
        #[serde(rename = "lastCommit")]
        last_commit: Option<GitCommitInfo>,
    },
    GitTagsResult {
        tags: Vec<GitTagInfo>,
    },
    GitStashListResult {
        entries: Vec<GitStashEntry>,
    },
    // Favorites
    FavoritesList {
        favorites: Vec<crate::favorite_store::FavoriteEntry>,
    },
    FavoriteAdded {
        favorite: crate::favorite_store::FavoriteEntry,
    },
    FavoriteUpdated {
        favorite: crate::favorite_store::FavoriteEntry,
    },
    FavoriteDeleted {
        id: String,
        success: bool,
    },
    FavoriteTagsUpdated {
        #[serde(rename = "favoriteId")]
        favorite_id: String,
        #[serde(rename = "tagIds")]
        tag_ids: Vec<String>,
    },
    // Tags
    TagsList {
        tags: Vec<crate::tag_store::TagEntry>,
    },
    TagAdded {
        tag: crate::tag_store::TagEntry,
    },
    TagDeleted {
        id: String,
        success: bool,
    },
    TagAssignmentsList {
        assignments: Vec<crate::tag_store::TagAssignment>,
    },
    TagAssignmentUpdated {
        #[serde(rename = "sessionName")]
        session_name: String,
        #[serde(rename = "tagId")]
        tag_id: String,
        assigned: bool,
    },
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitBlameLine {
    pub line_number: u32,
    pub hash: String,
    pub author: String,
    pub date: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitRemoteInfo {
    pub name: String,
    pub url: String,
    pub remote_type: String, // "fetch" or "push"
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitTagInfo {
    pub name: String,
    pub hash: String,
    pub message: Option<String>,
    pub date: Option<String>,
    pub is_annotated: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitStashEntry {
    pub index: u32,
    pub message: String,
    pub date: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitFileChange {
    pub path: String,
    pub status: String, // "M", "A", "D", "R", "C"
    pub additions: Option<u32>,
    pub deletions: Option<u32>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitCommitInfo {
    pub hash: String,
    pub short_hash: String,
    pub message: String,
    pub author: String,
    pub date: String,
    pub parents: Vec<String>,
    pub refs: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitBranchInfo {
    pub name: String,
    pub current: bool,
    pub tracking: Option<String>,
    pub ahead: Option<i32>,
    pub behind: Option<i32>,
    pub last_commit: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitGraphNode {
    pub hash: String,
    pub short_hash: String,
    pub message: String,
    pub author: String,
    pub date: String,
    pub parents: Vec<String>,
    pub refs: Vec<String>,
    pub column: i32,
    pub color: u32,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json;

    #[test]
    fn test_parse_datetime_rfc3339() {
        let result = parse_datetime_lenient("2024-01-15T10:30:00Z");
        assert!(result.is_some());
    }

    #[test]
    fn test_parse_datetime_naive_dot_f() {
        let result = parse_datetime_lenient("2024-01-15T10:30:00.123");
        assert!(result.is_some());
    }

    #[test]
    fn test_parse_datetime_space_separated() {
        let result = parse_datetime_lenient("2024-01-15 10:30:00.000");
        assert!(result.is_some());
    }

    #[test]
    fn test_parse_datetime_invalid() {
        assert!(parse_datetime_lenient("not-a-date").is_none());
        assert!(parse_datetime_lenient("").is_none());
    }

    #[test]
    fn test_cron_job_deserialization_with_camel_case_dates() {
        let json = r#"{
            "id": "test-id",
            "name": "test job",
            "schedule": "* * * * *",
            "command": "echo hi",
            "enabled": true,
            "lastRun": "2024-01-15T10:30:00Z",
            "nextRun": null
        }"#;
        let job: CronJob = serde_json::from_str(json).unwrap();
        assert_eq!(job.id, "test-id");
        assert!(job.last_run.is_some());
        assert!(job.next_run.is_none());
    }

    #[test]
    fn test_tmux_session_serialization() {
        let session = TmuxSession {
            name: "test".to_string(),
            attached: true,
            created: chrono::Utc::now(),
            windows: 2,
            dimensions: "80x24".to_string(),
            tool: None,
        };
        let json = serde_json::to_string(&session).unwrap();
        assert!(json.contains("\"name\":\"test\""));
        assert!(json.contains("\"attached\":true"));
    }

    #[test]
    fn test_websocket_message_deserialization() {
        let json = r#"{"type":"list-sessions"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        assert!(matches!(msg, WebSocketMessage::ListSessions));
    }

    #[test]
    fn test_server_message_pong_serialization() {
        let msg = ServerMessage::Pong;
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("pong"));
    }

    #[test]
    fn test_attach_session_deser() {
        let json = r#"{"type":"attach-session","sessionName":"my-sess","cols":80,"rows":24}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::AttachSession {
                session_name,
                cols,
                rows,
                ..
            } => {
                assert_eq!(session_name, "my-sess");
                assert_eq!(cols, 80);
                assert_eq!(rows, 24);
            }
            _ => panic!("Expected AttachSession"),
        }
    }

    #[test]
    fn test_send_chat_message_deser_with_notify() {
        let json = r#"{"type":"send-chat-message","sessionName":"s","windowIndex":0,"message":"hi","notify":true}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::SendChatMessage {
                message, notify, ..
            } => {
                assert_eq!(message, "hi");
                assert_eq!(notify, Some(true));
            }
            _ => panic!("Expected SendChatMessage"),
        }
        // Also test without notify field (should default)
        let json2 =
            r#"{"type":"send-chat-message","sessionName":"s","windowIndex":0,"message":"hi"}"#;
        let msg2: WebSocketMessage = serde_json::from_str(json2).unwrap();
        match msg2 {
            WebSocketMessage::SendChatMessage { notify, .. } => assert_eq!(notify, None),
            _ => panic!("Expected SendChatMessage"),
        }
    }

    #[test]
    fn test_server_message_error_ser() {
        let msg = ServerMessage::Error {
            message: "something went wrong".to_string(),
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"type\":\"error\""));
        assert!(json.contains("something went wrong"));
    }

    #[test]
    fn test_server_message_chat_history_ser() {
        let msg = ServerMessage::ChatHistory {
            session_name: "test".to_string(),
            window_index: 0,
            messages: vec![],
            tool: None,
            has_more: false,
            total_count: 0,
            context_window_usage: None,
            model_name: None,
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("chat-history"));
        assert!(json.contains("\"sessionName\":\"test\""));
    }

    #[test]
    fn test_file_attachment_roundtrip() {
        let attachment = FileAttachment {
            filename: "test.png".to_string(),
            mime_type: "image/png".to_string(),
            data: "base64data".to_string(),
        };
        let json = serde_json::to_string(&attachment).unwrap();
        assert!(json.contains("\"mimeType\":\"image/png\""));
        let parsed: FileAttachment = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.filename, "test.png");
        assert_eq!(parsed.mime_type, "image/png");
    }

    // Phase 2: Additional WebSocketMessage deserialization tests

    #[test]
    fn test_input_deser() {
        let json = r#"{"type":"input","data":"hello"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::Input { data } => assert_eq!(data, "hello"),
            _ => panic!("Expected Input"),
        }
    }

    #[test]
    fn test_resize_deser() {
        let json = r#"{"type":"resize","cols":120,"rows":40}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::Resize { cols, rows } => {
                assert_eq!(cols, 120);
                assert_eq!(rows, 40);
            }
            _ => panic!("Expected Resize"),
        }
    }

    #[test]
    fn test_ping_deser() {
        let json = r#"{"type":"ping"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        assert!(matches!(msg, WebSocketMessage::Ping));
    }

    #[test]
    fn test_get_stats_deser() {
        let json = r#"{"type":"get-stats"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        assert!(matches!(msg, WebSocketMessage::GetStats));
    }

    #[test]
    fn test_send_enter_key_deser() {
        let json = r#"{"type":"send-enter-key"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        assert!(matches!(msg, WebSocketMessage::SendEnterKey));
    }

    #[test]
    fn test_create_session_with_name() {
        let json = r#"{"type":"create-session","name":"my-sess"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::CreateSession { name, .. } => {
                assert_eq!(name, Some("my-sess".to_string()))
            }
            _ => panic!("Expected CreateSession"),
        }
    }

    #[test]
    fn test_create_session_without_name() {
        let json = r#"{"type":"create-session"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::CreateSession { name, .. } => assert_eq!(name, None),
            _ => panic!("Expected CreateSession"),
        }
    }

    #[test]
    fn test_kill_session_deser() {
        let json = r#"{"type":"kill-session","sessionName":"doomed"}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::KillSession { session_name } => assert_eq!(session_name, "doomed"),
            _ => panic!("Expected KillSession"),
        }
    }

    #[test]
    fn test_watch_chat_log_deser() {
        let json = r#"{"type":"watch-chat-log","sessionName":"s","windowIndex":1,"limit":50}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::WatchChatLog {
                session_name,
                window_index,
                limit,
            } => {
                assert_eq!(session_name, "s");
                assert_eq!(window_index, 1);
                assert_eq!(limit, Some(50));
            }
            _ => panic!("Expected WatchChatLog"),
        }
    }

    #[test]
    fn test_load_more_chat_history_deser() {
        let json = r#"{"type":"load-more-chat-history","sessionName":"s","windowIndex":0,"offset":10,"limit":20}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::LoadMoreChatHistory {
                session_name,
                window_index,
                offset,
                limit,
            } => {
                assert_eq!(session_name, "s");
                assert_eq!(window_index, 0);
                assert_eq!(offset, 10);
                assert_eq!(limit, 20);
            }
            _ => panic!("Expected LoadMoreChatHistory"),
        }
    }

    #[test]
    fn test_parse_datetime_with_timezone_offset() {
        let result = parse_datetime_lenient("2024-06-15T10:30:00+05:00");
        assert!(result.is_some());
    }

    #[test]
    fn test_server_message_session_created_skip_none() {
        let msg = ServerMessage::SessionCreated {
            success: true,
            session_name: Some("new-sess".to_string()),
            error: None,
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"sessionName\":\"new-sess\""));
        assert!(!json.contains("\"error\""));
    }

    #[test]
    fn test_server_message_window_selected_with_error_none() {
        let msg = ServerMessage::WindowSelected {
            success: true,
            window_index: Some(2),
            error: None,
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"windowIndex\":2"));
        assert!(!json.contains("\"error\""));
    }

    #[test]
    fn test_create_cron_job_deser() {
        let json = r#"{"type":"create-cron-job","job":{"id":"","name":"test","schedule":"* * * * *","command":"echo hi","enabled":true}}"#;
        let msg: WebSocketMessage = serde_json::from_str(json).unwrap();
        match msg {
            WebSocketMessage::CreateCronJob { job } => {
                assert_eq!(job.name, "test");
                assert_eq!(job.command, "echo hi");
            }
            _ => panic!("Expected CreateCronJob"),
        }
    }
}
