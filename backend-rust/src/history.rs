//! Disk-backed history snapshots. Only one message and the requested page are
//! decoded at a time; snapshots survive requests without retaining JSON in RAM.
use crate::chat_log::{AiTool, ChatMessage};
use anyhow::{bail, Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use serde::de::DeserializeOwned;
use std::{
    fs::File,
    io::{BufRead, BufReader, Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{Arc, OnceLock},
};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

pub const INLINE_RECORD_BYTES: usize = 256 * 1024;
pub const PAGE_BYTES: usize = 1024 * 1024;
pub const MAX_PAGE_MESSAGES: usize = 200;

/// Tune this process only; unlike MALLOC_ARENA_MAX, this is not inherited by agents.
pub fn configure_allocator() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    unsafe {
        libc::mallopt(libc::M_ARENA_MAX, 4);
    }
}

/// Capacity is acquired before starting blocking work. Dropping a caller never
/// releases capacity while its already-started blocking operation is still running.
pub async fn permit() -> Result<OwnedSemaphorePermit> {
    static WORK: OnceLock<Arc<Semaphore>> = OnceLock::new();
    static WAITING: OnceLock<Arc<Semaphore>> = OnceLock::new();
    let _admission = WAITING
        .get_or_init(|| Arc::new(Semaphore::new(16)))
        .clone()
        .try_acquire_owned()
        .map_err(|_| anyhow::anyhow!("History queue is busy; retry shortly"))?;
    Ok(WORK
        .get_or_init(|| Arc::new(Semaphore::new(1)))
        .clone()
        .acquire_owned()
        .await?)
}

#[derive(Debug)]
pub struct Page {
    pub messages: Vec<ChatMessage>,
    pub total: usize,
    pub has_more: bool,
}

#[derive(serde::Serialize)]
pub struct SyncEntry {
    pub key: String,
    pub position: usize,
    pub message: Option<ChatMessage>,
}
pub struct SyncPage {
    pub entries: Vec<SyncEntry>,
    pub total: usize,
    pub has_more: bool,
}
fn body_key(body: &str) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(body.as_bytes()))
}

