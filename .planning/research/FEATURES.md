---
domain: Flutter screen layout — search field + action button coexistence
researched: 2026-03-25
milestone: Cron & Dotfile screen overlap fix
---

# Feature Landscape: Search Field + Action Button Layout

**Domain:** Mobile app screens that show both a persistent filter/search field and secondary action buttons
**Researched:** 2026-03-25
**Overall confidence:** HIGH — grounded in Material Design 3 docs, Flutter official API, and this codebase's own established pattern in `FileBrowserScreen`

---

## Problem Statement

The Cron and Dotfile screens need to display a search/filter field and a config/settings button simultaneously. These two elements are currently overlapping. The question is: what layout pattern do users expect, and what does Flutter natively support?

---

## Table Stakes

Features users expect. Missing = the screen feels broken or inaccessible.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Both elements always tappable | Overlap = one control is inaccessible. This is the bug, not just a cosmetic issue. | Low | Non-negotiable minimum |
| Search activates inline — no full-screen takeover | Full-screen search is for apps where search IS the primary action (Google, Spotify). For a list-filter on a management screen, users expect to filter the visible list in-place. | Low | Confirmed by Material Design 3 "persistent search" pattern |
| Clear (X) button inside search field when text is present | Universal mobile expectation since iOS 6 / Android ICS. Users tap it to reset without backspacing. | Low | Flutter `suffixIcon` on `TextField` |
| Action button remains visible while search is active | Config/settings actions are independent of search state. Hiding them when search is open is disorienting. | Low | Must not be mutually exclusive with the search row |
| Search field auto-focuses when revealed | If the user tapped to open a text field, they expect the keyboard to appear immediately. | Low | `autofocus: true` on `TextField` |

---

## Common Implementations in Flutter Apps

### Pattern A — Toggle-search with body row (RECOMMENDED for this project)

**How it works:**
- AppBar `actions` contains icon buttons: `[SearchIcon, OtherActionIcon, ...]`
- Search icon is a toggle; pressing it sets a `_showSearch` boolean
- When `_showSearch == true`, a `Container` with a styled `TextField` appears at the top of the `Scaffold.body`, above the list
- The AppBar stays unchanged — title and all actions remain visible
- Search field is dismissed by toggling the icon again (which also clears the query)

**Why it fits this project:**
- `FileBrowserScreen` (this codebase, `flutter/lib/features/file_browser/screens/file_browser_screen.dart`) already implements this pattern exactly. Lines 452–485 show the conditional `Container(TextField(...))` at the top of the `body` Column. Lines 568–582 show the AppBar search icon toggle in `actions`.
- The Cron and Dotfile screens can match this established convention precisely.
- Zero new dependencies.
- No layout conflict possible: the search row is in the `body`, the config button is in `AppBar.actions`. They occupy different vertical regions.

**Flutter layout structure:**
```dart
Scaffold(
  appBar: AppBar(
    title: Text('Cron Jobs'),
    actions: [
      AlertsBellButton(),          // already there
      IconButton(icon: Icon(Icons.search), onPressed: _toggleSearch),
      IconButton(icon: Icon(Icons.settings), onPressed: _openConfig),
      IconButton(icon: Icon(Icons.refresh), onPressed: _refresh),
    ],
  ),
  body: Column(
    children: [
      if (_showSearch)
        Container(
          padding: EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: TextField(
            controller: _searchController,
            autofocus: true,
            decoration: InputDecoration(
              prefixIcon: Icon(Icons.search),
              hintText: 'Filter jobs…',
              suffixIcon: _query.isNotEmpty
                  ? IconButton(icon: Icon(Icons.close), onPressed: _clearSearch)
                  : null,
            ),
          ),
        ),
      Expanded(child: ListView.builder(...)),
    ],
  ),
)
```

**Confidence:** HIGH — matches Flutter docs, Material Design 3, and this codebase's own FileBrowserScreen.

---

### Pattern B — Search field always visible in body (NOT recommended)

**How it works:**
- A search `TextField` sits permanently at the top of the `Scaffold.body`, above the list.
- It is always rendered, occupying vertical space whether used or not.

