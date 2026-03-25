---
focus: quality
generated: 2026-03-25
---

# Coding Conventions

**Analysis Date:** 2026-03-25

## Flutter / Dart

### Naming Patterns

**Files:**
- `snake_case` for all Dart files: `chat_provider.dart`, `websocket_service.dart`, `host_repository.dart`
- Screens suffixed `_screen.dart`: `chat_screen.dart`, `terminal_screen.dart`
- Providers suffixed `_provider.dart`: `chat_provider.dart`, `sessions_provider.dart`
- Widgets suffixed `_widget.dart` or descriptively named: `terminal_view_widget.dart`, `professional_message_bubble.dart`
- Repositories suffixed `_repository.dart`: `host_repository.dart`, `cron_repository.dart`
- Services suffixed `_service.dart`: `websocket_service.dart`, `audio_service.dart`
- Models named after domain entity: `host.dart`, `chat_message.dart`, `cron_job.dart`

**Classes:**
- `PascalCase` for all classes: `ChatState`, `SessionsNotifier`, `WebSocketService`
- State classes: `{Feature}State` — `ChatState`, `SessionsState`, `TerminalState`
- Notifier classes: `{Feature}Notifier` — `ChatNotifier`, `HostsNotifier`, `AlertsNotifier`
- Screen classes: `{Feature}Screen` — `ChatScreen`, `TerminalScreen`
- Widget classes: descriptive `PascalCase` — `ProfessionalMessageBubble`, `AlertBanner`

**Variables and Fields:**
- `camelCase` for local variables, instance fields, and parameters
- Private fields prefixed with `_`: `_wsService`, `_messageSubscription`, `_currentUrl`
- Private methods prefixed with `_`: `_init()`, `_loadHosts()`, `_sanitizeUrl()`
- Tab index constants use `_k` prefix with uppercase: `_kTabSessions = 0`, `_kTabCron = 1`

**Enums:**
- `PascalCase` for enum types, `camelCase` for values: `ChatMessageType.user`, `ChatBlockType.text`
- Enum `.name` used for JSON serialization (not manual strings): `type.name`

**Providers:**
- Global provider variables use `camelCase` with `Provider` suffix: `chatProvider`, `hostsProvider`, `sharedWebSocketServiceProvider`
- Shared singleton providers prefixed `shared`: `sharedWebSocketServiceProvider`, `sharedPreferencesProvider`

### Code Style

**Formatting:**
- `flutter analyze` with `package:flutter_lints/flutter.yaml` (default ruleset, no extra rules added)
- `analysis_options.yaml` uses default lints only — no project-specific overrides enforced
- No `prefer_single_quotes` enforced; double quotes used throughout

**Linting:**
- `flutter_lints ^5.0.0` in dev_dependencies
- `// ignore: avoid_print` used in `websocket_service.dart` where `print()` is intentional for connection logging

### Import Organization

**Order (observed pattern):**
1. Dart SDK imports (`dart:async`, `dart:convert`, `dart:io`)
2. Flutter SDK imports (`package:flutter/material.dart`, `package:flutter/foundation.dart`)
3. Third-party packages (`package:flutter_riverpod/...`, `package:shared_preferences/...`)
4. Project-relative imports (using relative `../../../` paths — no path aliases)

Example from `chat_screen.dart`:
```dart
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../providers/chat_provider.dart';
```

**Barrel Files:**
- Each data layer directory exports via a barrel: `data/models/models.dart`, `data/services/services.dart`, `data/repositories/repositories.dart`
- Feature-level providers are NOT barrel-exported — imported directly

**Path Style:**
- Relative paths throughout (`../../../`) — no `package:agentshell/` imports in app code
- Platform conditional imports: `if (dart.library.html) 'stub.dart'` pattern used for web/native splits in `terminal_provider.dart`

### State Management Patterns

