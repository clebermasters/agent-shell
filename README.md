# AgentShell

<p align="center">
  <img src="logo-square.png" alt="AgentShell Logo" width="200">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rust-DEA584?style=flat&logo=rust&logoColor=white" alt="Rust">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/OpenCode-FF6B6B?style=flat" alt="OpenCode ACP">
</p>

AgentShell is a high-performance terminal and AI session platform for Android.
The **maintained and feature-complete client is the native Android app** (`android-native/`), backed by a Rust server.

- Native Android: Kotlin + Jetpack Compose (`android-native/`)
- Backend: Rust + Axum + WebSocket (`backend-rust/`)
- Flutter client: kept for history only, **deprecated** (`flutter/`)

## What’s new since the original docs

- Direct Chat support for both:
  - **OpenCode ACP** (shared ACP transport)
  - **Codex app-server** (provider-aware integration)
- Enhanced session UX for favorites and tags:
  - Favorites CRUD
  - Tag CRUD and session-tag assignment
  - Session filtering by tags and grouping in session lists
- Better launch controls:
  - Direct sessions list can be grouped and managed by provider/context
  - Direct session launch targets include dedicated paths for ACP, TMUX, and Codex
- Rich usage visibility:
  - Claude usage bars (5h and 7d) and Codex usage in chat sessions
- File attachment support in chat for both tmux and direct sessions
- ACP history persistence and resume/load flow improvements
- Expanded websocket command coverage (`acp-*`, file browser, git, notifications, cron, dotfiles)

## Core Feature Set

### Terminal and Sessions (tmux)
- List/create/kill/rename tmux sessions
- List/create/select/kill windows
- Real-time terminal I/O over WebSocket
- Terminal streaming optimizations for large output
- Cursor-safe line buffering and reconnection handling

### AI Sessions
- **Claude Code**: run Claude inside tmux and control it like any terminal session
- **OpenCode ACP**: direct chat sessions with permission-based tool calls
- **Codex direct mode**: app-server based sessions via the same ACP transport shape
- Kiro session detection and parsing in tmux chat logs
- Context-window updates and tool call timeline tracking

### Session Management
- tmux sessions + favorite sessions in local app store
- Provider-aware direct sessions:
  - OpenCode thread IDs are prefixed as `opencode:<id>`
  - Codex thread IDs are prefixed as `codex:<id>`
- Delete, fork, resume, and list direct sessions
- Session-level tags with color and filter support

### Mobile App Features
- Multi-panel UI, split terminal/chat workflows, quick pane switching
- Real-time system usage (`CPU`, `memory`, `disk`) and process list
- System alerts and notifications with persistent storage
- Audio transcription and playback pipeline
- File browser with copy/cut/paste/delete/move/rename and search/sort
- Cron job manager with schedule + test execution
- Dotfile editor and history
- In-chat file attachments for AI workflows

## Architecture

```text
Android (WebSocket + HTTPS)        Backend (Rust + Axum)
----------------------------      -----------------------------
tmux session control --------------> tmux integration + chat log watcher
ACP/Codex direct chat --------------> chat/event backplane + providers
notifications / file browser --------> filesystem + notification services
system stats -----------------------> OS process + disk monitoring
favorites/tags ---------------------> SQLite persistence
```

Backend flow is split across:
- `backend-rust/src/websocket/*` for websocket command handlers
- `backend-rust/src/*` for terminal and system subsystems
- `backend-rust/src/codex_app/` for Codex app-server orchestration
- `backend-rust/src/favorite_store.rs` and `backend-rust/src/tag_store.rs` for favorites/tags

For implementation details on Codex routing and transport reuse, use:
- [CODEX_APP_SERVER_INTEGRATION.md](CODEX_APP_SERVER_INTEGRATION.md)

## AI Integration Details

### 1) Claude Code (tmux mode)
Run Claude Code manually inside a tmux session and attach from the app:
```bash
tmux new-session -s claude -d "claude"
```
From the app, attach to `claude` as a normal tmux session and interact in real time.