**When appropriate:**
- Screens where filtering is the primary action (a contact picker, a search-first feature).
- Long lists (50+ items) where users routinely filter.

**Why it does not fit here:**
- Cron jobs and dotfiles are management screens. Users arrive to manage entries, not primarily to search them. A permanently-visible search row wastes screen real estate on small phones.
- The PROJECT.md says "fix does not regress any existing Cron or Dotfile functionality" and "minimal change" — adding a persistent bar changes the existing visual weight significantly.

**Confidence:** HIGH (well-established pattern, just wrong scope here)

---

### Pattern C — Search as AppBar title replacement (NOT recommended)

**How it works:**
- A search icon in AppBar actions, when tapped, replaces the AppBar `title` with a `TextField`.
- Config/action buttons remain in `actions` beside the now-text-field title area.

**Why it does not fit here:**
- When `title` becomes a `TextField`, the screen loses its title label. Users lose orientation ("which screen am I on?").
- The config button staying in `actions` while the title area is a search field creates an inconsistent visual hierarchy — the config button appears to belong to the search context.
- This pattern suits apps that navigate to a search sub-mode (like Gmail), not list screens that filter in-place.

---

### Pattern D — SliverAppBar with search in `bottom` (NOT recommended)

**How it works:**
- `AppBar.bottom` contains a `PreferredSize` wrapping a `TextField`.
- The title and actions appear in the primary toolbar row; the search field appears as a second row of the AppBar.

**Why it does not fit here:**
- `AppBar.bottom` always renders — the search field would occupy screen space even when unused (same issue as Pattern B).
- This is appropriate for e-commerce apps where searching is the dominant gesture on a screen.
- The existing screens use plain `AppBar`, not `SliverAppBar`. Introducing slivers for a minimal bug fix violates the "minimal change" constraint from PROJECT.md.

---

### Pattern E — Inline search icon in the same actions row as config (current broken state, DO NOT USE)

**What is currently broken:**
Reading the current `CronScreen` code (lines 31–38): the AppBar `actions` list contains `[AlertsBellButton(), RefreshButton]`. There is no search field yet. The "overlap" described in PROJECT.md is likely caused by the **planned addition** of a search element being placed at the same layout slot as an existing action button — either both being added to `actions` without adequate horizontal space, or a Stack being introduced accidentally.

The fix is not to cram both into the same horizontal slot without proper `Expanded`/`Flexible` constraints. A `TextField` in `actions` without wrapping it in `Expanded` causes the text field to fight with other action icons for the same space — this is a well-known Flutter pitfall that produces the overlap described.

---

## Anti-Patterns to Avoid

| Anti-Pattern | Why Avoid | What to Do Instead |
|---|---|---|
| `TextField` in `AppBar.actions` without `Expanded` | `actions` does not constrain its children — without `Expanded`, a `TextField` defaults to a zero-width or overflowing box; this is the most likely cause of the current overlap | Place the search TextField in the `body`, not in `actions` |
| Stack with search field over the action button | Produces exact bug described — one widget physically on top of another, making one unreachable | Use Column (vertical separation) not Stack (z-axis overlap) |
| Hiding config button when search is active | User may want to change config while viewing filtered results; makes the flow non-linear | Keep all AppBar actions visible regardless of search state |
| Navigating to a new screen for search | Adds a route, a back button, and nav cost for a simple list filter | Filter the existing list in-place |
| `PopupMenuButton` grouping search + config together | These are different types of actions. Config is a navigation action; search is an inline filter. They should not share a menu. | Keep them as separate `IconButton` entries |
| Not clearing search query when toggle is dismissed | User re-opens search and sees stale filter applied, making list appear wrong | `_searchController.clear()` when `_showSearch` is toggled off |

---

## Feature Dependencies

```
Search toggle button in AppBar.actions
  → _showSearch boolean in ConsumerStatefulWidget state
  → Conditional Container(TextField) in body Column above ListView
  → TextEditingController + listener → filtered list passed to ListView.builder
```

Config button in AppBar.actions:
  → Completely independent; no dependency on search state

---

