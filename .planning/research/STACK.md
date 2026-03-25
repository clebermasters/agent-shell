---
focus: tech
generated: 2026-03-25
scope: AppBar layout fix — search field + action button overlap
---

# Technology Stack

**Project:** AgentShell — Cron & Dotfile Screen Layout Fix
**Researched:** 2026-03-25
**Confidence:** HIGH (verified against official Flutter API docs + live codebase inspection)

---

## Existing Project Constraints

| Constraint | Value | Source |
|------------|-------|--------|
| Dart SDK | `^3.9.0` | `flutter/pubspec.yaml` |
| Flutter channel | stable | `flutter/pubspec.yaml` |
| Material version | `useMaterial3: true` | `flutter/lib/core/theme/app_theme.dart` |
| State management | Riverpod `^2.6.1` (StateNotifier) | `flutter/pubspec.yaml` |
| Targets | Android (phone + tablet) + Web | PROJECT.md |

Flutter stable with Dart 3.9 ships Flutter 3.27+. All Material 3 widgets (including `SearchBar`, `SearchAnchor`, `Badge`) are available. No dependency changes are needed for this fix.

---

## Root Cause Diagnosis (from codebase inspection)

Both `CronScreen` and `DotfilesScreen` currently define `AppBar.actions` with an `AlertsBellButton` (wrapped `Badge` + `IconButton`) plus additional `IconButton` widgets. The PROJECT.md confirms a "search/filter capability" that overlaps with the config/settings button. The overlap happens because:

1. A search `TextField` (or the `AlertsBellButton` itself) is being placed in `AppBar.title` as a bare `Row` without an `Expanded` constraint on the text field, causing both the title area and the `actions` list to compete for the same horizontal space.
2. **OR** the search field is an `actions` item rendered without width constraint, making adjacent buttons physically overlap when the combined width exceeds the available `actions` area.

The fix does not require replacing the `AppBar` widget — only restructuring what is inside it.

---

## Recommended Flutter APIs for This Fix

### Primary Recommendation: AppBar.title as a Row with Expanded

**Confidence: HIGH** — documented in official Flutter AppBar API, and the existing codebase already uses this pattern in `dotfile_editor_screen.dart` (lines 386–404).

Place the search `TextField` in `AppBar.title` wrapped in a `Row` → `Expanded`. Keep action buttons in `AppBar.actions`. Set `titleSpacing: 0` to eliminate the default horizontal gap that otherwise wastes space.

```dart
AppBar(
  titleSpacing: 0,           // removes default 16dp leading gap
  title: Row(
    children: [
      Expanded(
        child: TextField(
          controller: _searchController,
          decoration: const InputDecoration(
            hintText: 'Filter...',
            prefixIcon: Icon(Icons.search),
            border: InputBorder.none,
          ),
        ),
      ),
    ],
  ),
  actions: [
    const AlertsBellButton(),
    IconButton(
      icon: const Icon(Icons.refresh),
      onPressed: () => ...,
    ),
  ],
)
```

**Why this works:** `AppBar` lays out `leading` → `title` → `actions` left-to-right. `Expanded` inside the title `Row` tells Flutter the `TextField` must fill exactly the space between the leading area and the start of `actions`. The `actions` list is laid out independently at the right edge. They cannot overlap because `Expanded` respects the pre-allocated `actions` width.

**Why it is correct for this project:** `DotfileEditorScreen` already uses the toggle-search-via-icon pattern. `CronScreen` and `DotfilesScreen` should use the always-visible inline pattern (the search is a primary affordance on list screens, not a secondary editor tool), and the `Row`/`Expanded` title approach is the standard for this use case.

**Both Android and Web:** Flutter's layout engine applies the same horizontal constraints on both platforms. The `Row`/`Expanded` approach is platform-agnostic.

---

### Alternative A: AppBar.bottom with PreferredSizeWidget

**Confidence: HIGH** — official Flutter API, well-documented.

`AppBar.bottom` accepts any `PreferredSizeWidget`. You can render a search bar in a dedicated row below the main toolbar, leaving `title` and `actions` unaffected.

```dart
AppBar(
  title: const Text('Cron Jobs'),
  actions: [
    const AlertsBellButton(),
    IconButton(icon: const Icon(Icons.refresh), onPressed: ...),
  ],
  bottom: PreferredSize(
    preferredSize: const Size.fromHeight(48),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: TextField(
        controller: _searchController,
        decoration: const InputDecoration(
          hintText: 'Filter jobs...',
          prefixIcon: Icon(Icons.search),
          isDense: true,
          border: OutlineInputBorder(),
        ),
      ),
    ),
  ),
)
```

**Why to use it:** When the toolbar is already crowded (many `actions`) and you want the search field to have a full-width dedicated row. It is zero-risk for the toolbar layout.

**Why NOT preferred for this case:** Adds 48dp of permanent vertical real estate to every screen, even when the list has few items. On small Android phones this is noticeable. The `DotfileEditorScreen` search pattern (toggled inline in body) already shows the project prefers not consuming permanent vertical space.

---

### Alternative B: Toggle-to-search via IconButton (existing pattern)

**Confidence: HIGH** — already implemented in `DotfileEditorScreen._buildSearchBar`.

