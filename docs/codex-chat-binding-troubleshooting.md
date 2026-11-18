# Existing Codex chat loses its conversation binding

## Incident KrebsBot November 15 2026

The Android chat showed existing assistant messages together with
**Waiting for this terminal's conversation to be identified automatically** and
**Show Session ID in Codex /statusline to identify this conversation**. The
conversation and transcript still existed. A read-only WebSocket probe reproduced
`required` with no history for KrebsBot while the other eight active chats bound
normally.

KrebsBot's native Codex footer at **122×51** had its input cursor on row 48, a
blank separator on row 49, and the complete native Session ID on the last row
50. The keyboard-hint row was absent. The previous parser assumed the native
status block always ended on the penultimate row, so it inspected the blank
separator and invalidated the terminal-derived binding. Android retained cached
messages while the backend suspended its unverified transcript watcher.

The parser now keeps its established penultimate-row path and also recognizes a
native status block ending on the last viewport row. The new last-row path
requires native indentation, the same blank separator and cursor relationship,
and a leading full ID or an explicitly ellipsized, globally corroborated prefix.
It does not search messages, drafts, menus or scrollback for a UUID. Existing
capture-height, process-generation, prefix-collision and pre-send lease checks
remain unchanged. Missing native identity still suspends a previously cached
terminal-derived binding; the fix does not reuse a potentially stale ID.

Five regression tests cover the captured geometry, wrapped and single-line
status with or without hints, prefix forms, rejection of bare UUIDs and IDs in
drafts or menus, and a same-process conversation change replacing the old lease.
The captured-geometry test failed against the previous parser and passed after
the correction. All 30 binding tests passed, including the existing guards.

This correction requires only a backend deployment. No Android APK update,
manual registration, terminal resize, conversation recreation or production
terminal input is needed. Private before/after verification evidence is kept in
`~/.local/state/chat-identity-recovery-20261115/`.


Verification passed after deployment: **715 backend tests** passed, with one
existing test ignored. All nine active chats returned `bound` and their existing
histories with their original conversation IDs and binding leases. KrebsBot
recovered its original lease and all 52 messages without input, manual
registration, or changing its 122×51 viewport. Every original TMUX pane process
remained alive.

## Incident: synergy split view, 2026-11-13

### Follow-up: sending from a 47×16 terminal

The first correction below restored wrapped-footer history, but a later send in
**agentShell** still returned `This conversation is no longer linked`. In a
16-row terminal, the expanded multiline composer can hide the entire status
block after paste. The pre-Enter identity check correctly refused to submit;
reading more footer rows cannot recover an ID that is no longer rendered.

Bound Codex sends now temporarily reserve at least 32 terminal rows, wait for
the native identity to redraw, and retain both pre-paste and pre-Enter checks.
They restore the original window size and local/inherited sizing policy on
success, failure, and cancellation. A newer explicit user resize takes
precedence. Existing drafts are never replayed or discarded automatically.

The real-client regression includes a multiline Unicode send at **47×16**,
checks exactly-once delivery and the live reply, and verifies restoration of
the original viewport and sizing policy. The initially deployed wrapped-footer
binary reproduced this precise send error in that fixture.

The follow-up build passed **699 tests**, with one existing test ignored, and
the real-client regression passed with the short composer send and restoration
checks included. It was deployed, and read-only production checks confirmed
**agentShell** and **synergy** remained bound to their original conversations
and leases, returned their histories, and retained all original TMUX processes.

### Original wrapped-footer failure

The terminal continued updating while chat showed **Status unavailable** and
**Waiting for this terminal's conversation to be identified automatically**.
The full native conversation ID was visible. At **41×37**, Codex 0.169.0 wrapped
the configured status line over three rows, followed by a goal/hint row:

```text
» Ask Codex to do anything

  <full native UUID> ·
  GPT-6.1-Sol ultra · ~ · Context 59%
  left · Main [default]
  ← for agents       Goal achieved (20m)
```

