# Roadmap: AgentShell — Cron & Dotfile FAB Overlap Fix

## Overview

A single-phase bug fix. The root cause is a dual FloatingActionButton collision inside `home_screen.dart`: because `HomeScreen` wraps all tabs in an `IndexedStack`, the outer command-palette FAB and the per-screen add FABs both render at the same `endFloat` position simultaneously. Suppressing the outer FAB when `_currentIndex` is `_kTabCron` or `_kTabDotfiles` resolves the collision with a minimal one-file ternary change. No architectural work, no new dependencies.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: FAB Collision Fix** - Suppress the outer HomeScreen command-palette FAB on Cron and Dotfiles tabs so each tab has exactly one tappable FAB

## Phase Details

### Phase 1: FAB Collision Fix
**Goal**: Both FABs are exclusively visible and tappable on their respective tabs — the add FAB on Cron and Dotfiles, the command-palette FAB on Sessions and System
**Depends on**: Nothing (first phase)
**Requirements**: FIX-01, FIX-02, FIX-03, FIX-04, REG-01, REG-02, REG-03, REG-04
**Success Criteria** (what must be TRUE):
  1. User can tap the add (+) FAB on the Cron tab and the action triggers (no invisible FAB on top blocking it)
  2. User can tap the add (+) FAB on the Dotfiles tab and the action triggers (no invisible FAB on top blocking it)
  3. User can tap the command-palette FAB (search icon) on the Sessions tab and the palette opens
  4. User can tap the command-palette FAB (search icon) on the System tab and the palette opens
  5. All existing Cron and Dotfile CRUD operations (add, edit, enable/disable, delete) complete without error, and the layout is correct on both a narrow Android phone (<=360dp) and a desktop web viewport
**Plans:** 1 plan

Plans:
- [ ] 01-01-PLAN.md — Suppress outer FAB on Cron/Dotfiles tabs and verify on device

**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. FAB Collision Fix | 0/1 | Not started | - |
