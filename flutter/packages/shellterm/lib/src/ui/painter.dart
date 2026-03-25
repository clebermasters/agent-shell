import 'dart:ui';
import 'package:flutter/painting.dart';

import 'package:shellterm/src/ui/palette_builder.dart';
import 'package:shellterm/src/ui/paragraph_cache.dart';
import 'package:shellterm/shellterm.dart';

/// Encapsulates the logic for painting various terminal elements.
class TerminalPainter {
  TerminalPainter({
    required TerminalTheme theme,
    required TerminalStyle textStyle,
    required TextScaler textScaler,
  })  : _textStyle = textStyle,
        _theme = theme,
        _textScaler = textScaler;

  /// A lookup table from terminal colors to Flutter colors.
  late var _colorPalette = PaletteBuilder(_theme).build();

  /// Size of each character in the terminal.
  late var _cellSize = _measureCharSize();

  /// The cached for cells in the terminal. Should be cleared when the same
  /// cell no longer produces the same visual output. For example, when
  /// [_textStyle] is changed, or when the system font changes.
  final _paragraphCache = ParagraphCache(10240);

  TerminalStyle get textStyle => _textStyle;
  TerminalStyle _textStyle;
  set textStyle(TerminalStyle value) {
    if (value == _textStyle) return;
    _textStyle = value;
    _cellSize = _measureCharSize();
    _paragraphCache.clear();
  }

  TextScaler get textScaler => _textScaler;
  TextScaler _textScaler = TextScaler.linear(1.0);
  set textScaler(TextScaler value) {
    if (value == _textScaler) return;
    _textScaler = value;
    _cellSize = _measureCharSize();
    _paragraphCache.clear();
  }

  TerminalTheme get theme => _theme;
  TerminalTheme _theme;
  set theme(TerminalTheme value) {
    if (value == _theme) return;
    _theme = value;
    _colorPalette = PaletteBuilder(value).build();
    _paragraphCache.clear();
  }

  Size _measureCharSize() {
    const test = 'mmmmmmmmmm';

    final textStyle = _textStyle.toTextStyle();
    final builder = ParagraphBuilder(textStyle.getParagraphStyle());
    builder.pushStyle(
      textStyle.getTextStyle(textScaler: _textScaler),
    );
    builder.addText(test);

    final paragraph = builder.build();
    paragraph.layout(ParagraphConstraints(width: double.infinity));

    final result = Size(
      paragraph.maxIntrinsicWidth / test.length,
      paragraph.height,
    );

    paragraph.dispose();
    return result;
  }

  /// The size of each character in the terminal.
  Size get cellSize => _cellSize;

  /// When the set of font available to the system changes, call this method to
  /// clear cached state related to font rendering.
  void clearFontCache() {
    _cellSize = _measureCharSize();
    _paragraphCache.clear();
  }

  /// Paints the cursor based on the current cursor type.
  void paintCursor(
    Canvas canvas,
    Offset offset, {
    required TerminalCursorType cursorType,
    bool hasFocus = true,
  }) {
    final paint = Paint()
      ..color = _theme.cursor
      ..strokeWidth = 1;

    if (!hasFocus) {
      paint.style = PaintingStyle.stroke;
      canvas.drawRect(offset & _cellSize, paint);
      return;
    }

    switch (cursorType) {
      case TerminalCursorType.block:
        paint.style = PaintingStyle.fill;
        canvas.drawRect(offset & _cellSize, paint);
        return;
      case TerminalCursorType.underline:
        return canvas.drawLine(
          Offset(offset.dx, _cellSize.height - 1),
          Offset(offset.dx + _cellSize.width, _cellSize.height - 1),
          paint,
        );
      case TerminalCursorType.verticalBar:
        return canvas.drawLine(
          Offset(offset.dx, 0),
          Offset(offset.dx, _cellSize.height),
          paint,
        );
    }
  }

  @pragma('vm:prefer-inline')
  void paintHighlight(Canvas canvas, Offset offset, int length, Color color) {
    final endOffset =
        offset.translate(length * _cellSize.width, _cellSize.height);

    final paint = Paint()
      ..color = color
      ..strokeWidth = 1;

    canvas.drawRect(
      Rect.fromPoints(offset, endOffset),
      paint,
    );
  }

  /// Whether to use run-based rendering that enables ligatures.
  /// When true, adjacent cells with identical style are grouped into text runs
  /// and rendered as a single [Paragraph], allowing the text shaper to produce
  /// ligatures (e.g. `==`, `=>`, `!=`, `->`, `>=`, `<=`).
  bool enableLigatures = false;