The parser still expected the ID on the penultimate row, which now contained
`left · Main [default]`. Terminal output uses the PTY independently, so it kept
working while the unverified chat binding stopped its transcript watcher.

Identity detection now reads the contiguous status block ending on that row,
stopping at its blank separator below the composer cursor. Wrapped rows must
retain Codex's native indentation. It reads the leading ID of that block;
it does not search messages, drafts, menus, or scrollback for IDs. The final
hint remains irrelevant, and pane/process, capture-height, UUID-prefix collision,
and pre-send lease checks remain in place.

This requires a backend deployment and reconnect, with no Android APK update,
conversation recreation, terminal resize, or manual conversation registration.
The fix is independent of the laptop's lid/suspend settings.

```bash
cd backend-rust
cargo test --release --locked
cargo build --release --locked
cd ..
python3 scripts/test_codex_wrapped_footer.py \
  --before-binary /PATH/TO/PRE_FIX_BACKEND \
  --binary backend-rust/target/release/agentshell-backend \
  --evidence-dir /PATH/TO/PRIVATE_TEST_EVIDENCE
```

The opt-in real-Codex fixture configures multiple native status fields, reproduces
the old failure at 41 columns, and checks automatic recovery, live replies,
stable leases after restart and goal/resize transitions, separate same-folder
conversations, and rejection of stale sends after `/new`. It uses existing Codex
login for small fixture-only model calls and removes its own threads afterward.
Production terminals receive no input or resize commands.

The isolated backend build passed **696 tests**, with one existing test ignored.
The real-client before/after fixture passed every check above. After deployment,
read-only production WebSocket checks returned `bound` for **synergy** and
**agentShell**, preserving their original conversation IDs and binding leases.
synergy returned its original history (157 messages total), including the latest
Mac/PC verification reply that was missing from the screenshot's chat pane.
Every production TMUX pane retained its original process. The deployment was
built from the committed backend plus this parser fix, excluding unrelated
unfinished workspace changes.

## Incident: AssistClaude, 2026-10-11

The same existing-session error recurred at **47×43**, while `tesy` used the same
working directory and remained linked. Read-only inspection confirmed that the
backend was running the previously corrected binary. The conversation and its
transcript still existed. The failed pane's native footer looked like this:

```text
<UUID prefix>… Goal achieved (2h 41m)
```

Codex's goal banner takes space from the configured status line, even with Thread
ID first. It also uses whitespace instead of the normal middle-dot separator.
A paused goal hides more of the ID:

```text
<shorter UUID prefix>… Goal paused (/goal resume)
```

Earlier fixes handled changing keyboard hints but still required a full UUID and
the usual separator. The backend therefore suspended the binding when the goal
completed or its narrow footer changed. Widening the pane or manually registering
an ID could temporarily hide this bug.

The backend now recognizes the leading native UUID independently of other status
fields. Explicitly ellipsized prefixes use **read-only global native metadata**:

- Normal prefixes require the first three UUID groups and exactly one matching
  full ID in `~/.codex/state_5.sqlite`.
- Goal-related prefixes can be shorter (at least eight characters), but must
  match exactly one full thread ID in `~/.codex/goals_1.sqlite`. All matching IDs
  in the global thread index must agree with it. This includes goals whose first
  transcript has not been created yet.
- Other directories, archived threads, and other goals participate in collision
  checks. Folder, recency, goal status, and cached bindings never choose between
  matching IDs. Zero or multiple matches suspend sending.
- Cursor, blank separator, footer location, pane/process generation, and capture
  height guards remain. IDs in drafts, messages, menus, or unmarked partial IDs
  cannot authorize a conversation.
- The same exact native thread retains its binding lease across reconnects and
  backend restarts. `/new` or `/resume` with a different ID changes the lease;
  an old lease cannot submit text there.