### 2) OpenCode (ACP mode)
Uses shared ACP-style direct chat commands:
- `select-backend acp`
- `acp-create-session`
- `acp-send-prompt`
- `acp-permission-request` -> `acp-respond-permission`
- `watch-acp-chat-log`, `acp-load-history`, `acp-clear-history`

### 3) Codex (app-server mode)
Also uses the same direct-chat websocket message family (`acp-*`) with provider-specific routing on backend.
- Create/list/resume calls include `backend=codex` where required.
- Session IDs returned are prefixed `codex:<thread_id>`.
- Resume/load flow uses Codex thread APIs under the hood (`thread/start`, `thread/resume`, `thread/read`).

If you want the full flow and edge cases, read:
- [CODEX_APP_SERVER_INTEGRATION.md](CODEX_APP_SERVER_INTEGRATION.md)

## Prerequisites

- Linux server/macOS for backend
- Rust (latest stable) and Cargo
- `tmux` installed
- `docker` + Android SDK platform-tools (for Android build/install)
- Optional: `curl`, `git`, `openssl` (for HTTPS and diagnostics)

## Backend Setup

### Required env (runtime)
`AUTH_TOKEN` is required at startup. The server exits if it is missing or empty.

| Variable | Required | Meaning |
|----------|----------|---------|
| `AUTH_TOKEN` | required | Shared token for all HTTP and WebSocket requests |
| `AGENTSHELL_HTTP_PORT` | optional | HTTP listen port (default: `4010`) |
| `AGENTSHELL_HTTPS_PORT` | optional | HTTPS listen port (default: `4443`) |
| `ALLOWED_ORIGIN` | optional | Extra CORS origin (default includes localhost variants) |
| `OPENAI_API_KEY` | optional | Used by backend features that call OpenAI-capable tooling |
| `RUST_LOG` | optional | Tracing level |

Example:
```bash
cd backend-rust
export AUTH_TOKEN="your-strong-secret"
export AGENTSHELL_HTTP_PORT=4010
cargo run --release
```

### Install service
`install.sh` builds and installs the release binary into `/opt/agentshell` and creates a systemd service.
```bash
sudo ./install.sh
```

## Android Native Build

All Android builds now come from `android-native/build.sh`.

```bash
cd android-native
./build.sh release               # release APK
./build.sh debug                 # debug APK
./build.sh release --install      # build + install over USB
./build.sh release --install --wireless # build + wireless install
./build.sh release --install --force # force install on work-profile devices
```

Build config is populated from `.env` at repo root (`SERVER_LIST`, `AUTH_TOKEN`, `SHOW_THINKING`, `SHOW_TOOL_CALLS`, `OPENAI_API_KEY`) and injected into Android `BuildConfig`.

If you already have an APK and want install only:
```bash
./scripts/install-android.sh agentshell-native-release.apk
./scripts/install-android.sh agentshell-native-release.apk --wireless --launch
```

## Endpoints

### WebSocket
- `ws://<host>:<port>/ws?token=<AUTH_TOKEN>`
- Header auth alternative: `X-Auth-Token: <AUTH_TOKEN>`
- Also accepts direct secure WebSocket at HTTPS port when configured.

### REST endpoints (all protected by auth middleware)
- `GET /api/clients`
- `GET /api/chat/files/:id`
- `GET /api/notifications`
- `POST /api/notifications`
- `POST /api/tmux/input`
- `GET|POST|PUT|DELETE /api/cron/jobs` and `/api/cron/jobs/:id` variants
- `GET /api/notifications/files/:id`

## WebSocket Message Surface

The full source-of-truth definitions are in:
- `backend-rust/src/types/mod.rs`
- `backend-rust/src/websocket/types.rs` (internal helpers)
- `backend-rust/src/websocket/*.rs` (handlers)

Current high-level message families:
- Terminal sessions:
  - `list-sessions`, `create-session`, `attach-session`, `kill-session`, `list-windows`, `select-window`, `input`, `resize`
- Chat logs:
  - `watch-chat-log`, `watch-acp-chat-log`, `load-more-chat-history`, `clear-chat-log`
