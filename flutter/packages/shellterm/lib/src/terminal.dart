/// The central [Terminal] class — ties parser + buffer together and provides
/// the public API consumed by the Flutter app.
///
/// Extends [ChangeNotifier] with microtask-coalesced notifications:
/// multiple `write()` calls in the same frame produce ONE rebuild.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import 'buffer.dart';
import 'cell.dart';
import 'emitter.dart';
import 'handler.dart';
import 'parser.dart';

class Terminal extends ChangeNotifier implements EscapeHandler {
  Terminal({int maxLines = 1000})
      : _mainBuffer = Buffer(cols: 80, rows: 24, maxLines: maxLines),
        _altBuffer = Buffer(cols: 80, rows: 24, maxLines: 24, isAlt: true) {
    _parser = EscapeParser(this);
    _activeBuffer = _mainBuffer;
  }

  late final EscapeParser _parser;
  Buffer _mainBuffer;
  Buffer _altBuffer;
  late Buffer _activeBuffer;

  // ── Public API (matches app contract) ──────────────────────────────────

  int get viewWidth => _activeBuffer.cols;
  int get viewHeight => _activeBuffer.rows;

  /// The active buffer — used by the selection overlay to read lines.
  Buffer get buffer => _activeBuffer;

  /// Called when the terminal generates output (DA responses, cursor reports).
  Function(String)? onOutput;

  /// Called when the terminal is resized.
  Function(int cols, int rows, double pixelWidth, double pixelHeight)? onResize;

  /// Feed VT data from the backend into the terminal.
  ///
  /// Uses microtask coalescing: multiple calls in the same frame produce
  /// ONE [notifyListeners] call, preventing rebuild storms during
  /// high-frequency data arrival (e.g., history hydration).
  void write(String data) {
    _parser.write(data);
    if (!_dirty) {
      _dirty = true;
      scheduleMicrotask(_flush);
    }
  }

  bool _dirty = false;

  void _flush() {
    if (!_dirty) return;
    _dirty = false;
    notifyListeners();
  }

  /// Resize the terminal to [cols] × [rows].
  void resize(int cols, int rows, [double pixelWidth = 0, double pixelHeight = 0]) {
    if (cols <= 0 || rows <= 0) return;
    if (cols == _activeBuffer.cols && rows == _activeBuffer.rows) return;

    _mainBuffer.resize(cols, rows);
    _altBuffer.resize(cols, rows);
    onResize?.call(cols, rows, pixelWidth, pixelHeight);
    notifyListeners();
  }

  // ── Terminal modes ─────────────────────────────────────────────────────

  bool _insertMode = false;
  bool _autoWrapMode = true;
  bool _originMode = false;
  bool _appCursorKeys = false;
  bool _appKeypadMode = false;
  bool _bracketedPasteMode = false;
  bool _altBufferActive = false;
  bool _cursorVisible = true;
  bool _reverseVideo = false;
  int _mouseMode = 0; // 0=off, 9/1000/1002/1003/1006
  int _mouseEncoding = 0; // 0=normal, 1006=SGR

  bool get insertMode => _insertMode;
  bool get autoWrapMode => _autoWrapMode;
  bool get originMode => _originMode;
  bool get appCursorKeys => _appCursorKeys;
  bool get appKeypadMode => _appKeypadMode;
  bool get bracketedPasteMode => _bracketedPasteMode;
  bool get cursorVisible => _cursorVisible;
  bool get reverseVideo => _reverseVideo;
  int get mouseMode => _mouseMode;
  int get mouseEncoding => _mouseEncoding;

  // Charset state — used by shiftIn/shiftOut and designateCharset.
  // Currently stored for protocol correctness; character mapping is TODO.
  int activeCharset = 0; // 0=G0, 1=G1
  final List<int> charsets = [0, 0, 0, 0]; // G0-G3 charset designations

  // Last printed character (for REP — CSI b)
  int _lastChar = 0;

  // ── Input methods ──────────────────────────────────────────────────────

  /// Send a text string as terminal input (e.g., typed characters).
  bool textInput(String text) {
    if (text.isEmpty) return false;
    onOutput?.call(text);
    return true;
  }

  /// Paste text, wrapping in bracketed paste markers if enabled.
  void paste(String text) {
    if (bracketedPasteMode) {
      onOutput?.call(EscapeEmitter.bracketedPaste(text));
    } else {
      onOutput?.call(text);
    }
  }

