---
focus: tech
generated: 2026-03-25
---

# External Integrations

**Analysis Date:** 2026-03-25

## APIs & External Services

**OpenAI:**
- Used for: Speech-to-text transcription (Whisper)
- Endpoint: `https://api.openai.com/v1/audio/transcriptions`
- Model: `whisper-1`, language: `en`
- SDK/Client: `dio ^5.8.0+1` (multipart form POST)
- Implementation: `flutter/lib/data/services/whisper_service.dart`
- Auth: `Authorization: Bearer <OPENAI_API_KEY>` header; key stored in `SharedPreferences` (key `AppConfig.keyOpenAiApiKey`) or embedded via `BuildConfig.defaultApiKey`
- Note: Key is embedded in APK at build time via `docker/flutter/Dockerfile` build args

**AWS S3:**
- Used for: Web build hosting and deployment
- Client: AWS CLI (`aws s3 cp`, `aws s3 sync`) — called from `scripts/deploy-web.sh`
- Env var: `S3_WEB_BUCKET` (e.g. `s3://your-bucket`)
- Cache strategy: entry-point files (`index.html`, `flutter_service_worker.js`, etc.) get `no-cache`; content-hashed assets get `max-age=31536000, immutable`

**Cloudflare:**
- Used for: CDN in front of S3 web hosting; cache purge after deployments
- Auth: `X-Auth-Key` + `X-Auth-Email` headers via Cloudflare Global API Key
- API calls: `https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache`
- Zone ID auto-detected from `CF_DOMAIN` if `CF_ZONE_ID` not set
- Purges: `index.html`, `main.dart.js`, `flutter_service_worker.js`, `flutter_bootstrap.js`, `flutter.js`, `manifest.json`
- Implementation: `scripts/deploy-web.sh`
- Env vars: `CF_API_KEY`, `CF_EMAIL`, `CF_ZONE_ID`, `CF_DOMAIN`

**GitHub Container Registry (GHCR):**
- Used for: Storing and distributing `agentshell-flutter-base:latest` Docker image
- Auth: `GITHUB_TOKEN` (GitHub Actions secret)
- Push: CI workflow (`docker/build-push-action@v5`)
- Pull: All Flutter build jobs pull the cached base image from `ghcr.io/$OWNER/agentshell-flutter-base:latest`
- Caching: `cache-from: type=gha,scope=flutter-base` for GHA cache layer reuse

## Data Storage

**Databases:**
- SQLite — bundled via `rusqlite 0.38.0` (bundled feature; no system SQLite needed)
  - `chat_events.db`: chat history, indexed by `(session_name, window_index, timestamp_millis)`; also stores `acp_deleted_sessions`. Path: `<backend_cwd>/chat_events.db`. Implementation: `backend-rust/src/chat_event_store.rs`
  - `notifications.db`: notifications and notification file attachments. Path: `<backend_cwd>/notifications.db`. Implementation: `backend-rust/src/notification_store.rs`
  - Both databases use `busy_timeout(5s)` and are opened per-operation (no connection pool)

**Local Key-Value Store (Flutter):**
- `shared_preferences ^2.5.4` — settings, API key, web auth token, preferences
- `hive ^2.2.3` + `hive_flutter ^1.1.0` — local NoSQL boxes (hosts list, configuration)

**File Storage:**
- Chat file attachments: stored on disk in `<backend_cwd>/chat_files/` directory; tracked by in-memory `ChatFileStorage` (`backend-rust/src/chat_file_storage.rs`). Served at `/api/chat/files/:id`
- Notification file attachments: stored on disk under `<backend_cwd>/notification_files/`; served at `/api/notifications/files/:id`
- Dotfile backups: stored with versioned history by `backend-rust/src/dotfiles/mod.rs`

**Caching:**
- None — no Redis or in-process cache beyond in-memory Rust data structures

## Authentication & Identity

