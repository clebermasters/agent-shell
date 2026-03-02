use std::path::Path;
use anyhow::{Context, Result};
use chrono::{TimeZone, Utc};
use rusqlite::Connection;
use serde::Deserialize;
use serde_json::Value;
use tracing::{warn, debug};

use super::{AiTool, ChatMessage, ContentBlock};

use std::collections::{HashMap, HashSet};

#[derive(Debug)]
pub struct OpencodeState {
    pub session_id: String,
    pub last_time_updated: i64,
    pub seen_text_lengths: HashMap<String, usize>,
    pub seen_tool_calls: HashSet<String>,
    pub seen_tool_results: HashSet<String>,
}

#[derive(Deserialize, Debug)]
struct PartData {
    #[serde(rename = "type")]
    part_type: String,
    
    // For text parts
    text: Option<String>,
    
    // For tool parts
    tool: Option<String>,
    state: Option<ToolState>,
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct ToolState {
    status: Option<String>,
    input: Option<Value>,
    output: Option<Value>,
}

/// Start an opencode session tracking state
pub fn init_opencode_state(db_path: &Path, directory: &Path) -> Result<OpencodeState> {
    let conn = Connection::open(db_path)?;
    let dir_str = directory.to_str().context("invalid directory path")?;
    
    // Opencode stores the directory in the Session table. 
    // We try to find the most recently created session for this directory.
    let mut stmt = conn.prepare("SELECT id FROM session WHERE directory = ? ORDER BY time_created DESC LIMIT 1")?;
    
    let session_id: String = stmt.query_row([dir_str], |row| row.get(0))
        .context(format!("No opencode session found for directory: {}", dir_str))?;
        
    debug!("Found Opencode session: {}", session_id);
    
    Ok(OpencodeState {
        session_id,
        last_time_updated: 0,
        seen_text_lengths: HashMap::new(),
        seen_tool_calls: HashSet::new(),
        seen_tool_results: HashSet::new(),
    })
}

/// Fetch all new messages since the last fetch
pub fn fetch_new_messages(db_path: &Path, state: &mut OpencodeState) -> Result<Vec<ChatMessage>> {
    let conn = Connection::open(db_path)?;
    
    let mut stmt = conn.prepare(
        "SELECT p.id, p.data, p.time_updated, json_extract(m.data, '$.role') 
         FROM part p
         JOIN message m ON p.message_id = m.id
         WHERE p.session_id = ? AND p.time_updated > ?
         ORDER BY p.time_updated ASC"
    )?;
    
    let mut rows = stmt.query(rusqlite::params![state.session_id, state.last_time_updated])?;
    
    let mut messages = Vec::new();
    let mut new_last_time_updated = state.last_time_updated;
    
    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        let data: String = row.get(1)?;
        let time_updated: i64 = row.get(2)?;
        let parsed_role: Option<String> = row.get(3)?;
        
        let role = parsed_role.unwrap_or_else(|| "assistant".to_string());
        
        if time_updated > new_last_time_updated {
            new_last_time_updated = time_updated;
        }
        
        match serde_json::from_str::<PartData>(&data) {
            Ok(part) => {
                if let Some(msg) = parse_part(&id, &part, time_updated, state, &role) {
                    messages.push(msg);
                }
            }
            Err(e) => {
                warn!("Failed to deserialize opencode part data: {} - error: {}", data, e);
            }
        }
    }
    
    state.last_time_updated = new_last_time_updated;
    Ok(messages)
}

fn parse_part(id: &str, part: &PartData, time_updated: i64, state: &mut OpencodeState, message_role: &str) -> Option<ChatMessage> {
    let timestamp = Utc.timestamp_millis_opt(time_updated).single();
    
    let mut final_role = message_role.to_string();
    if part.part_type != "text" {
        final_role = "tool".to_string();
    }
    
    let block = match part.part_type.as_str() {
        "text" => {
            let full_text = part.text.as_deref().unwrap_or_default();
            let last_len = state.seen_text_lengths.get(id).copied().unwrap_or(0);
            
            if full_text.len() <= last_len {
                return None; // no new text to send
            }
            
            let new_chunk = &full_text[last_len..];
            state.seen_text_lengths.insert(id.to_string(), full_text.len());
            
            ContentBlock::Text {
                text: new_chunk.to_string(),
            }
        }
        "tool" => {
            let status = part.state.as_ref().and_then(|s| s.status.as_deref()).unwrap_or("unknown");
            let tool_name = part.tool.clone().unwrap_or_else(|| "unknown".to_string());
            
            if status != "completed" {
                if !state.seen_tool_calls.insert(id.to_string()) {
                    return None; // already announced this running tool
                }
                
                let input = part.state.as_ref().and_then(|s| s.input.clone());
                ContentBlock::ToolCall {
                    name: tool_name.clone(),
                    summary: format!("Calling tool {}", tool_name),
                    input,
                }
            } else {
                if !state.seen_tool_results.insert(id.to_string()) {
                    return None; // already announced this completed result
                }
                
                let content_str = match part.state.as_ref().and_then(|s| s.output.as_ref()) {
                    Some(Value::String(s)) => Some(s.clone()),
                    Some(v) => Some(v.to_string()),
                    None => None,
                };
                
                ContentBlock::ToolResult {
                    tool_name: tool_name.clone(),
                    summary: format!("Tool {} finished", tool_name),
                    content: content_str,
                }
            }
        }
        _ => return None, // e.g., patch, snapshot, reasoning, etc. we skip formatting those as simple chat events for now
    };
    
    Some(ChatMessage {
        role: final_role,
        timestamp,
        blocks: vec![block],
    })
}
