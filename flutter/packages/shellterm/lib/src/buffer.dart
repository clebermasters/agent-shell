/// Terminal buffer: circular line storage, per-line cell data, and all
/// cursor/erase/scroll/reflow operations.
library;

import 'dart:math' show max, min;
import 'dart:typed_data';

import 'cell.dart';
import 'unicode.dart';

// ═══════════════════════════════════════════════════════════════════════════════
//  CircularBuffer — power-of-2 capacity, bitmask indexing
// ═══════════════════════════════════════════════════════════════════════════════

/// Efficient ring buffer using `& _mask` instead of `% capacity`.
///
/// On ARM Cortex-A53/A55 (typical budget Android SoCs), a bitwise AND takes
/// 1 cycle vs 20-40 cycles for an integer divide.  Since every line access
/// during scrolling and painting goes through the index operation, this saves
/// significant CPU time on low-power devices.
class CircularBuffer<T> {
  CircularBuffer(int maxLength)
      : _mask = _nextPow2(maxLength) - 1,
        _array = List<T?>.filled(_nextPow2(maxLength), null);

  List<T?> _array;
  int _mask;
  int _head = 0;
  int _length = 0;

  /// Absolute index of the first element since creation.
  int absoluteHead = 0;

  int get length => _length;
  int get maxLength => _mask + 1;

  set maxLength(int value) {
    final newCap = _nextPow2(value);
    if (newCap == _array.length) return;
    final newArray = List<T?>.filled(newCap, null);
    final keep = min(_length, newCap);
    final drop = _length - keep;
    for (var i = 0; i < keep; i++) {
      newArray[i] = _array[(_head + drop + i) & _mask];
    }
    _array = newArray;
    _mask = newCap - 1;
    _head = 0;
    _length = keep;
    absoluteHead += drop;
  }

  @pragma('vm:prefer-inline')
  T operator [](int index) {
    assert(index >= 0 && index < _length);
    return _array[(_head + index) & _mask]!;
  }

  @pragma('vm:prefer-inline')
  void operator []=(int index, T value) {
    assert(index >= 0 && index < _length);
    _array[(_head + index) & _mask] = value;
  }

  /// Push a new element at the end. Returns the evicted element if the buffer
  /// was full, or null otherwise.
  T? push(T value) {
    T? evicted;
    if (_length == maxLength) {
      evicted = _array[_head];
      _array[_head] = null;
      _head = (_head + 1) & _mask;
      absoluteHead++;
      _length--;
    }
    _array[(_head + _length) & _mask] = value;
    _length++;
    return evicted;
  }

  /// Remove and return the last element.
  T? pop() {
    if (_length == 0) return null;
    _length--;
    final idx = (_head + _length) & _mask;
    final val = _array[idx];
    _array[idx] = null;
    return val;
  }

  /// Insert an element at [index], shifting subsequent elements right.
  /// If the buffer is full, the first element is evicted.
  void insert(int index, T value) {
    if (_length == maxLength) {
      // Evict head to make room
      _array[_head] = null;
      _head = (_head + 1) & _mask;
      absoluteHead++;
      _length--;
      if (index > 0) index--;
    }
    // Shift elements from end down to index
    for (var i = _length; i > index; i--) {
      _array[(_head + i) & _mask] = _array[(_head + i - 1) & _mask];
    }
    _array[(_head + index) & _mask] = value;
    _length++;
  }

  /// Remove the element at [index], shifting subsequent elements left.
  T removeAt(int index) {
    final val = this[index];
    for (var i = index; i < _length - 1; i++) {
      _array[(_head + i) & _mask] = _array[(_head + i + 1) & _mask];
    }
    _length--;
    _array[(_head + _length) & _mask] = null;
    return val;
  }

  /// Clear all elements.
  void clear() {
    for (var i = 0; i < _length; i++) {
      _array[(_head + i) & _mask] = null;
    }
    _head = 0;
    _length = 0;
  }

