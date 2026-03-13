# Architecture Review — AgentShell (Backend + Flutter)

> **Date:** 2026-03-13
> **Scope:** Full codebase — Rust backend (~9.6K LOC) + Flutter app (~10K LOC)
> **Status:** Findings documented. Fixes tracked below.

---

## Legend

| Severity | Meaning |
|----------|---------|
| 🔴 Critical | Server crash or data loss |
| 🟠 High | Breakage under normal use |
| 🟡 Medium | Degrades stability or security |
| 🔵 Low | Code quality / cleanup |

---

## Part 1 — Rust Backend

### Architecture Overview

```
Axum HTTP/WebSocket Server
  ├── /ws  → websocket/mod.rs (2,850 lines — main handler)
  │           ├── ClientManager (RwLock<HashMap<UUID, mpsc::Sender>>)
  │           ├── PtySession (PTY writer/reader/child)
  │           ├── WsState (per-client session state)
  │           └── handle_message() — 60+ message types in one match
  ├── /api/clients
  ├── /api/chat/files/:id
  ├── /api/tmux/input
  └── static files (../dist)

Subsystems:
  tmux/        — CLI wrapper (list, create, kill, rename, capture)
  monitor/     — Polls tmux every 250ms, broadcasts changes
  chat_event_store.rs — SQLite chat persistence
  chat_log/    — File watcher + parsers (claude, opencode, codex)
  cron/        — Cron job management via crontab
  dotfiles/    — Dotfile read/write/version history
  acp/         — WebSocket client to OpenCode AI agent
  audio/       — Audio streaming via ffmpeg
  auth.rs      — AUTH_TOKEN middleware
```

### Bugs

| # | Severity | Description | File | Line |
|---|----------|-------------|------|------|
| B1 | ~~🔴 Critical~~ | ~~`panic!()` in `claude_parser`~~ — **FALSE: these are inside `#[cfg(test)]`, not production code** | `chat_log/claude_parser.rs` | — |
| B2 | ~~🔴 Critical~~ | ~~`panic!()` in `codex_parser`~~ — **FALSE: test-only** | `chat_log/codex_parser.rs` | — |
| B3 | ~~🔴 Critical~~ | ~~ENTER key not sent~~ — **FIXED: see B3a + B3b below** | `websocket/mod.rs` | — |
| B3a | ~~🔴 Critical~~ | ~~`WatchChatLog` (Flutter `watchChatLog`) called `attachSession(cols:80, rows:24)` on every invocation, shrinking the tmux window from its real size (e.g. 211×56) to 80×23, sending SIGWINCH to OpenCode. OpenCode's redraw during transitional states (post-streaming, post-resize) consumed Enter keystrokes, silently dropping subsequent messages.~~ — **FIXED: `attach_to_session()` now queries the actual tmux window dimensions and uses `max(requested, actual)` for the PTY size, preventing any shrink.** | `websocket/mod.rs` (`attach_to_session`) | — |
| B3b | ~~🟠 High~~ | ~~Enter keystroke arriving while TUI is transitioning (post-streaming, post-SIGWINCH) was consumed by redraw handler instead of message-submission handler.~~ — **FIXED: Added 80 ms delay between text send-keys and Enter send-keys in `InputViaTmux` handler, giving the TUI time to finish processing typed characters before Enter arrives.** | `websocket/mod.rs` (`InputViaTmux`) | — |
| B4 | ~~🟠 High~~ | ~~Duplicate unreachable match arms for ACP handlers (lines ~2259–2298) shadow working implementations~~ — **FIXED** | `websocket/mod.rs` | — |
| B5 | ~~🟠 High~~ | ~~`WatchChatLog` race~~ — **FALSE: already aborts previous watcher at line 891** | `websocket/mod.rs` | — |
| B6 | 🟠 High | ACP polling loop (`WatchAcpChatLog`) never exits on client disconnect — infinite goroutine leak | `websocket/mod.rs` | ~1110 |
| B7 | 🟡 Medium | SQLite opens new connection per operation — no pooling | `chat_event_store.rs` | all methods |
| B8 | 🟡 Medium | `capture_history_above_viewport` uses unsafe `.unwrap_or(0)` on tmux output parse | `tmux/mod.rs` | ~64 |
| B9 | 🟡 Medium | PTY reader exits silently after 5 retries — client gets no disconnect message | `websocket/mod.rs` | ~2635 |
| B10 | ~~🔵 Low~~ | ~~Debug `print!()` / timing measurements left in production paths~~ — **FIXED** | `websocket/mod.rs` | — |

### Architecture Problems