**Auth Provider: Token-based (custom)**
- Implementation: `backend-rust/src/auth.rs`
- `AUTH_TOKEN` env var required at backend startup (server exits with clear error if unset)
- Token passed as query parameter `?token=<value>` on all WebSocket and API connections, or as `X-Auth-Token` header
- Constant-time comparison using `subtle::ConstantTimeEq` to prevent timing attacks
- Flutter client: `BuildConfig.authToken` takes priority; falls back to `runtimeToken` from `SharedPreferences` (key `AppConfig.keyWebAuthToken`) for web login flow
- All WebSocket (`/ws`) and API routes are protected by `auth::auth_middleware` Axum layer

## Monitoring & Observability

**Error Tracking:**
- None — no Sentry or similar service integrated

**Logs:**
- Backend: `tracing` + `tracing-subscriber` with `EnvFilter`; default level `agentshell_backend=debug,tower_http=info`. Controlled via `RUST_LOG` env var
- Production: logs collected by `systemd`/`journalctl` (service name `agentshell`)
- Flutter: `WebSocketService` maintains internal log stream via `_logController` (StreamController); no external logging service

**Audio Streaming Debug:**
- Optional `--audio` CLI flag enables verbose audio logs via global `AtomicBool` `ENABLE_AUDIO_LOGS`

## CI/CD & Deployment

**Hosting:**
- Web: AWS S3 static hosting + Cloudflare CDN
- Backend: Linux VPS/server running as a `systemd` service (managed by `start.sh`)
- Android: GitHub Releases (APK artifact attached on `release` event)

**CI Pipeline: GitHub Actions**

Flutter build (`.github/workflows/flutter.yml`):
- Triggers: pushes to `main` touching `flutter/**` or `docker/**`, PRs, `release` events, manual dispatch
- Three parallel jobs after `base-image` completes: `build-apk`, `build-web`, `build-linux`
- Base image pushed to GHCR; APK/web/linux artifacts uploaded to GitHub Actions and GitHub Releases on `release` events
- Uses `docker/build-push-action@v5`, `docker/setup-buildx-action@v3`, `actions/upload-artifact@v4`, `softprops/action-gh-release@v2`

Backend build (`.github/workflows/backend.yml`):
- Triggers: pushes to `main` touching `backend-rust/**`, PRs, `release` events
- Uses `dtolnay/rust-toolchain@stable` + `Swatinem/rust-cache@v2` for caching
- Produces `agentshell-backend` (Linux x86_64 binary); attached to GitHub Releases

## WebSocket Protocol

**Transport:** JSON messages over a single persistent WebSocket connection at `/ws`

**Auth:** `?token=<AUTH_TOKEN>` query parameter appended to WebSocket URL by `flutter/lib/data/services/websocket_service.dart`

**Keep-alive:** Flutter client sends `ping` every 30s; backend responds with `pong`. Pong timeout triggers reconnect. Auto-reconnect with 5s delay on disconnect.

**Message format:** All messages are JSON objects with a `"type"` field in `kebab-case`. Client→server messages are defined as `WebSocketMessage` enum in `backend-rust/src/types/mod.rs`. Server→client messages are defined as `ServerMessage` enum in the same file.

---

### Client → Server Message Types

**Terminal/Session:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `list-sessions` | — | List all tmux sessions |
| `attach-session` | `sessionName`, `cols`, `rows`, `windowIndex?` | Attach to tmux session, spawn PTY |
| `input` | `data` | Send raw PTY input |
| `input-via-tmux` | `sessionName?`, `windowIndex?`, `data` | Send input via tmux send-keys |
| `send-enter-key` | — | Send Enter key to PTY |
| `resize` | `cols`, `rows` | Resize PTY |
| `list-windows` | `sessionName` | List windows in a session |
| `select-window` | `sessionName`, `windowIndex` | Switch to a window |
| `create-session` | `name?` | Create new tmux session |
| `kill-session` | `sessionName` | Kill a session |
| `rename-session` | `sessionName`, `newName` | Rename session |
| `create-window` | `sessionName`, `windowName?` | Create new window |
| `kill-window` | `sessionName`, `windowIndex` | Kill a window |
| `rename-window` | `sessionName`, `windowIndex`, `newName` | Rename window |
| `get-session-cwd` | `sessionName` | Get working directory of session |

