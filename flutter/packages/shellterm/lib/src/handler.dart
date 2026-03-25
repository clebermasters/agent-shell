/// Abstract interface for escape sequence dispatch.
///
/// The [EscapeParser] calls these methods as it recognizes control characters,
/// ESC sequences, CSI sequences, OSC strings, and DCS passthrough data.
/// [Terminal] implements this interface.
library;

/// Handler interface for VT500 escape sequences.
abstract class EscapeHandler {
  // ── Single-byte controls (C0) ──────────────────────────────────────────

  void bell();
  void backspace();
  void tab();
  void lineFeed();
  void carriageReturn();
  void shiftOut(); // SO — switch to G1 charset
  void shiftIn(); // SI — switch to G0 charset

  /// Write a printable character at the cursor position.
  void writeChar(int codepoint);

  // ── ESC sequences ──────────────────────────────────────────────────────

  void saveCursor(); // ESC 7
  void restoreCursor(); // ESC 8
  void index(); // ESC D — move cursor down, scroll if at bottom
  void reverseIndex(); // ESC M — move cursor up, scroll if at top
  void nextLine(); // ESC E — CR + LF
  void setTabStop(); // ESC H
  void resetState(); // ESC c — full terminal reset
  void setAppKeypadMode(bool enabled); // ESC = / ESC >
  void designateCharset(int slot, int charset); // ESC ( / ) / * / +

  // ── CSI sequences ──────────────────────────────────────────────────────

  void setCursorPosition(int row, int col); // CSI H / CSI f
  void cursorUp(int n); // CSI A
  void cursorDown(int n); // CSI B
  void cursorForward(int n); // CSI C
  void cursorBackward(int n); // CSI D
  void cursorNextLine(int n); // CSI E
  void cursorPrevLine(int n); // CSI F
  void cursorToColumn(int col); // CSI G / CSI `
  void cursorToRow(int row); // CSI d

  void eraseInDisplay(int mode); // CSI J (0=below, 1=above, 2=all, 3=scrollback)
  void eraseInLine(int mode); // CSI K (0=right, 1=left, 2=all)
  void eraseChars(int n); // CSI X

  void insertLines(int n); // CSI L
  void deleteLines(int n); // CSI M
  void insertChars(int n); // CSI @
  void deleteChars(int n); // CSI P

  void scrollUp(int n); // CSI S
  void scrollDown(int n); // CSI T

  void setScrollMargins(int top, int bottom); // CSI r
  void tabForward(int n); // CSI I
  void tabBackward(int n); // CSI Z
  void clearTabStop(int mode); // CSI g

  void setMode(int mode, bool value); // CSI h / CSI l
  void setDecMode(int mode, bool value); // CSI ? h / CSI ? l

  void sendDeviceAttributes(int mode); // CSI c / CSI > c
  void sendDeviceStatus(int mode); // CSI n
  void sendCursorPosition(); // CSI 6 n

  void setCharacterProtection(int mode); // CSI " q
  void setCursorStyle(int style); // CSI SP q
  void repeatChar(int n); // CSI b

  // ── SGR (Select Graphic Rendition) ─────────────────────────────────────

  void resetStyle(); // SGR 0
  void setBold(bool enabled);
  void setFaint(bool enabled);
  void setItalic(bool enabled);
  void setUnderline(bool enabled);
  void setBlink(bool enabled);
  void setInverse(bool enabled);
  void setInvisible(bool enabled);
  void setStrikethrough(bool enabled);
  void setForegroundColor16(int color); // SGR 30-37, 90-97
  void setBackgroundColor16(int color); // SGR 40-47, 100-107
  void setForegroundColor256(int index); // SGR 38;5;n
  void setBackgroundColor256(int index); // SGR 48;5;n
  void setForegroundColorRgb(int r, int g, int b); // SGR 38;2;r;g;b
  void setBackgroundColorRgb(int r, int g, int b); // SGR 48;2;r;g;b
  void resetForeground(); // SGR 39
  void resetBackground(); // SGR 49

  // ── OSC (Operating System Command) ────────────────────────────────────

  void setTitle(String title); // OSC 0 / OSC 2
  void setIconName(String name); // OSC 1

  // ── DCS (Device Control String) ────────────────────────────────────────

  void dcsHook(List<int> params, List<int> intermediates);
  void dcsPut(int byte);
  void dcsUnhook();

  // ── Fallback ───────────────────────────────────────────────────────────

  /// Called for unrecognized escape sequences.
  void unknownEscape(int char) {}

  /// Called for unrecognized CSI sequences.
  void unknownCsi(int finalByte, List<int> params) {}
}
