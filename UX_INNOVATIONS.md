# AgentShell UX Innovation Ideas

> Generated from codebase analysis on 2026-03-15.
> Last updated: 2026-03-16.
> Based on gaps between Rust backend capabilities and Flutter UI.

---

## Status Overview

| # | Feature | Status | Date |
|---|---------|--------|------|
| 1 | AI Permission Approval Card | ✅ Done | 2026-03-15 |
| 2 | Terminal Scrollback on Attach | ✅ Done | 2026-03-15 |
| 3 | Inline Session/Window Rename | ✅ Done | 2026-03-15 |
| 4 | Proactive System Alert Banner | ✅ Done | 2026-03-15 |
| 5 | Global Command Palette | ✅ Done | 2026-03-15 |
| 6 | AI Task Done Notification | ✅ Done | 2026-03-15 |
| 7 | Dotfile Version History Timeline | ✅ Done | 2026-03-15 |
| 8 | Swipe-Between-Sessions (Terminal) | ✅ Done + Polished | 2026-03-16 |
| 8b | Swipe-Between-Chats (Chat) | ✅ Done | 2026-03-16 |
| 8c | Recency-Based Swipe (Terminal + Chat) | ✅ Done | 2026-03-16 |
| 9 | Cron Environment Variables Editor | 🔲 Pending | — |
| 10 | Live System Stats Mini-Bar | 🔲 Pending | — |

---

## Feature 1 — AI Permission Approval Card
**Priority: HIGH | Effort: Low | Backend: Ready | Status: ✅ Done**

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
- `flutter/lib/features/chat/providers/chat_provider.dart` — `pendingPermission` in `ChatState`
- `flutter/lib/features/chat/screens/chat_screen.dart` — renders `AcpPermissionCard` above input

---

## Feature 2 — Terminal Scrollback on Attach
**Priority: HIGH | Effort: Trivially Low | Backend: Ready | Status: ✅ Done**

### Problem
Every terminal open started with a blank screen. Backend streams scrollback history via `terminal-history-start/chunk/end`. `TerminalService` already handled these messages but attach payload never included `requestHistory: true`.

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
- File browser multi-select also gained rename support

### Files Changed
- `flutter/lib/data/services/websocket_service.dart`
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

## Feature 6 — AI Task Completion Notification
**Priority: MEDIUM | Effort: Low | Backend: Ready | Status: ✅ Done**

### Solution
- Local notification on `acp-prompt-done` with session title + last message preview
- Notification tap routes to the finished session's `ChatScreen`

### Files Changed
- `flutter/lib/features/chat/providers/chat_provider.dart`

---

## Feature 7 — Dotfile Version History Timeline
**Priority: MEDIUM | Effort: Medium | Backend: Ready | Status: ✅ Done**

### Solution
- History icon button in `DotfileEditorScreen` AppBar
- `DraggableScrollableSheet` with timeline (vertical line + dots)
- Each entry: timestamp + first 2 lines of diff preview
- "Restore" button → confirmation → calls `restoreDotfileVersion`

### Files Changed
- NEW: `flutter/lib/features/dotfiles/widgets/dotfile_version_sheet.dart`
- `flutter/lib/features/dotfiles/screens/dotfile_editor_screen.dart`

---

## Feature 8 — Swipe-Between-Sessions (Terminal)
**Priority: MEDIUM | Effort: High | Backend: No new WS needed | Status: ✅ Done + Polished**

### Implementation
`GestureDetector` wrapping the terminal body (disabled in selection mode):
- Swipe left → next session, swipe right → previous session
- Ghost hint pill (black 70% opacity, rounded) shows target session name during drag
- **Dot position indicator**: row of small circles at top (filled = current, outline = others), fades in on drag start, fades out 600ms after release
- **Rubber-band boundary**: at first/last session in the recency list, delta is applied at 0.25× resistance; hint shows `⟵ (start)` or `(end) ⟶`; no navigation fires on release

### Recency-Based Navigation (Feature 8c)
Swipe does **not** cycle through all sessions. Instead each screen maintains a persisted ordered list of the last 3 sessions visited (SharedPreferences). Dead sessions are filtered out at read time.

- Storage key: `AppConfig.keyRecentTerminalSessions` (`recent_terminal_sessions`)
- Format: JSON array, most-recent-first, max 3 entries
- Updated on every `TerminalScreen` open via `_pushRecentTerminalSession()`
- Dots reflect the recency window (1–3 dots max)

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

### Recency-Based Navigation (Feature 8c)
Chat maintains its own separate recency list (independent from terminal).

- Storage key: `AppConfig.keyRecentChatSessions` (`recent_chat_sessions`)
- Entry format: `"acp:<sessionId>"` or `"tmux:<sessionName>\x00<windowIndex>"`
  - Null-byte (`\x00`) separator avoids collision with colons in TMUX session names
- Navigation decodes the key and constructs the correct `ChatScreen` variant
- ACP hint label: `AcpSession.title` (falls back to first 8 chars of `sessionId`)
- TMUX hint label: session name
- No circular wrap (hard boundary stop, unlike terminal which was also changed to hard stop)

### Files Changed
- `flutter/lib/features/chat/screens/chat_screen.dart`
- `flutter/lib/core/config/app_config.dart` — `keyRecentChatSessions`

---

## Feature 9 — Cron Environment Variables Editor
**Priority: MEDIUM | Effort: Low | Backend: Ready | Status: 🔲 Pending**

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
- `flutter/lib/features/cron/screens/cron_job_editor_screen.dart`

---

## Feature 10 — Live System Stats Mini-Bar on Sessions Tab
**Priority: LOW | Effort: Very Low | Backend: Ready | Status: 🔲 Pending**

### Problem
Users want ambient server health awareness while managing sessions without navigating away to the System tab.

### Solution
A 40dp `SystemMiniBar` widget between the AppBar and session list showing CPU%, Memory%, Disk% as colored chips. Uses `ref.watch(systemProvider.select(...))` for efficient reactive updates. Color-coded: green (normal) → orange (>70%) → red (>90%).

### Files
- NEW: `flutter/lib/features/system/widgets/system_mini_bar.dart`
- `flutter/lib/features/sessions/screens/sessions_screen.dart`

---

## Implementation Sequencing

### Phase 1 — Quick wins ✅ Complete
1. Terminal Scrollback on Attach
2. Inline Session/Window Rename
3. AI Permission Approval Card
4. Proactive System Alert Banner
5. AI Task Done Notification

### Phase 2 — Core UX gaps ✅ Complete
6. Global Command Palette
7. Dotfile Version History Timeline
8. Swipe-Between-Sessions (Terminal + Chat, recency-based)

### Phase 3 — Remaining polish 🔲
9. Cron Environment Variables Editor
10. Live System Stats Mini-Bar

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
| 9 | Cron Environment Variables Editor | Medium | ✅ Yes | Low | 🔲 Pending |
| 10 | Live System Stats Mini-Bar | Low | ✅ Yes | Very Low | 🔲 Pending |
