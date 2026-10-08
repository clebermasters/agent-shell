Codex replies missing from Android chat — diagnosed 2026-09-10, deployed 2026-09-11

The confirmed failure is in AgentShell's backend Codex log parser. Recent Codex
rollouts store ordinary replies in `event_msg` records whose payload is
`item_completed` with an `AgentMessage` item. User messages use `UserMessage`.
The original parser recognizes the older `agent_message` and `user_message`
events, but ignores these completed items. It still recognizes tool calls in
`response_item` records, which explains the visible tools and missing replies.

Evidence collected from this machine:

- Before deployment, the backend's WebSocket history for `finkrebs:0` returned 45 assistant
  messages containing 45 tool-call blocks and zero assistant text blocks.
- Its Codex rollout, starting 2026-09-04 at 21:53:48, contains 26 `AgentMessage`
  events and nine `UserMessage` events. It was created by Codex 0.153.2.
- Current Codex 0.154.0 logs use the same completion format and have no legacy
  `agent_message`/`user_message` events in the inspected sessions.
- Android's `ChatViewModel.parseMessage()` already accepts normalized `text`
  blocks, and `MessageBubble` renders them. The missing text is absent from the
  WebSocket payload before it reaches Android.

Installation checks:

- Shell Codex, npm package, and native package match: 0.154.0.
- The running backend resolves Codex to the same npm executable.
- Node is 24.15.0; the installed package declares Node >=16.
- An existing `finkrebs` process still runs 0.153.2 from an executable replaced
  during an update. Both that version's inspected logs and 0.154.0 need this fix.
- The backend service uses `/opt/agentshell/backend/agentshell-backend`.
  Before deployment, that binary's file modification date was 2026-04-22.
- Separately, `scripts/agentshell-codex` uses the unsupported top-level flag
  `codex --json`; a help-only invocation exits 2 with `unexpected argument '--json'`.
  The inspected sessions use native Codex rollouts, so this wrapper is not the
  cause of the reproduced missing-message failure.

The fix is in `.worktrees/codex-chat-compat`, based on commit `51b484f8`.
It maps completed `UserMessage`/`AgentMessage` text into the existing Android
chat protocol, preserving timestamps, multiline text, Unicode, and older events.
Raw `response_item/message` records remain ignored because they duplicate
conversation events and also contain injected instructions.

Changed files:

- [Codex parser](.worktrees/codex-chat-compat/backend-rust/src/chat_log/codex_parser.rs)
- [History/live-update regression test](.worktrees/codex-chat-compat/backend-rust/src/chat_log/watcher.rs)
- [Portable patch](codex-chat-compat.patch)

Validation:

- Before the fix: three new compatibility tests failed, reproducing missing user
  messages, missing replies, and an empty modern conversation transcript.
- After the fix: `cargo test --locked chat_log::` passed all 142 chat-log tests.
- `cargo test --locked` passed all 549 backend tests.
- Real-log replay preserved conversation text exactly once and in order across
  Codex 0.154.0, 0.153.2, 0.148.0-alpha.9, and 0.146.0. The affected 0.153.2 log
  yielded all 26 assistant replies and nine user text messages.
- Regression coverage includes commentary/final replies, old-format messages,
  malformed records, history reload, and newly appended live messages.

The primary checkout already had the entire tracked `backend-rust` directory
deleted before this investigation. That existing state is preserved; the patched
source remains in the isolated checkout and has now been built and deployed.
An Android rebuild is not needed for this protocol-compatible parser change.
No Android device was attached to ADB, so on-device UI verification remains open.

Deployment completed on 2026-09-11 at approximately 19:12 UTC:

- Built with `cargo build --release --locked` in the isolated backend checkout.
- Backed up the previous executable, installed the new binary at
  `/opt/agentshell/backend/agentshell-backend`, and restarted using `start.sh restart`.
- The service is active with PID `3353282` and zero automatic restarts at verification.
- The installed and running executable match the release build SHA-256:
  `ff48e0f9abe3dfdc263583ba6dec87c8e78b6c0f2e420e10d47839ab0f84a592`.
- The systemd service configuration is unchanged. All five TMUX sessions were
  preserved, with the same session IDs, names, and window counts.
- Authenticated HTTP API requests on port 4010 return 200.
- Live WebSocket history for `finkrebs:0` now includes 22 assistant text blocks
  alongside its 45 tool-call blocks. These are all 22 replies after the existing
  chat-clear marker; four older replies remain cleared. `agentShell:0` also
  returns assistant text.
- A newly emitted assistant reply was received as a live `chat-event` after
  history loading, confirming that new replies work as well as saved history.
- Optional local HTTPS on port 4443 is inactive because the configured
  `/opt/agentshell/certs/cert.pem` and `key.pem` files are absent. HTTP/WebSocket
  verification passed on port 4010; no certificate configuration was changed.

Previous executable retained for rollback:
`/opt/agentshell/backend/agentshell-backend.bak-20260911T191247048691Z`.
