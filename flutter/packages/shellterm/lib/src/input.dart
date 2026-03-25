/// Terminal key definitions and input handling.
library;

/// Terminal key identifiers for key input handling.
enum TerminalKey {
  none,
  // Modifier keys
  shiftLeft, shiftRight, controlLeft, controlRight, altLeft, altRight,
  metaLeft, metaRight,
  // Navigation
  arrowUp, arrowDown, arrowLeft, arrowRight,
  home, end, pageUp, pageDown,
  insert, delete,
  // Editing
  backspace, tab, enter, escape, space,
  // Function keys
  f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12,
  // Alphabet
  keyA, keyB, keyC, keyD, keyE, keyF, keyG, keyH, keyI, keyJ,
  keyK, keyL, keyM, keyN, keyO, keyP, keyQ, keyR, keyS, keyT,
  keyU, keyV, keyW, keyX, keyY, keyZ,
  // Numbers
  digit0, digit1, digit2, digit3, digit4, digit5, digit6, digit7, digit8, digit9,
  // Symbols
  minus, equal, bracketLeft, bracketRight, backslash, semicolon,
  quote, backquote, comma, period, slash,
}

/// Handler signature for terminal key input.
typedef TerminalInputHandler = String? Function(
  TerminalKey key, {
  bool ctrl,
  bool alt,
  bool shift,
});

/// Default input handler mapping terminal keys to escape sequences.
String? defaultInputHandler(
  TerminalKey key, {
  bool ctrl = false,
  bool alt = false,
  bool shift = false,
  bool appCursorKeys = false,
  bool appKeypadMode = false,
}) {
  switch (key) {
    case TerminalKey.enter:
      return '\r';
    case TerminalKey.backspace:
      return alt ? '\x1b\x7f' : '\x7f';
    case TerminalKey.tab:
      return shift ? '\x1b[Z' : '\t';
    case TerminalKey.escape:
      return '\x1b';
    case TerminalKey.arrowUp:
      return appCursorKeys ? '\x1bOA' : '\x1b[A';
    case TerminalKey.arrowDown:
      return appCursorKeys ? '\x1bOB' : '\x1b[B';
    case TerminalKey.arrowRight:
      return appCursorKeys ? '\x1bOC' : '\x1b[C';
    case TerminalKey.arrowLeft:
      return appCursorKeys ? '\x1bOD' : '\x1b[D';
    case TerminalKey.home:
      return '\x1b[H';
    case TerminalKey.end:
      return '\x1b[F';
    case TerminalKey.pageUp:
      return '\x1b[5~';
    case TerminalKey.pageDown:
      return '\x1b[6~';
    case TerminalKey.insert:
      return '\x1b[2~';
    case TerminalKey.delete:
      return '\x1b[3~';
    case TerminalKey.f1:
      return '\x1bOP';
    case TerminalKey.f2:
      return '\x1bOQ';
    case TerminalKey.f3:
      return '\x1bOR';
    case TerminalKey.f4:
      return '\x1bOS';
    case TerminalKey.f5:
      return '\x1b[15~';
    case TerminalKey.f6:
      return '\x1b[17~';
    case TerminalKey.f7:
      return '\x1b[18~';
    case TerminalKey.f8:
      return '\x1b[19~';
    case TerminalKey.f9:
      return '\x1b[20~';
    case TerminalKey.f10:
      return '\x1b[21~';
    case TerminalKey.f11:
      return '\x1b[23~';
    case TerminalKey.f12:
      return '\x1b[24~';
    default:
      return null;
  }
}
