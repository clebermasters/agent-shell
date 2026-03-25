import 'dart:typed_data';

import 'package:shellterm/src/core/color.dart';
import 'package:shellterm/src/core/mouse/mode.dart';
import 'package:shellterm/src/core/escape/handler.dart';
import 'package:shellterm/src/utils/ascii.dart';

// ─────────────────────────────────────────────────────────────────────────────
// State IDs (fits in low 4 bits of table entry)
// ─────────────────────────────────────────────────────────────────────────────
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
const int _numStates = 12;

// ─────────────────────────────────────────────────────────────────────────────
// Action IDs (fits in high 4 bits of table entry)
// ─────────────────────────────────────────────────────────────────────────────
const int _aNone = 0; // ignore input
const int _aPrint = 1; // write character to screen
const int _aExec = 2; // execute C0 control
const int _aCollect = 3; // collect intermediate / prefix byte
const int _aParam = 4; // accumulate CSI parameter digit
const int _aParamSep = 5; // CSI parameter separator ';'
const int _aEscDisp = 6; // dispatch ESC sequence
const int _aCsiDisp = 7; // dispatch CSI sequence
const int _aOscPut = 8; // accumulate OSC content byte
const int _aOscEnd = 9; // dispatch OSC sequence
const int _aHook = 10; // DCS hook (final byte seen; placeholder for Sixel)
const int _aPut = 11; // DCS put byte (placeholder)
const int _aUnhook = 12; // DCS unhook (placeholder)

// ─────────────────────────────────────────────────────────────────────────────
// Transition table: indexed by [state * 128 + byte].
// Each byte encodes (action << 4) | nextState.
// Handles code points 0x00–0x7F; higher values are dispatched per-state.
// Initialized lazily once and shared across all parser instances.
// ─────────────────────────────────────────────────────────────────────────────
Uint8List? _table;