pub struct Index {
    conn: Connection,
}
impl Index {
    pub fn open(path: &Path) -> Result<Self> {
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        // Full transcripts can contain private data even when the backend's
        // installation directory is readable by other local accounts.
        let file=std::fs::OpenOptions::new().create(true).write(true).truncate(false).mode(0o600).open(path)?;
        file.set_permissions(std::fs::Permissions::from_mode(0o600))?;
        drop(file);
        for suffix in ["-wal","-shm","-journal"] {
            let sidecar=PathBuf::from(format!("{}{suffix}",path.display()));
            match std::fs::set_permissions(sidecar,std::fs::Permissions::from_mode(0o600)) {
                Ok(())=>{},Err(error) if error.kind()==std::io::ErrorKind::NotFound=>{},Err(error)=>return Err(error.into()),
            }
        }
        let conn = Connection::open(path)?;
        conn.busy_timeout(std::time::Duration::from_secs(10))?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA cache_size=-2048; PRAGMA temp_store=FILE;
            CREATE TABLE IF NOT EXISTS history_snapshots (key TEXT PRIMARY KEY, revision TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS history_messages (key TEXT NOT NULL, seq INTEGER NOT NULL, time INTEGER NOT NULL, body TEXT NOT NULL, body_key TEXT NOT NULL DEFAULT '', PRIMARY KEY(key,seq));
            CREATE INDEX IF NOT EXISTS history_message_time ON history_messages(key,time,seq);")?;
        let has_key = conn
            .prepare("PRAGMA table_info(history_messages)")?
            .query_map([], |r| r.get::<_, String>(1))?
            .collect::<std::result::Result<Vec<_>, _>>()?
            .iter()
            .any(|name| name == "body_key");
        if !has_key {
            conn.execute_batch("ALTER TABLE history_messages ADD COLUMN body_key TEXT NOT NULL DEFAULT ''; DELETE FROM history_messages; DELETE FROM history_snapshots; DROP TABLE IF EXISTS history_sources;")?;
        }
        conn.execute_batch(
            "CREATE INDEX IF NOT EXISTS history_body_key ON history_messages(key,body_key)",
        )?;
        Ok(Self { conn })
    }
    pub fn current(&self, key: &str, revision: &str) -> Result<bool> {
        Ok(self
            .conn
            .query_row(
                "SELECT revision FROM history_snapshots WHERE key=?",
                [key],
                |r| r.get::<_, String>(0),
            )
            .optional()?
            .as_deref()
            == Some(revision))
    }
    pub fn replace(
        &mut self,
        key: &str,
        revision: &str,
        producer: impl FnOnce(&mut dyn FnMut(ChatMessage) -> Result<()>) -> Result<()>,
    ) -> Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute("DELETE FROM history_messages WHERE key=?", [key])?;
        let mut seq = 0i64;
        {
            let mut insert = tx.prepare_cached(
                "INSERT INTO history_messages(key,seq,time,body,body_key) VALUES (?,?,?,?,?)",
            )?;
            producer(&mut |message| {
                let time = message.timestamp.map(|v| v.timestamp_millis()).unwrap_or(0);
                let body = serde_json::to_string(&message)?;
                insert.execute(params![key, seq, time, body, body_key(&body)])?;
                seq += 1;
                Ok(())
            })?;
        }
        tx.execute(
            "INSERT OR REPLACE INTO history_snapshots(key,revision) VALUES (?,?)",
            params![key, revision],
        )?;
        tx.commit()?;
        Ok(())
    }
    pub fn conditional_page(
        &self,
        key: &str,
        base: Option<&str>,
        cleared: Option<i64>,
        offset: usize,
        limit: usize,
    ) -> Result<SyncPage> {
        // Hold one WAL read snapshot across existence, count, body-elision,
        // and page reads. A concurrent retention pass must never look empty.
        let read=self.conn.unchecked_transaction()?;
        let exists=read.query_row("SELECT 1 FROM history_snapshots WHERE key=?",[key],|r|r.get::<_,i64>(0)).optional()?.is_some();
        if !exists {bail!("History snapshot changed; retry its revision");}
        let total = read.query_row(
            "SELECT COUNT(*) FROM history_messages WHERE key=? AND (? IS NULL OR time>?)",
            params![key, cleared, cleared.unwrap_or(i64::MIN)],
            |r| r.get::<_, i64>(0),
        )? as usize;
        let mut stmt=read.prepare("SELECT n.body_key,CASE WHEN n.body_key!='' AND EXISTS(SELECT 1 FROM history_messages b WHERE b.key=? AND b.body_key=n.body_key) THEN NULL ELSE n.body END FROM history_messages n WHERE n.key=? AND (? IS NULL OR n.time>?) ORDER BY n.time DESC,n.seq DESC LIMIT ? OFFSET ?")?;
        let mut rows = stmt.query(params![
            base,
            key,
            cleared,
            cleared.unwrap_or(i64::MIN),
            limit.clamp(1, MAX_PAGE_MESSAGES) as i64,
            offset.min(i64::MAX as usize) as i64
        ])?;
        let mut entries = Vec::new();
        let mut bytes = 0usize;
        while let Some(row) = rows.next()? {
            let mut key: String = row.get(0)?;
            let body: Option<String> = row.get(1)?;
            let size = body.as_ref().map_or(0, String::len) + 128;
            if !entries.is_empty() && bytes + size > PAGE_BYTES {
                break;
            }
            bytes += size;
            if key.is_empty() {
                key = body_key(body.as_deref().context("Missing history body key")?);
            }
            let position =
                total.saturating_sub(offset.saturating_add(entries.len()).saturating_add(1));
            entries.push(SyncEntry {
                key,
                position,
                message: body.map(|s| serde_json::from_str(&s)).transpose()?,
            });
        }
        let has_more = offset.saturating_add(entries.len()) < total;
        entries.reverse();
        Ok(SyncPage {
            entries,
            total,
            has_more,
        })
    }
    pub fn prune_snapshots(&mut self,prefix:&str)->Result<()> {
        // Delete the snapshot marker and its rows atomically. Readers either
        // retain the old snapshot or get a retry, never an authoritative empty page.
        let tx=self.conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let keys={
            let mut stmt=tx.prepare("SELECT key FROM history_snapshots WHERE substr(key,1,?)=? ORDER BY rowid DESC LIMIT -1 OFFSET 2")?;
            let keys=stmt.query_map(params![prefix.chars().count() as i64,prefix],|r|r.get::<_,String>(0))?.collect::<std::result::Result<Vec<_>,_>>()?;
            keys
        };
        for key in keys{tx.execute("DELETE FROM history_messages WHERE key=?",[&key])?;tx.execute("DELETE FROM history_snapshots WHERE key=?",[&key])?;}
        tx.commit()?;Ok(())
    }
    pub fn visit(&self, key: &str, sink: &mut dyn FnMut(ChatMessage) -> Result<()>) -> Result<()> {
        let mut stmt = self
            .conn
            .prepare("SELECT body FROM history_messages WHERE key=? ORDER BY seq")?;
        let mut rows = stmt.query([key])?;
        while let Some(row) = rows.next()? {
            let body: String = row.get(0)?;
            sink(serde_json::from_str(&body)?)?;
        }
        Ok(())
    }
    pub fn page(
        &self,
        key: &str,
        cleared: Option<i64>,
        offset: usize,
        limit: usize,
        chronological: bool,
    ) -> Result<Page> {
        let read=self.conn.unchecked_transaction()?;
        let min = cleared.unwrap_or(i64::MIN);
        let total = read.query_row(
            "SELECT COUNT(*) FROM history_messages WHERE key=? AND (? IS NULL OR time>?)",
            params![key, cleared, min],
            |r| r.get::<_, i64>(0),
        )? as usize;
        let sql = if chronological {
            "SELECT body FROM history_messages WHERE key=? AND (? IS NULL OR time>?) ORDER BY time DESC,seq DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT body FROM history_messages WHERE key=? AND (? IS NULL OR time>?) ORDER BY seq DESC LIMIT ? OFFSET ?"
        };
        let mut stmt = read.prepare(sql)?;
        let mut rows = stmt.query(params![
            key,
            cleared,
            min,
            limit.clamp(1, MAX_PAGE_MESSAGES) as i64,
            offset.min(i64::MAX as usize) as i64
        ])?;
        let mut messages = Vec::new();
        let mut bytes = 0;
        while let Some(row) = rows.next()? {
            let body: String = row.get(0)?;
            // A single oversized message is preserved intact for legacy clients.
            // Additional messages never push an ordinary page over the byte budget.
            if !messages.is_empty() && bytes + body.len() > PAGE_BYTES {
                break;
            }
            bytes += body.len();
            messages.push(serde_json::from_str(&body)?);
        }
        let has_more = offset.saturating_add(messages.len()) < total;
        messages.reverse();
        tracing::debug!(target:"agentshell_backend::history",count=messages.len(),bytes,total,"history page");
        Ok(Page {
            messages,
            total,
            has_more,
        })
    }
}

