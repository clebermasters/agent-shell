use anyhow::Result;
use std::sync::Arc;
use tokio::sync::mpsc;

use crate::{types::ServerMessage, AppState};

use super::types::{send_message, BroadcastMessage};

pub async fn handle_get_favorites(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
) -> Result<()> {
    let favorites = app_state.favorite_store.list().unwrap_or_default();
    send_message(tx, ServerMessage::FavoritesList { favorites }).await
}

pub async fn handle_add_favorite(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    name: String,
    path: String,
    sort_order: i32,
    startup_command: Option<String>,
    startup_args: Option<String>,
    tag_ids: Vec<String>,
) -> Result<()> {
    match app_state.favorite_store.add(
        name,
        path,
        sort_order,
        startup_command,
        startup_args,
        tag_ids,
    ) {
        Ok(favorite) => send_message(tx, ServerMessage::FavoriteAdded { favorite }).await?,
        Err(e) => tracing::error!("Failed to add favorite: {}", e),
    }
    Ok(())
}

pub async fn handle_update_favorite(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    id: String,
    name: String,
    path: String,
    sort_order: i32,
    startup_command: Option<String>,
    startup_args: Option<String>,
    tag_ids: Vec<String>,
) -> Result<()> {
    match app_state.favorite_store.update(
        &id,
        name,
        path,
        sort_order,
        startup_command,
        startup_args,
        tag_ids,
    ) {
        Ok(Some(favorite)) => send_message(tx, ServerMessage::FavoriteUpdated { favorite }).await?,
        Ok(None) => tracing::warn!("Favorite not found for update: {}", id),
        Err(e) => tracing::error!("Failed to update favorite: {}", e),
    }
    Ok(())
}

pub async fn handle_delete_favorite(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    id: String,
) -> Result<()> {
    let success = app_state.favorite_store.delete(&id).unwrap_or(false);
    send_message(tx, ServerMessage::FavoriteDeleted { id, success }).await
}

pub async fn handle_set_favorite_tags(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    app_state: Arc<AppState>,
    favorite_id: String,
    tag_ids: Vec<String>,
) -> Result<()> {
    match app_state.favorite_store.set_tags_for(&favorite_id, tag_ids) {
        Ok(tag_ids) => {
            send_message(
                tx,
                ServerMessage::FavoriteTagsUpdated {
                    favorite_id,
                    tag_ids,
                },
            )
            .await?
        }
        Err(e) => tracing::error!("Failed to set favorite tags: {}", e),
    }
    Ok(())
}