| # | Severity | Description | File |
|---|----------|-------------|------|
| A1 | 🟠 High | `websocket/mod.rs` is 2,850 lines — monolithic God file handling 60+ message types, PTY I/O, bootstrapping, broadcasting all in one place | `websocket/mod.rs` |
| A2 | 🟠 High | No tests anywhere in backend-rust | — |
| A3 | 🟠 High | No input validation: session names, window indices, file paths accepted as-is from client | `websocket/mod.rs` |
| A4 | 🟡 Medium | No rate limiting per client — spam possible | `auth.rs` / routes |
| A5 | 🟡 Medium | Cron jobs and dotfile history stored in memory — lost on restart | `cron/`, `dotfiles/` |
| A6 | 🟡 Medium | TmuxMonitor polls every 250ms — scales as O(clients) subprocess calls | `monitor/mod.rs` |
| A7 | 🟡 Medium | Unbounded `mpsc::UnboundedSender` for broadcasts — no backpressure, silent drops | `websocket/mod.rs` |
| A8 | 🟡 Medium | No per-session authorization — any authenticated client can access all tmux sessions | `websocket/mod.rs` |
| A9 | 🟡 Medium | No timeout on database operations beyond 5s busy_timeout | `chat_event_store.rs` |
| A10 | 🔵 Low | SQLite chat history has no pagination (loads all rows into memory) | `chat_event_store.rs` |
| A11 | 🔵 Low | No data expiration — chat history grows forever | `chat_event_store.rs` |
| A12 | 🔵 Low | `chat_log/` file watcher tasks can leak if session deleted before watcher exits | `chat_log/watcher.rs` |

### Suggested Refactor Structure (websocket/mod.rs)

```
websocket/
  ├── handler.rs        — upgrade, client lifecycle, send/receive loop
  ├── session_cmds.rs   — attach, create, kill, rename sessions/windows
  ├── input_cmds.rs     — Input, InputViaTmux, SendEnterKey, Resize
  ├── chat_cmds.rs      — SendChatMessage, WatchChatLog, ClearChatLog, etc.
  ├── acp_cmds.rs       — all ACP handlers
  ├── system_cmds.rs    — stats, ping, cron, dotfiles, audio
  └── pty_reader.rs     — PTY → WebSocket output loop
```

---

## Part 2 — Flutter App

### Architecture Overview

```
main.dart
  └── HomeScreen (BottomNavigationBar: Sessions, Cron, Dotfiles, System)
        └── SessionsScreen → TerminalScreen → ChatScreen (pushed)

State: Riverpod (StateNotifier + Provider)

sharedWebSocketServiceProvider   ← singleton WebSocket, reconnects on host change
  ↑ watched by
  ├── sessionsProvider            (TMUX + ACP session list)
  ├── chatProvider                (chat messages, audio, files — 1,100+ lines)
  ├── terminalProvider            (xterm emulation, PTY bridge)
  ├── cronProvider
  ├── dotfilesProvider
  └── systemProvider
```

### Bugs

| # | Severity | Description | File | Line |
|---|----------|-------------|------|------|
| B11 | ~~🔴 Critical~~ | ~~`sendInput()` appends `"\n"` (literal newline) instead of sending a separate Enter keypress — causes new line in terminal instead of submit~~ — **FIXED: `sendInput()` now sends text without `\n`, backend issues two separate tmux calls for text and Enter.** | `chat_provider.dart` | 834 |
| B12 | 🟠 High | Messages sent while WebSocket reconnecting are silently dropped — no queue | `websocket_service.dart` | 176 |
| B13 | 🟠 High | Chat history race condition: 500ms hardcoded delay before requesting history; fast session switching loads wrong history | `chat_provider.dart` | 826 |
| B14 | 🟠 High | `_connectionSubscription` never cancelled in dispose — stream leak | `chat_provider.dart` | 95 |
| B15 | 🟠 High | `_newAcpSessionController` StreamController never closed — leak on notifier recreation | `sessions_provider.dart` | 58 |
| B16 | 🟠 High | `_controllers` map (TerminalController per session) grows unbounded — memory leak on many session attaches | `terminal_provider.dart` | 63 |
| B17 | 🟡 Medium | Recording timer not guarded — if widget disposed during recording, timer keeps ticking | `chat_provider.dart` | 986 |
| B18 | 🟡 Medium | `setPrefs()` called after notifier construction — null crash if audio used before init | `chat_provider.dart` | 105 |
| B19 | 🟡 Medium | Optimistic ACP session deletion not rolled back on backend failure | `sessions_provider.dart` | 107 |
| B20 | 🔵 Low | Debug timing `print()` statements left in production | `sessions_provider.dart` | 70 |

### Architecture Problems

