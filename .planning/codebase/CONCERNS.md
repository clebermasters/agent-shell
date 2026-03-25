---
focus: concerns
generated: 2026-03-25
---

# Codebase Concerns

**Analysis Date:** 2026-03-25

---

## Tech Debt

**Large monolithic files in Flutter (1000+ lines):**
- Issue: Several Flutter screens and widgets have grown well beyond maintainable size, mixing UI, business logic, and state mutations.
- Files:
  - `flutter/lib/features/chat/widgets/professional_message_bubble.dart` (1473 lines)
  - `flutter/lib/features/chat/providers/chat_provider.dart` (1219 lines)
  - `flutter/lib/features/chat/screens/chat_screen.dart` (1202 lines)
  - `flutter/lib/features/dotfiles/screens/dotfile_editor_screen.dart` (873 lines)
  - `flutter/lib/features/file_browser/screens/file_browser_screen.dart` (813 lines)
  - `flutter/lib/features/terminal/screens/terminal_screen.dart` (796 lines)
- Impact: Difficult to navigate, test, or extend. Changes in one section risk regressions in another.
- Fix approach: Extract reusable sub-widgets and delegate business logic to providers. Target <400 lines per file.

**Large Rust files:**
- Issue: Several backend source files are large and handle multiple concerns.
- Files:
  - `backend-rust/src/websocket/acp_cmds.rs` (1476 lines)
  - `backend-rust/src/websocket/chat_cmds.rs` (1452 lines)
  - `backend-rust/src/chat_log/watcher.rs` (823 lines)
  - `backend-rust/src/acp/client.rs` (939 lines)
  - `backend-rust/src/types/mod.rs` (1110 lines)
- Impact: `types/mod.rs` contains ALL message enums (`WebSocketMessage`, `ServerMessage`) and all domain structs; adding a new protocol message requires editing this single file and causes merge conflicts.
- Fix approach: Split `types/mod.rs` into domain-specific submodules (e.g., `types/cron.rs`, `types/chat.rs`, `types/terminal.rs`).

**Hardcoded ports:**
- Issue: HTTP port 4010 and HTTPS port 4443 are hardcoded literals in `backend-rust/src/main.rs` lines 268-269 with a comment `// Dev branch uses different ports`. CORS allowlist also hardcodes these ports at lines 333-336.
- Files: `backend-rust/src/main.rs`
- Impact: Changing the port requires editing source code and recompiling; not configurable via environment.
- Fix approach: Read from `HTTP_PORT` / `HTTPS_PORT` environment variables with fallbacks.

**`lazy_static` global singletons for managers:**
- Issue: `CRON_MANAGER` and `DOTFILES_MANAGER` are global lazy-static singletons initialized at process startup. The cron manager is not part of `AppState` and bypasses the dependency injection pattern used for other components.
- Files: `backend-rust/src/cron/mod.rs:382`, `backend-rust/src/dotfiles/mod.rs:415`
- Impact: Cannot be mocked for tests; initialization errors are not propagated to callers; tight coupling between the HTTP layer and the global manager.
- Fix approach: Move both managers into `AppState` using the same `Arc<>` pattern as `acp_client`, `chat_event_store`, etc.

**Background service timer is a no-op:**
- Issue: The Android background service `onStart` spawns a 1-second `Timer.periodic` that does nothing (the body of the `isForegroundService()` branch is empty).
- Files: `flutter/lib/core/services/background_service_native.dart:111-115`
- Impact: Wastes a wakeup every second on Android, drains battery. Currently provides no functional value.
- Fix approach: Remove the timer until an actual use case (e.g., polling for notifications) is implemented, or stop and restart the service on demand.

**Unused `HostRepository` (Hive):**
- Issue: `flutter/lib/data/repositories/host_repository.dart` uses Hive for host persistence, but no feature code calls it. The `HostsNotifier` at `flutter/lib/features/hosts/providers/hosts_provider.dart` stores hosts via `SharedPreferences` JSON directly, making the repository dead code. Hive is still a pubspec dependency.
- Files: `flutter/lib/data/repositories/host_repository.dart`, `flutter/pubspec.yaml`
- Impact: Adds ~300KB to build, introduces an unused dependency with its own initialization path (Hive adapters / box opening never called).
- Fix approach: Remove `HostRepository`, remove the `hive` and `hive_flutter` dependencies from `pubspec.yaml`.

