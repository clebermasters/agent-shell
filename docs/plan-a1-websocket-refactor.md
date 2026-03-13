# Plan: A1 — Split `websocket/mod.rs` into Domain Modules

> **Issue:** A1 (🟠 High) — `websocket/mod.rs` is 2,840 lines, handling 45+ message types, PTY I/O, bootstrapping, and broadcasting in one file.
> **Goal:** Split into focused domain modules of 200–400 lines each, with no behavior changes.
> **Approach:** Pure refactor — extract handlers into modules, keep `mod.rs` as thin dispatcher.

---

## Current State

```
websocket/
  └── mod.rs  (2,840 lines — everything)
```

### Line Budget by Domain

| Domain | Lines | Message Types | Key Functions |
|--------|-------|---------------|---------------|
| Imports + structs + state | 1–147 | — | `ClientManager`, `PtySession`, `WsState` |
| Handler entry (`ws_handler`) | 149–274 | — | WebSocket upgrade, client lifecycle |
| Terminal/PTY I/O | 278–406 | 6 | `Input`, `InputViaTmux`, `Resize`, `SendEnterKey` |
| Tmux session/window mgmt | 409–649 | 8 | `Create/Kill/Rename Session/Window`, `ListWindows`, `SelectWindow` |
| System/stats/audio | 476–496, 652–682 | 3 | `GetStats`, `Ping`, `AudioControl` |
| Cron management | 685–769 | 6 | CRUD + toggle + test |
| Dotfile management | 771–876 | 6 | CRUD + history + templates |
| Chat log watching | 878–1316 | 4 | `WatchChatLog`, `WatchAcpChatLog`, `Unwatch`, `Clear` |
| Chat message sending | 1317–1549 | 2 | `SendFileToChat`, `SendChatMessage` |
| ACP backend | 1551–2333 | 13 | Session mgmt, prompts, config, history |
| `attach_to_session()` | 2417–2802 | — | PTY creation, bootstrap, reader task |
| `cleanup_session()` | 2804–2840 | — | PTY/watcher teardown |
| Helpers | 31–58, 2336–2415 | — | `write_acp_session_file`, `merge_history_messages`, `send_message` |

---

## Target State

```
websocket/
  ├── mod.rs              (~250 lines)  — re-exports, ws_handler, message dispatch skeleton
  ├── types.rs            (~100 lines)  — BroadcastMessage, PtySession, WsState, HistoryMessageEntry
  ├── client_manager.rs   (~80 lines)   — ClientManager struct + impl
  ├── terminal_cmds.rs    (~300 lines)  — Input, InputViaTmux, SendEnterKey, Resize, attach_to_session, cleanup
  ├── session_cmds.rs     (~250 lines)  — Create/Kill/Rename Session/Window, ListWindows, SelectWindow
  ├── chat_cmds.rs        (~500 lines)  — WatchChatLog, WatchAcpChatLog, Unwatch, Clear, SendChat, SendFile
  ├── acp_cmds.rs         (~400 lines)  — All ACP handlers + event loop
  ├── cron_cmds.rs        (~100 lines)  — Cron CRUD
  ├── dotfiles_cmds.rs    (~120 lines)  — Dotfile CRUD
  └── system_cmds.rs      (~80 lines)   — GetStats, Ping, AudioControl
```

**Total:** ~2,180 lines across 10 files (same logic, less boilerplate from shared imports).

---

## Phases

### Phase 1 — Extract types and `ClientManager` (low risk)

**Files created:** `types.rs`, `client_manager.rs`
**Changes to `mod.rs`:** Replace inline definitions with `use` imports

1. Move `BroadcastMessage`, `PtySession`, `WsState`, `HistoryMessageSource`, `HistoryMessageEntry` → `types.rs`
2. Move `ClientManager` struct + impl → `client_manager.rs`
3. Add `pub(crate)` visibility where needed for cross-module access
4. Update `mod.rs` imports: `mod types; mod client_manager; pub use client_manager::ClientManager;`
5. **Verify:** `cargo build` compiles clean

### Phase 2 — Extract simple domain handlers (low risk)

**Files created:** `cron_cmds.rs`, `dotfiles_cmds.rs`, `system_cmds.rs`

These handlers are self-contained with minimal state dependencies.

1. Create `cron_cmds.rs` — move lines 685–769 (cron match arms) into `pub async fn handle_cron_message(msg, state)` dispatcher
2. Create `dotfiles_cmds.rs` — move lines 771–876
3. Create `system_cmds.rs` — move lines 476–496, 652–682 (Ping, GetStats, AudioControl)
4. Each module receives `&WsState` and relevant message fields as parameters
5. In `mod.rs`, replace match arms with calls: `WebSocketMessage::ListCronJobs { .. } => cron_cmds::handle_list(state).await`
6. **Verify:** `cargo build` + manual test (list cron jobs, read dotfile, get stats)

### Phase 3 — Extract session management (medium risk)

**Files created:** `session_cmds.rs`

Session management calls tmux CLI and broadcasts results. Needs `WsState.message_tx` and `WsState.client_manager`.

