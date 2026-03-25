/// Escape sequence generation for terminal responses.
///
/// Pure functions — no state. Used by [Terminal] to send device attribute
/// responses, cursor position reports, etc. back to the host.
library;

abstract final class EscapeEmitter {
  /// Primary device attributes response: "I am a VT220".
  static const String primaryDA = '\x1b[?62;22c';

  /// Secondary device attributes response.
  static const String secondaryDA = '\x1b[>0;0;0c';

  /// Device status report: "OK".
  static const String deviceStatusOk = '\x1b[0n';

  /// Cursor position report: `ESC [ row ; col R` (1-based).
  static String cursorPosition(int row, int col) =>
      '\x1b[${row + 1};${col + 1}R';

  /// Wrap text in bracketed paste markers.
  static String bracketedPaste(String text) =>
      '\x1b[200~$text\x1b[201~';

  /// Application keypad mode key sequences.
  static String keypadKey(int key) => '\x1bO${String.fromCharCode(key)}';

  /// CSI-style key with modifiers.
  static String csiKey(String code, {int modifier = 0}) {
    if (modifier > 0) {
      return '\x1b[1;${modifier + 1}$code';
    }
    return '\x1b[$code';
  }

  /// SS3-style key (application mode arrows, etc.).
  static String ss3Key(String code) => '\x1bO$code';
}
