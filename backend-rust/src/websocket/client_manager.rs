use std::{collections::HashMap, sync::Arc};
use tokio::sync::{mpsc, RwLock};
use tokio_util::sync::CancellationToken;
use tracing::{info, warn};

use super::types::{BroadcastMessage, ClientId};
use crate::types::ServerMessage;

/// Manages all connected WebSocket clients and handles broadcasting.
pub struct ClientManager {
    clients: Arc<RwLock<HashMap<ClientId, ClientConnection>>>,
}

#[derive(Clone)]
struct ClientConnection {
    tx: mpsc::Sender<BroadcastMessage>,
    disconnect: CancellationToken,
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
    ) -> CancellationToken {
        let disconnect = CancellationToken::new();
        let mut clients = self.clients.write().await;
        if let Some(previous) = clients.insert(client_id, ClientConnection { tx, disconnect: disconnect.clone() }) {
            previous.disconnect.cancel();
        }
        info!("Client added. Total clients: {}", clients.len());
        disconnect
    }

    pub async fn remove_client(&self, client_id: &str) {
        let mut clients = self.clients.write().await;
        if let Some(client) = clients.remove(client_id) {
            client.disconnect.cancel();
        }
        info!("Client removed. Total clients: {}", clients.len());
    }

    pub async fn client_count(&self) -> usize {
        let clients = self.clients.read().await;
        clients.len()
    }

    pub async fn broadcast(&self, message: ServerMessage) {
        self.broadcast_value(serde_json::to_value(message).unwrap_or_default()).await;
    }

    pub async fn broadcast_bound(&self, message: ServerMessage, binding: &crate::chat_binding::ChatBinding) {
        let mut value = serde_json::to_value(message).unwrap_or_default();
        value["bindingId"] = binding.binding_id.clone().into();
        value["conversationKey"] = binding.conversation_key.clone().into();
        self.broadcast_value(value).await;
    }

    async fn broadcast_value(&self, message: serde_json::Value) {
        // Serialize once for all clients
        if let Ok(serialized) = serde_json::to_string(&message) {
            self.broadcast_message(BroadcastMessage::Text(Arc::new(serialized))).await;
        }
    }

    pub async fn broadcast_binary(&self, data: bytes::Bytes) {
        self.broadcast_message(BroadcastMessage::Binary(data)).await;
    }

    async fn broadcast_message(&self, msg: BroadcastMessage) {
        let clients = {
            let clients = self.clients.read().await;
            clients
                .iter()
                .map(|(client_id, client)| (client_id.clone(), client.clone()))
                .collect::<Vec<_>>()
        };
        let mut dead_clients = Vec::new();
        for (client_id, client) in clients {
            // Preserve FIFO for healthy recipients without allowing one slow
            // socket to block every sender's mutation acknowledgement. A full
            // recipient must reconnect and reload history; silently dropping a
            // packet while retaining its live connection would lose state.
            if let Err(error) = client.tx.try_send(msg.clone()) {
                let reason = match error {
                    mpsc::error::TrySendError::Full(_) => "outbound queue full",
                    mpsc::error::TrySendError::Closed(_) => "outbound queue closed",
                };
                warn!(client_id, reason, "Disconnecting stalled WebSocket recipient");
                client.disconnect.cancel();
                dead_clients.push((client_id, client.tx));
            }
        }
        if !dead_clients.is_empty() {
            let mut clients = self.clients.write().await;
            for (client_id, previous_tx) in dead_clients {
                // A registration may have been replaced after the snapshot.
                if clients.get(&client_id).is_some_and(|client| client.tx.same_channel(&previous_tx)) {
                    clients.remove(&client_id);
                }
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
        let disconnect = mgr.add_client("client1".to_string(), tx).await;
        mgr.remove_client("client1").await;
        assert_eq!(mgr.client_count().await, 0);
        assert!(disconnect.is_cancelled());
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
        let disconnect = mgr.add_client("c1".to_string(), tx).await;
        drop(rx); // Drop the receiver — sending to a dead client
                  // Should not panic
        mgr.broadcast(ServerMessage::Pong).await;
        assert!(disconnect.is_cancelled());
        assert_eq!(mgr.client_count().await, 0);
    }

    #[tokio::test]
    async fn full_recipient_cannot_block_chat_broadcast_to_healthy_recipient() {
        let mgr = ClientManager::new();
        let (slow_tx, _slow_rx) = mpsc::channel(1);
        assert!(slow_tx.try_send(BroadcastMessage::Binary(bytes::Bytes::from_static(b"occupied"))).is_ok());
        let (healthy_tx, mut healthy_rx) = mpsc::channel(4);
        let slow_disconnect = mgr.add_client("slow".into(), slow_tx).await;
        let healthy_disconnect = mgr.add_client("healthy".into(), healthy_tx).await;
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            mgr.broadcast(ServerMessage::Error { message: "first".into() }),
        ).await.expect("A saturated recipient must not hold another client's send acknowledgement");
        assert!(slow_disconnect.is_cancelled());
        assert!(!healthy_disconnect.is_cancelled());
        assert_eq!(mgr.client_count().await, 1);
        mgr.broadcast(ServerMessage::Error { message: "second".into() }).await;
        for expected in ["first", "second"] {
            let BroadcastMessage::Text(message) = healthy_rx.try_recv().unwrap() else { panic!("Expected text") };
            let value: serde_json::Value = serde_json::from_str(&message).unwrap();
            assert_eq!(value["message"], expected);
        }
    }

    #[tokio::test]
    async fn full_recipient_cannot_block_binary_broadcast_to_healthy_recipient() {
        let mgr = ClientManager::new();
        let (slow_tx, _slow_rx) = mpsc::channel(1);
        assert!(slow_tx.try_send(BroadcastMessage::Binary(bytes::Bytes::from_static(b"occupied"))).is_ok());
        let (healthy_tx, mut healthy_rx) = mpsc::channel(4);
        let slow_disconnect = mgr.add_client("slow".into(), slow_tx).await;
        let healthy_disconnect = mgr.add_client("healthy".into(), healthy_tx).await;
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            mgr.broadcast_binary(bytes::Bytes::from_static(b"first")),
        ).await.expect("A saturated recipient must not block binary fanout");
        assert!(slow_disconnect.is_cancelled());
        assert!(!healthy_disconnect.is_cancelled());
        assert_eq!(mgr.client_count().await, 1);
        let BroadcastMessage::Binary(message) = healthy_rx.try_recv().unwrap() else { panic!("Expected binary") };
        assert_eq!(message, bytes::Bytes::from_static(b"first"));
    }

    #[tokio::test]
    async fn replacing_registration_disconnects_only_the_previous_socket() {
        let mgr = ClientManager::new();
        let (old_tx, _old_rx) = mpsc::channel(1);
        let old_disconnect = mgr.add_client("same-id".into(), old_tx).await;
        let (new_tx, mut new_rx) = mpsc::channel(1);
        let new_disconnect = mgr.add_client("same-id".into(), new_tx).await;
        assert!(old_disconnect.is_cancelled());
        assert!(!new_disconnect.is_cancelled());
        mgr.broadcast(ServerMessage::Pong).await;
        assert!(new_rx.try_recv().is_ok());
        assert_eq!(mgr.client_count().await, 1);
    }
}