The goal database is private runtime state created by Codex automatically, not an
additional server, MCP dependency, or file to copy into the project. Identity
does not depend on goal status or elapsed-time wording. The
[official app-server goal API](https://developers.openai.com/codex/app-server#manage-a-thread-goal)
describes its persisted, thread-scoped goal contract.

This correction requires a backend deployment. Existing terminals keep running;
there is no need to recreate them, change directories, manually bind them, or
install a different Android APK. The watch resolves the same conversation
automatically after the corrected backend starts.

### Repeat the real-client regression

```bash
cd backend-rust
cargo test --locked
cargo build --release --locked
cd ..
python3 scripts/test_codex_goal_binding.py \
  --before-binary /PATH/TO/PRE_FIX_BACKEND \
  --binary backend-rust/target/release/agentshell-backend \
  --evidence-dir /PATH/TO/PRIVATE_TEST_EVIDENCE
```

The opt-in fixture creates two real Codex TUIs in the same owned folder, with
isolated TMUX and backend storage. It reproduces both existing chats losing their
bindings against the old binary, then checks automatic recovery, stable leases,
backend restart, exactly-once sends to separate conversations, goal/resize
transitions, and rejection of stale sends after `/new` in the same TUI process.
It uses existing Codex login for small model calls and disables global MCP tools
in the fixture. Cleanup removes only its own conversations and trust entry.
`--before-binary` is optional on later runs.

### Verification and deployment

On 2026-10-11, all **613 backend tests** passed. The real-client before/after
fixture passed automatic recovery without terminal input, retained leases after
backend restart, exactly-once sends isolated by conversation, paused/completed
goal and resize transitions, and stale-send rejection after `/new`.

The corrected release binary was installed using the normal installer, with
existing dependencies retained, and systemd restarted successfully. Read-only
production WebSocket checks confirmed that **AssistClaude** and **tesy** bound
to their own exact native IDs, returned their existing histories (38 and 10
messages), and reported **idle**. Both retained their existing pane/process and
47×43 dimensions, and their native footers were unchanged. No session recreation,
manual binding registration, or widening was used for recovery.

## Incident: ClaudeAssist, 2026-10-10

Android showed an empty chat, **Unknown / Live status is unavailable**, and:

```text
Waiting for this terminal's conversation to be identified automatically
Show Session ID in Codex /statusline to identify this conversation
```

This was an existing conversation, not a failed new-session creation or the
[MCP publication upgrade issue](interactive-ui-troubleshooting.md). The native
conversation UUID and transcript were still present. The backend had stopped
recognizing the footer, so Android waited for a valid binding instead of showing
history for an unverified conversation.

## Cause and fix

Codex 0.162.1 changes its bottom keyboard hint while terminal history is scrolled:

| Terminal state | Native keyboard hint |
| --- | --- |
| Normal input | `? for shortcuts` |
| Browsing terminal history | `enter/esc latest · ? shortcuts` |
| Draft present while browsing history | `esc latest` |

The initial shared-daemon binding fix recognized the normal hint and the hidden
hint during a draft, but missed the scrollback form. The native ID was fully
visible below the input cursor in the correct footer row. Nevertheless, the
parser returned no identity and invalidated its cached terminal-derived binding.

The first correction recognized both shortcuts forms and restored chat history,
but a later send exposed another state: after bracketed paste, the scrolled
composer changes the hint to `esc latest`. Binding validation failed between
paste and Enter, leaving the message in Codex's draft instead of submitting it.
Testing history loading alone did not cover that transition.

The 2026-10-10 correction read the native status line from the penultimate viewport
row independently of the hint text or whether the last row was blank. It required
a complete 36-character UUID below the input cursor, separated by a blank row.
The corroborated-prefix rules added on 2026-10-11 supersede that full-ID-only rule.
Capture height must match the TMUX header so a concurrent resize cannot shift the
presumed footer. UUIDs in messages, drafts and menus cannot authorize a
conversation. Missing or ambiguous identity still suspends the
binding rather than falling back to a different chat in the same folder.

The pre-paste and pre-Enter identity checks remain in place. Removing those checks
would allow a resume or fork to send a message to the wrong conversation.

This is a backend code correction, so it needs a backend deployment/restart;
it does not require an Android APK, MCP reload or a new conversation. Future
Codex versions may introduce other layouts; diagnose their actual footer before
changing the parser.

## Recovery and diagnosis

1. Keep the existing Codex conversation. Do not create another session to repair
   this screen, clear its history, or select a conversation by working directory.
2. Inspect the live footer and cursor without sending input:

   ```bash
   tmux display-message -p -t ClaudeAssist '#{pane_id} #{pid} #{pane_pid} #{cursor_y} #{pane_width} #{pane_height}'
   tmux capture-pane -p -t ClaudeAssist
   ```

   Replace the target with the affected session. Check that the native UUID or
   an explicitly ellipsized prefix is below the input cursor. Treat captures as
   private; their message text is unnecessary for diagnosing the footer.
3. If the footer ID is absent, use Codex `/statusline`, enable **Thread ID**
   (called Session ID in older versions), and put it first. Truncation should now
   recover automatically when native metadata has one matching full ID. Widening
   is a diagnostic fallback when the remaining prefix is ambiguous or too short.
   If a menu covers the footer, close that menu normally.
4. If a full ID or an unambiguous prefix is visible but Android still requires
   identity, record the footer, cursor and native metadata versions. Install the
   corrected backend with `./install.sh`, or use the project's backend deployment
   procedure when only the backend needs updating.
5. Reconnect the Android chat or press **Retry**. Verify its binding is `bound`
   and its `conversationId` matches the native footer. Confirm the old messages
   and any published widgets reappear under the same conversation.

If a failed send already pasted text into Codex, inspect the native composer
before repeating the message. The earlier failure could leave an unsent draft;
repeating a paste could append a second copy. Do not discard that draft or submit
it into a different conversation. A recovery test must exercise Enter delivery
as well as reopening chat history.

Returning Codex to the latest terminal output with its normal scrollback control
can temporarily restore the older parser's recognized layout. That is a
workaround, not the backend correction. The fix must keep the conversation bound
while the scrollback hint is visible.

## Regression checks

```bash
cd backend-rust
cargo test codex_identity_survives_the_scrollback_shortcuts_hint
cargo test codex_identity_does_not_depend_on_bottom_key_hints
cargo test
```

That targeted test reproduces the native scrollback layout. It failed against
the original parser. It also checks that the shortened hint cannot authorize IDs
from drafts or assistant text. Prefix recovery now has separate metadata and
collision tests. See
[Codex daemon validation](codex-daemon-validation.md) for live verification.

The opt-in real-client regression starts two real Codex TUIs against an owned
app-server, with isolated TMUX and backend storage. It uses existing Codex login
for small model calls in fixture conversations, disables the user's global MCP
servers in that fixture, and deletes only its own threads afterward. It can
compare an old binary against the candidate:

```bash
python3 scripts/test_codex_native_input.py \
  --before-binary /PATH/TO/PRE_FIX_BACKEND \
  --binary backend-rust/target/release/agentshell-backend \
  --evidence-dir /PATH/TO/PRIVATE_TEST_EVIDENCE
```

The fixture accepts Codex's folder-trust prompt only for its own newly created
directory and removes that directory's persisted trust entry during cleanup.
`--before-binary` is optional for future regression runs. The expected old failure
is text pasted but Enter rejected with `esc latest` visible. Candidate checks
must include actual native replies through the chat WebSocket with a stable
binding, exactly-once submission, multiline paste at 47×22, and isolation between
the two same-folder conversations.

The initial scrollback correction was deployed and verified against the same live terminal
with its scrollback hint still visible. Production returned the original
conversation history and its previously published widget; the independent
`tesy` conversation stayed separate. All 606 backend tests and the 144-reconnect
same-folder regression passed. ADB was unavailable, so this validates backend
delivery rather than a new visual inspection on the phone.

The later hint-independent correction passed all **607 backend tests** and the
real-client before/after test, including sending and receiving replies while
keeping the chat binding stable. This covers the post-paste failure that history
loading checks missed.
It was deployed and verified against ClaudeAssist's live `esc latest` draft
layout without submitting or discarding the user's existing draft.