**SQLite connection opened per operation:**
- Issue: `ChatEventStore` opens a new `Connection::open()` on every method call (9 separate open calls in the file). Each open acquires a new file handle and allocates SQLite state.
- Files: `backend-rust/src/chat_event_store.rs:34,66,110,166,233,249,259,276`
- Impact: Under load (high message volume), this creates excessive file-system overhead and SQLite lock contention, partially mitigated by the 5-second busy-timeout. No connection pooling.
- Fix approach: Hold a single `Arc<Mutex<Connection>>` across the lifetime of the store, or use `r2d2-sqlite` / `sqlx` for a connection pool.

---

## Known Bugs

**Cron handler panics on missing job (HTTP 500):**
- Symptoms: `GET /api/cron/jobs/:id` with a non-existent ID panics the request handler thread via `.expect("Job not found")`, which Axum converts to an opaque 500.
- Files: `backend-rust/src/cron_handler.rs:63,104,114`
- Trigger: Any request for a job ID that was deleted or never existed.
- Workaround: None; client receives a 500 with no body.

**Cron handler panics on create/update/delete failure:**
- Symptoms: `create_job`, `update_job`, `delete_job`, `toggle_job` all use `.expect(...)` on their `CRON_MANAGER` calls. Any internal error (e.g., duplicate name, crontab parse failure) panics instead of returning a 4xx/5xx response.
- Files: `backend-rust/src/cron_handler.rs:57,93,98,108`
- Trigger: Duplicate job name, malformed crontab file on disk, I/O error.
- Workaround: None.

---

## Security Considerations

**Shell injection in cron job command construction:**
- Risk: `create_job` and `update_job` in `backend-rust/src/cron_handler.rs` build a shell command string by interpolating `req.workdir`, `req.llm_provider`, and `req.llm_model` directly into a `format!` string. Only `prompt` is shell-escaped (single-quote escaping). A malicious `workdir` value like `; rm -rf ~` or a `llm_provider` containing shell metacharacters would execute arbitrary commands via `crontab`.
- Files: `backend-rust/src/cron_handler.rs:32-36,68-71`
- Current mitigation: Authenticated endpoint only; single-quote escaping on `prompt` only.
- Recommendations: Validate `workdir` is an existing absolute path with no shell metacharacters; validate `llm_provider` and `llm_model` against an allowlist of known values; or restructure to pass arguments as discrete cron fields rather than a shell command string.

**No filename sanitization on notification file upload:**
- Risk: `create_notification` in `backend-rust/src/notification_handler.rs:119` joins `file_req.filename` directly onto the notification's storage directory path without sanitization. A filename like `../../etc/cron.d/evil` could write outside the intended directory.
- Files: `backend-rust/src/notification_handler.rs:119`
- Current mitigation: Authenticated endpoint; server runs as the process user. `ChatFileStorage.save_file_to_directory` uses `sanitize_filename()` but `notification_handler` does not use that helper.
- Recommendations: Apply the same `sanitize_filename()` helper already present in `backend-rust/src/chat_file_storage.rs` to `file_req.filename` before constructing the path.

**Auth token passed as URL query parameter:**
- Risk: `websocket_service.dart:60` appends `?token=<AUTH_TOKEN>` to all WebSocket/HTTP URLs. Query parameters appear in server access logs, browser history, and HTTP `Referer` headers.
- Files: `flutter/lib/data/services/websocket_service.dart:55-61`
- Current mitigation: URL is sanitized before logging (`_sanitizeUrl`); TLS used in production.
- Recommendations: Prefer the `X-Auth-Token` header already supported by the backend middleware (`backend-rust/src/auth.rs:80-83`). The WebSocket upgrade request can carry custom headers on native (Android/Linux) but not on web, so a hybrid approach (header on native, query param on web) would reduce exposure.

**No upload size limit on notification file endpoint:**
- Risk: `POST /api/notifications` decodes arbitrarily large base64 payloads into memory (`STANDARD.decode(&file_req.data)` at `notification_handler.rs:120`) and writes them to disk. There is no `Content-Length` guard or per-request payload size limit configured in Axum.
- Files: `backend-rust/src/notification_handler.rs:102-164`
- Current mitigation: Authenticated endpoint.
- Recommendations: Add Axum's `DefaultBodyLimit::max(N)` layer to the `/api/notifications` route. Consider a per-file size cap in the request schema.

---

## Performance Bottlenecks

