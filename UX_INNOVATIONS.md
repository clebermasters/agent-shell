# AgentShell UX Innovation Ideas

> Generated from codebase analysis on 2026-03-15.
> Based on gaps between Rust backend capabilities and Flutter UI.

---

## Quick Discovery: Backend Ready, Flutter Missing

| Gap | Backend | Flutter |
|-----|---------|---------|
| AI permission approval (approve/deny destructive commands) | ✅ Full impl | ❌ Plain text, no buttons |
| Rename session/window | ✅ Full impl | ❌ No WS method, no UI |
| Terminal scrollback on attach | ✅ Full impl | ❌ `requestHistory: true` never sent |
| Proactive system alerts | ✅ Stats stream live | ❌ Only visible if user navigates to System tab |
| AI task done notification | ✅ `acp-prompt-done` event | ❌ No local notification fired |
| Dotfile version history | ✅ Full impl | ❌ State populated, no UI renders it |
| Cron env vars & TMUX session | ✅ Model fields exist | ❌ Editor has no UI for these fields |

---

## Feature 1 — AI Permission Approval Card
**Priority: HIGH | Effort: Low | Backend: Ready**

### Problem
When the AI agent wants to run a destructive command (e.g., `rm -rf`, network calls, file writes), it sends `acp-permission-request` to the client. Currently rendered as a plain grey system message with no interactive UI. `acpRespondPermission()` exists in `WebSocketService` but is never called. The agent is silently blocked.

### Solution
A new `AcpPermissionCard` widget replaces the generic system message for permission requests:
- Amber warning icon + bold title "AI wants to run:"
- Command shown in a dark code block (atom-one-dark theme, already used in chat)
- Approve/Deny buttons generated from the `options` array in the payload
- While pending, chat input shows a "Waiting for your approval" lock state

### Files
- NEW: `flutter/lib/features/chat/widgets/acp_permission_card.dart`
- `flutter/lib/features/chat/providers/chat_provider.dart` — add `pendingPermission` to `ChatState`; update `_handleAcpPermissionRequest`
- `flutter/lib/features/chat/widgets/professional_message_bubble.dart` — route permission messages to new widget
- `flutter/lib/features/chat/screens/chat_screen.dart` — render input lock while `pendingPermission != null`

---

## Feature 2 — Terminal Scrollback on Attach
**Priority: HIGH | Effort: Trivially Low | Backend: Ready**

### Problem
Every terminal open starts with a blank screen. The backend streams scrollback history via `terminal-history-start/chunk/end` and `TerminalService` already handles these messages (lines 68–102). The attach payload just never includes `requestHistory: true`.

### Solution
- Add `'requestHistory': true` to `attachSession()` in `WebSocketService` — one line change
- Show a thin `LinearProgressIndicator` at the top of the terminal while hydrating

### Files
- `flutter/lib/data/services/websocket_service.dart` — add `requestHistory: true` to `attachSession` payload
- `flutter/lib/features/terminal/providers/terminal_provider.dart` — add `isHydrating` bool to `TerminalState`
- `flutter/lib/features/terminal/screens/terminal_screen.dart` — render progress indicator during hydration

---

## Feature 3 — Inline Session/Window Rename
**Priority: HIGH | Effort: Low | Backend: Ready**

### Problem
Sessions and windows cannot be renamed after creation. Backend supports `rename-session` and `rename-window` tmux commands but no `WebSocketService` methods wrap them and no UI exposes them.

### Solution
- Add "Rename" to session tile popup menus → `AlertDialog` with pre-filled `TextField`
- Long-press AppBar title in `TerminalScreen` opens rename dialog

### Files
- `flutter/lib/data/services/websocket_service.dart` — add `renameSession(oldName, newName)` and `renameWindow(session, index, newName)`
- `flutter/lib/features/sessions/screens/sessions_screen.dart` — add "Rename" to `_SessionTile` popup menu
- `flutter/lib/features/terminal/screens/terminal_screen.dart` — long-press AppBar title to rename

---

## Feature 4 — Proactive System Alert Banner
**Priority: HIGH | Effort: Low | Backend: Ready**

