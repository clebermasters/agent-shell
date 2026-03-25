import 'dart:math' show min;
import 'dart:typed_data';

import 'package:shellterm/src/core/buffer/cell_offset.dart';
import 'package:shellterm/src/core/cell.dart';
import 'package:shellterm/src/core/cursor.dart';
import 'package:shellterm/src/utils/circular_buffer.dart';
import 'package:shellterm/src/utils/unicode_v11.dart';

const _cellSize = 4;
const _cellForeground = 0;
const _cellBackground = 1;
const _cellAttributes = 2;
const _cellContent = 3;

class BufferLine with IndexedItem {
  BufferLine(
    this._length, {
    this.isWrapped = false,
  }) : _data = Uint32List(_calcCapacity(_length) * _cellSize);

  int _length;
  Uint32List _data;
  Uint32List get data => _data;

  var isWrapped = false;

  /// Monotonically-increasing generation counter. Incremented on every cell
  /// mutation. The renderer uses this for dirty-line tracking: a line whose
  /// generation differs from the last-painted generation needs repainting.
  int _generation = 0;
  int get generation => _generation;

  int get length => _length;

  final _anchors = <CellAnchor>[];
  List<CellAnchor> get anchors => _anchors;

  @pragma('vm:prefer-inline')
  int getForeground(int index) =>
      _data[index * _cellSize + _cellForeground];

  @pragma('vm:prefer-inline')
  int getBackground(int index) =>
      _data[index * _cellSize + _cellBackground];

  @pragma('vm:prefer-inline')
  int getAttributes(int index) =>
      _data[index * _cellSize + _cellAttributes];

  @pragma('vm:prefer-inline')
  int getContent(int index) =>
      _data[index * _cellSize + _cellContent];

  @pragma('vm:prefer-inline')
  int getCodePoint(int index) =>
      _data[index * _cellSize + _cellContent] & CellContent.codepointMask;

  @pragma('vm:prefer-inline')
  int getWidth(int index) =>
      _data[index * _cellSize + _cellContent] >> CellContent.widthShift;

  void getCellData(int index, CellData cellData) {
    final offset = index * _cellSize;
    cellData.foreground = _data[offset + _cellForeground];
    cellData.background = _data[offset + _cellBackground];
    cellData.flags = _data[offset + _cellAttributes];
    cellData.content = _data[offset + _cellContent];
  }

  CellData createCellData(int index) {
    final cellData = CellData.empty();
    final offset = index * _cellSize;
    _data[offset + _cellForeground] = cellData.foreground;
    _data[offset + _cellBackground] = cellData.background;
    _data[offset + _cellAttributes] = cellData.flags;
    _data[offset + _cellContent] = cellData.content;
    return cellData;
  }

