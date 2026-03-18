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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json;

    #[test]
    fn test_initialize_result_all_fields() {
        let json = r#"{
            "protocolVersion": 1,
            "capabilities": { "tools": {} },
            "serverInfo": { "name": "test", "version": "1.0" },
            "authMethods": [{ "id": "a", "name": "Auth", "description": "desc" }],
            "agentCapabilities": {
                "loadSession": true,
                "mcpCapabilities": { "http": true, "sse": false },
                "promptCapabilities": { "embeddedContext": true, "image": false },
                "sessionCapabilities": { "fork": true, "list": true, "resume": true }
            },
            "agentInfo": { "name": "agent", "version": "2.0" }
        }"#;
        let result: InitializeResult = serde_json::from_str(json).unwrap();
        assert_eq!(result.protocol_version, 1);
        assert!(result.capabilities.is_some());
        assert!(result.server_info.is_some());
        assert_eq!(result.auth_methods.len(), 1);
        assert!(result.agent_capabilities.is_some());
        assert_eq!(result.agent_info.name, "agent");
    }

    #[test]
    fn test_initialize_result_optional_fields_absent() {
        let json = r#"{
            "protocolVersion": 2,
            "agentInfo": { "name": "minimal", "version": "0.1" }
        }"#;
        let result: InitializeResult = serde_json::from_str(json).unwrap();
        assert_eq!(result.protocol_version, 2);
        assert!(result.capabilities.is_none());
        assert!(result.server_info.is_none());
        assert!(result.auth_methods.is_empty());
        assert!(result.agent_capabilities.is_none());
    }

    #[test]
    fn test_initialize_result_roundtrip() {
        let original = InitializeResult {
            protocol_version: 1,
            capabilities: Some(ServerCapabilities { tools: serde_json::json!({}) }),
            server_info: Some(ServerInfo { name: "srv".into(), version: "1.0".into() }),
            auth_methods: vec![],
            agent_capabilities: None,
            agent_info: AgentInfo { name: "a".into(), version: "1".into() },
        };
        let json = serde_json::to_string(&original).unwrap();
        let parsed: InitializeResult = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.protocol_version, 1);
        assert!(parsed.server_info.is_some());
        // auth_methods empty => skipped in serialization
        assert!(parsed.auth_methods.is_empty());
    }

    #[test]
    fn test_create_session_result_with_models_and_modes() {
        let json = r#"{
            "sessionId": "sess-123",
            "models": {
                "currentModelId": "gpt-4",
                "availableModels": [
                    { "modelId": "gpt-4", "name": "GPT-4" },
                    { "modelId": "gpt-3.5", "name": "GPT-3.5" }
                ]
            },
            "modes": {
                "availableModes": [{ "id": "code", "name": "Code", "description": "Coding mode" }],
                "currentModeId": "code"
            }
        }"#;
        let result: CreateSessionResult = serde_json::from_str(json).unwrap();
        assert_eq!(result.session_id, "sess-123");
        let models = result.models.unwrap();
        assert_eq!(models.current_model_id, "gpt-4");
        assert_eq!(models.available_models.len(), 2);
        let modes = result.modes.unwrap();
        assert_eq!(modes.current_mode_id, "code");
    }

    #[test]
    fn test_model_info_rename() {
        let json = r#"{ "modelId": "claude-3", "name": "Claude 3" }"#;
        let info: ModelInfo = serde_json::from_str(json).unwrap();
        assert_eq!(info.id, "claude-3");
        assert_eq!(info.name, "Claude 3");
        let serialized = serde_json::to_string(&info).unwrap();
        assert!(serialized.contains("\"modelId\""));
    }

    #[test]
    fn test_session_info_renames() {
        let json = r#"{
            "sessionId": "s1",
            "cwd": "/home",
            "title": "My Session",
            "updatedAt": "2024-01-15T10:00:00Z"
        }"#;
        let info: SessionInfo = serde_json::from_str(json).unwrap();
        assert_eq!(info.session_id, "s1");
        assert_eq!(info.updated_at, "2024-01-15T10:00:00Z");
    }

    #[test]
    fn test_prompt_result_with_usage() {
        let json = r#"{
            "stopReason": "end_turn",
            "usage": {
                "totalTokens": 1000,
                "inputTokens": 500,
                "outputTokens": 500,
                "thoughtTokens": 100
            }
        }"#;
        let result: PromptResult = serde_json::from_str(json).unwrap();
        assert_eq!(result.stop_reason, Some("end_turn".to_string()));
        let usage = result.usage.unwrap();
        assert_eq!(usage.total_tokens, 1000);
        assert_eq!(usage.thought_tokens, Some(100));
    }

    #[test]
    fn test_prompt_result_without_usage() {
        let json = r#"{}"#;
        let result: PromptResult = serde_json::from_str(json).unwrap();
        assert!(result.stop_reason.is_none());
        assert!(result.usage.is_none());
    }

    #[test]
    fn test_list_sessions_result_with_cursor() {
        let json = r#"{
            "sessions": [{ "sessionId": "s1", "cwd": "/", "title": "t", "updatedAt": "now" }],
            "nextCursor": "abc"
        }"#;
        let result: ListSessionsResult = serde_json::from_str(json).unwrap();
        assert_eq!(result.sessions.len(), 1);
        assert_eq!(result.next_cursor, Some("abc".to_string()));
    }

    #[test]
    fn test_list_sessions_result_skip_serializing_cursor() {
        let result = ListSessionsResult {
            sessions: vec![],
            next_cursor: None,
        };
        let json = serde_json::to_string(&result).unwrap();
        assert!(!json.contains("nextCursor"));
    }

    #[test]
    fn test_modes_info_default() {
        let modes = ModesInfo::default();
        assert!(modes.available_modes.is_empty());
        assert!(modes.current_mode_id.is_empty());
        let json = serde_json::to_string(&modes).unwrap();
        assert!(json.contains("availableModes"));
    }

    #[test]
    fn test_agent_capabilities_nested_optionals() {
        let json = r#"{ "loadSession": false }"#;
        let caps: AgentCapabilities = serde_json::from_str(json).unwrap();
        assert!(!caps.load_session);
        assert!(caps.mcp_capabilities.is_none());
        assert!(caps.prompt_capabilities.is_none());
        assert!(caps.session_capabilities.is_none());
    }
}

