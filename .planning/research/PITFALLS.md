# Domain Pitfalls: Flutter AppBar Search + Action Button Layout

**Domain:** Flutter AppBar layout — adding an inline search field alongside existing action icons
**Project:** AgentShell — fixing search/button overlap on CronScreen and DotfilesScreen
**Researched:** 2026-03-25
**Confidence:** HIGH (Flutter docs + tracked GitHub issues + verified against actual codebase)

---

## Context: What This Document Is For

`CronScreen` and `DotfilesScreen` currently have `AppBar` `actions` lists that contain
`AlertsBellButton`, a refresh `IconButton`, and (for Dotfiles) a templates `IconButton`.
The fix adds an inline search/filter `TextField` to each screen. The pitfalls below are
specific to that exact task and that exact codebase.

---

## Critical Pitfalls

Mistakes that trigger a rewrite, an invisible button, or a crash.

---

### Pitfall 1: Putting the Search TextField Directly in `actions`

**What goes wrong:**
`AppBar.actions` is a `Row` with no `Expanded` children. Every widget placed in it is
measured at its natural (intrinsic) width. A `TextField` has no intrinsic width constraint
— Flutter tries to render it at infinite width and throws a `RenderFlex overflowed`
exception at runtime, printing yellow/black overflow stripes across the bar.