  // ── EscapeHandler implementation ───────────────────────────────────────

  // Single-byte controls

  @override
  void bell() {
    // No audio on mobile — could trigger haptic feedback
  }

  @override
  void backspace() {
    if (_activeBuffer.cursorX > 0) {
      _activeBuffer.cursorX = _activeBuffer.cursorX - 1;
    }
  }

  @override
  void tab() {
    _activeBuffer.cursorX = _activeBuffer.nextTabStop(_activeBuffer.cursorX);
  }

  @override
  void lineFeed() => _activeBuffer.lineFeed();

  @override
  void carriageReturn() => _activeBuffer.carriageReturn();

  @override
  void shiftOut() => activeCharset = 1;

  @override
  void shiftIn() => activeCharset = 0;

  @override
  void writeChar(int codepoint) {
    _lastChar = codepoint;
    _activeBuffer.writeChar(codepoint,
        autoWrap: _autoWrapMode, insertMode: _insertMode);
  }

  // ESC sequences

  @override
  void saveCursor() => _activeBuffer.saveCursor();

  @override
  void restoreCursor() => _activeBuffer.restoreCursor();

  @override
  void index() => _activeBuffer.lineFeed();

  @override
  void reverseIndex() => _activeBuffer.reverseIndex();

  @override
  void nextLine() {
    _activeBuffer.carriageReturn();
    _activeBuffer.lineFeed();
  }

  @override
  void setTabStop() => _activeBuffer.setTabStop(_activeBuffer.cursorX);

  @override
  void resetState() {
    _insertMode = false;
    _autoWrapMode = true;
    _originMode = false;
    _appCursorKeys = false;
    _appKeypadMode = false;
    _bracketedPasteMode = false;
    _cursorVisible = true;
    _reverseVideo = false;
    _mouseMode = 0;
    _mouseEncoding = 0;
    activeCharset = 0;
    charsets.fillRange(0, 4, 0);
    _activeBuffer.cursorFg = 0;
    _activeBuffer.cursorBg = 0;
    _activeBuffer.cursorAttrs = 0;

    if (_altBufferActive) {
      _altBufferActive = false;
      _activeBuffer = _mainBuffer;
    }

    _mainBuffer.resize(_mainBuffer.cols, _mainBuffer.rows);
    _altBuffer.resize(_altBuffer.cols, _altBuffer.rows);
    notifyListeners();
  }

  @override
  void setAppKeypadMode(bool enabled) => _appKeypadMode = enabled;

  @override
  void designateCharset(int slot, int charset) {
    if (slot >= 0 && slot < 4) {
      charsets[slot] = charset;
    }
  }

  // CSI sequences

  @override
  void setCursorPosition(int row, int col) {
    _activeBuffer.cursorY = _originMode ? row + _activeBuffer.scrollTop : row;
    _activeBuffer.cursorX = col;
  }

  @override
  void cursorUp(int n) =>
      _activeBuffer.cursorY = _activeBuffer.cursorY - n;

  @override
  void cursorDown(int n) =>
      _activeBuffer.cursorY = _activeBuffer.cursorY + n;

  @override
  void cursorForward(int n) =>
      _activeBuffer.cursorX = _activeBuffer.cursorX + n;

  @override
  void cursorBackward(int n) =>
      _activeBuffer.cursorX = _activeBuffer.cursorX - n;

  @override
  void cursorNextLine(int n) {
    _activeBuffer.cursorY = _activeBuffer.cursorY + n;
    _activeBuffer.cursorX = 0;
  }

  @override
  void cursorPrevLine(int n) {
    _activeBuffer.cursorY = _activeBuffer.cursorY - n;
    _activeBuffer.cursorX = 0;
  }

  @override
  void cursorToColumn(int col) => _activeBuffer.cursorX = col;

  @override
  void cursorToRow(int row) =>
      _activeBuffer.cursorY = _originMode ? row + _activeBuffer.scrollTop : row;

  @override
  void eraseInDisplay(int mode) {
    switch (mode) {
      case 0:
        _activeBuffer.eraseBelow();
      case 1:
        _activeBuffer.eraseAbove();
      case 2:
        _activeBuffer.eraseDisplay();
      case 3:
        // Erase scrollback (clear history)
        if (!_activeBuffer.isAlt) {
          _activeBuffer.eraseDisplay();
        }
    }
  }

