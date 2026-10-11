# Smart UI publication: HTTP 409 after an adapter update

## What happened

On 2026-10-10, publishing “Intelligent UI playground” from ClaudeAssist failed
with `HTTPError: HTTP Error 409: Conflict`.

The installed adapter already supported Codex's native conversation ID, but
ClaudeAssist's stdio MCP process had started before that update. Python kept
running its previously loaded code. That version ignored per-call
`_meta.threadId` and depended on an inherited TMUX pane that was unavailable in
the shared daemon. The backend's actual rejection was:

```text
The publisher has no terminal identity
```

The old adapter discarded that response body, leaving only the HTTP status.
`get_ui_design_system` still worked because it reads the local stylesheet and
does not contact the publisher or validate a conversation binding. The
calculator's HTML was not the cause of this incident.

## Permanent fix and its deployment requirement

The current adapter routes Codex publication using the native per-call thread
ID and preserves the backend's explanation on failure. The backend validates
the live terminal binding and publisher ownership before storing a widget.

The installer adds `AGENTSHELL_UI_REVISION`, the installed Python adapter's
SHA-256, to the MCP configuration. When the adapter changes, its configuration
changes too, allowing Codex's MCP reload to replace the old process. Reinstalling
unchanged bytes leaves the revision unchanged.

These changes persist on disk and survive restarts. They do not hot-patch a
Python process that is already running: **after updating an adapter, reload the
agent's MCP configuration**. The installer does not automatically restart live
agents. Restarting only the AgentShell backend or reconnecting Android does not
refresh an agent's stdio MCP process.

The backend installer now requests a native MCP configuration refresh from a
live local Codex daemon after registration. It reports a pending reload if that
request cannot complete. Other existing clients still need their normal MCP
reload. See the [portable installation reference](../deployment/README.md).

## Recovery steps

1. **Read the full error.** Updated adapters report
   `PublicationError: AgentShell publication rejected (HTTP 409): …`.
   A bare `HTTPError: HTTP Error 409: Conflict` after an upgrade is evidence to
   check for an older adapter; 409 alone does not prove that cause.
2. **Install and register the current adapter.** From the repository, run
   `./install.sh`. This installs the backend and MCP files and registers the
   adapter revision for supported agents. Manual registrations must also include
   the current content hash; see [MCP setup](mcp-setup.md).
3. **Refresh the native MCP connection.** For Codex's shared app-server, use
   `config/mcpServer/reload` on the daemon serving the affected conversation.
   It reloads configuration from disk and queues a refresh for loaded threads.
   This recovered ClaudeAssist without restarting the daemon or starting a model
   turn. A backend service restart does not perform this step.
4. **Check the exact conversation.** Keep it open in one identified TMUX
   terminal. In Codex, enable **Thread ID** through `/statusline`, move it first,
   and make the terminal wide enough to show the complete UUID. Android's chat
   binding must refer to that conversation.
5. **Retry once and verify delivery.** A successful result includes `widgetId`,
   `conversationKey` and `bindingId`. Confirm that exact widget appears in the
   intended chat history and not in another chat sharing the folder. For an
   uncertain or timed-out publication, check history before retrying to avoid
   creating a duplicate card.

### Codex app-server requests

These are native app-server RPC requests, not shell commands or requests to the
AgentShell `/api/chat/ui` endpoint. Send them over an initialized connection to
the affected Codex daemon. Its Unix transport carries WebSocket messages.
Discover the current socket and thread ID; do not reuse an old process or socket
reference from this incident.

```json
{"id":1,"method":"config/mcpServer/reload"}
```

After reload, inspect that conversation's connection:

```json
{"id":2,"method":"mcpServerStatus/list","params":{"threadId":"CURRENT_NATIVE_THREAD_UUID","serverName":"agentshell-ui"}}
```

Expect a connected server with no tool-discovery error. A connected status alone
does not prove correct publication identity; complete step 5 as well. See the
[Codex app-server API](https://learn.chatgpt.com/docs/app-server) for connection
initialization and RPC documentation.

### Check installed and configured revisions

```bash
sha256sum scripts/agent-ui-mcp.py /opt/agentshell/ui/agent-ui-mcp.py
codex mcp get agentshell-ui
```

The file hashes should match. The server entry's `AGENTSHELL_UI_REVISION` should
match the installed adapter hash. If a running MCP process still has the previous
revision, reload its native MCP connection before retrying. A future agent can
inspect `/proc/<MCP_PID>/environ` for that one revision value without printing
the whole environment or private runtime configuration.

## Other rejection reasons

If Android shows **Waiting for this terminal's conversation to be identified**
for an existing chat, use the separate
[Codex chat-binding guide](codex-chat-binding-troubleshooting.md). The native
scrollback footer layout can affect binding independently of the MCP revision.

The endpoint currently uses 409 for multiple failures. Follow the detailed
reason instead of treating every rejection as this upgrade problem.

| Detailed reason | What to check |
| --- | --- |
| `The publisher has no terminal identity` | Adapter revision and MCP reload; native thread metadata for Codex, inherited TMUX pane for other supported agents. |
| `The Codex conversation must be open in exactly one identified terminal` | Full native footer ID, the conversation being open, and duplicate terminals attached to the same conversation. Independent conversations in one folder are supported. |
| `Publisher does not own this conversation` | The MCP process belongs to the Codex daemon actually serving this thread. |
| `Publisher is not this pane's current agent` | The inherited pane and current foreground agent for the pane-based publisher. |
| `The terminal switched conversations. Reconnect the chat` | Refresh Android's binding after a resume or fork, then retry for the current conversation. |
| Storage or other errors | Investigate the returned detail and backend state; an MCP reload does not resolve every 409. |

Do not guess a conversation by folder, latest activity or title, or bypass the
ownership checks. Those checks prevent a widget from reaching the wrong chat.

## Verified recovery

The failure was reproduced through ClaudeAssist's actual native MCP connection.
After revision-aware registration and native MCP reload, the same connection
published the original 15,538-byte HTML successfully. WebSocket history found
the exact HTML once in ClaudeAssist and no copy in `tesy` or `agentShell`.
Detached-thread publication still failed with a detailed reason. No model turn
was started for these checks.

Seven adapter/installer regression tests passed:

```bash
python3 scripts/test_ui_mcp.py
```

See [Codex daemon validation](codex-daemon-validation.md#existing-mcp-process-upgrade-regression)
for the regression record. Publication and chat-history delivery were verified;
Android rendering and live button follow-up were not retested for this incident.