Uint8List _buildTable() {
  final t = Uint8List(_numStates * 128);

  void fill(int state, int lo, int hi, int action, int next) {
    final v = (action << 4) | next;
    for (var c = lo; c <= hi; c++) {
      t[state * 128 + c] = v;
    }
  }

  void at(int state, int c, int action, int next) {
    t[state * 128 + c] = (action << 4) | next;
  }

  // ── GROUND ──────────────────────────────────────────────────────────────────
  fill(_sGround, 0x00, 0x1A, _aExec, _sGround); // C0 controls
  at(_sGround, 0x1B, _aNone, _sEscape); // ESC
  fill(_sGround, 0x1C, 0x1F, _aExec, _sGround);
  fill(_sGround, 0x20, 0x7E, _aPrint, _sGround); // printable ASCII
  at(_sGround, 0x7F, _aNone, _sGround); // DEL: ignore

  // ── ESCAPE ──────────────────────────────────────────────────────────────────
  fill(_sEscape, 0x00, 0x17, _aExec, _sEscape);
  at(_sEscape, 0x18, _aExec, _sGround); // CAN → ground
  at(_sEscape, 0x19, _aExec, _sEscape);
  at(_sEscape, 0x1A, _aExec, _sGround); // SUB → ground
  at(_sEscape, 0x1B, _aNone, _sEscape); // ESC ESC: restart
  fill(_sEscape, 0x1C, 0x1F, _aExec, _sEscape);
  fill(_sEscape, 0x20, 0x2F, _aCollect, _sEscInterm); // intermediate bytes
  fill(_sEscape, 0x30, 0x4F, _aEscDisp, _sGround); // final bytes 0–O
  at(_sEscape, 0x50, _aNone, _sDcs); // DCS
  fill(_sEscape, 0x51, 0x57, _aEscDisp, _sGround); // Q–W
  at(_sEscape, 0x58, _aNone, _sSosPmApc); // SOS
  at(_sEscape, 0x59, _aEscDisp, _sGround); // Y
  at(_sEscape, 0x5A, _aEscDisp, _sGround); // Z
  at(_sEscape, 0x5B, _aNone, _sCsiEntry); // CSI [
  at(_sEscape, 0x5C, _aEscDisp, _sGround); // ST \ (terminates OSC/DCS)
  at(_sEscape, 0x5D, _aNone, _sOsc); // OSC ]
  at(_sEscape, 0x5E, _aNone, _sSosPmApc); // PM ^
  at(_sEscape, 0x5F, _aNone, _sSosPmApc); // APC _
  fill(_sEscape, 0x60, 0x7E, _aEscDisp, _sGround); // `–~
  at(_sEscape, 0x7F, _aNone, _sEscape); // DEL: ignore

  // ── ESCAPE INTERMEDIATE ─────────────────────────────────────────────────────
  fill(_sEscInterm, 0x00, 0x17, _aExec, _sEscInterm);
  at(_sEscInterm, 0x18, _aExec, _sGround);
  at(_sEscInterm, 0x19, _aExec, _sEscInterm);
  at(_sEscInterm, 0x1A, _aExec, _sGround);
  at(_sEscInterm, 0x1B, _aNone, _sEscape);
  fill(_sEscInterm, 0x1C, 0x1F, _aExec, _sEscInterm);
  fill(_sEscInterm, 0x20, 0x2F, _aCollect, _sEscInterm); // more intermediates
  fill(_sEscInterm, 0x30, 0x7E, _aEscDisp, _sGround); // final byte
  at(_sEscInterm, 0x7F, _aNone, _sEscInterm);

  // ── CSI ENTRY ───────────────────────────────────────────────────────────────
  fill(_sCsiEntry, 0x00, 0x17, _aExec, _sCsiEntry);
  at(_sCsiEntry, 0x18, _aExec, _sGround);
  at(_sCsiEntry, 0x19, _aExec, _sCsiEntry);
  at(_sCsiEntry, 0x1A, _aExec, _sGround);
  at(_sCsiEntry, 0x1B, _aNone, _sEscape);
  fill(_sCsiEntry, 0x1C, 0x1F, _aExec, _sCsiEntry);
  fill(_sCsiEntry, 0x20, 0x2F, _aCollect, _sCsiInterm); // intermediates
  fill(_sCsiEntry, 0x30, 0x39, _aParam, _sCsiParam); // digits 0–9
  at(_sCsiEntry, 0x3A, _aParamSep, _sCsiParam); // : separator
  at(_sCsiEntry, 0x3B, _aParamSep, _sCsiParam); // ; separator
  fill(_sCsiEntry, 0x3C, 0x3F, _aCollect, _sCsiParam); // prefix: ? > = <
  fill(_sCsiEntry, 0x40, 0x7E, _aCsiDisp, _sGround); // final bytes
  at(_sCsiEntry, 0x7F, _aNone, _sCsiEntry);

  // ── CSI PARAM ───────────────────────────────────────────────────────────────
  fill(_sCsiParam, 0x00, 0x17, _aExec, _sCsiParam);
  at(_sCsiParam, 0x18, _aExec, _sGround);
  at(_sCsiParam, 0x19, _aExec, _sCsiParam);
  at(_sCsiParam, 0x1A, _aExec, _sGround);
  at(_sCsiParam, 0x1B, _aNone, _sEscape);
  fill(_sCsiParam, 0x1C, 0x1F, _aExec, _sCsiParam);
  fill(_sCsiParam, 0x20, 0x2F, _aCollect, _sCsiInterm); // intermediates
  fill(_sCsiParam, 0x30, 0x39, _aParam, _sCsiParam); // more digits
  at(_sCsiParam, 0x3A, _aParamSep, _sCsiParam);
  at(_sCsiParam, 0x3B, _aParamSep, _sCsiParam);
  fill(_sCsiParam, 0x3C, 0x3F, _aNone, _sCsiIgnore); // private after params
  fill(_sCsiParam, 0x40, 0x7E, _aCsiDisp, _sGround); // final bytes
  at(_sCsiParam, 0x7F, _aNone, _sCsiParam);

  // ── CSI INTERMEDIATE ────────────────────────────────────────────────────────
  fill(_sCsiInterm, 0x00, 0x17, _aExec, _sCsiInterm);
  at(_sCsiInterm, 0x18, _aExec, _sGround);
  at(_sCsiInterm, 0x19, _aExec, _sCsiInterm);
  at(_sCsiInterm, 0x1A, _aExec, _sGround);
  at(_sCsiInterm, 0x1B, _aNone, _sEscape);
  fill(_sCsiInterm, 0x1C, 0x1F, _aExec, _sCsiInterm);
  fill(_sCsiInterm, 0x20, 0x2F, _aCollect, _sCsiInterm); // more intermediates
  fill(_sCsiInterm, 0x30, 0x3F, _aNone, _sCsiIgnore); // params after interm: bad
  fill(_sCsiInterm, 0x40, 0x7E, _aCsiDisp, _sGround); // final bytes
  at(_sCsiInterm, 0x7F, _aNone, _sCsiInterm);

  // ── CSI IGNORE ──────────────────────────────────────────────────────────────
  fill(_sCsiIgnore, 0x00, 0x17, _aExec, _sCsiIgnore);
  at(_sCsiIgnore, 0x18, _aExec, _sGround);
  at(_sCsiIgnore, 0x19, _aExec, _sCsiIgnore);
  at(_sCsiIgnore, 0x1A, _aExec, _sGround);
  at(_sCsiIgnore, 0x1B, _aNone, _sEscape);
  fill(_sCsiIgnore, 0x1C, 0x1F, _aExec, _sCsiIgnore);
  fill(_sCsiIgnore, 0x20, 0x3F, _aNone, _sCsiIgnore); // consume until final
  fill(_sCsiIgnore, 0x40, 0x7E, _aNone, _sGround); // final byte: discard
  at(_sCsiIgnore, 0x7F, _aNone, _sCsiIgnore);

  // ── OSC ─────────────────────────────────────────────────────────────────────
  fill(_sOsc, 0x00, 0x06, _aNone, _sOsc); // C0 except BEL: ignore
  at(_sOsc, 0x07, _aOscEnd, _sGround); // BEL terminates OSC
  fill(_sOsc, 0x08, 0x17, _aNone, _sOsc);
  at(_sOsc, 0x18, _aOscEnd, _sGround); // CAN terminates
  at(_sOsc, 0x19, _aNone, _sOsc);
  at(_sOsc, 0x1A, _aOscEnd, _sGround); // SUB terminates
  at(_sOsc, 0x1B, _aNone, _sEscape); // may start ST (ESC \)
  fill(_sOsc, 0x1C, 0x1F, _aNone, _sOsc);
  fill(_sOsc, 0x20, 0x7E, _aOscPut, _sOsc); // accumulate printable
  at(_sOsc, 0x7F, _aNone, _sOsc);
  // Unicode > 0x7F handled outside table (_aOscPut equivalent)

  // ── DCS ─────────────────────────────────────────────────────────────────────
  fill(_sDcs, 0x00, 0x17, _aNone, _sDcs);
  at(_sDcs, 0x18, _aNone, _sGround);
  at(_sDcs, 0x19, _aNone, _sDcs);
  at(_sDcs, 0x1A, _aNone, _sGround);
  at(_sDcs, 0x1B, _aNone, _sEscape); // may start ST
  fill(_sDcs, 0x1C, 0x1F, _aNone, _sDcs);
  fill(_sDcs, 0x20, 0x2F, _aCollect, _sDcs); // DCS intermediates
  fill(_sDcs, 0x30, 0x39, _aParam, _sDcs); // DCS params
  at(_sDcs, 0x3A, _aParamSep, _sDcs);
  at(_sDcs, 0x3B, _aParamSep, _sDcs);
  fill(_sDcs, 0x3C, 0x3F, _aNone, _sDcsIgnore); // private → ignore
  fill(_sDcs, 0x40, 0x7E, _aHook, _sDcsPassthru); // final byte → hook
  at(_sDcs, 0x7F, _aNone, _sDcs);

  // ── DCS PASSTHROUGH ─────────────────────────────────────────────────────────
  fill(_sDcsPassthru, 0x00, 0x17, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x18, _aUnhook, _sGround);
  at(_sDcsPassthru, 0x19, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x1A, _aUnhook, _sGround);
  at(_sDcsPassthru, 0x1B, _aNone, _sEscape); // may start ST
  fill(_sDcsPassthru, 0x1C, 0x1F, _aPut, _sDcsPassthru);
  fill(_sDcsPassthru, 0x20, 0x7E, _aPut, _sDcsPassthru);
  at(_sDcsPassthru, 0x7F, _aNone, _sDcsPassthru);

  // ── DCS IGNORE ──────────────────────────────────────────────────────────────
  fill(_sDcsIgnore, 0x00, 0x17, _aNone, _sDcsIgnore);
  at(_sDcsIgnore, 0x18, _aNone, _sGround);
  at(_sDcsIgnore, 0x19, _aNone, _sDcsIgnore);
  at(_sDcsIgnore, 0x1A, _aNone, _sGround);
  at(_sDcsIgnore, 0x1B, _aNone, _sEscape);
  fill(_sDcsIgnore, 0x1C, 0x7F, _aNone, _sDcsIgnore);

  // ── SOS / PM / APC ──────────────────────────────────────────────────────────
  fill(_sSosPmApc, 0x00, 0x17, _aNone, _sSosPmApc);
  at(_sSosPmApc, 0x18, _aNone, _sGround);
  at(_sSosPmApc, 0x19, _aNone, _sSosPmApc);
  at(_sSosPmApc, 0x1A, _aNone, _sGround);
  at(_sSosPmApc, 0x1B, _aNone, _sEscape);
  fill(_sSosPmApc, 0x1C, 0x7F, _aNone, _sSosPmApc);

  return t;
}

