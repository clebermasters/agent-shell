use std::{path::PathBuf, time::Duration};

use anyhow::{Context, Result};
use chrono::{TimeZone, Utc};
use rusqlite::{params, Connection};
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
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch(
            r#"
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
            "#,
        )?;
        Ok(())
    }

    pub fn append_message(
        &self,
        session_name: &str,
        window_index: u32,
        source: &str,
        message: &ChatMessage,
    ) -> Result<String> {
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;

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

    pub fn list_events(
        &self,
        session_name: &str,
        window_index: u32,
    ) -> Result<Vec<StoredChatEvent>> {
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open chat event db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;

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
}
