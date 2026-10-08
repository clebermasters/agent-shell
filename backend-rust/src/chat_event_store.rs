use std::{path::PathBuf, time::Duration};

use anyhow::{Context, Result};
use chrono::{TimeZone, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

use crate::chat_log::ChatMessage;

#[derive(Debug, Clone)]
pub struct ChatEventStore {
    db_path: PathBuf,
}

#[derive(Debug, Clone)]
pub struct StoredChatEvent {
    pub event_id: String,
    pub session_name: String,
    pub window_index: u32,
    pub source: String,
    pub timestamp_millis: i64,
    pub message: ChatMessage,
}

impl ChatEventStore {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        let db_path = base_dir.join("chat_events.db");
        let store = Self { db_path };
        store.init()?;
        Ok(store)
    }

    fn init(&self) -> Result<()> {
        let conn = self.open_connection()?;
        conn.execute_batch(
            r#"
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA temp_store = MEMORY;

            CREATE TABLE IF NOT EXISTS chat_events (
                event_id TEXT PRIMARY KEY,
                session_name TEXT NOT NULL,
                window_index INTEGER NOT NULL,
                source TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                message_json TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_chat_events_session_window_time
            ON chat_events(session_name, window_index, timestamp_millis);

            CREATE TABLE IF NOT EXISTS acp_deleted_sessions (
                session_id TEXT PRIMARY KEY
            );
            "#,
        )?;
        Ok(())
    }

    fn open_connection(&self) -> Result<Connection> {
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;
        Ok(conn)
    }

    pub fn append_message(
        &self,
        session_name: &str,
        window_index: u32,
        source: &str,
        message: &ChatMessage,
    ) -> Result<String> {
        let conn = self.open_connection()?;

        let event_id = Uuid::new_v4().to_string();
        let timestamp_millis = message
            .timestamp
            .as_ref()
            .map(|ts| ts.timestamp_millis())
            .unwrap_or_else(|| Utc::now().timestamp_millis());
        let message_json = serde_json::to_string(message)?;

        conn.execute(
            r#"
            INSERT INTO chat_events (
                event_id, session_name, window_index, source, timestamp_millis, message_json
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
            "#,
            params![
                event_id,
                session_name,
                i64::from(window_index),
                source,
                timestamp_millis,
                message_json
            ],
        )?;

        Ok(event_id)
    }

    /// For streaming text chunks: if the last stored message for this session has the same role
    /// and is a single Text block, append the new text to it instead of inserting a new row.
    /// This keeps one DB row per logical message regardless of how many chunks the AI streams.
    pub fn append_or_merge_text(
        &self,
        session_name: &str,
        window_index: u32,
        source: &str,
        role: &str,
        text: &str,
    ) -> Result<()> {
        use crate::chat_log::ContentBlock;

        let mut conn = self.open_connection()?;
        let tx = conn.transaction()?;

        // Fetch the last row for this session
        let last: Option<(String, String)> = tx
            .query_row(
                r#"
                SELECT event_id, message_json FROM chat_events
                WHERE session_name = ?1 AND window_index = ?2
                ORDER BY timestamp_millis DESC, rowid DESC
                LIMIT 1
                "#,
                params![session_name, i64::from(window_index)],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
            )
            .optional()?;

        if let Some((event_id, json)) = last {
            if let Ok(mut msg) = serde_json::from_str::<crate::chat_log::ChatMessage>(&json) {
                if msg.role == role
                    && msg.blocks.len() == 1
                    && matches!(&msg.blocks[0], ContentBlock::Text { .. })
                {
                    // Merge: append text to the existing block
                    if let ContentBlock::Text { text: ref existing } = msg.blocks[0].clone() {
                        let merged = format!("{}{}", existing, text);
                        msg.blocks[0] = ContentBlock::Text { text: merged };
                        let updated_json = serde_json::to_string(&msg)?;
                        tx.execute(
                            "UPDATE chat_events SET message_json = ?1 WHERE event_id = ?2",
                            params![updated_json, event_id],
                        )?;
                        tx.commit()?;
                        return Ok(());
                    }
                }
            }
        }

        // No mergeable row found — insert new
        let message = crate::chat_log::ChatMessage {
            role: role.to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![ContentBlock::Text {
                text: text.to_string(),
            }],
        };
        let timestamp_millis = message
            .timestamp
            .as_ref()
            .map(|ts| ts.timestamp_millis())
            .unwrap_or_else(|| Utc::now().timestamp_millis());
        let message_json = serde_json::to_string(&message)?;
        tx.execute(
            r#"
            INSERT INTO chat_events (
                event_id, session_name, window_index, source, timestamp_millis, message_json
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
            "#,
            params![
                Uuid::new_v4().to_string(),
                session_name,
                i64::from(window_index),
                source,
                timestamp_millis,
                message_json
            ],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn list_events(
        &self,
        session_name: &str,
        window_index: u32,
    ) -> Result<Vec<StoredChatEvent>> {
        let conn = self.open_connection()?;

        let mut stmt = conn.prepare(
            r#"
            SELECT event_id, session_name, window_index, source, timestamp_millis, message_json
            FROM chat_events
            WHERE session_name = ?1 AND window_index = ?2
            ORDER BY timestamp_millis ASC, rowid ASC
            "#,
        )?;

        let rows = stmt.query_map(params![session_name, i64::from(window_index)], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, i64>(4)?,
                row.get::<_, String>(5)?,
            ))
        })?;

        let mut events = Vec::new();
        for row in rows {
            let (event_id, session_name, window_index_raw, source, timestamp_millis, message_json) =
                row?;

            let message: ChatMessage = serde_json::from_str(&message_json).with_context(|| {
                format!(
                    "failed to deserialize stored chat message for event {}",
                    event_id
                )
            })?;

            let window_index = u32::try_from(window_index_raw).with_context(|| {
                format!(
                    "stored window index out of range ({}) for event {}",
                    window_index_raw, event_id
                )
            })?;

            let mut message = message;
            if message.timestamp.is_none() {
                message.timestamp = Utc.timestamp_millis_opt(timestamp_millis).single();
            }

            events.push(StoredChatEvent {
                event_id,
                session_name,
                window_index,
                source,
                timestamp_millis,
                message,
            });
        }

        Ok(events)
    }

    pub fn list_messages(&self, session_name: &str, window_index: u32) -> Result<Vec<ChatMessage>> {
        let events = self.list_events(session_name, window_index)?;
        Ok(events.into_iter().map(|event| event.message).collect())
    }

    pub fn list_messages_page(
        &self,
        session_name: &str,
        window_index: u32,
        offset: usize,
        limit: usize,
    ) -> Result<(Vec<ChatMessage>, bool)> {
        let conn = self.open_connection()?;

        let total: i64 = conn.query_row(
            r#"
            SELECT COUNT(*)
            FROM chat_events
            WHERE session_name = ?1 AND window_index = ?2
            "#,
            params![session_name, i64::from(window_index)],
            |row| row.get(0),
        )?;

        let total = usize::try_from(total).unwrap_or(0);
        let end = total.saturating_sub(offset);
        let start = end.saturating_sub(limit);
        let page_len = end.saturating_sub(start);
        let has_more = start > 0;

        if page_len == 0 {
            return Ok((Vec::new(), has_more));
        }

        let mut stmt = conn.prepare(
            r#"
            SELECT message_json, timestamp_millis
            FROM chat_events
            WHERE session_name = ?1 AND window_index = ?2
            ORDER BY timestamp_millis ASC, rowid ASC
            LIMIT ?3 OFFSET ?4
            "#,
        )?;

        let rows = stmt.query_map(
            params![
                session_name,
                i64::from(window_index),
                i64::try_from(page_len).unwrap_or(i64::MAX),
                i64::try_from(start).unwrap_or(i64::MAX)
            ],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )?;

        let mut messages = Vec::with_capacity(page_len);
        for row in rows {
            let (message_json, timestamp_millis) = row?;
            let mut message: ChatMessage = serde_json::from_str(&message_json)?;
            if message.timestamp.is_none() {
                message.timestamp = Utc.timestamp_millis_opt(timestamp_millis).single();
            }
            messages.push(message);
        }

        Ok((messages, has_more))
    }

    pub fn clear_messages(&self, session_name: &str, window_index: u32) -> Result<()> {
        let conn = self.open_connection()?;

        conn.execute(
            r#"
            DELETE FROM chat_events
            WHERE session_name = ?1 AND window_index = ?2
            "#,
            params![session_name, i64::from(window_index)],
        )?;

        Ok(())
    }

    pub fn mark_acp_session_deleted(&self, session_id: &str) -> Result<()> {
        let conn = self.open_connection()?;
        conn.execute(
            "INSERT OR IGNORE INTO acp_deleted_sessions (session_id) VALUES (?1)",
            params![session_id],
        )?;
        Ok(())
    }

    pub fn get_deleted_acp_session_ids(&self) -> Result<Vec<String>> {
        let conn = self.open_connection()?;
        let mut stmt = conn.prepare("SELECT session_id FROM acp_deleted_sessions")?;
        let ids = stmt
            .query_map([], |row| row.get(0))?
            .collect::<std::result::Result<Vec<String>, _>>()?;
        Ok(ids)
    }

    /// Get webhook/file overlay messages for an ACP session.
    /// Returns only messages with source 'webhook' or 'webhook-file'.
    /// Excludes messages with timestamp_millis <= cleared_at.
    pub fn get_acp_overlay(
        &self,
        session_key: &str,
        cleared_at: Option<i64>,
    ) -> Result<Vec<StoredChatEvent>> {
        let conn = self.open_connection()?;

        let min_time = cleared_at.unwrap_or(0);

        let mut stmt = conn.prepare(
            "SELECT event_id, session_name, window_index, source, timestamp_millis, message_json
             FROM chat_events
             WHERE session_name = ?1
               AND source IN ('webhook', 'webhook-file')
               AND timestamp_millis > ?2
             ORDER BY timestamp_millis ASC",
        )?;

        let rows = stmt.query_map(params![session_key, min_time], |row| {
            let message_json: String = row.get(5)?;
            let message: crate::chat_log::ChatMessage = serde_json::from_str(&message_json)
                .unwrap_or(crate::chat_log::ChatMessage {
                    role: "unknown".to_string(),
                    timestamp: None,
                    blocks: vec![],
                });
            Ok(StoredChatEvent {
                event_id: row.get(0)?,
                session_name: row.get(1)?,
                window_index: row.get(2)?,
                source: row.get(3)?,
                timestamp_millis: row.get(4)?,
                message,
            })
        })?;

        rows.collect::<std::result::Result<Vec<_>, _>>()
            .map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat_log::{ChatMessage, ContentBlock};
    use tempfile::TempDir;

    fn make_store() -> (ChatEventStore, TempDir) {
        let dir = TempDir::new().unwrap();
        let store = ChatEventStore::new(dir.path().to_path_buf()).unwrap();
        (store, dir)
    }

    fn text_msg(role: &str, text: &str) -> ChatMessage {
        ChatMessage {
            role: role.to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![ContentBlock::Text {
                text: text.to_string(),
            }],
        }
    }

    #[test]
    fn test_new_creates_db() {
        let dir = TempDir::new().unwrap();
        let store = ChatEventStore::new(dir.path().to_path_buf());
        assert!(store.is_ok());
    }

    #[test]
    fn test_append_and_list() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "hello");
        store.append_message("sess1", 0, "user", &msg).unwrap();

        let events = store.list_events("sess1", 0).unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].session_name, "sess1");
        assert_eq!(events[0].source, "user");
    }

    #[test]
    fn test_list_messages() {
        let (store, _dir) = make_store();
        let msg = text_msg("assistant", "hi there");
        store.append_message("sess1", 0, "acp", &msg).unwrap();

        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].role, "assistant");
    }

    #[test]
    fn test_append_or_merge_new_message() {
        let (store, _dir) = make_store();
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "Hello")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 1);
    }

    #[test]
    fn test_append_or_merge_merges_same_role() {
        let (store, _dir) = make_store();
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "Hello")
            .unwrap();
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", " World")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 1);
        match &messages[0].blocks[0] {
            ContentBlock::Text { text } => assert_eq!(text, "Hello World"),
            _ => panic!("Expected text block"),
        }
    }

    #[test]
    fn test_append_or_merge_different_roles() {
        let (store, _dir) = make_store();
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "Hi")
            .unwrap();
        store
            .append_or_merge_text("sess1", 0, "user", "user", "Hello")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 2);
    }

    #[test]
    fn test_clear_messages() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "test");
        store.append_message("sess1", 0, "user", &msg).unwrap();
        store.clear_messages("sess1", 0).unwrap();
        let events = store.list_events("sess1", 0).unwrap();
        assert_eq!(events.len(), 0);
    }

    #[test]
    fn test_clear_only_affects_target_session() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "test");
        store.append_message("sess1", 0, "user", &msg).unwrap();
        store.append_message("sess2", 0, "user", &msg).unwrap();
        store.clear_messages("sess1", 0).unwrap();
        assert_eq!(store.list_events("sess1", 0).unwrap().len(), 0);
        assert_eq!(store.list_events("sess2", 0).unwrap().len(), 1);
    }

    #[test]
    fn test_acp_deleted_sessions() {
        let (store, _dir) = make_store();
        store.mark_acp_session_deleted("session-abc").unwrap();
        let ids = store.get_deleted_acp_session_ids().unwrap();
        assert!(ids.contains(&"session-abc".to_string()));
    }

    #[test]
    fn test_acp_deleted_idempotent() {
        let (store, _dir) = make_store();
        store.mark_acp_session_deleted("sid1").unwrap();
        store.mark_acp_session_deleted("sid1").unwrap(); // INSERT OR IGNORE
        let ids = store.get_deleted_acp_session_ids().unwrap();
        assert_eq!(ids.iter().filter(|id| *id == "sid1").count(), 1);
    }

    #[test]
    fn test_get_acp_overlay_empty() {
        let (store, _dir) = make_store();
        let events = store.get_acp_overlay("sess1", None).unwrap();
        assert_eq!(events.len(), 0);
    }

    #[test]
    fn test_get_acp_overlay_with_webhook_source() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "from webhook");
        store.append_message("sess1", 0, "webhook", &msg).unwrap();
        let events = store.get_acp_overlay("sess1", None).unwrap();
        assert_eq!(events.len(), 1);
    }

    #[test]
    fn test_get_acp_overlay_filters_cleared() {
        let (store, _dir) = make_store();
        let mut msg = text_msg("user", "old");
        let old_ts = chrono::Utc::now() - chrono::Duration::seconds(100);
        msg.timestamp = Some(old_ts);
        store.append_message("sess1", 0, "webhook", &msg).unwrap();

        // Filter anything before now
        let events = store
            .get_acp_overlay("sess1", Some(chrono::Utc::now().timestamp_millis()))
            .unwrap();
        assert_eq!(events.len(), 0);
    }

    #[test]
    fn test_multiple_windows_independent() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "test");
        store.append_message("sess1", 0, "user", &msg).unwrap();
        store.append_message("sess1", 1, "user", &msg).unwrap();
        assert_eq!(store.list_events("sess1", 0).unwrap().len(), 1);
        assert_eq!(store.list_events("sess1", 1).unwrap().len(), 1);
    }

    #[test]
    fn test_append_or_merge_multi_block_no_merge() {
        let (store, _dir) = make_store();
        // Insert a message with 2 blocks (Text + Image)
        let multi_block_msg = ChatMessage {
            role: "assistant".to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![
                ContentBlock::Text {
                    text: "Look at this".to_string(),
                },
                ContentBlock::Image {
                    id: "img-1".to_string(),
                    mime_type: "image/png".to_string(),
                    alt_text: None,
                },
            ],
        };
        store
            .append_message("sess1", 0, "acp", &multi_block_msg)
            .unwrap();
        // Now append_or_merge_text with same role — should NOT merge (blocks.len() != 1)
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "New text")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 2); // separate rows
    }

    #[test]
    fn test_append_or_merge_empty_text() {
        let (store, _dir) = make_store();
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 1);
        // Merge empty text onto existing
        store
            .append_or_merge_text("sess1", 0, "acp", "assistant", "")
            .unwrap();
        let messages = store.list_messages("sess1", 0).unwrap();
        assert_eq!(messages.len(), 1); // still merged
    }

    #[test]
    fn test_list_events_ordering() {
        let (store, _dir) = make_store();
        let now = chrono::Utc::now();
        for i in 0..3 {
            let msg = ChatMessage {
                role: "user".to_string(),
                timestamp: Some(now + chrono::Duration::seconds(i)),
                blocks: vec![ContentBlock::Text {
                    text: format!("msg {}", i),
                }],
            };
            store.append_message("sess1", 0, "user", &msg).unwrap();
        }
        let events = store.list_events("sess1", 0).unwrap();
        assert_eq!(events.len(), 3);
        // Should be in timestamp ascending order
        assert!(events[0].timestamp_millis <= events[1].timestamp_millis);
        assert!(events[1].timestamp_millis <= events[2].timestamp_millis);
    }

    #[test]
    fn test_get_acp_overlay_mixed_sources() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "from webhook");
        store.append_message("sess1", 0, "webhook", &msg).unwrap();
        let msg2 = text_msg("user", "from webhook-file");
        store
            .append_message("sess1", 0, "webhook-file", &msg2)
            .unwrap();
        let msg3 = text_msg("user", "from user source");
        store.append_message("sess1", 0, "user", &msg3).unwrap();

        let events = store.get_acp_overlay("sess1", None).unwrap();
        // Only webhook and webhook-file sources should be returned
        assert_eq!(events.len(), 2);
        assert!(events
            .iter()
            .all(|e| e.source == "webhook" || e.source == "webhook-file"));
    }

    #[test]
    fn test_clear_messages_window_isolation() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "test");
        store.append_message("sess1", 0, "user", &msg).unwrap();
        store.append_message("sess1", 1, "user", &msg).unwrap();
        // Clear only window 0
        store.clear_messages("sess1", 0).unwrap();
        assert_eq!(store.list_events("sess1", 0).unwrap().len(), 0);
        assert_eq!(store.list_events("sess1", 1).unwrap().len(), 1); // window 1 intact
    }
}