// ─────────────────────────────────────────────────────────────────────────────
// EscapeParser — table-driven VT500 state machine
// ─────────────────────────────────────────────────────────────────────────────

/// Translates control characters and escape sequences into calls on [handler].
///
/// Design goals:
///   * Zero object allocation on the hot path (character printing).
///   * Stateful: handles sequences split across [write] calls naturally.
///   * Correct: follows the VT500 state machine (Paul Williams' dec_ansi_parser).
class EscapeParser {
  EscapeParser(this.handler) {
    _table ??= _buildTable();
  }

  final EscapeHandler handler;

  // ── Parser state ────────────────────────────────────────────────────────────
  int _state = _sGround;

  /// The state we were in when the current ESC was seen.
  /// Used to detect ST (ESC \) terminating OSC/DCS strings.
  int _prevStringState = _sGround;

  // ── ESC sequence accumulator ────────────────────────────────────────────────
  final _escIntermediates = <int>[];

  // ── CSI / DCS accumulators ──────────────────────────────────────────────────
  int _prefix = 0; // byte 0x3C–0x3F (e.g. '?') seen after '[', or 0
  final _params = <int>[];
  int _currentParam = 0;
  bool _hasCurrentParam = false;

  // ── OSC accumulator ─────────────────────────────────────────────────────────
  final _oscBuffer = StringBuffer();

  // ─────────────────────────────────────────────────────────────────────────────
  // Public API
  // ─────────────────────────────────────────────────────────────────────────────

