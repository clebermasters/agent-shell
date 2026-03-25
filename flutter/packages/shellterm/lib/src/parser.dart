/// Table-driven VT500 escape sequence parser.
///
/// Uses a Uint8List transition table indexed by `state * 128 + byte`.
/// Each entry encodes `(action << 4) | nextState` in a single byte.
/// This provides zero-branching, zero-allocation parsing for the ASCII range.
///
/// 12 states, 13 actions, 1536-byte table — lazy singleton shared across
/// all parser instances.
library;

import 'dart:typed_data';

import 'handler.dart';

// ── State IDs (4 bits: 0-11) ─────────────────────────────────────────────────

const int _sGround = 0;
const int _sEscape = 1;
const int _sEscInterm = 2;
const int _sCsiEntry = 3;
const int _sCsiParam = 4;
const int _sCsiInterm = 5;
const int _sCsiIgnore = 6;
const int _sOsc = 7;
const int _sDcs = 8;
const int _sDcsPassthru = 9;
const int _sDcsIgnore = 10;
const int _sSosPmApc = 11;

// ── Action IDs (4 bits: 0-12) ────────────────────────────────────────────────

const int _aNone = 0;
const int _aPrint = 1;
const int _aExec = 2;
const int _aCollect = 3;
const int _aParam = 4;
const int _aParamSep = 5;
const int _aEscDisp = 6;
const int _aCsiDisp = 7;
const int _aOscPut = 8;
const int _aOscEnd = 9;
const int _aHook = 10;
const int _aPut = 11;
const int _aUnhook = 12;

// ── Transition table ─────────────────────────────────────────────────────────

Uint8List? _table;

