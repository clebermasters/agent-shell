# Requirements: AgentShell — Cron & Dotfile FAB Overlap Fix

**Defined:** 2026-03-25
**Core Value:** Both the search FAB and the add FAB must be simultaneously visible and tappable on every screen that uses them.

## v1 Requirements

### Layout Fix

- [ ] **FIX-01**: User can tap the add (+) FAB on the Cron tab without it being obscured by the search/command FAB
- [ ] **FIX-02**: User can tap the add (+) FAB on the Dotfiles tab without it being obscured by the search/command FAB
- [ ] **FIX-03**: The search/command FAB remains functional and visible on the Sessions tab after the fix
- [ ] **FIX-04**: The search/command FAB remains functional and visible on the System tab after the fix

### Regression Protection

- [ ] **REG-01**: Existing Cron CRUD operations (add, edit, enable/disable, delete) continue to work after the fix
- [ ] **REG-02**: Existing Dotfile CRUD operations (add, edit, delete) continue to work after the fix
- [ ] **REG-03**: Layout is correct on a narrow Android phone viewport (≤360dp wide)
- [ ] **REG-04**: Layout is correct on a web viewport (desktop browser)

## v2 Requirements

### Search Enhancement (future, separate scope)

- **SRCH-01**: Cron screen has a persistent inline search/filter field in the AppBar
- **SRCH-02**: Dotfile screen has a persistent inline search/filter field in the AppBar
- **SRCH-03**: Search field and action icons are laid out correctly when search is added (Row/Expanded in AppBar.title)

## Out of Scope

| Feature | Reason |
|---------|--------|
| Adding search field to Cron/Dotfile AppBar | Separate feature; not part of the overlap bug fix |
| Redesigning Cron or Dotfile screen layouts | Scope creep; minimal change required |
| Backend changes | Pure Flutter layout fix |
| New screens or navigation flows | Out of bug fix scope |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| FIX-01 | Phase 1 | Pending |
| FIX-02 | Phase 1 | Pending |
| FIX-03 | Phase 1 | Pending |
| FIX-04 | Phase 1 | Pending |
| REG-01 | Phase 1 | Pending |
| REG-02 | Phase 1 | Pending |
| REG-03 | Phase 1 | Pending |
| REG-04 | Phase 1 | Pending |

**Coverage:**
- v1 requirements: 8 total
- Mapped to phases: 8
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-25*
*Last updated: 2026-03-25 after initial definition*
