use std::{path::PathBuf, time::Duration};

use anyhow::{Context, Result};
use rusqlite::{params, Connection};
use uuid::Uuid;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TagEntry {
    pub id: String,
    pub name: String,
    pub color_hex: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TagAssignment {
    pub session_name: String,
    pub tag_id: String,
}

pub struct TagStore {
    db_path: PathBuf,
}

impl TagStore {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        let db_path = base_dir.join("tags.db");
        let store = Self { db_path };
        store.init()?;
        Ok(store)
    }

    fn init(&self) -> Result<()> {
        let conn = self.open()?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS session_tags (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                color_hex TEXT NOT NULL DEFAULT '#2196F3',
                created_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_tags_created ON session_tags(created_at ASC);

            CREATE TABLE IF NOT EXISTS session_tag_assignments (
                session_name TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY (session_name, tag_id),
                FOREIGN KEY (tag_id) REFERENCES session_tags(id) ON DELETE CASCADE
            );
            "#,
        )?;
        Ok(())
    }

    fn open(&self) -> Result<Connection> {
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open tags db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute("PRAGMA foreign_keys = ON", [])?;
        Ok(conn)
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    pub fn list_tags(&self) -> Result<Vec<TagEntry>> {
        let conn = self.open()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, color_hex, created_at FROM session_tags ORDER BY created_at ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TagEntry {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    color_hex: row.get(2)?,
                    created_at: row.get(3)?,
                })
            })?
            .filter_map(|r| r.ok())
            .collect();
        Ok(rows)
    }

    pub fn add_tag(&self, name: String, color_hex: String) -> Result<TagEntry> {
        let conn = self.open()?;
        let id = Uuid::new_v4().to_string();
        let created_at = now_millis();
        conn.execute(
            "INSERT INTO session_tags (id, name, color_hex, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![id, name, color_hex, created_at],
        )?;
        Ok(TagEntry {
            id,
            name,
            color_hex,
            created_at,
        })
    }

    pub fn delete_tag(&self, id: &str) -> Result<bool> {
        let conn = self.open()?;
        let n = conn.execute("DELETE FROM session_tags WHERE id=?1", params![id])?;
        Ok(n > 0)
    }

    // ── Session-tag assignments ───────────────────────────────────────────────

    pub fn list_assignments(&self) -> Result<Vec<TagAssignment>> {
        let conn = self.open()?;
        let mut stmt = conn.prepare("SELECT session_name, tag_id FROM session_tag_assignments")?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TagAssignment {
                    session_name: row.get(0)?,
                    tag_id: row.get(1)?,
                })
            })?
            .filter_map(|r| r.ok())
            .collect();
        Ok(rows)
    }

    pub fn assign_tag(&self, session_name: &str, tag_id: &str) -> Result<()> {
        let conn = self.open()?;
        conn.execute(
            "INSERT OR REPLACE INTO session_tag_assignments (session_name, tag_id) VALUES (?1, ?2)",
            params![session_name, tag_id],
        )?;
        Ok(())
    }

    pub fn remove_tag_from_session(&self, session_name: &str, tag_id: &str) -> Result<()> {
        let conn = self.open()?;
        conn.execute(
            "DELETE FROM session_tag_assignments WHERE session_name=?1 AND tag_id=?2",
            params![session_name, tag_id],
        )?;
        Ok(())
    }

    pub fn clear_tags_for_session(&self, session_name: &str) -> Result<()> {
        let conn = self.open()?;
        conn.execute(
            "DELETE FROM session_tag_assignments WHERE session_name=?1",
            params![session_name],
        )?;
        Ok(())
    }

    /// Returns all tag IDs assigned to the given session.
    pub fn tag_ids_for_session(&self, session_name: &str) -> Result<Vec<String>> {
        let conn = self.open()?;
        let mut stmt =
            conn.prepare("SELECT tag_id FROM session_tag_assignments WHERE session_name=?1")?;
        let rows = stmt
            .query_map(params![session_name], |row| row.get(0))?
            .filter_map(|r| r.ok())
            .collect();
        Ok(rows)
    }
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