### Problem
Disk/memory/CPU alerts are only visible if the user navigates to the System tab. No proactive notification exists for critical thresholds during long AI tasks.

### Thresholds
- Disk > 90% → critical alert
- Memory > 85% → warning
- CPU load avg 1m > (cores × 0.9) → warning

### Solution
- A thin 40dp `AlertBanner` strip above the `IndexedStack` in `HomeScreen` using `AnimatedSwitcher`
- Amber/red gradient + icon + message + dismiss button
- Taps navigate to System tab
- Fires a local notification when app is backgrounded (5-min debounce per metric)

### Files
- NEW: `flutter/lib/features/system/providers/system_alerts_provider.dart` — threshold checks + debounce
- NEW: `flutter/lib/features/system/widgets/alert_banner.dart`
- `flutter/lib/features/home/screens/home_screen.dart` — place `AlertBanner` above `IndexedStack`

---

## Feature 5 — Global Command Palette
**Priority: HIGH | Effort: High | Backend: Ready**

### Problem
Finding a session, dotfile, or cron job requires multiple taps across separate tabs. No cross-feature search. Power users expect a ⌘K-style palette.

### Solution
- `FloatingActionButton` (search icon) in `HomeScreen` or two-finger swipe-up gesture
- Full-screen dark modal overlay with autofocused `TextField`
- Aggregates: TMUX sessions, ACP sessions, dotfiles, cron jobs, tab navigation
- Fuzzy string filter on every keystroke
- Each result opens its relevant screen on tap
- Items animate in with fade + slide (same pattern as message bubbles)

### Files
- NEW: `flutter/lib/features/home/providers/command_palette_provider.dart` — search index aggregating all providers
- NEW: `flutter/lib/features/home/widgets/command_palette.dart`
- `flutter/lib/features/home/screens/home_screen.dart` — FAB trigger + gesture detector

---

## Feature 6 — AI Task Completion Notification
**Priority: MEDIUM | Effort: Low | Backend: Ready**

### Problem
Users start a long AI task, put the phone down, and have no idea when it finishes. `acp-prompt-done` fires in `chat_provider.dart` and only creates a grey system message — no notification.

### Solution
- Local notification on `acp-prompt-done` with session title + last message preview (80 chars)
- Notification tap routes to the finished session's `ChatScreen`
- Sessions tab gets a `Badge` count for unread finished sessions (cleared on open)

### Files
- `flutter/lib/features/chat/providers/chat_provider.dart` — call `_showLocalNotification` in `_handleAcpPromptDone`
- `flutter/lib/features/sessions/providers/sessions_provider.dart` — add `badgeCount` to state
- `flutter/lib/features/home/screens/home_screen.dart` — `Badge` on Sessions `NavigationDestination`

---

## Feature 7 — Dotfile Version History Timeline
**Priority: MEDIUM | Effort: Medium | Backend: Ready**

### Problem
`DotfilesState.versions` is populated by the backend (`get-dotfile-history`) but no screen shows it. Users cannot roll back a broken `.bashrc` or `.zshrc`.

### Solution
- History icon button in `DotfileEditorScreen` AppBar
- `DraggableScrollableSheet` bottom sheet with timeline (vertical line + dots via `CustomPaint`)
- Each entry shows: formatted timestamp + first 2 lines of diff preview in monospace
- "Restore" button → confirmation dialog → calls `restoreDotfileVersion` → reloads editor with `AnimatedSwitcher` fade

### Files
- NEW: `flutter/lib/features/dotfiles/widgets/dotfile_version_sheet.dart`
- `flutter/lib/features/dotfiles/screens/dotfile_editor_screen.dart` — add history `IconButton` to AppBar

---

## Feature 8 — Swipe-Between-Sessions in Terminal
**Priority: MEDIUM | Effort: High | Backend: No new WS needed**

### Problem
Switching terminal sessions requires back-navigating to Sessions list → finding the session → tapping → waiting for attach. High friction for users with 3–5 active sessions.

