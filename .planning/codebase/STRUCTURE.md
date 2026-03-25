---
focus: arch
generated: 2026-03-25
---

# Codebase Structure

**Analysis Date:** 2026-03-25

## Directory Layout

```
webmux/                             # Project root
├── backend-rust/                   # Rust HTTP + WebSocket server
│   ├── src/
│   │   ├── main.rs                 # Server entry, Axum router, AppState
│   │   ├── auth.rs                 # AUTH_TOKEN middleware
│   │   ├── types/mod.rs            # WebSocketMessage + ServerMessage enums, shared types
│   │   ├── websocket/              # WS dispatch + per-domain command handlers
│   │   │   ├── mod.rs              # handle_socket, handle_message dispatch
│   │   │   ├── types.rs            # WsState, BroadcastMessage, PtySession
│   │   │   ├── client_manager.rs   # ClientManager (broadcast hub)
│   │   │   ├── acp_cmds.rs         # ACP session/chat commands (1476 lines)
│   │   │   ├── chat_cmds.rs        # Chat log watch/history commands (1452 lines)
│   │   │   ├── terminal_cmds.rs    # PTY attach/input/resize (706 lines)
│   │   │   ├── file_cmds.rs        # File browser commands (400 lines)
│   │   │   ├── session_cmds.rs     # Tmux session/window CRUD (360 lines)
│   │   │   ├── dotfiles_cmds.rs    # Dotfile CRUD + history (285 lines)
│   │   │   ├── cron_cmds.rs        # Cron job WS commands (228 lines)
│   │   │   └── system_cmds.rs      # Ping, stats, audio (219 lines)
│   │   ├── tmux/mod.rs             # Tmux CLI wrappers (list/create/kill/attach)
│   │   ├── acp/                    # ACP (OpenCode AI) client
│   │   │   ├── client.rs           # AcpClient, child process mgmt, JSON-RPC bridge
│   │   │   ├── session.rs          # SessionInfo, ModelInfo types
│   │   │   └── messages.rs         # JSON-RPC message types
│   │   ├── chat_event_store.rs     # SQLite chat history persistence
│   │   ├── chat_file_storage.rs    # Chat file attachment storage
│   │   ├── chat_clear_store.rs     # Tracks cleared chat sessions
│   │   ├── chat_log/               # AI chat log file parsers + watcher
│   │   │   ├── mod.rs
│   │   │   ├── claude_parser.rs
│   │   │   ├── codex_parser.rs
│   │   │   ├── opencode_parser.rs
│   │   │   └── watcher.rs
│   │   ├── cron/mod.rs             # Cron job scheduler (CRON_MANAGER global)
│   │   ├── cron_handler.rs         # REST handlers for /api/cron/jobs
│   │   ├── dotfiles/               # Dotfile manager with versioning
│   │   │   ├── mod.rs
│   │   │   └── templates/          # Bundled dotfile templates
│   │   ├── monitor/mod.rs          # TmuxMonitor: polls tmux every 250ms
│   │   ├── notification.rs         # Notification model
│   │   ├── notification_store.rs   # SQLite notification persistence
│   │   ├── notification_handler.rs # REST handlers for /api/notifications
│   │   ├── audio/mod.rs            # Audio streaming
│   │   └── terminal_buffer.rs      # Terminal output buffer
│   ├── tests/                      # Integration tests
│   ├── benches/                    # Performance benchmarks
│   ├── Cargo.toml
│   └── chat_events.db              # SQLite DB (runtime artifact, gitignored)
│
├── flutter/                        # Flutter app (Android + Web)
│   ├── lib/
│   │   ├── main.dart               # App entry, ProviderScope, routing
│   │   ├── core/
│   │   │   ├── config/
│   │   │   │   ├── app_config.dart      # AppConfig constants (keys, appName, etc.)
│   │   │   │   └── build_config.dart    # Build-time injected config (generated)
│   │   │   ├── providers.dart           # sharedPreferencesProvider
│   │   │   ├── theme/app_theme.dart     # AppTheme (light + dark)
│   │   │   ├── widgets/                 # Shared UI widgets
│   │   │   │   └── connection_status_banner.dart
│   │   │   ├── utils/                   # Utility helpers
│   │   │   └── services/
│   │   │       └── background_service.dart
│   │   ├── data/
│   │   │   ├── models/             # Immutable data models with fromJson/toJson
│   │   │   │   ├── models.dart          # Barrel export
│   │   │   │   ├── host.dart            # Host (wsUrl/httpUrl logic)
│   │   │   │   ├── tmux_session.dart
│   │   │   │   ├── acp_session.dart
│   │   │   │   ├── chat_message.dart
│   │   │   │   ├── cron_job.dart
│   │   │   │   ├── dotfile.dart
│   │   │   │   ├── file_entry.dart
│   │   │   │   ├── notification.dart
│   │   │   │   ├── system_stats.dart
│   │   │   │   └── connection_status.dart
│   │   │   ├── services/           # Service classes
│   │   │   │   ├── services.dart        # Barrel export
│   │   │   │   ├── websocket_service.dart  # WS client, auto-reconnect, send methods
│   │   │   │   ├── terminal_service.dart   # PTY I/O bridge
│   │   │   │   ├── audio_service.dart
│   │   │   │   ├── whisper_service.dart    # Speech-to-text
│   │   │   │   └── pty_stub.dart           # Web stub for PTY
│   │   │   └── repositories/       # Thin repository wrappers
│   │   │       ├── repositories.dart
│   │   │       ├── cron_repository.dart
│   │   │       ├── dotfiles_repository.dart
│   │   │       ├── host_repository.dart
│   │   │       └── session_repository.dart
│   │   └── features/               # Feature modules (vertical slices)
│   │       ├── home/               # Main shell: tab navigation, command palette
│   │       │   ├── screens/home_screen.dart
│   │       │   └── providers/command_palette_provider.dart
│   │       ├── sessions/           # Tmux + ACP session management
│   │       │   ├── providers/sessions_provider.dart  # sharedWebSocketServiceProvider here
│   │       │   └── screens/sessions_screen.dart
│   │       ├── terminal/           # Terminal emulator (xterm)
│   │       │   ├── providers/terminal_provider.dart
│   │       │   ├── screens/terminal_screen.dart
│   │       │   └── widgets/
│   │       ├── chat/               # AI chat interface
│   │       │   ├── providers/chat_provider.dart
│   │       │   ├── screens/
│   │       │   └── widgets/
│   │       ├── alerts/             # Notifications / alerts feed
│   │       │   ├── providers/alerts_provider.dart
│   │       │   ├── screens/
│   │       │   │   ├── alerts_screen.dart
│   │       │   │   ├── alert_html_screen.dart
│   │       │   │   ├── alert_image_viewer_screen.dart
│   │       │   │   └── alert_markdown_screen.dart
│   │       │   └── widgets/
│   │       ├── cron/               # Cron job manager
│   │       │   ├── providers/cron_provider.dart
│   │       │   └── screens/cron_screen.dart
│   │       ├── dotfiles/           # Dotfile editor
│   │       │   ├── providers/dotfiles_provider.dart
│   │       │   ├── screens/dotfiles_screen.dart
│   │       │   └── widgets/
│   │       ├── system/             # System stats + alert banner
│   │       │   ├── providers/system_provider.dart
│   │       │   ├── screens/system_screen.dart
│   │       │   └── widgets/alert_banner.dart
│   │       ├── file_browser/       # Remote file browser
│   │       │   ├── providers/file_browser_provider.dart
│   │       │   └── screens/file_browser_screen.dart
│   │       ├── hosts/              # Host (backend server) management
│   │       │   ├── providers/hosts_provider.dart
│   │       │   └── screens/
│   │       ├── auth/               # Web login screen
│   │       │   └── screens/login_screen.dart
│   │       ├── settings/           # App settings
│   │       │   └── screens/settings_screen.dart
│   │       └── debug/              # Debug/diagnostic screen
│   │           └── screens/debug_screen.dart
│   ├── android/                    # Android-specific build config
│   ├── build/                      # Build output (gitignored)
│   ├── fonts/                      # Custom fonts
│   ├── pubspec.yaml                # Flutter dependencies
│   └── build.sh                    # Docker-based build script
│
├── docker/
│   ├── flutter-base/Dockerfile     # Heavy base: Ubuntu + Java + Android SDK + Flutter
│   ├── flutter/Dockerfile          # Build image: extends base, runs flutter build
│   └── android-base/               # Android-specific base image
│
├── scripts/
│   ├── deploy-web.sh               # S3 upload + Cloudflare cache purge
│   └── (various test/debug scripts)
│
├── .github/workflows/
│   ├── flutter.yml                 # CI: APK + web builds, uploads artifacts
│   └── backend.yml                 # CI: cargo build --release
│
├── certs/                          # TLS certs for HTTPS (optional, not committed)
├── dist/                           # Flutter web build output (served by backend)
├── docs/                           # Architecture docs, plans, specs
├── start.sh                        # Service management (start/stop/restart/logs)
├── install.sh
├── CLAUDE.md                       # Project instructions for Claude Code
└── .env                            # Runtime secrets (gitignored)
```