- ACP/direct sessions:
  - `select-backend`, `acp-create-session`, `acp-resume-session`, `acp-fork-session`, `acp-list-sessions`, `acp-send-prompt`, `acp-load-history`, `acp-clear-history`, `acp-delete-session`
- Attachments:
  - `send-file-to-chat`, `send-file-to-acp-chat`
- Favorites/tags:
  - `get-favorites`, `add-favorite`, `update-favorite`, `delete-favorite`
  - `get-tags`, `add-tag`, `assign-tag-to-session`, `remove-tag-from-session`
- System / resources:
  - `get-stats`, `get-claude-usage`, `get-codex-usage`
- File operations and git workflows:
  - `list-files`, `read-binary-file`, `delete-files`, `rename-file`, `copy-files`, `move-files`
  - `git-status`, `git-diff`, `git-log`, `git-branches`, `git-commit`, `git-push`, etc.
- Notifications:
  - `get/create/mark/read/delete` notification commands are available through HTTP routes as shown above.

On the server side, common response types include:
- `sessions-list`, `chat-event`, `chat-history`, `acp-message-chunk`, `acp-tool-call`, `acp-permission-request`, `acp-history-loaded`, `acp-prompt-done`, `acp-error`, `favorites-list`, `tags-list`, `context-window-update`, `stats`, `claude-usage`, `codex-usage`, and more.

## Android App Notes

- Android sends `X-Auth-Token` and uses a server-provided token in HTTP/WebSocket flows.
- The app can host multiple panels, watch multiple sessions, and restore open chats.
- Direct session creation is provider-aware (`codex` or `opencode`) and opens into the shared ACP chat renderer.
- Session tiles now support tags and provider/path grouping so long lists stay manageable.
- File upload supports image/audio/doc attachments in chat.

## Legacy Flutter Client

- The Flutter app is maintained only for historical purposes.
- Use native Android for active development and feature updates:
  - [flutter/README.md](flutter/README.md)

## Debug, Logs and Verification

- Backend logs: check `backend.log` in project root or systemd `journalctl -u agentshell`.
- Start backend with debug logs:
```bash
cd backend-rust
RUST_LOG=debug AUTH_TOKEN=... cargo run --release
```

Useful verification scripts:
- `scripts/test_codex_direct_session.py` — end-to-end Codex direct session create/list/resume/load check.
- `scripts/test_enter_key.py` — terminal key event and input checks.
- `scripts/test-ws.sh`, `scripts/test-ws.py` — WebSocket smoke checks.

## Security

- The backend always enforces `AUTH_TOKEN`:
  - Missing token -> `401`
  - Invalid token -> `401`
- Constant-time token comparison is used to reduce timing leakage.
- WebSocket auth methods:
  - `?token=<AUTH_TOKEN>` query parameter
  - `X-Auth-Token: <AUTH_TOKEN>` header
- HTTPS is optional by default; when `certs/cert.pem` and `certs/key.pem` exist, port from `AGENTSHELL_HTTPS_PORT` is also available.

## Troubleshooting

- Build failures:
  - Ensure Docker is available for Android builds.
  - Ensure `codex` and `opencode` binaries are on `PATH` for their respective direct backends.
- Connection failures:
  - Confirm backend URL and port, and always include a valid token.
  - If using CORS in web contexts, set `ALLOWED_ORIGIN`.
- Authentication errors:
  - Confirm `AUTH_TOKEN` is set before backend start; server exits early if missing.
- App install issues:
  - Use `scripts/install-android.sh` with `--wireless` for ADB over Wi-Fi.

## Contributing

1. Fork the repository
2. Create a topic branch
3. Run tests/build checks where applicable
4. Open a PR with concise notes about protocol and behavior changes

## References

- [CODEX_APP_SERVER_INTEGRATION.md](CODEX_APP_SERVER_INTEGRATION.md)
- [backend-rust/src/types/mod.rs](backend-rust/src/types/mod.rs)
- [android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt](android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt)
- [backend-rust/src/websocket/acp_cmds.rs](backend-rust/src/websocket/acp_cmds.rs)
- [backend-rust/src/codex_app/client.rs](backend-rust/src/codex_app/client.rs)

## License

This project is licensed under the MIT License. See `LICENSE`.
