use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: Option<usize>,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
}

impl JsonRpcRequest {
    pub fn new(method: &str) -> Self {
        Self {
            jsonrpc: "2.0".to_string(),
            id: None,
            method: method.to_string(),
            params: None,
        }
    }

    pub fn with_id(mut self, id: usize) -> Self {
        self.id = Some(id);
        self
    }

    pub fn with_params<T: Serialize>(mut self, params: T) -> Self {
        self.params = serde_json::to_value(params).ok();
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcError {
    pub code: i32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcNotification {
    pub jsonrpc: String,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum JsonRpcMessage {
    Request(JsonRpcRequest),
    Response(JsonRpcResponse),
    Notification(JsonRpcNotification),
}

pub fn parse_jsonrpc_message(line: &str) -> Option<JsonRpcMessage> {
    let msg: JsonRpcMessage = serde_json::from_str(line).ok()?;
    Some(msg)
}
