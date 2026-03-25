/// Mouse mode handling for terminal emulation.
library;

/// Mouse tracking modes set by CSI ? Pm h/l.
enum MouseMode {
  /// No mouse tracking.
  none,
  /// X10 compatibility (button press only).
  x10,
  /// VT200 (button press + release).
  vt200,
  /// Button event tracking (press + release + motion while pressed).
  buttonEvent,
  /// Any event tracking (all motion).
  anyEvent,
}

/// Mouse encoding modes.
enum MouseEncoding {
  /// Default X10/X11 encoding.
  normal,
  /// UTF-8 encoding (mode 1005).
  utf8,
  /// SGR encoding (mode 1006) — supports coordinates > 223.
  sgr,
  /// URXVT encoding (mode 1015).
  urxvt,
}

/// Mouse button identifiers.
enum TerminalMouseButton {
  left,
  middle,
  right,
  wheelUp,
  wheelDown,
  none,
}

/// Encode a mouse event into an escape sequence for the host.
String? encodeMouseEvent({
  required MouseEncoding encoding,
  required TerminalMouseButton button,
  required int col,
  required int row,
  bool pressed = true,
  bool shift = false,
  bool alt = false,
  bool ctrl = false,
}) {
  var code = switch (button) {
    TerminalMouseButton.left => 0,
    TerminalMouseButton.middle => 1,
    TerminalMouseButton.right => 2,
    TerminalMouseButton.wheelUp => 64,
    TerminalMouseButton.wheelDown => 65,
    TerminalMouseButton.none => 3, // release
  };

  if (shift) code |= 4;
  if (alt) code |= 8;
  if (ctrl) code |= 16;

  switch (encoding) {
    case MouseEncoding.sgr:
      final action = pressed ? 'M' : 'm';
      return '\x1b[<$code;${col + 1};${row + 1}$action';

    case MouseEncoding.normal:
      if (col > 222 || row > 222) return null; // Coordinate overflow
      return '\x1b[M'
          '${String.fromCharCode(code + 32)}'
          '${String.fromCharCode(col + 33)}'
          '${String.fromCharCode(row + 33)}';

    case MouseEncoding.utf8:
      return '\x1b[M'
          '${String.fromCharCode(code + 32)}'
          '${String.fromCharCode(col + 33)}'
          '${String.fromCharCode(row + 33)}';

    case MouseEncoding.urxvt:
      return '\x1b[${code + 32};${col + 1};${row + 1}M';
  }
}
