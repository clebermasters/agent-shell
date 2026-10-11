# Codex shared-daemon regression

Validated against installed Codex CLI/app-server **0.162.1**, using two real
Codex terminal clients connected to a separately launched local app-server,
a separate TMUX server and temporary AgentShell backend storage. Both clients
used the same temporary working directory. No prompts were sent to user sessions.

The original backend returned `required` for both clients even though their
native status lines showed different IDs. The candidate backend bound both exact
IDs before either conversation had a transcript, and returned empty initial chat
history so the first prompt could be sent.

Two small model smoke prompts produced `daemon-alpha-ok` and `daemon-beta-ok`
in their respective native assistant transcripts. Each send was confirmed and
neither transcript contained the other's reply. During paste, Codex hides its
shortcuts hint; the footer reader must retain identity in that layout so the
existing guarded Enter does not leave an unsent draft.

A native `/fork` changed the displayed thread ID in the same TUI process.
AgentShell issued a new binding, and a send using the old binding was rejected
before reaching the terminal. The old detached thread could not publish a widget.

The real app-server supplied `_meta.threadId` on MCP calls. The production MCP
adapter published a widget to the matching terminal without a TMUX environment;
publisher generation/transcript ownership were checked. A B-owned widget action
sent through A was rejected. Legacy fake-agent regression also passed, including
144 reconnects, simultaneous input, resume, rename, clear and backend restart.

Unit coverage includes IDs in drafts/output, truncated IDs, picker text, the
hidden-shortcuts draft layout and removal of a previously visible ID. A missing
footer ID cannot authorize a cached terminal-derived conversation.

For manual reproduction, launch a separate app-server on an owned Unix socket,
then two terminals with:

```bash
codex --remote unix:///PATH/TO/OWNED/server.sock \
  --config 'tui.status_line=["session-id"]' --cd /PATH/TO/SAME/TEST/FOLDER
```

`session-id` is the compatibility configuration name; the 0.162 picker calls it
**Thread ID**. Enable it first for full visibility. Watch each session with the
normal `watch-chat-log` protocol and compare the returned `conversationId` to
its native footer. Test a native fork and rejection of the prior binding. Use
only owned test sessions for prompts and cleanup.

## Existing MCP process upgrade regression

Recovery steps and the update requirement are in the
[Smart UI publication recovery guide](interactive-ui-troubleshooting.md).

On 2026-10-10, ClaudeAssist's MCP process had started before the native-thread
publisher was installed. Its loaded Python code still used the missing inherited
TMUX pane, even though the installed file had already been updated. Calling
`render_interactive_ui` through that thread's actual app-server MCP connection
reproduced `HTTPError: HTTP Error 409: Conflict`. Replaying its missing-identity
payload directly confirmed the backend reason: `The publisher has no terminal
identity`.

The installer now puts the adapter's content hash in `AGENTSHELL_UI_REVISION`.
After registering the changed configuration and requesting
`config/mcpServer/reload`, the same native connection published the original
15,538-byte “Intelligent UI playground” HTML successfully. A WebSocket history
read verified the exact HTML and widget ID under ClaudeAssist's native thread,
with no copy in `tesy` or `agentShell`. A detached-thread publication was rejected
with the backend's detailed reason. No model turn was started for these checks.

`python3 scripts/test_ui_mcp.py` covers native metadata routing without a TMUX
environment, HTTP errors reaching stdio callers, bounded/redacted error bodies,
invalid metadata, and revision-aware configuration. This check does not establish
Android rendering or live follow-up behavior; those need a device or emulator
test separately.

## Existing conversation in terminal scrollback

Later on 2026-10-10, Android lost ClaudeAssist's binding while the same native
conversation remained open. Codex's bottom hint had changed from
`? for shortcuts` to `enter/esc latest · ? shortcuts`. The original parser
rejected that layout even though the complete native UUID was visible in its
usual footer row below the input cursor.

The new `codex_identity_survives_the_scrollback_shortcuts_hint` test failed
against the original parser. After recognizing both hint forms, all **606 Rust
tests** passed. The same-directory integration test also passed, including
simultaneous input, resume, stale-send rejection, backend restart and 144
reconnections.

A separate candidate backend inspected the live ClaudeAssist terminal without
sending input or changing its scroll position. The deployed old backend returned
`required`; the candidate returned `bound`, identified the same native thread
and loaded its existing history. After deployment, the production backend
returned the existing history and exactly one copy of the previously published
widget. `tesy` stayed bound to its independent conversation and did not receive
that widget. The native scrollback hint remained visible throughout verification.

No Codex conversation was created or restarted for this recovery. No Android
code changed; phone rendering was not inspected because no ADB device was
connected. See the [existing chat-binding recovery guide](codex-chat-binding-troubleshooting.md)
for diagnosis and deployment steps.

## Sending a draft while terminal history is scrolled

The subsequent failed-send report exposed the missing lifecycle check. With an
empty composer, the scrollback hint contains `? shortcuts`; after bracketed
paste it becomes `esc latest`. The first scrollback correction restored history,
but pre-Enter validation still rejected that draft layout and left text unsent.

Identity detection now anchors the native status line to the penultimate
viewport row and is independent of keyboard-hint wording. It retains the full
UUID, below-cursor and blank-separator checks, and checks captured viewport
height against the TMUX header to reject mismatched resize frames. Neither the
pre-paste nor the pre-Enter binding check was removed.

The new hint-independent unit test failed against the prior parser. All **607
backend tests** passed after the correction. The opt-in
`scripts/test_codex_native_input.py` then exercised two actual Codex TUIs, one
owned app-server, isolated backend storage and isolated TMUX, with both
conversations in the same temporary folder:

- The prior production binary rejected Enter after paste with
  `This conversation is no longer linked` and `esc latest` visible. No native
  user submission was recorded for that failed send.
- The candidate submitted the same short prompt once and received its native
  assistant reply through the same chat WebSocket, without losing its binding.
- Multiline Unicode paste at **47×22** submitted once and produced the expected
  reply. Codex trims the final newline on submission; the test checks native
  composer normalization and preserves internal lines and Unicode.
- The second same-folder conversation independently sent and received its
  own reply. Neither native transcript received the other's input.

Fixture threads, processes and their temporary folder-trust entry were cleaned
up. The production TMUX pane inventory was unchanged. Native reply delivery and
stable WebSocket bindings were checked in addition to send receipts; loading
chat history alone is insufficient regression coverage for this bug.

The corrected release binary was installed and the backend restarted. A
production read-only WebSocket check then verified ClaudeAssist was `bound` to
its original native thread with `esc latest` still visible and its existing
draft intact. Its history and published widget were returned; `tesy` and
`agentShell` remained bound to their separate conversations. No input was sent
to the user's Codex terminal during verification.
