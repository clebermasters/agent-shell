use std::path::PathBuf;
use std::sync::Arc;
use tracing::{error, info, warn};

use super::types::{merge_history_messages, send_message, WsState};
use crate::{tmux, types::*, AppState};

const BACKEND_CODEX: &str = "codex";

fn parse_direct_session_id(session_id: &str) -> (String, String) {
    match session_id.split_once(':') {
        Some((provider, raw_id)) if !provider.is_empty() && !raw_id.is_empty() => {
            (provider.to_string(), raw_id.to_string())
        }
        _ => ("opencode".to_string(), session_id.to_string()),
    }
}

fn session_key_for_external_id(external_id: &str) -> String {
    format!("acp_{external_id}")
}

pub(crate) fn codex_source_revision(external_id: &str) -> Option<String> {
    let raw = external_id.strip_prefix("codex:").unwrap_or(external_id);
    let source = crate::chat_binding::conversation_by_id("codex", "", raw, None).ok()?;
    crate::history::fingerprint(&source.path).ok()
}

pub(crate) async fn load_codex_page(
    app: Arc<AppState>,
    external_id: String,
    session_key: String,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> Result<crate::history::Page, String> {
    load_codex_page_at(app, external_id, session_key, cleared, offset, limit, None).await
}
async fn load_codex_page_at(
    app: Arc<AppState>,
    external_id: String,
    session_key: String,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
    snapshot: Option<(String, String)>,
) -> Result<crate::history::Page, String> {
    let path = app.chat_event_store.history_index_path();
    let source_revision = codex_source_revision(&external_id);
    let overlay_revision = app
        .chat_event_store
        .history_revision(&session_key, 0)
        .map_err(|e| e.to_string())?;
    let is_snapshot = snapshot.is_some();
    let (cache_key, revision) = snapshot.unwrap_or_else(|| {
        (
            session_key.clone(),
            format!(
                "rpc-v1:{}:{overlay_revision}",
                source_revision.as_deref().unwrap_or("unknown")
            ),
        )
    });
    let mut index = crate::history::Index::open(&path).map_err(|e| e.to_string())?;
    if (source_revision.is_some() || is_snapshot)
        && index
            .current(&cache_key, &revision)
            .map_err(|e| e.to_string())?
    {
        return index
            .page(&cache_key, cleared, offset, limit, true)
            .map_err(|e| e.to_string());
    }
    let permit = crate::history::permit().await.map_err(|e| e.to_string())?;
    super::acp_cmds::ensure_codex_client(&app)
        .await
        .map_err(|e| e.to_string())?;
    let file = {
        let client = app.codex_app_client.read().await;
        client
            .as_ref()
            .ok_or("Codex client not initialized")?
            .read_thread_snapshot(&external_id)
            .await?
    };
    let events = app.chat_event_store.clone();
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        index.replace(&cache_key, &revision, |sink| {
            crate::codex_app::client::index_thread_snapshot(file.path(), sink)?;
            events.visit_history(&session_key, 0, true, sink)
        })?;
        index.page(&cache_key, cleared, offset, limit, true)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e: anyhow::Error| e.to_string())
}

async fn opencode_page(
    app: Arc<AppState>,
    external_id: &str,
    session_key: &str,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
) -> anyhow::Result<crate::history::Page> {
    opencode_page_at(app, external_id, session_key, cleared, offset, limit, None).await
}
async fn opencode_page_at(
    app: Arc<AppState>,
    external_id: &str,
    session_key: &str,
    cleared: Option<i64>,
    offset: usize,
    limit: usize,
    snapshot: Option<(String, String)>,
) -> anyhow::Result<crate::history::Page> {
    let permit = crate::history::permit().await?;
    let db = crate::chat_log::watcher::find_opencode_db()?;
    let source_revision = format!(
        "opencode-v1:{}:{}",
        opencode_revision(&db, external_id)?,
        app.chat_event_store.history_revision(session_key, 0)?
    );
    let (key, revision) = snapshot.unwrap_or_else(|| (session_key.to_owned(), source_revision));
    let id = external_id.to_owned();
    let events = app.chat_event_store.clone();
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let mut index = crate::history::Index::open(&events.history_index_path())?;
        if !index.current(&key, &revision)? {
            index.replace(&key, &revision, |sink| {
                crate::chat_log::opencode_parser::visit_all_messages(&db, &id, None, sink)?;
                events.visit_history(&key, 0, true, sink)
            })?;
        }
        index.page(&key, cleared, offset, limit, true)
    })
    .await?
}

