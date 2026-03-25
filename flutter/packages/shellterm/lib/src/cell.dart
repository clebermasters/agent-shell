/// Cell storage format and accessors for the terminal buffer.
///
/// Each cell occupies 4 × Uint32 (16 bytes):
///   Word 0 — Content : codepoint[0:20] + width[22:23] + wideCont[24]
///   Word 1 — Foreground : RGB[0:23] + colorType[25:26]
///   Word 2 — Background : RGB[0:23] + colorType[25:26]
///   Word 3 — Attributes : bold[0] faint[1] italic[2] underline[3]
///                          blink[4] inverse[5] invisible[6] strikethrough[7]
library;

import 'dart:typed_data';

// ── Cell layout constants ────────────────────────────────────────────────────

/// Number of Uint32 words per cell.
const int kCellSize = 4;
const int kCellContent = 0;
const int kCellForeground = 1;
const int kCellBackground = 2;
const int kCellAttributes = 3;

// ── Content word (word 0) ────────────────────────────────────────────────────

/// Helpers for packing/unpacking the content word.
abstract final class CellContent {
  /// Mask for 21-bit Unicode codepoint (bits 0-20).
  static const int codepointMask = 0x1FFFFF;

  /// Bit offset for character display width (bits 22-23).
  static const int widthShift = 22;
  static const int widthMask = 0x3;

  /// Bit flag: this cell is the trailing half of a wide character.
  static const int wideContFlag = 1 << 24;

  @pragma('vm:prefer-inline')
  static int codepoint(int content) => content & codepointMask;

  @pragma('vm:prefer-inline')
  static int width(int content) => (content >> widthShift) & widthMask;

  @pragma('vm:prefer-inline')
  static bool isWideCont(int content) => content & wideContFlag != 0;

  @pragma('vm:prefer-inline')
  static int pack(int codepoint, int width, {bool wideCont = false}) {
    return (codepoint & codepointMask) |
        ((width & widthMask) << widthShift) |
        (wideCont ? wideContFlag : 0);
  }
}

// ── Color words (words 1 & 2) ────────────────────────────────────────────────

/// Color type stored in bits 25-26 of foreground/background words.
enum ColorType {
  /// Terminal default color.
  defaultColor, // 0
  /// Named ANSI color (0-15).
  named, // 1
  /// 256-color palette index.
  palette, // 2
  /// 24-bit truecolor RGB.
  truecolor, // 3
}

abstract final class CellColor {
  static const int rgbMask = 0xFFFFFF;
  static const int typeShift = 25;
  static const int typeMask = 0x3;

  @pragma('vm:prefer-inline')
  static int rgb(int colorWord) => colorWord & rgbMask;

  @pragma('vm:prefer-inline')
  static ColorType type(int colorWord) =>
      ColorType.values[(colorWord >> typeShift) & typeMask];

  @pragma('vm:prefer-inline')
  static int index(int colorWord) => colorWord & 0xFF;

  @pragma('vm:prefer-inline')
  static int pack(ColorType type, int value) {
    return (value & rgbMask) | (type.index << typeShift);
  }

  /// Encode a default-color word (zero).
  static const int defaultFg = 0;
  static const int defaultBg = 0;
}

// ── Attribute flags (word 3) ─────────────────────────────────────────────────

abstract final class CellAttr {
  static const int bold = 1 << 0;
  static const int faint = 1 << 1;
  static const int italic = 1 << 2;
  static const int underline = 1 << 3;
  static const int blink = 1 << 4;
  static const int inverse = 1 << 5;
  static const int invisible = 1 << 6;
  static const int strikethrough = 1 << 7;

  @pragma('vm:prefer-inline')
  static bool has(int attrs, int flag) => attrs & flag != 0;
}

// ── Mutable cell data for bulk reads ─────────────────────────────────────────

/// Reusable mutable container read from a [BufferLine] via [getCellData].
///
/// Avoids per-cell object allocation — one instance is reused across iterations.
class CellData {
  int content = 0;
  int foreground = 0;
  int background = 0;
  int attributes = 0;

  CellData();

  CellData.empty();

  @pragma('vm:prefer-inline')
  int get codepoint => CellContent.codepoint(content);

  @pragma('vm:prefer-inline')
  int get charWidth => CellContent.width(content);

  @pragma('vm:prefer-inline')
  bool get isWideCont => CellContent.isWideCont(content);

  @pragma('vm:prefer-inline')
  ColorType get fgType => CellColor.type(foreground);

  @pragma('vm:prefer-inline')
  ColorType get bgType => CellColor.type(background);

  @pragma('vm:prefer-inline')
  int get fgRgb => CellColor.rgb(foreground);

  @pragma('vm:prefer-inline')
  int get bgRgb => CellColor.rgb(background);

  @pragma('vm:prefer-inline')
  bool get isBold => CellAttr.has(attributes, CellAttr.bold);

  @pragma('vm:prefer-inline')
  bool get isFaint => CellAttr.has(attributes, CellAttr.faint);

  @pragma('vm:prefer-inline')
  bool get isItalic => CellAttr.has(attributes, CellAttr.italic);

  @pragma('vm:prefer-inline')
  bool get isUnderline => CellAttr.has(attributes, CellAttr.underline);

  @pragma('vm:prefer-inline')
  bool get isBlink => CellAttr.has(attributes, CellAttr.blink);

  @pragma('vm:prefer-inline')
  bool get isInverse => CellAttr.has(attributes, CellAttr.inverse);

  @pragma('vm:prefer-inline')
  bool get isInvisible => CellAttr.has(attributes, CellAttr.invisible);

  @pragma('vm:prefer-inline')
  bool get isStrikethrough => CellAttr.has(attributes, CellAttr.strikethrough);

  /// Hash combining all 4 words — used for render cache keying.
  @pragma('vm:prefer-inline')
  int get styleHash => foreground ^ background ^ attributes;

  @pragma('vm:prefer-inline')
  void readFrom(Uint32List data, int cellIndex) {
    final offset = cellIndex * kCellSize;
    content = data[offset + kCellContent];
    foreground = data[offset + kCellForeground];
    background = data[offset + kCellBackground];
    attributes = data[offset + kCellAttributes];
  }
}