**Provider Pattern (Riverpod):**
- All state uses `StateNotifierProvider<{Feature}Notifier, {Feature}State>`
- State classes are plain immutable Dart classes with `const` constructors and `copyWith`
- Notifiers extend `StateNotifier<T>` and accept dependencies via constructor injection
- Shared WebSocket service injected via `ref.watch(sharedWebSocketServiceProvider)`

**State Class Structure:**
```dart
class {Feature}State {
  final Type field;
  final bool isLoading;
  final String? error;

  const {Feature}State({
    this.field = const [],
    this.isLoading = false,
    this.error,
  });

  {Feature}State copyWith({
    Type? field,
    bool? isLoading,
    String? error,
  }) {
    return {Feature}State(
      field: field ?? this.field,
      isLoading: isLoading ?? this.isLoading,
      error: error,          // note: nullable fields NOT coalesced (allows explicit null)
    );
  }
}
```

**Sentinel pattern** for nullable fields that need explicit null-setting in `copyWith`:
```dart
// Used in chat_provider.dart and cron_provider.dart
const _sentinel = Object();
// or
static const _unset = Object();

copyWith({ Object? pendingPermission = _sentinel }) {
  return State(
    pendingPermission: pendingPermission == _sentinel
        ? this.pendingPermission
        : pendingPermission as PendingPermission?,
  );
}
```

**Notifier Structure:**
```dart
class {Feature}Notifier extends StateNotifier<{Feature}State> {
  final WebSocketService _wsService;
  StreamSubscription? _subscription;

  {Feature}Notifier(this._wsService) : super(const {Feature}State()) {
    _init();
  }

  void _init() {
    _wsService.messages.listen((message) {
      final type = message['type'] as String?;
      switch (type) {
        case 'message-type':
          // handle
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}

final {feature}Provider = StateNotifierProvider<{Feature}Notifier, {Feature}State>(
  (ref) {
    final wsService = ref.watch(sharedWebSocketServiceProvider);
    return {Feature}Notifier(wsService);
  },
);
```

**Widget Integration:**
- Complex stateful screens extend `ConsumerStatefulWidget` / `ConsumerState<T>`
- Simple display widgets extend `ConsumerWidget`
- Use `ref.watch()` for reactive state, `ref.read()` for one-shot reads (e.g., in callbacks)

### Data Model Patterns

**All models implement:**
1. Immutable `const` constructor with named parameters
2. `copyWith()` for immutable updates
3. `toJson()` → `Map<String, dynamic>`
4. `factory fromJson(Map<String, dynamic>)` for deserialization
5. `Equatable` extension for value equality (via `equatable ^2.0.7`)
6. `@override List<Object?> get props` listing all fields

```dart
class Host extends Equatable {
  final String id;
  final String name;

  const Host({required this.id, required this.name});

  Host copyWith({String? id, String? name}) =>
      Host(id: id ?? this.id, name: name ?? this.name);

  Map<String, dynamic> toJson() => {'id': id, 'name': name};

  factory Host.fromJson(Map<String, dynamic> json) =>
      Host(id: json['id'] as String, name: json['name'] as String);

  @override
  List<Object?> get props => [id, name];
}
```

**Factory constructors** on models (`ChatBlock`):
- Named factory constructors per type: `ChatBlock.text()`, `ChatBlock.toolCall()`, `ChatBlock.image()`

### Error Handling

**Patterns:**
- WebSocket message parsing wrapped in `try/catch`, errors logged to `_log()` internal stream
- Stream subscriptions stored as `StreamSubscription?`, cancelled in `dispose()`
- No global exception handlers — errors surface as `String? error` on state objects
- `try/catch` used in service methods; errors update `state.error` field
- `ignore: avoid_print` annotation in places where `print()` is kept intentionally

**State error field convention:**
- `error` field on state is nullable `String?`
- In `copyWith`, `error` is NOT coalesced (`error: error` not `error: error ?? this.error`) — allows explicit clearing

### Logging