**Why it happens:**
`actions` is laid out by a `Row` widget internally. `Row` measures each child in
unconstrained mode first. A bare `TextField` inside an unconstrained `Row` tries to
consume all available horizontal space. The result is the overflow error described in
[Flutter issue #68130](https://github.com/flutter/flutter/issues/68130).

**Consequences:**
- Yellow/black overflow stripes render across the AppBar in debug builds
- In release builds the text field clips silently and the icons to its right become
  unreachable (the exact overlap bug described in PROJECT.md)
- `const AlertsBellButton()` and `IconButton(Icons.refresh)` are pushed off-screen right

**Prevention:**
Never place a raw `TextField` in `actions`. Use one of these two approaches instead:

Option A — `title` widget approach (recommended for this codebase):
```dart
AppBar(
  title: Row(
    children: [
      Expanded(
        child: TextField(
          decoration: const InputDecoration(
            hintText: 'Filter jobs…',
            isDense: true,
            border: InputBorder.none,
            prefixIcon: Icon(Icons.search, size: 20),
          ),
          onChanged: (v) => setState(() => _query = v),
        ),
      ),
    ],
  ),
  actions: [
    const AlertsBellButton(),
    IconButton(icon: const Icon(Icons.refresh), onPressed: _refresh),
  ],
)
```

Option B — `bottom` property approach (adds a second row below the toolbar):
```dart
AppBar(
  title: const Text('Cron Jobs'),
  actions: [...],
  bottom: PreferredSize(
    preferredSize: const Size.fromHeight(48),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: TextField(...),
    ),
  ),
)
```

Option A keeps both elements visible in the same bar row. Option B uses more vertical
space but avoids any horizontal crowding.

**Detection (warning signs):**
- `RenderFlex overflowed by X pixels on the right` in the Flutter debug console
- Any action icon disappears from the right side of the bar
- Hot-reload shows overflow stripes immediately

**Phase:** Fix phase (milestone 1)

---

### Pitfall 2: `Expanded` Inside `actions` Instead of in `title`

**What goes wrong:**
Wrapping the `TextField` in `Expanded` and placing that `Expanded` inside the `actions`
list causes a different crash: `Expanded` widgets must be direct children of `Row`,
`Column`, or `Flex`, but the `actions` list is not an `Expanded`-aware context in all
Flutter rendering paths. Even when it works, `Expanded` in `actions` can crowd adjacent
`IconButton` widgets into zero width, making them invisible but still occupying tap area.

**Why it happens:**
The AppBar `actions` row does not expose its `Row` for `Flex` expansion. `Expanded` is
only valid as a direct child of the internal `Row` that AppBar owns, not as a child you
inject from the outside via the `actions` parameter.

**Consequences:**
- `IconButton` for `AlertsBellButton` and refresh shrinks to 0 px wide — invisible, still
  tappable only by blind luck
- On web with a wide viewport the field may appear to work, masking the mobile regression

**Prevention:**
Expansion must happen inside `title`, not `actions`. Put the `TextField` in `title` with
`Expanded`, leave `actions` as a list of fixed-size `IconButton` widgets.

**Detection:**
- `AlertsBellButton` or refresh icon stops rendering but `AlertsBellButton` still fires
  on tap at the invisible position
- Switching from a wide web viewport to phone simulation reveals the buttons have vanished

**Phase:** Fix phase (milestone 1)

---

### Pitfall 3: Using `Stack` or `Positioned` to Layer the Search Field

**What goes wrong:**
Some tutorials solve AppBar crowding by wrapping the AppBar content in a `Stack` and
positioning the search field via `Positioned`. On Android this renders correctly at the
time of writing. On Flutter web the `Stack` is placed inside the Scaffold coordinate
space, and the `Positioned` child can extend beyond the AppBar bounds, covering the
`Scaffold.body` content at the top (or being clipped by the safe area insets).

**Why it happens:**
Web and Android differ in how they handle the topmost rendering surface. On Android the
AppBar `Stack` is bounded by the system status bar insets. On web there is no such hard
boundary — the overlay canvas covers differently. There is a tracked Flutter issue
([#90101](https://github.com/flutter/flutter/issues/90101)) where autocomplete overlays
placed relative to the AppBar cover the body when the page is scrolled.

**Consequences:**
- Search field on web floats over the `ListView` of cron jobs / dotfiles when the list
  scrolls, blocking taps on list items
- `AlertsBellButton` badge counter may render under the `Stack` child and be invisible

**Prevention:**
Do not use `Stack` / `Positioned` in the AppBar for this task. Use the `title` or
`bottom` property exclusively. These are the layout slots AppBar allocates coordinate
space for on both platforms.

**Detection:**
- Manual scroll test on web: scroll the list down, check whether the search field appears
  to float over the list items
- Flutter inspector `Show baselines` reveals the Positioned child outside its parent bounds

**Phase:** Fix phase (milestone 1); regression check phase

---

### Pitfall 4: Forgetting to Dispose `TextEditingController` and `FocusNode`

**What goes wrong:**
Inline search requires a `TextEditingController` (to clear the field) and often a
`FocusNode` (to dismiss the keyboard on navigation). Both are allocated resources.
If the screen widget is a `ConsumerStatefulWidget` and the controller / node are created
in `build()` rather than `initState()`, a new instance is created on every rebuild,
leaking the old one. Flutter emits a "A TextEditingController was used after being
disposed" error in this case.

There is a directly-tracked Flutter issue for this pattern:
[SearchController disposed after use #146068](https://github.com/flutter/flutter/issues/146068).

**Why it happens:**
Both `CronScreen` and `DotfilesScreen` are `ConsumerStatefulWidget` — the correct class
for lifecycle management. But it is easy to initialise the controller inside `build()`
("I'll just declare it at the top of build…") rather than in `initState`.

**Consequences:**
- Memory leak: previous controller is not disposed
- Assertion thrown in debug mode: `"A TextEditingController was used after being disposed"`
- On hot-restart the field may have stale text from the leaked instance

**Prevention:**
```dart
class _CronScreenState extends ConsumerState<CronScreen> {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocus = FocusNode();

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocus.dispose();
    super.dispose();
  }
  // ...
}
```

Both fields must be declared as instance variables and disposed in `dispose()`.

**Detection:**
- Flutter debug console: `"A TextEditingController was used after being disposed"`
- DevTools Memory tab: `TextEditingController` count grows on repeated navigation
  to/from the screen

**Phase:** Fix phase (milestone 1) — must be correct from first implementation

---

## Moderate Pitfalls

---

### Pitfall 5: Rebuilding the Entire Screen on Every Keystroke

**What goes wrong:**
The simplest implementation puts `_query` in `setState(...)` inside `onChanged`. Because
both `CronScreen` and `DotfilesScreen` call `ref.watch(cronProvider)` /
`ref.watch(dotfilesProvider)` inside `build()`, every `setState` triggers a full rebuild
of the screen including the provider watch. This is a known Riverpod pitfall: placing
`ref.watch` at the top of `build()` causes all child widgets — including the static
`AppBar` title and the `FloatingActionButton` — to rebuild on every single keypress.

**Why it matters for this fix specifically:**
`DotfilesScreen` groups files into sections with `_groupFilesByType()` on every build.
That method creates a new `Map` and sorts keys on every keystroke. With many dotfiles
this is a perceptible jank on low-end Android phones.

**Prevention:**
Use `ref.select` to narrow the rebuild scope, or compute the filter result once via a
derived state in `CronNotifier` / `DotfilesNotifier`. Alternatively, keep `_query` in
local `setState` but move the expensive grouping into a separate `ValueListenableBuilder`
scope so only the list view rebuilds.

For this fix the minimal acceptable approach is local `setState` for `_query` + passing
the filtered list to the `ListView.builder` without calling `ref.read` again inside the
filter — the existing `ref.watch` call at the top of `build` is already the Riverpod
observation point.

**Detection:**
- Flutter DevTools "Rebuild stats" shows `CronScreen` / `DotfilesScreen` rebuilding on
  every character typed
- Noticeable jank on typing in `DotfilesScreen` when more than ~50 files are loaded

**Phase:** Fix phase; optimise if jank is observed

---

### Pitfall 6: `titleSpacing` Causing Invisible Gap or Title Collision

**What goes wrong:**
When `title` contains a `TextField` (rather than a `Text`), the default `titleSpacing`
(16 dp on Material 3) adds padding on the leading edge of the title. This means the
search field does not visually reach the leading edge of the toolbar; there is a gap
between the back button (or hamburger) and the search field. This is cosmetically fine,
but if `titleSpacing: 0` is set to close the gap, the `TextField`'s prefix icon can
overlap the leading button area on compact phone screens (360 dp wide).

There is a tracked Flutter issue on titleSpacing inconsistencies:
[#134911](https://github.com/flutter/flutter/issues/134911).

**Prevention:**
Accept the default `titleSpacing` unless the design specifically requires full-bleed
search. If zero spacing is needed, measure against the minimum supported phone width
(360 dp) and verify the leading icon does not clip the back/drawer button.

**Detection:**
- Visual inspection at 360 dp width in the Flutter Device Preview or Chrome DevTools
  responsive mode
- Prefix search icon renders on top of the AppBar leading button (back arrow visible on
  CronJobEditorScreen when navigating back to CronScreen)

**Phase:** Visual polish, after the overlap bug is fixed

---

### Pitfall 7: `leadingWidth` Override Pushing Actions Off-Screen

**What goes wrong:**
If `leadingWidth` is set to a large value (e.g., to accommodate a wider logo or custom
back button), the title widget's available width shrinks correspondingly. With a
`TextField` in `title`, the field becomes too narrow to be usable. On a 360 dp phone
with a large `leadingWidth`, the field may shrink to under 100 dp.

Tracked Flutter issue: [#130403](https://github.com/flutter/flutter/issues/130403) —
`leadingWidth` has priority over `centerTitle` and can push the title entirely off screen.

**Prevention:**
`CronScreen` and `DotfilesScreen` do not currently set `leadingWidth` or have a custom
leading widget (they are pushed screens with no Drawer). No action needed for this fix.
Do not introduce `leadingWidth` as part of the layout fix.

**Detection:**
- Search field appears with width < 80 dp on a 360 dp phone
- Field is unusable: can't see typed text

**Phase:** Not applicable unless a leading widget is added later

---

### Pitfall 8: `centerTitle: true` Centering the Search Field Instead of the Buttons

**What goes wrong:**
`DotfilesScreen`'s `AppBar` does not explicitly set `centerTitle`. On Android Material 3,
the default is `centerTitle: false` (title is left-aligned). If `centerTitle: true` is
added while the search `TextField` is in `title`, Flutter will try to center the
`Expanded > TextField` within the toolbar. Since `Expanded` consumes all available space,
centering has no visible effect, but on iOS the behavior differs: `centerTitle` defaults
to `true` on iOS, which causes the `TextField` to render centered with fixed width,
shrinking it and leaving unused space beside the action buttons.

This project targets Android and web only, but the codebase uses `kIsWeb` checks in other
widgets (`professional_message_bubble.dart`) — a future iOS build would hit this.

**Prevention:**
Explicitly set `centerTitle: false` on any `AppBar` that has a search `TextField` in
`title`. This makes the behavior consistent across platforms.

**Detection:**
- Run on an iOS simulator (or set `defaultTargetPlatform = TargetPlatform.iOS`): the
  search field appears narrower and centered
- `actions` icons have large empty space to their left

**Phase:** Fix phase — set `centerTitle: false` as a one-liner precaution

---

## Minor Pitfalls

---

### Pitfall 9: Hardcoded Fixed-Width `SizedBox` Around the Search Field

**What goes wrong:**
A common "quick fix" seen in tutorials is `SizedBox(width: 200, child: TextField(...))`.
This works on a 400 dp phone. On a 360 dp phone, the `SizedBox` consumes more than half
the bar width and squeezes the `AlertsBellButton` and refresh `IconButton` into their
minimum sizes (typically 24–28 dp), making them unreachable on a touchscreen (below the
Material 48 dp minimum tap target).

**Prevention:**
Never use a hardcoded width for the search field. Use `Expanded` inside `title` so the
field grows/shrinks with the available toolbar width.

**Detection:**
- Run the app on a 360 dp emulator; tap the bell icon — it may not register
- Flutter accessibility checker flags tap targets smaller than 48×48 dp

**Phase:** Fix phase — do not introduce hardcoded widths

---

### Pitfall 10: Search Filter Not Cleared on Screen Navigation Away

**What goes wrong:**
If `_query` is stored in a `ConsumerStatefulWidget`'s local state and the user types a
filter, navigates to `CronJobEditorScreen` or `DotfileEditorScreen`, then returns, the
`_query` is preserved (the state is kept alive by `Navigator`). The `_searchController`
still shows the old text. However, if the job list was refreshed while the user was on the
editor screen, the filtered view still applies, making newly added/edited jobs invisible
until the search field is manually cleared.

**Prevention:**
Clear the search controller on the `refresh()` call, or show a visible "x" clear button
in the `TextField` suffix:
```dart
decoration: InputDecoration(
  suffixIcon: _query.isNotEmpty
    ? IconButton(
        icon: const Icon(Icons.clear, size: 18),
        onPressed: () {
          _searchController.clear();
          setState(() => _query = '');
        },
      )
    : null,
),
```

**Detection:**
1. Type "abc" in the search field
2. Tap a job to open the editor
3. Return to the list — note the filter is still "abc" but a new job you added is not shown

**Phase:** Fix phase — the clear button is a one-liner that prevents a confusing UX

---

### Pitfall 11: `dart:io` and `Platform` Checks in Search-Related Code on Web

**What goes wrong:**
`DotfilesScreen` already has `professional_message_bubble.dart` in the codebase with
unguarded `dart:io` calls flagged in `CONCERNS.md`. If the search field implementation
imports a package that internally uses `dart:io` (e.g., for clipboard access or keyboard
event detection) without guarding with `if (!kIsWeb)`, the app will throw
`UnsupportedError` on the web build.

**Why it matters:**
The app is deployed to both Android and web. The web build runs in a browser where
`dart:io` is entirely unavailable.

**Prevention:**
- Keep the search field implementation to `TextField`, `TextEditingController`,
  `FocusNode`, and Riverpod state — all of which are cross-platform
- Do not add `dart:io`, `Platform.isAndroid`, or clipboard packages without `kIsWeb` guards

**Note on `kIsWeb` reliability:**
A 2025 GitHub issue ([#170698](https://github.com/flutter/flutter/issues/170698)) reports
`kIsWeb` returning `true` on some Samsung Android 15 devices. This is a Flutter framework
edge case; the project's current `dart:io` guard pattern in `professional_message_bubble.dart`
uses `kIsWeb` and would be affected. For this layout fix, avoid `dart:io` entirely rather
than relying on `kIsWeb`.

**Detection:**
- `dart pub publish --dry-run` or `flutter build web` immediately surfaces `dart:io`
  import errors
- `flutter analyze` catches `Platform.` calls not guarded by `kIsWeb`

**Phase:** Fix phase — avoid the issue by keeping the implementation platform-agnostic

---

## Platform-Specific Rendering Differences

| Concern | Android (phone) | Android (tablet) | Web (browser) |
|---------|----------------|-----------------|---------------|
| AppBar toolbar height | 56 dp (Material 3) | 64 dp | 64 dp |
| `centerTitle` default | `false` | `false` | `false` |
| Soft keyboard on `TextField` focus | Pushes Scaffold body up via `resizeToAvoidBottomInset` | Same | Grey gap appears below keyboard on Chrome mobile (Flutter issue #182750) |
| `TextField` tap target | 48 dp minimum enforced by Android accessibility | Same | No OS enforcement; must be set manually |
| `IconButton` minimum size | 48×48 dp (Material 3 default) | Same | Same — verify with browser zoom |
| `Badge` on `AlertsBellButton` | Renders correctly | Renders correctly | Badge position may shift at <360 dp viewport |
| Scroll behavior of `AppBar` | Pinned by default | Pinned | Pinned; web has no system pull-to-refresh |

---

## Phase-Specific Warnings

| Fix Area | Likely Pitfall | Mitigation |
|----------|---------------|------------|
| Adding search `TextField` to AppBar | Put it in `actions` instead of `title` — triggers overflow | Always use `title: Expanded(child: TextField(...))` |
| `DotfilesScreen` has 3 `actions` icons | Adding search makes 4 items; overflow on 320 dp phones | Use `title` approach; keep `actions` as icon-only buttons |
| `CronScreen` has 2 `actions` icons | Adding search is safer, but still needs `Expanded` in `title` | Same as above |
| `TextEditingController` lifecycle | Initialising in `build()` causes dispose assertion | Init in `initState`, dispose in `dispose()` |
| Search filter state on back-navigation | Filter persists after editing a job; new jobs hidden | Add `suffixIcon` clear button |
| `DotfilesScreen` section grouping | Re-runs `_groupFilesByType()` on every keystroke | Filter before grouping; group the filtered list once |
| `dart:io` use in new helpers | `UnsupportedError` on web build | Use only `package:flutter/foundation.dart` APIs |
| `centerTitle` on iOS if ever targeted | Search field centered, actions crowded | Set `centerTitle: false` explicitly |

---

## Sources

- Flutter official AppBar documentation: [https://api.flutter.dev/flutter/material/AppBar-class.html](https://api.flutter.dev/flutter/material/AppBar-class.html)
- Flutter common errors (RenderFlex): [https://docs.flutter.dev/testing/common-errors](https://docs.flutter.dev/testing/common-errors)
- GitHub issue #68130 — Rendering problem with AppBar actions: [https://github.com/flutter/flutter/issues/68130](https://github.com/flutter/flutter/issues/68130)
- GitHub issue #132107 — SearchAnchor RenderFlex overflowed: [https://github.com/flutter/flutter/issues/132107](https://github.com/flutter/flutter/issues/132107)
- GitHub issue #116188 — SliverAppBar title overlaps actions when scrolled: [https://github.com/flutter/flutter/issues/116188](https://github.com/flutter/flutter/issues/116188)
- GitHub issue #134911 — AppBar title padding inconsistencies: [https://github.com/flutter/flutter/issues/134911](https://github.com/flutter/flutter/issues/134911)
- GitHub issue #130403 — leadingWidth has priority over centerTitle: [https://github.com/flutter/flutter/issues/130403](https://github.com/flutter/flutter/issues/130403)
- GitHub issue #146068 — SearchController disposed after use: [https://github.com/flutter/flutter/issues/146068](https://github.com/flutter/flutter/issues/146068)
- GitHub issue #170698 — kIsWeb returns true on Android 15: [https://github.com/flutter/flutter/issues/170698](https://github.com/flutter/flutter/issues/170698)
- GitHub issue #90101 — Autocomplete overlapping AppBar: [https://github.com/flutter/flutter/issues/90101](https://github.com/flutter/flutter/issues/90101)
- GitHub issue #182750 — Grey gap above keyboard on Chrome mobile: [https://github.com/flutter/flutter/issues/182750](https://github.com/flutter/flutter/issues/182750)
- KindaCode — Flutter add search field to AppBar (two approaches): [https://www.kindacode.com/article/flutter-add-a-search-field-to-the-app-bar](https://www.kindacode.com/article/flutter-add-a-search-field-to-the-app-bar)
- LeanCode — AppBar common mistakes: [https://leancode.co/glossary/appbar-in-flutter](https://leancode.co/glossary/appbar-in-flutter)

---

*Pitfalls audit: 2026-03-25*