| # | Severity | Description | File |
|---|----------|-------------|------|
| A13 | 🟠 High | `chat_provider.dart` is 1,100+ lines — mixes chat messages, audio recording, transcription, ACP state, file handling | `chat_provider.dart` |
| A14 | 🟠 High | No tests anywhere in Flutter app | — |
| A15 | 🟠 High | Silent message drops throughout (session mismatch → `return` with no log) | `chat_provider.dart`, `sessions_provider.dart` |
| A16 | 🟡 Medium | WebSocket protocol inconsistent: `sessions-list` vs `session_list`, `output` vs `terminal_data`, `sessionName` vs `session-name` vs `session_name` | multiple providers |
| A17 | 🟡 Medium | Hardcoded terminal size 80×24 — not responsive to actual widget dimensions | `terminal_provider.dart`, `websocket_service.dart` |
| A18 | 🟡 Medium | Hardcoded delays in critical paths: 300ms, 500ms waits to "let backend catch up" — brittle under load | `sessions_provider.dart`, `chat_provider.dart` |
| A19 | 🟡 Medium | Auth token passed in URL query string — visible in browser history | `websocket_service.dart` |
| A20 | 🟡 Medium | API key stored in `SharedPreferences` plaintext — should use platform secure storage | `hosts_provider.dart` |
| A21 | 🟡 Medium | `terminal_service.dart` owns hydration state (`_hydrating`, `_hydrationQueue` maps) — business logic in wrong layer | `terminal_service.dart` |
| A22 | 🔵 Low | Unused packages: `dio`, `hive`, `hive_flutter` in pubspec.yaml | `pubspec.yaml` |
| A23 | 🔵 Low | Dead code: `session_repository.dart` — unused (StateNotifier used directly instead) | `data/repositories/` |
| A24 | 🔵 Low | Dead code: `HostSelectionScreen` — never navigated to | `features/hosts/` |
| A25 | 🔵 Low | Both `'output'` and `'terminal_data'` message types handled for same purpose — migration never cleaned up | `terminal_service.dart` |
| A26 | 🔵 Low | Theme hardcoded to dark mode — should respect system preference | `main.dart` |

### Suggested Split of chat_provider.dart

```
chat/providers/
  ├── chat_messages_provider.dart   — message list, history loading, chat events
  ├── chat_input_provider.dart      — sendInput, sendFile, ENTER key logic
  ├── chat_audio_provider.dart      — voice recording, Whisper transcription
  └── chat_acp_provider.dart        — ACP-specific streaming state
```

---

## Fix Tracker

### Priority 1 — Immediate (fix now)

| # | Fix | Status |
|---|-----|--------|
| F1 | Fix ENTER key: two separate `tmux` calls in `websocket/mod.rs`; remove `\n` from `chat_provider.dart:846` | ✅ DONE |
| F1b | Fix Enter key drop on second+ message: `watchChatLog` was calling `attachSession(80×24)`, shrinking window → SIGWINCH → OpenCode redraw consumed Enter. Fixed in `attach_to_session()`: query real tmux window size and use `max(requested, actual)`. Also added 80 ms delay between text and Enter in `InputViaTmux` handler. | ✅ DONE |
| F2 | Replace `panic!()` in `claude_parser.rs` and `codex_parser.rs` with error returns | N/A — these are test-only |
| F3 | Remove duplicate unreachable ACP match arms in `websocket/mod.rs` (~2259–2298) | ✅ DONE |

### Priority 2 — Stability (this week)

| # | Fix | Status |
|---|-----|--------|
| F4 | Cancel `_connectionSubscription` in `ChatNotifier.dispose()` | ✅ DONE |
| F5 | Close `_newAcpSessionController` in `SessionsNotifier.dispose()` | ✅ DONE |
| F6 | Cap `_controllers` map in `TerminalNotifier` (LRU or max-size eviction) | ✅ DONE |
| F7 | Abort previous watcher before starting new one in `WatchChatLog` handler | N/A — already implemented |
| F8 | Remove debug `print()` / timing statements from both codebases | ✅ DONE |
| F9 | Add basic input validation: session name (alphanumeric + dash), window index (0–999) | ⬜ TODO |

### Priority 3 — Architecture (next sprint)

| # | Fix | Status |
|---|-----|--------|
| F10 | Add SQLite connection pool (`r2d2-sqlite` or `sqlx`) | ⬜ TODO |
| F11 | Implement message queue in `WebSocketService` for send-while-disconnected | ⬜ TODO |
| F12 | Standardize WebSocket protocol field names (kebab-case throughout) | ⬜ TODO |
| F13 | Remove hardcoded 300ms/500ms delays; use proper async coordination | ⬜ TODO |
| F14 | Make terminal size responsive (read actual widget dimensions before attach) | ⬜ TODO |
| F15 | Persist cron jobs and dotfile history to SQLite | ⬜ TODO |
| F16 | Split `websocket/mod.rs` into domain modules | ⬜ TODO |
| F17 | Split `chat_provider.dart` into focused providers | ⬜ TODO |
| F18 | Remove unused packages: `dio`, `hive`, `hive_flutter` | ⬜ TODO |
| F19 | Remove dead code: `session_repository.dart`, `HostSelectionScreen` | ⬜ TODO |
| F20 | Add rate limiting per client (token bucket, 100 msg/s) | ⬜ TODO |

### Priority 4 — Production Hardening (later)

| # | Fix | Status |
|---|-----|--------|
| F21 | Write integration tests: attach → send → receive flow (backend) | ⬜ TODO |
| F22 | Write unit tests: message parsing, state transitions (Flutter) | ⬜ TODO |
| F23 | Add structured logging (replace `print!` with `tracing`, replace `print()` with `logger` package) | ⬜ TODO |
| F24 | Move API key / auth token to platform secure storage (iOS Keychain, Android Keystore) | ⬜ TODO |
| F25 | Add Prometheus metrics: active clients, message latency, error rate | ⬜ TODO |
| F26 | Add chat history pagination (backend: `LIMIT`/`OFFSET`; Flutter: load-more) | ⬜ TODO |