  static int _nextPow2(int v) {
    if (v <= 0) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    return v + 1;
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  BufferLine — one row of terminal cells backed by Uint32List
// ═══════════════════════════════════════════════════════════════════════════════

class BufferLine {
  BufferLine(int cols, {this.isWrapped = false})
      : _length = cols,
        _data = Uint32List(_nextPow2(cols) * kCellSize);

  Uint32List _data;
  int _length;
  bool isWrapped;

  /// Monotonically-increasing dirty counter. The renderer compares this against
  /// its last-painted generation to decide if the line needs repainting.
  int _generation = 0;
  int get generation => _generation;

  int get length => _length;

  // ── Cell accessors ──────────────────────────────────────────────────────

  @pragma('vm:prefer-inline')
  int getContent(int col) => _data[col * kCellSize + kCellContent];

  @pragma('vm:prefer-inline')
  int getForeground(int col) => _data[col * kCellSize + kCellForeground];

  @pragma('vm:prefer-inline')
  int getBackground(int col) => _data[col * kCellSize + kCellBackground];

  @pragma('vm:prefer-inline')
  int getAttributes(int col) => _data[col * kCellSize + kCellAttributes];

  @pragma('vm:prefer-inline')
  int getCodepoint(int col) =>
      CellContent.codepoint(_data[col * kCellSize + kCellContent]);

  @pragma('vm:prefer-inline')
  int getWidth(int col) =>
      CellContent.width(_data[col * kCellSize + kCellContent]);

  @pragma('vm:prefer-inline')
  bool isWideCont(int col) =>
      CellContent.isWideCont(_data[col * kCellSize + kCellContent]);

  /// Read all 4 words of a cell into a reusable [CellData] object.
  @pragma('vm:prefer-inline')
  void getCellData(int col, CellData out) => out.readFrom(_data, col);

  // ── Cell mutators ──────────────────────────────────────────────────────

  @pragma('vm:prefer-inline')
  void setCell(int col, int content, int fg, int bg, int attrs) {
    final o = col * kCellSize;
    _data[o + kCellContent] = content;
    _data[o + kCellForeground] = fg;
    _data[o + kCellBackground] = bg;
    _data[o + kCellAttributes] = attrs;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setContent(int col, int content) {
    _data[col * kCellSize + kCellContent] = content;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setForeground(int col, int fg) {
    _data[col * kCellSize + kCellForeground] = fg;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setBackground(int col, int bg) {
    _data[col * kCellSize + kCellBackground] = bg;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setAttributes(int col, int attrs) {
    _data[col * kCellSize + kCellAttributes] = attrs;
    _generation++;
  }

  /// Erase a cell to default state.
  @pragma('vm:prefer-inline')
  void eraseCell(int col, [int bg = 0]) {
    final o = col * kCellSize;
    _data[o + kCellContent] = 0;
    _data[o + kCellForeground] = 0;
    _data[o + kCellBackground] = bg;
    _data[o + kCellAttributes] = 0;
    _generation++;
  }

  /// Erase columns [start, end) to default state.
  void eraseRange(int start, int end, [int bg = 0]) {
    for (var i = start; i < end && i < _length; i++) {
      eraseCell(i, bg);
    }
  }

  /// Erase the entire line.
  void clear([int bg = 0]) => eraseRange(0, _length, bg);

  /// Copy cells from [src] columns [srcStart, srcStart+count) to [dstStart].
  void copyCells(BufferLine src, int srcStart, int dstStart, int count) {
    for (var i = 0; i < count; i++) {
      final si = (srcStart + i) * kCellSize;
      final di = (dstStart + i) * kCellSize;
      _data[di + kCellContent] = src._data[si + kCellContent];
      _data[di + kCellForeground] = src._data[si + kCellForeground];
      _data[di + kCellBackground] = src._data[si + kCellBackground];
      _data[di + kCellAttributes] = src._data[si + kCellAttributes];
    }
    _generation++;
  }

  /// Insert [count] blank cells at [col], shifting right. Cells falling
  /// off the right edge are lost.
  void insertCells(int col, int count, [int bg = 0]) {
    if (col >= _length) return;
    // Shift right
    for (var i = _length - 1; i >= col + count; i--) {
      final src = (i - count) * kCellSize;
      final dst = i * kCellSize;
      _data[dst] = _data[src];
      _data[dst + 1] = _data[src + 1];
      _data[dst + 2] = _data[src + 2];
      _data[dst + 3] = _data[src + 3];
    }
    // Clear inserted cells
    for (var i = col; i < col + count && i < _length; i++) {
      eraseCell(i, bg);
    }
  }

  /// Delete [count] cells at [col], shifting left. New cells on the right
  /// are filled with blanks.
  void deleteCells(int col, int count, [int bg = 0]) {
    if (col >= _length) return;
    for (var i = col; i < _length; i++) {
      if (i + count < _length) {
        final si = (i + count) * kCellSize;
        final di = i * kCellSize;
        _data[di] = _data[si];
        _data[di + 1] = _data[si + 1];
        _data[di + 2] = _data[si + 2];
        _data[di + 3] = _data[si + 3];
      } else {
        eraseCell(i, bg);
      }
    }
    _generation++;
  }

  // ── Resize ──────────────────────────────────────────────────────────────

  /// Resize the line to [newLength] columns, preserving existing data.
  void resize(int newLength) {
    final newCap = _nextPow2(newLength);
    if (newCap * kCellSize > _data.length) {
      final newData = Uint32List(newCap * kCellSize);
      final copyLen = min(_length, newLength) * kCellSize;
      for (var i = 0; i < copyLen; i++) {
        newData[i] = _data[i];
      }
      _data = newData;
    }
    // Clear new cells if growing
    if (newLength > _length) {
      for (var i = _length; i < newLength; i++) {
        eraseCell(i);
      }
    }
    _length = newLength;
    _generation++;
  }

  // ── Text extraction ────────────────────────────────────────────────────

  /// Extract the text content of this line as a [String].
  /// Skips wide-continuation cells and trailing whitespace.
  String getText({int start = 0, int? end}) {
    end ??= _length;
    end = min(end, _length);
    final buf = StringBuffer();
    for (var i = start; i < end; i++) {
      final content = _data[i * kCellSize + kCellContent];
      if (CellContent.isWideCont(content)) continue;
      final cp = CellContent.codepoint(content);
      if (cp == 0) {
        buf.writeCharCode(0x20); // Null → space
      } else {
        buf.writeCharCode(cp);
      }
    }
    // Trim trailing spaces
    var s = buf.toString();
    var trimEnd = s.length;
    while (trimEnd > 0 && s.codeUnitAt(trimEnd - 1) == 0x20) {
      trimEnd--;
    }
    return s.substring(0, trimEnd);
  }

  static int _nextPow2(int v) {
    if (v <= 0) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    return v + 1;
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  BufferLines — lightweight indexable view over CircularBuffer<BufferLine>
// ═══════════════════════════════════════════════════════════════════════════════

/// Provides `[]` and `.length` for the selection overlay API requirement:
///   `terminal.buffer.lines[i].getText()`
class BufferLines {
  final CircularBuffer<BufferLine> _storage;
  BufferLines(this._storage);

  int get length => _storage.length;

  @pragma('vm:prefer-inline')
  BufferLine operator [](int index) => _storage[index];
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Buffer — terminal screen buffer with all cursor/erase/scroll operations
// ═══════════════════════════════════════════════════════════════════════════════

class Buffer {
  Buffer({
    required int cols,
    required int rows,
    int maxLines = 1000,
    this.isAlt = false,
  })  : _cols = cols,
        _rows = rows,
        _lines = CircularBuffer<BufferLine>(maxLines) {
    // Pre-fill visible area with empty lines
    for (var i = 0; i < rows; i++) {
      _lines.push(BufferLine(cols));
    }
    _scrollTop = 0;
    _scrollBottom = rows - 1;
    _tabStops = _initTabStops(cols);
  }

  final bool isAlt;
  CircularBuffer<BufferLine> _lines;
  int _cols;
  int _rows;
  int _cursorX = 0;
  int _cursorY = 0;
  int _savedCursorX = 0;
  int _savedCursorY = 0;
  int _savedFg = 0;
  int _savedBg = 0;
  int _savedAttrs = 0;
  int _scrollTop = 0;
  int _scrollBottom = 0;
  Uint8List _tabStops = Uint8List(0);

  // Current cell style (set by SGR sequences)
  int cursorFg = 0;
  int cursorBg = 0;
  int cursorAttrs = 0;

  int get cols => _cols;
  int get rows => _rows;
  int get cursorX => _cursorX;
  int get cursorY => _cursorY;
  int get scrollTop => _scrollTop;
  int get scrollBottom => _scrollBottom;

  set cursorX(int v) => _cursorX = v.clamp(0, _cols - 1);
  set cursorY(int v) => _cursorY = v.clamp(0, _rows - 1);

  /// Public read-only view for the selection overlay.
  late final BufferLines lines = BufferLines(_lines);

  /// Height of the scrollback (lines above the visible viewport).
  int get scrollBack => max(0, _lines.length - _rows);

  /// Absolute line index of the cursor in the circular buffer.
  int get _absoluteCursorLine => scrollBack + _cursorY;

  /// Get the [BufferLine] at absolute buffer index [i].
  @pragma('vm:prefer-inline')
  BufferLine getLine(int i) => _lines[i];

  /// Get the visible line at viewport row [row].
  @pragma('vm:prefer-inline')
  BufferLine getVisibleLine(int row) => _lines[scrollBack + row];

  // ── Tab stops ───────────────────────────────────────────────────────────

  Uint8List _initTabStops(int cols) {
    final stops = Uint8List((cols + 7) >> 3);
    for (var i = 0; i < cols; i += 8) {
      stops[i >> 3] |= 1 << (i & 7);
    }
    return stops;
  }

  bool isTabStop(int col) {
    if (col < 0 || col >= _cols) return false;
    return _tabStops[col >> 3] & (1 << (col & 7)) != 0;
  }

  void setTabStop(int col) {
    if (col < 0 || col >= _cols) return;
    _tabStops[col >> 3] |= 1 << (col & 7);
  }

  void clearTabStop(int col) {
    if (col < 0 || col >= _cols) return;
    _tabStops[col >> 3] &= ~(1 << (col & 7));
  }

  void clearAllTabStops() => _tabStops.fillRange(0, _tabStops.length, 0);

  int nextTabStop(int col) {
    for (var i = col + 1; i < _cols; i++) {
      if (isTabStop(i)) return i;
    }
    return _cols - 1;
  }

  int prevTabStop(int col) {
    for (var i = col - 1; i >= 0; i--) {
      if (isTabStop(i)) return i;
    }
    return 0;
  }

  // ── Cursor save/restore ─────────────────────────────────────────────────

  void saveCursor() {
    _savedCursorX = _cursorX;
    _savedCursorY = _cursorY;
    _savedFg = cursorFg;
    _savedBg = cursorBg;
    _savedAttrs = cursorAttrs;
  }

  void restoreCursor() {
    _cursorX = _savedCursorX;
    _cursorY = _savedCursorY;
    cursorFg = _savedFg;
    cursorBg = _savedBg;
    cursorAttrs = _savedAttrs;
  }

  // ── Character writing ──────────────────────────────────────────────────

  /// Write a character at the cursor position with current style.
  /// Handles wide characters and auto-wrap.
  void writeChar(int codepoint, {bool autoWrap = true, bool insertMode = false}) {
    final w = unicodeWidth(codepoint);
    if (w <= 0) return; // Zero-width or control — skip

    final line = _lines[_absoluteCursorLine];

    // Auto-wrap: if cursor is at right margin and we need space
    if (_cursorX + w > _cols) {
      if (autoWrap) {
        line.isWrapped = false;
        _lineFeed();
        _cursorX = 0;
        final nextLine = _lines[_absoluteCursorLine];
        nextLine.isWrapped = true;
        _writeCharAt(nextLine, _cursorX, codepoint, w, insertMode);
      }
      return;
    }

    _writeCharAt(line, _cursorX, codepoint, w, insertMode);
  }

  void _writeCharAt(
      BufferLine line, int col, int codepoint, int w, bool insertMode) {
    if (insertMode) {
      line.insertCells(col, w, cursorBg);
    }

    final content = CellContent.pack(codepoint, w);
    line.setCell(col, content, cursorFg, cursorBg, cursorAttrs);

    // Mark trailing cell for wide characters
    if (w == 2 && col + 1 < _cols) {
      line.setCell(col + 1, CellContent.wideContFlag, cursorFg, cursorBg, cursorAttrs);
    }

    _cursorX = min(_cursorX + w, _cols - 1);
  }

  // ── Line feed / carriage return ────────────────────────────────────────

  void _lineFeed() {
    if (_cursorY == _scrollBottom) {
      scrollUp(1);
    } else if (_cursorY < _rows - 1) {
      _cursorY++;
    }
  }

  void lineFeed() => _lineFeed();

  void carriageReturn() => _cursorX = 0;

  void reverseIndex() {
    if (_cursorY == _scrollTop) {
      scrollDown(1);
    } else if (_cursorY > 0) {
      _cursorY--;
    }
  }

  // ── Scroll operations ──────────────────────────────────────────────────

  /// Scroll the scroll region up by [n] lines.
  void scrollUp(int n) {
    n = min(n, _scrollBottom - _scrollTop + 1);
    if (n <= 0) return;

    if (_scrollTop == 0 && _scrollBottom == _rows - 1 && !isAlt) {
      // Optimization: if full-screen scroll, just push new lines
      for (var i = 0; i < n; i++) {
        _lines.push(BufferLine(_cols));
      }
    } else {
      // Scroll region: shift lines up within the region
      final base = scrollBack;
      for (var i = 0; i < n; i++) {
        // Move each line up
        for (var row = _scrollTop; row < _scrollBottom; row++) {
          _lines[base + row] = _lines[base + row + 1];
        }
        _lines[base + _scrollBottom] = BufferLine(_cols);
      }
    }
  }

  /// Scroll the scroll region down by [n] lines.
  void scrollDown(int n) {
    n = min(n, _scrollBottom - _scrollTop + 1);
    if (n <= 0) return;

    final base = scrollBack;
    for (var i = 0; i < n; i++) {
      for (var row = _scrollBottom; row > _scrollTop; row--) {
        _lines[base + row] = _lines[base + row - 1];
      }
      _lines[base + _scrollTop] = BufferLine(_cols);
    }
  }

  // ── Erase operations ──────────────────────────────────────────────────

  /// Erase from cursor to end of line.
  void eraseRight() {
    final line = _lines[_absoluteCursorLine];
    line.eraseRange(_cursorX, _cols, cursorBg);
  }

  /// Erase from start of line to cursor (inclusive).
  void eraseLeft() {
    final line = _lines[_absoluteCursorLine];
    line.eraseRange(0, _cursorX + 1, cursorBg);
  }

  /// Erase entire current line.
  void eraseLine() {
    _lines[_absoluteCursorLine].clear(cursorBg);
  }

  /// Erase from cursor to end of display.
  void eraseBelow() {
    eraseRight();
    final base = scrollBack;
    for (var row = _cursorY + 1; row < _rows; row++) {
      _lines[base + row].clear(cursorBg);
    }
  }

  /// Erase from start of display to cursor.
  void eraseAbove() {
    eraseLeft();
    final base = scrollBack;
    for (var row = 0; row < _cursorY; row++) {
      _lines[base + row].clear(cursorBg);
    }
  }

  /// Erase entire display.
  void eraseDisplay() {
    final base = scrollBack;
    for (var row = 0; row < _rows; row++) {
      _lines[base + row].clear(cursorBg);
    }
  }

  /// Erase [n] characters at cursor position.
  void eraseChars(int n) {
    final line = _lines[_absoluteCursorLine];
    line.eraseRange(_cursorX, min(_cursorX + n, _cols), cursorBg);
  }

  // ── Insert / delete lines ──────────────────────────────────────────────

  void insertLines(int n) {
    if (_cursorY < _scrollTop || _cursorY > _scrollBottom) return;
    n = min(n, _scrollBottom - _cursorY + 1);
    final base = scrollBack;
    for (var i = 0; i < n; i++) {
      for (var row = _scrollBottom; row > _cursorY; row--) {
        _lines[base + row] = _lines[base + row - 1];
      }
      _lines[base + _cursorY] = BufferLine(_cols);
    }
  }

  void deleteLines(int n) {
    if (_cursorY < _scrollTop || _cursorY > _scrollBottom) return;
    n = min(n, _scrollBottom - _cursorY + 1);
    final base = scrollBack;
    for (var i = 0; i < n; i++) {
      for (var row = _cursorY; row < _scrollBottom; row++) {
        _lines[base + row] = _lines[base + row + 1];
      }
      _lines[base + _scrollBottom] = BufferLine(_cols);
    }
  }

  void insertChars(int n) {
    _lines[_absoluteCursorLine].insertCells(_cursorX, n, cursorBg);
  }

  void deleteChars(int n) {
    _lines[_absoluteCursorLine].deleteCells(_cursorX, n, cursorBg);
  }

  // ── Scroll margins ─────────────────────────────────────────────────────

  void setScrollMargins(int top, int bottom) {
    _scrollTop = top.clamp(0, _rows - 1);
    _scrollBottom = bottom.clamp(0, _rows - 1);
    if (_scrollTop >= _scrollBottom) {
      _scrollTop = 0;
      _scrollBottom = _rows - 1;
    }
  }

  void resetScrollMargins() {
    _scrollTop = 0;
    _scrollBottom = _rows - 1;
  }

  // ── Resize with reflow ─────────────────────────────────────────────────

  void resize(int newCols, int newRows) {
    if (newCols == _cols && newRows == _rows) return;

    if (newCols != _cols) {
      _reflow(newCols);
    }

    // ── Adjust row count ──────────────────────────────────────────────────
    //
    // The invariant is the cursor's ABSOLUTE position in the circular
    // buffer (scrollBack + cursorY).  When the viewport shrinks (keyboard
    // appears) or grows (keyboard hides), we keep the cursor on the same
    // buffer line and recompute cursorY from the new scrollBack.
    //
    // This makes shrink+grow an identity: the cursor and content return
    // to exactly where they were.

    // 1. Record cursor's absolute buffer position before changing _rows
    final absCursorLine = scrollBack + _cursorY;

    // 2. Ensure the buffer has enough lines to fill the new viewport.
    //    Only needed when the buffer is short (e.g., fresh terminal).
    final effectiveCols = newCols > 0 ? newCols : _cols;
    while (_lines.length < newRows) {
      _lines.push(BufferLine(effectiveCols));
    }

    // 3. Update dimensions — this changes scrollBack implicitly
    _cols = newCols;
    _rows = newRows;

    // 4. Recompute cursorY so the cursor stays on the same buffer line.
    //    newScrollBack = _lines.length - newRows
    final newScrollBack = scrollBack;
    _cursorY = (absCursorLine - newScrollBack).clamp(0, newRows - 1);

    _cursorX = _cursorX.clamp(0, max(1, newCols) - 1);
    resetScrollMargins();
    _tabStops = _initTabStops(newCols);
  }

  /// Reflow lines when column count changes.
  void _reflow(int newCols) {
    if (isAlt) {
      // Alt buffer: just resize each line, no reflow
      for (var i = 0; i < _lines.length; i++) {
        _lines[i].resize(newCols);
      }
      return;
    }

    // Main buffer: unwrap wrapped lines, then re-wrap at new width
    final unwrapped = <BufferLine>[];
    var i = 0;
    while (i < _lines.length) {
      final first = _lines[i];
      i++;
      // Collect continuation lines
      final parts = <BufferLine>[first];
      while (i < _lines.length && _lines[i].isWrapped) {
        parts.add(_lines[i]);
        i++;
      }

      if (parts.length == 1 && !first.isWrapped) {
        // Single line — just resize
        first.resize(newCols);
        unwrapped.add(first);
      } else {
        // Merge all parts into one logical line
        final totalCols = parts.fold<int>(
            0, (sum, line) => sum + _effectiveLength(line));
        final merged = BufferLine(totalCols);
        var col = 0;
        for (final part in parts) {
          final len = _effectiveLength(part);
          merged.copyCells(part, 0, col, len);
          col += len;
        }

        // Re-wrap at new width
        var offset = 0;
        while (offset < totalCols) {
          final chunkLen = min(newCols, totalCols - offset);
          final chunk = BufferLine(newCols, isWrapped: offset > 0);
          chunk.copyCells(merged, offset, 0, chunkLen);
          unwrapped.add(chunk);
          offset += chunkLen;
        }
        if (unwrapped.isEmpty) {
          unwrapped.add(BufferLine(newCols));
        }
      }
    }

    // Rebuild the circular buffer
    final maxLen = _lines.maxLength;
    _lines = CircularBuffer<BufferLine>(maxLen);
    for (final line in unwrapped) {
      _lines.push(line);
    }
  }

  /// Effective length of a line (excluding trailing empty cells).
  int _effectiveLength(BufferLine line) {
    var len = line.length;
    while (len > 0 && line.getCodepoint(len - 1) == 0) {
      len--;
    }
    return max(len, 1);
  }

  // ── Word boundary detection ────────────────────────────────────────────

  /// Find word boundaries around [col] on the given [line].
  /// Returns (start, end) column indices.
  (int, int) getWordBoundary(BufferLine line, int col) {
    if (col < 0 || col >= line.length) return (col, col);

    final cp = line.getCodepoint(col);
    if (cp == 0 || cp == 0x20) return (col, col + 1);

    final isWord = _isWordChar(cp);
    var start = col;
    var end = col;

    while (start > 0 && _isWordChar(line.getCodepoint(start - 1)) == isWord) {
      start--;
    }
    while (
        end < line.length - 1 &&
        _isWordChar(line.getCodepoint(end + 1)) == isWord) {
      end++;
    }
    return (start, end + 1);
  }

  bool _isWordChar(int cp) {
    if (cp >= 0x30 && cp <= 0x39) return true; // 0-9
    if (cp >= 0x41 && cp <= 0x5A) return true; // A-Z
    if (cp >= 0x61 && cp <= 0x7A) return true; // a-z
    if (cp == 0x5F) return true; // underscore
    if (cp > 0x7F) return true; // non-ASCII treated as word chars
    return false;
  }
}
