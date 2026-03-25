---
focus: architecture
generated: 2026-03-25
---

# Architecture: Cron and Dotfile AppBar Overlap

**Project:** AgentShell — Flutter AppBar layout bug fix
**Researched:** 2026-03-25
**Confidence:** HIGH (all source files examined)

## Root Cause

The bug is a **dual FloatingActionButton collision**, not an AppBar issue.

`HomeScreen` and both sub-screens (`CronScreen`, `DotfilesScreen`) each define their own `Scaffold` with a `floatingActionButton`. Flutter renders FABs from **all nested Scaffolds** simultaneously at the same bottom-right coordinate, so the outer FAB sits directly on top of the inner FAB.

### FAB Stack at Runtime

| Screen | FAB | Source location |
|--------|-----|----------------|
| `HomeScreen` (outer) | `FloatingActionButton.small` — `Icons.search` command palette | `home_screen.dart:148-161` |
| `CronScreen` (inner, tab 1) | `FloatingActionButton` — `Icons.add` new job | `cron_screen.dart:136-146` |
| `DotfilesScreen` (inner, tab 2) | `FloatingActionButton` — `Icons.add` new file | `dotfiles_screen.dart:130-134` |

Both FABs are rendered at the default `FloatingActionButtonLocation.endFloat` position. The outer `FloatingActionButton.small` has `heroTag: 'command_palette_fab'`; the inner FABs use the Flutter default heroTag (`const Object()`). No heroTag conflict crash occurs, but the two widgets occupy the same pixel region and only the topmost one receives tap events.

### Why Only Cron and Dotfiles Are Affected

`SessionsScreen` and `SystemScreen` do not define a `floatingActionButton` on their inner Scaffold, so the HomeScreen FAB is the only one rendered on those tabs. `CronScreen` and `DotfilesScreen` both add their own `+` FAB, creating the collision only on tabs 1 and 2.

## Widget Hierarchy Causing the Overlap

```
HomeScreen (Scaffold)
├── body: Column
│   ├── AlertBanner
│   ├── ConnectionStatusBanner
│   └── Expanded
│       └── IndexedStack          ← screens are stacked here; all exist in tree
│           ├── SessionsScreen (Scaffold — no FAB)
│           ├── CronScreen (Scaffold)
│           │   └── floatingActionButton: FAB(Icons.add)  ← COLLIDES
│           ├── DotfilesScreen (Scaffold)
│           │   └── floatingActionButton: FAB(Icons.add)  ← COLLIDES
│           └── SystemScreen (Scaffold — no FAB)
└── floatingActionButton: FAB.small(Icons.search)         ← OUTER FAB
```

`IndexedStack` keeps all children alive in the widget tree simultaneously. Flutter's overlay handles FABs from all visible `Scaffold` widgets, so even off-screen tabs may contribute FABs. The visible tab's inner FAB plus the outer HomeScreen FAB both render at the same position.

## Exact Files That Need to Change

Only **one** of these approaches needs to be applied:

### Option A — Remove the outer HomeScreen FAB on tabs that have their own FAB (recommended minimal change)

**File:** `flutter/lib/features/home/screens/home_screen.dart`

Change the `floatingActionButton` property to return `null` when `_currentIndex` is 1 (Cron) or 2 (Dotfiles):

```dart
// home_screen.dart lines 148-161
floatingActionButton: widget.showDebug && _currentIndex == 0
    ? FloatingActionButton(...)
    : (_currentIndex == _kTabCron || _currentIndex == _kTabDotfiles)
        ? null   // inner screen owns the FAB on these tabs
        : FloatingActionButton.small(
            heroTag: 'command_palette_fab',
            onPressed: () => showCommandPalette(...),
            child: const Icon(Icons.search),
          ),
```

No changes needed to `cron_screen.dart` or `dotfiles_screen.dart`.

### Option B — Remove the FAB from the inner screens and keep it in HomeScreen

**Files:** `flutter/lib/features/cron/screens/cron_screen.dart`, `flutter/lib/features/dotfiles/screens/dotfiles_screen.dart`

Remove `floatingActionButton` from both inner `Scaffold`s and add the `+` action as an AppBar action icon or as a separate FAB registered in HomeScreen via a callback. This is a larger change and is not the minimal approach.

### Option C — Assign distinct heroTags and use a Stack

Assign a unique `heroTag` to each inner FAB and use `FloatingActionButtonLocation` offsets to position them above each other. This adds visual complexity and is not recommended.

## Recommended Fix: Option A

**Rationale:** Option A is a single-file, 3-line change. The command palette (search) FAB is a HomeScreen-level concern; the `+` FABs in CronScreen/DotfilesScreen are feature-level concerns. Suppressing the outer FAB when the inner screen provides its own is the cleanest separation of responsibility.

**Specific change:**

```
File:   flutter/lib/features/home/screens/home_screen.dart
Lines:  148-161
Change: Add null branch for _kTabCron and _kTabDotfiles in floatingActionButton expression
```

The constants `_kTabCron = 1` and `_kTabDotfiles = 2` are already defined at the top of `home_screen.dart` (lines 17-19), so no new constants are needed.

## Secondary Finding: DotfileEditorScreen AppBar Crowding

The `DotfileEditorScreen` (`dotfiles_screen.dart` sub-screen, pushed via `Navigator.push`) has a separate crowding issue: its AppBar `actions` list contains up to 8 icon buttons (Save, Preview, Zoom+, Zoom-, History, Search toggle, Scroll-to-end, Copy). On narrow phone screens these buttons overflow. This is a separate issue from the FAB collision and is **not** the primary bug described in PROJECT.md, but it may be worth noting for a follow-up.

## Current AppBar Actions (Not the Bug, But Context)

Both list screens have clean AppBar `actions` lists:

**CronScreen AppBar actions** (`cron_screen.dart:31-37`):
```
[AlertsBellButton, IconButton(refresh)]
```

**DotfilesScreen AppBar actions** (`dotfiles_screen.dart:56-80`):
```
[AlertsBellButton, IconButton(refresh), IconButton(bookmark/templates)]
```

These are correctly implemented as separate list items and do not overlap. The PROJECT.md description of "search field and config button" maps to the FAB-on-FAB collision, where `Icons.search` (search/command palette) is the "search" button and `Icons.add` is the "config" button.

## Summary for Fix Plan

| Item | Detail |
|------|--------|
| Bug type | Dual FAB render collision |
| Root cause | `IndexedStack` keeps all child Scaffolds alive; inner FABs render alongside outer FAB |
| Affected tabs | `_kTabCron` (index 1), `_kTabDotfiles` (index 2) |
| Files to change | `home_screen.dart` only (Option A) |
| Lines to change | 148-161 (floatingActionButton expression) |
| Change complexity | Low — add two null branches to existing ternary |
| Test targets | Android phone, Android tablet, web viewport; verify `+` FAB works, command palette still accessible on Sessions and System tabs |

## Sources

All findings from direct source examination of:
- `/home/cleber_rodrigues/project/webmux/flutter/lib/features/home/screens/home_screen.dart`
- `/home/cleber_rodrigues/project/webmux/flutter/lib/features/cron/screens/cron_screen.dart`
- `/home/cleber_rodrigues/project/webmux/flutter/lib/features/dotfiles/screens/dotfiles_screen.dart`
- `/home/cleber_rodrigues/project/webmux/flutter/lib/features/dotfiles/screens/dotfile_editor_screen.dart`
- `/home/cleber_rodrigues/project/webmux/flutter/lib/features/alerts/widgets/alerts_bell_button.dart`
- Git history: commits `3f542b0b`, `5bf27a00`
