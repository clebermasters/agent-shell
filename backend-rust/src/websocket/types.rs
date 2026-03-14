use bytes::Bytes;
use std::{cmp::Ordering, io::Write, sync::Arc};
use tokio::{
    sync::{mpsc, Mutex},
    task::JoinHandle,
};

use crate::{chat_clear_store, chat_event_store, chat_file_storage, chat_log::ChatMessage};

use super::client_manager::ClientManager;

// ── Broadcasting ──────────────────────────────────────────────────────────

pub type ClientId = String;

/// Pre-serialized message for zero-copy broadcasting.
#[derive(Clone)]
pub enum BroadcastMessage {
    Text(Arc<String>),
    Binary(Bytes),
}

// ── PTY session state ─────────────────────────────────────────────────────

pub(crate) struct PtySession {
    pub writer: Arc<Mutex<Box<dyn Write + Send>>>,
    pub master: Arc<Mutex<Box<dyn portable_pty::MasterPty + Send>>>,
    pub reader_task: JoinHandle<()>,
    pub child: Arc<Mutex<Box<dyn portable_pty::Child + Send>>>,
    pub tmux_session: String,
}

// ── Per-client WebSocket state ────────────────────────────────────────────

pub(crate) struct WsState {
    pub client_id: ClientId,
    pub current_pty: Arc<Mutex<Option<PtySession>>>,
    pub current_session: Arc<Mutex<Option<String>>>,
    pub current_window: Arc<Mutex<Option<u32>>>,
    pub audio_tx: Option<mpsc::UnboundedSender<BroadcastMessage>>,
    pub message_tx: mpsc::UnboundedSender<BroadcastMessage>,
    pub chat_log_handle: Arc<Mutex<Option<JoinHandle<()>>>>,
    pub chat_file_storage: Arc<chat_file_storage::ChatFileStorage>,
    pub chat_event_store: Arc<chat_event_store::ChatEventStore>,
    pub chat_clear_store: Arc<chat_clear_store::ChatClearStore>,
    pub client_manager: Arc<ClientManager>,
    #[allow(dead_code)]
    pub acp_client: Arc<tokio::sync::RwLock<Option<crate::acp::AcpClient>>>,
}

// ── Chat history merging ──────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum HistoryMessageSource {
    Tool,
    Persisted,
}

#[derive(Clone, Debug)]
struct HistoryMessageEntry {
    message: ChatMessage,
    source: HistoryMessageSource,
    sequence: usize,
}

pub(crate) fn merge_history_messages(
    tool_messages: Vec<ChatMessage>,
    persisted_messages: Vec<ChatMessage>,
) -> Vec<ChatMessage> {
    if persisted_messages.is_empty() {
        return tool_messages;
    }
    if tool_messages.is_empty() {
        return persisted_messages;
    }

    let mut entries = Vec::with_capacity(tool_messages.len() + persisted_messages.len());
    entries.extend(
        tool_messages
            .into_iter()
            .enumerate()
            .map(|(sequence, message)| HistoryMessageEntry {
                message,
                source: HistoryMessageSource::Tool,
                sequence,
            }),
    );
    entries.extend(
        persisted_messages
            .into_iter()
            .enumerate()
            .map(|(sequence, message)| HistoryMessageEntry {
                message,
                source: HistoryMessageSource::Persisted,
                sequence,
            }),
    );

    entries.sort_by(|left, right| {
        if let (Some(left_ts), Some(right_ts)) = (
            left.message.timestamp.as_ref(),
            right.message.timestamp.as_ref(),
        ) {
            let ts_cmp = left_ts.cmp(right_ts);
            if ts_cmp != Ordering::Equal {
                return ts_cmp;
            }
        }

        if left.source == right.source {
            return left.sequence.cmp(&right.sequence);
        }

        match (left.source, right.source) {
            (HistoryMessageSource::Tool, HistoryMessageSource::Persisted) => Ordering::Less,
            (HistoryMessageSource::Persisted, HistoryMessageSource::Tool) => Ordering::Greater,
            (HistoryMessageSource::Tool, HistoryMessageSource::Tool)
            | (HistoryMessageSource::Persisted, HistoryMessageSource::Persisted) => Ordering::Equal,
        }
    });

    entries.into_iter().map(|entry| entry.message).collect()
}

// ── Helpers ───────────────────────────────────────────────────────────────

pub(crate) async fn send_message(
    tx: &mpsc::UnboundedSender<BroadcastMessage>,
    msg: crate::types::ServerMessage,
) -> anyhow::Result<()> {
    let json = serde_json::to_string(&msg)?;
    tx.send(BroadcastMessage::Text(Arc::new(json)))?;
    Ok(())
}
