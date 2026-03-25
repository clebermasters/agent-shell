# Terminal Text Overlap / Mixing — Investigation & Fix

## The Symptom

During heavy terminal output (e.g., Claude Code streaming responses), text becomes unreadable: lines overlap, mix, and pile up on top of each other. Triggers include:

- Keyboard appearing or disappearing (soft keyboard show/hide)
- Screen orientation change
- Any event that resizes the terminal viewport
- Heavy streaming output even with no resize (~10% of the time)

The only workaround was volume up/down (zoom in/out), which would trigger a redraw and clear the corrupted state.

---

## Phase 1–4: First Round of Fixes (Did Not Resolve the Issue)

The initial investigation assumed the corruption was a **rendering problem**. Four fixes were applied on branch `fix/terminal-text-overlap`:

| Fix | File | Change |
|-----|------|--------|
| Disable client-side reflow | `terminal_service.dart` | `reflowEnabled: false` — tmux handles reflow server-side; local reflow conflicted with tmux redraws |
| Clear paragraph cache on resize | `render.dart` | Cache cleared after resize so stale glyph measurements don't produce wrong cell positions |
| Zoom sync flag | `terminal_view_widget.dart` | Added `_zooming` flag to prevent conflicting state during zoom transitions |
| Ceil cell width | `painter.dart` | `ceilToDouble()` on cell width measurement so glyphs never bleed into adjacent cells |

These fixes improved zoom stability but **did not fix the primary corruption**. The text was still bad during keyboard show/hide.

---

## Root Cause: Resize Timing Race

Deep investigation of the rendering pipeline confirmed:

- Dart is single-threaded — no true paint/write race is possible
- The paragraph cache, buffer operations, and scroll handling are all structurally sound

The actual cause is a **race between client resize and tmux SIGWINCH propagation**.

### What happens step by step

1. User hides the keyboard → viewport shrinks (e.g., 24 → 15 rows)
2. Flutter's `RenderTerminal.performLayout()` **immediately** calls `terminal.resize()` — the buffer is now 15 rows
3. A resize message is sent to the backend via WebSocket
4. **50–200ms of network + processing latency** before tmux receives SIGWINCH and processes it
5. During that window, tmux is still sending output formatted for **24 rows**
6. Cursor position sequences arrive for rows beyond the new viewport — e.g., `CSI 20;1 H` (move to row 20)
7. `buffer.setCursorY()` clamps row 20 to row 14 (the new last row)
8. The next sequence, `CSI 21;1 H`, also clamps to row 14
9. **Multiple lines of content accumulate on the same row** → overlapping, unreadable text

### Why zoom fixed it

Zoom triggers another resize → tmux receives a new SIGWINCH → tmux sends a **complete screen redraw** for the new dimensions → this redraw overwrites the corrupted content with correct content.

### Why Phase 1–4 didn't fix it

All four fixes addressed the zoom/reflow path. None of them touched the **keyboard/orientation resize path**, which is the actual trigger. The corruption is a data problem (tmux sending cursor positions for wrong dimensions), not a rendering problem.

---

## Phase 5–7: The Actual Fix

Committed as `c1cccb26` on branch `fix/terminal-text-overlap`.

### Phase 5 — Resize Data Buffering (Primary Fix)

**File**: `flutter/lib/data/services/terminal_service.dart`

After sending a resize to the backend, all incoming terminal data is buffered for **300ms**. This window covers the SIGWINCH propagation latency. When the timer fires, the buffer is flushed synchronously — tmux's full screen redraw is always the last item in the queue, so the UI only paints the correct final state.

The existing `_hydrating` / `_hydrationQueue` mechanism (already used for history bootstrap) was reused — no new infrastructure was needed.

```dart
void resizeTerminal(String sessionName, int cols, int rows) {
  _wsService.resizeTerminal(sessionName, cols, rows);

  _resizeGuardTimers[sessionName]?.cancel();
  _hydrating[sessionName] = true;
  _hydrationQueue[sessionName] = [];

  _resizeGuardTimers[sessionName] = Timer(
    const Duration(milliseconds: 300),
    () {
      final terminal = _terminals[sessionName];
      if (terminal == null) return;

      _hydrating[sessionName] = false;
      final queue = _hydrationQueue[sessionName] ?? [];
      for (final data in queue) {
        terminal.write(data);
      }
      _hydrationQueue[sessionName] = [];
      _resizeGuardTimers.remove(sessionName);
    },
  );
}
```