  void write(String data) {
    for (final cp in data.runes) {
      _processCodepoint(cp);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Core state machine
  // ─────────────────────────────────────────────────────────────────────────────

  void _processCodepoint(int cp) {
    // ── ESC: recognised in every state ────────────────────────────────────────
    if (cp == 0x1B) {
      _prevStringState = _state;
      _escIntermediates.clear();
      _clearCsi();
      // Note: _oscBuffer is NOT cleared here; it may still be needed for ST.
      _state = _sEscape;
      return;
    }

    // ── CAN (0x18) / SUB (0x1A): abort sequence ───────────────────────────────
    if (cp == 0x18 || cp == 0x1A) {
      _execC0(cp);
      _clearCsi();
      _oscBuffer.clear();
      _prevStringState = _sGround;
      _state = _sGround;
      return;
    }

    // ── Unicode code points > 0x7F: handled per state ─────────────────────────
    if (cp > 0x7F) {
      switch (_state) {
        case _sGround:
          handler.writeChar(cp);
          return;
        case _sOsc:
          _oscBuffer.writeCharCode(cp);
          return;
        default:
          return; // ignore inside escape sequences
      }
    }

    // ── ASCII 0x00–0x7F: look up in state table ────────────────────────────────
    final entry = _table![_state * 128 + cp];
    final action = entry >> 4;
    final nextState = entry & 0xF;

    // Execute action first (while _state still holds the old value).
    _executeAction(action, cp);

    // Apply state transition.
    if (nextState != _state) {
      _state = nextState;
      _onEnterState(nextState, cp);
    }
  }

  /// Called once when transitioning INTO [state].
  void _onEnterState(int state, int triggerByte) {
    switch (state) {
      case _sCsiEntry:
        _clearCsi();
        break;
      case _sDcs:
        _clearCsi();
        break;
      case _sOsc:
        _oscBuffer.clear();
        _prevStringState = _sGround;
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Action dispatcher
  // ─────────────────────────────────────────────────────────────────────────────

  void _executeAction(int action, int cp) {
    switch (action) {
      case _aNone:
        break;

      case _aPrint:
        handler.writeChar(cp);
        break;

      case _aExec:
        _execC0(cp);
        break;

      case _aCollect:
        if (_state == _sEscape || _state == _sEscInterm) {
          _escIntermediates.add(cp);
        } else if (_state == _sCsiEntry) {
          // Bytes 0x3C–0x3F are the CSI prefix (e.g. '?' for DEC private modes).
          // Bytes 0x20–0x2F are CSI intermediates (rare; handled via _sCsiInterm).
          if (cp >= 0x3C) {
            _prefix = cp;
          }
          // Intermediates are tracked implicitly via the _sCsiInterm state.
        }
        // DCS intermediates/params are accumulated but not dispatched yet.
        break;

      case _aParam:
        _hasCurrentParam = true;
        _currentParam = _currentParam * 10 + (cp - 0x30);
        break;

      case _aParamSep:
        // Only push if there was a preceding digit (matches existing behaviour).
        if (_hasCurrentParam) {
          _params.add(_currentParam);
        }
        _currentParam = 0;
        _hasCurrentParam = false;
        break;

      case _aEscDisp:
        _dispatchEsc(cp);
        break;

      case _aCsiDisp:
        if (_hasCurrentParam) {
          _params.add(_currentParam);
          _currentParam = 0;
          _hasCurrentParam = false;
        }
        _dispatchCsi(cp);
        _clearCsi();
        break;

      case _aOscPut:
        _oscBuffer.writeCharCode(cp);
        break;

      case _aOscEnd:
        _dispatchOsc();
        break;

      case _aHook:
        // DCS hook: pass collected params and intermediates to handler.
        if (_hasCurrentParam) {
          _params.add(_currentParam);
          _currentParam = 0;
          _hasCurrentParam = false;
        }
        handler.dcsHook(List<int>.from(_params), List<int>.from(_escIntermediates));
        break;

      case _aPut:
        // DCS put byte: forward data to handler.
        handler.dcsPut(cp);
        break;

      case _aUnhook:
        // DCS unhook: notify handler that DCS sequence is complete.
        handler.dcsUnhook();
        _clearCsi();
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // C0 control execution
  // ─────────────────────────────────────────────────────────────────────────────

  void _execC0(int cp) {
    switch (cp) {
      case Ascii.BEL:
        handler.bell();
        break;
      case Ascii.BS:
        handler.backspaceReturn();
        break;
      case Ascii.HT:
        handler.tab();
        break;
      case Ascii.LF:
      case Ascii.VT:
      case Ascii.FF:
        handler.lineFeed();
        break;
      case Ascii.CR:
        handler.carriageReturn();
        break;
      case Ascii.SO:
        handler.shiftOut();
        break;
      case Ascii.SI:
        handler.shiftIn();
        break;
      default:
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // ESC dispatch
  // ─────────────────────────────────────────────────────────────────────────────

  void _dispatchEsc(int finalByte) {
    if (_escIntermediates.isEmpty) {
      switch (finalByte) {
        case 0x37: // ESC 7 — Save Cursor (DECSC)
          handler.saveCursor();
          break;
        case 0x38: // ESC 8 — Restore Cursor (DECRC)
          handler.restoreCursor();
          break;
        case 0x44: // ESC D — Index (IND)
          handler.index();
          break;
        case 0x45: // ESC E — Next Line (NEL)
          handler.nextLine();
          break;
        case 0x48: // ESC H — Horizontal Tab Set (HTS)
          handler.setTapStop();
          break;
        case 0x4D: // ESC M — Reverse Index (RI)
          handler.reverseIndex();
          break;
        case 0x3D: // ESC = — Application Keypad (DECKPAM)
          handler.setAppKeypadMode(true);
          break;
        case 0x3E: // ESC > — Normal Keypad (DECKPNM)
          handler.setAppKeypadMode(false);
          break;
        case 0x5C: // ESC \ — String Terminator (ST): ends OSC or DCS
          if (_prevStringState == _sOsc) {
            _dispatchOsc();
          } else if (_prevStringState == _sDcsPassthru) {
            handler.dcsUnhook();
            _clearCsi();
          }
          break;
        default:
          handler.unkownEscape(finalByte);
          break;
      }
    } else {
      // ESC with intermediate byte(s) — charset designation sequences.
      switch (_escIntermediates[0]) {
        case 0x28: // ESC ( Cs — Designate G0 Character Set
          handler.designateCharset(0, finalByte);
          break;
        case 0x29: // ESC ) Cs — Designate G1 Character Set
          handler.designateCharset(1, finalByte);
          break;
        default:
          handler.unkownEscape(finalByte);
          break;
      }
    }

    _escIntermediates.clear();
    _prevStringState = _sGround;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // CSI dispatch
  // ─────────────────────────────────────────────────────────────────────────────

  void _dispatchCsi(int finalByte) {
    switch (finalByte) {
      case 0x40: // CSI @ — Insert Blank Characters (ICH)
        _csiInsertBlankCharacters();
        break;
      case 0x41: // CSI A — Cursor Up (CUU)
        _csiCursorUp();
        break;
      case 0x42: // CSI B — Cursor Down (CUD)
        _csiCursorDown();
        break;
      case 0x43: // CSI C — Cursor Forward (CUF)
        _csiCursorForward();
        break;
      case 0x44: // CSI D — Cursor Backward (CUB)
        _csiCursorBackward();
        break;
      case 0x45: // CSI E — Cursor Next Line (CNL)
        _csiCursorNextLine();
        break;
      case 0x46: // CSI F — Cursor Preceding Line (CPL)
        _csiCursorPrecedingLine();
        break;
      case 0x47: // CSI G — Cursor Horizontal Absolute (CHA)
        _csiCursorHorizontalAbsolute();
        break;
      case 0x48: // CSI H — Cursor Position (CUP) / alias
      case 0x66: // CSI f — Horizontal and Vertical Position (HVP)
        _csiCursorPosition();
        break;
      case 0x4A: // CSI J — Erase in Display (ED)
        _csiEraseDisplay();
        break;
      case 0x4B: // CSI K — Erase in Line (EL)
        _csiEraseLine();
        break;
      case 0x4C: // CSI L — Insert Lines (IL)
        _csiInsertLines();
        break;
      case 0x4D: // CSI M — Delete Lines (DL)
        _csiDeleteLines();
        break;
      case 0x50: // CSI P — Delete Characters (DCH)
        _csiDelete();
        break;
      case 0x53: // CSI S — Scroll Up (SU)
        _csiScrollUp();
        break;
      case 0x54: // CSI T — Scroll Down (SD)
        _csiScrollDown();
        break;
      case 0x58: // CSI X — Erase Characters (ECH)
        _csiEraseCharacters();
        break;
      case 0x62: // CSI b — Repeat Previous Character (REP)
        _csiRepeatPreviousCharacter();
        break;
      case 0x63: // CSI c — Device Attributes (DA)
        _csiSendDeviceAttributes();
        break;
      case 0x64: // CSI d — Line Position Absolute (VPA)
        _csiLinePositionAbsolute();
        break;
      case 0x67: // CSI g — Tab Clear (TBC)
        _csiClearTabStop();
        break;
      case 0x68: // CSI h — Set Mode (SM) / DEC Private Mode Set (DECSET)
      case 0x6C: // CSI l — Reset Mode (RM) / DEC Private Mode Reset (DECRST)
        _csiMode(finalByte);
        break;
      case 0x6D: // CSI m — Select Graphic Rendition (SGR)
        _csiSgr();
        break;
      case 0x6E: // CSI n — Device Status Report (DSR)
        _csiDeviceStatusReport();
        break;
      case 0x72: // CSI r — Set Top and Bottom Margins (DECSTBM)
        _csiSetMargins();
        break;
      case 0x74: // CSI t — Window Manipulation
        _csiWindowManipulation();
        break;
      default:
        handler.unknownCSI(finalByte);
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // OSC dispatch
  // ─────────────────────────────────────────────────────────────────────────────

  void _dispatchOsc() {
    final content = _oscBuffer.toString();
    _oscBuffer.clear();
    _prevStringState = _sGround;

    if (content.isEmpty) return;

    final parts = content.split(';');
    final ps = parts[0];

    if (parts.length >= 2) {
      final pt = parts[1];
      switch (ps) {
        case '0': // Set Window Title and Icon Name
          handler.setTitle(pt);
          handler.setIconName(pt);
          return;
        case '1': // Set Icon Name
          handler.setIconName(pt);
          return;
        case '2': // Set Window Title
          handler.setTitle(pt);
          return;
      }
    }

    handler.unknownOSC(ps, parts.length > 1 ? parts.sublist(1) : const []);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // CSI handler implementations
  // ─────────────────────────────────────────────────────────────────────────────

  int _p(int index, [int def = 0]) =>
      index < _params.length ? _params[index] : def;

  int _p1(int index, [int def = 1]) {
    final v = _p(index, def);
    return v == 0 ? def : v;
  }

  /// `CSI @ Ps` — Insert Blank Characters (ICH)
  void _csiInsertBlankCharacters() {
    handler.insertBlankChars(_p1(0));
  }

  /// `CSI A Ps` — Cursor Up (CUU)
  void _csiCursorUp() {
    handler.moveCursorY(-_p1(0));
  }

  /// `CSI B Ps` — Cursor Down (CUD)
  void _csiCursorDown() {
    handler.moveCursorY(_p1(0));
  }

  /// `CSI C Ps` — Cursor Forward / Right (CUF)
  void _csiCursorForward() {
    handler.moveCursorX(_p1(0));
  }

  /// `CSI D Ps` — Cursor Backward / Left (CUB)
  void _csiCursorBackward() {
    handler.moveCursorX(-_p1(0));
  }

  /// `CSI E Ps` — Cursor Next Line (CNL)
  void _csiCursorNextLine() {
    handler.cursorNextLine(_p1(0));
  }

  /// `CSI F Ps` — Cursor Preceding Line (CPL)
  void _csiCursorPrecedingLine() {
    handler.cursorPrecedingLine(_p1(0));
  }

  /// `CSI G Ps` — Cursor Horizontal Absolute (CHA)
  void _csiCursorHorizontalAbsolute() {
    handler.setCursorX(_p1(0) - 1);
  }

  /// `CSI H Ps ; Ps` — Cursor Position (CUP) / `CSI f` — HVP
  void _csiCursorPosition() {
    final row = _p1(0);
    final col = _p1(1);
    handler.setCursor(col - 1, row - 1);
  }

  /// `CSI J Ps` — Erase in Display (ED)
  void _csiEraseDisplay() {
    switch (_p(0)) {
      case 0:
        handler.eraseDisplayBelow();
        break;
      case 1:
        handler.eraseDisplayAbove();
        break;
      case 2:
        handler.eraseDisplay();
        break;
      case 3:
        handler.eraseScrollbackOnly();
        break;
    }
  }

  /// `CSI K Ps` — Erase in Line (EL)
  void _csiEraseLine() {
    switch (_p(0)) {
      case 0:
        handler.eraseLineRight();
        break;
      case 1:
        handler.eraseLineLeft();
        break;
      case 2:
        handler.eraseLine();
        break;
    }
  }

  /// `CSI L Ps` — Insert Lines (IL)
  void _csiInsertLines() {
    handler.insertLines(_p1(0));
  }

  /// `CSI M Ps` — Delete Lines (DL)
  void _csiDeleteLines() {
    handler.deleteLines(_p1(0));
  }

  /// `CSI P Ps` — Delete Characters (DCH)
  void _csiDelete() {
    handler.deleteChars(_p1(0));
  }

  /// `CSI S Ps` — Scroll Up (SU)
  void _csiScrollUp() {
    handler.scrollUp(_p1(0));
  }

  /// `CSI T Ps` — Scroll Down (SD)
  void _csiScrollDown() {
    handler.scrollDown(_p1(0));
  }

  /// `CSI X Ps` — Erase Characters (ECH)
  void _csiEraseCharacters() {
    handler.eraseChars(_p1(0));
  }

  /// `CSI b Ps` — Repeat Previous Character (REP)
  void _csiRepeatPreviousCharacter() {
    handler.repeatPreviousCharacter(_p1(0));
  }

  /// `CSI c Ps` — Device Attributes (DA)
  void _csiSendDeviceAttributes() {
    switch (_prefix) {
      case 0x3E: // CSI > c — Secondary DA
        handler.sendSecondaryDeviceAttributes();
        break;
      case 0x3D: // CSI = c — Tertiary DA
        handler.sendTertiaryDeviceAttributes();
        break;
      default: // CSI c — Primary DA
        handler.sendPrimaryDeviceAttributes();
        break;
    }
  }

  /// `CSI d Ps` — Line Position Absolute (VPA)
  void _csiLinePositionAbsolute() {
    handler.setCursorY(_p1(0) - 1);
  }

  /// `CSI g Ps` — Tab Clear (TBC)
  void _csiClearTabStop() {
    switch (_p(0)) {
      case 0:
        handler.clearTabStopUnderCursor();
        break;
      default:
        handler.clearAllTabStops();
        break;
    }
  }

  /// `CSI h` / `CSI l` — Set / Reset Mode (SM / RM) and DEC Private Modes
  void _csiMode(int finalByte) {
    final enable = finalByte == 0x68; // 'h'
    if (_prefix == Ascii.questionMark) {
      for (final mode in _params) {
        _setDecMode(mode, enable);
      }
    } else {
      for (final mode in _params) {
        _setMode(mode, enable);
      }
    }
  }

  /// `CSI m` — Select Graphic Rendition (SGR)
  void _csiSgr() {
    if (_params.isEmpty) {
      handler.resetCursorStyle();
      return;
    }

    for (var i = 0; i < _params.length; i++) {
      final p = _params[i];
      switch (p) {
        case 0:
          handler.resetCursorStyle();
          break;
        case 1:
          handler.setCursorBold();
          break;
        case 2:
          handler.setCursorFaint();
          break;
        case 3:
          handler.setCursorItalic();
          break;
        case 4:
          handler.setCursorUnderline();
          break;
        case 5:
          handler.setCursorBlink();
          break;
        case 7:
          handler.setCursorInverse();
          break;
        case 8:
          handler.setCursorInvisible();
          break;
        case 9:
          handler.setCursorStrikethrough();
          break;
        case 21:
          handler.unsetCursorBold();
          break;
        case 22:
          handler.unsetCursorFaint();
          break;
        case 23:
          handler.unsetCursorItalic();
          break;
        case 24:
          handler.unsetCursorUnderline();
          break;
        case 25:
          handler.unsetCursorBlink();
          break;
        case 27:
          handler.unsetCursorInverse();
          break;
        case 28:
          handler.unsetCursorInvisible();
          break;
        case 29:
          handler.unsetCursorStrikethrough();
          break;
        case 30:
          handler.setForegroundColor16(NamedColor.black);
          break;
        case 31:
          handler.setForegroundColor16(NamedColor.red);
          break;
        case 32:
          handler.setForegroundColor16(NamedColor.green);
          break;
        case 33:
          handler.setForegroundColor16(NamedColor.yellow);
          break;
        case 34:
          handler.setForegroundColor16(NamedColor.blue);
          break;
        case 35:
          handler.setForegroundColor16(NamedColor.magenta);
          break;
        case 36:
          handler.setForegroundColor16(NamedColor.cyan);
          break;
        case 37:
          handler.setForegroundColor16(NamedColor.white);
          break;
        case 38:
          if (i + 1 < _params.length) {
            switch (_params[i + 1]) {
              case 2: // RGB
                if (i + 4 < _params.length) {
                  handler.setForegroundColorRgb(
                      _params[i + 2], _params[i + 3], _params[i + 4]);
                  i += 4;
                }
                break;
              case 5: // 256-colour
                if (i + 2 < _params.length) {
                  handler.setForegroundColor256(_params[i + 2]);
                  i += 2;
                }
                break;
            }
          }
          break;
        case 39:
          handler.resetForeground();
          break;
        case 40:
          handler.setBackgroundColor16(NamedColor.black);
          break;
        case 41:
          handler.setBackgroundColor16(NamedColor.red);
          break;
        case 42:
          handler.setBackgroundColor16(NamedColor.green);
          break;
        case 43:
          handler.setBackgroundColor16(NamedColor.yellow);
          break;
        case 44:
          handler.setBackgroundColor16(NamedColor.blue);
          break;
        case 45:
          handler.setBackgroundColor16(NamedColor.magenta);
          break;
        case 46:
          handler.setBackgroundColor16(NamedColor.cyan);
          break;
        case 47:
          handler.setBackgroundColor16(NamedColor.white);
          break;
        case 48:
          if (i + 1 < _params.length) {
            switch (_params[i + 1]) {
              case 2: // RGB
                if (i + 4 < _params.length) {
                  handler.setBackgroundColorRgb(
                      _params[i + 2], _params[i + 3], _params[i + 4]);
                  i += 4;
                }
                break;
              case 5: // 256-colour
                if (i + 2 < _params.length) {
                  handler.setBackgroundColor256(_params[i + 2]);
                  i += 2;
                }
                break;
            }
          }
          break;
        case 49:
          handler.resetBackground();
          break;
        case 90:
          handler.setForegroundColor16(NamedColor.brightBlack);
          break;
        case 91:
          handler.setForegroundColor16(NamedColor.brightRed);
          break;
        case 92:
          handler.setForegroundColor16(NamedColor.brightGreen);
          break;
        case 93:
          handler.setForegroundColor16(NamedColor.brightYellow);
          break;
        case 94:
          handler.setForegroundColor16(NamedColor.brightBlue);
          break;
        case 95:
          handler.setForegroundColor16(NamedColor.brightMagenta);
          break;
        case 96:
          handler.setForegroundColor16(NamedColor.brightCyan);
          break;
        case 97:
          handler.setForegroundColor16(NamedColor.brightWhite);
          break;
        case 100:
          handler.setBackgroundColor16(NamedColor.brightBlack);
          break;
        case 101:
          handler.setBackgroundColor16(NamedColor.brightRed);
          break;
        case 102:
          handler.setBackgroundColor16(NamedColor.brightGreen);
          break;
        case 103:
          handler.setBackgroundColor16(NamedColor.brightYellow);
          break;
        case 104:
          handler.setBackgroundColor16(NamedColor.brightBlue);
          break;
        case 105:
          handler.setBackgroundColor16(NamedColor.brightMagenta);
          break;
        case 106:
          handler.setBackgroundColor16(NamedColor.brightCyan);
          break;
        case 107:
          handler.setBackgroundColor16(NamedColor.brightWhite);
          break;
        default:
          handler.unsupportedStyle(p);
          break;
      }
    }
  }

  /// `CSI n Ps` — Device Status Report (DSR)
  void _csiDeviceStatusReport() {
    switch (_p(0)) {
      case 5:
        handler.sendOperatingStatus();
        break;
      case 6:
        handler.sendCursorPosition();
        break;
    }
  }

  /// `CSI r Ps ; Ps` — Set Top and Bottom Margins (DECSTBM)
  void _csiSetMargins() {
    if (_params.length > 2) return;
    final top = _p1(0);
    final bottom = _params.length == 2 ? _params[1] - 1 : null;
    handler.setMargins(top - 1, bottom);
  }

  /// `CSI t Ps …` — Window Manipulation
  void _csiWindowManipulation() {
    if (_params.isEmpty) return;
    switch (_params[0]) {
      case 8: // Set window size in characters
        if (_params.length == 3) {
          handler.resize(_params[2], _params[1]);
        }
        break;
      case 18: // Report terminal size
        handler.sendSize();
        break;
      // All other sub-commands are out of scope or security-sensitive.
      default:
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Mode dispatch helpers
  // ─────────────────────────────────────────────────────────────────────────────

  void _setMode(int mode, bool enabled) {
    switch (mode) {
      case 4:
        handler.setInsertMode(enabled);
        break;
      case 20:
        handler.setLineFeedMode(enabled);
        break;
      default:
        handler.setUnknownMode(mode, enabled);
        break;
    }
  }

  void _setDecMode(int mode, bool enabled) {
    switch (mode) {
      case 1:
        handler.setCursorKeysMode(enabled);
        break;
      case 3:
        handler.setColumnMode(enabled);
        break;
      case 5:
        handler.setReverseDisplayMode(enabled);
        break;
      case 6:
        handler.setOriginMode(enabled);
        break;
      case 7:
        handler.setAutoWrapMode(enabled);
        break;
      case 9:
        handler.setMouseMode(
            enabled ? MouseMode.clickOnly : MouseMode.none);
        break;
      case 12:
      case 13:
        handler.setCursorBlinkMode(enabled);
        break;
      case 25:
        handler.setCursorVisibleMode(enabled);
        break;
      case 47:
        if (enabled) {
          handler.useAltBuffer();
        } else {
          handler.useMainBuffer();
        }
        break;
      case 66:
        handler.setAppKeypadMode(enabled);
        break;
      case 1000:
      case 10061000:
        handler.setMouseMode(
            enabled ? MouseMode.upDownScroll : MouseMode.none);
        break;
      case 1001:
        handler.setMouseMode(
            enabled ? MouseMode.upDownScroll : MouseMode.none);
        break;
      case 1002:
        handler.setMouseMode(
            enabled ? MouseMode.upDownScrollDrag : MouseMode.none);
        break;
      case 1003:
        handler.setMouseMode(
            enabled ? MouseMode.upDownScrollMove : MouseMode.none);
        break;
      case 1004:
        handler.setReportFocusMode(enabled);
        break;
      case 1005:
        handler.setMouseReportMode(
            enabled ? MouseReportMode.utf : MouseReportMode.normal);
        break;
      case 1006:
        handler.setMouseReportMode(
            enabled ? MouseReportMode.sgr : MouseReportMode.normal);
        break;
      case 1007:
        handler.setAltBufferMouseScrollMode(enabled);
        break;
      case 1015:
        handler.setMouseReportMode(
            enabled ? MouseReportMode.urxvt : MouseReportMode.normal);
        break;
      case 1047:
        if (enabled) {
          handler.useAltBuffer();
        } else {
          handler.clearAltBuffer();
          handler.useMainBuffer();
        }
        break;
      case 1048:
        if (enabled) {
          handler.saveCursor();
        } else {
          handler.restoreCursor();
        }
        break;
      case 1049:
        if (enabled) {
          handler.saveCursor();
          handler.clearAltBuffer();
          handler.useAltBuffer();
        } else {
          handler.useMainBuffer();
        }
        break;
      case 2004:
        handler.setBracketedPasteMode(enabled);
        break;
      default:
        handler.setUnknownDecMode(mode, enabled);
        break;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  void _clearCsi() {
    _prefix = 0;
    _params.clear();
    _currentParam = 0;
    _hasCurrentParam = false;
  }
}
