# WebMux Feature Parity Analysis

## Overview

This document analyzes the differences between the Vue.js web application and the Flutter mobile application, identifying missing features in Flutter and unused code that can be removed.

---

## Missing Features in Flutter

### 1. Dotfile History

**Vue Component:** `src/components/DotfileHistory.vue`

**Description:** Version history feature for dotfiles that allows users to:
- View all previous versions of a dotfile
- See timestamps for each version
- Restore previous versions

**Flutter Status:** ❌ Not implemented

- The `dotfiles_provider.dart` handles the WebSocket messages for history (`dotfile-history` / `dotfile_history`)
- However, there is no screen to display this data
- No UI for viewing or restoring versions

---

### 2. Window Management

**Vue Component:** `src/components/WindowList.vue`

**Description:** Tmux window management within sessions:
- Create new windows
- Rename windows
- Close windows
- View window list with active status

**Flutter Status:** ❌ Not implemented

- The `TmuxWindow` model exists in `tmux_session.dart`
- No screen exists to manage windows
- Sessions screen only shows sessions, not their windows

---

## Unused Code in Flutter

### 1. Repositories Folder

**Location:** `flutter/lib/data/repositories/`

**Files:**
- `cron_repository.dart`
- `session_repository.dart`
- `host_repository.dart`
- `dotfiles_repository.dart`
- `repositories.dart` (barrel file)

**Status:** ❌ Entire folder is dead code

**Reason:** All providers communicate directly via WebSocket. The repository layer was likely planned but never integrated.

---

### 2. TmuxWindow Model

**Location:** `flutter/lib/data/models/tmux_session.dart`

**Status:** ❌ Unused

**Reason:** Model is defined but never imported or used anywhere in the codebase.

---

### 3. AppConstants

**Location:** `flutter/lib/core/constants/app_constants.dart`

**Status:** ❌ Unused

**Reason:** File is defined but never imported anywhere.

---

### 4. Extensions

**Location:** `flutter/lib/core/utils/extensions.dart`

**Status:** ❌ Unused

**Reason:** File is defined but never imported anywhere.

---

## Implementation Recommendations

### Priority 1: Remove Dead Code

Remove the following to reduce codebase size:
1. `flutter/lib/data/repositories/` (entire folder)
2. `TmuxWindow` class from `tmux_session.dart`
3. `flutter/lib/core/constants/app_constants.dart`
4. `flutter/lib/core/utils/extensions.dart`

### Priority 2: Implement Missing Features

1. **Dotfile History Screen**
   - Create `dotfiles_history_screen.dart`
   - Connect to existing WebSocket handlers in `dotfiles_provider.dart`
   - Implement version list and restore functionality

2. **Window Management**
   - Create `window_list_screen.dart` or integrate into sessions screen
   - Add window creation, renaming, and closing
   - Use existing `TmuxWindow` model

---

## Files Reference

### Vue Components (Complete)
```
src/components/
├── ChatView.vue           ✅
├── CronSection.vue        ✅
├── CronJobEditor.vue      ✅
├── CronJobItem.vue        ✅
├── DebugScreen.vue        ✅
├── DotfileEditor.vue      ✅
├── DotfileHistory.vue     ❌ (missing in Flutter)
├── DotfileTemplates.vue    ✅
├── DotfilesSection.vue    ✅
├── HostSelector.vue       ✅
├── SessionItem.vue       ✅
├── SessionList.vue       ✅
├── TerminalView.vue      ✅
└── WindowList.vue        ❌ (missing in Flutter)
```

### Flutter Screens (Complete)
```
flutter/lib/features/
├── chat/screens/chat_screen.dart                    ✅
├── cron/screens/cron_screen.dart                    ✅
├── cron/screens/cron_job_editor_screen.dart         ✅
├── debug/screens/debug_screen.dart                  ✅
├── dotfiles/screens/dotfiles_screen.dart           ✅
├── dotfiles/screens/dotfile_editor_screen.dart     ✅
├── dotfiles/screens/dotfiles_templates_screen.dart ✅
├── hosts/screens/host_selection_screen.dart         ✅
├── sessions/screens/sessions_screen.dart           ✅
├── system/screens/system_screen.dart                ✅
└── terminal/screens/terminal_screen.dart           ✅
```

---

## Summary

| Category | Count |
|----------|-------|
| Features fully implemented | 11 |
| Features missing in Flutter | 2 |
| Unused files/folders | 5 |

**Recommendation:** Implement missing features for feature parity, then remove all dead code to maintain a clean codebase.