  /// Paints [line] to [canvas] at [offset]. The x offset of [offset] is usually
  /// 0, and the y offset is the top of the line.
  void paintLine(
    Canvas canvas,
    Offset offset,
    BufferLine line,
  ) {
    if (enableLigatures) {
      _paintLineRuns(canvas, offset, line);
      return;
    }

    final cellData = CellData.empty();
    final cellWidth = _cellSize.width;

    for (var i = 0; i < line.length; i++) {
      line.getCellData(i, cellData);

      final charWidth = cellData.content >> CellContent.widthShift;
      final cellOffset = offset.translate(i * cellWidth, 0);

      paintCell(canvas, cellOffset, cellData);

      if (charWidth == 2) {
        i++;
      }
    }
  }

  /// Paints a line using run-based rendering. Groups adjacent cells with the
  /// same style attributes into text runs and renders each run as a single
  /// [Paragraph], enabling the text shaper to produce ligatures.
  void _paintLineRuns(
    Canvas canvas,
    Offset offset,
    BufferLine line,
  ) {
    final cellData = CellData.empty();
    final cellWidth = _cellSize.width;

    // First pass: paint all backgrounds (per-cell, same as before).
    for (var i = 0; i < line.length; i++) {
      line.getCellData(i, cellData);
      final charWidth = cellData.content >> CellContent.widthShift;
      final cellOffset = offset.translate(i * cellWidth, 0);
      paintCellBackground(canvas, cellOffset, cellData);
      if (charWidth == 2) i++;
    }

    // Second pass: group same-style foreground cells into runs.
    var runStart = 0;
    var runFg = 0;
    var runBg = 0;
    var runFlags = 0;
    final runChars = StringBuffer();
    var inRun = false;

    for (var i = 0; i < line.length; i++) {
      line.getCellData(i, cellData);
      final charCode = cellData.content & CellContent.codepointMask;
      final charWidth = cellData.content >> CellContent.widthShift;

      // Skip trailing half of wide chars.
      if (charWidth == 0 && charCode == 0) continue;

      // Wide chars (CJK, emoji) occupy 2 cells but render as 1 glyph.
      // Force a run break around them to avoid pixel misalignment.
      final isWide = charWidth == 2;

      final styleSame = inRun &&
          !isWide &&
          cellData.foreground == runFg &&
          cellData.background == runBg &&
          cellData.flags == runFlags;

      if (charCode == 0) {
        // Empty cell: flush current run.
        if (inRun) {
          _flushRun(canvas, offset, runStart, runChars, runFg, runBg, runFlags);
          inRun = false;
        }
        continue;
      }

      if (!styleSame) {
        // Style changed: flush previous run.
        if (inRun) {
          _flushRun(canvas, offset, runStart, runChars, runFg, runBg, runFlags);
        }
        // Start new run.
        runStart = i;
        runFg = cellData.foreground;
        runBg = cellData.background;
        runFlags = cellData.flags;
        runChars.clear();
        inRun = true;
      }

      var char = String.fromCharCode(charCode);
      if (runFlags & CellFlags.underline != 0 && charCode == 0x20) {
        char = String.fromCharCode(0xA0);
      }
      runChars.write(char);

      if (charWidth == 2) {
        // Wide char: flush immediately as a single-char run to keep alignment.
        _flushRun(canvas, offset, runStart, runChars, runFg, runBg, runFlags);
        inRun = false;
        i++;
      }
    }

    // Flush final run.
    if (inRun) {
      _flushRun(canvas, offset, runStart, runChars, runFg, runBg, runFlags);
    }
  }

  /// Flush a text run to the canvas.
  void _flushRun(
    Canvas canvas,
    Offset lineOffset,
    int startCol,
    StringBuffer chars,
    int fg,
    int bg,
    int flags,
  ) {
    final text = chars.toString();
    if (text.isEmpty) return;

    var color = flags & CellFlags.inverse == 0
        ? resolveForegroundColor(fg)
        : resolveBackgroundColor(bg);

    if (flags & CellFlags.faint != 0) {
      color = color.withValues(alpha: 0.5);
    }

    final style = _textStyle.toTextStyle(
      color: color,
      bold: flags & CellFlags.bold != 0,
      italic: flags & CellFlags.italic != 0,
      underline: flags & CellFlags.underline != 0,
    );

    final builder = ParagraphBuilder(style.getParagraphStyle());
    builder.pushStyle(style.getTextStyle(textScaler: _textScaler));
    builder.addText(text);
    final paragraph = builder.build();
    paragraph.layout(const ParagraphConstraints(width: double.infinity));

    final runOffset = lineOffset.translate(startCol * _cellSize.width, 0);
    canvas.drawParagraph(paragraph, runOffset);
    paragraph.dispose();
  }