**Unbounded MPSC channels for client message queues:**
- Problem: Each connected WebSocket client is given an `mpsc::unbounded_channel` (`websocket/mod.rs:48`, `client_manager.rs:10`). If a slow client fails to drain messages (e.g., poor network), the queue grows unbounded and consumes RAM.
- Files: `backend-rust/src/websocket/client_manager.rs:10,23`, `backend-rust/src/websocket/mod.rs:48`
- Cause: `tokio::sync::mpsc::unbounded_channel` has no backpressure.
- Improvement path: Switch to `mpsc::channel(capacity)` with a bounded capacity (e.g., 256-512 messages). Drop old messages or disconnect the slow client when the buffer fills.

**SQLite per-call connection overhead in ChatEventStore:**
- Problem: See Tech Debt section above. Under high chat message throughput, repeated open/close cycles degrade write performance.
- Files: `backend-rust/src/chat_event_store.rs`
- Cause: No connection pooling.
- Improvement path: Use a single shared connection protected by a `Mutex`, or migrate to `sqlx` with a `SqlitePool`.

**Output flooding heuristic is fragile:**
- Problem: `websocket/mod.rs:90-92` adds a 100µs sleep when a message contains `"type":"output"` and is longer than 1000 bytes. This is a comment-noted heuristic to "prevent flooding" rather than real backpressure.
- Files: `backend-rust/src/websocket/mod.rs:89-92`
- Cause: No flow-control mechanism between the PTY reader and the WebSocket sender.
- Improvement path: Implement proper backpressure using a bounded channel or batch-and-send approach.

---

## Fragile Areas

**ACP client process lifecycle:**
- Files: `backend-rust/src/acp/client.rs`, `backend-rust/src/websocket/acp_cmds.rs`
- Why fragile: The ACP client spawns an `opencode` child process (`acp/client.rs:110`). If `opencode` is not installed or exits unexpectedly, the error propagates but the `Arc<RwLock<Option<AcpClient>>>` is left in a `Some` state, causing subsequent calls to find an unresponsive client. Recovery requires a server restart or `SelectBackend` re-invocation.
- Safe modification: Always check `initialized` RwLock flag before sending; implement a `reconnect` path.
- Test coverage: ACP client unit tests exist but none test the crash/recovery path; `main.rs` (0% coverage) is not tested.

**Chat log watcher multi-parser switch:**
- Files: `backend-rust/src/chat_log/watcher.rs`, `backend-rust/src/chat_log/opencode_parser.rs`, `backend-rust/src/chat_log/claude_parser.rs`, `backend-rust/src/chat_log/codex_parser.rs`
- Why fragile: The watcher detects which parser to use based on file path heuristics and PID inspection. Log format changes in upstream tools (OpenCode, Claude CLI, Codex) silently produce empty or malformed chat events.
- Safe modification: Add integration tests using fixture log files for each parser. Any change to a parser should require fixture updates.
- Test coverage: `claude_parser.rs` (94%), `codex_parser.rs` (95%), `opencode_parser.rs` (62%), `watcher.rs` (37%).

**Cron job state lives in both memory and crontab:**
- Files: `backend-rust/src/cron/mod.rs`
- Why fragile: `CronManager` maintains an in-memory `HashMap` and also writes to the system crontab. The two can diverge if the process restarts (memory is re-read from crontab) or if crontab is edited externally. The parse logic at lines 181-217 is fragile string-splitting.
- Safe modification: Treat the crontab as the single source of truth; eliminate the in-memory cache entirely, or add a crontab checksum check on each load.
- Test coverage: `cron/mod.rs` (44%).

**`professional_message_bubble.dart` has multiple `dart:io` usages without web guards:**
- Files: `flutter/lib/features/chat/widgets/professional_message_bubble.dart`
- Why fragile: The file imports `dart:io` at line 3. Some branches are guarded by `kIsWeb` checks (lines 682, 728, 836, 1378), but the widget is used in both Android and web builds. Any code path reaching an unguarded `File()` or `Platform.` call on web will throw a `UnsupportedError` at runtime.
- Safe modification: Audit every `dart:io` usage in this file; wrap unguarded paths with `if (!kIsWeb)` or extract into platform-specific files.

---

## Scaling Limits

**No WebSocket connection limit:**
- Current capacity: Unlimited concurrent WebSocket connections; each adds an unbounded channel, a Tokio task, and a PTY allocation.
- Limit: OOM or PTY descriptor exhaustion (system default is ~1024 open files per process).
- Scaling path: Add a configurable `MAX_CLIENTS` guard in `ClientManager::add_client`.

**No notification storage eviction:**
- Current capacity: Notification files accumulate in `<base_dir>/notifications/<id>/` indefinitely. No TTL, no size cap, no cleanup job.
- Limit: Disk full.
- Scaling path: Add a periodic cleanup task that removes notifications and their files older than a configurable retention period (e.g., 30 days).

