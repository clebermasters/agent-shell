use super::types::{send_message, BroadcastMessage, WsState};
use crate::{
    chat_binding::ChatBinding,
    chat_log::ChatLogEvent,
    types::{ServerMessage, WebSocketMessage},
    AppState,
};
use anyhow::{Context, Result};
use std::sync::Arc;
use tokio::{sync::mpsc, task::JoinHandle};

#[path = "send_receipts.rs"]
mod receipts;

pub(super) async fn record_submission_delivered() { receipts::delivered().await; }

pub(crate) async fn send_value(
    tx: &mpsc::Sender<BroadcastMessage>,
    value: serde_json::Value,
) -> Result<()> {
    tx.send(BroadcastMessage::Text(Arc::new(value.to_string())))
        .await?;
    Ok(())
}
pub(crate) async fn send_bound(
    tx: &mpsc::Sender<BroadcastMessage>,
    message: ServerMessage,
    binding: &ChatBinding,
) -> Result<()> {
    let mut value = serde_json::to_value(message)?;
    value["bindingId"] = binding.binding_id.clone().into();
    value["conversationKey"] = binding.conversation_key.clone().into();
    send_value(tx, value).await
}

pub(crate) async fn require_binding(
    state: &WsState,
    session: &str,
    window: u32,
) -> Result<ChatBinding> {
    if !state
        .chat_target
        .as_ref()
        .is_some_and(|(name, index)| name == session && *index == window)
    {
        anyhow::bail!("Open this chat before sending");
    }
    let binding = state
        .chat_binding
        .lock()
        .await
        .clone()
        .context("Waiting for this terminal's conversation to be identified")?;
    state.chat_event_store.bindings.validate(&binding).await?;
    Ok(binding)
}

struct Child(Option<JoinHandle<()>>);
impl Drop for Child {
    fn drop(&mut self) {
        if let Some(child) = self.0.take() {
            child.abort();
        }
    }
}

