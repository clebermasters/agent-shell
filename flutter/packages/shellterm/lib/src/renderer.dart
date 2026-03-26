/// High-performance terminal renderer.
///
/// Uses per-cell [Paragraph] rendering with aggressive LRU caching and
/// per-line [Picture] caching with generation-based dirty tracking.
///
/// All text is rendered via Flutter's native text engine, which handles
/// device pixel ratio automatically — text is always sharp at any DPI.
///
/// Performance strategy:
///   1. Per-line dirty tracking: only changed lines are repainted
///   2. Per-line Picture cache: unchanged lines = one drawPicture() call
///   3. Per-cell Paragraph LRU cache: identical cells reuse laid-out text
///   4. Background color runs: consecutive same-color cells merged into one rect
library;

import 'dart:collection';
import 'dart:math' show max, min;
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart' show ScrollPosition;

import 'buffer.dart';
import 'cell.dart';
import 'controller.dart';
import 'terminal.dart';
import 'theme.dart';

// ═══════════════════════════════════════════════════════════════════════════════
//  Line cache entry — stores a recorded Picture per BufferLine
// ═══════════════════════════════════════════════════════════════════════════════

class _LineCache {
  final int generation;
  final ui.Picture picture;
  _LineCache(this.generation, this.picture);
  void dispose() => picture.dispose();
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Paragraph LRU cache — caches laid-out single-character Paragraphs
// ═══════════════════════════════════════════════════════════════════════════════

class _ParagraphCache {
  static const int _maxSize = 8192;
  final _cache = LinkedHashMap<int, ui.Paragraph>();

  ui.Paragraph? get(int key) {
    final val = _cache.remove(key);
    if (val != null) {
      _cache[key] = val; // Move to end (most recently used)
    }
    return val;
  }

  void put(int key, ui.Paragraph paragraph) {
    _cache[key] = paragraph;
    while (_cache.length > _maxSize) {
      _cache.remove(_cache.keys.first)?.dispose();
    }
  }

