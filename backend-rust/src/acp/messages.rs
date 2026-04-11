use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: serde_json::Value,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: serde_json::Value,
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
    if !line.starts_with('{') {
        tracing::debug!("ACP stdout (non-JSON): {}", line);
        return None;
    }
    match serde_json::from_str::<JsonRpcMessage>(line) {
        Ok(msg) => Some(msg),
        Err(e) => {
            tracing::error!("Failed to parse JSON-RPC message: {} - Line: {}", e, line);
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_parse_jsonrpc_request() {
        let line = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Request(req) => {
                assert_eq!(req.method, "initialize");
                assert_eq!(req.jsonrpc, "2.0");
            }
            _ => panic!("Expected Request"),
        }
    }

    #[test]
    fn test_parse_jsonrpc_response() {
        let line = r#"{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Response(resp) => {
                assert_eq!(resp.id, json!(1));
                assert!(resp.result.is_some());
            }
            _ => panic!("Expected Response"),
        }
    }

    #[test]
    fn test_parse_jsonrpc_notification() {
        let line = r#"{"jsonrpc":"2.0","method":"update","params":{"x":1}}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Notification(notif) => {
                assert_eq!(notif.method, "update");
            }
            _ => panic!("Expected Notification"),
        }
    }

    #[test]
    fn test_parse_non_json_returns_none() {
        assert!(parse_jsonrpc_message("not json").is_none());
        assert!(parse_jsonrpc_message("").is_none());
    }

    #[test]
    fn test_parse_invalid_json_returns_none() {
        assert!(parse_jsonrpc_message("{invalid}").is_none());
    }

    #[test]
    fn test_jsonrpc_error_serialization() {
        let err = JsonRpcError {
            code: -32601,
            message: "Method not found".to_string(),
            data: None,
        };
        let json_str = serde_json::to_string(&err).unwrap();
        assert!(json_str.contains("Method not found"));
        assert!(json_str.contains("-32601"));
    }

    #[test]
    fn test_jsonrpc_request_no_params() {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: json!(42),
            method: "ping".to_string(),
            params: None,
        };
        let json_str = serde_json::to_string(&req).unwrap();
        assert!(!json_str.contains("params")); // skip_serializing_if
    }

    // Phase 3: JSON-RPC edge cases

    #[test]
    fn test_response_with_error_no_result() {
        let line =
            r#"{"jsonrpc":"2.0","id":5,"error":{"code":-32600,"message":"Invalid Request"}}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Response(resp) => {
                assert!(resp.result.is_none());
                let err = resp.error.unwrap();
                assert_eq!(err.code, -32600);
                assert_eq!(err.message, "Invalid Request");
            }
            _ => panic!("Expected Response"),
        }
    }

    #[test]
    fn test_notification_with_null_params() {
        let line = r#"{"jsonrpc":"2.0","method":"heartbeat","params":null}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Notification(notif) => {
                assert_eq!(notif.method, "heartbeat");
                assert!(notif.params.is_none() || notif.params == Some(serde_json::Value::Null));
            }
            _ => panic!("Expected Notification"),
        }
    }

    #[test]
    fn test_request_with_string_id() {
        let line = r#"{"jsonrpc":"2.0","id":"req-abc","method":"test","params":{}}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        match msg {
            JsonRpcMessage::Request(req) => {
                assert_eq!(req.id, json!("req-abc"));
                assert_eq!(req.method, "test");
            }
            _ => panic!("Expected Request"),
        }
    }

    #[test]
    fn test_message_with_id_and_method_is_request() {
        // Has both id and method => untagged tries Request first
        let line = r#"{"jsonrpc":"2.0","id":99,"method":"doSomething"}"#;
        let msg = parse_jsonrpc_message(line).unwrap();
        assert!(matches!(msg, JsonRpcMessage::Request(_)));
    }
}
