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
