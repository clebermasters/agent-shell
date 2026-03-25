# AgentShell — Cron & Dotfile Screen Layout Fix

## What This Is

AgentShell is a Flutter app (Android + Web) that connects over WebSocket to a Rust backend. It manages TMUX sessions, terminal I/O, chat history, cron jobs, dotfiles, and an AI agent bridge. The Cron and Dotfile screens each have a search bar and a config/settings button, but these two UI elements are currently rendering on top of each other, making one of them inaccessible.

## Core Value

Both the search field and the config button must be simultaneously visible and tappable on every screen that uses them.

## Requirements

### Validated

- ✓ Flutter app connects to Rust backend over persistent WebSocket — existing
- ✓ Cron screen lists cron jobs with CRUD operations — existing
- ✓ Dotfile screen lists dotfiles with CRUD operations — existing
- ✓ Cron screen has a search/filter capability — existing
- ✓ Dotfile screen has a search/filter capability — existing
- ✓ Cron screen has a config/settings button — existing
- ✓ Dotfile screen has a config/settings button — existing

### Active

- [ ] Search field and config button on Cron screen are visually separated and both accessible
- [ ] Search field and config button on Dotfile screen are visually separated and both accessible
- [ ] Fix does not regress any existing Cron or Dotfile functionality
- [ ] Layout remains correct across Android phone, Android tablet, and web viewport sizes

### Out of Scope

- Redesigning the Cron or Dotfile screens beyond fixing the overlap — keep scope minimal
- Adding new features to either screen — separate concern
- Backend changes — purely a Flutter layout bug

## Context

- **Bug severity**: One button physically renders on top of the other, hiding it entirely — not just cosmetic cramping
- **Affected screens**: `CronScreen` and `DotfileScreen` (or their respective widgets)
- **Likely cause**: AppBar `actions` list or a Stack widget where both elements share the same layout slot
- **Codebase map available**: `.planning/codebase/` has ARCHITECTURE.md, STRUCTURE.md, STACK.md for reference
- **Feature layout**: `flutter/lib/features/cron/` and `flutter/lib/features/dotfiles/` follow `providers/ + screens/ + widgets/` structure

## Constraints

- **Tech Stack**: Flutter + Dart — must use Flutter layout primitives (Row, AppBar actions, SliverAppBar, etc.)
- **Compatibility**: Fix must work on Android (phone + tablet) and web (the two deployed targets)
- **Minimal change**: Prefer the smallest layout change that eliminates the overlap — avoid broad refactors

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Fix layout only, no feature scope | User confirmed this is a pure layout bug fix | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition:**
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone:**
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-25 after initialization*
