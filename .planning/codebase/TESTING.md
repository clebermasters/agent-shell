---
focus: quality
generated: 2026-03-25
---

# Testing Patterns

**Analysis Date:** 2026-03-25

## Flutter Tests

### Test Framework

**Runner:**
- `flutter_test` (SDK package) — built-in Flutter test runner
- No separate test config file (uses default `flutter test` discovery)
- Config: none — standard `flutter test` discovers files under `flutter/test/`

**Assertion Library:**
- `package:flutter_test/flutter_test.dart` (includes `expect`, `group`, `test`, `testWidgets`)

**Run Commands:**
```bash
cd flutter && flutter test              # Run all tests
cd flutter && flutter test --coverage   # Run with coverage report
cd flutter && flutter test test/data/models/host_test.dart  # Single file
```

### Test File Organization

**Location:**
- Separate `flutter/test/` directory (not co-located with source)
- Mirror of `lib/` structure: `test/data/models/` mirrors `lib/data/models/`

**Naming:**
- Files suffixed `_test.dart`: `host_test.dart`, `chat_message_test.dart`
- Directory structure:
```
flutter/test/
  widget_test.dart                   # Top-level smoke test (placeholder only)
  data/
    models/
      host_test.dart                 # Host model tests (12 tests)
      chat_message_test.dart         # ChatMessage + ChatBlock tests (21 tests)
  core/
    utils/                           # Directory exists, no test files yet
```

### Test Structure

**Suite Organization:**
```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:agentshell/data/models/host.dart';

void main() {
  group('Host', () {
    // Factory helper at top of group
    Host makeHost({
      String id = 'h1',
      String name = 'Test',
      String address = 'example.com',
      int port = 4010,
    }) => Host(id: id, name: name, address: address, port: port);

    group('wsUrl', () {
      test('returns ws:// for non-443 port', () {
        final host = makeHost(port: 4010);
        expect(host.wsUrl, 'ws://example.com:4010');
      });
    });

    group('fromJson', () {
      test('round trip equality', () {
        final host = makeHost();
        expect(Host.fromJson(host.toJson()), host);
      });
    });
  });
}
```

**Patterns:**
- Factory helper functions at top of `group()` with optional named params and defaults
- Nested `group()` per logical concept (field, method, scenario)
- Individual `test()` per case within group
- Equality via `expect(actual, expected)` — leverages `Equatable` on models
- No `setUp()` / `tearDown()` used — factories recreated per test
- `testWidgets` used only in placeholder `widget_test.dart` (non-functional)

### What Is Tested (Flutter)

**`flutter/test/data/models/host_test.dart`** — 12 tests covering:
- `wsUrl` — ws:// vs wss:// selection by port
- `httpUrl` — http:// vs https:// selection by port
- `copyWith` — field preservation and update
- `toJson` — serialization shape (key count, key names)
- `fromJson` — round-trip equality, null `lastConnected`
- `Equatable` — equality and inequality

**`flutter/test/data/models/chat_message_test.dart`** — 21 tests covering:
- `ChatBlock` factory constructors: `text`, `thinking`, `toolCall`, `toolResult`, `image`, `audio`, `file`
- `ChatBlock` null-safety defaults (null args default to empty string)
- `ChatMessage.copyWith` — field preservation and block list replacement
- `ChatMessage.toJson` — type as string, blocks as list
- `ChatMessage.fromJson` — round-trip, unknown type fallback to `system`, unknown block type fallback to `text`, null blocks → empty list, missing `isStreaming` → false
- `ParsedChatBlock` — construction

**`flutter/test/widget_test.dart`** — 1 placeholder test (`expect(1 + 1, 2)`), not a real test.

### Mocking

**No mocking framework used.** Tests rely entirely on real model instances. No provider or service mocking present.

### Coverage

**Requirements:** None enforced — no coverage thresholds configured.

**What is NOT tested:**
- All providers (`chat_provider.dart`, `sessions_provider.dart`, `hosts_provider.dart`, etc.)
- All screens and widgets
- All services (`websocket_service.dart`, `audio_service.dart`, `whisper_service.dart`)
- All repositories (`host_repository.dart`, `cron_repository.dart`)
- Navigation and routing logic
- `core/utils/` utilities (`extensions.dart`, `gal_helper.dart`)
- Connection and reconnection behavior