1. Move `ListSessions`, `CreateSession`, `KillSession`, `RenameSession` handlers
2. Move `ListWindows`, `SelectWindow`, `CreateWindow`, `KillWindow`, `RenameWindow` handlers
3. Each handler: `pub async fn handle_*(state: &WsState, ...) -> ()`
4. **Verify:** `cargo build` + manual test (create session, rename, kill, switch windows)

### Phase 4 — Extract terminal/PTY handlers (high complexity)

**Files created:** `terminal_cmds.rs`

This is the most complex extraction because `attach_to_session()` (386 lines) has deep coupling to `WsState`, spawns blocking tasks, and manages PTY lifecycle.

1. Move `Input`, `InputViaTmux`, `SendEnterKey`, `Resize` handlers
2. Move `attach_to_session()` function (lines 2417–2802)
3. Move `cleanup_session()` function (lines 2804–2840)
4. Move PTY reader task logic (currently inline in `attach_to_session`)
5. Key challenge: `attach_to_session` accesses `WsState.current_pty`, `message_tx`, `client_manager`, `chat_event_store` — pass `&WsState` or extract needed fields
6. **Verify:** `cargo build` + **full attach/type/resize test** via `scripts/test_enter_key.py`

### Phase 5 — Extract chat handlers (high complexity)

**Files created:** `chat_cmds.rs`

Chat handlers spawn long-lived watcher tasks and merge history from multiple sources.

1. Move `WatchChatLog` handler (lines 879–1012) — spawns file watcher
2. Move `WatchAcpChatLog` handler (lines 1014–1143) — polls OpenCode DB
3. Move `UnwatchChatLog`, `ClearChatLog` handlers
4. Move `SendChatMessage`, `SendFileToChat` handlers
5. Move helper: `merge_history_messages()` (lines 2348–2405)
6. Key challenge: watcher tasks store `JoinHandle` in `WsState.chat_log_handle` — ensure abort logic still works
7. **Verify:** `cargo build` + manual test (watch chat, send message, clear, file upload)

### Phase 6 — Extract ACP handlers (high complexity)

**Files created:** `acp_cmds.rs`

ACP is the largest domain (780 lines) with its own event loop and async session management.

1. Move `SelectBackend` + ACP client initialization + event handler spawn
2. Move `AcpCreateSession`, `AcpResumeSession`, `AcpForkSession`, `AcpListSessions`
3. Move `AcpSendPrompt`, `SendFileToAcpChat`, `AcpCancelPrompt`
4. Move `AcpSetModel`, `AcpSetMode`, `AcpRespondPermission`
5. Move `AcpLoadHistory`, `AcpClearHistory`, `AcpDeleteSession`
6. Move helper: `write_acp_session_file()` (lines 31–58)
7. Key challenge: ACP event handler spawns a long-lived task that persists messages and broadcasts — needs `WsState.chat_event_store`, `client_manager`, `acp_client`
8. **Verify:** `cargo build` + manual test (select backend, create/resume ACP session, send prompt)

### Phase 7 — Clean up `mod.rs` dispatcher

After all extractions, `mod.rs` should contain:

```rust
mod types;
mod client_manager;
mod terminal_cmds;
mod session_cmds;
mod chat_cmds;
mod acp_cmds;
mod cron_cmds;
mod dotfiles_cmds;
mod system_cmds;

pub use client_manager::ClientManager;

// ws_handler() — ~120 lines (unchanged)
// handle_message() — ~80 lines (thin match dispatching to modules)
```

1. Review `mod.rs` — should be ~250 lines max
2. Remove any dead imports
3. Ensure all `pub` visibility is minimal (`pub(crate)` preferred)
4. **Verify:** `cargo build` + full regression (all message types)

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Breaking PTY I/O during Phase 4 | Run `test_enter_key.py` after every change |
| Visibility/borrow issues across modules | `WsState` fields use `Arc<Mutex<>>` already — safe to pass `&WsState` |
| ACP event handler coupling | Pass `Arc<WsState>` clone to spawned tasks (already done for most) |
| Watcher task lifecycle | Keep `JoinHandle` storage in `WsState`, modules return handles |
| Merge conflicts (if other work in progress) | Do this on a dedicated branch, merge when stable |

## Rules

1. **No behavior changes** — this is a pure structural refactor
2. **One phase per commit** — easy to bisect if something breaks
3. **Build after every phase** — never proceed with a broken build
4. **Manual smoke test after Phases 4–6** — these touch I/O paths
5. **Keep `handle_message()` in `mod.rs`** — it's the dispatch table, visible at a glance

---

## Estimated Effort

| Phase | Complexity | Est. Size |
|-------|-----------|-----------|
| Phase 1 — Types + ClientManager | Low | Small |
| Phase 2 — Cron, Dotfiles, System | Low | Small |
| Phase 3 — Session management | Medium | Medium |
| Phase 4 — Terminal/PTY | High | Large |
| Phase 5 — Chat handlers | High | Large |
| Phase 6 — ACP handlers | High | Large |
| Phase 7 — Final cleanup | Low | Small |

---

## Success Criteria

- [ ] `cargo build --release` passes
- [ ] `cargo test` passes
- [ ] `mod.rs` is under 300 lines
- [ ] No file exceeds 500 lines
- [ ] All 45+ message types still work (manual smoke test)
- [ ] `scripts/test_enter_key.py` passes (PTY path regression)
- [ ] No new `pub` items leaked to crate root