An `IconButton` (search icon) in `actions` toggles a `bool _showSearch` state. When `true`, a search `TextField` appears as the first widget in `body` (inside a `Column`), below the `AppBar`.

**Why NOT preferred for CronScreen / DotfilesScreen:** On these screens the search is a primary list filter — always needing it visible without a tap is better UX than toggling. The toggle pattern is correct for `DotfileEditorScreen` because the text editor is the primary content; search is secondary there. On list screens, filter-as-you-type is the expected behaviour.

---

### Do NOT Use: SearchBar / SearchAnchor (Material 3)

**Confidence: HIGH** — available in this Flutter version, but wrong tool for this job.

`SearchBar` and `SearchAnchor` (introduced in Flutter 3.7, Material 3) are designed to open a full-screen or overlay search view with suggestion results. They expand into a separate route. They are not a filter field that narrows an already-rendered list in place.

Using `SearchAnchor` here would require implementing a suggestion provider, routing, and a results list — that is a feature addition, not a layout bug fix. It also changes the UX model entirely. **Avoid.**

---

### Do NOT Use: Stack / Positioned inside AppBar

**Confidence: HIGH** — this is the cause of the current bug, not the fix.

Manually positioning widgets with `Stack` + `Positioned` inside `AppBar.title` or `AppBar.flexibleSpace` to overlap the search field and buttons is the pattern that produced the bug in the first place. Never layer layout-sibling widgets in a `Stack` when `Row`/`Expanded` expresses the intent correctly.

---

### Do NOT Use: titleSpacing alone (without Expanded)

`AppBar(titleSpacing: 0, title: TextField(...))` without `Expanded` will cause the `TextField` to measure its width as unconstrained and throw a `RenderFlex` overflow or expand beyond the actions area. Always pair `titleSpacing: 0` with `Expanded` wrapping the `TextField`.

---

## Relevant Flutter Widget APIs

| Widget / Parameter | Purpose | Confidence |
|--------------------|---------|------------|
| `AppBar.title` | Accepts any widget; laid out between leading and actions | HIGH |
| `AppBar.titleSpacing` | Horizontal padding around title area; set to `0` to reclaim space | HIGH |
| `AppBar.actions` | `List<Widget>` placed at trailing edge; independently sized | HIGH |
| `Row` (in title) | Horizontal container for search field + optional inline elements | HIGH |
| `Expanded` (wrapping TextField) | Forces TextField to fill remaining title space, never overflowing into actions | HIGH |
| `AppBar.bottom` + `PreferredSize` | Adds a full-width row below the toolbar; `preferredSize: Size.fromHeight(48)` is typical | HIGH |
| `TextField.decoration.isDense: true` | Reduces vertical padding; keeps the search field compact inside AppBar | HIGH |
| `InputDecoration.border: InputBorder.none` | Removes the underline/box; looks native inside AppBar | HIGH |
| `AlertsBellButton` | Already in `actions` on both screens; leave it there, do not move it to title | HIGH |
| `Badge` | Already wraps `AlertsBellButton`; compatible with `actions` list | HIGH |

---

## Implementation Notes for Both Screens

**CronScreen** (`flutter/lib/features/cron/screens/cron_screen.dart`):
- Current `actions`: `[AlertsBellButton(), IconButton(refresh)]`
- No search `TextField` currently in the `AppBar`
- Add a `_filterQuery` `String` state field + `TextEditingController`
- Place `TextField` in `AppBar.title` via `Row`/`Expanded`
- Filter `cronState.jobs` with `.where((job) => job.name.toLowerCase().contains(_filterQuery))` before passing to `ListView.builder`

**DotfilesScreen** (`flutter/lib/features/dotfiles/screens/dotfiles_screen.dart`):
- Current `actions`: `[AlertsBellButton(), IconButton(refresh), IconButton(templates)]`
- Three `actions` items leave limited title space — using `AppBar.bottom` with `PreferredSize` may be the cleaner option here to avoid cramping
- Alternatively, use `Row`/`Expanded` title with `titleSpacing: 0`; three `IconButton` widgets measure at `48dp` each = `144dp`, well within standard AppBar width
- Filter `dotfilesState.files` with `.where(...)` before `_groupFilesByType`

---

## No New Dependencies Required

All widgets used (`Row`, `Expanded`, `TextField`, `AppBar`, `PreferredSize`) are part of `flutter/material.dart`, which is already in the project. Zero pubspec changes needed.

---

## Sources

- [AppBar class — Flutter API docs](https://api.flutter.dev/flutter/material/AppBar-class.html) — HIGH confidence
- [PreferredSize class — Flutter API docs](https://api.flutter.dev/flutter/widgets/PreferredSize-class.html) — HIGH confidence
- [SearchBar class — Flutter API docs](https://api.flutter.dev/flutter/material/SearchBar-class.html) — HIGH confidence (consulted to confirm it is NOT the right tool)
- [Flutter: Add a Search Field to an App Bar — KindaCode](https://www.kindacode.com/article/flutter-add-a-search-field-to-the-app-bar) — MEDIUM confidence (cross-referenced with official docs)
- Live codebase inspection: `dotfile_editor_screen.dart` lines 386–404, `cron_screen.dart`, `dotfiles_screen.dart`, `app_theme.dart` — HIGH confidence