**Chat/AI:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `watch-chat-log` | `sessionName`, `windowIndex`, `limit?` | Subscribe to chat log updates |
| `watch-acp-chat-log` | `sessionId`, `windowIndex?`, `limit?` | Subscribe to ACP session chat |
| `load-more-chat-history` | `sessionName`, `windowIndex`, `offset`, `limit` | Paginate chat history |
| `unwatch-chat-log` | — | Unsubscribe from chat log |
| `clear-chat-log` | `sessionName`, `windowIndex` | Clear chat history |
| `send-chat-message` | `sessionName`, `windowIndex`, `message`, `notify?` | Send message to AI agent |
| `send-file-to-chat` | `sessionName`, `windowIndex`, `file`, `prompt?` | Attach file to chat |

**ACP (AI Agent Protocol):**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `select-backend` | `backend` | Switch AI backend |
| `acp-create-session` | `cwd` | Create new ACP session |
| `acp-resume-session` | `sessionId`, `cwd` | Resume existing ACP session |
| `acp-fork-session` | `sessionId`, `cwd` | Fork an ACP session |
| `acp-list-sessions` | — | List all ACP sessions |
| `acp-send-prompt` | `sessionId`, `message` | Send prompt to AI agent |
| `acp-cancel-prompt` | `sessionId` | Cancel in-progress prompt |
| `acp-set-model` | `sessionId`, `modelId` | Change AI model |
| `acp-set-mode` | `sessionId`, `modeId` | Change AI mode |
| `acp-respond-permission` | `requestId`, `optionId` | Respond to tool permission request |
| `acp-load-history` | `sessionId`, `offset?`, `limit?` | Load ACP chat history |
| `acp-clear-history` | `sessionId` | Clear ACP session history |
| `acp-delete-session` | `sessionId` | Delete an ACP session |
| `send-file-to-acp-chat` | `sessionId`, `file`, `prompt?`, `cwd?` | Send file to ACP chat |

**System/Cron/Dotfiles/Files:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `get-stats` | — | Get system CPU/memory/disk stats |
| `ping` | — | Keep-alive ping |
| `audio-control` | `action` (start/stop) | Start/stop audio streaming |
| `list-cron-jobs` | — | List all cron jobs |
| `create-cron-job` | `job` | Create cron job |
| `update-cron-job` | `id`, `job` | Update cron job |
| `delete-cron-job` | `id` | Delete cron job |
| `toggle-cron-job` | `id`, `enabled` | Enable/disable cron job |
| `test-cron-command` | `command` | Test a shell command |
| `list-dotfiles` | — | List managed dotfiles |
| `read-dotfile` | `path` | Read dotfile content |
| `write-dotfile` | `path`, `content` | Write dotfile |
| `get-dotfile-history` | `path` | Get versioned history |
| `restore-dotfile-version` | `path`, `timestamp` | Restore a dotfile version |
| `get-dotfile-templates` | — | List dotfile templates |
| `list-files` | `path` | Browse filesystem |
| `read-binary-file` | `path` | Read image/audio as binary |
| `delete-files` | `paths[]`, `recursive` | Delete files/directories |
| `rename-file` | `path`, `newName` | Rename a file |

---

### Server → Client Message Types

**Terminal/Session:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `sessions-list` | `sessions[]` | List of tmux sessions |
| `attached` | `sessionName` | Confirmed session attachment |
| `output` | `data` | Terminal output data (PTY stream) |
| `disconnected` | — | Session disconnected |
| `windows-list` | `sessionName`, `windows[]` | Windows in a session |
| `window-selected` | `success`, `windowIndex?`, `error?` | Window switch result |
| `session-created` | `success`, `sessionName?`, `error?` | Session creation result |
| `session-killed` | `success`, `error?` | Kill result |
| `session-renamed` | `success`, `error?` | Rename result |
| `window-created` | `success`, `error?` | Window creation result |
| `window-killed` | `success`, `error?` | Window kill result |
| `window-renamed` | `success`, `error?` | Window rename result |
| `terminal-history-start` | `sessionName`, `windowIndex`, `totalLines`, `chunkSize`, `generatedAt` | Begin terminal history bootstrap |
| `terminal-history-chunk` | `sessionName`, `windowIndex`, `seq`, `data`, `lineCount`, `isLast` | Chunked terminal history data |
| `terminal-history-end` | `sessionName`, `windowIndex`, `totalLines`, `totalChunks` | Terminal history complete |

