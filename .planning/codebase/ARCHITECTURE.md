---
focus: arch
generated: 2026-03-25
---

# Architecture

**Analysis Date:** 2026-03-25

## Pattern Overview

**Overall:** Client-Server with WebSocket as the primary transport layer

**Key Characteristics:**
- Flutter mobile/web app (client) connects to a Rust HTTP/WebSocket server (backend) over a single persistent WebSocket connection
- All real-time interaction — terminal I/O, session management, chat, cron, dotfiles, file browsing — flows through one WebSocket connection per client
- REST HTTP endpoints handle non-realtime concerns: notifications, file downloads, cron job CRUD, and static asset serving
- Backend manages tmux sessions as the underlying terminal multiplexer; it wraps the `tmux` CLI and exposes PTY I/O via WebSocket
- AI agent integration (ACP/OpenCode) runs as a subprocess; the backend bridges ACP JSON-RPC to the WebSocket protocol

## Layers

**Flutter Presentation Layer:**
- Purpose: UI rendering, user input, state display
- Location: `flutter/lib/features/*/screens/` and `flutter/lib/features/*/widgets/`
- Contains: `ConsumerWidget` / `ConsumerStatefulWidget` screens, reusable widgets
- Depends on: Providers layer (via `ref.watch` / `ref.read`)
- Used by: Nothing — top of the dependency chain

**Flutter State Layer (Riverpod Providers):**
- Purpose: Reactive state management bridging UI and services
- Location: `flutter/lib/features/*/providers/`
- Contains: `StateNotifier` subclasses, `StateNotifierProvider`, `StreamProvider`, `Provider` definitions
- Pattern: `XxxState` (immutable, `copyWith`) + `XxxNotifier extends StateNotifier<XxxState>`
- Depends on: `WebSocketService` (via `sharedWebSocketServiceProvider`), `SharedPreferences`
- Used by: Screen widgets via `ref.watch`

**Flutter Data Layer:**
- Purpose: Models, service abstractions, repository interfaces
- Location: `flutter/lib/data/`
- Contains:
  - `data/models/` — plain Dart classes with `fromJson`/`toJson` (immutable with `copyWith`)
  - `data/services/` — `WebSocketService`, `TerminalService`, `WhisperService`, `AudioService`
  - `data/repositories/` — thin wrappers (CronRepository, DotfilesRepository, etc.)
- Depends on: External packages (web_socket_channel, xterm)
- Used by: Providers layer

**Flutter Core Layer:**
- Purpose: Shared configuration, theme, utilities, cross-cutting widgets
- Location: `flutter/lib/core/`
- Contains: `AppConfig`, `BuildConfig`, `AppTheme`, `sharedPreferencesProvider`, `ConnectionStatusBanner`
- Used by: All other Flutter layers

**Rust HTTP/WebSocket Layer:**
- Purpose: Entry point, routing, auth middleware, static file serving
- Location: `backend-rust/src/main.rs`
- Contains: Axum router setup, `AppState` struct, CORS config, TLS setup, graceful shutdown
- Depends on: All backend subsystems
- Used by: Operating system / process runner

**Rust WebSocket Dispatch Layer:**
- Purpose: Per-client connection lifecycle, message parsing, command dispatch
- Location: `backend-rust/src/websocket/mod.rs` (321 lines), `backend-rust/src/websocket/types.rs`
- Contains: `ws_handler`, `handle_socket`, `handle_message` (exhaustive match over `WebSocketMessage` enum), `WsState` (per-connection state), `ClientManager`
- Depends on: All `*_cmds` modules
- Used by: Axum router (`/ws` route)

**Rust Command Handler Modules:**
- Purpose: Domain-specific WebSocket command implementations
- Location: `backend-rust/src/websocket/`
- Files and sizes:
  - `acp_cmds.rs` (1476 lines) — ACP session lifecycle, prompt relay, history, permission requests
  - `chat_cmds.rs` (1452 lines) — chat log watching, pagination, file sending, event broadcasting
  - `terminal_cmds.rs` (706 lines) — PTY attach, input, resize, enter key, cleanup
  - `file_cmds.rs` (400 lines) — file listing, read, delete, rename, session cwd
  - `session_cmds.rs` (360 lines) — tmux session/window CRUD, list, rename
  - `dotfiles_cmds.rs` (285 lines) — dotfile CRUD, history, version restore, templates
  - `cron_cmds.rs` (228 lines) — cron job WebSocket pass-through
  - `system_cmds.rs` (219 lines) — ping/pong, system stats, audio control

