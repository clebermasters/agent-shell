use std::{path::PathBuf, time::Duration};

use anyhow::{Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FavoriteEntry {
    pub id: String,
    pub name: String,
    pub path: String,
    pub sort_order: i32,
    pub startup_command: Option<String>,
    pub startup_args: Option<String>,
    pub created_at: i64,
    pub tag_ids: Vec<String>,
}

pub struct FavoriteStore {
    db_path: PathBuf,
}

impl FavoriteStore {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        let db_path = base_dir.join("favorites.db");
        let store = Self { db_path };
        store.init()?;
        Ok(store)
    }

    fn init(&self) -> Result<()> {
        let conn = self.open()?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS favorites (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                path TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                startup_command TEXT,
                startup_args TEXT,
                created_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_favorites_sort ON favorites(sort_order ASC, created_at ASC);

            CREATE TABLE IF NOT EXISTS favorite_tag_assignments (
                favorite_id TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY (favorite_id, tag_id),
                FOREIGN KEY (favorite_id) REFERENCES favorites(id) ON DELETE CASCADE
            );
            "#,
        )?;
        Ok(())
    }

    fn open(&self) -> Result<Connection> {
        let conn = Connection::open(&self.db_path)
            .with_context(|| format!("failed to open favorites db: {}", self.db_path.display()))?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute("PRAGMA foreign_keys = ON", [])?;
        Ok(conn)
    }

    fn tag_ids_for(&self, conn: &Connection, favorite_id: &str) -> Vec<String> {
        let mut stmt = match conn
            .prepare("SELECT tag_id FROM favorite_tag_assignments WHERE favorite_id=?1 ORDER BY rowid")
        {
            Ok(s) => s,
            Err(_) => return vec![],
        };
        stmt.query_map(params![favorite_id], |row| row.get(0))
            .map(|rows| rows.filter_map(|r| r.ok()).collect())
            .unwrap_or_default()
    }

    fn set_tags(&self, conn: &Connection, favorite_id: &str, tag_ids: &[String]) -> Result<()> {
        conn.execute(
            "DELETE FROM favorite_tag_assignments WHERE favorite_id=?1",
            params![favorite_id],
        )?;
        for tag_id in tag_ids {
            conn.execute(
                "INSERT OR IGNORE INTO favorite_tag_assignments (favorite_id, tag_id) VALUES (?1, ?2)",
                params![favorite_id, tag_id],
            )?;
        }
        Ok(())
    }

    pub fn list(&self) -> Result<Vec<FavoriteEntry>> {
        let conn = self.open()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, path, sort_order, startup_command, startup_args, created_at \
             FROM favorites ORDER BY sort_order ASC, created_at ASC",
        )?;
        let rows: Vec<FavoriteEntry> = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i32>(3)?,
                    row.get::<_, Option<String>>(4)?,
                    row.get::<_, Option<String>>(5)?,
                    row.get::<_, i64>(6)?,
                ))
            })?
            .filter_map(|r| r.ok())
            .map(|(id, name, path, sort_order, startup_command, startup_args, created_at)| {
                let tag_ids = self.tag_ids_for(&conn, &id);
                FavoriteEntry { id, name, path, sort_order, startup_command, startup_args, created_at, tag_ids }
            })
            .collect();
        Ok(rows)
    }

    pub fn add(
        &self,
        name: String,
        path: String,
        sort_order: i32,
        startup_command: Option<String>,
        startup_args: Option<String>,
        tag_ids: Vec<String>,
    ) -> Result<FavoriteEntry> {
        let conn = self.open()?;
        let id = Uuid::new_v4().to_string();
        let created_at = now_millis();
        conn.execute(
            "INSERT INTO favorites (id, name, path, sort_order, startup_command, startup_args, created_at) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![id, name, path, sort_order, startup_command, startup_args, created_at],
        )?;
        self.set_tags(&conn, &id, &tag_ids)?;
        Ok(FavoriteEntry { id, name, path, sort_order, startup_command, startup_args, created_at, tag_ids })
    }

    pub fn update(
        &self,
        id: &str,
        name: String,
        path: String,
        sort_order: i32,
        startup_command: Option<String>,
        startup_args: Option<String>,
        tag_ids: Vec<String>,
    ) -> Result<Option<FavoriteEntry>> {
        let conn = self.open()?;
        let rows_changed = conn.execute(
            "UPDATE favorites SET name=?2, path=?3, sort_order=?4, startup_command=?5, startup_args=?6 WHERE id=?1",
            params![id, name, path, sort_order, startup_command, startup_args],
        )?;
        if rows_changed == 0 {
            return Ok(None);
        }
        self.set_tags(&conn, id, &tag_ids)?;
        let entry = conn
            .query_row(
                "SELECT id, name, path, sort_order, startup_command, startup_args, created_at \
                 FROM favorites WHERE id=?1",
                params![id],
                |row| {
                    Ok(FavoriteEntry {
                        id: row.get(0)?,
                        name: row.get(1)?,
                        path: row.get(2)?,
                        sort_order: row.get(3)?,
                        startup_command: row.get(4)?,
                        startup_args: row.get(5)?,
                        created_at: row.get(6)?,
                        tag_ids: vec![],
                    })
                },
            )
            .optional()?
            .map(|mut e| {
                e.tag_ids = self.tag_ids_for(&conn, &e.id);
                e
            });
        Ok(entry)
    }

    pub fn set_tags_for(&self, favorite_id: &str, tag_ids: Vec<String>) -> Result<Vec<String>> {
        let conn = self.open()?;
        self.set_tags(&conn, favorite_id, &tag_ids)?;
        Ok(tag_ids)
    }

    pub fn delete(&self, id: &str) -> Result<bool> {
        let conn = self.open()?;
        let rows_changed = conn.execute("DELETE FROM favorites WHERE id=?1", params![id])?;
        Ok(rows_changed > 0)
    }
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
