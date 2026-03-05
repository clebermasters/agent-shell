# AGENTS.md - WebMux Flutter Development Guide

## Project Overview

WebMux is a Flutter application providing terminal management and related features. It uses **Riverpod** for state management, follows a **feature-based** directory structure, and targets Android (APK builds).

## Build Commands

```bash
# Install dependencies
flutter pub get

# Run the app on connected device/emulator
flutter run

# Run tests
flutter test                    # Run all tests
flutter test test/widget_test.dart  # Run single test file
flutter test --name "test name"      # Run tests matching pattern

# Analyze/lint
flutter analyze                 # Run static analysis
flutter analyze lib/           # Analyze specific directory

# Build APK (debug)
flutter build apk --debug

# Build APK (release)
flutter build apk --release

# Docker build (using flutter/build.sh)

The `flutter/build.sh` script builds the Flutter APK using Docker. It supports both debug and release builds with optional auto-install to a connected Android device.

```bash
# From the flutter directory
./build.sh debug        # Debug build
./build.sh release      # Release build (default)

# With auto-install to device
./build.sh release --install    # Auto-install via USB
./build.sh release --wireless   # Auto-install via WiFi

# From project root (if flutter/build.sh is executable)
./flutter/build.sh debug
./flutter/build.sh release --install
```

The script will output the APK to the project root:
- `webmux-flutter-debug.apk`
- `webmux-flutter-release.apk`

The build script also reads from a `.env` file in the project root for:
- `SERVER_LIST` - Default server list
- `OPENAI_API_KEY` - API key for builds
```

## Code Style Guidelines

### Formatting

- Use **2 spaces** for indentation (Flutter default)
- Run `flutter format lib/` before committing
- Maximum line length: 80 characters (recommended)
- Use trailing commas for better formatting

### Imports

Organize imports in the following order (use `flutter format` to enforce):

1. Dart SDK imports (`package:flutter/...`)
2. External packages (`package:...`)
3. Relative imports (`../`, `./`)
4. Empty line between groups

```dart
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:some_package/some_widget.dart';
import '../core/config/app_config.dart';
import 'providers/my_provider.dart';
```

### Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Files | snake_case | `terminal_screen.dart`, `my_provider.dart` |
| Classes | PascalCase | `class TerminalScreen` |
| Enums | PascalCase | `enum ConnectionStatus` |
| Constants | camelCase with k prefix | `kMaxRetries` |
| Variables | camelCase | `final userName` |
| Private members | underscore prefix | `_internalState` |
| Providers | suffix with Provider | `terminalProvider` |

### Types & Generics

- Use `var` for local variables when type is obvious
- Prefer explicit types for public APIs, function parameters, and return types
- Use `late` for lazy initialization when appropriate
- Prefer `String?` over nullable dynamic types
- Use `typedef` for frequently used function signatures

```dart
// Good
final List<TerminalSession> sessions = [];
var currentIndex = 0;
late TerminalController controller;

// Avoid
final sessions = [];  // Type not clear
dynamic data;         // Avoid dynamic
```

### State Management (Riverpod)

```dart
// Provider definition
final myProvider = StateNotifierProvider<MyNotifier, MyState>((ref) {
  return MyNotifier(ref);
});

// StateNotifier for complex state
class MyNotifier extends StateNotifier<MyState> {
  MyNotifier(this.ref) : super(MyState.initial());

  final Ref ref;

  void updateSomething(String value) {
    state = state.copyWith(value: value);
  }
}
```

### Error Handling

- Use try-catch with specific exception types
- Provide meaningful error messages
- Use Result types or sealed classes for operations that can fail
- Always handle async errors

```dart
// Good
try {
  final result = await service.fetchData();
} on NetworkException catch (e) {
  // Handle specific error
  emit(state.copyWith(error: e.message));
} catch (e) {
  // Fallback for unexpected errors
  logger.e('Unexpected error: $e');
}
```

### Widgets

- Extract widgets for reusability (>10 lines or repeated 2+ times)
- Use `const` constructors where possible
- Prefer composition over inheritance
- Keep widgets small and focused (single responsibility)

```dart
// Good - small focused widget
class TerminalTitle extends StatelessWidget {
  const TerminalTitle({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Text(title, style: Theme.of(context).textTheme.titleMedium);
  }
}
```

### Documentation

- Document public APIs with doc comments (`///`)
- Document widget parameters if not self-explanatory
- Add TODO comments for incomplete code: `// TODO(username): description`
- Avoid inline comments explaining obvious code

### Testing

- Follow naming: `<subject>_test.dart`
- Group related tests with `group()`:
- Use `expect()` for assertions
- Mock external dependencies

```dart
group('TerminalService', () {
  test('should connect to server', () async {
    final service = TerminalService();
    await service.connect('localhost');
    expect(service.isConnected, true);
  });
});
```

## Project Structure

```
lib/
├── main.dart                 # Entry point
├── core/
│   ├── config/              # App configuration
│   ├── constants/           # App constants
│   ├── theme/               # Theme definition
│   └── utils/               # Utilities
├── data/
│   ├── models/              # Data models
│   ├── repositories/       # Data repositories
│   └── services/            # API/WebSocket services
├── features/                # Feature modules
│   ├── auth/
│   ├── terminal/
│   ├── chat/
│   └── ...
└── shared/
    ├── widgets/             # Shared widgets
    └── providers/          # Shared providers
```

## Key Dependencies

- **State**: `flutter_riverpod`
- **HTTP**: `dio`, `web_socket_channel`
- **Terminal**: `xterm`, `flutter_pty`
- **Storage**: `shared_preferences`, `hive`
- **UI**: `flutter_svg`, `flutter_markdown`

## Pre-commit Checklist

Before committing code:

1. Run `flutter analyze` - fix all warnings/errors
2. Run `flutter test` - ensure tests pass
3. Run `flutter format lib/` - format code
4. Verify no secrets/keys in code