## MVP Recommendation

This is a bug fix, not a feature design exercise. The target outcome is:

1. **Move search out of AppBar.actions into the body** — use the toggle pattern from `FileBrowserScreen` (Pattern A above). Exactly mirrors the existing codebase convention.
2. **Config/settings button stays in AppBar.actions** — as an `IconButton` alongside the existing bell and refresh buttons.
3. **Search icon in AppBar.actions** — toggles the body search row.

No new packages required. No Sliver migration required. No architectural changes.

Defer:
- **Animated expansion of search row** — a `AnimatedContainer` for the body row would be polished but is not table stakes for this fix.
- **Debounce on search TextField** — useful performance optimization for large lists, but the lists here are small; not needed for the fix.

---

## Codebase-Specific Notes

### Established convention in this codebase (CONFIRMED HIGH confidence)

`FileBrowserScreen` (`flutter/lib/features/file_browser/screens/file_browser_screen.dart`) already implements Pattern A precisely:

- `bool _showSearch = false` + `String _searchQuery = ''` state fields (lines 21–23)
- AppBar search `IconButton` in `actions` that toggles `_showSearch` (lines 568–582)
- Conditional `Container(TextField(...))` at the top of `body` Column (lines 452–485)
- Filtered list: `entries.where(e.name.contains(query))` passed to `ListView.builder` (lines 438–443)
- `TextEditingController` cleaned up in `dispose()` (line 36)

**The Cron and Dotfile screens should replicate this exact pattern.** This is not a new design decision; it is bringing these screens into conformance with the convention the codebase already established.

### Current AppBar.actions on each screen

**CronScreen** (lines 31–38): `[AlertsBellButton(), RefreshIconButton]`
**DotfilesScreen** (lines 56–80): `[AlertsBellButton(), RefreshIconButton, TemplatesIconButton]`

The config button to be added will slot in as another `IconButton` in `actions`. On the Dotfile screen, after the fix: `[AlertsBellButton(), SearchIconButton, ConfigIconButton, RefreshIconButton, TemplatesIconButton]`. This is 5 items. On small phones, 5 items can get crowded.

**Mobile screen width consideration:** A typical Android phone AppBar at 360dp logical width with a title uses approximately 180–200dp for the actions area. Each `IconButton` default touch target is 48dp. Five buttons would need 240dp — this overflows.

Mitigation options (in order of preference for a minimal fix):
1. **Reduce the action count** — merge less-used actions into a `PopupMenuButton` (the `...` three-dot menu). Refresh is almost always a candidate. Templates is a navigation action that could move there.
2. **Reduce icon button padding** — `IconButton(padding: EdgeInsets.all(4), constraints: BoxConstraints())` reduces touch target to visual minimum while remaining usable.
3. **Accept the current count** — if the screens already work with 3 items (bell + refresh + templates), adding 2 more (search + config) brings it to 5. Test on actual 360dp device before deciding to trim.

The research recommendation: **add search and config as discrete `IconButton` entries first, then test on a narrow viewport. Trim to PopupMenu only if they actually overflow.**

---

## Sources

- [Flutter AppBar class — official API](https://api.flutter.dev/flutter/material/AppBar-class.html) — MEDIUM confidence (via WebFetch)
- [Material Design 3 — Search component](https://m3.material.io/components/search) — MEDIUM confidence (page CSS-only, content not fetched; cross-checked with M2 docs)
- [Flutter: Add a Search Field to an App Bar — KindaCode](https://www.kindacode.com/article/flutter-add-a-search-field-to-the-app-bar) — MEDIUM confidence (WebFetch verified approach)
- [Flutter SearchBar class — official API](https://api.flutter.dev/flutter/material/SearchBar-class.html) — HIGH confidence (official)
- [UI Patterns For Mobile Apps: Search, Sort And Filter — Smashing Magazine](https://www.smashingmagazine.com/2012/04/ui-patterns-for-mobile-apps-search-sort-filter/) — MEDIUM confidence
- `flutter/lib/features/file_browser/screens/file_browser_screen.dart` in this repo — HIGH confidence (direct code inspection, primary reference)
