# Terminal Scrollback Streaming Plan (Backend + Flutter)

## Goal

Enable seamless swipe up/down in the Flutter terminal to browse old tmux scrollback history, with no extra UI controls. The backend must stream tmux buffer data in real time so the terminal feels continuous (history + live output in one flow).

## Product Requirements

- User can drag/swipe up in terminal and see old output that existed before attach/reconnect.
- User can drag/swipe down to return to live output.
- No new buttons, drawers, or mode toggles in UI.
- Works with existing live terminal output stream.
- Must behave well on reconnect and session/window switches.

## Current State and Gap

- Backend currently sends live PTY output (`output`) after `attach-session`.
- Flutter `xterm` view scrolls local buffer, but only contains data received after attach.
- Result: swipe works only for recent local lines; old tmux history is missing.

## Proposed Architecture

Use a two-phase attach bootstrap:

1. Server captures tmux scrollback (history area) and streams it in chunks.
2. Server then streams queued live PTY output and continues normal realtime `output`.

This keeps one continuous buffer in Flutter `xterm` so native swipe scrolling is enough.

### Data Flow

- Client sends `attach-session` (existing).
- Server starts PTY attach but temporarily queues live output frames.
- Server captures tmux history lines above current viewport (`capture-pane`) and sends chunked history messages.
- Server sends `terminal-history-end`.
- Server flushes queued live output, then switches to normal realtime output forwarding.

## WebSocket Protocol Changes

### Client -> Server

- Reuse existing `attach-session`.
- Optional future extension:
  - `historyLines` (requested cap, default server policy).

### Server -> Client (new)

- `terminal-history-start`
  - `sessionName`, `windowIndex`, `totalLines`, `chunkSize`, `generatedAt`
- `terminal-history-chunk`
  - `sessionName`, `windowIndex`, `seq`, `data`, `lineCount`, `isLast`
- `terminal-history-end`
  - `sessionName`, `windowIndex`, `totalLines`, `totalChunks`

Existing messages continue:

- `output` for live stream.

## Backend Plan (Rust)

### 1) Add tmux history primitives

Files:

- `backend-rust/src/tmux/mod.rs`
- `backend-rust/src/types/mod.rs`

Add functions:

- `get_pane_metadata(session, window) -> { history_size, pane_height, pane_width }`
- `capture_history_above_viewport(session, window) -> String`
- `chunk_terminal_stream(text, max_bytes)` utility preserving line boundaries.

Implementation notes:

- Use `tmux display-message -p` for pane metadata.
- Use `tmux capture-pane -p -e -J` with start/end bounds that include scrollback history area, excluding the currently visible viewport to avoid duplicate first screen.
- Default chunk target: 16-32 KB payload per message.

### 2) Extend WS message enums

Files:

- `backend-rust/src/types/mod.rs`

Add server message variants:

- `TerminalHistoryStart`
- `TerminalHistoryChunk`
- `TerminalHistoryEnd`

### 3) Attach bootstrap sequencing

Files:

- `backend-rust/src/websocket/mod.rs`

Changes:

- In `attach_to_session`, create a bootstrap mode:
  - queue PTY output in memory (bounded queue) until history streaming completes.
  - capture and stream history chunks first.
  - send history end.
  - flush queued PTY output in order.
  - switch to normal live forwarding.

Safety constraints:

- Per-client queue limit (for example 2-4 MB). If exceeded, flush early with warning log.
- Timeout protection around tmux capture (for example 2-3 seconds).
- If capture fails, continue with live stream and send an error event (non-fatal).

### 4) Observability

Files:

- `backend-rust/src/websocket/mod.rs`

Add logs/metrics:

- history lines captured
- chunk count and total bytes
- bootstrap duration
- queue overflow occurrences

## Flutter Plan

### 1) Add protocol handlers

Files:

- `flutter/lib/data/services/websocket_service.dart`
- `flutter/lib/data/services/terminal_service.dart`

Add parsing/forwarding for:

- `terminal-history-start`
- `terminal-history-chunk`
- `terminal-history-end`

### 2) Terminal hydration pipeline

Files:

- `flutter/lib/data/services/terminal_service.dart`
- `flutter/lib/features/terminal/providers/terminal_provider.dart`

Behavior:

- On `terminal-history-start`: reset current terminal session buffer once.
- On chunk events: write chunk text to terminal in sequence.
- On history end: mark session hydrated and allow normal live-only path.
- If any `output` arrives during hydration, enqueue and flush after history end.

### 3) Swipe/drag behavior (UI only)

Files:

- `flutter/lib/features/terminal/widgets/terminal_view_widget.dart`

Goal:

- Preserve current terminal visual layout.
- Ensure drag events are not blocked by outer gesture listeners.
- Let `TerminalView` native scroll behavior handle up/down swipe smoothly.

No visible control changes. Only touch-drag behavior must feel seamless.

### 4) Buffer sizing

Files:

- `flutter/lib/data/services/terminal_service.dart`

Increase terminal scrollback capacity for mobile (for example from 10,000 to 50,000/100,000 with memory cap) so streamed tmux history is not trimmed immediately.

## Rollout Strategy

### Phase 1: Protocol and backend capture

- Add new server messages and tmux capture functions.
- Stream history chunks before live output flush.
- Verify with WebSocket logs and manual tmux sessions.

### Phase 2: Flutter hydration + swipe continuity

- Apply history chunk handling and hydration queue.
- Validate smooth swipe up/down and return to live output.

### Phase 3: Hardening

- Timeout/overflow handling, reconnect cases, session/window switching.
- Performance tuning on low-memory mobile devices.

## Testing Plan

### Backend

- Unit tests for chunking logic (line-safe chunk split).
- Integration test for attach bootstrap ordering:
  - start -> chunks -> end -> live output.
- Manual test with long-running tmux pane history.

### Flutter

- Widget/integration tests for hydration state transitions.
- Manual tests:
  - open terminal with existing long history, swipe up immediately.
  - while scrolled up, receive live output and then swipe down to latest.
  - reconnect app and confirm history is backfilled again.

## Acceptance Criteria

- On opening terminal, user can immediately swipe up and see pre-existing tmux history.
- Swipe down returns to latest output without abrupt jumps.
- No extra UI controls added for history mode.
- Reconnect and session switch preserve same behavior.
- No significant input latency introduced in live terminal mode.

## Risks and Mitigations

- Large history payloads can spike memory:
  - use chunking + queue caps + configurable line cap.
- Duplicate lines at bootstrap boundary:
  - capture history excluding visible viewport and queue PTY output until history end.
- Slow tmux capture on huge panes:
  - timeout and fallback to live stream.

## Future Extension (Optional)

If unlimited history is needed beyond initial cap:

- Add on-demand pagination (`terminal-history-fetch-before`) triggered when user reaches top.
- Rebuild terminal buffer from cached lines + fetched prefix in a controlled replay step.