pub fn fingerprint(path: &Path) -> Result<String> {
    use std::os::unix::fs::MetadataExt;
    let m = std::fs::metadata(path)?;
    Ok(format!(
        "v1:{}:{}:{}:{}:{}:{}",
        m.dev(),
        m.ino(),
        m.len(),
        m.mtime(),
        m.mtime_nsec(),
        m.ctime_nsec()
    ))
}

/// File-based JSON spans let a thread containing thousands of turns be walked
/// without deserializing its complete result, turns, or item arrays.
#[derive(Clone, Copy, Debug)]
pub struct Span {
    pub start: u64,
    pub len: u64,
}
pub struct JsonFile {
    reader: BufReader<File>,
}
impl JsonFile {
    pub fn open(path: &Path) -> Result<Self> {
        Ok(Self {
            reader: BufReader::with_capacity(64 * 1024, File::open(path)?),
        })
    }
    fn position(&mut self) -> Result<u64> {
        Ok(self.reader.stream_position()?)
    }
    fn seek(&mut self, pos: u64) -> Result<()> {
        self.reader.seek(SeekFrom::Start(pos))?;
        Ok(())
    }
    fn peek(&mut self) -> Result<Option<u8>> {
        Ok(self.reader.fill_buf()?.first().copied())
    }
    fn byte(&mut self) -> Result<u8> {
        let b = self.peek()?.context("Unexpected end of JSON")?;
        self.reader.consume(1);
        Ok(b)
    }
    fn whitespace(&mut self) -> Result<()> {
        while self.peek()?.is_some_and(|b| b.is_ascii_whitespace()) {
            self.reader.consume(1);
        }
        Ok(())
    }
    fn value(&mut self) -> Result<Span> {
        self.whitespace()?;
        let start = self.position()?;
        let first = self.byte()?;
        let mut depth = usize::from(first == b'{' || first == b'[');
        let mut string = first == b'"';
        let mut escaped = false;
        if depth == 0 && !string {
            while self
                .peek()?
                .is_some_and(|b| !b.is_ascii_whitespace() && !b",]}".contains(&b))
            {
                self.reader.consume(1);
            }
        } else {
            'outer: loop {
                let buf = self.reader.fill_buf()?;
                if buf.is_empty() {
                    bail!("Incomplete JSON value");
                }
                let mut used = 0;
                for &b in buf {
                    used += 1;
                    if string {
                        if escaped {
                            escaped = false;
                        } else if b == b'\\' {
                            escaped = true;
                        } else if b == b'"' {
                            string = false;
                            if depth == 0 {
                                self.reader.consume(used);
                                break 'outer;
                            }
                        }
                    } else {
                        match b {
                            b'"' => string = true,
                            b'{' | b'[' => {
                                depth += 1;
                                if depth > 256 {
                                    bail!("JSON nesting limit exceeded");
                                }
                            }
                            b'}' | b']' => {
                                depth -= 1;
                                if depth == 0 {
                                    self.reader.consume(used);
                                    break 'outer;
                                }
                            }
                            _ => {}
                        }
                    }
                }
                self.reader.consume(used);
            }
        }
        Ok(Span {
            start,
            len: self.position()? - start,
        })
    }
    pub fn root(&mut self) -> Result<Span> {
        self.seek(0)?;
        self.value()
    }
    pub fn decode<T: DeserializeOwned>(&mut self, span: Span) -> Result<T> {
        self.seek(span.start)?;
        Ok(serde_json::from_reader((&mut self.reader).take(span.len))?)
    }
    pub fn fields(&mut self, span: Span) -> Result<Vec<(String, Span)>> {
        self.seek(span.start)?;
        self.whitespace()?;
        if self.byte()? != b'{' {
            bail!("Expected JSON object");
        }
        let mut fields = Vec::new();
        loop {
            self.whitespace()?;
            if self.peek()? == Some(b'}') {
                self.byte()?;
                break;
            }
            let name = self.value()?;
            if name.len > 4096 {
                bail!("JSON field name too large");
            }
            let next = self.position()?;
            let key: String = self.decode(name)?;
            self.seek(next)?;
            self.whitespace()?;
            if self.byte()? != b':' {
                bail!("Expected JSON colon");
            }
            let value = self.value()?;
            fields.push((key, value));
            self.whitespace()?;
            match self.byte()? {
                b',' => {}
                b'}' => break,
                _ => bail!("Expected JSON object delimiter"),
            }
        }
        Ok(fields)
    }
    pub fn field(&mut self, span: Span, name: &str) -> Result<Option<Span>> {
        Ok(self
            .fields(span)?
            .into_iter()
            .find(|(k, _)| k == name)
            .map(|(_, s)| s))
    }
    pub fn array(
        &mut self,
        span: Span,
        mut visit: impl FnMut(&mut Self, Span) -> Result<()>,
    ) -> Result<()> {
        self.seek(span.start)?;
        self.whitespace()?;
        if self.byte()? != b'[' {
            bail!("Expected JSON array");
        }
        loop {
            self.whitespace()?;
            if self.peek()? == Some(b']') {
                self.byte()?;
                break;
            }
            let value = self.value()?;
            let next = self.position()?;
            visit(self, value)?;
            self.seek(next)?;
            self.whitespace()?;
            match self.byte()? {
                b',' => {}
                b']' => break,
                _ => bail!("Expected JSON array delimiter"),
            }
        }
        Ok(())
    }
}

