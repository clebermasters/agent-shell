use anyhow::{bail, Context, Result};
use axum::{extract::State, http::StatusCode, Json};
use rusqlite::{params, Connection, OpenFlags, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock},
};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PaneIdentity {
    pub pane_id: String,
    pub server_pid: u32,
    pub server_start: u64,
    pub root_pid: u32,
    pub root_start: u64,
    pub agent_pid: u32,
    pub agent_start: u64,
    pub tool: String,
    pub cwd: String,
}

impl PaneIdentity {
    pub fn token(&self) -> String {
        format!(
            "{}:{}:{}:{}:{}:{}:{}",
            self.server_pid,
            self.server_start,
            self.pane_id,
            self.root_pid,
            self.root_start,
            self.agent_pid,
            self.agent_start
        )
    }
    fn pane_key(&self) -> String {
        format!("{}:{}:{}", self.server_pid, self.server_start, self.pane_id)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Conversation {
    pub id: String,
    pub tool: String,
    pub title: String,
    pub updated_at: i64,
    #[serde(skip_serializing)]
    pub path: PathBuf,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ChatBinding {
    pub binding_id: String,
    pub conversation_id: String,
    pub conversation_key: String,
    pub source: String,
    #[serde(default)]
    pub registered_at: i64,
    pub pane: PaneIdentity,
    pub transcript_path: PathBuf,
}

impl ChatBinding {
    pub fn storage_key(&self) -> &str {
        &self.conversation_key
    }
    pub fn ai_tool(&self) -> crate::chat_log::AiTool {
        match self.pane.tool.as_str() {
            "claude" => crate::chat_log::AiTool::Claude,
            "opencode" => crate::chat_log::AiTool::OpencodeBound {
                session_id: self.conversation_id.clone(),
            },
            "kiro" => crate::chat_log::AiTool::Kiro {
                cwd: self.pane.cwd.clone().into(),
                pid: self.pane.agent_pid,
                session_id: self.conversation_id.clone(),
            },
            _ => crate::chat_log::AiTool::Codex,
        }
    }
}

#[derive(Clone, Debug)]
pub struct BindingStore {
    path: PathBuf,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BindingState {
    pub status: String,
    pub pane_token: String,
    pub tool: String,
    pub binding: Option<ChatBinding>,
    pub candidates: Vec<Conversation>,
    pub detail: String,
}

// Activity detection shares the same exact transcript once chat/registration has resolved it.
static SOURCES: OnceLock<Mutex<HashMap<u32, ChatBinding>>> = OnceLock::new();
fn remember(binding: &ChatBinding) {
    if native_tool(binding.pane.agent_pid).as_deref() != Some(binding.pane.tool.as_str()) {
        return;
    }
    let mut sources = SOURCES
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    sources.retain(|_, b| process_start(b.pane.agent_pid).ok() == Some(b.pane.agent_start));
    sources.insert(binding.pane.root_pid, binding.clone());
}
pub(crate) fn registered_source(root_pid: u32) -> Option<(u32, PathBuf, String, String)> {
    let sources = SOURCES.get_or_init(Mutex::default).lock().ok()?;
    let binding = sources.get(&root_pid)?;
    if process_start(binding.pane.agent_pid).ok()? != binding.pane.agent_start {
        return None;
    }
    Some((
        binding.pane.agent_pid,
        binding.transcript_path.clone(),
        binding.pane.tool.clone(),
        binding.conversation_key.clone(),
    ))
}

impl BindingStore {
    pub fn new(path: PathBuf) -> Result<Self> {
        let store = Self { path };
        let conn = store.connection()?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS agent_chat_bindings (pane_key TEXT PRIMARY KEY, binding_id TEXT NOT NULL UNIQUE, binding_json TEXT NOT NULL);")?;
        Ok(store)
    }
    fn connection(&self) -> Result<Connection> {
        let conn = Connection::open(&self.path)?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        Ok(conn)
    }
    fn current(&self, pane: &PaneIdentity) -> Result<Option<ChatBinding>> {
        let value: Option<String> = self
            .connection()?
            .query_row(
                "SELECT binding_json FROM agent_chat_bindings WHERE pane_key=?",
                [pane.pane_key()],
                |row| row.get(0),
            )
            .optional()?;
        value
            .map(|json| serde_json::from_str(&json).map_err(Into::into))
            .transpose()
    }
    pub fn by_id(&self, id: &str) -> Result<ChatBinding> {
        let json: String = self
            .connection()?
            .query_row(
                "SELECT binding_json FROM agent_chat_bindings WHERE binding_id=?",
                [id],
                |row| row.get(0),
            )
            .context("This conversation link has changed. Reconnect the chat")?;
        Ok(serde_json::from_str(&json)?)
    }
    pub fn bind(
        &self,
        pane: PaneIdentity,
        conversation: Conversation,
        source: &str,
    ) -> Result<ChatBinding> {
        self.bind_reported(
            pane,
            conversation,
            source,
            chrono::Utc::now().timestamp_millis(),
        )
    }
    pub fn bind_reported(
        &self,
        pane: PaneIdentity,
        conversation: Conversation,
        source: &str,
        observed_at: i64,
    ) -> Result<ChatBinding> {
        if pane.tool != conversation.tool {
            bail!("The selected conversation belongs to a different agent");
        }
        let old = self.current(&pane)?;
        if let Some(old) = old {
            if old.pane.token() == pane.token()
                && observed_at < old.registered_at
                && old.conversation_id != conversation.id
            {
                return Ok(old);
            }
            if old.pane.token() == pane.token() && old.conversation_id == conversation.id {
                let source = if observed_at < old.registered_at {
                    old.source.clone()
                } else {
                    source.to_owned()
                };
                let updated = ChatBinding {
                    source,
                    registered_at: observed_at.max(old.registered_at),
                    transcript_path: conversation.path,
                    pane,
                    ..old.clone()
                };
                if updated == old {
                    remember(&old);
                    return Ok(old);
                }
                return self.save(&updated);
            }
        }
        let binding = ChatBinding {
            binding_id: uuid::Uuid::new_v4().to_string(),
            conversation_key: format!("conversation:{}:{}", conversation.tool, conversation.id),
            conversation_id: conversation.id,
            source: source.to_owned(),
            pane,
            transcript_path: conversation.path,
            registered_at: observed_at,
        };
        self.save(&binding)
    }
    fn save(&self, binding: &ChatBinding) -> Result<ChatBinding> {
        let mut binding = binding.clone();
        let mut connection = self.connection()?;
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let current: Option<String> = transaction
            .query_row(
                "SELECT binding_json FROM agent_chat_bindings WHERE pane_key=?",
                [binding.pane.pane_key()],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(value) = current {
            let current: ChatBinding = serde_json::from_str(&value)?;
            if current.pane.token() == binding.pane.token() {
                if current.registered_at > binding.registered_at
                    && current.conversation_id != binding.conversation_id
                {
                    return Ok(current);
                }
                if current.conversation_id == binding.conversation_id {
                    binding.binding_id = current.binding_id;
                    if current.registered_at > binding.registered_at {
                        binding.source = current.source;
                        binding.registered_at = current.registered_at;
                    }
                }
            }
        }
        {
            let mut statement = transaction
                .prepare("SELECT binding_json FROM agent_chat_bindings WHERE pane_key<>?")?;
            let records = statement
                .query_map([binding.pane.pane_key()], |row| row.get::<_, String>(0))?
                .collect::<Result<Vec<_>, _>>()?;
            for record in records {
                let existing: ChatBinding = serde_json::from_str(&record)?;
                if existing.conversation_key == binding.conversation_key
                    && process_start(existing.pane.agent_pid).ok()
                        == Some(existing.pane.agent_start)
                {
                    bail!("This conversation is already linked to another live terminal. Start or fork a separate conversation");
                }
            }
        }
        transaction.execute("INSERT INTO agent_chat_bindings(pane_key,binding_id,binding_json) VALUES(?,?,?) ON CONFLICT(pane_key) DO UPDATE SET binding_id=excluded.binding_id,binding_json=excluded.binding_json",
            params![binding.pane.pane_key(), binding.binding_id, serde_json::to_string(&binding)?])?;
        transaction.commit()?;
        remember(&binding);
        Ok(binding)
    }
    pub fn resolve_pane(&self, pane: PaneIdentity) -> Result<BindingState> {
        if pane.tool == "kiro" {
            let id = format!(
                "terminal-{}-{}-{}",
                pane.server_start,
                pane.pane_id.trim_start_matches('%'),
                pane.agent_start
            );
            let conversation = Conversation {
                id,
                tool: pane.tool.clone(),
                title: "Terminal conversation".into(),
                updated_at: 0,
                path: "/dev/null".into(),
            };
            let binding = self.bind(pane.clone(), conversation, "terminal")?;
            return Ok(bound_state(pane, binding));
        }
        if let Some(registration) = read_sidecar(&pane) {
            if let Ok(conversation) = hook_conversation(&registration) {
                let mut reported = pane.clone();
                reported.cwd = registration.cwd;
                let binding =
                    self.bind_reported(reported, conversation, "hook", registration.observed_at)?;
                return Ok(bound_state(pane, binding));
            }
        }
        if let Some(binding) = self.current(&pane)? {
            if binding.pane.token() == pane.token() {
                if binding.transcript_path.as_os_str().is_empty()
                    || !binding.transcript_path.exists()
                {
                    if let Ok(conversation) = conversation_by_id(
                        &pane.tool,
                        &binding.pane.cwd,
                        &binding.conversation_id,
                        None,
                    ) {
                        let refreshed = self.bind(pane.clone(), conversation, &binding.source)?;
                        return Ok(bound_state(pane, refreshed));
                    }
                }
                // Hooks explicitly report conversation switches, including resume in the same process.
                // Passive/manual bindings can also be corrected by a unique open transcript.
                if binding.source != "hook" {
                    if let Some(conversation) = exact_open_transcript(&pane)? {
                        if conversation.id != binding.conversation_id {
                            let binding = self.bind(pane.clone(), conversation, "process")?;
                            return Ok(bound_state(pane, binding));
                        }
                    }
                }
                remember(&binding);
                return Ok(bound_state(pane, binding));
            }
        }
        if let Some(conversation) = exact_open_transcript(&pane)? {
            let binding = self.bind(pane.clone(), conversation, "process")?;
            return Ok(bound_state(pane, binding));
        }
        // A specific resume argument is identity evidence; directory or timestamps are not.
        if let Some(id) = explicit_session_id(pane.agent_pid, &pane.tool) {
            if let Ok(conversation) = conversation_by_id(&pane.tool, &pane.cwd, &id, None) {
                let binding = self.bind(pane.clone(), conversation, "resume")?;
                return Ok(bound_state(pane, binding));
            }
        }
        let candidates = conversation_candidates(&pane.tool, &pane.cwd).unwrap_or_default();
        Ok(BindingState {
            status: "required".into(),
            pane_token: pane.token(),
            tool: pane.tool,
            binding: None,
            candidates,
            detail: "Waiting for the agent to report its conversation".into(),
        })
    }
    pub async fn resolve(&self, session: &str, window: u32) -> Result<BindingState> {
        let pane = inspect_pane(&format!("{session}:{window}")).await?;
        self.resolve_inspected_pane(pane).await
    }
    pub async fn resolve_inspected_pane(&self, pane: PaneIdentity) -> Result<BindingState> {
        let store = self.clone();
        tokio::task::spawn_blocking(move || store.resolve_pane(pane)).await?
    }
    pub async fn validate(&self, binding: &ChatBinding) -> Result<()> {
        let live = inspect_pane(&binding.pane.pane_id).await?;
        if live.token() != binding.pane.token() {
            bail!("The agent in this terminal has changed. Reconnect the chat");
        }
        // Resolve current native identity here rather than trusting the last
        // watcher tick. A resume can change conversations in the same process
        // immediately before a send, pagination request, or history clear.
        let current = self
            .resolve_inspected_pane(live)
            .await?
            .binding
            .context("This conversation is no longer linked")?;
        if current.binding_id != binding.binding_id {
            bail!("The terminal switched conversations. Reconnect the chat");
        }
        Ok(())
    }
}

fn bound_state(pane: PaneIdentity, binding: ChatBinding) -> BindingState {
    BindingState {
        status: "bound".into(),
        pane_token: pane.token(),
        tool: pane.tool.clone(),
        binding: Some(binding),
        candidates: vec![],
        detail: "Conversation linked".into(),
    }
}

pub(crate) fn process_start(pid: u32) -> Result<u64> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat"))?;
    let fields: Vec<_> = stat[stat.rfind(')').context("Invalid process metadata")? + 2..]
        .split_whitespace()
        .collect();
    fields
        .get(19)
        .context("Missing process generation")?
        .parse()
        .map_err(Into::into)
}

fn native_tool(pid: u32) -> Option<String> {
    match fs::read_to_string(format!("/proc/{pid}/comm")).ok()?.trim() {
        "codex" => Some("codex".into()),
        "claude" => Some("claude".into()),
        "opencode" => Some("opencode".into()),
        "kiro-cli" | "kiro-cli-chat" => Some("kiro".into()),
        _ => None,
    }
}

pub async fn locate_pane(target: &str) -> Result<String> {
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        tokio::process::Command::new("tmux")
            .args(["display-message", "-p", "-t", target, "#{pane_id}"])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .context("Terminal lookup timed out")??;
    if !output.status.success() {
        bail!("This terminal no longer exists");
    }
    let pane = String::from_utf8(output.stdout)?.trim().to_owned();
    if !pane.starts_with('%') || pane.len() < 2 || !pane[1..].chars().all(|c| c.is_ascii_digit()) {
        bail!("Unable to identify this terminal");
    }
    Ok(pane)
}

pub async fn inspect_pane(target: &str) -> Result<PaneIdentity> {
    let output = tokio::process::Command::new("tmux")
        .args([
            "display-message",
            "-p",
            "-t",
            target,
            "#{pane_id}\t#{pid}\t#{pane_pid}\t#{pane_current_path}",
        ])
        .kill_on_drop(true)
        .output()
        .await?;
    if !output.status.success() {
        bail!("This terminal no longer exists");
    }
    let line = String::from_utf8(output.stdout)?;
    let parts: Vec<_> = line.trim_end().split('\t').collect();
    if parts.len() != 4 {
        bail!("Unable to identify this terminal");
    }
    let pane_id = parts[0].to_owned();
    let server_pid = parts[1].parse()?;
    let root_pid = parts[2].parse()?;
    let initial_cwd = parts[3].to_owned();
    tokio::task::spawn_blocking(move || {
        let pids = crate::chat_log::watcher::get_descendant_pids(root_pid)?;
        let mut agents: Vec<_> = pids
            .iter()
            .filter_map(|pid| {
                let tool = native_tool(*pid)?;
                let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
                let fields: Vec<_> = stat[stat.rfind(')')? + 2..].split_whitespace().collect();
                if fields.get(2) != fields.get(5) || fields.get(4) == Some(&"0") {
                    return None;
                }
                let mut parent = *pid;
                let mut depth = 0;
                while parent != root_pid && depth < 32 {
                    let stat = fs::read_to_string(format!("/proc/{parent}/stat")).ok()?;
                    parent = stat[stat.rfind(')')? + 2..]
                        .split_whitespace()
                        .nth(1)?
                        .parse()
                        .ok()?;
                    depth += 1;
                }
                if parent != root_pid {
                    return None;
                }
                Some((depth, *pid, tool))
            })
            .collect();
        agents.sort_by_key(|entry| entry.0);
        let first = agents
            .first()
            .context("No supported foreground agent is running in this terminal")?;
        if agents.iter().filter(|entry| entry.0 == first.0).count() != 1 {
            bail!("More than one foreground agent was found; terminal identity is ambiguous");
        }
        let (agent_pid, tool) = (first.1, first.2.clone());
        let mut cwd = initial_cwd;
        if let Ok(args) = fs::read(format!("/proc/{agent_pid}/cmdline")) {
            let words: Vec<_> = args
                .split(|b| *b == 0)
                .filter_map(|s| std::str::from_utf8(s).ok())
                .collect();
            for pair in words.windows(2) {
                if matches!(pair[0], "-C" | "--cd" | "--cwd") {
                    cwd = pair[1].to_owned();
                }
            }
        }
        Ok(PaneIdentity {
            pane_id,
            server_pid,
            server_start: process_start(server_pid)?,
            root_pid,
            root_start: process_start(root_pid)?,
            agent_pid,
            agent_start: process_start(agent_pid)?,
            tool,
            cwd,
        })
    })
    .await?
}

fn explicit_session_id(pid: u32, tool: &str) -> Option<String> {
    let bytes = fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    let words: Vec<_> = bytes
        .split(|b| *b == 0)
        .filter_map(|v| std::str::from_utf8(v).ok())
        .collect();
    for pair in words.windows(2) {
        if (tool == "codex" && pair[0] == "resume")
            || (tool == "claude" && matches!(pair[0], "--resume" | "-r" | "--session-id"))
            || (tool == "opencode" && matches!(pair[0], "--session" | "-s"))
        {
            if valid_id(pair[1]) {
                return Some(pair[1].to_owned());
            }
        }
    }
    None
}
fn valid_id(id: &str) -> bool {
    id.len() >= 8
        && id.len() <= 200
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
}
fn home() -> Result<PathBuf> {
    dirs::home_dir().context("Agent home directory is unavailable")
}

pub fn conversation_candidates(tool: &str, cwd: &str) -> Result<Vec<Conversation>> {
    let home = home()?;
    let mut result = match tool {
        "codex" => {
            let path = home.join(".codex/state_5.sqlite");
            let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
            let mut stmt = conn.prepare("SELECT id,COALESCE(title,''),updated_at,rollout_path FROM threads WHERE cwd=? ORDER BY updated_at DESC LIMIT 100")?;
            let values = stmt
                .query_map([cwd], |row| {
                    Ok(Conversation {
                        id: row.get(0)?,
                        title: row.get(1)?,
                        updated_at: row.get::<_, i64>(2)? * 1000,
                        path: row.get::<_, String>(3)?.into(),
                        tool: "codex".into(),
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?;
            values
        }
        "claude" => {
            let directory = home
                .join(".claude/projects")
                .join(cwd.replace(['/', '_'], "-"));
            let mut list = Vec::new();
            for entry in fs::read_dir(directory)?.flatten() {
                let path = entry.path();
                if path.extension().and_then(|v| v.to_str()) != Some("jsonl") {
                    continue;
                }
                let id = path
                    .file_stem()
                    .and_then(|v| v.to_str())
                    .unwrap_or("")
                    .to_owned();
                if !valid_id(&id) {
                    continue;
                }
                let updated = entry
                    .metadata()?
                    .modified()?
                    .duration_since(std::time::UNIX_EPOCH)?
                    .as_millis() as i64;
                list.push(Conversation {
                    title: format!("Claude conversation {}", &id[..8]),
                    id,
                    updated_at: updated,
                    path,
                    tool: "claude".into(),
                });
            }
            list
        }
        "opencode" => {
            let conn = Connection::open_with_flags(
                home.join(".local/share/opencode/opencode.db"),
                OpenFlags::SQLITE_OPEN_READ_ONLY,
            )?;
            let mut stmt = conn.prepare("SELECT id,COALESCE(title,''),time_updated FROM session WHERE directory=? AND parent_id IS NULL ORDER BY time_updated DESC LIMIT 100")?;
            let values = stmt
                .query_map([cwd], |row| {
                    Ok(Conversation {
                        id: row.get(0)?,
                        title: row.get(1)?,
                        updated_at: row.get(2)?,
                        tool: "opencode".into(),
                        path: home.join(".local/share/opencode/opencode.db"),
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?;
            values
        }
        _ => bail!("This agent does not provide supported conversation identity"),
    };
    result.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
    result.truncate(100);
    Ok(result)
}

pub fn conversation_by_id(
    tool: &str,
    cwd: &str,
    id: &str,
    supplied_path: Option<&Path>,
) -> Result<Conversation> {
    if !valid_id(id) {
        bail!("Invalid conversation identifier");
    }
    let home = home()?;
    if tool == "codex" && home.join(".codex/state_5.sqlite").exists() {
        let conn = Connection::open_with_flags(
            home.join(".codex/state_5.sqlite"),
            OpenFlags::SQLITE_OPEN_READ_ONLY,
        )?;
        let found = conn
            .query_row(
                "SELECT id,COALESCE(title,''),updated_at,rollout_path FROM threads WHERE id=?",
                [id],
                |row| {
                    Ok(Conversation {
                        id: row.get(0)?,
                        title: row.get(1)?,
                        updated_at: row.get::<_, i64>(2)? * 1000,
                        path: row.get::<_, String>(3)?.into(),
                        tool: tool.to_owned(),
                    })
                },
            )
            .optional()?;
        if let Some(found) = found {
            return Ok(found);
        }
    }
    if tool == "opencode" {
        let path = home.join(".local/share/opencode/opencode.db");
        let connection = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        return connection.query_row("SELECT id,COALESCE(title,''),time_updated FROM session WHERE id=? AND parent_id IS NULL", [id], |row| {
            Ok(Conversation { id: row.get(0)?, title: row.get(1)?, updated_at: row.get(2)?, tool: tool.to_owned(), path: path.clone() })
        }).context("This OpenCode conversation was not found");
    }
    if tool == "claude" && supplied_path.is_none() {
        let path = home
            .join(".claude/projects")
            .join(cwd.replace(['/', '_'], "-"))
            .join(format!("{id}.jsonl"));
        if path.exists() {
            return conversation_by_id(tool, cwd, id, Some(&path));
        }
    }
    if let Some(path) = supplied_path {
        let allowed = match tool {
            "codex" => home.join(".codex/sessions"),
            "claude" => home.join(".claude/projects"),
            _ => bail!("This agent requires a database conversation"),
        };
        if !path.starts_with(&allowed)
            || path
                .components()
                .any(|c| c == std::path::Component::ParentDir)
            || path.extension().and_then(|v| v.to_str()) != Some("jsonl")
        {
            bail!("Invalid conversation transcript path");
        }
        let name = path.file_name().and_then(|v| v.to_str()).unwrap_or("");
        if !name.contains(id) {
            bail!("The transcript does not match this conversation");
        }
        if path.exists() {
            let canonical = fs::canonicalize(path)?;
            if !canonical.starts_with(fs::canonicalize(&allowed)?)
                || !canonical
                    .file_name()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .contains(id)
            {
                bail!("The transcript resolves outside this conversation’s storage");
            }
        }
        return Ok(Conversation {
            id: id.to_owned(),
            tool: tool.to_owned(),
            title: format!("{} conversation {}", tool, &id[..8]),
            updated_at: chrono::Utc::now().timestamp_millis(),
            path: path.to_owned(),
        });
    }
    conversation_candidates(tool, cwd)?
        .into_iter()
        .find(|c| c.id == id)
        .context("This conversation was not found. Refresh the list")
}

pub(crate) fn exact_open_transcript(pane: &PaneIdentity) -> Result<Option<Conversation>> {
    if pane.tool == "opencode" {
        return Ok(None);
    }
    let fds = match fs::read_dir(format!("/proc/{}/fd", pane.agent_pid)) {
        Ok(fds) => fds,
        Err(_) => return Ok(None),
    };
    let mut candidates = Vec::new();
    for fd in fds.flatten() {
        let Ok(path) = fs::read_link(fd.path()) else {
            continue;
        };
        let text = path.to_string_lossy();
        if text.contains("/subagents/") {
            continue;
        }
        if !text.ends_with(".jsonl")
            || !(text.contains("/.codex/sessions/") || text.contains("/.claude/projects/"))
        {
            continue;
        }
        let id = if pane.tool == "codex" {
            let first = BufReader::new(fs::File::open(&path)?)
                .lines()
                .next()
                .transpose()?
                .unwrap_or_default();
            let value: serde_json::Value = serde_json::from_str(&first)?;
            if value.pointer("/payload/source/subagent").is_some()
                || value.pointer("/payload/source").and_then(|v| v.as_str()) == Some("subagent")
            {
                continue;
            }
            value
                .pointer("/payload/id")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_owned()
        } else {
            path.file_stem()
                .and_then(|v| v.to_str())
                .unwrap_or("")
                .to_owned()
        };
        if valid_id(&id) {
            candidates.push(Conversation {
                id,
                tool: pane.tool.clone(),
                title: "Active conversation".into(),
                updated_at: 0,
                path,
            });
        }
    }
    candidates.sort_by(|a, b| a.id.cmp(&b.id));
    candidates.dedup_by(|a, b| a.id == b.id);
    Ok(if candidates.len() == 1 {
        candidates.pop()
    } else {
        None
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HookRegistration {
    pub pane_id: String,
    pub agent_pid: u32,
    pub agent_start: u64,
    pub tool: String,
    pub session_id: String,
    pub cwd: String,
    pub transcript_path: Option<PathBuf>,
    #[serde(default)]
    pub server_pid: Option<u32>,
    #[serde(default)]
    pub server_start: Option<u64>,
    #[serde(default)]
    pub observed_at: i64,
}

fn hook_conversation(payload: &HookRegistration) -> Result<Conversation> {
    match conversation_by_id(
        &payload.tool,
        &payload.cwd,
        &payload.session_id,
        payload.transcript_path.as_deref(),
    ) {
        Ok(conversation) => Ok(conversation),
        Err(_)
            if payload.tool == "codex"
                && payload.transcript_path.is_none()
                && valid_id(&payload.session_id) =>
        {
            Ok(Conversation {
                id: payload.session_id.clone(),
                tool: payload.tool.clone(),
                title: "New conversation".into(),
                updated_at: 0,
                path: PathBuf::new(),
            })
        }
        Err(error) => Err(error),
    }
}

fn read_sidecar(pane: &PaneIdentity) -> Option<HookRegistration> {
    use std::os::unix::fs::MetadataExt;
    let directory = home().ok()?.join(".local/state/agentshell/bindings");
    let path = directory.join(format!(
        "{}-{}-{}.json",
        pane.server_pid,
        pane.server_start,
        pane.pane_id.trim_start_matches('%')
    ));
    let metadata = fs::symlink_metadata(&path).ok()?;
    if !metadata.is_file()
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o077 != 0
        || metadata.len() > 64 * 1024
    {
        return None;
    }
    let value: HookRegistration = serde_json::from_slice(&fs::read(path).ok()?).ok()?;
    if value.agent_pid != pane.agent_pid
        || value.agent_start != pane.agent_start
        || value.tool != pane.tool
        || value.server_pid != Some(pane.server_pid)
        || value.server_start != Some(pane.server_start)
    {
        return None;
    }
    Some(value)
}

pub async fn register_hook(
    State(state): State<Arc<crate::AppState>>,
    Json(payload): Json<HookRegistration>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    async {
        let mut pane = inspect_pane(&payload.pane_id).await?;
        if pane.agent_pid != payload.agent_pid
            || pane.agent_start != payload.agent_start
            || pane.tool != payload.tool
        {
            bail!("The registration does not belong to the current foreground agent");
        }
        if payload.server_pid.is_some_and(|pid| pid != pane.server_pid)
            || payload
                .server_start
                .is_some_and(|start| start != pane.server_start)
        {
            bail!("The registration belongs to a different TMUX server");
        }
        if !Path::new(&payload.cwd).is_absolute() {
            bail!("The agent working directory must be absolute");
        }
        pane.cwd = payload.cwd.clone();
        let store = state.chat_event_store.bindings.clone();
        tokio::task::spawn_blocking(move || {
            let conversation = hook_conversation(&payload)?;
            store.bind_reported(
                pane,
                conversation,
                "hook",
                if payload.observed_at > 0 {
                    payload.observed_at
                } else {
                    chrono::Utc::now().timestamp_millis()
                },
            )
        })
        .await??;
        Ok::<_, anyhow::Error>(Json(serde_json::json!({"ok":true})))
    }
    .await
    .map_err(|error| (StatusCode::BAD_REQUEST, error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pane(id: &str) -> PaneIdentity {
        PaneIdentity {
            pane_id: id.into(),
            server_pid: 1,
            server_start: 1,
            root_pid: 900_001,
            root_start: 1,
            agent_pid: 900_001,
            agent_start: 1,
            tool: "codex".into(),
            cwd: "/same/project".into(),
        }
    }
    fn conversation(id: &str) -> Conversation {
        Conversation {
            id: id.into(),
            tool: "codex".into(),
            title: id.into(),
            updated_at: 0,
            path: format!("/logs/{id}.jsonl").into(),
        }
    }
    fn store(directory: &tempfile::TempDir) -> BindingStore {
        BindingStore::new(directory.path().join("bindings.db")).unwrap()
    }

    #[test]
    fn bundled_sqlite_includes_concurrent_close_deadlock_fix() {
        // 3.51.0/3.51.1 could deadlock unixClose against unixLock and freeze
        // every SQLite user in the daemon. 3.51.3 also fixes the WAL-reset bug.
        assert!(rusqlite::version_number() >= 3_051_003);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn binding_resolution_does_not_block_connection_handling_on_database_contention() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let transcript = directory.path().join("conversation-A.jsonl");
        fs::write(&transcript, "").unwrap();
        let mut selected = conversation("conversation-A");
        selected.path = transcript;
        store.bind(pane("%1"), selected, "manual").unwrap();
        let locker = Connection::open(&store.path).unwrap();
        locker.execute_batch("BEGIN EXCLUSIVE").unwrap();
        let lookup = tokio::spawn(async move { store.resolve_inspected_pane(pane("%1")).await });
        // A timer must run on the sole runtime worker while SQLite is waiting.
        // The locker eventually releases independently, so a regression fails
        // without leaving the test process permanently blocked.
        let release = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(500));
            locker.execute_batch("ROLLBACK").unwrap();
        });
        let start = std::time::Instant::now();
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        assert!(start.elapsed() < std::time::Duration::from_millis(300));
        assert_eq!(lookup.await.unwrap().unwrap().status, "bound");
        release.join().unwrap();
    }

    #[test]
    fn independent_conversations_in_one_directory_keep_distinct_links_and_storage() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let first = store
            .bind(pane("%1"), conversation("conversation-A"), "manual")
            .unwrap();
        let second = store
            .bind(pane("%2"), conversation("conversation-B"), "manual")
            .unwrap();
        assert_ne!(first.binding_id, second.binding_id);
        assert_ne!(first.storage_key(), second.storage_key());
        assert_eq!(
            store.current(&pane("%1")).unwrap().unwrap().conversation_id,
            "conversation-A"
        );
        assert_eq!(
            store.current(&pane("%2")).unwrap().unwrap().conversation_id,
            "conversation-B"
        );
    }

    #[test]
    fn restart_restores_the_same_link_for_the_same_process_generation() {
        let directory = tempfile::tempdir().unwrap();
        let original = store(&directory)
            .bind(pane("%1"), conversation("conversation-A"), "hook")
            .unwrap();
        let restored = store(&directory).current(&pane("%1")).unwrap().unwrap();
        assert_eq!(original, restored);
    }

    #[test]
    fn resume_changes_identity_without_reusing_previous_lease_or_history() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let first = store
            .bind(pane("%1"), conversation("conversation-A"), "hook")
            .unwrap();
        let next = store
            .bind(
                pane("%1"),
                conversation("older-resumed-conversation"),
                "hook",
            )
            .unwrap();
        assert_ne!(first.binding_id, next.binding_id);
        assert_ne!(first.storage_key(), next.storage_key());
        assert!(store.by_id(&first.binding_id).is_err());
    }

    #[test]
    fn reused_pid_and_pane_ids_do_not_restore_a_stale_lease() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let first = store
            .bind(pane("%1"), conversation("conversation-A"), "manual")
            .unwrap();
        let mut restarted = pane("%1");
        restarted.agent_start += 1;
        let next = store
            .bind(restarted, conversation("conversation-A"), "hook")
            .unwrap();
        assert_ne!(first.binding_id, next.binding_id);
        assert_eq!(first.storage_key(), next.storage_key());
        assert!(store.by_id(&first.binding_id).is_err());
    }

    #[test]
    fn delayed_startup_hook_cannot_overwrite_a_newer_resume_or_manual_choice() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let original = store
            .bind_reported(pane("%1"), conversation("conversation-A"), "hook", 100)
            .unwrap();
        let resumed = store
            .bind_reported(pane("%1"), conversation("conversation-B"), "manual", 200)
            .unwrap();
        let delayed = store
            .bind_reported(pane("%1"), conversation("conversation-A"), "hook", 150)
            .unwrap();
        assert_eq!(delayed.binding_id, resumed.binding_id);
        assert_eq!(delayed.conversation_id, "conversation-B");
        assert!(store.by_id(&original.binding_id).is_err());
    }

    #[test]
    fn concurrent_watchers_share_one_lease_and_older_registration_cannot_win() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let mut threads = Vec::new();
        for _ in 0..8 {
            let store = store.clone();
            threads.push(std::thread::spawn(move || {
                store
                    .bind_reported(pane("%1"), conversation("conversation-A"), "hook", 100)
                    .unwrap()
                    .binding_id
            }));
        }
        let ids = threads
            .into_iter()
            .map(|t| t.join().unwrap())
            .collect::<Vec<_>>();
        assert!(ids.iter().all(|id| id == &ids[0]));
        let mut threads = Vec::new();
        for index in 0..8 {
            let store = store.clone();
            threads.push(std::thread::spawn(move || {
                store
                    .bind_reported(
                        pane("%1"),
                        conversation(if index % 2 == 0 {
                            "conversation-B"
                        } else {
                            "conversation-A"
                        }),
                        "hook",
                        if index % 2 == 0 { 300 } else { 200 },
                    )
                    .unwrap()
            }));
        }
        for thread in threads {
            thread.join().unwrap();
        }
        assert_eq!(
            store.current(&pane("%1")).unwrap().unwrap().conversation_id,
            "conversation-B"
        );
    }

    #[test]
    fn one_live_conversation_cannot_be_accidentally_linked_to_two_terminal_writers() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let mut first_pane = pane("%1");
        first_pane.agent_pid = std::process::id();
        first_pane.agent_start = process_start(std::process::id()).unwrap();
        store
            .bind(first_pane, conversation("conversation-A"), "manual")
            .unwrap();
        assert!(store
            .bind(pane("%2"), conversation("conversation-A"), "manual")
            .is_err());
    }

    #[test]
    fn cached_directory_match_without_identity_evidence_requires_selection() {
        let directory = tempfile::tempdir().unwrap();
        let store = store(&directory);
        let unlinked = store.resolve_pane(pane("%1")).unwrap();
        assert_eq!(unlinked.status, "required");
        assert!(unlinked.binding.is_none());
    }

    #[test]
    fn ids_cannot_be_paths_or_command_fragments() {
        for id in ["../../private", "abc;command", "a/b/session", "short"] {
            assert!(!valid_id(id));
        }
        assert!(valid_id("01a11981-6ad0-7300-94d2-c653ae4a0973"));
    }
}