**Rust Backend Subsystems:**
- Purpose: Business logic for individual domains
- Location: `backend-rust/src/`
- Modules:
  - `tmux/` — wraps `tmux` CLI to create/kill/attach sessions and windows
  - `acp/` — ACP client (`client.rs`, `session.rs`, `messages.rs`); spawns OpenCode as a child process and bridges JSON-RPC over stdin/stdout
  - `chat_event_store.rs` — SQLite-backed chat event persistence (`chat_events.db`)
  - `notification_store.rs` — SQLite-backed notification persistence
  - `chat_file_storage.rs` — file attachment storage for chat
  - `chat_clear_store.rs` — tracks cleared chat sessions
  - `chat_log/` — parsers for claude, codex, opencode log formats; file watcher
  - `cron/` — cron job scheduler and manager (global `CRON_MANAGER` singleton)
  - `cron_handler.rs` — Axum REST handlers for `/api/cron/jobs`
  - `dotfiles/` — dotfile read/write/history with versioning
  - `monitor/` — background task polling tmux state every 250ms and broadcasting changes
  - `audio/` — audio stream handling
  - `auth.rs` — `AUTH_TOKEN` middleware (query param `?token=` or `X-Auth-Token` header, constant-time comparison)
  - `terminal_buffer.rs` — terminal output buffering
  - `notification.rs`, `notification_handler.rs` — notification model and REST handlers
  - `types/mod.rs` — all shared types: `WebSocketMessage` enum (client→server), `ServerMessage` enum (server→client)

## Data Flow

**Terminal I/O Flow:**

1. User types in Flutter `TerminalScreen` (xterm widget)
2. `TerminalNotifier` calls `_wsService.sendTerminalData(session, data)` → sends `{"type":"input","data":"..."}`
3. `WebSocketService.send()` encodes JSON and writes to WebSocket channel
4. Rust `handle_socket` receives text frame, deserializes to `WebSocketMessage::Input {data}`
5. `terminal_cmds::handle_input(state, data)` writes to the PTY writer of the active `PtySession`
6. PTY reader task on backend sends `ServerMessage::Output {data}` back over the per-client mpsc channel
7. Rust sender task forwards `BroadcastMessage::Text(json)` to the WebSocket sink
8. Flutter `WebSocketService._onMessage` adds to `_messageController` stream
9. `TerminalNotifier` listens to `messages` stream, writes `output` type to the `xterm.Terminal` buffer

**Session List / Monitor Push Flow:**

1. `TmuxMonitor` polls `tmux list-sessions` every 250ms
2. On state change, broadcasts `ServerMessage::SessionsList` via `broadcast_tx`
3. `mpsc` broadcast loop in `main.rs` calls `ClientManager.broadcast(msg)` to all connected clients
4. All listening Flutter `SessionsNotifier` instances receive `sessions-list` type and update state

**ACP (AI Agent) Flow:**

1. Flutter sends `acp-create-session` or `acp-send-prompt` WebSocket message
2. `acp_cmds::handle` in Rust takes the `AcpClient` from `AppState.acp_client` (RwLock-guarded singleton)
3. `AcpClient` sends JSON-RPC request over child process stdin (OpenCode process)
4. ACP event loop reads stdout line-by-line, emits `AcpEvent::SessionUpdate` events
5. `acp_cmds` converts events to `ServerMessage::AcpSessionUpdate` / `AcpChatEvent` etc.
6. Flutter `ChatNotifier` receives these and updates chat state

**Host Selection + Reconnect Flow:**

1. `HostsNotifier` (persisted in `SharedPreferences`) holds `selectedHost`
2. `sharedWebSocketServiceProvider` uses `ref.listen<HostsState>` to watch host changes
3. On host change, calls `WebSocketService.connect('${host.wsUrl}/ws')`
4. `WebSocketService` appends `?token=` query param, upgrades `ws://` to `wss://` on web
5. Auto-reconnect: 5s timer on error/close; ping every 30s with 20s pong timeout

**State Management:**
- All Riverpod state is immutable — `XxxState` uses `copyWith` pattern, never mutated in place
- `sharedWebSocketServiceProvider` is a plain `Provider<WebSocketService>` (singleton per Riverpod scope)
- Feature providers (e.g., `sessionsProvider`, `terminalProvider`) are `StateNotifierProvider` that receive the shared WS service via `ref.watch(sharedWebSocketServiceProvider)`
- `connectionStatusProvider` is a `StreamProvider` wrapping `WebSocketService.connectionStatus`

## Key Abstractions

**`WebSocketMessage` enum (Rust, `backend-rust/src/types/mod.rs`):**
- Purpose: Typed representation of all client-to-server messages
- Pattern: `#[serde(tag = "type", rename_all = "kebab-case")]` — the `type` JSON field maps directly to enum variant names
- Examples: `WebSocketMessage::ListSessions`, `WebSocketMessage::Input {data}`, `WebSocketMessage::AcpSendPrompt {session_id, message}`

**`ServerMessage` enum (Rust, `backend-rust/src/types/mod.rs`):**
- Purpose: Typed representation of all server-to-client messages
- Pattern: Same serde tag strategy — `#[serde(tag = "type", rename_all = "kebab-case")]`
- Examples: `ServerMessage::Output {data}`, `ServerMessage::SessionsList {sessions}`, `ServerMessage::AcpChatEvent {..}`