/// Preserve unusually large records as content-addressed files. This bounds
/// parser memory and keeps Android SQLite rows below CursorWindow's limit.
/// The complete original record remains downloadable and available offline.
pub fn archive_large(
    mut source: impl Read,
    directory: &Path,
    role: &str,
    timestamp: Option<chrono::DateTime<chrono::Utc>>,
) -> Result<ChatMessage> {
    use sha2::{Digest, Sha256};
    use std::io::Write;
    std::fs::create_dir_all(directory)?;
    let mut file = tempfile::NamedTempFile::new_in(directory)?;
    let mut hash = Sha256::new();
    let mut size = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = source.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
        file.write_all(&buffer[..n])?;
        size += n as u64;
    }
    let id = format!("history-{:x}", hash.finalize());
    let path = directory.join(format!("{id}.json"));
    if !path.exists() {
        if let Err(error)=file.persist_noclobber(&path) {
            if error.error.kind()!=std::io::ErrorKind::AlreadyExists{return Err(error.error.into());}
        }
    }
    Ok(ChatMessage {
        role: role.into(),
        timestamp,
        blocks: vec![
            crate::chat_log::ContentBlock::Text {
                text: "The full message is available in the attached file.".into(),
            },
            crate::chat_log::ContentBlock::File {
                id,
                filename: "full-message.json".into(),
                mime_type: "application/json".into(),
                size_bytes: Some(size),
            },
        ],
    })
}

pub fn attachment_directory() -> Result<PathBuf> {
    Ok(std::env::current_dir()?.join("chat_files"))
}

enum Record {
    Inline(Vec<u8>),
    Spool(tempfile::NamedTempFile),
}
fn record(reader: &mut impl BufRead) -> Result<Option<Record>> {
    use std::io::Write;
    let mut bytes = Vec::new();
    let mut disk: Option<tempfile::NamedTempFile> = None;
    loop {
        let buffer = reader.fill_buf()?;
        if buffer.is_empty() {
            break;
        }
        let newline = buffer.iter().position(|&b| b == b'\n');
        let n = newline.map(|p| p + 1).unwrap_or(buffer.len());
        if disk.is_none() && bytes.len() + n > INLINE_RECORD_BYTES {
            let mut file = tempfile::NamedTempFile::new()?;
            file.write_all(&bytes)?;
            bytes.clear();
            disk = Some(file);
        }
        if let Some(file) = disk.as_mut() {
            file.write_all(&buffer[..n])?;
        } else {
            bytes.extend_from_slice(&buffer[..n]);
        }
        reader.consume(n);
        if newline.is_some() {
            break;
        }
    }
    Ok(if let Some(file) = disk {
        Some(Record::Spool(file))
    } else if bytes.is_empty() {
        None
    } else {
        Some(Record::Inline(bytes))
    })
}

pub fn context_records(path: &Path) -> Result<impl Iterator<Item = serde_json::Value>> {
    let mut reader = BufReader::new(File::open(path)?);
    Ok(std::iter::from_fn(move || loop {
        let record = record(&mut reader).ok()??;
        let value = match record {
            Record::Inline(bytes) => serde_json::from_slice(&bytes).ok(),
            Record::Spool(file) => (|| -> Result<serde_json::Value> {
                let mut json = JsonFile::open(file.path())?;
                let root = json.root()?;
                let mut out = serde_json::Map::new();
                if let Some(span) = json.field(root, "type")? {
                    if span.len < 1024 {
                        out.insert("type".into(), json.decode(span)?);
                    }
                }
                for (field, keys) in [
                    ("message", &["usage", "model"][..]),
                    (
                        "payload",
                        &["type", "info", "model", "model_context_window"][..],
                    ),
                ] {
                    if let Some(parent) = json.field(root, field)? {
                        let mut metadata = serde_json::Map::new();
                        for key in keys {
                            if let Some(span) = json.field(parent, key)? {
                                if span.len < 16 * 1024 {
                                    metadata.insert((*key).into(), json.decode(span)?);
                                }
                            }
                        }
                        out.insert(field.into(), metadata.into());
                    }
                }
                Ok(out.into())
            })()
            .ok(),
        };
        if let Some(value) = value {
            return Some(value);
        }
    }))
}

fn large_transcript(file: &Path, tool: &AiTool) -> Result<Option<ChatMessage>> {
    let mut json = JsonFile::open(file)?;
    let root = json.root()?;
    let Some(kind) = json.field(root, "type")? else {
        return Ok(None);
    };
    let kind: String = json.decode(kind)?;
    let (keep, role) = match tool {
        AiTool::Claude => (
            matches!(
                kind.as_str(),
                "user" | "assistant" | "tool_use" | "tool_result"
            ),
            if kind == "user" { "user" } else { "assistant" },
        ),
        AiTool::Codex => {
            let payload = json.field(root, "payload")?;
            let subtype = if let Some(payload) = payload {
                if let Some(kind) = json.field(payload, "type")? {
                    json.decode::<String>(kind)?
                } else {
                    String::new()
                }
            } else {
                String::new()
            };
            (
                kind == "event_msg"
                    && matches!(
                        subtype.as_str(),
                        "user_message"
                            | "agent_message"
                            | "item_completed"
                            | "exec_command_end"
                            | "patch_apply_end"
                    )
                    || kind == "response_item"
                        && matches!(subtype.as_str(), "function_call" | "function_call_output"),
                if subtype == "user_message" {
                    "user"
                } else {
                    "assistant"
                },
            )
        }
        _ => (false, "assistant"),
    };
    if !keep {
        return Ok(None);
    }
    let timestamp = if let Some(span) = json.field(root, "timestamp")? {
        json.decode::<Option<chrono::DateTime<chrono::Utc>>>(span)?
    } else {
        None
    };
    Ok(Some(archive_large(
        File::open(file)?,
        &attachment_directory()?,
        role,
        timestamp,
    )?))
}

