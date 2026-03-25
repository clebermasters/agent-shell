/// Platform detection for input handling adjustments.
library;

import 'package:flutter/foundation.dart' show kIsWeb, TargetPlatform, defaultTargetPlatform;

enum TerminalTargetPlatform {
  android,
  ios,
  linux,
  macos,
  windows,
  web,
}

/// Detect the current platform for terminal input adjustments.
TerminalTargetPlatform get terminalPlatform {
  if (kIsWeb) return TerminalTargetPlatform.web;
  switch (defaultTargetPlatform) {
    case TargetPlatform.android:
      return TerminalTargetPlatform.android;
    case TargetPlatform.iOS:
      return TerminalTargetPlatform.ios;
    case TargetPlatform.linux:
      return TerminalTargetPlatform.linux;
    case TargetPlatform.macOS:
      return TerminalTargetPlatform.macos;
    case TargetPlatform.windows:
      return TerminalTargetPlatform.windows;
    case TargetPlatform.fuchsia:
      return TerminalTargetPlatform.linux;
  }
}

/// Whether the platform uses macOS-style key bindings (Cmd instead of Ctrl).
bool get isMacOS =>
    terminalPlatform == TerminalTargetPlatform.macos ||
    terminalPlatform == TerminalTargetPlatform.ios;
