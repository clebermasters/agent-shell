use std::{fs, path::PathBuf, time::Duration};

use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

use crate::notification::{Notification, NotificationFile};

pub struct NotificationStore {
    db_path: PathBuf,
    base_dir: PathBuf,
}

impl NotificationStore {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        let db_path = base_dir.join("notifications.db");
        let store = Self { db_path, base_dir };
        store.init()?;
        Ok(store)
    }

    fn init(&self) -> Result<()> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS notifications (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                source TEXT NOT NULL,
                source_detail TEXT,
                timestamp_millis INTEGER NOT NULL,
                read INTEGER NOT NULL DEFAULT 0,
                file_count INTEGER DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS notification_files (
                id TEXT PRIMARY KEY,
                notification_id TEXT NOT NULL,
                filename TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size INTEGER NOT NULL,
                stored_path TEXT NOT NULL,
                FOREIGN KEY (notification_id) REFERENCES notifications(id)
            );

            CREATE INDEX IF NOT EXISTS idx_notifications_timestamp ON notifications(timestamp_millis DESC);
            CREATE INDEX IF NOT EXISTS idx_notification_files_nid ON notification_files(notification_id);
            "#,
        )?;
        Ok(())
    }

    pub fn insert(&self, notification: &Notification, files: Vec<NotificationFile>) -> Result<()> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;

        conn.execute(
            r#"
            INSERT INTO notifications (id, title, body, source, source_detail, timestamp_millis, read, file_count)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
            "#,
            params![
                notification.id,
                notification.title,
                notification.body,
                notification.source,
                notification.source_detail,
                notification.timestamp_millis,
                notification.read as i32,
                notification.file_count
            ],
        )?;

        for file in &files {
            conn.execute(
                r#"
                INSERT INTO notification_files (id, notification_id, filename, mime_type, size, stored_path)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                "#,
                params![
                    file.id,
                    file.notification_id,
                    file.filename,
                    file.mime_type,
                    file.size,
                    file.stored_path
                ],
            )?;
        }

        Ok(())
    }

    pub fn list(&self, limit: usize, before: Option<i64>) -> Result<Vec<Notification>> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;

        let mut notifications = Vec::new();

        if let Some(before_ts) = before {
            let mut stmt = conn.prepare(
                r#"
                SELECT id, title, body, source, source_detail, timestamp_millis, read, file_count
                FROM notifications
                WHERE timestamp_millis < ?1
                ORDER BY timestamp_millis DESC
                LIMIT ?2
                "#,
            )?;

            let rows = stmt.query_map(params![before_ts, limit as i64], |row| {
                Ok(Notification {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    body: row.get(2)?,
                    source: row.get(3)?,
                    source_detail: row.get(4)?,
                    timestamp_millis: row.get(5)?,
                    read: row.get::<_, i32>(6)? != 0,
                    file_count: row.get(7)?,
                })
            })?;

            for row in rows {
                notifications.push(row?);
            }
        } else {
            let mut stmt = conn.prepare(
                r#"
                SELECT id, title, body, source, source_detail, timestamp_millis, read, file_count
                FROM notifications
                ORDER BY timestamp_millis DESC
                LIMIT ?1
                "#,
            )?;

            let rows = stmt.query_map(params![limit as i64], |row| {
                Ok(Notification {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    body: row.get(2)?,
                    source: row.get(3)?,
                    source_detail: row.get(4)?,
                    timestamp_millis: row.get(5)?,
                    read: row.get::<_, i32>(6)? != 0,
                    file_count: row.get(7)?,
                })
            })?;

            for row in rows {
                notifications.push(row?);
            }
        }

        Ok(notifications)
    }

    pub fn mark_read(&self, id: &str) -> Result<()> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;

        conn.execute(
            "UPDATE notifications SET read = 1 WHERE id = ?1",
            params![id],
        )?;

        Ok(())
    }

    pub fn get_file(&self, file_id: &str) -> Result<Option<NotificationFile>> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;

        let file = conn
            .query_row(
                r#"
                SELECT id, notification_id, filename, mime_type, size, stored_path
                FROM notification_files
                WHERE id = ?1
                "#,
                params![file_id],
                |row| {
                    Ok(NotificationFile {
                        id: row.get(0)?,
                        notification_id: row.get(1)?,
                        filename: row.get(2)?,
                        mime_type: row.get(3)?,
                        size: row.get(4)?,
                        stored_path: row.get(5)?,
                    })
                },
            )
            .optional()?;

        Ok(file)
    }

    pub fn base_dir(&self) -> &PathBuf {
        &self.base_dir
    }

    pub fn get_unread_count(&self) -> Result<i32> {
        let conn = Connection::open(&self.db_path).with_context(|| {
            format!(
                "failed to open notifications db: {}",
                self.db_path.display()
            )
        })?;
        conn.busy_timeout(Duration::from_secs(5))?;

        let count: i32 = conn.query_row(
            "SELECT COUNT(*) FROM notifications WHERE read = 0",
            [],
            |row| row.get(0),
        )?;

        Ok(count)
    }
}