### Solution
- `HorizontalDragGestureRecognizer` inside `TerminalScreen` (disabled during text selection mode)
- Swipe left/right to cycle through sessions
- Ghost overlay shows next/prev session name during drag (30% opacity, monospace text)
- Small dot indicator briefly appears at top showing current position among sessions
- Rubber-band animation at first/last session communicates boundary

### Files
- `flutter/lib/features/terminal/screens/terminal_screen.dart` — gesture detection, overlay, dot indicator
- `flutter/lib/features/terminal/providers/terminal_provider.dart` — add `switchToSession(index)`
- `flutter/lib/features/sessions/providers/sessions_provider.dart` — expose ordered session list

---

## Feature 9 — Cron Environment Variables Editor
**Priority: MEDIUM | Effort: Low | Backend: Ready**

### Problem
`CronJob` model has `environment: Map<String, String>?` and `tmuxSession: String?` fields that are sent to the backend — but the editor has no UI to set them. Scripts that depend on `PATH`, `HOME`, or need to run in a TMUX window cannot be configured.

### Solution
Two `ExpansionTile` sections at the bottom of `CronJobEditorScreen`:

1. **Environment Variables**
   - Key/value row pairs with delete buttons
   - "Add Variable" button appends new row
   - Chip row for common vars: PATH, HOME, TZ, LANG (tap to pre-fill key)

2. **TMUX Session**
   - `DropdownButtonFormField<String?>` populated from active sessions
   - "None" as the null option

### Files
- `flutter/lib/features/cron/screens/cron_job_editor_screen.dart` — add two `ExpansionTile` sections

---

## Feature 10 — Live System Stats Mini-Bar on Sessions Tab
**Priority: LOW | Effort: Very Low | Backend: Ready**

### Problem
Users want ambient server health awareness while managing sessions without navigating away to the System tab.

### Solution
A 40dp `SystemMiniBar` widget between the AppBar and session list showing CPU%, Memory%, Disk% as colored chips. Uses `ref.watch(systemProvider.select(...))` for efficient reactive updates. Color-coded: green (normal) → orange (>70%) → red (>90%).

### Files
- NEW: `flutter/lib/features/system/widgets/system_mini_bar.dart`
- `flutter/lib/features/sessions/screens/sessions_screen.dart` — add `SystemMiniBar` above `ListView`

---

## Implementation Sequencing

### Phase 1 — Quick wins (1–2 days)
1. **Terminal Scrollback on Attach** — 1 line + loading indicator
2. **Inline Session/Window Rename** — 2 WS methods + popup menu
3. **Cron Environment Variables Editor** — pure UI addition
4. **System Stats Mini-Bar** — small new widget

### Phase 2 — Core UX gaps (3–5 days)
5. **AI Permission Approval Card** — most critical for AI safety
6. **Proactive System Alert Banner** — new provider + banner widget
7. **AI Task Done Notification** — builds on existing notification infra

### Phase 3 — Power features (1–2 weeks)
8. **Dotfile Version History Timeline** — bottom sheet, no new backend
9. **Global Command Palette** — new search index + overlay screen
10. **Swipe-Between-Sessions** — gesture detection needs testing with terminal scrolling

---

## Priority Matrix

| # | Feature | Priority | Backend Ready | Effort |
|---|---------|----------|---------------|--------|
| 1 | AI Permission Approval Card | High | ✅ Yes | Medium |
| 2 | Terminal Scrollback on Attach | High | ✅ Yes | Very Low |
| 3 | Inline Session/Window Rename | High | ✅ Yes | Low |
| 4 | Proactive System Alert Banner | High | ✅ Yes | Low |
| 5 | Global Command Palette | High | ✅ Yes | High |
| 6 | AI Task Done Notification | Medium | ✅ Yes | Low |
| 7 | Dotfile Version History Timeline | Medium | ✅ Yes | Medium |
| 8 | Swipe-Between-Sessions | Medium | No new WS | High |
| 9 | Cron Environment Variables Editor | Medium | ✅ Yes | Low |
| 10 | System Stats Mini-Bar | Low | ✅ Yes | Very Low |