## Directory Purposes

**`backend-rust/src/websocket/`:**
- Purpose: Core WebSocket subsystem — the message dispatch hub
- Contains: `mod.rs` (connection lifecycle + `handle_message` dispatch), one `*_cmds.rs` per domain, `client_manager.rs` (broadcast), `types.rs` (per-client state), `types.rs` (shared types)
- Key files: `mod.rs` (321 lines), `acp_cmds.rs` (1476 lines), `chat_cmds.rs` (1452 lines)

**`backend-rust/src/types/`:**
- Purpose: Canonical shared type definitions for the entire backend
- Key types: `WebSocketMessage` (all client→server messages as a tagged enum), `ServerMessage` (all server→client messages), `TmuxSession`, `TmuxWindow`, `CronJob`, `FileEntry`, `FileAttachment`, `SystemStats`
- Used by: All backend modules via `use crate::types::*`

**`flutter/lib/features/`:**
- Purpose: Feature-vertical organization — each subdirectory owns its screens, providers, and widgets
- Pattern: Every feature follows `providers/` + `screens/` + `widgets/` (widgets optional)
- 13 features: `home`, `sessions`, `terminal`, `chat`, `alerts`, `cron`, `dotfiles`, `system`, `file_browser`, `hosts`, `auth`, `settings`, `debug`