  @pragma('vm:prefer-inline')
  void setForeground(int index, int value) {
    _data[index * _cellSize + _cellForeground] = value;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setBackground(int index, int value) {
    _data[index * _cellSize + _cellBackground] = value;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setAttributes(int index, int value) {
    _data[index * _cellSize + _cellAttributes] = value;
    _generation++;
  }

  @pragma('vm:prefer-inline')
  void setContent(int index, int value) {
    _data[index * _cellSize + _cellContent] = value;
    _generation++;
  }

  void setCodePoint(int index, int char) {
    final width = unicodeV11.wcwidth(char);
    setContent(index, char | (width << CellContent.widthShift));
  }

  void setCell(int index, int char, int width, CursorStyle style) {
    final offset = index * _cellSize;
    _data[offset + _cellForeground] = style.foreground;
    _data[offset + _cellBackground] = style.background;
    _data[offset + _cellAttributes] = style.attrs;
    _data[offset + _cellContent] = char | (width << CellContent.widthShift);
    _generation++;
  }

  void setCellData(int index, CellData cellData) {
    final offset = index * _cellSize;
    _data[offset + _cellForeground] = cellData.foreground;
    _data[offset + _cellBackground] = cellData.background;
    _data[offset + _cellAttributes] = cellData.flags;
    _data[offset + _cellContent] = cellData.content;
    _generation++;
  }

  void eraseCell(int index, CursorStyle style) {
    final offset = index * _cellSize;
    _data[offset + _cellForeground] = style.foreground;
    _data[offset + _cellBackground] = style.background;
    _data[offset + _cellAttributes] = style.attrs;
    _data[offset + _cellContent] = 0;
    _generation++;
  }

  void resetCell(int index) {
    final offset = index * _cellSize;
    _data[offset + _cellForeground] = 0;
    _data[offset + _cellBackground] = 0;
    _data[offset + _cellAttributes] = 0;
    _data[offset + _cellContent] = 0;
    _generation++;
  }

  void eraseRange(int start, int end, CursorStyle style) {
    if (start > 0 && getWidth(start - 1) == 2) {
      eraseCell(start - 1, style);
    }
    if (end < _length && getWidth(end - 1) == 2) {
      eraseCell(end - 1, style);
    }
    end = min(end, _length);
    for (var i = start; i < end; i++) {
      eraseCell(i, style);
    }
  }

  void removeCells(int start, int count, [CursorStyle? style]) {
    assert(start >= 0 && start < _length);
    assert(count >= 0 && start + count <= _length);
    style ??= CursorStyle.empty;
    if (start + count < _length) {
      final moveStart = start * _cellSize;
      final moveEnd = (_length - count) * _cellSize;
      final moveOffset = count * _cellSize;
      for (var i = moveStart; i < moveEnd; i++) {
        _data[i] = _data[i + moveOffset];
      }
    }
    for (var i = _length - count; i < _length; i++) {
      eraseCell(i, style);
    }
    if (start > 0 && getWidth(start - 1) == 2) {
      eraseCell(start - 1, style);
    }
    for (var i = 0; i < _anchors.length; i++) {
      final anchor = _anchors[i];
      if (anchor.x >= start) {
        if (anchor.x < start + count) {
          anchor.dispose();
        } else {
          anchor.reposition(anchor.x - count);
        }
      }
    }
    _generation++;
  }

  void insertCells(int start, int count, [CursorStyle? style]) {
    style ??= CursorStyle.empty;
    if (start > 0 && getWidth(start - 1) == 2) {
      eraseCell(start - 1, style);
    }
    if (start + count < _length) {
      final moveStart = start * _cellSize;
      final moveEnd = (_length - count) * _cellSize;
      final moveOffset = count * _cellSize;
      for (var i = moveEnd - 1; i >= moveStart; i--) {
        _data[i + moveOffset] = _data[i];
      }
    }
    final end = min(start + count, _length);
    for (var i = start; i < end; i++) {
      eraseCell(i, style);
    }
    if (getWidth(_length - 1) == 2) {
      eraseCell(_length - 1, style);
    }
    for (var i = 0; i < _anchors.length; i++) {
      final anchor = _anchors[i];
      if (anchor.x >= start + count) {
        anchor.reposition(anchor.x + count);
        if (anchor.x >= _length) anchor.dispose();
      }
    }
    _generation++;
  }

  void resize(int length) {
    assert(length >= 0);
    if (length == _length) return;
    if (length > _length) {
      final newBufferSize = _calcCapacity(length) * _cellSize;
      if (newBufferSize > _data.length) {
        final newBuffer = Uint32List(newBufferSize);
        newBuffer.setRange(0, _data.length, _data);
        _data = newBuffer;
      }
    }
    _length = length;
    for (var i = 0; i < _anchors.length; i++) {
      final anchor = _anchors[i];
      if (anchor.x > _length) anchor.reposition(_length);
    }
    _generation++;
  }

  int getTrimmedLength([int? cols]) {
    final maxCols = _data.length ~/ _cellSize;
    if (cols == null || cols > maxCols) cols = maxCols;
    if (cols <= 0) return 0;
    for (var i = cols - 1; i >= 0; i--) {
      var codePoint = getCodePoint(i);
      if (codePoint != 0) {
        final lastCellWidth = getWidth(i);
        return i + lastCellWidth;
      }
    }
    return 0;
  }

  void copyFrom(BufferLine src, int srcCol, int dstCol, int len) {
    resize(dstCol + len);
    var srcOffset = srcCol * _cellSize;
    var dstOffset = dstCol * _cellSize;
    for (var i = 0; i < len * _cellSize; i++) {
      _data[dstOffset++] = src._data[srcOffset++];
    }
    _generation++;
  }

  static int _calcCapacity(int length) {
    assert(length >= 0);
    var capacity = 64;
    if (length < 256) {
      while (capacity < length) capacity *= 2;
    } else {
      capacity = 256;
      while (capacity < length) capacity += 32;
    }
    return capacity;
  }

  String getText([int? from, int? to]) {
    if (from == null || from < 0) from = 0;
    if (to == null || to > _length) to = _length;
    final builder = StringBuffer();
    for (var i = from; i < to; i++) {
      final codePoint = getCodePoint(i);
      final width = getWidth(i);
      if (codePoint != 0 && i + width <= to) {
        builder.writeCharCode(codePoint);
      }
    }
    return builder.toString();
  }

  CellAnchor createAnchor(int offset) {
    final anchor = CellAnchor(offset, owner: this);
    _anchors.add(anchor);
    return anchor;
  }

  void dispose() {
    for (final anchor in _anchors) {
      anchor.dispose();
    }
  }

  @override
  String toString() => getText();
}

/// A handle to a cell in a [BufferLine] that tracks its location stably
/// through buffer mutations.
class CellAnchor {
  CellAnchor(int offset, {BufferLine? owner})
      : _offset = offset,
        _owner = owner;

  int _offset;
  int get x => _offset;

  int get y {
    assert(attached);
    return _owner!.index;
  }

  CellOffset get offset {
    assert(attached);
    return CellOffset(_offset, _owner!.index);
  }

  BufferLine? _owner;
  BufferLine? get line => _owner;
  bool get attached => _owner?.attached ?? false;

  void reparent(BufferLine owner, int offset) {
    _owner?._anchors.remove(this);
    _owner = owner;
    _owner?._anchors.add(this);
    _offset = offset;
  }

  void reposition(int offset) => _offset = offset;

  void dispose() {
    _owner?._anchors.remove(this);
    _owner = null;
  }

  @override
  String toString() =>
      attached ? 'CellAnchor($x, $y)' : 'CellAnchor($x, detached)';
}