  void clear() {
    for (final p in _cache.values) {
      p.dispose();
    }
    _cache.clear();
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  RenderTerminal — the custom RenderBox
// ═══════════════════════════════════════════════════════════════════════════════

class RenderTerminal extends RenderBox with RelayoutWhenSystemFontsChangeMixin {
  RenderTerminal({
    required Terminal terminal,
    required TerminalStyle style,
    required TerminalTheme theme,
    TerminalController? controller,
    TerminalCursorType cursorType = TerminalCursorType.block,
    bool alwaysShowCursor = true,
    ViewportOffset? offset,
    EdgeInsets padding = EdgeInsets.zero,
  })  : _terminal = terminal,
        _style = style,
        _theme = theme,
        _controller = controller,
        _cursorType = cursorType,
        _alwaysShowCursor = alwaysShowCursor,
        _offset = offset,
        _padding = padding {
    _palette = buildPalette(theme);
  }

  Terminal _terminal;
  TerminalStyle _style;
  TerminalTheme _theme;
  TerminalController? _controller;
  TerminalCursorType _cursorType;
  bool _alwaysShowCursor;
  ViewportOffset? _offset;
  EdgeInsets _padding;

  late List<ui.Color> _palette;

  // Cell dimensions (computed from font metrics — in logical pixels)
  double _cellWidth = 0;
  double _cellHeight = 0;

  // Line cache: Picture per BufferLine, keyed by identity + generation
  final _lineCache = LinkedHashMap<BufferLine, _LineCache>();
  static const _lineCacheMax = 1024;

  // Paragraph cache for individual cells
  final _paraCache = _ParagraphCache();

  // Reusable CellData to avoid allocation during paint
  final _cellData = CellData();

  // ── Property setters ──────────────────────────────────────────────────

  set terminal(Terminal value) {
    if (_terminal == value) return;
    _terminal = value;
    markNeedsPaint();
  }

  set style(TerminalStyle value) {
    if (_style == value) return;
    _style = value;
    _cellWidth = 0; // Force remeasure
    _paraCache.clear();
    _clearLineCache();
    markNeedsLayout();
  }

  set theme(TerminalTheme value) {
    if (_theme == value) return;
    _theme = value;
    _palette = buildPalette(value);
    _paraCache.clear();
    _clearLineCache();
    markNeedsPaint();
  }

  set controller(TerminalController? value) {
    _controller = value;
    markNeedsPaint();
  }

  set cursorType(TerminalCursorType value) {
    _cursorType = value;
    markNeedsPaint();
  }

  set alwaysShowCursor(bool value) {
    _alwaysShowCursor = value;
    markNeedsPaint();
  }

  set offset(ViewportOffset? value) {
    _offset = value;
    markNeedsPaint();
  }

  set padding(EdgeInsets value) {
    _padding = value;
    markNeedsLayout();
  }

  double get cellWidth => _cellWidth;
  double get cellHeight => _cellHeight;

  @override
  bool get isRepaintBoundary => true;

  // ── Layout ─────────────────────────────────────────────────────────────

  @override
  void performLayout() {
    size = constraints.biggest;

    if (_cellWidth == 0) {
      _measureCellSize();
    }
  }

  void _measureCellSize() {
    final builder = ui.ParagraphBuilder(ui.ParagraphStyle(
      fontFamily: _style.fontFamily,
      fontSize: _style.fontSize,
    ))
      ..addText('M');
    final para = builder.build()
      ..layout(const ui.ParagraphConstraints(width: double.infinity));
    _cellWidth = para.maxIntrinsicWidth;
    _cellHeight = para.height * _style.fontHeightFactor;
    para.dispose();
  }

  // ── Paint ──────────────────────────────────────────────────────────────

  @override
  void paint(PaintingContext context, Offset globalOffset) {
    if (_cellWidth == 0 || _cellHeight == 0) return;

    final canvas = context.canvas;
    final buffer = _terminal.buffer;
    final scrollBack = buffer.scrollBack;
    final viewCols = _terminal.viewWidth;

    final contentOffset = globalOffset + _padding.topLeft;

    // Background fill
    canvas.drawRect(
      globalOffset & size,
      ui.Paint()..color = _theme.background,
    );

    // Determine scroll offset and clamp to valid range
    final scrollOffset = _offset is ScrollPosition
        ? (_offset as ScrollPosition).pixels
        : 0.0;
    final rawFirstVisible = (scrollOffset / _cellHeight).floor();
    final maxFirstRow = max(0, buffer.lines.length - _terminal.viewHeight);
    final firstVisibleRow = rawFirstVisible.clamp(0, maxFirstRow);
    final visibleRowCount =
        ((size.height - _padding.vertical) / _cellHeight).ceil() + 1;

    // Paint visible lines
    for (var viewRow = 0; viewRow < visibleRowCount; viewRow++) {
      final bufferRow = scrollBack + firstVisibleRow + viewRow;
      if (bufferRow < 0 || bufferRow >= buffer.lines.length) continue;

      final line = buffer.lines[bufferRow];
      final y = contentOffset.dy + viewRow * _cellHeight -
          (scrollOffset % _cellHeight);

      // Check line cache — skip if generation matches
      final cached = _lineCache[line];
      if (cached != null && cached.generation == line.generation) {
        canvas.save();
        canvas.translate(contentOffset.dx, y);
        canvas.drawPicture(cached.picture);
        canvas.restore();
        continue;
      }

      // Cache miss — render line into a Picture
      final recorder = ui.PictureRecorder();
      final lineCanvas = ui.Canvas(recorder,
          ui.Rect.fromLTWH(0, 0, viewCols * _cellWidth, _cellHeight));

      _paintLine(lineCanvas, line, viewCols);

      final picture = recorder.endRecording();

      // Update cache
      cached?.dispose();
      _lineCache[line] = _LineCache(line.generation, picture);
      _evictLineCache();

      canvas.save();
      canvas.translate(contentOffset.dx, y);
      canvas.drawPicture(picture);
      canvas.restore();
    }

    // Cursor
    if (_terminal.cursorVisible || _alwaysShowCursor) {
      _paintCursor(canvas, contentOffset, scrollOffset, firstVisibleRow);
    }

    // Selection highlight
    if (_controller?.hasSelection == true) {
      _paintSelection(
          canvas, contentOffset, scrollOffset, firstVisibleRow, visibleRowCount);
    }
  }

  // ── Line painting ─────────────────────────────────────────────────────
  //
  // Two passes:
  //   Pass 1: background color rects (merged runs of same color)
  //   Pass 2: foreground text (per-cell Paragraph from cache)

  void _paintLine(ui.Canvas canvas, BufferLine line, int viewCols) {
    final cols = min(line.length, viewCols);
    final bgPaint = ui.Paint();

    // ── Pass 1: background runs ─────────────────────────────────────────
    ui.Color? runBg;
    int runStart = 0;

    for (var col = 0; col <= cols; col++) {
      ui.Color bg;
      if (col < cols) {
        line.getCellData(col, _cellData);
        bg = _resolveBgColor(_cellData);
      } else {
        bg = _theme.background; // Sentinel to flush last run
      }

      if (bg != runBg) {
        // Flush previous run if it's not the default background
        if (runBg != null && runBg != _theme.background) {
          bgPaint.color = runBg;
          canvas.drawRect(
            ui.Rect.fromLTWH(
                runStart * _cellWidth, 0, (col - runStart) * _cellWidth, _cellHeight),
            bgPaint,
          );
        }
        runBg = bg;
        runStart = col;
      }
    }

    // ── Pass 2: foreground characters ───────────────────────────────────
    for (var col = 0; col < cols; col++) {
      line.getCellData(col, _cellData);

      // Skip wide-continuation cells and empty/space cells
      if (_cellData.isWideCont) continue;
      final cp = _cellData.codepoint;
      if (cp == 0 || cp == 0x20) continue;

      final fgColor = _resolveFgColor(_cellData);
      final x = col * _cellWidth;

      _paintCellParagraph(canvas, x, cp, fgColor, _cellData);

      // Skip trailing cell of wide character
      if (_cellData.charWidth == 2) col++;
    }
  }

  /// Paint a single cell's foreground character using cached Paragraphs.
  ///
  /// Cache key combines: codepoint + foreground color + attributes.
  /// Flutter's text engine handles device pixel ratio automatically —
  /// Paragraphs are always rendered at the native screen resolution.
  void _paintCellParagraph(
      ui.Canvas canvas, double x, int cp, ui.Color fgColor, CellData cell) {
    // Hash: codepoint(21 bits) + fg color(32 bits) + attrs(8 bits)
    final key = cp ^ (cell.foreground * 31) ^ (cell.attributes * 127);

    var para = _paraCache.get(key);
    if (para == null) {
      final weight = cell.isBold ? ui.FontWeight.bold : ui.FontWeight.normal;
      final fontStyle =
          cell.isItalic ? ui.FontStyle.italic : ui.FontStyle.normal;

      final builder = ui.ParagraphBuilder(ui.ParagraphStyle(
        fontFamily: _style.fontFamily,
        fontSize: _style.fontSize,
        fontWeight: weight,
        fontStyle: fontStyle,
      ))
        ..pushStyle(ui.TextStyle(
          color: fgColor,
          fontFamily: _style.fontFamily,
          fontFamilyFallback: _style.fontFamilyFallback,
          fontSize: _style.fontSize,
          fontWeight: weight,
          fontStyle: fontStyle,
          decoration: cell.isUnderline ? ui.TextDecoration.underline : null,
          decorationColor: cell.isUnderline ? fgColor : null,
        ))
        ..addText(String.fromCharCode(cp));

      para = builder.build()
        ..layout(ui.ParagraphConstraints(width: _cellWidth * 2));
      _paraCache.put(key, para);
    }

    canvas.drawParagraph(para, ui.Offset(x, 0));
  }

  // ── Color resolution ──────────────────────────────────────────────────

  ui.Color _resolveFgColor(CellData cell) {
    if (cell.isInverse) return _resolveBgColorRaw(cell);
    return _resolveFgColorRaw(cell);
  }

  ui.Color _resolveBgColor(CellData cell) {
    if (cell.isInverse) return _resolveFgColorRaw(cell);
    return _resolveBgColorRaw(cell);
  }

  ui.Color _resolveFgColorRaw(CellData cell) {
    switch (cell.fgType) {
      case ColorType.defaultColor:
        return _theme.foreground;
      case ColorType.named:
        final idx = CellColor.index(cell.foreground);
        final resolvedIdx = (cell.isBold && idx < 8) ? idx + 8 : idx;
        return resolvedIdx < _palette.length
            ? _palette[resolvedIdx]
            : _theme.foreground;
      case ColorType.palette:
        final idx = CellColor.index(cell.foreground);
        return idx < _palette.length ? _palette[idx] : _theme.foreground;
      case ColorType.truecolor:
        return ui.Color(0xFF000000 | cell.fgRgb);
    }
  }

  ui.Color _resolveBgColorRaw(CellData cell) {
    switch (cell.bgType) {
      case ColorType.defaultColor:
        return _theme.background;
      case ColorType.named:
        final idx = CellColor.index(cell.background);
        return idx < _palette.length ? _palette[idx] : _theme.background;
      case ColorType.palette:
        final idx = CellColor.index(cell.background);
        return idx < _palette.length ? _palette[idx] : _theme.background;
      case ColorType.truecolor:
        return ui.Color(0xFF000000 | cell.bgRgb);
    }
  }

  // ── Cursor painting ───────────────────────────────────────────────────

  void _paintCursor(ui.Canvas canvas, Offset contentOffset,
      double scrollOffset, int firstVisibleRow) {
    final cx = _terminal.buffer.cursorX;
    final cy = _terminal.buffer.cursorY;
    final screenRow = cy - firstVisibleRow;

    final x = contentOffset.dx + cx * _cellWidth;
    final y = contentOffset.dy + screenRow * _cellHeight -
        (scrollOffset % _cellHeight);

    final paint = ui.Paint()..color = _theme.cursor;

    switch (_cursorType) {
      case TerminalCursorType.block:
        paint.color = _theme.cursor.withValues(alpha: 0.5);
        canvas.drawRect(
            ui.Rect.fromLTWH(x, y, _cellWidth, _cellHeight), paint);
      case TerminalCursorType.underline:
        paint.strokeWidth = 2;
        canvas.drawLine(
            ui.Offset(x, y + _cellHeight - 1),
            ui.Offset(x + _cellWidth, y + _cellHeight - 1),
            paint);
      case TerminalCursorType.verticalBar:
        paint.strokeWidth = 2;
        canvas.drawLine(
            ui.Offset(x, y), ui.Offset(x, y + _cellHeight), paint);
    }
  }

  // ── Selection painting ────────────────────────────────────────────────

  void _paintSelection(ui.Canvas canvas, Offset contentOffset,
      double scrollOffset, int firstVisibleRow, int visibleRowCount) {
    final sel = _controller;
    if (sel == null || !sel.hasSelection) return;

    final (startCol, startRow) = sel.selectionStart!;
    final (endCol, endRow) = sel.selectionEnd!;

    final paint = ui.Paint()..color = _theme.selection;
    final scrollBack = _terminal.buffer.scrollBack;

    for (var screenRow = 0; screenRow < visibleRowCount; screenRow++) {
      final absRow = scrollBack + firstVisibleRow + screenRow;
      if (absRow < min(startRow, endRow) || absRow > max(startRow, endRow)) {
        continue;
      }

      final y = contentOffset.dy + screenRow * _cellHeight -
          (scrollOffset % _cellHeight);
      final lineStart = absRow == startRow ? startCol : 0;
      final lineEnd = absRow == endRow ? endCol : _terminal.viewWidth;

      canvas.drawRect(
        ui.Rect.fromLTWH(
          contentOffset.dx + lineStart * _cellWidth,
          y,
          (lineEnd - lineStart) * _cellWidth,
          _cellHeight,
        ),
        paint,
      );
    }
  }

  // ── Cache management ──────────────────────────────────────────────────

  void _evictLineCache() {
    while (_lineCache.length > _lineCacheMax) {
      final oldest = _lineCache.keys.first;
      _lineCache.remove(oldest)?.dispose();
    }
  }

  void _clearLineCache() {
    for (final entry in _lineCache.values) {
      entry.dispose();
    }
    _lineCache.clear();
  }

  @override
  void dispose() {
    _clearLineCache();
    _paraCache.clear();
    super.dispose();
  }
}