**Chat/AI:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `chat-history` | `sessionName`, `windowIndex`, `messages[]`, `tool?`, `hasMore`, `totalCount` | Full chat history load |
| `chat-history-chunk` | `sessionName`, `windowIndex`, `messages[]`, `hasMore` | Paginated history |
| `chat-event` | `sessionName`, `windowIndex`, `message`, `source?` | New chat message event |
| `chat-log-error` | `error` | Chat log error |
| `chat-log-cleared` | `sessionName`, `windowIndex`, `success`, `error?` | Clear result |
| `chat-file-message` | `sessionName`, `windowIndex`, `message` | File attachment message |
| `chat-notification` | `sessionName`, `windowIndex`, `preview` | Chat notification preview |
| `notification-event` | `notification` | Push notification from server |

**ACP (AI Agent Protocol):**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `backend-selected` | `backend` | Backend switch confirmed |
| `acp-initialized` | `success`, `error?` | ACP client init result |
| `acp-session-created` | `sessionId`, `currentModelId?`, `availableModels?`, `currentModeId?`, `cwd?` | New ACP session |
| `acp-session-resumed` | `sessionId`, `currentModelId`, `availableModels[]`, `currentModeId` | Session resume result |
| `acp-session-forked` | `sessionId`, `currentModelId` | Session fork result |
| `acp-sessions-listed` | `sessions[]` | List of ACP sessions |
| `acp-message-chunk` | `sessionId`, `content`, `isThinking` | Streaming AI response chunk |
| `acp-tool-call` | `sessionId`, `toolCallId`, ... | AI tool invocation |

**System:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `stats` | `stats` (cpu, memory, disk, uptime, hostname, platform, arch) | System stats |
| `pong` | — | Keep-alive response |
| `error` | `message` | Generic error |
| `audio-status` | `streaming`, `error?` | Audio stream state |
| `audio-stream` | `data` (base64) | Audio data chunk |

**Cron:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `cron-jobs-list` | `jobs[]` | All cron jobs |
| `cron-job-created` | `job` | Created job |
| `cron-job-updated` | `job` | Updated job |
| `cron-job-deleted` | `id` | Deleted job ID |
| `cron-command-output` | `output`, `error?` | Test command result |

**Dotfiles:**
| Type | Key Fields | Description |
|------|-----------|-------------|
| `dotfiles-list` | `files[]` | Managed dotfiles |
| `dotfile-content` | `path`, `content`, `error?` | File contents |
| `dotfile-written` | `path`, `success`, `error?` | Write result |
| `dotfile-history` | `path`, `versions[]` | Version history |
| `dotfile-restored` | `path`, `success`, `error?` | Restore result |
| `dotfile-templates` | `templates[]` | Available templates |

---

## ACP / OpenCode AI Agent Bridge

- **What it is:** ACP (AI Command Protocol) is a JSON-RPC-over-stdio bridge to the `opencode` CLI tool
- **Implementation:** `backend-rust/src/acp/` (4 files: `mod.rs`, `client.rs`, `session.rs`, `messages.rs`)
- **How it works:** `AcpClient::start()` spawns `opencode acp` as a child process (`tokio::process::Command`), communicates via stdin/stdout using newline-delimited JSON-RPC 2.0 messages
- **Client lifecycle:** Single `AcpClient` instance stored in `AppState.acp_client` (Arc<RwLock<Option<AcpClient>>>); initialized lazily on first `acp-create-session` message
- **Session model:** ACP sessions have their own IDs (distinct from tmux sessions); support create, resume, fork, delete. Active session tracked in `active_session_id`
- **Streaming:** AI responses stream as `agent_message_chunk` / `agent_thought_chunk` events; tool calls stream as `tool_call` + `tool_call_update` events. Backend translates these to `AcpMessageChunk` / `AcpToolCall` ServerMessage variants
- **Permission flow:** When `opencode` requires tool permission, backend emits a `PermissionRequest` event; Flutter shows a dialog; user responds with `acp-respond-permission`
- **Model/Mode selection:** `acp-set-model` and `acp-set-mode` messages forwarded to `opencode`
- **File attachments:** `send-file-to-acp-chat` sends base64-encoded files through the ACP channel
- **Requirement:** `opencode` binary must be in `$PATH` on the backend host

