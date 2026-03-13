use std::{collections::HashMap, sync::Arc};
use tokio::sync::{mpsc, RwLock};
use tracing::{error, info};

use super::types::{BroadcastMessage, ClientId};
use crate::types::ServerMessage;

/// Manages all connected WebSocket clients and handles broadcasting.
pub struct ClientManager {
    clients: Arc<RwLock<HashMap<ClientId, mpsc::UnboundedSender<BroadcastMessage>>>>,
}

impl ClientManager {
    pub fn new() -> Self {
        Self {
            clients: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn add_client(
        &self,
        client_id: ClientId,
        tx: mpsc::UnboundedSender<BroadcastMessage>,
    ) {
        let mut clients = self.clients.write().await;
        clients.insert(client_id, tx);
        info!("Client added. Total clients: {}", clients.len());
    }

    pub async fn remove_client(&self, client_id: &str) {
        let mut clients = self.clients.write().await;
        clients.remove(client_id);
        info!("Client removed. Total clients: {}", clients.len());
    }

    pub async fn client_count(&self) -> usize {
        let clients = self.clients.read().await;
        clients.len()
    }

    pub async fn broadcast(&self, message: ServerMessage) {
        // Serialize once for all clients
        if let Ok(serialized) = serde_json::to_string(&message) {
            let msg = BroadcastMessage::Text(Arc::new(serialized));
            let clients = self.clients.read().await;
            for (client_id, tx) in clients.iter() {
                if let Err(e) = tx.send(msg.clone()) {
                    error!("Failed to send to client {}: {}", client_id, e);
                }
            }
        }
    }

    pub async fn broadcast_binary(&self, data: bytes::Bytes) {
        let msg = BroadcastMessage::Binary(data);
        let clients = self.clients.read().await;
        for (client_id, tx) in clients.iter() {
            if let Err(e) = tx.send(msg.clone()) {
                error!("Failed to send binary to client {}: {}", client_id, e);
            }
        }
    }
}
