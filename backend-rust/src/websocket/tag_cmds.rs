use anyhow::Result;
use std::sync::Arc;
use tokio::sync::mpsc;

use crate::{types::ServerMessage, AppState};

use super::types::{send_message, BroadcastMessage};

pub async fn handle_get_tags(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
) -> Result<()> {
    let tags = app_state.tag_store.list_tags().unwrap_or_default();
    send_message(tx, ServerMessage::TagsList { tags }).await
}

pub async fn handle_add_tag(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    name: String,
    color_hex: String,
) -> Result<()> {
    match app_state.tag_store.add_tag(name, color_hex) {
        Ok(tag) => send_message(tx, ServerMessage::TagAdded { tag }).await?,
        Err(e) => tracing::error!("Failed to add tag: {}", e),
    }
    Ok(())
}

pub async fn handle_delete_tag(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    id: String,
) -> Result<()> {
    let success = app_state.tag_store.delete_tag(&id).unwrap_or(false);
    send_message(tx, ServerMessage::TagDeleted { id, success }).await
}

pub async fn handle_get_tag_assignments(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
) -> Result<()> {
    let assignments = app_state.tag_store.list_assignments().unwrap_or_default();
    send_message(tx, ServerMessage::TagAssignmentsList { assignments }).await
}

pub async fn handle_assign_tag_to_session(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    session_name: String,
    tag_id: String,
) -> Result<()> {
    if let Err(e) = app_state.tag_store.assign_tag(&session_name, &tag_id) {
        tracing::error!("Failed to assign tag: {}", e);
    } else {
        send_message(
            tx,
            ServerMessage::TagAssignmentUpdated {
                session_name,
                tag_id,
                assigned: true,
            },
        )
        .await?;
    }
    Ok(())
}

pub async fn handle_remove_tag_from_session(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    session_name: String,
    tag_id: String,
) -> Result<()> {
    if let Err(e) = app_state
        .tag_store
        .remove_tag_from_session(&session_name, &tag_id)
    {
        tracing::error!("Failed to remove tag: {}", e);
    } else {
        send_message(
            tx,
            ServerMessage::TagAssignmentUpdated {
                session_name,
                tag_id,
                assigned: false,
            },
        )
        .await?;
    }
    Ok(())
}
