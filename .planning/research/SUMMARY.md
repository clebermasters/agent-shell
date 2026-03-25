# Project Research Summary

**Project:** AgentShell — Cron & Dotfile Screen Layout Fix
**Domain:** Flutter UI bug fix — nested Scaffold FAB collision
**Researched:** 2026-03-25
**Confidence:** HIGH

## Executive Summary

The bug reported as "search field and config button overlapping on Cron and Dotfile screens" is not an AppBar layout issue. The architecture agent confirmed through direct source inspection that the root cause is a **dual FloatingActionButton collision** inside `home_screen.dart`. `HomeScreen` wraps all tabs in an `IndexedStack`, which keeps all child `Scaffold` widgets alive simultaneously. Both `CronScreen` and `DotfilesScreen` define their own `floatingActionButton: FAB(Icons.add)`, which renders at the same `endFloat` position as the outer `HomeScreen` FAB (`Icons.search` / command palette). Only the topmost FAB receives tap events; the one beneath it is visually present but unreachable.

The fix is a single-file, low-risk change confined to `home_screen.dart` lines 148-161. By returning `null` from the `floatingActionButton` property when `_currentIndex` is `_kTabCron` (1) or `_kTabDotfiles` (2), the outer command-palette FAB is suppressed on those tabs and the inner `+` FAB becomes exclusively visible and tappable. Both constants (`_kTabCron`, `_kTabDotfiles`) are already defined in `home_screen.dart`; no new constants or imports are required. `SessionsScreen` and `SystemScreen` are unaffected because they do not define their own FAB.

The three other researchers (STACK, FEATURES, PITFALLS) operated under the initial assumption that the bug was an AppBar `actions` overlap — their findings are valid Flutter knowledge but address a problem that does not exist in this codebase. Their recommendations become relevant only if search fields are later added to the AppBar, but that is out of scope for this fix. The immediate task is one ternary change in one file.

---

## Key Findings

### Recommended Stack

No stack changes are required. The fix uses only existing Flutter layout primitives already present in `home_screen.dart`. The project runs Flutter stable / Dart `^3.9.0` with Material 3 (`useMaterial3: true`). All relevant widgets (`FloatingActionButton`, `Scaffold`, `IndexedStack`) are standard `flutter/material.dart` exports.

**Core technologies involved in the fix:**
- `Scaffold.floatingActionButton` — returns `null` to suppress the outer FAB on affected tabs — standard Flutter API
- `IndexedStack` — the structural reason all child Scaffolds remain alive; no change needed to this widget
- `_kTabCron` / `_kTabDotfiles` — integer constants already defined at lines 17-19 of `home_screen.dart`

### Expected Features

The only feature requirement for this fix is stated in PROJECT.md:

**Must have (table stakes):**
- Search/command-palette FAB (`Icons.search`) remains accessible on Sessions and System tabs — non-negotiable, it is the primary command palette entry point
- Add-new FAB (`Icons.add`) on Cron and Dotfiles tabs is exclusively visible and tappable — this is the bug resolution

**Out of scope for this fix:**
- Adding a search/filter TextField to either screen's AppBar — separate feature, not this bug
- Modifying CronScreen or DotfilesScreen internals — not needed under Option A
- Backend changes — purely Flutter layout

### Architecture Approach

`HomeScreen` uses `IndexedStack` to hold all four tab screens simultaneously in the widget tree. This pattern preserves state across tab switches but has the documented side effect that FABs from all live `Scaffold` children are registered in Flutter's overlay simultaneously. The outer HomeScreen FAB and any inner-screen FAB both render at `FloatingActionButtonLocation.endFloat` with no automatic deconfliction.

**The collision chain:**
1. `HomeScreen` (`Scaffold`) defines `floatingActionButton: FAB.small(Icons.search)` — always present
2. `CronScreen` (`Scaffold`) defines `floatingActionButton: FAB(Icons.add)` — rendered on top of #1 when tab 1 is active
3. `DotfilesScreen` (`Scaffold`) defines `floatingActionButton: FAB(Icons.add)` — rendered on top of #1 when tab 2 is active
4. `SessionsScreen` and `SystemScreen` define no FAB — HomeScreen FAB is the only one on tabs 0 and 3, no collision

**Recommended fix — Option A (`home_screen.dart` only):**

```dart
// lines 148-161
floatingActionButton: widget.showDebug && _currentIndex == 0
    ? FloatingActionButton(...)
    : (_currentIndex == _kTabCron || _currentIndex == _kTabDotfiles)
        ? null   // inner screen owns the FAB; suppress outer
        : FloatingActionButton.small(
            heroTag: 'command_palette_fab',
            onPressed: () => showCommandPalette(...),
            child: const Icon(Icons.search),
          ),
```

No changes to `cron_screen.dart` or `dotfiles_screen.dart`.

### Critical Pitfalls

The following are the top risks for this specific change:

1. **Breaking the command palette on all tabs** — Returning `null` unconditionally instead of only for `_kTabCron` and `_kTabDotfiles` would remove the command palette FAB from Sessions and System tabs where it is the only FAB and is expected. The null branch must be guarded by the tab index check.

2. **Off-by-one tab index** — The constants `_kTabCron = 1` and `_kTabDotfiles = 2` are already defined; use them by name, not by literal integers. If tab order ever changes, literal integers silently suppress the wrong FAB.

3. **showDebug branch interaction** — The existing `floatingActionButton` expression has a leading `widget.showDebug && _currentIndex == 0` branch for a debug FAB. The new null branch must be inserted as the second condition in the ternary chain, not before the debug check, to avoid accidentally suppressing debug tooling.