**`flutter/lib/data/`:**
- Purpose: Data layer — models, service implementations, repository wrappers
- `models/` — plain Dart classes only; no business logic
- `services/` — stateful service classes with lifecycles (WebSocketService, TerminalService)
- `repositories/` — thin interfaces wrapping service calls; some features bypass and call services directly

**`flutter/lib/core/`:**
- Purpose: Application-wide utilities shared across all features
- `config/app_config.dart` — constants for SharedPreferences keys, app name
- `config/build_config.dart` — compile-time injected values (generated by Dockerfile; default empty for dev)
- `providers.dart` — the root `sharedPreferencesProvider` that all providers depend on

## Key File Locations

**Entry Points:**
- `flutter/lib/main.dart` — Flutter app entry; initializes ProviderScope
- `backend-rust/src/main.rs` — Rust server entry; binds HTTP/HTTPS, starts monitor and cron

**WebSocket Connection Hub:**
- `flutter/lib/features/sessions/providers/sessions_provider.dart` — defines `sharedWebSocketServiceProvider` (singleton WS connection)
- `flutter/lib/data/services/websocket_service.dart` — `WebSocketService` class (all WS logic)
- `backend-rust/src/websocket/mod.rs` — `ws_handler` + `handle_message` dispatch
- `backend-rust/src/websocket/client_manager.rs` — `ClientManager` (fan-out broadcast to all clients)

**Message Type Contracts:**
- `backend-rust/src/types/mod.rs` — canonical `WebSocketMessage` and `ServerMessage` enums (source of truth for the protocol)

**Per-Connection State:**
- `backend-rust/src/websocket/types.rs` — `WsState` struct (PTY, session, chat handles per client)

**Global Server State:**
- `backend-rust/src/main.rs` — `AppState` struct (broadcast channel, client manager, stores, ACP client)

**Authentication:**
- `backend-rust/src/auth.rs` — token validation middleware
- `flutter/lib/core/config/build_config.dart` — build-time `authToken` constant

**Persistence:**
- `backend-rust/src/chat_event_store.rs` — SQLite chat events (`chat_events.db`)
- `backend-rust/src/notification_store.rs` — SQLite notifications
- `backend-rust/src/chat_clear_store.rs` — cleared session tracking

**Build Configuration:**
- `flutter/build.sh` — Docker-based build with env injection
- `docker/flutter/Dockerfile` — generates `build_config.dart` from `.env` at build time
- `.env` — source of `SERVER_LIST`, `OPENAI_API_KEY`, `AUTH_TOKEN`, etc. (gitignored)