## TMUX Integration

- **Implementation:** `backend-rust/src/tmux/mod.rs`, `backend-rust/src/monitor/mod.rs`
- **Mechanism:** Rust backend shells out to the `tmux` CLI via `tokio::process::Command`
- **PTY bridge:** When a client attaches to a session, `portable-pty 0.8` creates a PTY; tmux attaches inside it. Output streams to the Flutter terminal widget via `output` WebSocket messages
- **Session monitoring:** `TmuxMonitor` watches for tmux state changes and broadcasts `sessions-list` updates to all clients. Implemented in `backend-rust/src/monitor/mod.rs`
- **Direct tmux input:** REST endpoint `POST /api/tmux/input` allows sending text + Enter to a session by session name and window index (used by cron jobs and notifications)
- **Terminal history:** Backend reads tmux scrollback buffer (`tmux capture-pane`) and streams it in chunks (`terminal-history-start` / `terminal-history-chunk` / `terminal-history-end`)
- **CWD detection:** `tmux display-message -p -F "#{pane_current_path}"` used to get current working directory for ACP session initialization
- **Pane metadata:** History size and dimensions fetched via `tmux display-message` for buffer management
- **Requirement:** `tmux` must be installed on the backend host

## Cron Job System

- **Implementation:** `backend-rust/src/cron/mod.rs`, `backend-rust/src/cron_handler.rs`
- **Storage:** Persisted to system crontab (loaded on startup via `load_from_crontab()`)
- **Scheduling:** `cron ^0.12` parses standard 5-field cron expressions
- **REST API:** Full CRUD at `/api/cron/jobs` (list, create, get, update, delete, toggle, test)
- **WebSocket API:** `list-cron-jobs`, `create-cron-job`, `update-cron-job`, `delete-cron-job`, `toggle-cron-job`, `test-cron-command`
- **AI Cron Jobs:** `CronJob` model supports `prompt`, `llm_provider`, `llm_model`, `tmux_session`, `workdir` fields — enabling cron jobs that run AI prompts against a tmux session
- **Execution:** Commands run as shell processes; output optionally logged

## SQLite Usage

**Databases created at backend startup:**

`chat_events.db` (managed by `backend-rust/src/chat_event_store.rs`):
- Table `chat_events`: stores chat messages per session/window with JSON-serialized content
- Index on `(session_name, window_index, timestamp_millis)` for efficient paged queries
- Table `acp_deleted_sessions`: tracks deleted ACP sessions to filter history
- Schema initialized in `ChatEventStore::init()` via `execute_batch`

`notifications.db` (managed by `backend-rust/src/notification_store.rs`):
- Table `notifications`: notification records with title, body, source, timestamp, read status
- Table `notification_files`: file attachments keyed to notifications; stored on disk, tracked here
- Foreign key pattern (notification_id) — note: foreign key enforcement must be explicitly enabled in rusqlite

Both databases use `busy_timeout(Duration::from_secs(5))` and open a new connection per operation (no persistent connection pool).

## Speech-to-Text (Whisper)

- **Provider:** OpenAI Whisper API
- **Implementation:** `flutter/lib/data/services/whisper_service.dart`
- **Flow:** Flutter records audio with `record ^6.0.0` → saves as `.m4a` file → `WhisperService.transcribe()` uploads to `https://api.openai.com/v1/audio/transcriptions` via multipart form POST using `dio` → returns transcribed text → text injected into input field or sent as message
- **Model:** `whisper-1`, language hardcoded to `en`
- **Auth:** `OPENAI_API_KEY` from `SharedPreferences` or embedded `BuildConfig.defaultApiKey`
- **Platform:** Available on Android and iOS (uses `flutter_pty` PTY path); not available on web

