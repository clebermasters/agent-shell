use std::{collections::HashMap, sync::Arc};
use tokio::sync::{mpsc, RwLock};
use tracing::{error, info};

use super::types::{BroadcastMessage, ClientId};
use crate::types::ServerMessage;

/// Manages all connected WebSocket clients and handles broadcasting.
pub struct ClientManager {
    clients: Arc<RwLock<HashMap<ClientId, mpsc::Sender<BroadcastMessage>>>>,
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
        tx: mpsc::Sender<BroadcastMessage>,
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
                if let Err(e) = tx.send(msg.clone()).await {
                    error!("Failed to send to client {}: {}", client_id, e);
                }
            }
        }
    }

    pub async fn broadcast_binary(&self, data: bytes::Bytes) {
        let msg = BroadcastMessage::Binary(data);
        let clients = self.clients.read().await;
        for (client_id, tx) in clients.iter() {
            if let Err(e) = tx.send(msg.clone()).await {
                error!("Failed to send binary to client {}: {}", client_id, e);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_new_manager_empty() {
        let mgr = ClientManager::new();
        assert_eq!(mgr.client_count().await, 0);
    }

    #[tokio::test]
    async fn test_add_client() {
        let mgr = ClientManager::new();
        let (tx, _rx) = mpsc::channel(256);
        mgr.add_client("client1".to_string(), tx).await;
        assert_eq!(mgr.client_count().await, 1);
    }

    #[tokio::test]
    async fn test_remove_client() {
        let mgr = ClientManager::new();
        let (tx, _rx) = mpsc::channel(256);
        mgr.add_client("client1".to_string(), tx).await;
        mgr.remove_client("client1").await;
        assert_eq!(mgr.client_count().await, 0);
    }

    #[tokio::test]
    async fn test_remove_nonexistent_client_ok() {
        let mgr = ClientManager::new();
        mgr.remove_client("ghost").await; // should not panic
        assert_eq!(mgr.client_count().await, 0);
    }

    #[tokio::test]
    async fn test_multiple_clients() {
        let mgr = ClientManager::new();
        for i in 0..5 {
            let (tx, _rx) = mpsc::channel(256);
            mgr.add_client(format!("client{}", i), tx).await;
        }
        assert_eq!(mgr.client_count().await, 5);
    }

    #[tokio::test]
    async fn test_broadcast_sends_to_all_clients() {
        let mgr = ClientManager::new();
        let (tx1, mut rx1) = mpsc::channel(256);
        let (tx2, mut rx2) = mpsc::channel(256);
        mgr.add_client("c1".to_string(), tx1).await;
        mgr.add_client("c2".to_string(), tx2).await;

        mgr.broadcast(ServerMessage::Pong).await;

        // Both receivers should get the message
        assert!(rx1.try_recv().is_ok());
        assert!(rx2.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_broadcast_binary() {
        let mgr = ClientManager::new();
        let (tx, mut rx) = mpsc::channel(256);
        mgr.add_client("c1".to_string(), tx).await;
        mgr.broadcast_binary(bytes::Bytes::from("binary data"))
            .await;
        assert!(rx.try_recv().is_ok());
    }

    #[tokio::test]
    async fn test_broadcast_dead_client_does_not_panic() {
        let mgr = ClientManager::new();
        let (tx, rx) = mpsc::channel(256);
        mgr.add_client("c1".to_string(), tx).await;
        drop(rx); // Drop the receiver — sending to a dead client
                  // Should not panic
        mgr.broadcast(ServerMessage::Pong).await;
    }
}
