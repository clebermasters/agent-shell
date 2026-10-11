# Configure AgentShell Smart UI MCP

The **agentshell-ui** stdio server exposes two tools:

| Tool | Purpose |
| --- | --- |
| `get_ui_design_system` | Obtain the mobile stylesheet, theme variables and bridge guidance. |
| `render_interactive_ui` | Publish a complete `{title, html}` document to the calling agent's Android conversation. |

The native agent must run in a TMUX pane on the daemon's machine. AgentShell must
know that pane's native conversation identity. The adapter currently recognizes
**Codex, Claude Code and OpenCode** processes. A project named `kiro-bot` works
with those agents; Kiro CLI itself needs a separate native-identity adapter.
Direct ACP/app-server sessions without TMUX are outside this adapter's scope.

## Automatic installation

From the AgentShell repository, run:

```bash
./install.sh
```

The installer bootstraps missing backend/native-agent dependencies, builds/restarts the backend, installs
`/opt/agentshell/ui/agent-ui-mcp.py` and `mobile.css`, and registers the server
globally for installed Codex, Claude and OpenCode CLIs. It preserves unrelated
configuration and creates timestamped backups before editing existing files.
OpenCode JSONC files are merged automatically while retaining comments and
unrelated configuration. See the [portable installation reference](../deployment/README.md)
for dependency versions, source backups and credential-free templates.

The installer writes the daemon URL and authentication token to:

```text
~/.config/agentshell/interactive-ui.json
```

This private file has mode `0600`. The agent's MCP entry contains its path, not
the token. Keep it outside Git and never embed its contents in generated HTML.
For a manual setup, create the same private file with `url` and `token` fields
using the daemon's configured authentication token. Its default URL is
`http://127.0.0.1:4010`. The backend publisher endpoint is **`/api/chat/ui`**;
the MCP URL is the base URL, without `/api/chat/ui` or `/ws`.

Restart/reload the agent to discover the new server. Installing MCP configuration
does not change the tools of an already-running agent process. The backend
installer also requests a native MCP configuration refresh from a live local
Codex daemon, and reports when that refresh is pending.

The installer includes an `AGENTSHELL_UI_REVISION` content hash in the server's
environment. Updating the adapter changes that configuration value so a native
MCP reload replaces the old stdio process. Restarting only the AgentShell backend
does not update Python code already loaded by an agent's MCP process. Codex's
app-server `config/mcpServer/reload` refreshes its configuration and queues a
refresh for loaded threads; reconnecting/resuming the same conversation is also
an option when the agent client does not expose a reload control.

Publication failures include the backend's explanation after the HTTP status,
for example `HTTP 409: The publisher has no terminal identity`. The adapter caps
the response detail at 8 KiB and redacts its authentication token. A successful
`get_ui_design_system` call only reads the stylesheet; it does not test the
publisher's conversation identity or connectivity to the backend.

## Codex

Codex 0.162's shared app-server can own transcripts outside the terminal process.
The installer puts the native session/thread ID first in `tui.status_line`, preserving your other
footer items. AgentShell reads the full native UUID from the live footer below
Codex's input cursor; it never identifies a conversation by directory or title.
New conversations are usable before their first transcript is written.

For an already-running Codex terminal, use **/statusline**, enable **Thread ID** (called Session ID in older versions),
move it first and confirm. This updates the footer immediately without creating
another conversation. The full ID must fit in the terminal. If it is hidden or
truncated, a terminal-derived binding cannot authorize a send.

Codex's per-call MCP `_meta.threadId` routes widgets even when the shared server
has no TMUX environment or inherited an old one. The backend checks the current
terminal binding and the publisher's ownership of the transcript. Detached or
ambiguous conversations fail rather than publishing to another chat.


Register using the official CLI:

```bash
AGENTSHELL_UI_REVISION="$(sha256sum /opt/agentshell/ui/agent-ui-mcp.py | cut -d ' ' -f1)"
codex mcp add agentshell-ui \
  --env "AGENTSHELL_UI_CONFIG=$HOME/.config/agentshell/interactive-ui.json" \
  --env "AGENTSHELL_UI_REVISION=$AGENTSHELL_UI_REVISION" \
  -- /usr/bin/python3 /opt/agentshell/ui/agent-ui-mcp.py
codex mcp get agentshell-ui
```

This writes the user configuration in `~/.codex/config.toml`:

```toml
[mcp_servers.agentshell-ui]
command = "/usr/bin/python3"
args = ["/opt/agentshell/ui/agent-ui-mcp.py"]

[mcp_servers.agentshell-ui.env]
AGENTSHELL_UI_CONFIG = "/home/YOUR_USER/.config/agentshell/interactive-ui.json"
AGENTSHELL_UI_REVISION = "<SHA256_OF_INSTALLED_ADAPTER>"
```

