//! Opt-in POC delivery of complete UI documents. No inference providers are used.
use crate::{
    chat_log::{ChatMessage, ContentBlock},
    types::ServerMessage,
    AppState,
};
use axum::{extract::State, http::StatusCode, Json};
use serde::Deserialize;
use std::sync::Arc;

pub(crate) fn enabled() -> bool {
    std::env::var("AGENTSHELL_UI_POC").as_deref() == Ok("1")
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublishWidget {
    pub pane_id: String,
    pub agent_pid: u32,
    pub agent_start: u64,
    pub title: String,
    pub html: String,
}

pub async fn publish(
    State(app): State<Arc<AppState>>,
    Json(payload): Json<PublishWidget>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    if !enabled() {
        return Err((StatusCode::NOT_FOUND, "UI POC is disabled".into()));
    }
    let result = async {
        anyhow::ensure!(!payload.title.trim().is_empty() && payload.title.len() <= 160, "Invalid widget title");
        anyhow::ensure!(!payload.html.trim().is_empty() && payload.html.len() <= 256 * 1024, "Widget document must contain 1–262144 bytes");
        let pane = crate::chat_binding::inspect_pane(&payload.pane_id).await?;
        anyhow::ensure!(pane.agent_pid == payload.agent_pid && pane.agent_start == payload.agent_start, "Publisher is not this pane's current agent");
        let binding = app.chat_event_store.bindings.resolve_inspected_pane(pane).await?.binding
            .ok_or_else(|| anyhow::anyhow!("The agent has not reported its native conversation ID"))?;
        app.chat_event_store.bindings.validate(&binding).await?;
        let output = tokio::process::Command::new("tmux").args(["display-message", "-p", "-t", &binding.pane.pane_id, "#{session_name}\t#{window_index}"]).kill_on_drop(true).output().await?;
        anyhow::ensure!(output.status.success(), "Terminal disappeared");
        let location = String::from_utf8(output.stdout)?;
        let (session, window) = location.trim().split_once('\t').ok_or_else(|| anyhow::anyhow!("Invalid terminal location"))?;
        let id = uuid::Uuid::new_v4().to_string();
        let message = ChatMessage { role: "assistant".into(), timestamp: Some(chrono::Utc::now()), blocks: vec![ContentBlock::UiWidget { id: id.clone(), title: payload.title, html: payload.html }] };
        let store = app.chat_event_store.clone();
        let stored = message.clone();
        let key = binding.conversation_key.clone();
        tokio::task::spawn_blocking(move || store.append_message(&key, 0, "ui-poc", &stored)).await??;
        app.client_manager.broadcast_bound(ServerMessage::ChatEvent { session_name: session.into(), window_index: window.parse()?, message, source: Some("ui-poc".into()) }, &binding).await;
        Ok::<_, anyhow::Error>(Json(serde_json::json!({"widgetId":id,"conversationKey":binding.conversation_key,"bindingId":binding.binding_id})))
    }.await;
    result.map_err(|error| (StatusCode::CONFLICT, error.to_string()))
}