Uint8List _buildTable() {
  final t = Uint8List(12 * 128);

  void fill(int state, int lo, int hi, int action, int next) {
    final v = (action << 4) | next;
    for (var c = lo; c <= hi; c++) {
      t[state * 128 + c] = v;
    }
  }

  void at(int state, int c, int action, int next) {
    t[state * 128 + c] = (action << 4) | next;
  }

  // ── GROUND ────────────────────────────────────────────────────────────
  fill(_sGround, 0x00, 0x1A, _aExec, _sGround);
  at(_sGround, 0x1B, _aNone, _sEscape);
  fill(_sGround, 0x1C, 0x1F, _aExec, _sGround);
  fill(_sGround, 0x20, 0x7E, _aPrint, _sGround);
  at(_sGround, 0x7F, _aNone, _sGround);

  // ── ESCAPE ────────────────────────────────────────────────────────────
  fill(_sEscape, 0x00, 0x17, _aExec, _sEscape);
  at(_sEscape, 0x18, _aExec, _sGround);
  at(_sEscape, 0x19, _aExec, _sEscape);
  at(_sEscape, 0x1A, _aExec, _sGround);
  at(_sEscape, 0x1B, _aNone, _sEscape);
  fill(_sEscape, 0x1C, 0x1F, _aExec, _sEscape);
  fill(_sEscape, 0x20, 0x2F, _aCollect, _sEscInterm);
  fill(_sEscape, 0x30, 0x4F, _aEscDisp, _sGround);
  at(_sEscape, 0x50, _aNone, _sDcs); // DCS
  fill(_sEscape, 0x51, 0x57, _aEscDisp, _sGround);
  at(_sEscape, 0x58, _aNone, _sSosPmApc); // SOS
  at(_sEscape, 0x59, _aEscDisp, _sGround);
  at(_sEscape, 0x5A, _aEscDisp, _sGround);
  at(_sEscape, 0x5B, _aNone, _sCsiEntry); // CSI
  at(_sEscape, 0x5C, _aEscDisp, _sGround); // ST
  at(_sEscape, 0x5D, _aNone, _sOsc); // OSC
  at(_sEscape, 0x5E, _aNone, _sSosPmApc); // PM
  at(_sEscape, 0x5F, _aNone, _sSosPmApc); // APC
  fill(_sEscape, 0x60, 0x7E, _aEscDisp, _sGround);
  at(_sEscape, 0x7F, _aNone, _sEscape);

  // ── ESCAPE INTERMEDIATE ───────────────────────────────────────────────
  fill(_sEscInterm, 0x00, 0x17, _aExec, _sEscInterm);
  at(_sEscInterm, 0x18, _aExec, _sGround);
  at(_sEscInterm, 0x19, _aExec, _sEscInterm);
  at(_sEscInterm, 0x1A, _aExec, _sGround);
  at(_sEscInterm, 0x1B, _aNone, _sEscape);
  fill(_sEscInterm, 0x1C, 0x1F, _aExec, _sEscInterm);
  fill(_sEscInterm, 0x20, 0x2F, _aCollect, _sEscInterm);
  fill(_sEscInterm, 0x30, 0x7E, _aEscDisp, _sGround);
  at(_sEscInterm, 0x7F, _aNone, _sEscInterm);

  // ── CSI ENTRY ─────────────────────────────────────────────────────────
  fill(_sCsiEntry, 0x00, 0x17, _aExec, _sCsiEntry);
  at(_sCsiEntry, 0x18, _aExec, _sGround);
  at(_sCsiEntry, 0x19, _aExec, _sCsiEntry);
  at(_sCsiEntry, 0x1A, _aExec, _sGround);
  at(_sCsiEntry, 0x1B, _aNone, _sEscape);
  fill(_sCsiEntry, 0x1C, 0x1F, _aExec, _sCsiEntry);
  fill(_sCsiEntry, 0x20, 0x2F, _aCollect, _sCsiInterm);
  fill(_sCsiEntry, 0x30, 0x39, _aParam, _sCsiParam);
  at(_sCsiEntry, 0x3A, _aNone, _sCsiIgnore);
  at(_sCsiEntry, 0x3B, _aParamSep, _sCsiParam);
  fill(_sCsiEntry, 0x3C, 0x3F, _aCollect, _sCsiParam); // '?' '<' '=' '>'
  fill(_sCsiEntry, 0x40, 0x7E, _aCsiDisp, _sGround);
  at(_sCsiEntry, 0x7F, _aNone, _sCsiEntry);

  // ── CSI PARAM ─────────────────────────────────────────────────────────
  fill(_sCsiParam, 0x00, 0x17, _aExec, _sCsiParam);
  at(_sCsiParam, 0x18, _aExec, _sGround);
  at(_sCsiParam, 0x19, _aExec, _sCsiParam);
  at(_sCsiParam, 0x1A, _aExec, _sGround);
  at(_sCsiParam, 0x1B, _aNone, _sEscape);
  fill(_sCsiParam, 0x1C, 0x1F, _aExec, _sCsiParam);
  fill(_sCsiParam, 0x20, 0x2F, _aCollect, _sCsiInterm);
  fill(_sCsiParam, 0x30, 0x39, _aParam, _sCsiParam);
  at(_sCsiParam, 0x3A, _aNone, _sCsiIgnore);
  at(_sCsiParam, 0x3B, _aParamSep, _sCsiParam);
  fill(_sCsiParam, 0x3C, 0x3F, _aNone, _sCsiIgnore);
  fill(_sCsiParam, 0x40, 0x7E, _aCsiDisp, _sGround);
  at(_sCsiParam, 0x7F, _aNone, _sCsiParam);

  // ── CSI INTERMEDIATE ──────────────────────────────────────────────────
  fill(_sCsiInterm, 0x00, 0x17, _aExec, _sCsiInterm);
  at(_sCsiInterm, 0x18, _aExec, _sGround);
  at(_sCsiInterm, 0x19, _aExec, _sCsiInterm);
  at(_sCsiInterm, 0x1A, _aExec, _sGround);
  at(_sCsiInterm, 0x1B, _aNone, _sEscape);
  fill(_sCsiInterm, 0x1C, 0x1F, _aExec, _sCsiInterm);
  fill(_sCsiInterm, 0x20, 0x2F, _aCollect, _sCsiInterm);
  fill(_sCsiInterm, 0x30, 0x3F, _aNone, _sCsiIgnore);
  fill(_sCsiInterm, 0x40, 0x7E, _aCsiDisp, _sGround);
  at(_sCsiInterm, 0x7F, _aNone, _sCsiInterm);

  // ── CSI IGNORE ────────────────────────────────────────────────────────
  fill(_sCsiIgnore, 0x00, 0x17, _aExec, _sCsiIgnore);
  at(_sCsiIgnore, 0x18, _aExec, _sGround);
  at(_sCsiIgnore, 0x19, _aExec, _sCsiIgnore);
  at(_sCsiIgnore, 0x1A, _aExec, _sGround);
  at(_sCsiIgnore, 0x1B, _aNone, _sEscape);
  fill(_sCsiIgnore, 0x1C, 0x1F, _aExec, _sCsiIgnore);
  fill(_sCsiIgnore, 0x20, 0x3F, _aNone, _sCsiIgnore);
  fill(_sCsiIgnore, 0x40, 0x7E, _aNone, _sGround);
  at(_sCsiIgnore, 0x7F, _aNone, _sCsiIgnore);

  // ── OSC ───────────────────────────────────────────────────────────────
  fill(_sOsc, 0x00, 0x06, _aNone, _sOsc);
  at(_sOsc, 0x07, _aOscEnd, _sGround); // BEL terminates OSC
  fill(_sOsc, 0x08, 0x17, _aNone, _sOsc);
  at(_sOsc, 0x18, _aNone, _sGround);
  at(_sOsc, 0x19, _aNone, _sOsc);
  at(_sOsc, 0x1A, _aNone, _sGround);
  at(_sOsc, 0x1B, _aNone, _sEscape); // ESC starts ST check
  fill(_sOsc, 0x1C, 0x1F, _aNone, _sOsc);
  fill(_sOsc, 0x20, 0x7E, _aOscPut, _sOsc);
  at(_sOsc, 0x7F, _aNone, _sOsc);

  // ── DCS ENTRY ─────────────────────────────────────────────────────────
  fill(_sDcs, 0x00, 0x17, _aNone, _sDcs);
  at(_sDcs, 0x18, _aNone, _sGround);
  at(_sDcs, 0x19, _aNone, _sDcs);
  at(_sDcs, 0x1A, _aNone, _sGround);
  at(_sDcs, 0x1B, _aNone, _sEscape);
  fill(_sDcs, 0x1C, 0x1F, _aNone, _sDcs);
  fill(_sDcs, 0x20, 0x2F, _aCollect, _sDcs);
  fill(_sDcs, 0x30, 0x39, _aParam, _sDcs);
  at(_sDcs, 0x3A, _aNone, _sDcsIgnore);
  at(_sDcs, 0x3B, _aParamSep, _sDcs);
  fill(_sDcs, 0x3C, 0x3F, _aCollect, _sDcs);
  fill(_sDcs, 0x40, 0x7E, _aHook, _sDcsPassthru);
  at(_sDcs, 0x7F, _aNone, _sDcs);

  // ── DCS PASSTHROUGH ───────────────────────────────────────────────────
  fill(_sDcsPassthru, 0x00, 0x17, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x18, _aUnhook, _sGround);
  at(_sDcsPassthru, 0x19, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x1A, _aUnhook, _sGround);
  at(_sDcsPassthru, 0x1B, _aNone, _sEscape); // ESC will handle ST → unhook
  fill(_sDcsPassthru, 0x1C, 0x1F, _aPut, _sDcsPassthru);
  fill(_sDcsPassthru, 0x20, 0x7E, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x7F, _aNone, _sDcsPassthru);

  // ── DCS IGNORE ────────────────────────────────────────────────────────
  fill(_sDcsIgnore, 0x00, 0x17, _aNone, _sDcsIgnore);
  at(_sDcsIgnore, 0x18, _aNone, _sGround);
  at(_sDcsIgnore, 0x19, _aNone, _sDcsIgnore);
  at(_sDcsIgnore, 0x1A, _aNone, _sGround);
  at(_sDcsIgnore, 0x1B, _aNone, _sEscape);
  fill(_sDcsIgnore, 0x1C, 0x7E, _aNone, _sDcsIgnore);
  at(_sDcsIgnore, 0x7F, _aNone, _sDcsIgnore);

  // ── SOS/PM/APC ────────────────────────────────────────────────────────
  fill(_sSosPmApc, 0x00, 0x17, _aNone, _sSosPmApc);
  at(_sSosPmApc, 0x18, _aNone, _sGround);
  at(_sSosPmApc, 0x19, _aNone, _sSosPmApc);
  at(_sSosPmApc, 0x1A, _aNone, _sGround);
  at(_sSosPmApc, 0x1B, _aNone, _sEscape);
  fill(_sSosPmApc, 0x1C, 0x7F, _aNone, _sSosPmApc);

  return t;
}