For manual entries, replace the revision placeholder with the installed
adapter's hash and update it whenever that file changes. `./install.sh` computes
it automatically. Reload the native MCP connection after registering an update;
see the [409 recovery guide](interactive-ui-troubleshooting.md).

Codex's conversation-identity hook may require its normal one-time review in
`/hooks`. Review that hook when prompted; MCP registration does not bypass trust.

## Claude Code

Use user scope so the tools are available in every folder:

```bash
AGENTSHELL_UI_REVISION="$(sha256sum /opt/agentshell/ui/agent-ui-mcp.py | cut -d ' ' -f1)"
claude mcp add --scope user --transport stdio agentshell-ui \
  --env "AGENTSHELL_UI_CONFIG=$HOME/.config/agentshell/interactive-ui.json" \
  --env "AGENTSHELL_UI_REVISION=$AGENTSHELL_UI_REVISION" \
  -- /usr/bin/python3 /opt/agentshell/ui/agent-ui-mcp.py
claude mcp get agentshell-ui
```

The equivalent entry under `mcpServers` in `~/.claude.json` is:

```json
{
  "agentshell-ui": {
    "type": "stdio",
    "command": "/usr/bin/python3",
    "args": ["/opt/agentshell/ui/agent-ui-mcp.py"],
    "env": {
      "AGENTSHELL_UI_CONFIG": "/home/YOUR_USER/.config/agentshell/interactive-ui.json",
      "AGENTSHELL_UI_REVISION": "<SHA256_OF_INSTALLED_ADAPTER>"
    }
  }
}
```

If a launcher uses `--strict-mcp-config`, merge that entry into the supplied
file's `mcpServers` object as well; strict mode excludes user-scope servers.
The existing `kiro-bot/claude-minimax.sh` and `claude-zai.sh` wrappers invoke
Claude normally, so their default invocation uses the registered global server.

## OpenCode

Merge this entry into `mcp` in `~/.config/opencode/opencode.json` (or the JSONC
configuration used by your installation):

```json
{
  "agentshell-ui": {
    "type": "local",
    "command": ["/usr/bin/python3", "/opt/agentshell/ui/agent-ui-mcp.py"],
    "enabled": true,
    "environment": {
      "AGENTSHELL_UI_CONFIG": "/home/YOUR_USER/.config/agentshell/interactive-ui.json",
      "AGENTSHELL_UI_REVISION": "<SHA256_OF_INSTALLED_ADAPTER>"
    }
  }
}
```

Retain every other server and setting. Replace the revision placeholder with
the installed adapter's hash and update it after adapter upgrades. On another
machine, install the local daemon and server there. Claude and OpenCode inherit
the owning local agent's `TMUX_PANE`; Codex uses per-call native thread metadata.
Do not point a publisher at an unrelated remote pane by directory.

## Verify and use

1. Open a new supported agent in TMUX, then open that session's Android Chat.
2. Confirm both tools appear in the agent's MCP tool list.
3. Ask for a useful interactive chart or calculator using actual task data.
4. The agent calls `get_ui_design_system`, then `render_interactive_ui`. A successful
   tool result returns the widget ID and resolved conversation binding.
5. Expand the card or message, switch the app theme and try a contextual button.
   Follow-ups are validated against the current conversation.

`AGENTS.md` guidance is optional for discovery, but useful for encouraging good
decisions. `/home/cleber_rodrigues/kiro-bot/AGENTS.md` now contains **AgentShell
Smart UI** guidance. Keep a short text explanation alongside each interactive view.
See [interactive UI](interactive-ui.md) for libraries, state and sandbox boundaries.

## Troubleshooting

For a 409 after an adapter update, follow the
[publication recovery guide](interactive-ui-troubleshooting.md). It records the
ClaudeAssist incident, the permanent fix, native reload requests and delivery
checks. A backend restart alone does not refresh a running MCP adapter.
For an existing Android chat waiting for its conversation identity, follow the
[Codex chat-binding guide](codex-chat-binding-troubleshooting.md), including the
scrollback footer regression.

- **Tools absent:** restart/reload the agent; check its registered server entry,
  disabled-server settings and any strict MCP configuration supplied by a wrapper.
- **Runtime not configured / 401:** check the private config path, file ownership
  and token against the daemon. Do not paste the token into chat or instructions.
- **No owning native agent:** launch from Codex, Claude or OpenCode in TMUX.
  Running the adapter manually can list tools but cannot publish for an agent.
- **Native conversation ID unavailable:** confirm the identity hooks and binding
  are working; for Codex, review the hook in `/hooks` if prompted. Never resolve
  this by picking whichever conversation most recently used the same folder.
- **View cannot load:** use **Reopen view**, update Android System WebView if
  requested, and keep the document within 256 KiB with local scripts and assets.
- **Agent calls a tool but no view appears:** confirm you are viewing the exact
  session/conversation identified by the successful publication result.
