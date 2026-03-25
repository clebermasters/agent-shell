---
phase: 01-fab-collision-fix
plan: 01
subsystem: ui
tags: [flutter, fab, indexed-stack, navigation, home-screen]

# Dependency graph
requires: []
provides:
  - "Conditional FAB suppression in HomeScreen: null returned for Cron (tab 1) and Dotfiles (tab 2) tabs"
  - "Command-palette FAB remains on Sessions (tab 0) and System (tab 3)"
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Ternary null branch in floatingActionButton property to suppress outer FAB on specific tabs"

key-files:
  created: []
  modified:
    - flutter/lib/features/home/screens/home_screen.dart

key-decisions:
  - "Only home_screen.dart modified — inner CronScreen and DotfilesScreen FABs left unchanged"
  - "Named constants _kTabCron and _kTabDotfiles used in null branch, not literal integers"

patterns-established:
  - "FAB suppression pattern: middle ternary branch returning null prevents outer Scaffold FAB from stacking over inner screen FAB in IndexedStack"

requirements-completed: [FIX-01, FIX-02, FIX-03, FIX-04, REG-01, REG-02, REG-03, REG-04]

# Metrics
duration: 1min
completed: 2026-03-25
---

# Phase 01 Plan 01: FAB Collision Fix Summary

**Null branch added to HomeScreen floatingActionButton ternary so Cron and Dotfiles tabs show only their inner add (+) FAB with no command-palette FAB stacking on top**

## Performance

- **Duration:** ~1 min
- **Started:** 2026-03-25T15:16:41Z
- **Completed:** 2026-03-25T15:17:27Z
- **Tasks:** 1 of 2 automated (Task 2 is human-verify checkpoint)
- **Files modified:** 1

## Accomplishments
- Added null branch `(_currentIndex == _kTabCron || _currentIndex == _kTabDotfiles) ? null` to outer Scaffold's floatingActionButton ternary
- Debug FAB branch (`widget.showDebug && _currentIndex == 0`) preserved as outermost condition
- Command-palette FAB (heroTag: 'command_palette_fab') preserved for Sessions and System tabs
- dart analyze: 0 errors (3 pre-existing warnings unrelated to this change)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add null FAB branch for Cron and Dotfiles tabs** - `9ceb1a3d` (fix)

**Plan metadata:** pending final commit after human verification (Task 2)

## Files Created/Modified
- `flutter/lib/features/home/screens/home_screen.dart` - Added null branch in floatingActionButton ternary for tabs 1 and 2

## Decisions Made
- Option A (change only home_screen.dart) confirmed correct — no changes needed in cron_screen.dart or dotfiles_screen.dart
- Named constants `_kTabCron`/`_kTabDotfiles` used per plan constraint (not literal `1`/`2`)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- `dart analyze` reported 3 pre-existing warnings (unused import, two unused elements) that existed before this change. These are out-of-scope and not caused by this edit. 0 errors confirmed.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Code change complete and committed. Awaiting human verification (Task 2 checkpoint) to confirm all 9 behavioral checks pass on a running device/browser.
- Once verified, phase 01 is fully complete.

---
*Phase: 01-fab-collision-fix*
*Completed: 2026-03-25*