## Audio Streaming (Server-side)

- **Implementation:** `backend-rust/src/audio/mod.rs`
- **Mechanism:** Backend spawns `ffmpeg` process to capture system audio; reads stdout and broadcasts as base64-encoded chunks via `audio-stream` WebSocket messages
- **State:** Global `AudioState` singleton (lazy_static) manages active ffmpeg process and connected clients
- **Control:** `audio-control` WebSocket message with `action: start|stop`
- **Requirement:** `ffmpeg` must be installed on the backend host
- **Flutter playback:** `just_audio ^0.9.36` plays decoded audio on the client

## Push Notifications (Android)

- **Implementation:** `flutter/lib/core/services/background_service_native.dart`, `flutter/lib/features/alerts/providers/alerts_provider.dart`
- **Framework:** `flutter_local_notifications ^20.1.0` (local; not FCM/APNs push)
- **Background service:** `flutter_background_service ^5.1.0` keeps WebSocket connection alive
- **Notification channels (Android):**
  - `agentshell_background` — background service persistence (low importance)
  - `agentshell_alerts` — AI task completion notifications (high importance)
  - `agentshell_chat` — chat message notifications
- **Trigger:** `notification-event` WebSocket messages from server (sourced from `notifications.db`)
- **Tap action:** Tapping an alert notification navigates to `AlertsScreen` via `navigatorKey`
- **REST API (server-side):** `GET /api/notifications`, `POST /api/notifications`, `POST /api/notifications/:id/read`, `GET /api/notifications/files/:id`
- **Permissions:** `permission_handler ^12.0.1` requests Android notification permissions at runtime

## Chat Log Watching

- **Implementation:** `backend-rust/src/chat_log/` (parsers: `claude_parser.rs`, `opencode_parser.rs`, `codex_parser.rs`), `backend-rust/src/chat_log/watcher.rs`
- **Mechanism:** Backend watches AI tool log files using `notify 6.1` (inotify on Linux); parses new entries and broadcasts as `chat-event` WebSocket messages
- **Supported tools:** Claude Code (`claude_parser.rs`), OpenCode (`opencode_parser.rs`), Codex (`codex_parser.rs`)
- **Persistence:** New events also written to `chat_events.db` via `ChatEventStore` for history replay
- **Session association:** Chat logs tied to `(session_name, window_index)` pairs

## Dotfiles Management

- **Implementation:** `backend-rust/src/dotfiles/mod.rs`
- **Features:** Read, write, and version dotfiles (bash history, `.bashrc`, `.zshrc`, etc.); versioned backups with timestamp-based restore
- **Templates:** Pre-defined dotfile templates accessible via `get-dotfile-templates`
- **WebSocket API:** `list-dotfiles`, `read-dotfile`, `write-dotfile`, `get-dotfile-history`, `restore-dotfile-version`, `get-dotfile-templates`

## Environment Configuration

**Required at runtime (backend):**
- `AUTH_TOKEN` — mandatory; server exits at startup if not set

**Optional backend env vars:**
- `ALLOWED_ORIGIN` — additional CORS origin (default: localhost only)
- `RUST_LOG` — log level filter (default: `agentshell_backend=debug,tower_http=info`)

**Embedded at Flutter build time:**
- `SERVER_LIST` — default host list
- `OPENAI_API_KEY` — default API key
- `AUTH_TOKEN` — default auth token for APK builds
- `SHOW_THINKING` — show AI thinking blocks
- `SHOW_TOOL_CALLS` — show AI tool calls

**Secrets location:**
- `.env` file at project root (gitignored)
- `BuildConfig.authToken` / `BuildConfig.defaultApiKey` embedded in compiled Dart code (extracted from build args at Docker build time)

## Webhooks & Callbacks

**Incoming (server receives):**
- None from external services — no webhooks configured

**Outgoing (server calls):**
- OpenAI Whisper API: called from Flutter client directly (not via backend)
- Cloudflare API: called from `scripts/deploy-web.sh` during deployment only
- `opencode` subprocess communication: JSON-RPC over stdin/stdout (not HTTP)

---

*Integration audit: 2026-03-25*
