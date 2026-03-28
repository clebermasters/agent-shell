# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

**AgentShell** — a native Android app (Kotlin/Jetpack Compose) + Rust backend. The backend manages TMUX sessions, terminal I/O, chat history (SQLite), cron jobs, dotfiles, and bridges to AI agents (ACP/OpenCode). The Android app provides a terminal UI, chat interface, session manager, and real-time system monitoring over WebSocket. A legacy Flutter app exists in `flutter/` but the primary client is now `android-native/`.

## Build Commands

```bash
# Backend (Rust)
cd backend-rust
cargo build --release        # → target/release/agentshell-backend
cargo run --release          # Run locally (port 4010)
cargo test
cargo bench --bench performance

# Android Native (requires JAVA_HOME set to JDK 17)
cd android-native
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleRelease
# Docker-based build (reads .env for SERVER_LIST, AUTH_TOKEN, etc.)
./build.sh debug             # Debug APK
./build.sh release           # Release APK
./build.sh release --install # Build + install via USB

# Install APK directly (debug suffix: com.agentshell.debug)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Deploy backend
sudo systemctl stop agentshell
sudo cp backend-rust/target/release/agentshell-backend /opt/agentshell/backend/
sudo systemctl start agentshell

# Service management
./start.sh start|stop|restart|logs|status

# Deploy web (Flutter legacy)
./scripts/deploy-web.sh          # Deploy existing zip to S3 + purge Cloudflare
./scripts/deploy-web.sh --build  # Rebuild first
```

## Architecture

### Android Native App (`android-native/`)

**Stack:** Kotlin, Jetpack Compose (Material3), Hilt DI, Room + DataStore, OkHttp WebSocket.

**App ID:** `com.agentshell` (debug: `com.agentshell.debug`). Min SDK 26, Target SDK 35.

**Navigation:** Single-activity (`MainActivity`) with `NavHost` in `Navigation.kt`. Routes defined in `Routes` object (Home, Terminal, Chat, Settings, Alerts, FileBrowser, SplitScreen, etc.).

**Key patterns:**
- ViewModels with `@HiltViewModel` expose `StateFlow<UiState>` collected via `collectAsStateWithLifecycle()`
- `WebSocketService` is a `@Singleton` — one OkHttp WebSocket connection shared across all features
- Extension functions in `WebSocketCommands.kt` provide typed send methods (`requestSystemStats()`, `requestClaudeUsage()`, `sendFileToChat()`, etc.)
- `SystemRepository` owns system stats and Claude usage state; `ChatRepository` owns chat message routing
- `HomeViewModel` routes all incoming WebSocket messages by `type` field to the appropriate repository/handler

**Build-time config:** `BuildConfig.kt` is auto-generated from `.env` by a Gradle task (`generateBuildConfig`). Contains `DEFAULT_SERVER_LIST`, `AUTH_TOKEN`, `DEFAULT_API_KEY`, `DEFAULT_SHOW_THINKING`, `DEFAULT_SHOW_TOOL_CALLS`.

**Feature layout:** `feature/` → `{feature}/` → `{Feature}Screen.kt` + `{Feature}ViewModel.kt`. Data layer: `data/model/`, `data/remote/`, `data/repository/`, `data/local/`.

### Rust Backend (`backend-rust/`)

**Stack:** Axum 0.7, Tokio, portable-pty, rusqlite, reqwest, sysinfo.

**Entry point:** `src/main.rs` sets up Axum routes and Tokio runtime on port 4010.

**WebSocket routing:** `src/websocket/mod.rs` dispatches by `WebSocketMessage` enum variant (serde `tag = "type"`, `rename_all = "kebab-case"`) to sub-modules:
- `terminal_cmds.rs` — PTY attach/input/resize, `tmux send-keys`
- `session_cmds.rs` — TMUX session/window CRUD
- `chat_cmds.rs` — Chat history, file uploads, message forwarding to tmux
- `acp_cmds.rs` — ACP (Agent Control Protocol) for OpenCode AI sessions
- `system_cmds.rs` — System stats, ping/pong, Claude usage API
- `cron_cmds.rs` — Cron job management
- `dotfiles_cmds.rs` — Dotfile versioning
- `file_cmds.rs` — File browser operations

**Chat log system** (`src/chat_log/`): Auto-detects AI tools running in tmux sessions by scanning `/proc` for process names:
- `claude` → parses JSONL from `~/.claude/projects/`
- `codex` → parses NDJSON logs
- `opencode` → parses SQLite database
- Detection result sent as `tool` field in `chat-history` messages

**Per-client state** (`WsState`): client_id, current PTY/session/window, message channel, storage refs. **Global state** (`AppState`): broadcast channel, client manager, shared storage instances.

### WebSocket Protocol

All messages are JSON with a `type` field. Client → server: `list-sessions`, `attach-session`, `input`, `input-via-tmux`, `resize`, `get-stats`, `get-claude-usage`, `send-file-to-chat`, `acp-create-session`, etc. Server → client: `sessions-list`, `output`, `stats`, `claude-usage`, `chat-history`, `chat-event`, etc.

**Auth:** Token passed as `?token=` query param on WebSocket upgrade. Backend validates with constant-time comparison. Token set via `AUTH_TOKEN` env var in systemd service.

### Key Subsystems

- **tmux integration** (`src/tmux/`): Wraps tmux CLI. `send-keys -l` for text input (with retry on window failure + 80ms delay before Enter). File paths wrapped in backticks to prevent Claude Code's image auto-detection from consuming them.
- **ACP client** (`src/acp/`): Spawns `opencode acp` subprocess, communicates via JSON-RPC over stdin/stdout.
- **Chat file storage** (`src/chat_file_storage.rs`): Saves uploaded files to UUID-named paths, supports both session directory placement and chat_files display storage.
- **Monitor** (`src/monitor/`): Watches for TMUX state changes and broadcasts session list updates.

## Environment (.env)

```
SERVER_LIST=host:port,Label|host:port,Label   # Embedded in APK at build time
AUTH_TOKEN=your-secret-token                  # WebSocket auth
OPENAI_API_KEY=sk-...
SHOW_THINKING=false
SHOW_TOOL_CALLS=false
S3_WEB_BUCKET=s3://your-bucket
CF_API_KEY=...
CF_EMAIL=...
CF_ZONE_ID=...
CF_DOMAIN=your-domain.com
```

`.env` is gitignored. `SERVER_LIST` format: pipe-separated `address:port,Name` entries.

## CI/CD

- `.github/workflows/flutter.yml` — Builds Flutter APK and web in parallel, uploads artifacts.
- `.github/workflows/backend.yml` — `cargo build --release`, uploads Linux x86_64 binary.

## Docker Build Flow

Base images (heavy, cached on GHCR): `agentshell-flutter-base`, `agentshell-android-native-base`. Build images extend base, inject `.env` values into `BuildConfig.kt`/`build_config.dart`, compile. `BUILD_TIMESTAMP` changes every build to bust service worker cache.