/// Incremental transcript index. Offsets and parsed rows are committed together;
/// an incomplete trailing record is deliberately retried after the next append.
fn parsed_record(record: Record, tool: &AiTool) -> Result<Option<Option<ChatMessage>>> {
    Ok(Some(match record {
        Record::Inline(bytes) => {
            if !bytes.ends_with(b"\n")
                && serde_json::from_slice::<serde::de::IgnoredAny>(&bytes).is_err()
            {
                return Ok(None);
            }
            let line = match std::str::from_utf8(&bytes) {
                Ok(line) => line,
                Err(_) if !bytes.ends_with(b"\n") => return Ok(None),
                Err(error) => return Err(error.into()),
            };
            match tool {
                AiTool::Claude => crate::chat_log::claude_parser::parse_line(line),
                AiTool::Codex => crate::chat_log::codex_parser::parse_line(line),
                _ => bail!("Unsupported transcript provider"),
            }
        }
        Record::Spool(file) => match large_transcript(file.path(), tool) {
            Ok(message) => message,
            Err(error) => {
                use std::os::unix::fs::FileExt;
                let len = file.as_file().metadata()?.len();
                let mut last = [0u8; 1];
                if len > 0 {
                    file.as_file().read_exact_at(&mut last, len - 1)?;
                }
                if last[0] != b'\n' {
                    return Ok(None);
                }
                tracing::warn!(%error,"Skipping malformed transcript record");
                None
            }
        },
    }))
}

pub const LIVE_BATCH: usize = 32;
pub fn new_transcript_messages(
    path: &Path,
    pos: &mut u64,
    tool: &AiTool,
) -> Result<Vec<ChatMessage>> {
    let file = File::open(path)?;
    if file.metadata()?.len() < *pos {
        *pos = 0;
    }
    let mut reader = BufReader::new(file);
    reader.seek(SeekFrom::Start(*pos))?;
    let mut messages = Vec::new();
    while messages.len() < LIVE_BATCH {
        let Some(record) = record(&mut reader)? else {
            break;
        };
        let Some(message) = parsed_record(record, tool)? else {
            break;
        };
        if let Some(message) = message {
            messages.push(message);
        }
        *pos = reader.stream_position()?;
    }
    Ok(messages)
}