// ═══════════════════════════════════════════════════════════════════════════════
//  EscapeParser
// ═══════════════════════════════════════════════════════════════════════════════

class EscapeParser {
  final EscapeHandler handler;

  EscapeParser(this.handler);

  int _state = _sGround;

  /// Tracks the previous state that entered OSC/DCS — used for ESC \ (ST)
  /// to know whether to call oscEnd or dcsUnhook.
  int _prevStringState = _sGround;

  // Fixed-size CSI parameter buffer — zero allocation.
  final Uint16List _params = Uint16List(16);
  int _paramCount = 0;
  int _currentParam = 0;
  bool _hasParam = false;

  // Intermediates collected during ESC/CSI sequences.
  final List<int> _intermediates = [];

  // OSC content accumulator.
  final StringBuffer _oscBuffer = StringBuffer();

  /// Feed a chunk of terminal data into the parser.
  void write(String data) {
    _table ??= _buildTable();
    final table = _table!;

    for (var i = 0; i < data.length; i++) {
      var cp = data.codeUnitAt(i);

      // Handle surrogate pairs for codepoints > 0xFFFF
      if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < data.length) {
        final lo = data.codeUnitAt(i + 1);
        if (lo >= 0xDC00 && lo <= 0xDFFF) {
          cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
          i++;
        }
      }

      _processCodepoint(cp, table);
    }
  }

  void _processCodepoint(int cp, Uint8List table) {
    // Fast path: ESC is recognized in every state
    if (cp == 0x1B) {
      // Track which string-accumulating state we came from
      if (_state == _sOsc || _state == _sDcsPassthru) {
        _prevStringState = _state;
      }
      _state = _sEscape;
      _intermediates.clear();
      _clearParams();
      return;
    }

    // CAN (0x18) / SUB (0x1A) abort to ground from any state
    if (cp == 0x18 || cp == 0x1A) {
      if (_state == _sDcsPassthru) {
        handler.dcsUnhook();
      }
      _state = _sGround;
      return;
    }

    // Unicode > 0x7F: handle per state (no table coverage)
    if (cp > 0x7F) {
      switch (_state) {
        case _sGround:
          handler.writeChar(cp);
        case _sOsc:
          _oscBuffer.writeCharCode(cp);
        case _sDcsPassthru:
          handler.dcsPut(cp);
        // All other states: ignore high bytes
      }
      return;
    }

    // ASCII 0x00-0x7F: table lookup
    final entry = table[_state * 128 + cp];
    final action = entry >> 4;
    final nextState = entry & 0xF;

    _executeAction(action, cp);
    if (nextState != _state) {
      _state = nextState;
    }
  }

  void _executeAction(int action, int cp) {
    switch (action) {
      case _aNone:
        break;
      case _aPrint:
        handler.writeChar(cp);
      case _aExec:
        _executeControl(cp);
      case _aCollect:
        _intermediates.add(cp);
      case _aParam:
        _currentParam = _currentParam * 10 + (cp - 0x30);
        _hasParam = true;
      case _aParamSep:
        _pushParam();
      case _aEscDisp:
        _dispatchEsc(cp);
      case _aCsiDisp:
        _pushParam(); // Finalize last parameter
        _dispatchCsi(cp);
        _clearParams();
        _intermediates.clear();
      case _aOscPut:
        _oscBuffer.writeCharCode(cp);
      case _aOscEnd:
        _dispatchOsc();
        _oscBuffer.clear();
        _prevStringState = _sGround;
      case _aHook:
        _pushParam();
        handler.dcsHook(
          _params.sublist(0, _paramCount).toList(),
          List.of(_intermediates),
        );
        _clearParams();
        _intermediates.clear();
      case _aPut:
        handler.dcsPut(cp);
      case _aUnhook:
        handler.dcsUnhook();
    }
  }

  // ── Control character dispatch ────────────────────────────────────────

  void _executeControl(int cp) {
    switch (cp) {
      case 0x07:
        handler.bell();
      case 0x08:
        handler.backspace();
      case 0x09:
        handler.tab();
      case 0x0A || 0x0B || 0x0C:
        handler.lineFeed();
      case 0x0D:
        handler.carriageReturn();
      case 0x0E:
        handler.shiftOut();
      case 0x0F:
        handler.shiftIn();
    }
  }

  // ── ESC sequence dispatch ─────────────────────────────────────────────

  void _dispatchEsc(int finalByte) {
    // Check if we came from an OSC/DCS via ESC \ (ST)
    if (finalByte == 0x5C) {
      // ST (String Terminator)
      if (_prevStringState == _sOsc) {
        _dispatchOsc();
        _oscBuffer.clear();
        _prevStringState = _sGround;
        return;
      } else if (_prevStringState == _sDcsPassthru) {
        handler.dcsUnhook();
        _prevStringState = _sGround;
        return;
      }
    }

    if (_intermediates.isNotEmpty) {
      // ESC with intermediates: charset designation
      final interm = _intermediates[0];
      if (interm >= 0x28 && interm <= 0x2B) {
        handler.designateCharset(interm - 0x28, finalByte);
        _intermediates.clear();
        return;
      }
      // ESC # sequences (line attributes)
      if (interm == 0x23) {
        // ESC # 8 = DECALN (fill screen with 'E')
        _intermediates.clear();
        return;
      }
      _intermediates.clear();
      return;
    }

    switch (finalByte) {
      case 0x37: // ESC 7 — save cursor
        handler.saveCursor();
      case 0x38: // ESC 8 — restore cursor
        handler.restoreCursor();
      case 0x3D: // ESC = — application keypad mode
        handler.setAppKeypadMode(true);
      case 0x3E: // ESC > — normal keypad mode
        handler.setAppKeypadMode(false);
      case 0x44: // ESC D — index (cursor down / scroll)
        handler.index();
      case 0x45: // ESC E — next line
        handler.nextLine();
      case 0x48: // ESC H — set tab stop
        handler.setTabStop();
      case 0x4D: // ESC M — reverse index (cursor up / scroll)
        handler.reverseIndex();
      case 0x63: // ESC c — full reset
        handler.resetState();
      default:
        handler.unknownEscape(finalByte);
    }
  }

  // ── CSI sequence dispatch ─────────────────────────────────────────────

  void _dispatchCsi(int finalByte) {
    final isPrivate =
        _intermediates.isNotEmpty && _intermediates[0] == 0x3F; // '?'
    final hasSpace =
        _intermediates.isNotEmpty && _intermediates.last == 0x20; // ' '
    final hasQuote =
        _intermediates.isNotEmpty && _intermediates.last == 0x22; // '"'

    int p(int i, [int def = 0]) =>
        i < _paramCount ? (_params[i] == 0 ? def : _params[i]) : def;

    switch (finalByte) {
      case 0x40: // CSI @ — insert chars
        handler.insertChars(p(0, 1));
      case 0x41: // CSI A — cursor up
        handler.cursorUp(p(0, 1));
      case 0x42: // CSI B — cursor down
        handler.cursorDown(p(0, 1));
      case 0x43: // CSI C — cursor forward
        handler.cursorForward(p(0, 1));
      case 0x44: // CSI D — cursor backward
        handler.cursorBackward(p(0, 1));
      case 0x45: // CSI E — cursor next line
        handler.cursorNextLine(p(0, 1));
      case 0x46: // CSI F — cursor prev line
        handler.cursorPrevLine(p(0, 1));
      case 0x47: // CSI G — cursor to column
        handler.cursorToColumn(p(0, 1) - 1);
      case 0x48: // CSI H — set cursor position
        handler.setCursorPosition(p(0, 1) - 1, p(1, 1) - 1);
      case 0x49: // CSI I — tab forward
        handler.tabForward(p(0, 1));
      case 0x4A: // CSI J — erase in display
        handler.eraseInDisplay(p(0));
      case 0x4B: // CSI K — erase in line
        handler.eraseInLine(p(0));
      case 0x4C: // CSI L — insert lines
        handler.insertLines(p(0, 1));
      case 0x4D: // CSI M — delete lines
        handler.deleteLines(p(0, 1));
      case 0x50: // CSI P — delete chars
        handler.deleteChars(p(0, 1));
      case 0x53: // CSI S — scroll up
        handler.scrollUp(p(0, 1));
      case 0x54: // CSI T — scroll down
        handler.scrollDown(p(0, 1));
      case 0x58: // CSI X — erase chars
        handler.eraseChars(p(0, 1));
      case 0x5A: // CSI Z — tab backward
        handler.tabBackward(p(0, 1));
      case 0x60: // CSI ` — cursor to column (same as G)
        handler.cursorToColumn(p(0, 1) - 1);
      case 0x62: // CSI b — repeat char
        handler.repeatChar(p(0, 1));
      case 0x63: // CSI c — device attributes
        if (isPrivate) {
          handler.sendDeviceAttributes(1); // Secondary DA
        } else {
          handler.sendDeviceAttributes(0); // Primary DA
        }
      case 0x64: // CSI d — cursor to row
        handler.cursorToRow(p(0, 1) - 1);
      case 0x66: // CSI f — same as H
        handler.setCursorPosition(p(0, 1) - 1, p(1, 1) - 1);
      case 0x67: // CSI g — clear tab stop
        handler.clearTabStop(p(0));
      case 0x68: // CSI h — set mode
        if (isPrivate) {
          for (var i = 0; i < _paramCount; i++) {
            handler.setDecMode(_params[i], true);
          }
        } else {
          for (var i = 0; i < _paramCount; i++) {
            handler.setMode(_params[i], true);
          }
        }
      case 0x6C: // CSI l — reset mode
        if (isPrivate) {
          for (var i = 0; i < _paramCount; i++) {
            handler.setDecMode(_params[i], false);
          }
        } else {
          for (var i = 0; i < _paramCount; i++) {
            handler.setMode(_params[i], false);
          }
        }
      case 0x6D: // CSI m — SGR
        _dispatchSgr();
      case 0x6E: // CSI n — device status report
        if (p(0) == 6) {
          handler.sendCursorPosition();
        } else {
          handler.sendDeviceStatus(p(0));
        }
      case 0x71: // CSI q — with intermediates
        if (hasSpace) {
          handler.setCursorStyle(p(0));
        } else if (hasQuote) {
          handler.setCharacterProtection(p(0));
        }
      case 0x72: // CSI r — set scroll margins
        handler.setScrollMargins(p(0, 1) - 1, p(1, 999) - 1);
      default:
        handler.unknownCsi(finalByte, _params.sublist(0, _paramCount).toList());
    }
  }

  // ── SGR (Select Graphic Rendition) ────────────────────────────────────

  void _dispatchSgr() {
    if (_paramCount == 0) {
      handler.resetStyle();
      return;
    }

    for (var i = 0; i < _paramCount; i++) {
      final p = _params[i];
      switch (p) {
        case 0:
          handler.resetStyle();
        case 1:
          handler.setBold(true);
        case 2:
          handler.setFaint(true);
        case 3:
          handler.setItalic(true);
        case 4:
          handler.setUnderline(true);
        case 5:
          handler.setBlink(true);
        case 7:
          handler.setInverse(true);
        case 8:
          handler.setInvisible(true);
        case 9:
          handler.setStrikethrough(true);
        case 21:
          handler.setUnderline(true); // Double underline → underline
        case 22:
          handler.setBold(false);
          handler.setFaint(false);
        case 23:
          handler.setItalic(false);
        case 24:
          handler.setUnderline(false);
        case 25:
          handler.setBlink(false);
        case 27:
          handler.setInverse(false);
        case 28:
          handler.setInvisible(false);
        case 29:
          handler.setStrikethrough(false);
        case >= 30 && <= 37:
          handler.setForegroundColor16(p - 30);
        case 38:
          i = _parseSgrColor(i, true);
        case 39:
          handler.resetForeground();
        case >= 40 && <= 47:
          handler.setBackgroundColor16(p - 40);
        case 48:
          i = _parseSgrColor(i, false);
        case 49:
          handler.resetBackground();
        case >= 90 && <= 97:
          handler.setForegroundColor16(p - 90 + 8);
        case >= 100 && <= 107:
          handler.setBackgroundColor16(p - 100 + 8);
      }
    }
  }

  /// Parse extended color (256-color or truecolor) starting after the 38/48.
  /// Returns the new param index.
  int _parseSgrColor(int i, bool isFg) {
    if (i + 1 >= _paramCount) return i;
    final mode = _params[i + 1];
    if (mode == 5 && i + 2 < _paramCount) {
      // 256-color: 38;5;n or 48;5;n
      final idx = _params[i + 2];
      if (isFg) {
        handler.setForegroundColor256(idx);
      } else {
        handler.setBackgroundColor256(idx);
      }
      return i + 2;
    } else if (mode == 2 && i + 4 < _paramCount) {
      // Truecolor: 38;2;r;g;b or 48;2;r;g;b
      final r = _params[i + 2];
      final g = _params[i + 3];
      final b = _params[i + 4];
      if (isFg) {
        handler.setForegroundColorRgb(r, g, b);
      } else {
        handler.setBackgroundColorRgb(r, g, b);
      }
      return i + 4;
    }
    return i;
  }

  // ── OSC dispatch ──────────────────────────────────────────────────────

  void _dispatchOsc() {
    final content = _oscBuffer.toString();
    if (content.isEmpty) return;

    // Find the first ';' separator
    final sepIdx = content.indexOf(';');
    if (sepIdx < 0) return;

    final cmdStr = content.substring(0, sepIdx);
    final cmd = int.tryParse(cmdStr);
    if (cmd == null) return;

    final data = content.substring(sepIdx + 1);

    switch (cmd) {
      case 0: // Set icon name + title
        handler.setTitle(data);
        handler.setIconName(data);
      case 1: // Set icon name
        handler.setIconName(data);
      case 2: // Set title
        handler.setTitle(data);
    }
  }

  // ── Parameter helpers ─────────────────────────────────────────────────

  void _pushParam() {
    if (_paramCount < _params.length) {
      _params[_paramCount++] = _hasParam ? _currentParam : 0;
    }
    _currentParam = 0;
    _hasParam = false;
  }

  void _clearParams() {
    _paramCount = 0;
    _currentParam = 0;
    _hasParam = false;
  }
}