  @pragma('vm:prefer-inline')
  void paintCell(Canvas canvas, Offset offset, CellData cellData) {
    paintCellBackground(canvas, offset, cellData);
    paintCellForeground(canvas, offset, cellData);
  }

  /// Paints the character in the cell represented by [cellData] to [canvas] at
  /// [offset].
  @pragma('vm:prefer-inline')
  void paintCellForeground(Canvas canvas, Offset offset, CellData cellData) {
    final charCode = cellData.content & CellContent.codepointMask;
    if (charCode == 0) return;

    final cacheKey = cellData.getHash() ^ _textScaler.hashCode;
    var paragraph = _paragraphCache.getLayoutFromCache(cacheKey);

    if (paragraph == null) {
      final cellFlags = cellData.flags;

      var color = cellFlags & CellFlags.inverse == 0
          ? resolveForegroundColor(cellData.foreground)
          : resolveBackgroundColor(cellData.background);

      if (cellData.flags & CellFlags.faint != 0) {
        color = color.withValues(alpha: 0.5);
      }

      final style = _textStyle.toTextStyle(
        color: color,
        bold: cellFlags & CellFlags.bold != 0,
        italic: cellFlags & CellFlags.italic != 0,
        underline: cellFlags & CellFlags.underline != 0,
      );

      // Flutter does not draw an underline below a space which is not between
      // other regular characters. As only single characters are drawn, this
      // will never produce an underline below a space in the terminal. As a
      // workaround the regular space CodePoint 0x20 is replaced with
      // the CodePoint 0xA0. This is a non breaking space and a underline can be
      // drawn below it.
      var char = String.fromCharCode(charCode);
      if (cellFlags & CellFlags.underline != 0 && charCode == 0x20) {
        char = String.fromCharCode(0xA0);
      }

      paragraph = _paragraphCache.performAndCacheLayout(
        char,
        style,
        _textScaler,
        cacheKey,
      );
    }

    canvas.drawParagraph(paragraph, offset);
  }

  /// Paints the background of a cell represented by [cellData] to [canvas] at
  /// [offset].
  @pragma('vm:prefer-inline')
  void paintCellBackground(Canvas canvas, Offset offset, CellData cellData) {
    late Color color;
    final colorType = cellData.background & CellColor.typeMask;

    if (cellData.flags & CellFlags.inverse != 0) {
      color = resolveForegroundColor(cellData.foreground);
    } else if (colorType == CellColor.normal) {
      return;
    } else {
      color = resolveBackgroundColor(cellData.background);
    }

    final paint = Paint()..color = color;
    final doubleWidth = cellData.content >> CellContent.widthShift == 2;
    final widthScale = doubleWidth ? 2 : 1;
    final size = Size(_cellSize.width * widthScale + 1, _cellSize.height);
    canvas.drawRect(offset & size, paint);
  }

  /// Get the effective foreground color for a cell from information encoded in
  /// [cellColor].
  @pragma('vm:prefer-inline')
  Color resolveForegroundColor(int cellColor) {
    final colorType = cellColor & CellColor.typeMask;
    final colorValue = cellColor & CellColor.valueMask;

    switch (colorType) {
      case CellColor.normal:
        return _theme.foreground;
      case CellColor.named:
      case CellColor.palette:
        return _colorPalette[colorValue];
      case CellColor.rgb:
      default:
        return Color(colorValue | 0xFF000000);
    }
  }

  /// Get the effective background color for a cell from information encoded in
  /// [cellColor].
  @pragma('vm:prefer-inline')
  Color resolveBackgroundColor(int cellColor) {
    final colorType = cellColor & CellColor.typeMask;
    final colorValue = cellColor & CellColor.valueMask;

    switch (colorType) {
      case CellColor.normal:
        return _theme.background;
      case CellColor.named:
      case CellColor.palette:
        return _colorPalette[colorValue];
      case CellColor.rgb:
      default:
        return Color(colorValue | 0xFF000000);
    }
  }
}