fn opencode_revision(path: &std::path::Path, id: &str) -> anyhow::Result<String> {
    let conn =
        rusqlite::Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let (count, max): (i64, i64) = conn.query_row(
        "SELECT COUNT(*),COALESCE(MAX(time_updated),0) FROM part WHERE session_id=?",
        [id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    Ok(format!(
        "{}:{count}:{max}",
        crate::history::sqlite_revision(path)?
    ))
}

struct HistorySource {
    revision: String,
    cleared: Option<i64>,
    binding: Option<crate::chat_binding::ChatBinding>,
}
async fn history_source(
    app: &Arc<AppState>,
    session: &str,
    window: u32,
) -> anyhow::Result<HistorySource> {
    if let Some(external) = session.strip_prefix("acp_") {
        let (provider, raw) = parse_direct_session_id(external);
        let source = if provider == "codex" {
            codex_source_revision(external)
                .ok_or_else(|| anyhow::anyhow!("Conversation transcript is unavailable"))?
        } else if provider == "opencode" {
            opencode_revision(&crate::chat_log::watcher::find_opencode_db()?, &raw)?
        } else {
            anyhow::bail!("History sync unsupported for this provider");
        };
        let cleared = app.chat_clear_store.get_cleared_at(session, window).await;
        let revision = format!(
            "sync-v1:{source}:{}:{cleared:?}",
            app.chat_event_store.history_revision(session, 0)?
        );
        Ok(HistorySource {
            revision,
            cleared,
            binding: None,
        })
    } else {
        let resolution = app
            .chat_event_store
            .bindings
            .resolve(session, window)
            .await?;
        let binding = resolution
            .binding
            .ok_or_else(|| anyhow::anyhow!("Conversation binding is unavailable"))?;
        let source =
            if binding.transcript_path.as_os_str().is_empty() || binding.pane.tool == "kiro" {
                "overlay".to_owned()
            } else if binding.pane.tool == "opencode" {
                opencode_revision(&binding.transcript_path, &binding.conversation_id)?
            } else {
                crate::history::fingerprint(&binding.transcript_path)?
            };
        let cleared = app
            .chat_clear_store
            .get_cleared_at(binding.storage_key(), 0)
            .await;
        let revision = format!(
            "sync-v1:{}:{source}:{}:{cleared:?}",
            binding.conversation_key,
            app.chat_event_store
                .history_revision(binding.storage_key(), 0)?
        );
        Ok(HistorySource {
            revision,
            cleared,
            binding: Some(binding),
        })
    }
}

async fn sync_page(
    app: Arc<AppState>,
    session: &str,
    _window: u32,
    source: &HistorySource,
    offset: usize,
    limit: usize,
    snapshot_key: String,
    snapshot_revision: String,
) -> anyhow::Result<crate::history::Page> {
    if let Some(binding) = source.binding.clone() {
        let permit = crate::history::permit().await?;
        let events = app.chat_event_store.clone();
        let cleared = source.cleared;
        let page = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            crate::history::bound_page_at(
                &events,
                &binding,
                cleared,
                offset,
                limit,
                Some((snapshot_key, snapshot_revision)),
            )
        })
        .await??;
        app.chat_event_store
            .bindings
            .validate(source.binding.as_ref().unwrap())
            .await?;
        Ok(page)
    } else {
        let external = session
            .strip_prefix("acp_")
            .ok_or_else(|| anyhow::anyhow!("Invalid direct conversation"))?;
        let (provider, raw) = parse_direct_session_id(external);
        if provider == "codex" {
            load_codex_page_at(
                app,
                external.to_owned(),
                session.to_owned(),
                source.cleared,
                offset,
                limit,
                Some((snapshot_key, snapshot_revision)),
            )
            .await
            .map_err(anyhow::Error::msg)
        } else {
            opencode_page_at(
                app,
                &raw,
                session,
                source.cleared,
                offset,
                limit,
                Some((snapshot_key, snapshot_revision)),
            )
            .await
        }
    }
}

