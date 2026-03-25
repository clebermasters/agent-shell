/// Terminal styling: [TerminalStyle], [TerminalTheme], [TerminalCursorType],
/// and 256-color palette builder.
library;

import 'dart:ui' show Color;

// ═══════════════════════════════════════════════════════════════════════════════
//  TerminalStyle — font configuration (matches app API)
// ═══════════════════════════════════════════════════════════════════════════════

class TerminalStyle {
  final double fontSize;
  final double fontHeightFactor;
  final String fontFamily;
  final List<String> fontFamilyFallback;

  const TerminalStyle({
    this.fontSize = 14.0,
    this.fontHeightFactor = 1.2,
    this.fontFamily = 'monospace',
    this.fontFamilyFallback = const [],
  });

  TerminalStyle copyWith({
    double? fontSize,
    double? fontHeightFactor,
    String? fontFamily,
    List<String>? fontFamilyFallback,
  }) {
    return TerminalStyle(
      fontSize: fontSize ?? this.fontSize,
      fontHeightFactor: fontHeightFactor ?? this.fontHeightFactor,
      fontFamily: fontFamily ?? this.fontFamily,
      fontFamilyFallback: fontFamilyFallback ?? this.fontFamilyFallback,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TerminalStyle &&
          fontSize == other.fontSize &&
          fontHeightFactor == other.fontHeightFactor &&
          fontFamily == other.fontFamily;

  @override
  int get hashCode => Object.hash(fontSize, fontHeightFactor, fontFamily);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TerminalCursorType
// ═══════════════════════════════════════════════════════════════════════════════

enum TerminalCursorType {
  block,
  underline,
  verticalBar,
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TerminalTheme
// ═══════════════════════════════════════════════════════════════════════════════

class TerminalTheme {
  final Color foreground;
  final Color background;
  final Color cursor;
  final Color selection;

  /// The 16 named ANSI colors (0-15).
  final List<Color> colors;

  const TerminalTheme({
    required this.foreground,
    required this.background,
    required this.cursor,
    required this.selection,
    required this.colors,
  });
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Default themes
// ═══════════════════════════════════════════════════════════════════════════════

abstract final class TerminalThemes {
  static const defaultTheme = TerminalTheme(
    foreground: Color(0xFFCCCCCC),
    background: Color(0xFF1A1A1A),
    cursor: Color(0xFFCCCCCC),
    selection: Color(0x80FFFFFF),
    colors: [
      // Normal colors (0-7)
      Color(0xFF000000), // Black
      Color(0xFFCC0000), // Red
      Color(0xFF00CC00), // Green
      Color(0xFFCCCC00), // Yellow
      Color(0xFF0000CC), // Blue
      Color(0xFFCC00CC), // Magenta
      Color(0xFF00CCCC), // Cyan
      Color(0xFFCCCCCC), // White
      // Bright colors (8-15)
      Color(0xFF555555), // Bright Black
      Color(0xFFFF5555), // Bright Red
      Color(0xFF55FF55), // Bright Green
      Color(0xFFFFFF55), // Bright Yellow
      Color(0xFF5555FF), // Bright Blue
      Color(0xFFFF55FF), // Bright Magenta
      Color(0xFF55FFFF), // Bright Cyan
      Color(0xFFFFFFFF), // Bright White
    ],
  );

  /// Whiteout theme for light terminals.
  static const whiteOnBlack = TerminalTheme(
    foreground: Color(0xFFFFFFFF),
    background: Color(0xFF000000),
    cursor: Color(0xFFFFFFFF),
    selection: Color(0x40FFFFFF),
    colors: [
      Color(0xFF000000),
      Color(0xFFAA0000),
      Color(0xFF00AA00),
      Color(0xFFAA5500),
      Color(0xFF0000AA),
      Color(0xFFAA00AA),
      Color(0xFF00AAAA),
      Color(0xFFAAAAAA),
      Color(0xFF555555),
      Color(0xFFFF5555),
      Color(0xFF55FF55),
      Color(0xFFFFFF55),
      Color(0xFF5555FF),
      Color(0xFFFF55FF),
      Color(0xFF55FFFF),
      Color(0xFFFFFFFF),
    ],
  );
}

// ═══════════════════════════════════════════════════════════════════════════════
//  256-color palette builder
// ═══════════════════════════════════════════════════════════════════════════════

/// Build the full 256-color palette from a theme's 16 named colors.
List<Color> buildPalette(TerminalTheme theme) {
  final palette = List<Color>.filled(256, const Color(0xFF000000));

  // 0-15: named colors from theme
  for (var i = 0; i < 16 && i < theme.colors.length; i++) {
    palette[i] = theme.colors[i];
  }

  // 16-231: 6×6×6 color cube
  for (var r = 0; r < 6; r++) {
    for (var g = 0; g < 6; g++) {
      for (var b = 0; b < 6; b++) {
        final idx = 16 + 36 * r + 6 * g + b;
        final rv = r == 0 ? 0 : 55 + 40 * r;
        final gv = g == 0 ? 0 : 55 + 40 * g;
        final bv = b == 0 ? 0 : 55 + 40 * b;
        palette[idx] = Color.fromARGB(255, rv, gv, bv);
      }
    }
  }

  // 232-255: grayscale ramp
  for (var i = 0; i < 24; i++) {
    final v = 8 + 10 * i;
    palette[232 + i] = Color.fromARGB(255, v, v, v);
  }

  return palette;
}
