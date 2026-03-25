---
focus: tech
generated: 2026-03-25
---

# Technology Stack

**Analysis Date:** 2026-03-25

## Languages

**Primary:**
- Dart 3.9+ - Flutter frontend (Android, Web, Linux desktop)
- Rust 2021 edition - Backend server (`backend-rust/`)

**Secondary:**
- Kotlin (JVM 17) - Android Gradle plugin configuration (`flutter/android/app/build.gradle.kts`)
- Bash - Build scripts, deployment scripts (`flutter/build.sh`, `scripts/deploy-web.sh`, `start.sh`)

## Runtime

**Flutter Frontend:**
- Flutter (stable channel) — cloned at build time from GitHub into Docker base image
- Dart SDK: `^3.9.0` (enforced in `flutter/pubspec.yaml`)
- Android SDK: API levels 34, 35, 36 installed in base image
- NDK: `27.0.12077973` (required by `flutter_pty`)

**Rust Backend:**
- Tokio async runtime `v1` (full feature set) — all async I/O
- Axum `v0.7` web framework with WebSocket and macros features
- Compiled to a single static binary: `backend-rust/target/release/agentshell-backend`
- Listens on HTTP port `4010`, optional HTTPS port `4443`

**Package Managers:**
- Flutter: `pub` (lockfile: `flutter/pubspec.lock` — present)
- Rust: `cargo` (lockfile: `backend-rust/Cargo.lock` — present)
- Android: Gradle with parallel builds and daemon enabled

## Frameworks

**Frontend:**
- Flutter 3.x (stable) — cross-platform UI framework (`flutter/`)
- Riverpod `^2.6.1` — state management via `StateNotifier` + `Provider` pattern

**Backend:**
- Axum `0.7` — async HTTP/WebSocket server
- Tower HTTP `0.5` — middleware: CORS, static file serving, tracing
- axum-server `0.6` with `tls-rustls` — optional HTTPS via rustls

**Testing:**
- Flutter: `flutter_test` SDK package + `flutter_lints ^5.0.0`
- Rust: `criterion ^0.5` for benchmarks (`backend-rust/benches/performance.rs`), `tempfile ^3` for test fixtures

**Build:**
- Docker (BuildKit) — all Flutter builds run inside Docker containers
- Base image: `agentshell-flutter-base:latest` (Ubuntu 22.04 + Java 21 + Android SDK + Flutter)
- Gradle parallel + daemon + caching enabled in `flutter/android/gradle.properties`

## Key Dependencies

**Flutter Critical:**
- `flutter_riverpod ^2.6.1` — all state management; provider tree root
- `xterm ^4.0.0` (local path override at `flutter/packages/xterm/`) — terminal emulator widget with VT100/xterm support
- `flutter_pty ^0.4.0` — PTY bridge for native Linux terminal (not used on web/Android where tmux is remote)
- `web_socket_channel ^3.0.3` — WebSocket client used in `WebSocketService`
- `dio ^5.8.0+1` — HTTP client (used for Whisper API calls in `WhisperService`)
- `flutter_background_service ^5.1.0` — keeps WebSocket alive on Android background
- `flutter_local_notifications ^20.1.0` — push notifications (Android channels: background, alerts, chat)
- `hive ^2.2.3` + `hive_flutter ^1.1.0` — local NoSQL storage (boxes for hosts, settings)
- `shared_preferences ^2.5.4` — key-value settings (API key, auth token, preferences)
- `flutter_markdown ^0.7.6` + `flutter_highlight ^0.7.0` — chat message rendering with code syntax highlighting
- `record ^6.0.0` — audio recording for speech-to-text
- `just_audio ^0.9.36` — audio playback
- `permission_handler ^12.0.1` — runtime Android permissions
- `webview_flutter ^4.10.0` — HTML file viewer in alerts

**Rust Critical:**
- `axum 0.7` — HTTP + WebSocket server framework
- `tokio 1` (full) — async runtime; drives all I/O
- `rusqlite 0.38.0` (bundled feature) — SQLite; used for `chat_events.db` and `notifications.db`
- `serde 1.0` + `serde_json 1.0` — all JSON serialization/deserialization
- `portable-pty 0.8` — PTY creation for terminal sessions
- `reqwest 0.11` (json feature) — HTTP client for REST calls (ACP/OpenCode)
- `axum-server 0.6` + `rustls 0.22` — optional TLS for HTTPS
- `subtle 2.6` — constant-time token comparison (timing-attack resistance)
- `cron 0.12` — cron expression parsing/scheduling
- `notify 6.1` — filesystem watching (inotify on Linux) for chat log changes
- `tracing 0.1` + `tracing-subscriber 0.3` — structured logging with env-filter
- `uuid 1.6` (v4, serde) — UUID generation for client IDs, job IDs, event IDs
- `base64 0.21` — audio streaming encoding
- `chrono 0.4` (serde) — all datetime handling
- `clap 4.4` (derive) — CLI argument parsing (`--audio` flag)

## Configuration

**Environment (`.env`, gitignored):**
- `SERVER_LIST` — pipe-separated `address:port,Name` entries embedded into APK at build time
- `OPENAI_API_KEY` — embedded in APK for Whisper and direct API calls
- `AUTH_TOKEN` — required at server startup; must be set or server exits
- `SHOW_THINKING` — boolean, embedded in APK
- `SHOW_TOOL_CALLS` — boolean, embedded in APK
- `S3_WEB_BUCKET` — S3 bucket for web deployment
- `CF_API_KEY`, `CF_EMAIL`, `CF_ZONE_ID`, `CF_DOMAIN` — Cloudflare cache purge
- `ALLOWED_ORIGIN` — optional extra CORS origin for the backend

**Build-time Generated:**
- `flutter/lib/core/config/build_config.dart` — auto-generated by Dockerfile from `--build-arg` values; contains `defaultServerList`, `defaultApiKey`, `defaultShowThinking`, `defaultShowToolCalls`, `authToken`, and a `BUILD_TIMESTAMP` injected post-compile into `version.json`

**TLS Certificates:**
- Expected at `../certs/cert.pem` and `../certs/key.pem` relative to the backend binary
- If absent, HTTPS server is skipped silently; HTTP-only mode continues

## Platform Requirements

**Development:**
- Docker (with BuildKit) required for all Flutter/Android builds
- Rust stable toolchain for backend builds (`dtolnay/rust-toolchain@stable` in CI)
- `tmux` must be installed and accessible on the host where the backend runs
- `ffmpeg` must be available on the host for audio streaming
- `opencode` binary must be in `$PATH` for ACP/AI agent integration

**Production:**
- Linux x86_64 (backend binary is Linux-native)
- `systemd` service (`agentshell`) managed via `start.sh`
- Port `4010` (HTTP), optionally `4443` (HTTPS)
- Serves static Flutter web build from `../dist/` directory

**Targets Supported:**
- Android APK (min SDK from `flutter.minSdkVersion`, target SDK 35+)
- Web (Flutter web, deployed to S3 + Cloudflare CDN)
- Linux desktop (Flutter Linux, bundled binary + libs)

---

*Stack analysis: 2026-03-25*