  @override
  void eraseInLine(int mode) {
    switch (mode) {
      case 0:
        _activeBuffer.eraseRight();
      case 1:
        _activeBuffer.eraseLeft();
      case 2:
        _activeBuffer.eraseLine();
    }
  }

  @override
  void eraseChars(int n) => _activeBuffer.eraseChars(n);

  @override
  void insertLines(int n) => _activeBuffer.insertLines(n);

  @override
  void deleteLines(int n) => _activeBuffer.deleteLines(n);

  @override
  void insertChars(int n) => _activeBuffer.insertChars(n);

  @override
  void deleteChars(int n) => _activeBuffer.deleteChars(n);

  @override
  void scrollUp(int n) => _activeBuffer.scrollUp(n);

  @override
  void scrollDown(int n) => _activeBuffer.scrollDown(n);

  @override
  void setScrollMargins(int top, int bottom) {
    _activeBuffer.setScrollMargins(top, bottom);
    _activeBuffer.cursorX = 0;
    _activeBuffer.cursorY = _originMode ? _activeBuffer.scrollTop : 0;
  }

  @override
  void tabForward(int n) {
    for (var i = 0; i < n; i++) {
      _activeBuffer.cursorX =
          _activeBuffer.nextTabStop(_activeBuffer.cursorX);
    }
  }

  @override
  void tabBackward(int n) {
    for (var i = 0; i < n; i++) {
      _activeBuffer.cursorX =
          _activeBuffer.prevTabStop(_activeBuffer.cursorX);
    }
  }

  @override
  void clearTabStop(int mode) {
    switch (mode) {
      case 0:
        _activeBuffer.clearTabStop(_activeBuffer.cursorX);
      case 3:
        _activeBuffer.clearAllTabStops();
    }
  }

  @override
  void setMode(int mode, bool value) {
    switch (mode) {
      case 4: // IRM — insert/replace mode
        _insertMode = value;
      case 20: // LNM — linefeed/newline mode
        // Auto-CR on LF — not commonly used
        break;
    }
  }

  @override
  void setDecMode(int mode, bool value) {
    switch (mode) {
      case 1: // DECCKM — cursor keys mode
        _appCursorKeys = value;
      case 5: // DECSCNM — reverse video
        _reverseVideo = value;
      case 6: // DECOM — origin mode
        _originMode = value;
        if (value) {
          _activeBuffer.cursorX = 0;
          _activeBuffer.cursorY = _activeBuffer.scrollTop;
        }
      case 7: // DECAWM — auto-wrap mode
        _autoWrapMode = value;
      case 9: // X10 mouse
        _mouseMode = value ? 9 : 0;
      case 25: // DECTCEM — cursor visible
        _cursorVisible = value;
      case 47: // Alt buffer (no save cursor)
        _switchBuffer(value, saveCursor: false);
      case 1000: // VT200 mouse
        _mouseMode = value ? 1000 : 0;
      case 1002: // Button event mouse
        _mouseMode = value ? 1002 : 0;
      case 1003: // Any event mouse
        _mouseMode = value ? 1003 : 0;
      case 1004: // Focus events — not commonly used
        break;
      case 1005: // UTF-8 mouse encoding
        _mouseEncoding = value ? 1005 : 0;
      case 1006: // SGR mouse encoding
        _mouseEncoding = value ? 1006 : 0;
      case 1015: // URXVT mouse encoding
        _mouseEncoding = value ? 1015 : 0;
      case 1047: // Alt buffer
        _switchBuffer(value, saveCursor: false);
      case 1048: // Save/restore cursor
        if (value) {
          _mainBuffer.saveCursor();
        } else {
          _mainBuffer.restoreCursor();
        }
      case 1049: // Alt buffer + save cursor
        _switchBuffer(value, saveCursor: true);
      case 2004: // Bracketed paste
        _bracketedPasteMode = value;
    }
  }

  void _switchBuffer(bool toAlt, {required bool saveCursor}) {
    if (toAlt && !_altBufferActive) {
      if (saveCursor) _mainBuffer.saveCursor();
      _altBufferActive = true;
      _activeBuffer = _altBuffer;
      _altBuffer.eraseDisplay();
    } else if (!toAlt && _altBufferActive) {
      _altBufferActive = false;
      _activeBuffer = _mainBuffer;
      if (saveCursor) _mainBuffer.restoreCursor();
    }
  }

