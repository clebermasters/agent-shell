use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InitializeResult {
    #[serde(rename = "protocolVersion")]
    pub protocol_version: usize,
    #[serde(rename = "capabilities", skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<ServerCapabilities>,
    #[serde(rename = "serverInfo", skip_serializing_if = "Option::is_none")]
    pub server_info: Option<ServerInfo>,
    #[serde(rename = "authMethods", skip_serializing_if = "Vec::is_empty", default)]
    pub auth_methods: Vec<AuthMethod>,
    #[serde(rename = "agentCapabilities", skip_serializing_if = "Option::is_none")]
    pub agent_capabilities: Option<AgentCapabilities>,
    #[serde(rename = "agentInfo")]
    pub agent_info: AgentInfo,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ServerCapabilities {
    #[serde(default)]
    pub tools: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerInfo {
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthMethod {
    pub id: String,
    pub name: String,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentCapabilities {
    #[serde(rename = "loadSession", default)]
    pub load_session: bool,
    #[serde(rename = "mcpCapabilities", skip_serializing_if = "Option::is_none")]
    pub mcp_capabilities: Option<McpCapabilities>,
    #[serde(rename = "promptCapabilities", skip_serializing_if = "Option::is_none")]
    pub prompt_capabilities: Option<PromptCapabilities>,
    #[serde(
        rename = "sessionCapabilities",
        skip_serializing_if = "Option::is_none"
    )]
    pub session_capabilities: Option<SessionCapabilities>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpCapabilities {
    #[serde(default)]
    pub http: bool,
    #[serde(default)]
    pub sse: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptCapabilities {
    #[serde(rename = "embeddedContext", default)]
    pub embedded_context: bool,
    #[serde(default)]
    pub image: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionCapabilities {
    #[serde(default)]
    pub fork: serde_json::Value,
    #[serde(default)]
    pub list: serde_json::Value,
    #[serde(default)]
    pub resume: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentInfo {
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateSessionResult {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    #[serde(default)]
    pub models: Option<ModelsInfo>,
    #[serde(default)]
    pub modes: Option<ModesInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelsInfo {
    #[serde(rename = "currentModelId")]
    pub current_model_id: String,
    #[serde(rename = "availableModels")]
    pub available_models: Vec<ModelInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelInfo {
    #[serde(rename = "modelId")]
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModesInfo {
    #[serde(rename = "availableModes")]
    pub available_modes: Vec<ModeInfo>,
    #[serde(rename = "currentModeId")]
    pub current_mode_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModeInfo {
    pub id: String,
    pub name: String,
    pub description: String,
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ListSessionsResult {
    pub sessions: Vec<SessionInfo>,
    #[serde(rename = "nextCursor", skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    pub cwd: String,
    pub title: String,
    #[serde(rename = "updatedAt")]
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptResult {
    #[serde(rename = "stopReason", skip_serializing_if = "Option::is_none")]
    pub stop_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<UsageInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageInfo {
    #[serde(rename = "totalTokens")]
    pub total_tokens: usize,
    #[serde(rename = "inputTokens")]
    pub input_tokens: usize,
    #[serde(rename = "outputTokens")]
    pub output_tokens: usize,
    #[serde(rename = "thoughtTokens", skip_serializing_if = "Option::is_none")]
    pub thought_tokens: Option<usize>,
}

