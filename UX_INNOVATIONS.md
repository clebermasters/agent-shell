# AgentShell UX Innovation Ideas

> Generated from codebase analysis on 2026-03-15.
> Last updated: 2026-03-16.
> Based on gaps between Rust backend capabilities and Flutter UI.

---

## Status Overview

| # | Feature | Status | Commit |
|---|---------|--------|--------|
| 1 | AI Permission Approval Card | ✅ Done | `05dadb82` |
| 2 | Terminal Scrollback on Attach | ✅ Done | `d34162fa` |
| 3 | Inline Session/Window Rename | ✅ Done | `30f7a1fb` |
| 4 | Proactive System Alert Banner | ✅ Done | `e67a4144` |
| 5 | Global Command Palette | ✅ Done | `7446da5e` |
| 6 | AI Task Done Notification | ✅ Done | `05dadb82` |
| 7 | Dotfile Version History Timeline | ✅ Done | `a9792833` |
| 8 | Swipe-Between-Sessions (Terminal) | ✅ Done + Polished | `7eda7fbf` |
| 8b | Swipe-Between-Chats | ✅ Done | `3440276c` |
| 8c | Recency-Based Swipe (last 3) | ✅ Done | `a3b5e14a` |
| 9 | Cron Environment Variables Editor | ✅ Done | `e8d67039` |
| 10 | Live System Stats Mini-Bar | ✅ Done | `0270f749` |
| 11 | File Manager Enhancements | ✅ Done | `89fa55a8` |
| 12 | HTML/Markdown Preview (WebView) | ✅ Done | `040f5325` |