pub fn cached_transcript_page(
    path: &Path,
    tool: &AiTool,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<Option<Page>> {
    let index = Index::open(&transcript_cache_path()?)?;
    let key = format!("{}:{tool:?}", path.display());
    if index.current(&key, &fingerprint(path)?)? {
        Ok(Some(index.page(&key, cleared, offset, limit, false)?))
    } else {
        Ok(None)
    }
}
pub fn transcript_offset(path: &Path, tool: &AiTool) -> Result<u64> {
    let index = Index::open(&transcript_cache_path()?)?;
    let key = format!("{}:{tool:?}", path.display());
    Ok(index.conn.query_row(
        "SELECT offset FROM history_sources WHERE key=?",
        [key],
        |r| r.get::<_, i64>(0),
    )? as u64)
}

pub fn transcript_page(
    path: &Path,
    tool: &AiTool,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<Page> {
    use std::os::unix::fs::MetadataExt;
    let mut index = Index::open(&transcript_cache_path()?)?;
    let key = format!("{}:{tool:?}", path.display());
    let rev = fingerprint(path)?;
    if !index.current(&key, &rev)? {
        let meta = std::fs::metadata(path)?;
        let identity = format!("{}:{}", meta.dev(), meta.ino());
        index.conn.execute_batch("CREATE TABLE IF NOT EXISTS history_sources(key TEXT PRIMARY KEY,identity TEXT NOT NULL,offset INTEGER NOT NULL,length INTEGER NOT NULL)")?;
        let previous: Option<(String, i64, i64)> = index
            .conn
            .query_row(
                "SELECT identity,offset,length FROM history_sources WHERE key=?",
                [&key],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let append = previous
            .as_ref()
            .is_some_and(|(id, _, len)| id == &identity && meta.len() > *len as u64);
        let position = if append {
            previous.as_ref().unwrap().1 as u64
        } else {
            0
        };
        let tx = index.conn.transaction()?;
        if !append {
            tx.execute("DELETE FROM history_messages WHERE key=?", [&key])?;
        }
        let mut seq = tx.query_row(
            "SELECT COALESCE(MAX(seq)+1,0) FROM history_messages WHERE key=?",
            [&key],
            |r| r.get::<_, i64>(0),
        )?;
        let mut reader = BufReader::new(File::open(path)?);
        reader.seek(SeekFrom::Start(position))?;
        let mut committed = position;
        while let Some(record) = record(&mut reader)? {
            let Some(message) = parsed_record(record, tool)? else {
                break;
            };
            if let Some(message) = message {
                tx.execute(
                    "INSERT INTO history_messages(key,seq,time,body,body_key) VALUES (?,?,?,?,?)",
                    params![
                        key,
                        seq,
                        message.timestamp.map(|v| v.timestamp_millis()).unwrap_or(0),
                        serde_json::to_string(&message)?,
                        body_key(&serde_json::to_string(&message)?)
                    ],
                )?;
                seq += 1;
            }
            committed = reader.stream_position()?;
        }
        tx.execute(
            "INSERT OR REPLACE INTO history_sources VALUES (?,?,?,?)",
            params![key, identity, committed as i64, meta.len() as i64],
        )?;
        tx.execute(
            "INSERT OR REPLACE INTO history_snapshots VALUES (?,?)",
            params![key, rev],
        )?;
        tx.commit()?;
    }
    index.page(&key, cleared, offset, limit, false)
}
pub fn sqlite_revision(path: &Path) -> Result<String> {
    let wal = PathBuf::from(format!("{}-wal", path.display()));
    Ok(format!(
        "{}:{}",
        fingerprint(path)?,
        fingerprint(&wal).unwrap_or_default()
    ))
}
pub fn opencode_native_page(
    path: &Path,
    session: &str,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<(Page, i64)> {
    let mut index = Index::open(&transcript_cache_path()?)?;
    let key = format!("opencode:{}:{session}", path.display());
    let revision = sqlite_revision(path)?;
    let conn = Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut max = conn.query_row(
        "SELECT COALESCE(MAX(time_updated),0) FROM part WHERE session_id=?",
        [session],
        |r| r.get(0),
    )?;
    if !index.current(&key, &revision)? {
        index.replace(&key, &revision, |sink| {
            max = crate::chat_log::opencode_parser::visit_all_messages(path, session, None, sink)?;
            Ok(())
        })?;
    }
    Ok((index.page(&key, cleared, offset, limit, false)?, max))
}

pub fn cached_bound_page(
    events: &crate::chat_event_store::ChatEventStore,
    binding: &crate::chat_binding::ChatBinding,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<Option<Page>> {
    let revision = bound_revision(events, binding)?;
    let key = format!("bound:{}", binding.storage_key());
    let index = Index::open(&events.history_index_path())?;
    if index.current(&key, &revision)? {
        Ok(Some(index.page(&key, cleared, offset, limit, true)?))
    } else {
        Ok(None)
    }
}
fn bound_revision(
    events: &crate::chat_event_store::ChatEventStore,
    binding: &crate::chat_binding::ChatBinding,
) -> Result<String> {
    Ok(format!(
        "bound-v1:{}:{}",
        if binding.transcript_path.as_os_str().is_empty() || binding.pane.tool == "kiro" {
            "overlay".into()
        } else {
            fingerprint(&binding.transcript_path)?
        },
        events.history_revision(binding.storage_key(), 0)?
    ))
}

pub fn bound_page(
    events: &crate::chat_event_store::ChatEventStore,
    binding: &crate::chat_binding::ChatBinding,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<Page> {
    bound_page_at(events, binding, cleared, offset, limit, None)
}
pub fn bound_page_at(
    events: &crate::chat_event_store::ChatEventStore,
    binding: &crate::chat_binding::ChatBinding,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
    snapshot: Option<(String, String)>,
) -> Result<Page> {
    let source_revision = bound_revision(events, binding)?;
    let (key, revision) =
        snapshot.unwrap_or_else(|| (format!("bound:{}", binding.storage_key()), source_revision));
    let mut index = Index::open(&events.history_index_path())?;
    if !index.current(&key, &revision)? {
        index.replace(&key, &revision, |sink| {
            if !binding.transcript_path.as_os_str().is_empty() {
                match binding.pane.tool.as_str() {
                    "claude" | "codex" => {
                        let tool = binding.ai_tool();
                        transcript_page(&binding.transcript_path, &tool, None, 0, 1)?;
                        let transcript = Index::open(&transcript_cache_path()?)?;
                        transcript.visit(
                            &format!("{}:{tool:?}", binding.transcript_path.display()),
                            sink,
                        )?;
                    }
                    "opencode" => {
                        crate::chat_log::opencode_parser::visit_all_messages(
                            &binding.transcript_path,
                            &binding.conversation_id,
                            None,
                            sink,
                        )?;
                    }
                    "kiro" => {}
                    _ => bail!("Unsupported history provider"),
                }
            }
            events.visit_history(binding.storage_key(), 0, false, sink)
        })?;
    }
    index.page(&key, cleared, offset, limit, true)
}

fn transcript_cache_path() -> Result<PathBuf> {
    if let Some(path) = std::env::var_os("AGENTSHELL_HISTORY_CACHE") {
        return Ok(path.into());
    }
    let directory = dirs::cache_dir()
        .context("Cache directory is unavailable")?
        .join("agentshell");
    std::fs::create_dir_all(&directory)?;
    Ok(directory.join("transcript_history.sqlite"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat_log::ContentBlock;
    fn message(i: usize) -> ChatMessage {
        ChatMessage {
            role: "assistant".into(),
            timestamp: None,
            blocks: vec![ContentBlock::Text {
                text: format!("message {i}"),
            }],
        }
    }
    #[test]
    fn retention_preserves_active_read_snapshots_and_eviction_returns_retry() {
        let directory=tempfile::tempdir().unwrap();let path=directory.path().join("history.db");
        let mut writer=Index::open(&path).unwrap();
        for n in 1..=3{writer.replace(&format!("chat:{n}"),"r",|sink|sink(message(n))).unwrap();}
        let reader=Index::open(&path).unwrap();
        let read=reader.conn.unchecked_transaction().unwrap();
        assert_eq!(read.query_row("SELECT COUNT(*) FROM history_messages WHERE key='chat:1'",[],|r|r.get::<_,i64>(0)).unwrap(),1);
        writer.prune_snapshots("chat:").unwrap();
        assert_eq!(read.query_row("SELECT COUNT(*) FROM history_messages WHERE key='chat:1'",[],|r|r.get::<_,i64>(0)).unwrap(),1);
        drop(read);
        let error=reader.conditional_page("chat:1",None,None,0,30).err().unwrap();
        assert!(error.to_string().contains("changed"));
        assert_eq!(reader.conditional_page("chat:2",None,None,0,30).unwrap().entries.len(),1);
    }

    #[test]
    fn disk_cache_is_private_and_upgrades_existing_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let directory=tempfile::tempdir().unwrap();let path=directory.path().join("history.db");
        let mut index=Index::open(&path).unwrap();index.replace("one","r1",|sink|sink(message(1))).unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode()&0o777,0o600);
        drop(index);std::fs::set_permissions(&path,std::fs::Permissions::from_mode(0o644)).unwrap();
        let index=Index::open(&path).unwrap();assert!(index.current("one","r1").unwrap());
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode()&0o777,0o600);
    }

    #[test]
    fn live_batches_are_bounded_and_partial_utf8_is_retried_without_loss() {
        use std::io::Write;
        let mut file = tempfile::NamedTempFile::new().unwrap();
        for n in 0..100 {
            writeln!(file,"{}",serde_json::json!({"type":"event_msg","payload":{"type":"agent_message","message":format!("message {n}")}})).unwrap();
        }
        let tail=serde_json::json!({"type":"event_msg","payload":{"type":"agent_message","message":"last 😀"}}).to_string();
        let split = tail.find('😀').unwrap() + 1;
        file.write_all(&tail.as_bytes()[..split]).unwrap();
        let mut pos = 0;
        let mut count = 0;
        loop {
            let page = new_transcript_messages(file.path(), &mut pos, &AiTool::Codex).unwrap();
            assert!(page.len() <= LIVE_BATCH);
            count += page.len();
            if page.len() < LIVE_BATCH {
                break;
            }
        }
        assert_eq!(count, 100);
        let before = pos;
        assert!(
            new_transcript_messages(file.path(), &mut pos, &AiTool::Codex)
                .unwrap()
                .is_empty()
        );
        assert_eq!(pos, before);
        file.write_all(&tail.as_bytes()[split..]).unwrap();
        file.write_all(b"\n").unwrap();
        let last = new_transcript_messages(file.path(), &mut pos, &AiTool::Codex).unwrap();
        assert_eq!(last.len(), 1);
        assert!(serde_json::to_string(&last).unwrap().contains('😀'));
        assert_eq!(pos, file.as_file().metadata().unwrap().len());
    }

    #[test]
    fn changed_snapshots_send_only_changed_bodies_with_complete_stable_order() {
        let dir = tempfile::tempdir().unwrap();
        let mut index = Index::open(&dir.path().join("index.db")).unwrap();
        index
            .replace("old", "r1", |sink| {
                for n in 0..1003 {
                    sink(message(n))?;
                }
                Ok(())
            })
            .unwrap();
        index
            .replace("new", "r2", |sink| {
                for n in 0..1003 {
                    if n == 100 {
                        continue;
                    }
                    sink(if n == 50 { message(9999) } else { message(n) })?;
                }
                sink(message(1003))
            })
            .unwrap();
        let mut offset = 0;
        let mut bodies = 0;
        let mut positions = Vec::new();
        loop {
            let page = index
                .conditional_page("new", Some("old"), None, offset, 200)
                .unwrap();
            bodies += page
                .entries
                .iter()
                .filter(|entry| entry.message.is_some())
                .count();
            offset += page.entries.len();
            positions.extend(page.entries.into_iter().map(|entry| entry.position));
            if !page.has_more {
                break;
            }
        }
        assert_eq!(offset, 1003);
        assert_eq!(bodies, 2);
        positions.sort();
        assert_eq!(positions, (0..1003).collect::<Vec<_>>());
        assert!(index
            .conditional_page("new", Some("evicted"), None, 0, 30)
            .unwrap()
            .entries
            .iter()
            .all(|entry| entry.message.is_some()));
    }

    #[test]
    fn transcript_index_retries_partial_lines_and_rebuilds_after_truncation() {
        use std::io::Write;
        let mut source = tempfile::NamedTempFile::new().unwrap();
        let record = |text: &str| {
            serde_json::json!({"type":"event_msg","payload":{"type":"agent_message","message":text}}).to_string()
        };
        writeln!(source, "{}", record("one")).unwrap();
        assert_eq!(
            transcript_page(source.path(), &AiTool::Codex, None, 0, 30)
                .unwrap()
                .total,
            1
        );
        let second = record("two");
        let middle = second.len() / 2;
        source.write_all(&second.as_bytes()[..middle]).unwrap();
        assert_eq!(
            transcript_page(source.path(), &AiTool::Codex, None, 0, 30)
                .unwrap()
                .total,
            1
        );
        source.write_all(&second.as_bytes()[middle..]).unwrap();
        source.write_all(b"\n").unwrap();
        assert_eq!(
            transcript_page(source.path(), &AiTool::Codex, None, 0, 30)
                .unwrap()
                .total,
            2
        );
        source.as_file_mut().set_len(0).unwrap();
        source.as_file_mut().seek(SeekFrom::Start(0)).unwrap();
        writeln!(source, "{}", record("replacement")).unwrap();
        let page = transcript_page(source.path(), &AiTool::Codex, None, 0, 30).unwrap();
        assert_eq!(page.total, 1);
        assert!(serde_json::to_string(&page.messages)
            .unwrap()
            .contains("replacement"));
    }

    #[test]
    fn concurrent_large_records_share_one_complete_attachment() {
        let directory=tempfile::tempdir().unwrap();let bytes=vec![b'x';1024*1024];
        std::thread::scope(|scope|{
            let handles=(0..8).map(|_|scope.spawn(||archive_large(&bytes[..],directory.path(),"assistant",None))).collect::<Vec<_>>();
            for handle in handles{handle.join().unwrap().unwrap();}
        });
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(),1);
        let path=std::fs::read_dir(directory.path()).unwrap().next().unwrap().unwrap().path();
        assert_eq!(std::fs::read(path).unwrap(),bytes);
    }

    #[test]
    fn oversized_records_keep_every_original_byte_in_a_deduplicated_attachment() {
        let dir = tempfile::tempdir().unwrap();
        let bytes = serde_json::to_vec(&serde_json::json!({"text":"é😀".repeat(100_000)})).unwrap();
        let first = archive_large(&bytes[..], dir.path(), "assistant", None).unwrap();
        let second = archive_large(&bytes[..], dir.path(), "assistant", None).unwrap();
        assert_eq!(
            serde_json::to_string(&first).unwrap(),
            serde_json::to_string(&second).unwrap()
        );
        assert!(serde_json::to_vec(&first).unwrap().len() < 1024);
        let ContentBlock::File { id, .. } = &first.blocks[1] else {
            panic!("expected complete attachment")
        };
        assert_eq!(
            std::fs::read(dir.path().join(format!("{id}.json"))).unwrap(),
            bytes
        );
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn snapshot_retention_is_bounded_and_does_not_touch_other_conversations() {
        let dir = tempfile::tempdir().unwrap();
        let mut index = Index::open(&dir.path().join("index.db")).unwrap();
        for key in ["chat:😀:1", "other:1", "chat:😀:2", "chat:😀:3"] {
            index.replace(key, "r", |s| s(message(1))).unwrap();
        }
        index.prune_snapshots("chat:😀:").unwrap();
        assert!(!index.current("chat:😀:1", "r").unwrap());
        assert!(index.current("other:1", "r").unwrap());
        assert!(index.current("chat:😀:2", "r").unwrap());
        assert!(index.current("chat:😀:3", "r").unwrap());
    }

    #[test]
    fn disk_pages_keep_entire_archive_and_clamp_client_limits() {
        let d = tempfile::tempdir().unwrap();
        let mut i = Index::open(&d.path().join("index.db")).unwrap();
        i.replace("a", "r1", |sink| {
            for n in 0..503 {
                sink(message(n))?;
            }
            Ok(())
        })
        .unwrap();
        let p = i.page("a", None, 0, 30, false).unwrap();
        assert_eq!(p.total, 503);
        assert_eq!(p.messages.len(), 30);
        assert!(p.has_more);
        assert_eq!(
            i.page("a", None, 30, usize::MAX, false)
                .unwrap()
                .messages
                .len(),
            200
        );
        assert_eq!(
            i.page("a", None, 500, 200, false).unwrap().messages.len(),
            3
        );
        assert!(i.current("a", "r1").unwrap());
    }
    #[test]
    fn failed_reindex_preserves_committed_snapshot() {
        let d = tempfile::tempdir().unwrap();
        let mut i = Index::open(&d.path().join("index.db")).unwrap();
        i.replace("a", "r1", |s| s(message(1))).unwrap();
        assert!(i
            .replace("a", "r2", |s| {
                s(message(2))?;
                bail!("interrupted")
            })
            .is_err());
        assert!(i.current("a", "r1").unwrap());
        assert_eq!(i.page("a", None, 0, 30, false).unwrap().total, 1);
    }
    #[test]
    fn json_spans_handle_nested_values_escaped_strings_and_field_order() {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        use std::io::Write;
        write!(f,"{{\"result\":{{\"items\":[{{\"text\":\"a\\\"[b]\"}},{{\"text\":\"é😀\"}}]}},\"id\":7}}").unwrap();
        let mut j = JsonFile::open(f.path()).unwrap();
        let root = j.root().unwrap();
        let id = j.field(root, "id").unwrap().unwrap();
        assert_eq!(j.decode::<u64>(id).unwrap(), 7);
        let result = j.field(root, "result").unwrap().unwrap();
        let items = j.field(result, "items").unwrap().unwrap();
        let mut seen = 0;
        j.array(items, |j, s| {
            let value: serde_json::Value = j.decode(s)?;
            assert!(value["text"].is_string());
            seen += 1;
            Ok(())
        })
        .unwrap();
        assert_eq!(seen, 2);
    }
}