4. **IndexedStack FAB rendering on non-visible tabs** — Because `IndexedStack` keeps all widgets alive, even the currently hidden tabs may contribute FABs. After the fix, verify that navigating away from Cron/Dotfiles and back does not cause a one-frame flash of both FABs due to widget rebuild order. The `null` return eliminates the outer FAB immediately; no animation delay is expected, but manual verification on device is warranted.

5. **Appbar pitfalls from PITFALLS.md are not relevant now but must be observed if a search TextField is ever added** — Specifically: never place a `TextField` directly in `AppBar.actions` (causes RenderFlex overflow); always use `Expanded` inside `title`; always dispose `TextEditingController` in `dispose()`. These are deferred concerns, not blockers for this fix.

---

## Implications for Roadmap

This is a single-phase fix with no dependencies.

### Phase 1: FAB Collision Fix

**Rationale:** The root cause is fully identified (dual FAB at `endFloat` from nested Scaffolds inside IndexedStack), the exact file and lines are known (`home_screen.dart` lines 148-161), and the change is a 3-line ternary addition. No research gaps remain. No new dependencies. No architectural changes.

**Delivers:**
- Command palette FAB (`Icons.search`) suppressed on Cron and Dotfiles tabs
- Add-new FAB (`Icons.add`) on Cron and Dotfiles tabs is the only FAB present and fully tappable
- Command palette FAB remains functional on Sessions (tab 0) and System (tab 3) tabs
- No regression to any existing CronScreen or DotfilesScreen functionality

**File to change:** `flutter/lib/features/home/screens/home_screen.dart`

**Lines to change:** 148-161 (the `floatingActionButton:` expression)

**Change complexity:** Low — add two null branches to the existing ternary

**Must avoid:**
- Unconditional null return (breaks command palette on Sessions/System)
- Literal tab index integers (use `_kTabCron` / `_kTabDotfiles` constants)
- Disturbing the existing `widget.showDebug` leading branch

**Test targets after change:**
- Android phone: Cron tab — only `+` FAB visible; Sessions tab — command palette FAB visible
- Android tablet: same as above
- Web viewport: same as above
- Functional regression: add/edit/delete a cron job; add/edit/delete a dotfile

### Phase Ordering Rationale

Single phase. The architecture agent eliminated all ambiguity about the cause. There are no dependencies, no prerequisite infrastructure changes, and no decisions deferred to a later phase.

### Research Flags

No phases need deeper research. The fix is fully specified from direct source inspection.

Standard pattern (no additional research needed): The `Scaffold.floatingActionButton` returning `null` to suppress a FAB on specific tab indices is a documented Flutter pattern. All required constants are already in the file.

**Secondary finding worth a follow-up issue (not blocking):** `DotfileEditorScreen` has up to 8 `IconButton` widgets in its AppBar `actions` list, which can overflow on 360dp phone screens. This is a separate problem from the FAB collision and is explicitly out of scope for this fix. The PITFALLS.md and STACK.md AppBar guidance would apply when that issue is addressed.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | No stack changes needed; all findings from direct `pubspec.yaml` and source inspection |
| Features | HIGH | Single non-negotiable requirement (both FABs must be tappable); confirmed by PROJECT.md |
| Architecture | HIGH | Architecture agent read all 5 affected source files plus git history; root cause is unambiguous |
| Pitfalls | HIGH | Pitfalls from PITFALLS.md address AppBar TextField patterns, not the FAB fix; FAB-specific risks documented above are low-severity and easily avoided |

**Overall confidence:** HIGH

### Gaps to Address

No meaningful gaps remain.

- **STACK.md and FEATURES.md operated on a wrong initial hypothesis** (AppBar TextField overlap rather than FAB collision). Their findings are accurate Flutter knowledge but do not apply to the immediate fix. If a search TextField is ever added to the CronScreen or DotfilesScreen AppBar in a future iteration, the STACK.md `Row/Expanded` approach and the PITFALLS.md controller lifecycle warnings become directly relevant.
- **IndexedStack FAB render timing** — one minor uncertainty: whether Flutter renders the FAB from a non-visible `IndexedStack` child before or after the parent Scaffold's FAB settles. Manual verification on device after the fix is the only way to confirm no one-frame flicker occurs. This is not a blocker; it is a post-fix manual check.

---

## Sources

### Primary (HIGH confidence)

- Direct source inspection: `flutter/lib/features/home/screens/home_screen.dart` (lines 17-19, 148-161)
- Direct source inspection: `flutter/lib/features/cron/screens/cron_screen.dart` (lines 31-38, 136-146)
- Direct source inspection: `flutter/lib/features/dotfiles/screens/dotfiles_screen.dart` (lines 56-80, 130-134)
- Flutter official API — `AppBar` class: https://api.flutter.dev/flutter/material/AppBar-class.html
- Flutter official API — `PreferredSize` class: https://api.flutter.dev/flutter/widgets/PreferredSize-class.html
- Git history: commits `3f542b0b`, `5bf27a00` (confirmed FAB and alert navigation history)

### Secondary (MEDIUM confidence)

- KindaCode — Flutter add search field to AppBar: https://www.kindacode.com/article/flutter-add-a-search-field-to-the-app-bar (confirmed `Row/Expanded` pattern; not needed for this fix)
- Flutter GitHub issue #68130 — RenderFlex in AppBar actions (confirms TextField-in-actions pitfall; pre-emptive knowledge for future work)
- Flutter GitHub issue #146068 — SearchController disposed after use (pre-emptive for future search field work)

---

*Research completed: 2026-03-25*
*Ready for roadmap: yes*