## Naming Conventions

**Files (Flutter):**
- `snake_case.dart` for all Dart source files
- Screens: `<feature>_screen.dart` (e.g., `terminal_screen.dart`)
- Providers: `<feature>_provider.dart` (e.g., `chat_provider.dart`)
- Models: `<model_name>.dart` (e.g., `tmux_session.dart`)
- Barrel exports: `models.dart`, `services.dart`, `repositories.dart`

**Files (Rust):**
- `snake_case.rs` for all Rust source files
- Command handlers: `<domain>_cmds.rs` (e.g., `acp_cmds.rs`, `session_cmds.rs`)
- Domain modules: `<domain>/mod.rs`

**Directories (Flutter):**
- `snake_case` throughout
- Feature directories named after the domain noun: `terminal/`, `chat/`, `sessions/`

**Classes (Flutter):**
- State classes: `<Feature>State` (e.g., `TerminalState`, `SessionsState`)
- Notifiers: `<Feature>Notifier extends StateNotifier<<Feature>State>`
- Providers named: `<feature>Provider` (e.g., `sessionsProvider`, `terminalProvider`)

## Where to Add New Code

**New Feature (full vertical slice):**
- Create directory: `flutter/lib/features/<feature_name>/`
- Add `providers/<feature_name>_provider.dart` with `XxxState` + `XxxNotifier` + `xxxProvider`
- Add `screens/<feature_name>_screen.dart` extending `ConsumerStatefulWidget`
- Add `widgets/` if the feature has reusable sub-components
- Register screen in `flutter/lib/features/home/screens/home_screen.dart` navigation tabs if needed

**New WebSocket Message Type:**
- Add variant to `WebSocketMessage` enum in `backend-rust/src/types/mod.rs` (client→server)
- Add variant to `ServerMessage` enum in `backend-rust/src/types/mod.rs` (server→client)
- Add handler in the appropriate `backend-rust/src/websocket/<domain>_cmds.rs`
- Add match arm in `handle_message` in `backend-rust/src/websocket/mod.rs`
- Add send method to `flutter/lib/data/services/websocket_service.dart`
- Handle the response type in the relevant Flutter provider's `messages.listen` callback

**New REST Endpoint:**
- Add handler function in `backend-rust/src/<domain>_handler.rs` (Axum extractor style)
- Register route in `backend-rust/src/main.rs` under `protected` router
- Call the endpoint from Flutter via `host.httpUrl` (using `dart:http` or similar)

**New Model (Flutter):**
- Add `flutter/lib/data/models/<model_name>.dart` with `fromJson`/`toJson` and `copyWith`
- Export from `flutter/lib/data/models/models.dart`

**New Shared Widget:**
- Place in `flutter/lib/core/widgets/<widget_name>.dart` if used by multiple features
- Place in `flutter/lib/features/<feature>/widgets/` if feature-specific

**Shared Utilities:**
- Add to `flutter/lib/core/utils/`

## Special Directories

**`backend-rust/target/`:**
- Purpose: Rust build output and dependency cache
- Generated: Yes
- Committed: No (gitignored)

**`flutter/build/`:**
- Purpose: Flutter build output (APK, web artifacts, etc.)
- Generated: Yes
- Committed: No (gitignored)

**`dist/`:**
- Purpose: Flutter web build unpacked here; served as static files by the Rust backend (`ServeDir("../dist")`)
- Generated: Yes (by `flutter/build.sh web` or Docker build)
- Committed: No

**`certs/`:**
- Purpose: TLS certificate (`cert.pem`) and private key (`key.pem`) for optional HTTPS on port 4443
- Generated: Manually provisioned
- Committed: No

**`backend-rust/chat_files/`:**
- Purpose: Stored chat file attachments uploaded via `send-file-to-chat`
- Generated: Runtime
- Committed: No

**`docker/flutter-base/`:**
- Purpose: Heavy base Docker image (Ubuntu + Java + Android SDK + Flutter SDK). Built once and pushed to GHCR. Not rebuilt on every CI run.
- Generated: No
- Committed: Yes (Dockerfile only)

**`.planning/codebase/`:**
- Purpose: GSD codebase analysis documents used by `/gsd:plan-phase` and `/gsd:execute-phase`
- Generated: Yes (by `/gsd:map-codebase`)
- Committed: Yes

---

*Structure analysis: 2026-03-25*