**Flutter:**
- Connection events use `print()` with `[CONN]` prefix (intentional, suppressed lint warnings with `// ignore: avoid_print`)
- Debug traces commented out with `// print(...)` — not deleted — scattered throughout `chat_provider.dart` and `websocket_service.dart`
- Internal WebSocket log stream via `_logController` (StreamController) for structured log access

**Rust:**
- `tracing` crate for structured logging: `info!()`, `debug!()`, `warn!()`, `error!()`
- Tracing subscriber configured with `EnvFilter` (respects `RUST_LOG` env var)
- Connection events logged at `info!` level; errors at `error!` level

### Configuration Constants

**Flutter — `AppConfig` class (`core/config/app_config.dart`):**
- Private constructor `AppConfig._()` — prevents instantiation
- All constants `static const String key*` for storage keys
- All defaults `static get default*` delegate to `BuildConfig`
- Storage keys prefixed semantically: `keyHosts`, `keySelectedHost`, `keyThemeMode`

**Flutter — `AppConstants` class (`core/constants/app_constants.dart`):**
- Private constructor `AppConstants._()` — prevents instantiation
- Message type string constants: `msgTypeSessionList`, `msgTypeCronList`

### Module Design

**Feature folder structure:**
```
features/{feature}/
  providers/{feature}_provider.dart   # State + Notifier + Provider declarations
  screens/{feature}_screen.dart       # UI entry point
  widgets/{widget_name}.dart          # Reusable sub-widgets
```

**Barrel exports only at data layer** — features do NOT export barrels; providers are imported directly.

---

## Rust Backend

### Naming Patterns

- `snake_case` for modules, functions, local variables, struct fields: `chat_event_store`, `append_message`, `session_name`
- `PascalCase` for types, enums, structs: `ChatEventStore`, `ContentBlock`, `AppState`
- `SCREAMING_SNAKE_CASE` for constants and statics: `ENABLE_AUDIO_LOGS`
- Modules named after domain: `chat_event_store.rs`, `dotfiles/mod.rs`, `websocket/mod.rs`

### Serialization Conventions

- All JSON-serialized structs use `#[serde(rename_all = "camelCase")]` to match Flutter's camelCase field names
- Enum variants serialized as kebab-case for WebSocket message types: `#[serde(tag = "type", rename_all = "kebab-case")]`
- Tagged enums use `#[serde(tag = "type")]` for discriminated union JSON
- Selective `#[serde(rename = "fieldName")]` overrides for specific field name mismatches
- `#[serde(skip_serializing_if = "Option::is_none")]` on optional fields to keep JSON lean
- `#[serde(alias = "...")]` for backward compat with older message format variants

### Error Handling

- `anyhow::Result<T>` used pervasively for fallible functions
- `.with_context(|| format!("..."))` for error context enrichment
- `anyhow::bail!("message")` for early returns with error messages
- `?` operator propagated up; no `unwrap()` in production paths except `unwrap_or` defaults
- `#[allow(dead_code)]` on intermediate parsing structs that are only used for deserialization

### Module Structure

- Top-level subsystems as modules in `src/`: `tmux/`, `acp/`, `cron/`, `dotfiles/`, `websocket/`
- `websocket/` subdirected into command handlers: `acp_cmds.rs`, `chat_cmds.rs`, `cron_cmds.rs`, etc.
- Each module file begins with `use` imports, then type definitions, then `impl` blocks, then `#[cfg(test)]`
- `pub use` re-exports in `mod.rs` files to expose module API

### Testing Inline Pattern

All Rust tests are inline `#[cfg(test)] mod tests { ... }` at the bottom of the source file:
```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn make_fixture() -> SomeType { ... }  // helper

    #[test]
    fn test_something() {
        let x = make_fixture();
        assert_eq!(x.field, expected);
    }

    #[tokio::test]
    async fn test_async_something() { ... }
}
```

---

*Convention analysis: 2026-03-25*