**Trade-off**: After a resize (keyboard show/hide), the terminal display is frozen for up to 300ms, then snaps to the correct state. This is preferable to corrupted/overlapping text.

### Phase 6 — Handle DCS Sequences (Secondary Fix)

**File**: `flutter/packages/xterm/lib/src/core/escape/parser.dart`

`ESC P` (Device Control String) was previously unhandled. The parser consumed the `P` character but left the DCS payload to be interpreted as regular text and escape sequences, producing garbled output.

Added a handler that silently consumes the entire DCS payload until the String Terminator (`ESC \`) or BEL:

```dart
'P'.charCode: _escHandleDCS,

bool _escHandleDCS() {
  while (true) {
    if (_queue.isEmpty) return false;
    final char = _queue.consume();
    if (char == Ascii.ESC) {
      if (_queue.isEmpty) return false;
      _queue.consume();
      return true;
    }
    if (char == Ascii.BEL) return true;
  }
}
```

### Phase 7 — Fix 1-Param CSI Cursor Position (Minor Fix)

**File**: `flutter/packages/xterm/lib/src/core/escape/parser.dart`

`_csiHandleCursorPosition()` required exactly 2 parameters. The 1-parameter form (`CSI 5 H` = move to row 5, column 1) fell through to defaults, placing the cursor at (1, 1) instead of (5, 1).

Fixed to handle 0, 1, or 2 parameters:

```dart
void _csiHandleCursorPosition() {
  var row = 1;
  var col = 1;
  if (_csi.params.isNotEmpty) {
    row = _csi.params[0];
    if (_csi.params.length >= 2) {
      col = _csi.params[1];
    }
  }
  handler.setCursor(col - 1, row - 1);
}
```

---

## Phases 5–11: Did Not Fully Resolve the Issue

After building and testing, corruption was still happening. The resize buffering eliminated the keyboard show/hide case but text was still getting corrupted **~10% of the time during normal heavy output with no resize at all**. Additionally, after the Round 3 parser fixes were applied, zoom (volume keys) stopped working — the terminal would change font size visually but the content would not reflow to fit the new dimensions.

This revealed two separate remaining problems:
1. **Parser still corrupting data** — the bounds checks in Phases 8–11 were band-aids. The real issue was that any uncaught exception in any parser handler would abort the entire `Terminal.write()` call, silently losing all remaining data in that WebSocket message.
2. **Zoom architecture was fundamentally broken** — the `_zooming` flag suppressed `autoResize`, causing the painter to use new cell sizes while the terminal buffer kept old dimensions. When the 500ms debounce fired, `_resizeTerminalIfNeeded()` sometimes did not fire at all (if the new cell size happened to produce the same cols/rows), leaving the display permanently mismatched.

---

## Round 3 Investigation: Parser Bugs

Deeper investigation of the escape sequence parser revealed the real root cause.

### How the data flows

The Rust backend reads PTY output in **8KB chunks** and sends each chunk as a separate WebSocket message. There is no guarantee that chunk boundaries align with ANSI escape sequence boundaries. A sequence like `\x1b[38;2;255;128;0m` (RGB foreground color) can be split: the first message ends with `\x1b[38;2;255;128` and the next starts with `;0m`.

The parser handles this correctly in most cases via its rollback mechanism — an incomplete sequence is rolled back and waits for the next `write()` call. But several bugs prevented this from working reliably.

### Bug 1 (Critical): SGR extended color — no bounds check

**File**: `parser.dart`, lines 517–532 and 562–577

```dart
case 38:
  final mode = params[i + 1];   // RangeError if split at chunk boundary!
  case 2:
    final r = params[i + 2];
    final g = params[i + 3];
    final b = params[i + 4];    // params may only have [38, 2, 255, 128]
```

When `\x1b[38;2;255;128` arrives without the final `;0m`, the CSI parser sees the chunk end as a terminator. The params array contains `[38, 2, 255, 128]`. Then `_csiHandleSgr` accesses `params[i+4]` → **RangeError**. Since `Terminal.write()` has no try/catch, the exception propagates up and **aborts the entire write call**, losing all remaining data in that WebSocket message.

Every colored terminal output uses SGR 38/48, and chunk boundaries are random — this explains the ~10% rate.

### Bug 2 (High): OSC termination destroys the next escape sequence

**File**: `parser.dart`, `_consumeOsc()`

```dart
if (char == Ascii.ESC) {
  if (_queue.isEmpty) return false;
  if (_queue.consume() == Ascii.backslash) {
    _osc.add(param.toString());
  }
  return true;  // returns true even if the byte after ESC was NOT backslash!
}
```

If an OSC string is immediately followed by a CSI — e.g., `\x1b]0;title\x1b[2J` — the parser sees ESC, consumes `[`, and returns true. The `2J` (erase display) is now orphaned and rendered as visible text `2J`. tmux and shells emit OSC title updates constantly.

### Bug 3 (High): CSI loop silently consumes non-ASCII characters

**File**: `parser.dart`, `_consumeCsi()`

The CSI parsing loop handles chars 0x01–0x2F, 0x30–0x39 (digits), 0x3B (semicolon), and 0x40–0x7E (final byte). Any character with value 0 or > 126 falls through all conditions and loops back silently. During heavy UTF-8 output, if a CSI hits a chunk boundary and the next chunk starts with non-ASCII text, the parser eats visible characters indefinitely looking for a CSI final byte.

### Bug 4 (Medium): DCS handler consumed next escape's first byte

**File**: `parser.dart`, `_escHandleDCS()` (added in Phase 6)

The DCS handler we added in Phase 6 consumed any byte after ESC as the "terminator", not just backslash. If a DCS was immediately followed by `ESC [` (the start of a CSI), the `[` was consumed as the terminator, and the CSI sequence was destroyed.

---

## Phase 8–11: Parser Bug Fixes

Committed as `f00c043a` on branch `fix/terminal-text-overlap`.

### Phase 8 — SGR bounds checking (Critical fix)

Added bounds guards before every `params[i+N]` access in both case 38 (foreground) and case 48 (background). If the params array is too short (truncated sequence), the color command is silently skipped instead of throwing:

```dart
case 38:
  if (i + 1 >= params.length) continue;
  final mode = params[i + 1];
  switch (mode) {
    case 2:
      if (i + 4 >= params.length) { i = params.length - 1; break; }
      final r = params[i + 2];
      final g = params[i + 3];
      final b = params[i + 4];
      handler.setForegroundColorRgb(r, g, b);
      i += 4;
      break;
    case 5:
      if (i + 2 >= params.length) { i = params.length - 1; break; }
      final index = params[i + 2];
      handler.setForegroundColor256(index);
      i += 2;
      break;
  }
  continue;
```

Same pattern applied to case 48 (background).

### Phase 9 — OSC ESC termination fix

When `_consumeOsc()` sees ESC followed by a non-backslash byte, it now rolls back both bytes so the following escape sequence is preserved:

```dart
final next = _queue.consume();
if (next == Ascii.backslash) {
  _osc.add(param.toString());
} else {
  _osc.add(param.toString());
  _queue.rollback(2);  // put ESC and the non-backslash byte back
}
return true;
```

### Phase 10 — CSI non-ASCII termination

Added an explicit fallthrough case after the final-byte check. Any character that is not a valid CSI byte (NULL or code point > 126) now terminates the CSI as malformed and puts the invalid character back for normal text processing:

```dart
// Invalid byte: malformed CSI. Put it back for normal text processing.
if (hasParam) _csi.params.add(param);
_csi.finalByte = 0;  // no handler will match
_queue.rollback(1);
return true;
```

### Phase 11 — DCS handler ESC validation

Updated `_escHandleDCS()` to validate that the byte after ESC is actually backslash. If not, both bytes are rolled back:

```dart
final next = _queue.consume();
if (next == Ascii.backslash) {
  return true;  // Proper ST (ESC \)
}
// Not ST — roll back both bytes so the next escape sequence is not lost
_queue.rollback(2);
return true;
```

---

## Round 4 Investigation: Architectural Weaknesses

After 11 phases of incremental fixes failed to resolve the issue (and introduced a zoom regression), a full architectural review was performed. Six fundamental weaknesses were identified:

### Weakness 1 (Critical): No Error Isolation in Terminal.write()

`Terminal.write()` and `EscapeParser.write()` had **zero error handling**. Any uncaught exception in any parser handler — SGR, CSI, OSC, or DCS — propagated all the way up, aborting the entire write call. `notifyListeners()` never fired, the UI didn't repaint, and all remaining data in that WebSocket message was silently lost. The bounds checks in Phases 8–11 were attempts to prevent every possible exception rather than catching them. This approach can never be complete.

### Weakness 2 (High): Parser Cannot Handle Colon Subparameters

Modern terminals use colon-separated subparameters per ITU T.416 (e.g., `38:2::R:G:B`). tmux emits these. A colon (0x3A) inside the CSI parameter loop fell through all conditions and hit the "invalid byte" branch, which **terminated the entire CSI sequence as malformed**. Every colon-syntax color sequence was silently destroyed.

### Weakness 3 (High): CSI Intermediate Bytes Were Discarded

Characters 0x20–0x2F (the ECMA-48 "intermediate byte" range) were consumed and thrown away with a comment `// intermediates.add(char)`. Sequences like `CSI Ps SP q` (cursor style) lost their intermediate bytes and were misrouted.

### Weakness 4 (High): Zoom/Resize Architecture Was Fragile

The zoom flow involved 5 interacting mechanisms: `_zooming` flag, `_zoomResizeDebounce` timer (500ms), `_lastCols`/`_lastRows` guards, `_resizeDebounce` timer (150ms), and the 300ms resize guard in `TerminalService`. When `_zooming = true`, `autoResize` was suppressed, so the painter used new cell sizes while the terminal buffer kept old dimensions. When the debounce fired, `_resizeTerminalIfNeeded()` only triggered if the computed viewport size *differed* from the stored `_viewportSize` — if the new font size happened to produce the same cols/rows (common at certain sizes), no resize fired and the display stayed permanently broken.

### Weakness 5 (Medium): SGR Unknown Sub-Modes Did Not Advance Index

In `_csiHandleSgr()`, case 38/48 with an unknown mode value (not 2 or 5) fell through the inner `switch` without advancing `i`. The outer `continue` only incremented by 1, so the mode value was re-interpreted as a standalone SGR code on the next iteration (e.g., mode 3 was interpreted as SGR 3 = italic).

### Weakness 6 (Low): Resize Guard Could Lose History Bootstrap Data

`_hydrating` and `_hydrationQueue` served double duty for history bootstrap and resize guard. If a resize occurred while history was streaming, `resizeTerminal()` cleared the queue, discarding any live data already buffered by the history bootstrap.

---

## Round 4: Complete Redesign (Phases 12–15)

A full redesign of the affected subsystems, addressing all six weaknesses.

### Phase 12 — Error-Resilient Terminal.write() (Critical Fix)

**Files**: `flutter/packages/xterm/lib/src/terminal.dart`, `flutter/packages/xterm/lib/src/core/escape/parser.dart`

Wrapped `_parser.write()` in try/catch in `Terminal.write()`. On any uncaught exception, `_parser.reset()` is called to clear the ByteConsumer queue and all parser state, so the next `write()` call starts clean. This single change makes the parser robust against any future parser bug — the worst outcome is losing the current chunk, not an indefinite broken state.

```dart
void write(String data) {
  try {
    _parser.write(data);
  } catch (_) {
    _parser.reset();
  }
  notifyListeners();
}
```

Added `reset()` to `EscapeParser`:
```dart
void reset() {
  _queue.reset();
  _csi.params.clear();
  _csi.prefix = null;
  _csi.finalByte = 0;
  _csi.intermediate = 0;
  _osc.clear();
}
```

### Phase 13 — Robust CSI Parameter Parsing (High Fix)

**File**: `flutter/packages/xterm/lib/src/core/escape/parser.dart`

Rewrote `_consumeCsi()`:

1. **Colon subparameters**: Colon (0x3A) is now treated as a parameter separator alongside semicolon (0x3B). Sub-parameters are flattened into the main params list, so existing SGR handlers work without modification. `38:2::R:G:B` now parses as `[38, 2, 0, R, G, B]` — the `0` from the empty sub-param is harmless.

2. **Intermediate bytes**: Bytes 0x20–0x2F are now accumulated into `_csi.intermediate` instead of being discarded. This prevents misrouting of sequences that use intermediate bytes.

3. **Prefix detection fix**: The prefix detection range was corrected from `Ascii.colon..Ascii.questionMark` to `Ascii.lessThan..Ascii.questionMark` (0x3C–0x3F), excluding colon and semicolon which are now correctly treated as parameter separators.

Also fixed SGR case 38/48 unknown sub-mode handling: added `default: i += 1` so the mode byte is always skipped instead of being re-interpreted as a standalone SGR code.

### Phase 14 — Simplified Zoom/Resize Flow (High Fix)

**Files**: `flutter/lib/features/terminal/widgets/terminal_view_widget.dart`, `flutter/lib/data/services/terminal_service.dart`

**Removed entirely**: `_zooming` flag, `_zoomResizeDebounce` timer, `_scheduleZoomResize()` method. `autoResize` is now always `true`. The terminal buffer always matches the visual dimensions — no more mismatch between painter cell size and buffer column count.

`_zoomIn()`/`_zoomOut()` now just change `_fontSize`, call `setState()`, and reset `_lastCols`/`_lastRows` to 0 so the next layout pass always fires `onResize`.

`resizeTerminal()` redesigned with a two-phase timer:
- **Phase 1 (debounce, 300ms)**: Rapid resize calls (e.g., volume key held down) coalesce into a single backend message with the latest dimensions.
- **Phase 2 (SIGWINCH guard, 300ms)**: After sending to the backend, buffer incoming data for another 300ms to cover tmux SIGWINCH propagation latency.

```dart
void resizeTerminal(String sessionName, int cols, int rows) {
  _resizeGuardTimers[sessionName]?.cancel();
  _resizeGuarding[sessionName] = true;
  _pendingQueue[sessionName] ??= [];

  _resizeGuardTimers[sessionName] = Timer(const Duration(milliseconds: 300), () {
    _wsService.resizeTerminal(sessionName, cols, rows);
    _resizeGuardTimers[sessionName] = Timer(const Duration(milliseconds: 300), () {
      _resizeGuarding[sessionName] = false;
      _flushIfReady(sessionName);
      _resizeGuardTimers.remove(sessionName);
    });
  });
}
```

### Phase 15 — Separate Hydration Queues (Low Fix)

**File**: `flutter/lib/data/services/terminal_service.dart`

Replaced the shared `_hydrating`/`_hydrationQueue` with two independent blocking states and one shared queue:

- `_historyHydrating` — set true on `terminal-history-start`, false on `terminal-history-end`
- `_resizeGuarding` — set true immediately on resize call, false after SIGWINCH guard expires
- `_pendingQueue` — data is queued if EITHER state is true; flushed only when BOTH are false

Helper methods:
```dart
bool _isBlocking(String session) =>
    _historyHydrating[session] == true || _resizeGuarding[session] == true;

void _flushIfReady(String session) {
  if (_isBlocking(session)) return;
  // write all queued data to terminal, clear queue
}
```

This prevents a resize during history streaming from discarding buffered history data.

---

## Verification

Build and install the debug APK:

```bash
cd flutter && ./build.sh debug --install
```

Test scenarios:

1. **Heavy output without any resize** — text must stay clean (primary test for Phases 12–13)
2. **Zoom in/out via volume keys** — text must reflow to fit the new dimensions (primary test for Phase 14)
3. **Rapid zoom in/out** — terminal stays readable throughout
4. **Keyboard show/hide during heavy output** — no corruption
5. **Colored output (ls --color, htop)** — no garbled characters; validates Phase 13 colon subparam fix
6. **Session reconnect after zoom** — history bootstrap completes correctly (validates Phase 15)
7. **Orientation change during output** — no corruption

---

## Files Changed (All Rounds)

| File | Phases | Change |
|------|--------|--------|
| `flutter/lib/data/services/terminal_service.dart` | 1, 5, 14–15 | `reflowEnabled: false`; resize buffering; debounced resize; separate hydration queues |
| `flutter/packages/xterm/lib/src/ui/render.dart` | 2 | Clear paragraph cache after resize |
| `flutter/lib/features/terminal/widgets/terminal_view_widget.dart` | 3, 14 | Removed `_zooming` flag; simplified zoom flow |
| `flutter/packages/xterm/lib/src/ui/painter.dart` | 4 | Ceil cell width measurement |
| `flutter/packages/xterm/lib/src/core/escape/parser.dart` | 6–11, 12–13 | DCS handler; CSI 1-param cursor fix; SGR bounds check; OSC ESC fix; CSI non-ASCII termination; DCS ESC validation; try/catch + reset(); colon subparams; intermediate bytes; SGR unknown sub-mode fix |
| `flutter/packages/xterm/lib/src/terminal.dart` | 12 | try/catch in `write()`, reset parser on error |
