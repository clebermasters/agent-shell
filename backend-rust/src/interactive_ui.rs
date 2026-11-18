//! Authenticated, conversation-scoped interactive UI publication.
use crate::{
    chat_log::{ChatMessage, ContentBlock},
    types::ServerMessage,
    AppState,
};
use axum::{extract::State, http::StatusCode, Json};
use serde::Deserialize;
use std::sync::Arc;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublishWidget {
    #[serde(default)]
    pub pane_id: Option<String>,
    #[serde(default)]
    pub codex_thread_id: Option<String>,
    pub agent_pid: u32,
    pub agent_start: u64,
    pub title: String,
    pub html: String,
}

pub async fn publish(
    State(app): State<Arc<AppState>>,
    Json(payload): Json<PublishWidget>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let result = async {
        anyhow::ensure!(!payload.title.trim().is_empty() && payload.title.len() <= 160, "Invalid widget title");
        anyhow::ensure!(!payload.html.trim().is_empty() && payload.html.len() <= 256 * 1024, "Widget document must contain 1–262144 bytes");
        let target = crate::chat_publication::resolve_publisher(&app, payload.pane_id.as_deref(), payload.codex_thread_id.as_deref(), payload.agent_pid, payload.agent_start).await?;
        let binding = target.binding;
        let id = uuid::Uuid::new_v4().to_string();
        let message = ChatMessage { role: "assistant".into(), timestamp: Some(chrono::Utc::now()), blocks: vec![ContentBlock::UiWidget { id: id.clone(), title: payload.title, html: payload.html }] };
        let store = app.chat_event_store.clone();
        let stored = message.clone();
        let key = binding.conversation_key.clone();
        tokio::task::spawn_blocking(move || store.append_message(&key, 0, "interactive-ui", &stored)).await??;
        app.client_manager.broadcast_bound(ServerMessage::ChatEvent { session_name: target.session_name, window_index: target.window_index, message, source: Some("interactive-ui".into()) }, &binding).await;
        Ok::<_, anyhow::Error>(Json(serde_json::json!({"widgetId":id,"conversationKey":binding.conversation_key,"bindingId":binding.binding_id})))
    }.await;
    result.map_err(|error| (StatusCode::CONFLICT, error.to_string()))
}