**Coverage is minimal** — only 2 model files have meaningful tests. The overall Flutter test coverage is estimated well below 10%.

---

## Rust Backend Tests

### Test Framework

**Runner:**
- Rust's built-in `cargo test`
- `tokio` for async tests via `#[tokio::test]`

**Assertion Library:**
- Standard Rust `assert!`, `assert_eq!`, `assert!(result.is_ok())`, `assert!(result.is_err())`

**Dev Dependencies:**
- `tempfile = "3"` — temporary directories/files for SQLite and filesystem tests
- `reqwest` — available but not used in unit tests (only integration potential)
- `criterion` — benchmark harness (not a test framework)

**Run Commands:**
```bash
cd backend-rust && cargo test                    # Run all unit tests
cd backend-rust && cargo test -- --nocapture     # Show println output
cd backend-rust && cargo test test_name          # Run matching test(s)
cd backend-rust && cargo bench --bench performance  # Run benchmarks
```

### Test File Organization

**Location:**
- All tests are **inline** within source files using `#[cfg(test)] mod tests { ... }`
- Located at the **bottom** of each source file
- No separate `tests/` integration test directory (directory exists but is empty)

**Files with inline test modules (30 total `#[cfg(test)]` blocks):**

| File | Test Count | What's Tested |
|------|-----------|---------------|
| `src/auth.rs` | ~20 | Token extraction, percent decoding, token comparison |
| `src/chat_event_store.rs` | ~18 | SQLite event append, merge, clear, ACP overlay, ordering |
| `src/cron/mod.rs` | ~6+ | Cron expression validation, next-run calculation, async list |
| `src/chat_log/mod.rs` | ~6+ | ContentBlock serialization, message merge ordering |
| `src/chat_log/claude_parser.rs` | ~10+ | JSONL line parsing for Claude Code log format |
| `src/chat_log/codex_parser.rs` | ~6+ | Codex log parsing |
| `src/chat_log/opencode_parser.rs` | ~8+ | OpenCode log parsing |
| `src/chat_log/watcher.rs` | tests | File watcher behavior |
| `src/tmux/mod.rs` | tests | tmux command parsing |
| `src/monitor/mod.rs` | tests | Monitor state |
| `src/terminal_buffer.rs` | tests | Terminal buffer operations |
| `src/websocket/types.rs` | ~8+ | `merge_history_messages` deduplication and ordering |
| `src/websocket/acp_cmds.rs` | tests | ACP command handling |
| `src/websocket/chat_cmds.rs` | tests | Chat command handling |
| `src/websocket/cron_cmds.rs` | tests | Cron command serialization |
| `src/websocket/dotfiles_cmds.rs` | tests | Dotfiles command handling |
| `src/websocket/file_cmds.rs` | tests | File command handling |
| `src/websocket/session_cmds.rs` | tests | Session command handling |
| `src/websocket/system_cmds.rs` | tests | System command handling |
| `src/websocket/terminal_cmds.rs` | tests | Terminal command handling |
| `src/websocket/client_manager.rs` | tests | Client manager |
| `src/types/mod.rs` | tests | Type serialization |
| `src/acp/client.rs` | tests | ACP client |
| `src/acp/messages.rs` | tests | ACP message parsing |
| `src/acp/session.rs` | tests | ACP session handling |
| `src/dotfiles/mod.rs` | tests | Dotfile operations |
| `src/chat_clear_store.rs` | tests | Chat clear store |
| `src/chat_file_storage.rs` | tests | File storage |

### Test Structure

