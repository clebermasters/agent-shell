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
    /// Shared kiro PTY output sender. Protected by std::sync::Mutex so the
    /// blocking PTY reader thread can check it dynamically. WatchChatLog sets
    /// this when kiro is detected; the PTY reader reads it on every output chunk.
    /// This eliminates the ordering dependency between AttachSession and WatchChatLog.
    pub kiro_chat_output_tx: Arc<std::sync::Mutex<Option<mpsc::UnboundedSender<String>>>>,
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat_log::{ChatMessage, ContentBlock};
    use chrono::Utc;

    fn make_msg(role: &str, ts_offset_secs: i64) -> ChatMessage {
        ChatMessage {
            role: role.to_string(),
            timestamp: Some(Utc::now() + chrono::Duration::seconds(ts_offset_secs)),
            blocks: vec![ContentBlock::Text { text: format!("{} message", role) }],
        }
    }

    fn make_msg_no_ts(role: &str) -> ChatMessage {
        ChatMessage {
            role: role.to_string(),
            timestamp: None,
            blocks: vec![ContentBlock::Text { text: role.to_string() }],
        }
    }

    #[test]
    fn test_merge_empty_persisted_returns_tool() {
        let tool = vec![make_msg("user", 0)];
        let result = merge_history_messages(tool.clone(), vec![]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].role, "user");
    }

    #[test]
    fn test_merge_empty_tool_returns_persisted() {
        let persisted = vec![make_msg("assistant", 0)];
        let result = merge_history_messages(vec![], persisted);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].role, "assistant");
    }

    #[test]
    fn test_merge_both_empty() {
        let result = merge_history_messages(vec![], vec![]);
        assert_eq!(result.len(), 0);
    }

    #[test]
    fn test_merge_orders_by_timestamp() {
        let tool = vec![make_msg("user", 10)]; // later
        let persisted = vec![make_msg("assistant", 0)]; // earlier
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].role, "assistant"); // earlier first
        assert_eq!(result[1].role, "user");
    }

    #[test]
    fn test_merge_tool_before_persisted_at_same_time() {
        // Same timestamp: tool comes before persisted
        let now = Utc::now();
        let mut tool_msg = make_msg("user", 0);
        tool_msg.timestamp = Some(now);
        let mut pers_msg = make_msg("assistant", 0);
        pers_msg.timestamp = Some(now);
        let result = merge_history_messages(vec![tool_msg], vec![pers_msg]);
        assert_eq!(result[0].role, "user"); // tool comes first
    }

    #[test]
    fn test_merge_no_timestamps_preserves_order() {
        let tool = vec![make_msg_no_ts("t1"), make_msg_no_ts("t2")];
        let persisted = vec![make_msg_no_ts("p1")];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 3);
    }

    #[test]
    fn test_merge_multiple_messages() {
        let tool = vec![make_msg("user", 0), make_msg("user", 20)];
        let persisted = vec![make_msg("assistant", 10), make_msg("assistant", 30)];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 4);
        // Should be in timestamp order: 0, 10, 20, 30
        assert_eq!(result[0].role, "user");
        assert_eq!(result[1].role, "assistant");
        assert_eq!(result[2].role, "user");
        assert_eq!(result[3].role, "assistant");
    }

    #[test]
    fn test_merge_five_tool_same_timestamp() {
        let now = Utc::now();
        let tool: Vec<ChatMessage> = (0..5).map(|i| {
            let mut m = make_msg("user", 0);
            m.timestamp = Some(now);
            m.blocks = vec![ContentBlock::Text { text: format!("tool-{}", i) }];
            m
        }).collect();
        let result = merge_history_messages(tool, vec![]);
        assert_eq!(result.len(), 5);
        // Should maintain insertion order
        for (i, msg) in result.iter().enumerate() {
            match &msg.blocks[0] {
                ContentBlock::Text { text } => assert_eq!(text, &format!("tool-{}", i)),
                _ => panic!("Expected text"),
            }
        }
    }

    #[test]
    fn test_merge_interleaved_timestamps() {
        // Tool at t=0, t=20, t=40; persisted at t=10, t=30
        let tool = vec![make_msg("user", 0), make_msg("user", 20), make_msg("user", 40)];
        let persisted = vec![make_msg("assistant", 10), make_msg("assistant", 30)];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 5);
        // Expected order: user(0), assistant(10), user(20), assistant(30), user(40)
        assert_eq!(result[0].role, "user");
        assert_eq!(result[1].role, "assistant");
        assert_eq!(result[2].role, "user");
        assert_eq!(result[3].role, "assistant");
        assert_eq!(result[4].role, "user");
    }

    #[tokio::test]
    async fn test_send_message_success() {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<BroadcastMessage>();
        let result = send_message(&tx, crate::types::ServerMessage::Pong).await;
        assert!(result.is_ok());
        let msg = rx.try_recv().unwrap();
        match msg {
            BroadcastMessage::Text(s) => assert!(s.contains("pong")),
            _ => panic!("Expected text"),
        }
    }

    // Phase 9: Merge logic additional tests

    #[test]
    fn test_merge_single_each_source_tool_first() {
        let tool = vec![make_msg("user", 0)];
        let persisted = vec![make_msg("assistant", 5)];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].role, "user");
        assert_eq!(result[1].role, "assistant");
    }

    #[test]
    fn test_merge_single_each_source_persisted_first() {
        let tool = vec![make_msg("user", 10)];
        let persisted = vec![make_msg("assistant", 0)];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].role, "assistant");
        assert_eq!(result[1].role, "user");
    }

    #[test]
    fn test_merge_large_volume() {
        let tool: Vec<ChatMessage> = (0..100).map(|i| make_msg("user", i * 2)).collect();
        let persisted: Vec<ChatMessage> = (0..100).map(|i| make_msg("assistant", i * 2 + 1)).collect();
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 200);
        // Verify ordered by timestamp
        for i in 1..result.len() {
            if let (Some(prev), Some(curr)) = (&result[i-1].timestamp, &result[i].timestamp) {
                assert!(prev <= curr);
            }
        }
    }

    #[test]
    fn test_merge_all_same_timestamp_tool_first() {
        let now = Utc::now();
        let mut tool = vec![make_msg("user", 0)];
        tool[0].timestamp = Some(now);
        let mut persisted = vec![make_msg("assistant", 0)];
        persisted[0].timestamp = Some(now);
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 2);
        // Tool messages should come before persisted at same timestamp
        assert_eq!(result[0].role, "user");
        assert_eq!(result[1].role, "assistant");
    }

    #[test]
    fn test_merge_both_sources_no_timestamps() {
        let tool = vec![make_msg_no_ts("t1"), make_msg_no_ts("t2")];
        let persisted = vec![make_msg_no_ts("p1"), make_msg_no_ts("p2")];
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 4);
        // Tool messages should come first when no timestamps
        assert_eq!(result[0].role, "t1");
        assert_eq!(result[1].role, "t2");
    }

    #[test]
    fn test_merge_preserves_block_content() {
        let mut tool_msg = make_msg("user", 0);
        tool_msg.blocks = vec![ContentBlock::Text { text: "important data".to_string() }];
        let result = merge_history_messages(vec![tool_msg], vec![]);
        match &result[0].blocks[0] {
            ContentBlock::Text { text } => assert_eq!(text, "important data"),
            _ => panic!("Expected text block"),
        }
    }

    #[test]
    fn test_merge_many_same_timestamp_ordering() {
        let now = Utc::now();
        let tool: Vec<ChatMessage> = (0..5).map(|i| {
            let mut m = make_msg("user", 0);
            m.timestamp = Some(now);
            m.blocks = vec![ContentBlock::Text { text: format!("t{}", i) }];
            m
        }).collect();
        let persisted: Vec<ChatMessage> = (0..5).map(|i| {
            let mut m = make_msg("assistant", 0);
            m.timestamp = Some(now);
            m.blocks = vec![ContentBlock::Text { text: format!("p{}", i) }];
            m
        }).collect();
        let result = merge_history_messages(tool, persisted);
        assert_eq!(result.len(), 10);
        // First 5 should be tool messages (same timestamp, tool comes first)
        for i in 0..5 {
            assert_eq!(result[i].role, "user");
        }
        for i in 5..10 {
            assert_eq!(result[i].role, "assistant");
        }
    }
}