pub(crate) async fn start_watch(
    state: &mut WsState,
    session: String,
    window: u32,
    limit: Option<usize>,
) -> Result<()> {
    let same_target = state
        .chat_target
        .as_ref()
        .is_some_and(|(name, index)| name == &session && *index == window);
    let previous_pane = if same_target {
        state
            .chat_binding
            .lock()
            .await
            .clone()
            .map(|binding| binding.pane.pane_id)
    } else {
        None
    };
    // An explicit watch resolves the requested alias first. Retain this chat's
    // prior pane only when the alias disappeared (for example after a rename).
    // A recreated session with the same name must use its new pane.
    let pinned = crate::chat_binding::locate_pane(&format!("{session}:{window}"))
        .await
        .ok()
        .or(previous_pane);
    if let Some(handle) = state.chat_log_handle.lock().await.take() {
        handle.abort();
    }
    *state.chat_binding.lock().await = None;
    state.chat_target = Some((session.clone(), window));
    *state.current_session.lock().await = Some(session.clone());
    *state.current_window.lock().await = Some(window);
    if let Some(handle) = state.chat_activity_handle.take() {
        handle.abort();
    }
    state.chat_activity_handle = Some(crate::chat_activity::start_tmux_watch(
        session.clone(),
        window,
        state.message_tx.clone(),
    ));
    let tx = state.message_tx.clone();
    let events = state.chat_event_store.clone();
    let clears = state.chat_clear_store.clone();
    let slot = state.chat_binding.clone();
    let kiro_slot = state.kiro_chat_output_tx.clone();
    let handle = tokio::spawn(async move {
        let mut child = Child(None);
        let mut last = String::new();
        let mut error_sent = false;
        let mut pane_target: Option<String> = pinned;
        loop {
            let resolution = if let Some(target) = &pane_target {
                match crate::chat_binding::inspect_pane(target).await {
                    Ok(pane) => events.bindings.resolve_inspected_pane(pane).await,
                    // A destroyed/recreated pane invalidates the old pin. Try
                    // this chat's own alias so it can bind the new incarnation.
                    Err(_) => events.bindings.resolve(&session, window).await,
                }
            } else {
                events.bindings.resolve(&session, window).await
            };
            match resolution {
                Ok(resolved) => {
                    error_sent = false;
                    let signature = serde_json::to_string(&resolved).unwrap_or_default();
                    if signature != last {
                        if let Some(handle) = child.0.take() {
                            handle.abort();
                        }
                        *slot.lock().await = resolved.binding.clone();
                        *kiro_slot.lock().unwrap_or_else(|e| e.into_inner()) = None;
                        if let Some(binding) = &resolved.binding {
                            pane_target = Some(binding.pane.pane_id.clone());
                        }
                        if send_value(&tx, serde_json::json!({"type":"chat-binding","sessionName":session,"windowIndex":window,"state":resolved,"sendReceipts":true})).await.is_err() { return; }
                        if let Some(binding) = resolved
                            .binding
                            .as_ref()
                            .filter(|b| b.transcript_path.as_os_str().is_empty())
                        {
                            let cleared=clears.get_cleared_at(binding.storage_key(),0).await;
                            let page=match read_bound_page(events.clone(),binding.clone(),cleared,0,limit.unwrap_or(30)).await{Ok(page)=>page,Err(_)=>return};
                            let _ = send_bound(
                                &tx,
                                ServerMessage::ChatHistory {
                                    session_name: session.clone(),
                                    window_index: window,
                                    messages: page.messages,
                                    tool: Some(binding.ai_tool()),
                                    has_more: page.has_more,
                                    total_count: page.total,
                                    context_window_usage: None,
                                    model_name: None,
                                },
                                binding,
                            )
                            .await;
                        }
                        if resolved.status == "required" {
                            let _ = send_message(&tx, ServerMessage::ChatLogError { error: "Waiting for this terminal's conversation to be identified automatically. Start or resume the agent in the terminal if it has not reported a conversation yet".into() }).await;
                        }
                        last = signature;
                    }
                    if let Some(binding) = resolved.binding {
                        if binding.transcript_path.as_os_str().is_empty() {
                            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                            continue;
                        }
                        if child.0.as_ref().map(|h| h.is_finished()).unwrap_or(true) {
                            let tx = tx.clone();
                            let events = events.clone();
                            let clears = clears.clone();
                            let name = session.clone();
                            let kiro_slot = kiro_slot.clone();
                            child.0 = Some(tokio::spawn(async move {
                                let cleared = clears.get_cleared_at(binding.storage_key(), 0).await;
                                if binding.pane.tool == "kiro" {
                                    let _ = run_kiro(
                                        &binding, &tx, &events, &name, window, kiro_slot, cleared,
                                    )
                                    .await;
                                    return;
                                }
                                let (event_tx, mut event_rx) = mpsc::channel(32);
                                let _watcher = match crate::chat_log::watcher::watch_log_file(
                                    &binding.transcript_path,
                                    binding.ai_tool(),
                                    event_tx,
                                    cleared,
                                    limit,
                                )
                                .await
                                {
                                    Ok(watcher) => watcher,
                                    Err(error) => {
                                        let _ = send_bound(&tx, ServerMessage::ChatLogError { error: format!("Waiting for this conversation's transcript: {error}") }, &binding).await;
                                        return;
                                    }
                                };
                                while let Some(event) = event_rx.recv().await {
                                    let message = match event {
                                        ChatLogEvent::History {
                                            messages: _,
                                            tool,
                                            has_more: _,
                                            total_count: _,
                                            context_window_usage,
                                            model_name,
                                        } => {
                                            let page=match read_bound_page(events.clone(),binding.clone(),cleared,0,limit.unwrap_or(30)).await {
                                                Ok(page)=>page,
                                                Err(error)=>{let _=send_bound(&tx,ServerMessage::ChatLogError{error:error.to_string()},&binding).await;return;}
                                            };
                                            ServerMessage::ChatHistory {
                                                session_name: name.clone(),
                                                window_index: window,
                                                messages: page.messages,
                                                tool: Some(tool),
                                                has_more: page.has_more,
                                                total_count: page.total,
                                                context_window_usage,
                                                model_name,
                                            }
                                        }
                                        ChatLogEvent::NewMessage { message } => {
                                            ServerMessage::ChatEvent {
                                                session_name: name.clone(),
                                                window_index: window,
                                                message,
                                                source: None,
                                            }
                                        }
                                        ChatLogEvent::ContextWindowUpdate { usage, model_name } => {
                                            ServerMessage::ContextWindowUpdate {
                                                session_name: name.clone(),
                                                window_index: window,
                                                context_window_usage: usage,
                                                model_name,
                                            }
                                        }
                                        ChatLogEvent::Error { error } => {
                                            ServerMessage::ChatLogError { error }
                                        }
                                    };
                                    // A superseded conversation must not forward one more event after /resume.
                                    if events.bindings.validate(&binding).await.is_err() {
                                        return;
                                    }
                                    if send_bound(&tx, message, &binding).await.is_err() {
                                        return;
                                    }
                                }
                            }));
                        }
                    }
                }
                Err(error) => {
                    if let Some(handle) = child.0.take() {
                        handle.abort();
                    }
                    *slot.lock().await = None;
                    if !error_sent {
                        let _ = send_value(&tx, serde_json::json!({"type":"chat-binding","sessionName":session,"windowIndex":window,
                            "state":{"status":"unavailable","paneToken":"","tool":"","binding":null,"candidates":[],"detail":error.to_string()}})).await;
                        let _ = send_message(
                            &tx,
                            ServerMessage::ChatLogError {
                                error: error.to_string(),
                            },
                        )
                        .await;
                        error_sent = true;
                    }
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        }
    });
    *state.chat_log_handle.lock().await = Some(handle);
    Ok(())
}

pub(crate) async fn handle(
    message: WebSocketMessage,
    state: &mut WsState,
    app: Arc<AppState>,
) -> Result<()> {
    let store = receipts::Store::new(state.chat_event_store.send_receipts_path());
    if let WebSocketMessage::GetChatSendResult { binding_id, request_id } = message {
        let lookup_store = store.clone();
        let (binding,request) = (binding_id.clone(),request_id.clone());
        let receipt = tokio::task::spawn_blocking(move || lookup_store.get(&binding,&request)).await??;
        return send_value(&state.message_tx,receipt.message(&binding_id,&request_id)).await;
    }
    let ids = match &message {
        WebSocketMessage::SendBoundChatMessage { binding_id,request_id,.. }
        | WebSocketMessage::SendBoundFileToChat { binding_id,request_id,.. }
        | WebSocketMessage::SendBoundUiAction { binding_id,request_id,.. } => Some((binding_id.clone(),request_id.clone())),
        _ => None,
    };
    let Some((binding,request)) = ids else { return handle_untracked(message,state,app).await; };
    let fingerprint = receipts::fingerprint(&message)?;
    let reserve_store = store.clone();
    let (b,r) = (binding.clone(),request.clone());
    let reservation = tokio::task::spawn_blocking(move || {
        let reservation=reserve_store.reserve(&b,&r,&fingerprint)?;
        // The guard is created in the same worker as the durable reservation.
        // If its caller disappears, dropping the unclaimed result marks it
        // uncertain instead of leaving a permanent "pending" receipt.
        let guard=matches!(&reservation,receipts::Reservation::New)
            .then(||receipts::InFlight::new(reserve_store,b,r));
        Ok::<_,anyhow::Error>((reservation,guard))
    }).await?;
    match reservation {
        Ok((receipts::Reservation::Existing(receipt),_)) => {
            // This is an idempotency lookup, never a second terminal write.
            send_value(&state.message_tx,receipt.message(&binding,&request)).await
        }
        Err(error) => send_value(&state.message_tx,receipts::Receipt::complete(&Err(error)).message(&binding,&request)).await,
        Ok((receipts::Reservation::New,Some(mut in_flight))) => {
            let started = std::time::Instant::now();
            tracing::info!(request_id=%request,binding_id=%binding,"Chat submission started");
            let result = receipts::during(store.clone(),binding.clone(),request.clone(),handle_untracked(message,state,app)).await;
            let receipt = receipts::Receipt::complete(&result);
            let persisted = receipt.clone();
            let (b,r) = (binding.clone(),request.clone());
            // Commit before trying to acknowledge on a socket that may be gone.
            let receipt = tokio::task::spawn_blocking(move || store.settle(&b,&r,&persisted)).await??;
            in_flight.settled();
            tracing::info!(request_id=%request,binding_id=%binding,success=receipt.success.unwrap_or(false),elapsed_ms=started.elapsed().as_millis(),"Chat submission settled");
            send_value(&state.message_tx,receipt.message(&binding,&request)).await
        }
        Ok((receipts::Reservation::New,None)) => unreachable!("A new submission owns its cancellation guard"),
    }
}

async fn handle_untracked(
    message: WebSocketMessage,
    state: &mut WsState,
    app: Arc<AppState>,
) -> Result<()> {
    match message {
        WebSocketMessage::SendBoundUiAction {
            binding_id,
            request_id: _,
            session_name,
            window_index,
            widget_id,
            message,
        } => {
            async {
                anyhow::ensure!(!message.trim().is_empty() && message.len() <= 4000, "Widget action must contain 1–4000 bytes");
                let binding = require_binding(state, &session_name, window_index).await?;
                anyhow::ensure!(binding.binding_id == binding_id, "This conversation changed; the widget action was not sent");
                let events = state.chat_event_store.clone();
                let key = binding.conversation_key.clone();
                let owned = tokio::task::spawn_blocking(move || -> Result<bool> {
                    Ok(events.list_messages(&key, 0)?.iter().any(|msg| msg.blocks.iter().any(|block| matches!(block, crate::chat_log::ContentBlock::UiWidget { id, .. } if id == &widget_id))))
                }).await??;
                anyhow::ensure!(owned, "This widget belongs to another conversation");
                super::chat_cmds::handle(WebSocketMessage::SendChatMessage { session_name, window_index, message, notify: Some(false) }, state, app).await
            }.await?;
        }
        WebSocketMessage::ListChatConversations {
            session_name,
            window_index,
        } => {
            let result = async {
                let mut state_data = state.chat_event_store.bindings.resolve(&session_name, window_index).await?;
                if let Some(binding) = &state_data.binding {
                    let pane = binding.pane.clone();
                    state_data.candidates = tokio::task::spawn_blocking(move || crate::chat_binding::conversation_candidates(&pane.tool, &pane.cwd)).await??;
                }
                send_value(&state.message_tx, serde_json::json!({"type":"chat-binding","sessionName":session_name,"windowIndex":window_index,"state":state_data,"sendReceipts":true})).await
            }.await;
            if let Err(error) = result {
                send_message(
                    &state.message_tx,
                    ServerMessage::ChatLogError {
                        error: error.to_string(),
                    },
                )
                .await?;
            }
        }
        WebSocketMessage::BindChatConversation {
            session_name,
            window_index,
            pane_token,
            conversation_id,
        } => {
            let result = async {
                let pane = crate::chat_binding::inspect_pane(&format!("{session_name}:{window_index}")).await?;
                if pane.token() != pane_token { anyhow::bail!("The terminal changed while you were selecting a conversation. Refresh and select again"); }
                let store = state.chat_event_store.bindings.clone();
                tokio::task::spawn_blocking(move || {
                    let candidate = crate::chat_binding::conversation_by_id(&pane.tool, &pane.cwd, &conversation_id, None)?;
                    if let Some(active) = crate::chat_binding::exact_open_transcript(&pane)? {
                        if active.id != candidate.id { anyhow::bail!("This terminal reports a different active conversation. Resume the selected conversation there before linking it"); }
                    }
                    store.bind(pane, candidate, "manual")
                }).await??;
                start_watch(state, session_name.clone(), window_index, Some(30)).await
            }.await;
            if let Err(error) = result {
                send_message(
                    &state.message_tx,
                    ServerMessage::ChatLogError {
                        error: error.to_string(),
                    },
                )
                .await?;
            }
        }
        WebSocketMessage::SendBoundChatMessage {
            binding_id,
            request_id: _,
            session_name,
            window_index,
            message,
        } => {
            async {
                anyhow::ensure!(!message.trim().is_empty(), "A message is required");
                let binding = require_binding(state, &session_name, window_index).await?;
                if binding.binding_id != binding_id {
                    anyhow::bail!("This conversation link changed. Reconnect before sending");
                }
                super::chat_cmds::handle(
                    WebSocketMessage::SendChatMessage {
                        session_name,
                        window_index,
                        message,
                        notify: Some(false),
                    },
                    state,
                    app,
                )
                .await
            }
            .await?;
        }
        WebSocketMessage::SendBoundFileToChat {
            binding_id,
            request_id: _,
            session_name,
            window_index,
            file,
            prompt,
        } => {
            async {
                let binding = require_binding(state, &session_name, window_index).await?;
                if binding.binding_id != binding_id {
                    anyhow::bail!("This conversation link changed. Reconnect before sending");
                }
                super::chat_cmds::handle(
                    WebSocketMessage::SendFileToChat {
                        session_name,
                        window_index,
                        file,
                        prompt,
                    },
                    state,
                    app,
                )
                .await
            }
            .await?;
        }
        WebSocketMessage::LoadMoreBoundChatHistory {
            binding_id,
            session_name,
            window_index,
            offset,
            limit,
        } => {
            match require_binding(state, &session_name, window_index).await {
                Ok(binding) if binding.binding_id == binding_id => {
                    load_more(state, session_name, window_index, offset, limit).await?
                }
                _ => send_message(
                    &state.message_tx,
                    ServerMessage::ChatLogError {
                        error:
                            "The conversation link changed. Reconnect before loading older messages"
                                .into(),
                    },
                )
                .await?,
            }
        }
        WebSocketMessage::ClearBoundChatLog {
            binding_id,
            session_name,
            window_index,
        } => match require_binding(state, &session_name, window_index).await {
            Ok(binding) if binding.binding_id == binding_id => {
                clear(state, session_name, window_index).await?
            }
            _ => {
                send_message(
                    &state.message_tx,
                    ServerMessage::ChatLogError {
                        error: "The conversation link changed. Reconnect before clearing history"
                            .into(),
                    },
                )
                .await?
            }
        },
        _ => {}
    }
    Ok(())
}

async fn read_bound_page(events:Arc<crate::chat_event_store::ChatEventStore>,binding:ChatBinding,cleared:Option<i64>,offset:usize,limit:usize)->Result<crate::history::Page>{
    let cached=crate::history::cached_bound_page(&events,&binding,cleared,offset,limit)?;
    if let Some(page)=cached{return Ok(page);}
    let permit=crate::history::permit().await?;
    tokio::task::spawn_blocking(move||{let _permit=permit;crate::history::bound_page(&events,&binding,cleared,offset,limit)}).await?
}

pub(crate) async fn load_more(
    state: &WsState,
    session: String,
    window: u32,
    offset: usize,
    limit: usize,
) -> Result<()> {
    let binding = require_binding(state, &session, window).await?;
    let cleared = state
        .chat_clear_store
        .get_cleared_at(binding.storage_key(), 0)
        .await;
    let page=read_bound_page(state.chat_event_store.clone(),binding.clone(),cleared,offset,limit).await?;
    state.chat_event_store.bindings.validate(&binding).await?;
    send_bound(
        &state.message_tx,
        ServerMessage::ChatHistoryChunk {
            session_name: session,
            window_index: window,
            messages: page.messages,
            has_more: page.has_more,
        },
        &binding,
    )
    .await
}

pub(crate) async fn clear(state: &mut WsState, session: String, window: u32) -> Result<()> {
    let binding = require_binding(state, &session, window).await?;
    state
        .chat_clear_store
        .set_cleared_at(
            binding.storage_key(),
            0,
            chrono::Utc::now().timestamp_millis(),
        )
        .await;
    state
        .chat_event_store
        .clear_messages(binding.storage_key(), 0)?;
    send_bound(
        &state.message_tx,
        ServerMessage::ChatLogCleared {
            session_name: session.clone(),
            window_index: window,
            success: true,
            error: None,
        },
        &binding,
    )
    .await?;
    start_watch(state, session, window, Some(30)).await
}

async fn run_kiro(
    binding: &ChatBinding,
    tx: &mpsc::Sender<BroadcastMessage>,
    events: &crate::chat_event_store::ChatEventStore,
    session: &str,
    window: u32,
    slot: Arc<std::sync::Mutex<Option<mpsc::UnboundedSender<String>>>>,
    cleared: Option<i64>,
) -> Result<()> {
    let (output_tx, mut output_rx) = mpsc::unbounded_channel();
    *slot.lock().unwrap_or_else(|e| e.into_inner()) = Some(output_tx);
    let page=read_bound_page(Arc::new(events.clone()),binding.clone(),cleared,0,30).await?;
    send_bound(
        tx,
        ServerMessage::ChatHistory {
            session_name: session.into(),
            window_index: window,
            messages: page.messages,
            tool: Some(binding.ai_tool()),
            has_more: page.has_more,
            total_count: page.total,
            context_window_usage: None,
            model_name: None,
        },
        binding,
    )
    .await?;
    let mut state = crate::chat_log::kiro_parser::KiroState::new(
        binding.pane.agent_pid,
        binding.conversation_id.clone(),
        binding.pane.cwd.clone().into(),
    );
    let mut interval = tokio::time::interval(std::time::Duration::from_millis(100));
    loop {
        let messages = tokio::select! {
            chunk = output_rx.recv() => match chunk { Some(chunk) => crate::chat_log::kiro_parser::parse_pty_chunk(&chunk, &mut state), None => return Ok(()) },
            _ = interval.tick() => {
                if crate::chat_log::kiro_parser::is_response_complete(&state) {
                    let remaining = std::mem::take(&mut state.response_buffer);
                    crate::chat_log::kiro_parser::emit_response(&remaining, &mut state)
                } else { vec![] }
            },
        };
        for message in messages {
            events.bindings.validate(binding).await?;
            events.append_message(binding.storage_key(), 0, "kiro-pty", &message)?;
            send_bound(
                tx,
                ServerMessage::ChatEvent {
                    session_name: session.into(),
                    window_index: window,
                    message,
                    source: None,
                },
                binding,
            )
            .await?;
        }
    }
}