**Standard inline pattern:**
```rust
#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    // Factory helper
    fn make_store() -> (ChatEventStore, TempDir) {
        let dir = TempDir::new().unwrap();
        let store = ChatEventStore::new(dir.path().to_path_buf()).unwrap();
        (store, dir)
    }

    fn text_msg(role: &str, text: &str) -> ChatMessage {
        ChatMessage {
            role: role.to_string(),
            timestamp: Some(chrono::Utc::now()),
            blocks: vec![ContentBlock::Text { text: text.to_string() }],
        }
    }

    #[test]
    fn test_new_creates_db() {
        let dir = TempDir::new().unwrap();
        let store = ChatEventStore::new(dir.path().to_path_buf());
        assert!(store.is_ok());
    }

    #[test]
    fn test_append_and_list() {
        let (store, _dir) = make_store();
        let msg = text_msg("user", "hello");
        store.append_message("sess1", 0, "user", &msg).unwrap();
        let events = store.list_events("sess1", 0).unwrap();
        assert_eq!(events.len(), 1);
    }
}
```

**Async test pattern:**
```rust
#[tokio::test]
async fn test_list_jobs_empty() {
    let mgr = CronManager::new();
    let jobs = mgr.list_jobs().await;
    assert!(jobs.is_empty());
}
```

**Parser test pattern** (from `claude_parser.rs`):
```rust
#[test]
fn parse_user_text_message() {
    let line = r#"{"uuid":"a","parentUuid":"b","timestamp":"...","type":"user","message":{...}}"#;
    let msg = parse_line(line).expect("should parse");
    assert_eq!(msg.role, "user");
    match &msg.blocks[0] {
        ContentBlock::Text { text } => assert_eq!(text, "fix the auth bug"),
        other => panic!("expected Text, got {other:?}"),
    }
}
```

### Fixtures and Factories

**Rust:**
- Factory functions at top of `mod tests` block, not in separate files
- `TempDir` from `tempfile` crate used for SQLite/filesystem isolation
- Helper message constructors: `text_msg(role, text)`, `make_msg(role, ts_offset_secs)`, `make_store()`
- `TempDir` bound to tuple with store to prevent premature drop: `(store, _dir)`

### Benchmarks

**Location:** `backend-rust/benches/performance.rs`

**Framework:** `criterion ^0.5` with `html_reports` feature

**Run Command:**
```bash
cd backend-rust && cargo bench --bench performance
```

**Benchmark Groups:**
- `terminal_output` — JSON encoding vs binary encoding at sizes 1KB–256KB
- `message_batching` — individual sends vs batched sends at 1–500 messages
- `buffer_operations` — `Vec<u8>` vs `BytesMut` at 100–10,000 operations
- `utf8_validation` — `std::str::from_utf8` vs `simdutf8` at 1KB–1MB
- `session_management` — PTY attachment vs capture-pane simulation (uses `sleep`)

### CI Test Integration

**CI does NOT run tests.** Neither workflow runs `flutter test` or `cargo test`:

- `.github/workflows/flutter.yml` — runs `flutter build release` only, no test step
- `.github/workflows/backend.yml` — runs `cargo build --release` only, no test step

Tests must be run locally by developers. There is no automated test enforcement on PRs or main branch pushes.

### Coverage

**Requirements:** None enforced.

**Flutter coverage summary:**
- 2/~40 model/service/provider files have tests
- No widget, screen, or provider tests

**Rust coverage summary:**
- Most source files have inline `#[cfg(test)]` modules
- Core data structures (`ChatEventStore`, `auth`, parsers) have comprehensive tests
- WebSocket handlers have test modules but coverage depth varies
- No integration tests (the `backend-rust/tests/` directory is empty)

### What Is NOT Tested (Priority Gaps)

**Flutter — High Priority:**
- `features/sessions/providers/sessions_provider.dart` — core WebSocket message routing
- `features/chat/providers/chat_provider.dart` — primary chat state machine (~1200 lines)
- `data/services/websocket_service.dart` — reconnect logic, message parsing
- `data/repositories/host_repository.dart` — Hive persistence

**Rust — High Priority:**
- `websocket/mod.rs` — main WebSocket dispatch logic
- `websocket/acp_cmds.rs` / `websocket/chat_cmds.rs` — 1400+ line command handlers with integration-level behavior
- End-to-end WebSocket message handling (no integration tests against a real Axum server)

---

*Testing analysis: 2026-03-25*
