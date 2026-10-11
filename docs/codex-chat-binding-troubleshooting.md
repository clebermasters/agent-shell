# Existing Codex chat loses its conversation binding

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

The parser now reads the native status line from the penultimate viewport row,
independently of the hint text or whether the last row is blank. It still requires
a complete 36-character UUID below the input cursor, separated from input by a
blank row. Capture height must match the TMUX header so a concurrent resize
cannot shift the presumed footer. UUIDs in messages, drafts, menus and truncated
footer text cannot authorize a conversation. Missing identity still suspends the
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

   Replace the target with the affected session. Check that the full native
   thread UUID is visible below the input cursor. Treat terminal captures as
   private; their message text is unnecessary for diagnosing the footer.
3. If the footer ID is absent, use Codex `/statusline`, enable **Thread ID**
   (called Session ID in older versions), and put it first. If truncated, widen
   the terminal. If a menu covers the footer, close that menu normally.
4. If the ID is complete but Android still requires identity, record the exact
   keyboard hint and layout. Compare them with the layouts above. Install the
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

The new targeted test reproduces the native scrollback layout. It failed against
the original parser. It also checks that the shortened hint cannot authorize IDs
from drafts, assistant text or truncated footers. See
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