**`WebSocketService` (Flutter, `flutter/lib/data/services/websocket_service.dart`):**
- Purpose: Single connection manager; serializes all outgoing messages, broadcasts all incoming messages as typed maps
- Pattern: Facade with domain-specific send methods (`requestSessions()`, `attachSession()`, `acpSendPrompt()`, etc.)
- Streams exposed: `messages`, `connectionState`, `connectionStatus`, `notificationStream`

**`sharedWebSocketServiceProvider` (Flutter, `flutter/lib/features/sessions/providers/sessions_provider.dart`):**
- Purpose: Singleton WS service scoped to Riverpod — all features share this one connection
- Pattern: Plain `Provider<WebSocketService>` with host-change listener and `ref.onDispose`

**`WsState` (Rust, `backend-rust/src/websocket/types.rs`):**
- Purpose: Per-client mutable state for an active WebSocket connection
- Fields: `current_pty` (active PTY session), `current_session`, `current_window`, `message_tx` (outbound channel), chat storage references, `client_manager`, `acp_client`

**`AppState` (Rust, `backend-rust/src/main.rs`):**
- Purpose: Shared server state cloned into every Axum handler
- Fields: `broadcast_tx`, `client_manager`, `chat_file_storage`, `chat_event_store`, `chat_clear_store`, `acp_client` (RwLock-wrapped singleton), `notification_store`

**`Host` model (Flutter, `flutter/lib/data/models/host.dart`):**
- Purpose: Backend server address — computes `wsUrl` and `httpUrl` based on port/platform
- Protocol logic: port 443 or `kIsWeb` → `wss://`/`https://`; otherwise `ws://`/`http://`

## Entry Points

**Flutter App Entry:**
- Location: `flutter/lib/main.dart`
- Triggers: App launch
- Responsibilities: Initialize `SharedPreferences`, initialize background service, inject `sharedPreferencesProvider` override into `ProviderScope`, handle cold-start notification tap, route to `LoginScreen` (web, no token) or `HomeScreen`

**Rust Server Entry:**
- Location: `backend-rust/src/main.rs`
- Triggers: Process start
- Responsibilities: Parse CLI args (`--audio`), init tracing, create broadcast channel + `ClientManager`, start `TmuxMonitor` task, initialize `CronManager`, configure Axum router, enforce `AUTH_TOKEN`, bind HTTP (4010) and optional HTTPS (4443) listeners

**WebSocket Upgrade Handler:**
- Location: `backend-rust/src/websocket/mod.rs::ws_handler`
- Triggers: HTTP GET `/ws` with `Upgrade: websocket` header (after auth middleware)
- Responsibilities: Assign UUID client ID, split socket into sender/receiver, register with `ClientManager`, spawn outbound forwarding task, run inbound message loop, clean up on disconnect

## Error Handling

**Strategy:** Propagate errors with `anyhow::Result` in Rust; stream-based error broadcasting in Flutter

**Patterns:**
- Rust command handlers return `anyhow::Result<()>`; errors are logged via `tracing::error!` in `handle_socket`
- Flutter `WebSocketService` uses try/catch on JSON decode; connection errors trigger the reconnect schedule
- `SessionsNotifier` propagates `error: String?` field in `SessionsState`; screens render error state from `ref.watch`
- ACP client failures emit `AcpEvent::Error {message}` which flows back to Flutter as a `ServerMessage::Error`

## Cross-Cutting Concerns

**Authentication:**
- Backend: Axum middleware (`auth.rs`) validates `AUTH_TOKEN` from `?token=` query param or `X-Auth-Token` header using constant-time comparison. Applied to all routes under `protected` router. Server exits at startup if `AUTH_TOKEN` env var is not set.
- Flutter: Auth token embedded at build time in `BuildConfig.authToken` (generated by Dockerfile from `.env`). Web login flow stores token in `SharedPreferences` under `AppConfig.keyWebAuthToken`. Token appended to WS URL as `?token=...`.

**Logging:**
- Rust: `tracing` crate with `EnvFilter` (`agentshell_backend=debug,tower_http=info`). Structured log lines with `info!`, `debug!`, `error!`, `warn!`.
- Flutter: `WebSocketService` has an internal `_logController` stream emitting timestamped strings; not currently printed to console (commented out).

**Validation:**
- Message parsing is the validation layer — Rust deserializes incoming WebSocket text to `WebSocketMessage` via serde; parse failures are logged and the connection continues.

**Notifications:**
- Flutter `WebSocketService.notificationStream` is a filtered view of `messages` where `type == 'notification-event'`
- Backend `notification_handler.rs` serves REST endpoints (`GET/POST /api/notifications`, `POST /api/notifications/:id/read`)
- Flutter `flutter_local_notifications` renders push notifications on Android; cold-start taps navigate to `AlertsScreen`

---

*Architecture analysis: 2026-03-25*