  @override
  void sendDeviceAttributes(int mode) {
    if (mode == 0) {
      onOutput?.call(EscapeEmitter.primaryDA);
    } else {
      onOutput?.call(EscapeEmitter.secondaryDA);
    }
  }

  @override
  void sendDeviceStatus(int mode) {
    if (mode == 5) {
      onOutput?.call(EscapeEmitter.deviceStatusOk);
    }
  }

  @override
  void sendCursorPosition() {
    onOutput?.call(
        EscapeEmitter.cursorPosition(_activeBuffer.cursorY, _activeBuffer.cursorX));
  }

  @override
  void setCharacterProtection(int mode) {
    // DECSCA — not commonly used
  }

  @override
  void setCursorStyle(int style) {
    // CSI SP q — cursor shape. Could be exposed as a property.
  }

  @override
  void repeatChar(int n) {
    if (_lastChar == 0) return;
    for (var i = 0; i < n; i++) {
      writeChar(_lastChar);
    }
  }

  // SGR (Select Graphic Rendition)

  @override
  void resetStyle() {
    _activeBuffer.cursorFg = 0;
    _activeBuffer.cursorBg = 0;
    _activeBuffer.cursorAttrs = 0;
  }

  @override
  void setBold(bool enabled) => _toggleAttr(CellAttr.bold, enabled);

  @override
  void setFaint(bool enabled) => _toggleAttr(CellAttr.faint, enabled);

  @override
  void setItalic(bool enabled) => _toggleAttr(CellAttr.italic, enabled);

  @override
  void setUnderline(bool enabled) => _toggleAttr(CellAttr.underline, enabled);

  @override
  void setBlink(bool enabled) => _toggleAttr(CellAttr.blink, enabled);

  @override
  void setInverse(bool enabled) => _toggleAttr(CellAttr.inverse, enabled);

  @override
  void setInvisible(bool enabled) => _toggleAttr(CellAttr.invisible, enabled);

  @override
  void setStrikethrough(bool enabled) =>
      _toggleAttr(CellAttr.strikethrough, enabled);

  void _toggleAttr(int flag, bool enabled) {
    if (enabled) {
      _activeBuffer.cursorAttrs |= flag;
    } else {
      _activeBuffer.cursorAttrs &= ~flag;
    }
  }

  @override
  void setForegroundColor16(int color) {
    _activeBuffer.cursorFg = CellColor.pack(ColorType.named, color);
  }

  @override
  void setBackgroundColor16(int color) {
    _activeBuffer.cursorBg = CellColor.pack(ColorType.named, color);
  }

  @override
  void setForegroundColor256(int index) {
    _activeBuffer.cursorFg = CellColor.pack(ColorType.palette, index);
  }

  @override
  void setBackgroundColor256(int index) {
    _activeBuffer.cursorBg = CellColor.pack(ColorType.palette, index);
  }

  @override
  void setForegroundColorRgb(int r, int g, int b) {
    _activeBuffer.cursorFg =
        CellColor.pack(ColorType.truecolor, (r << 16) | (g << 8) | b);
  }

  @override
  void setBackgroundColorRgb(int r, int g, int b) {
    _activeBuffer.cursorBg =
        CellColor.pack(ColorType.truecolor, (r << 16) | (g << 8) | b);
  }

  @override
  void resetForeground() => _activeBuffer.cursorFg = 0;

  @override
  void resetBackground() => _activeBuffer.cursorBg = 0;

  // OSC

  @override
  void setTitle(String title) {
    // Could be exposed as a notifier property
  }

  @override
  void setIconName(String name) {
    // Not used in mobile context
  }

  // DCS

  @override
  void dcsHook(List<int> params, List<int> intermediates) {
    // DCS hook — placeholder for Sixel/image protocol
  }

  @override
  void dcsPut(int byte) {
    // DCS put — placeholder for Sixel/image protocol
  }

  @override
  void dcsUnhook() {
    // DCS unhook — placeholder for Sixel/image protocol
  }

  // Fallback

  @override
  void unknownEscape(int char) {
    // Silently ignore unrecognized sequences
  }

  @override
  void unknownCsi(int finalByte, List<int> params) {
    // Silently ignore unrecognized CSI sequences
  }
}