---

## Dependencies at Risk

**`lazy_static` (cron, dotfiles, terminal_buffer, audio):**
- Risk: `lazy_static` is being superseded by `std::sync::OnceLock` (stable in Rust 1.70+) and `once_cell`. The project SDK requires no minimum Rust version, but `lazy_static` adds an indirect dependency.
- Impact: Cosmetic; not a breaking risk.
- Migration plan: Replace `lazy_static!` blocks with `std::sync::OnceLock::new()` or `std::sync::LazyLock` (stable Rust 1.80+).

**Vendored xterm fork (`flutter/packages/xterm`):**
- Risk: `pubspec.yaml` overrides `xterm: ^4.0.0` with a local path fork (`packages/xterm`). The fork diverges from upstream, meaning upstream bug fixes and security patches must be ported manually.
- Impact: Terminal rendering or PTY input bugs fixed upstream will not automatically apply.
- Migration plan: Document what changes were made to the fork; periodically sync with upstream `xterm` releases.

**`hive` / `hive_flutter` dependency (unused):**
- Risk: Hive is listed in `pubspec.yaml` but never initialized or called (see Tech Debt section). The package adds build time and APK size for zero benefit.
- Impact: ~300KB APK size increase; potential future version conflicts.
- Migration plan: Remove both `hive` and `hive_flutter` from `pubspec.yaml`.

---

## Missing Critical Features

**No request body size limit on the Axum router:**
- Problem: The Axum router does not configure `DefaultBodyLimit`. Any authenticated caller can send an arbitrarily large JSON body to any endpoint (including chat file upload, notification creation).
- Blocks: Production hardening.

**No WebSocket message size limit:**
- Problem: There is no maximum message size configured on the `WebSocketUpgrade` handler. A single oversized message could exhaust memory parsing a large JSON payload.
- Blocks: Production hardening.

**iOS notification support absent:**
- Problem: `background_service_native.dart:13` exits early if not Android. `flutterLocalNotificationsPlugin.initialize()` only configures `AndroidInitializationSettings`; the `IOSInitializationSettings` / `DarwinInitializationSettings` is not provided. The iOS background service entry point `onIosBackground` returns `true` but does nothing.
- Blocks: iOS deployment.

---

## Test Coverage Gaps

**`backend-rust/src/main.rs` — 0% coverage:**
- What is not tested: Server startup, port binding, CORS layer construction, TLS detection, shutdown signal handling.
- Files: `backend-rust/src/main.rs`
- Risk: Startup regressions (wrong port, CORS misconfiguration) go undetected until runtime.
- Priority: Medium — integration tests that start the server and verify `/api/clients` and `/ws` are accessible would cover this.

**`backend-rust/src/websocket/mod.rs` — 0% coverage:**
- What is not tested: WebSocket upgrade, client registration/deregistration, message routing dispatch, connection lifecycle.
- Files: `backend-rust/src/websocket/mod.rs`
- Risk: Changes to the dispatch table (`handle_message`) can silently drop message types.
- Priority: High — the dispatch table at lines 160-320 is the single most critical routing point in the backend.

**`backend-rust/src/acp/client.rs` — 46% coverage:**
- What is not tested: Process crash recovery, partial JSON line handling, session ID mismatch, concurrent prompt cancellation.
- Files: `backend-rust/src/acp/client.rs`
- Risk: ACP subprocess failures produce silent hangs rather than errors.
- Priority: High.

**`backend-rust/src/chat_log/watcher.rs` — 37% coverage:**
- What is not tested: File rotation handling, PID lookup failure, parser fallback logic, large file backfill.
- Files: `backend-rust/src/chat_log/watcher.rs`
- Risk: Chat history silently stops updating under unusual log states.
- Priority: High.

**Flutter — near-zero widget/integration tests:**
- What is not tested: All screens, all providers (except `ChatMessage`/`Host` models), WebSocket reconnect logic, notification tap navigation.
- Files: `flutter/test/widget_test.dart` contains a placeholder `expect(1 + 1, 2)` test.
- Risk: UI regressions are only caught by manual testing.
- Priority: High — at minimum, provider unit tests and a WebSocket mock-server integration test should exist.

**`backend-rust/src/cron/mod.rs` — 44% coverage:**
- What is not tested: `load_from_crontab` parse paths, concurrent job updates, crontab write failures.
- Files: `backend-rust/src/cron/mod.rs`
- Risk: Crontab corruption on concurrent writes.
- Priority: Medium.

---

*Concerns audit: 2026-03-25*