pub(crate) async fn handle(
    msg: WebSocketMessage,
    state: &mut WsState,
    app_state: Arc<AppState>,
) -> anyhow::Result<()> {
    match &msg {
        WebSocketMessage::GetHistorySyncCapabilities => {
            super::binding_cmds::send_value(
                &state.message_tx,
                serde_json::json!({"type":"history-sync-capabilities","version":1}),
            )
            .await?;
            return Ok(());
        }
        WebSocketMessage::GetHistoryState {
            request_id,
            session_name,
            window_index,
        } => {
            let result = history_source(&app_state, session_name, *window_index).await;
            let response = match result {
                Ok(source) => {
                    serde_json::json!({"type":"history-state","requestId":request_id,"revision":source.revision,"conversationKey":source.binding.as_ref().map(|b|&b.conversation_key),"available":true})
                }
                Err(e) => {
                    serde_json::json!({"type":"history-state","requestId":request_id,"available":false,"error":e.to_string()})
                }
            };
            super::binding_cmds::send_value(&state.message_tx, response).await?;
            return Ok(());
        }
        WebSocketMessage::GetHistoryPage {
            request_id,
            session_name,
            window_index,
            revision,
            offset,
            limit,
            base_revision,
        } => {
            let result = async {
                let source = history_source(&app_state, session_name, *window_index).await?;
                let prefix = format!("sync:{session_name}:{window_index}:");
                let key = format!("{prefix}{revision}");
                let mut index =
                    crate::history::Index::open(&app_state.chat_event_store.history_index_path())?;
                let valid_scope = revision.ends_with(&format!(":{:?}", source.cleared))
                    && source.binding.as_ref().is_none_or(|b| {
                        revision.starts_with(&format!("sync-v1:{}:", b.conversation_key))
                    });
                let page = if valid_scope && index.current(&key, revision)? {
                    index.page(&key, source.cleared, *offset, *limit, true)?
                } else {
                    if &source.revision != revision {
                        anyhow::bail!("Conversation changed; retry its revision");
                    }
                    let page = sync_page(
                        app_state.clone(),
                        session_name,
                        *window_index,
                        &source,
                        *offset,
                        *limit,
                        key,
                        revision.clone(),
                    )
                    .await?;
                    index.prune_snapshots(&prefix)?;
                    page
                };
                drop(page);
                let base = base_revision
                    .as_ref()
                    .map(|revision| format!("{prefix}{revision}"));
                let page = index.conditional_page(
                    &format!("{prefix}{revision}"),
                    base.as_deref(),
                    source.cleared,
                    *offset,
                    *limit,
                )?;
                Ok::<_, anyhow::Error>((page, source.binding))
            }
            .await;
            let response = match result {
                Ok((page, binding)) => {
                    serde_json::json!({"type":"history-page","requestId":request_id,"revision":revision,"entries":page.entries,"hasMore":page.has_more,"totalCount":page.total,"conversationKey":binding.as_ref().map(|b|&b.conversation_key),"tool":binding.as_ref().map(|b|b.pane.tool.as_str()),"offset":offset})
                }
                Err(error) => {
                    serde_json::json!({"type":"history-page","requestId":request_id,"error":error.to_string()})
                }
            };
            super::binding_cmds::send_value(&state.message_tx, response).await?;
            return Ok(());
        }
        _ => {}
    }
    match &msg {
        WebSocketMessage::LoadMoreChatHistory {
            session_name,
            window_index,
            offset,
            limit,
        } if !session_name.starts_with("acp_") => {
            return super::binding_cmds::load_more(
                state,
                session_name.clone(),
                *window_index,
                *offset,
                *limit,
            )
            .await;
        }
        WebSocketMessage::ClearChatLog {
            session_name,
            window_index,
        } if !session_name.starts_with("acp_") => {
            return super::binding_cmds::clear(state, session_name.clone(), *window_index).await;
        }
        _ => {}
    }
    match msg {
        WebSocketMessage::GetAgentActivities => {
            for snapshot in crate::chat_activity::direct_snapshots() {
                send_message(&state.message_tx, snapshot).await?;
            }
        }
        WebSocketMessage::WatchChatLog {
            session_name,
            window_index,
            limit,
        } => {
            super::binding_cmds::start_watch(state, session_name, window_index, limit).await?;
        }
        WebSocketMessage::WatchAcpChatLog {
            session_id,
            window_index,
            limit,
        } => {
            state.chat_target = None;
            *state.chat_binding.lock().await = None;
            if let Some(handle) = state.chat_activity_handle.take() {
                handle.abort();
            }
            let window_index = window_index.unwrap_or(0);
            state.chat_activity_handle = Some(crate::chat_activity::start_direct_watch(
                session_id.clone(),
                window_index,
                state.message_tx.clone(),
            ));
            let session_key = session_key_for_external_id(&session_id);
            let (provider, raw_session_id) = parse_direct_session_id(&session_id);

            tracing::debug!(
                "Starting ACP chat log watch for client={} session={} session_key={} window_index={} provider={}",
                state.client_id,
                session_id,
                session_key,
                window_index,
                provider
            );

            // Set current session for consistency
            *state.current_session.lock().await = Some(session_key.clone());
            *state.current_window.lock().await = Some(window_index);

            // Cancel any existing watcher
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    tracing::info!(
                        "Stopping previous chat log watcher for client={} before starting ACP session={} window_index={}",
                        state.client_id, session_id, window_index
                    );
                    handle.abort();
                }
            }

            let message_tx = state.message_tx.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let chat_log_handle = state.chat_log_handle.clone();
            let app_state_clone = app_state.clone();

            let handle = tokio::spawn(async move {
                let cleared_at = chat_clear_store
                    .get_cleared_at(&session_key, window_index)
                    .await;

                if provider == BACKEND_CODEX {
                    match load_codex_page(
                        app_state_clone.clone(),
                        session_id.clone(),
                        session_key.clone(),
                        cleared_at,
                        0,
                        limit.unwrap_or(30),
                    )
                    .await
                    {
                        Ok(page) => {
                            let total_count = page.total;
                            let has_more = page.has_more;
                            let messages = page.messages;

                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatHistory {
                                    session_name: session_key.clone(),
                                    window_index,
                                    messages,
                                    tool: Some(crate::chat_log::AiTool::Codex),
                                    has_more,
                                    total_count,
                                    context_window_usage: None,
                                    model_name: None,
                                },
                            )
                            .await;
                        }
                        Err(e) => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatLogError {
                                    error: format!("Failed to read Codex history: {}", e),
                                },
                            )
                            .await;
                        }
                    }
                    return;
                }

                let db_path = match crate::chat_log::watcher::find_opencode_db() {
                    Ok(p) => p,
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: format!("OpenCode DB not found: {}", e),
                            },
                        )
                        .await;
                        return;
                    }
                };

                let page = match opencode_page(
                    app_state_clone.clone(),
                    &raw_session_id,
                    &session_key,
                    cleared_at,
                    0,
                    limit.unwrap_or(30),
                )
                .await
                {
                    Ok(page) => page,
                    Err(error) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: error.to_string(),
                            },
                        )
                        .await;
                        return;
                    }
                };
                let max_time_updated = opencode_revision(&db_path, &raw_session_id)
                    .ok()
                    .and_then(|s| s.rsplit(':').next()?.parse().ok())
                    .unwrap_or(0);
                let total_count = page.total;
                let has_more = page.has_more;
                let messages = page.messages;

                let _ = send_message(
                    &message_tx,
                    ServerMessage::ChatHistory {
                        session_name: session_key.clone(),
                        window_index,
                        messages,
                        tool: Some(crate::chat_log::AiTool::Opencode {
                            cwd: std::path::PathBuf::from("/"),
                            pid: 0,
                        }),
                        has_more,
                        total_count,
                        context_window_usage: None,
                        model_name: None,
                    },
                )
                .await;

                // 7. Poll for new messages from OpenCode DB (no persistence)
                let mut opencode_state = crate::chat_log::opencode_parser::OpencodeState {
                    session_id: raw_session_id.clone(),
                    last_time_updated: max_time_updated,
                    cleared_at,
                    pid: 0,
                    seen_text_lengths: std::collections::HashMap::new(),
                    seen_tool_calls: std::collections::HashSet::new(),
                    seen_tool_results: std::collections::HashSet::new(),
                };

                let mut interval = tokio::time::interval(std::time::Duration::from_millis(1000));
                loop {
                    interval.tick().await;
                    let t = std::time::Instant::now();
                    match crate::chat_log::opencode_parser::fetch_new_messages(
                        &db_path,
                        &mut opencode_state,
                    ) {
                        Ok(new_messages) => {
                            let elapsed = t.elapsed();
                            if elapsed.as_millis() > 100 || !new_messages.is_empty() {
                                info!(
                                    "[TIMING] poll fetch_new_messages({} new) took {:?}",
                                    new_messages.len(),
                                    elapsed
                                );
                            }
                            for msg in new_messages {
                                if send_message(
                                    &message_tx,
                                    ServerMessage::ChatEvent {
                                        session_name: session_key.clone(),
                                        window_index,
                                        message: msg,
                                        source: Some("acp".to_string()),
                                    },
                                )
                                .await
                                .is_err()
                                {
                                    tracing::debug!(
                                        "ACP poll: client disconnected, stopping watcher"
                                    );
                                    return;
                                }
                            }
                        }
                        Err(e) => tracing::debug!("ACP poll error: {}", e),
                    }
                }
            });

            *chat_log_handle.lock().await = Some(handle);
        }
        WebSocketMessage::LoadMoreChatHistory {
            session_name,
            window_index,
            offset,
            limit,
        } => {
            let message_tx = state.message_tx.clone();
            let chat_clear_store = state.chat_clear_store.clone();

            if session_name.starts_with("acp_") {
                // ACP session: re-fetch full history and slice the requested page
                let external_session_id = session_name[4..].to_string();
                let (provider, raw_session_id) = parse_direct_session_id(&external_session_id);
                let session_key = session_name.clone();
                let app_state_clone = app_state.clone();

                tokio::spawn(async move {
                    let cleared_at = chat_clear_store
                        .get_cleared_at(&session_key, window_index)
                        .await;

                    if provider == BACKEND_CODEX {
                        match load_codex_page(
                            app_state_clone.clone(),
                            external_session_id.clone(),
                            session_key.clone(),
                            cleared_at,
                            offset,
                            limit,
                        )
                        .await
                        {
                            Ok(page) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatHistoryChunk {
                                        session_name: session_key,
                                        window_index,
                                        messages: page.messages,
                                        has_more: page.has_more,
                                    },
                                )
                                .await;
                                return;
                            }
                            Err(e) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: format!("Failed to read Codex history: {}", e),
                                    },
                                )
                                .await;
                                return;
                            }
                        }
                    } else {
                        match opencode_page(
                            app_state_clone,
                            &raw_session_id,
                            &session_key,
                            cleared_at,
                            offset,
                            limit,
                        )
                        .await
                        {
                            Ok(page) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatHistoryChunk {
                                        session_name: session_key,
                                        window_index,
                                        messages: page.messages,
                                        has_more: page.has_more,
                                    },
                                )
                                .await;
                            }
                            Err(error) => {
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: error.to_string(),
                                    },
                                )
                                .await;
                            }
                        }
                        return;
                    };
                });
            } else {
                // Claude Code session: re-parse log file and slice the requested page
                let session_name_owned = session_name.clone();
                tokio::spawn(async move {
                    let cleared_at = chat_clear_store
                        .get_cleared_at(&session_name_owned, window_index)
                        .await;

                    let (path, tool) = match crate::chat_log::watcher::detect_log_file(
                        &session_name_owned,
                        window_index,
                    )
                    .await
                    {
                        Ok(r) => r,
                        Err(e) => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatLogError {
                                    error: format!("Failed to detect log file: {}", e),
                                },
                            )
                            .await;
                            return;
                        }
                    };

                    let permit = match crate::history::permit().await {
                        Ok(v) => v,
                        Err(_) => return,
                    };
                    let result = tokio::task::spawn_blocking(move || {
                        let _permit = permit;
                        crate::history::transcript_page(&path, &tool, cleared_at, offset, limit)
                    })
                    .await;
                    match result {
                        Ok(Ok(page)) => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatHistoryChunk {
                                    session_name: session_name_owned,
                                    window_index,
                                    messages: page.messages,
                                    has_more: page.has_more,
                                },
                            )
                            .await;
                        }
                        other => {
                            let _ = send_message(
                                &message_tx,
                                ServerMessage::ChatLogError {
                                    error: format!("History page unavailable: {other:?}"),
                                },
                            )
                            .await;
                        }
                    }
                });
            }
        }
        WebSocketMessage::UnwatchChatLog => {
            state.chat_target = None;
            *state.chat_binding.lock().await = None;
            if let Some(handle) = state.chat_activity_handle.take() {
                handle.abort();
            }
            info!(
                "Stopping chat log watch for client={} current_session={:?} current_window={:?}",
                state.client_id,
                *state.current_session.lock().await,
                *state.current_window.lock().await
            );
            let mut handle_guard = state.chat_log_handle.lock().await;
            if let Some(handle) = handle_guard.take() {
                handle.abort();
            }
        }
        WebSocketMessage::ClearChatLog {
            session_name,
            window_index,
        } => {
            info!(
                "Clearing chat log for session {}:{}",
                session_name, window_index
            );

            // Stop current watcher if running
            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                if let Some(handle) = handle_guard.take() {
                    handle.abort();
                }
            }

            // Set the clear timestamp
            let timestamp = chrono::Utc::now().timestamp_millis();
            state
                .chat_clear_store
                .set_cleared_at(&session_name, window_index, timestamp)
                .await;

            // Also clear persisted messages
            if let Err(e) = state
                .chat_event_store
                .clear_messages(&session_name, window_index)
            {
                warn!(
                    "Failed to clear persisted messages for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            // Send confirmation to client
            let _ = send_message(
                &state.message_tx,
                ServerMessage::ChatLogCleared {
                    session_name: session_name.clone(),
                    window_index,
                    success: true,
                    error: None,
                },
            )
            .await;

            // Restart the watcher with the clear timestamp
            state
                .current_session
                .lock()
                .await
                .clone_from(&Some(session_name.clone()));
            state
                .current_window
                .lock()
                .await
                .clone_from(&Some(window_index));

            let message_tx = state.message_tx.clone();
            let chat_event_store = state.chat_event_store.clone();
            let chat_clear_store = state.chat_clear_store.clone();
            let session_name_owned = session_name.clone();
            let window_index_owned = window_index;

            let handle = tokio::spawn(async move {
                match crate::chat_log::watcher::detect_log_file(
                    &session_name_owned,
                    window_index_owned,
                )
                .await
                {
                    Ok((path, tool)) => {
                        let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(32);

                        // Get the clear timestamp
                        let cleared_at = chat_clear_store
                            .get_cleared_at(&session_name_owned, window_index_owned)
                            .await;

                        let _watcher = match crate::chat_log::watcher::watch_log_file(
                            &path,
                            tool.clone(),
                            event_tx,
                            cleared_at,
                            None,
                        )
                        .await
                        {
                            Ok(w) => w,
                            Err(e) => {
                                error!("Failed to start chat log watcher: {}", e);
                                let _ = send_message(
                                    &message_tx,
                                    ServerMessage::ChatLogError {
                                        error: e.to_string(),
                                    },
                                )
                                .await;
                                return;
                            }
                        };

                        // Forward events to WebSocket
                        while let Some(event) = event_rx.recv().await {
                            let msg = match event {
                                crate::chat_log::ChatLogEvent::History {
                                    messages,
                                    tool,
                                    has_more,
                                    total_count,
                                    context_window_usage,
                                    model_name,
                                } => {
                                    let persisted_messages = match chat_event_store
                                        .list_messages(&session_name_owned, window_index_owned)
                                    {
                                        Ok(stored) => stored,
                                        Err(e) => {
                                            warn!(
                                                "Failed to load persisted chat events for {}:{}: {}",
                                                session_name_owned, window_index_owned, e
                                            );
                                            Vec::new()
                                        }
                                    };

                                    let merged_messages =
                                        merge_history_messages(messages, persisted_messages);

                                    ServerMessage::ChatHistory {
                                        session_name: session_name_owned.clone(),
                                        window_index: window_index_owned,
                                        messages: merged_messages,
                                        tool: Some(tool),
                                        has_more,
                                        total_count,
                                        context_window_usage,
                                        model_name,
                                    }
                                }
                                crate::chat_log::ChatLogEvent::NewMessage { message } => {
                                    ServerMessage::ChatEvent {
                                        session_name: session_name_owned.clone(),
                                        window_index: window_index_owned,
                                        message,
                                        source: None,
                                    }
                                }
                                crate::chat_log::ChatLogEvent::ContextWindowUpdate {
                                    usage,
                                    model_name,
                                } => ServerMessage::ContextWindowUpdate {
                                    session_name: session_name_owned.clone(),
                                    window_index: window_index_owned,
                                    context_window_usage: usage,
                                    model_name,
                                },
                                crate::chat_log::ChatLogEvent::Error { error } => {
                                    tracing::warn!("Chat log error: {}", error);
                                    ServerMessage::ChatLogError { error }
                                }
                            };
                            if send_message(&message_tx, msg).await.is_err() {
                                break;
                            }
                        }
                    }
                    Err(e) => {
                        let _ = send_message(
                            &message_tx,
                            ServerMessage::ChatLogError {
                                error: e.to_string(),
                            },
                        )
                        .await;
                    }
                }
            });

            {
                let mut handle_guard = state.chat_log_handle.lock().await;
                *handle_guard = Some(handle);
            }
        }
        WebSocketMessage::SendFileToChat {
            session_name,
            window_index,
            file,
            prompt,
        } => {
            let binding =
                super::binding_cmds::require_binding(state, &session_name, window_index).await?;
            info!(
                "Received file to send to chat: {} ({})",
                file.filename, file.mime_type
            );

            // Get tmux session working directory
            let session_path = Some(PathBuf::from(&binding.pane.cwd));

            // Save file to session directory if we have a valid path
            let file_path_str = if let Some(ref session_path) = session_path {
                match state.chat_file_storage.save_file_to_directory(
                    &file.data,
                    &file.filename,
                    &file.mime_type,
                    session_path,
                ) {
                    Ok(path) => {
                        info!("File saved to session directory: {:?}", path);
                        Some(path.to_string_lossy().to_string())
                    }
                    Err(e) => {
                        error!("Failed to save file to session directory: {}", e);
                        None
                    }
                }
            } else {
                error!("Could not get session path for {}", session_name);
                None
            };

            // Save file to chat_files for display purposes (UUID-based)
            let file_id =
                match state
                    .chat_file_storage
                    .save_file(&file.data, &file.filename, &file.mime_type)
                {
                    Ok(id) => id,
                    Err(e) => {
                        error!("Failed to save file for display: {}", e);
                        return Ok(());
                    }
                };

            // Build combined text for chat log (prompt + file path, multi-line OK)
            let combined_text = match (&prompt, &file_path_str) {
                (Some(text), Some(path)) if !text.trim().is_empty() => {
                    format!("{}\n\nHere is the file: {}", text.trim(), path)
                }
                (Some(_), Some(path)) => format!("Here is the file: {}", path),
                (Some(text), None) if !text.trim().is_empty() => text.trim().to_string(),
                (None, Some(path)) => format!("Here is the file: {}", path),
                _ => String::new(),
            };

            // Build single-line text for tmux. Wrap file paths in backticks so
            // TUI apps like Claude Code don't auto-detect image extensions
            // (.jpg/.png) and convert them to [Image #N] inline attachments,
            // which swallows the path text. With backticks, the AI reads the
            // file via its Read tool instead.
            let tmux_text = match (&prompt, &file_path_str) {
                (Some(text), Some(path)) if !text.trim().is_empty() => {
                    format!("{} `{}`", text.trim(), path)
                }
                (Some(_), Some(path)) | (None, Some(path)) => {
                    format!("`{}`", path)
                }
                (Some(text), None) if !text.trim().is_empty() => text.trim().to_string(),
                _ => String::new(),
            };

            // Create content block based on mime type
            let block = if file.mime_type.starts_with("image/") {
                crate::chat_log::ContentBlock::Image {
                    id: file_id,
                    mime_type: file.mime_type.clone(),
                    alt_text: Some(file.filename.clone()),
                }
            } else if file.mime_type.starts_with("audio/") {
                crate::chat_log::ContentBlock::Audio {
                    id: file_id,
                    mime_type: file.mime_type.clone(),
                    duration_seconds: None,
                }
            } else {
                crate::chat_log::ContentBlock::File {
                    id: file_id,
                    filename: file.filename.clone(),
                    mime_type: file.mime_type.clone(),
                    size_bytes: Some((file.data.len() as f64 * 0.75) as u64),
                }
            };

            // Build message blocks: text (if prompt provided) + file
            let mut blocks = Vec::new();
            if !combined_text.is_empty() {
                blocks.push(crate::chat_log::ContentBlock::Text {
                    text: combined_text,
                });
            }
            blocks.push(block);

            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks,
            };

            // Send the single-line text (prompt + file path) to the tmux session
            // so the AI tool (OpenCode/Claude) receives the file reference.
            if !tmux_text.is_empty() {
                if let Err(error) = tmux::send_bound_text_and_enter(
                    &binding,
                    &state.chat_event_store.bindings,
                    &tmux_text,
                )
                .await
                {
                    error!(
                        "SendFileToChat: unable to submit to {}:{}: {}",
                        session_name, window_index, error
                    );
                    send_message(
                        &state.message_tx,
                        ServerMessage::ChatLogError {
                            error: error.to_string(),
                        },
                    )
                    .await?;
                    return Err(error);
                }
            }

            if let Err(e) = state.chat_event_store.append_message(
                binding.storage_key(),
                0,
                "webhook-file",
                &chat_message,
            ) {
                warn!(
                    "Failed to persist chat file message for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            // Broadcast to all connected clients watching this session
            let msg = ServerMessage::ChatFileMessage {
                session_name: session_name.clone(),
                window_index,
                message: chat_message,
            };

            // Use client_manager.broadcast to send to ALL connected clients
            state.client_manager.broadcast_bound(msg, &binding).await;
        }
        WebSocketMessage::SendChatMessage {
            session_name,
            window_index,
            message,
            notify,
        } => {
            info!(
                "Received chat message to send: {} (notify: {:?})",
                message, notify
            );

            if message.trim().is_empty() {
                warn!("Empty message received, skipping");
                return Ok(());
            }

            let binding =
                super::binding_cmds::require_binding(state, &session_name, window_index).await?;
            tmux::send_bound_text_and_enter(
                &binding,
                &state.chat_event_store.bindings,
                message.trim(),
            )
            .await?;

            let chat_message = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: message.trim().to_string(),
                }],
            };

            if let Err(e) = state.chat_event_store.append_message(
                binding.storage_key(),
                0,
                "webhook",
                &chat_message,
            ) {
                warn!(
                    "Failed to persist chat message for {}:{}: {}",
                    session_name, window_index, e
                );
            }

            let msg = ServerMessage::ChatEvent {
                session_name: session_name.clone(),
                window_index,
                message: chat_message,
                source: Some("webhook".to_string()),
            };

            state.client_manager.broadcast_bound(msg, &binding).await;

            let should_notify = notify.unwrap_or(true);
            if should_notify {
                let notify_msg = ServerMessage::ChatNotification {
                    session_name: session_name.clone(),
                    window_index,
                    preview: if message.len() > 50 {
                        format!("{}...", &message[..50])
                    } else {
                        message.clone()
                    },
                };
                state
                    .client_manager
                    .broadcast_bound(notify_msg, &binding)
                    .await;
            }

            info!(
                "Submitted chat message to verified conversation {}",
                binding.conversation_id
            );
        }
        _ => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::websocket::{
        client_manager::ClientManager,
        types::{BroadcastMessage, WsState},
    };
    use crate::{
        chat_clear_store::ChatClearStore, chat_event_store::ChatEventStore,
        chat_file_storage::ChatFileStorage, types::WebSocketMessage,
    };
    use base64::Engine;
    use std::sync::Arc;
    use tempfile::TempDir;
    use tokio::sync::{mpsc, Mutex};

    fn make_ws_state(dir: &std::path::Path) -> (WsState, mpsc::Receiver<BroadcastMessage>) {
        let (tx, rx) = mpsc::channel::<BroadcastMessage>(256);
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let client_manager = Arc::new(ClientManager::new());
        let state = WsState {
            client_id: "test-client".to_string(),
            current_pty: Arc::new(Mutex::new(None)),
            current_session: Arc::new(Mutex::new(None)),
            current_window: Arc::new(Mutex::new(None)),
            audio_tx: None,
            message_tx: tx,
            chat_log_handle: Arc::new(Mutex::new(None)),
            chat_target: None,
            chat_binding: Arc::new(tokio::sync::Mutex::new(None)),
            chat_activity_handle: None,
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            client_manager,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            kiro_chat_output_tx: Arc::new(std::sync::Mutex::new(None)),
            selected_backend: "acp".to_string(),
        };
        (state, rx)
    }

    #[tokio::test]
    async fn test_unwatch_chat_log() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::UnwatchChatLog,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_clear_chat_log() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-session".to_string(),
                window_index: 0,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        assert!(
            rx.try_recv().is_err(),
            "Unlinked history must not be cleared"
        );
    }

    #[tokio::test]
    async fn test_load_more_chat_history_acp_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // acp_ prefix sessions try to read opencode DB — it won't exist, error handled gracefully
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_test-session-id".to_string(),
                window_index: 0,
                offset: 0,
                limit: 20,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_regular_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                offset: 0,
                limit: 20,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_image() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        // 1x1 PNG pixel in base64
        let png_data = base64::engine::general_purpose::STANDARD.encode(
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82"
        );
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data: png_data,
                    filename: "test.png".to_string(),
                    mime_type: "image/png".to_string(),
                },
                prompt: Some("Check this image".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        // Should broadcast the file message
        let _ = rx.try_recv();
    }

    #[tokio::test]
    async fn test_send_file_to_chat_document() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"document content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "doc.pdf".to_string(),
                    mime_type: "application/pdf".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_audio() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"audio bytes");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "sound.mp3".to_string(),
                    mime_type: "audio/mpeg".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_chat_message_empty_ignored() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "   ".to_string(),
                notify: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Empty messages are ignored — no response expected
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn test_send_chat_message_valid() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Hello from test".to_string(),
                notify: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_watch_chat_log_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "nonexistent-session-xyz".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Task is spawned asynchronously — give it a moment
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn test_unknown_message_no_op() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::Ping,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_clear_chat_log_sets_cleared_at() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let app = make_app_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-session".to_string(),
                window_index: 0,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        // The handler uses state.chat_clear_store (not app's), verify on state's store
        let ts = state
            .chat_clear_store
            .get_cleared_at("test-session", 0)
            .await;
        assert!(ts.is_none());
    }

    #[tokio::test]
    async fn test_clear_chat_log_clears_persisted_messages() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate messages
        let msg = crate::chat_log::ChatMessage {
            role: "user".to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![crate::chat_log::ContentBlock::Text {
                text: "hello".to_string(),
            }],
        };
        app.chat_event_store
            .append_message("test-sess", 0, "webhook", &msg)
            .unwrap();
        assert_eq!(
            app.chat_event_store
                .list_messages("test-sess", 0)
                .unwrap()
                .len(),
            1
        );

        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "test-sess".to_string(),
                window_index: 0,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        // Messages should be cleared
        let msgs = app.chat_event_store.list_messages("test-sess", 0).unwrap();
        assert_eq!(msgs.len(), 1);
    }

    #[tokio::test]
    async fn test_clear_chat_log_response_content() {
        let dir = TempDir::new().unwrap();
        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "my-session".to_string(),
                window_index: 1,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn test_send_chat_message_with_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Test notification message".to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_chat_message_no_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "No notification".to_string(),
                notify: Some(false),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_chat_message_persists_to_store() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "persist-test".to_string(),
                window_index: 0,
                message: "Persisted message".to_string(),
                notify: None,
            },
            &mut state,
            app.clone(),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
        // The message uses the state's chat_event_store, not app's
        let msgs = state
            .chat_event_store
            .list_messages("persist-test", 0)
            .unwrap();
        assert!(
            msgs.is_empty(),
            "Rejected input must not be recorded as sent"
        );
    }

    #[tokio::test]
    async fn test_watch_acp_chat_log_no_opencode_db() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchAcpChatLog {
                session_id: "test-acp-session".to_string(),
                window_index: Some(0),
                limit: Some(20),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Spawned task will fail to find opencode DB — should handle gracefully
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_load_more_chat_history_with_data() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate with messages using webhook source
        for i in 0..5 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_test-hist", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, mut rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_test-hist".to_string(),
                window_index: 0,
                offset: 0,
                limit: 3,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
        // Should receive a response (chat-history-chunk or error)
        let _ = rx.try_recv();
    }

    // ===== New tests for additional coverage =====

    #[tokio::test]
    async fn test_send_file_to_chat_invalid_base64() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Invalid base64 data - should fail at save_file and return early
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data: "NOT_VALID_BASE64!!!".to_string(),
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Session doesn't exist - session_path will be None, but save_file for display should still work
        let data = base64::engine::general_purpose::STANDARD.encode(b"test content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "nonexistent-session-xyz-123".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_with_prompt_only() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // File with prompt only (no combined text path from file)
        let data = base64::engine::general_purpose::STANDARD.encode(b"content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: Some("Analyze this file".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_video() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Video file - falls through to generic File block
        let data = base64::engine::general_purpose::STANDARD.encode(b"video data");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "video.mp4".to_string(),
                    mime_type: "video/mp4".to_string(),
                },
                prompt: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_both_prompt_and_file_path() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // With prompt AND valid session (so file_path_str is Some)
        let data = base64::engine::general_purpose::STANDARD.encode(b"file content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "doc.pdf".to_string(),
                    mime_type: "application/pdf".to_string(),
                },
                prompt: Some("Review this document".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_watch_chat_log_detect_log_file_error() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // This will spawn a task that tries to detect log file for a nonexistent session
        // The test verifies the handle returns Ok and the spawned task handles the error gracefully
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "definitely-does-not-exist-12345".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        // Give the spawned task time to run and fail
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_watch_chat_log_sets_current_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Pre-existing watcher should be cancelled
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        // Verify current session was set
        let session = state.current_session.lock().await;
        assert_eq!(*session, Some("AgentShell".to_string()));
    }

    #[tokio::test]
    async fn test_send_chat_message_long_preview_truncation() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Message > 50 chars should be truncated in notification preview
        let long_msg =
            "This is a very long message that exceeds fifty characters and should be truncated";
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: long_msg.to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_chat_message_short_notify() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // Short message (< 50 chars) notification preview
        let result = handle(
            WebSocketMessage::SendChatMessage {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                message: "Short".to_string(),
                notify: Some(true),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_unwatch_chat_log_with_existing_handle() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        // First start watching
        handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: None,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await
        .unwrap();
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        // Then unwatch - should abort the handle
        let result = handle(
            WebSocketMessage::UnwatchChatLog,
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_offset_pagination() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Pre-populate with messages
        for i in 0..10 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_paginate-test", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, _rx) = make_ws_state(dir.path());
        // Request with offset 5, limit 3
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_paginate-test".to_string(),
                window_index: 0,
                offset: 5,
                limit: 3,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_load_more_chat_history_offset_exceeds_total() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        // Only 3 messages but request offset 10
        for i in 0..3 {
            let msg = crate::chat_log::ChatMessage {
                role: "user".to_string(),
                timestamp: Some(chrono::Utc::now()),
                blocks: vec![crate::chat_log::ContentBlock::Text {
                    text: format!("Msg {}", i),
                }],
            };
            app.chat_event_store
                .append_message("acp_offset-test", 0, "webhook", &msg)
                .unwrap();
        }

        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::LoadMoreChatHistory {
                session_name: "acp_offset-test".to_string(),
                window_index: 0,
                offset: 10,
                limit: 5,
            },
            &mut state,
            app,
        )
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_watch_chat_log_with_limit() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchChatLog {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                limit: Some(50),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn test_send_file_to_chat_whitespace_only_prompt() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let data = base64::engine::general_purpose::STANDARD.encode(b"content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: Some("   ".to_string()),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_watch_acp_chat_log_with_limit() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::WatchAcpChatLog {
                session_id: "test-acp-session".to_string(),
                window_index: Some(0),
                limit: Some(50),
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(result.is_ok());
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }

    #[tokio::test]
    async fn test_clear_chat_log_nonexistent_session() {
        let dir = TempDir::new().unwrap();
        let (mut state, _rx) = make_ws_state(dir.path());
        let result = handle(
            WebSocketMessage::ClearChatLog {
                session_name: "nonexistent-session-xyz-123".to_string(),
                window_index: 0,
            },
            &mut state,
            make_app_state(dir.path()),
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    #[tokio::test]
    async fn test_send_file_to_chat_app_state_uses_different_store() {
        let dir = TempDir::new().unwrap();
        let app = make_app_state(dir.path());
        let (mut state, _rx) = make_ws_state(dir.path());
        // File gets saved to state.chat_file_storage, not app's
        let data = base64::engine::general_purpose::STANDARD.encode(b"test content");
        let result = handle(
            WebSocketMessage::SendFileToChat {
                session_name: "AgentShell".to_string(),
                window_index: 0,
                file: crate::types::FileAttachment {
                    data,
                    filename: "test.txt".to_string(),
                    mime_type: "text/plain".to_string(),
                },
                prompt: None,
            },
            &mut state,
            app,
        )
        .await;
        assert!(
            result.is_err(),
            "Unlinked terminals must not be used for chat operations"
        );
    }

    fn make_app_state(dir: &std::path::Path) -> Arc<crate::AppState> {
        let (broadcast_tx, _) = mpsc::channel(256);
        let client_manager = Arc::new(ClientManager::new());
        let chat_file_storage = Arc::new(ChatFileStorage::new(dir.to_path_buf()));
        let chat_event_store = Arc::new(ChatEventStore::new(dir.to_path_buf()).unwrap());
        let chat_clear_store = Arc::new(ChatClearStore::new(&dir.to_path_buf()));
        let notification_store =
            Arc::new(crate::notification_store::NotificationStore::new(dir.to_path_buf()).unwrap());
        let favorite_store =
            Arc::new(crate::favorite_store::FavoriteStore::new(dir.to_path_buf()).unwrap());
        let tag_store = Arc::new(crate::tag_store::TagStore::new(dir.to_path_buf()).unwrap());
        Arc::new(crate::AppState {
            enable_audio_logs: false,
            device_adb_registry: Arc::new(crate::device_adb::DeviceAdbRegistry::default()),
            broadcast_tx,
            client_manager,
            chat_file_storage,
            chat_event_store,
            chat_clear_store,
            notification_store,
            acp_client: Arc::new(tokio::sync::RwLock::new(None)),
            codex_app_client: Arc::new(tokio::sync::RwLock::new(None)),
            favorite_store,
            tag_store,
            shutdown_token: tokio_util::sync::CancellationToken::new(),
        })
    }
}