**All original planned features are complete.** See [Backlog](#backlog--future-ideas) for next ideas.

---

## Feature 1 — AI Permission Approval Card
**Priority: HIGH | Effort: Medium | Backend: Ready | Status: ✅ Done**

### Problem
When the AI agent wants to run a destructive command (e.g., `rm -rf`, network calls, file writes), it sends `acp-permission-request` to the client. Previously rendered as a plain grey system message with no interactive UI. `acpRespondPermission()` existed in `WebSocketService` but was never called. The agent was silently blocked.

### Solution
A new `AcpPermissionCard` widget replaces the generic system message for permission requests:
- Amber warning icon + bold title "AI wants to run:"
- Command shown in a dark code block
- Approve/Deny buttons generated from the `options` array in the payload
- While pending, chat input shows a "Waiting for your approval" lock state

### Files Changed
- NEW: `flutter/lib/features/chat/widgets/acp_permission_card.dart`
- `flutter/lib/features/chat/providers/chat_provider.dart` — `pendingPermission` in `ChatState`; `pendingPermission` cleared on reset
- `flutter/lib/features/chat/screens/chat_screen.dart` — renders `AcpPermissionCard` above input

---

## Feature 2 — Terminal Scrollback on Attach
**Priority: HIGH | Effort: Very Low | Backend: Ready | Status: ✅ Done**

### Problem
Every terminal open started with a blank screen. Backend streams scrollback history via `terminal-history-start/chunk/end`. `TerminalService` already handled these messages but the attach payload never included `requestHistory: true`.

### Solution
- Added `'requestHistory': true` to `attachSession()` in `WebSocketService`
- Thin `LinearProgressIndicator` at the top of the terminal while hydrating (`isHydrating` state)

### Files Changed
- `flutter/lib/data/services/websocket_service.dart`
- `flutter/lib/features/terminal/providers/terminal_provider.dart`
- `flutter/lib/features/terminal/screens/terminal_screen.dart`

---

## Feature 3 — Inline Session/Window Rename
**Priority: HIGH | Effort: Low | Backend: Ready | Status: ✅ Done**

### Problem
Sessions and windows could not be renamed after creation. Backend supported `rename-session` and `rename-window` but no `WebSocketService` methods wrapped them and no UI exposed them.

### Solution
- Long-press AppBar title in `TerminalScreen` opens rename `AlertDialog` with pre-filled `TextField`
- "Rename" added to session tile popup menus in `SessionsScreen`
- Rename controllers properly disposed on close

### Files Changed
- `flutter/lib/data/services/websocket_service.dart` — `renameSession()`, `renameWindow()`
- `flutter/lib/features/sessions/screens/sessions_screen.dart`
- `flutter/lib/features/terminal/screens/terminal_screen.dart`

---

## Feature 4 — Proactive System Alert Banner
**Priority: HIGH | Effort: Low | Backend: Ready | Status: ✅ Done**

### Thresholds
- Disk > 90% → critical alert
- Memory > 85% → warning
- CPU load avg 1m > (cores × 0.9) → warning

### Solution
- Thin `AlertBanner` strip above `IndexedStack` in `HomeScreen` using `AnimatedSwitcher`
- Amber/red gradient + icon + message + dismiss button
- Taps navigate to System tab

### Files Changed
- NEW: `flutter/lib/features/system/providers/system_alerts_provider.dart`
- NEW: `flutter/lib/features/system/widgets/alert_banner.dart`
- `flutter/lib/features/home/screens/home_screen.dart`

---

## Feature 5 — Global Command Palette
**Priority: HIGH | Effort: High | Backend: Ready | Status: ✅ Done**

### Solution
- FAB (search icon) in `HomeScreen`
- Full-screen dark modal overlay with autofocused `TextField`
- Aggregates: TMUX sessions, ACP sessions, dotfiles, cron jobs, tab navigation
- Fuzzy string filter on every keystroke, results animate in with fade + slide

### Files Changed
- NEW: `flutter/lib/features/home/providers/command_palette_provider.dart`
- NEW: `flutter/lib/features/home/widgets/command_palette.dart`
- `flutter/lib/features/home/screens/home_screen.dart`

---

## Feature 6 — AI Task Done Notification
**Priority: MEDIUM | Effort: Low | Backend: Ready | Status: ✅ Done**

### Solution
- Local notification fired on `acp-prompt-done` with session title + last message preview
- Notification tap routes to the finished session's `ChatScreen`

### Files Changed
- `flutter/lib/features/chat/providers/chat_provider.dart`

---

## Feature 7 — Dotfile Version History Timeline
**Priority: MEDIUM | Effort: Medium | Backend: Ready | Status: ✅ Done**

### Solution
- History icon button in `DotfileEditorScreen` AppBar
- `DraggableScrollableSheet` with timeline (vertical line + dots)
- Each entry: timestamp + first 2 lines of diff preview in monospace
- "Restore" button → confirmation → calls `restoreDotfileVersion`

### Files Changed
- NEW: `flutter/lib/features/dotfiles/widgets/dotfile_version_sheet.dart`
- `flutter/lib/features/dotfiles/screens/dotfile_editor_screen.dart`

---

## Feature 8 — Swipe-Between-Sessions (Terminal)
**Priority: MEDIUM | Effort: High | Backend: No new WS needed | Status: ✅ Done + Polished**

### Implementation
`GestureDetector` wrapping the terminal body (disabled in selection mode):
- Swipe left → next session, swipe right → previous session (threshold: 120px)
- Ghost hint pill (black 70% opacity, rounded) shows target session name during drag (threshold: 60px)
- **Dot position indicator**: row of small circles at top (filled = current, outline = others), fades in on drag start, fades out 600ms after release
- **Rubber-band boundary**: at first/last session in the recency list, delta is applied at 0.25× resistance; hint shows `⟵ (start)` or `(end) ⟶`; no navigation fires on release

### Recency-Based Navigation (8c)
Swipe cycles through the **last 3 sessions visited**, not all sessions:
- Storage key: `AppConfig.keyRecentTerminalSessions` (`recent_terminal_sessions`)
- Format: JSON array `["session1", "session2", "session3"]`, most-recent-first, max 3
- Updated on every `TerminalScreen` open via `_pushRecentTerminalSession()`
- Dead sessions filtered out at read time by cross-referencing `sessionsProvider.sessions`
- Dots reflect the recency window (1–3 dots max)
- No circular wrap — hard boundary stop on both ends

### Files Changed
- `flutter/lib/features/terminal/screens/terminal_screen.dart`
- `flutter/lib/core/config/app_config.dart` — `keyRecentTerminalSessions`

---

## Feature 8b — Swipe-Between-Chats
**Priority: MEDIUM | Effort: Medium | Backend: No new WS needed | Status: ✅ Done**

### Problem
Switching chat sessions required back-navigating to Sessions list. No swipe gesture existed for chat.

### Solution
Same gesture UX as terminal swipe (dot indicator + rubber-band + hint pill), applied to `ChatScreen`. Works for **both** ACP (AI agent) and TMUX chat sessions — no `isAcp` guard on gesture handlers.

### Recency-Based Navigation (8c)
Chat maintains its own separate recency list (independent from terminal):
- Storage key: `AppConfig.keyRecentChatSessions` (`recent_chat_sessions`)
- Entry format: `"acp:<sessionId>"` or `"tmux:<sessionName>\x00<windowIndex>"`
  - Null-byte (`\x00`) separator avoids collision with colons in TMUX session names
- Navigation decodes the key and constructs the correct `ChatScreen` variant
- ACP hint label: `AcpSession.title` (falls back to first 8 chars of `sessionId`)
- TMUX hint label: session name

### Files Changed
- `flutter/lib/features/chat/screens/chat_screen.dart`
- `flutter/lib/core/config/app_config.dart` — `keyRecentChatSessions`

---

## Feature 9 — Cron Environment Variables Editor
**Priority: MEDIUM | Effort: Low | Backend: Ready | Status: ✅ Done**

### Problem
`CronJob` model had `environment: Map<String, String>?` and `tmuxSession: String?` fields sent to the backend with no UI to configure them. Scripts depending on `PATH`, `HOME`, or a TMUX window couldn't be set up.

### Solution
Two `ExpansionTile` sections at the bottom of `CronJobEditorScreen`:

1. **Environment Variables** — key/value rows with delete buttons; "Add Variable" button; chips for common vars (PATH, HOME, TZ, LANG)
2. **TMUX Session** — `DropdownButtonFormField<String?>` populated from active sessions; "None" as null option

### Files Changed
- `flutter/lib/features/cron/screens/cron_job_editor_screen.dart`

---

## Feature 10 — Live System Stats Mini-Bar on Sessions Tab
**Priority: LOW | Effort: Very Low | Backend: Ready | Status: ✅ Done**

### Solution
A 40dp `SystemMiniBar` widget between the AppBar and session list showing CPU%, Memory%, Disk% as colored chips. Uses `ref.watch(systemProvider.select(...))` for efficient reactive updates. Color-coded: green → orange (>70%) → red (>90%).

### Files Changed
- NEW: `flutter/lib/features/system/widgets/system_mini_bar.dart`
- `flutter/lib/features/sessions/screens/sessions_screen.dart`

---

## Feature 11 — File Manager Enhancements
**Priority: MEDIUM | Effort: Medium | Backend: Ready | Status: ✅ Done**

### Problem
The file browser had no batch operations and no way to preview file content without leaving the app.

### Solution
- **Multi-select mode**: long-press to enter multi-select, checkboxes on each row
- **Batch delete**: delete multiple selected files with single confirmation dialog
- **Rename**: rename any file or directory inline via dialog
- **HTML preview**: render HTML files in-app using a `WebView`
- **Markdown preview**: render Markdown files with formatting

### Files Changed
- `flutter/lib/features/file_browser/screens/file_browser_screen.dart`
- `flutter/lib/features/file_browser/providers/file_browser_provider.dart`
- File browser subscription cancelled on timeout (5s guard)

---

## Feature 12 — HTML/Markdown Preview via WebView
**Priority: LOW | Effort: Low | Backend: No new WS needed | Status: ✅ Done**

### Problem
Initial file preview used a basic Flutter text renderer — HTML tags were visible as raw text, no CSS applied, images broken.

### Solution
- Upgraded to full `WebView` for HTML files — browser-quality rendering with CSS, images, JS
- Preview mode is now the **default** when opening a supported file (HTML, Markdown)
- Toggle between preview and raw source with a single AppBar button
- `BUILD_TIMESTAMP` in build config busts the Flutter service worker cache hash on every build

### Files Changed
- `flutter/lib/features/file_browser/screens/file_browser_screen.dart`
- `flutter/lib/features/file_browser/widgets/` (preview widget)

---

## Changelog

| Date | Commit | Change |
|------|--------|--------|
| 2026-03-16 | `a3b5e14a` | Recency-based swipe (last 3) for both terminal and chat |
| 2026-03-16 | `3440276c` | Swipe-between-chats with dot indicator and rubber-band |
| 2026-03-16 | `7eda7fbf` | Terminal swipe polish: dot indicator + rubber-band boundary |
| 2026-03-16 | `040f5325` | Upgrade HTML preview to WebView, default to preview mode |
| 2026-03-16 | `89fa55a8` | File manager: multi-select delete, rename, HTML/markdown preview |
| 2026-03-16 | `a9792833` | Code review fixes: subscriptions, session prefix, permission guard, drag cancel |
| 2026-03-15 | `7446da5e` | Global command palette |
| 2026-03-15 | `e67a4144` | Proactive system resource alert banner |
| 2026-03-15 | `05dadb82` | AI permission approval card + task done notification |
| 2026-03-15 | `0270f749` | System stats mini-bar on sessions screen |
| 2026-03-15 | `e8d67039` | Cron env vars + TMUX session editor |
| 2026-03-15 | `30f7a1fb` | Inline rename for sessions and windows |
| 2026-03-15 | `d34162fa` | Terminal scrollback on attach |

---

## Backlog / Future Ideas

> Ideas discovered during development — not yet planned or estimated.

- **Session groups / tags** — label sessions by project so swipe and palette can filter by group
- **Chat-to-terminal bridge** — single tap to copy last AI output as terminal input
- **Pinned sessions** — star up to 3 sessions that always appear first in swipe recency
- **Swipe threshold customisation** — user setting for swipe sensitivity (currently hardcoded 120px trigger, 60px hint)
- **Terminal font size gesture** — pinch-to-zoom in terminal view (currently only in settings)
- **Cron last-run output** — show last stdout/stderr of a cron job in the editor
- **ACP session notes** — free-text annotation on each AI session visible in the sessions list
- **Offline indicator** — distinct UI state when WebSocket is unreachable vs. reconnecting

---

## Priority Matrix

| # | Feature | Priority | Backend Ready | Effort | Status |
|---|---------|----------|---------------|--------|--------|
| 1 | AI Permission Approval Card | High | ✅ Yes | Medium | ✅ Done |
| 2 | Terminal Scrollback on Attach | High | ✅ Yes | Very Low | ✅ Done |
| 3 | Inline Session/Window Rename | High | ✅ Yes | Low | ✅ Done |
| 4 | Proactive System Alert Banner | High | ✅ Yes | Low | ✅ Done |
| 5 | Global Command Palette | High | ✅ Yes | High | ✅ Done |
| 6 | AI Task Done Notification | Medium | ✅ Yes | Low | ✅ Done |
| 7 | Dotfile Version History Timeline | Medium | ✅ Yes | Medium | ✅ Done |
| 8 | Swipe-Between-Sessions (Terminal) | Medium | No new WS | High | ✅ Done |
| 8b | Swipe-Between-Chats | Medium | No new WS | Medium | ✅ Done |
| 8c | Recency-Based Swipe (last 3) | Medium | No new WS | Low | ✅ Done |
| 9 | Cron Environment Variables Editor | Medium | ✅ Yes | Low | ✅ Done |
| 10 | Live System Stats Mini-Bar | Low | ✅ Yes | Very Low | ✅ Done |
| 11 | File Manager Enhancements | Medium | ✅ Yes | Medium | ✅ Done |
| 12 | HTML/Markdown Preview (WebView) | Low | No new WS | Low | ✅ Done |
