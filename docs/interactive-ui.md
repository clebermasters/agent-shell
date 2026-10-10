# Interactive messages and appearance

AgentShell's native Android chat renders interactive HTML/CSS/JavaScript documents
published by a TMUX agent. Charts, tables, calculators, SVG diagrams, Three.js
previews and Leaflet maps share the mobile design system from the reference
[`poc/open-intelligent-ui`](https://github.com/clebermasters/agent-shell/tree/poc/open-intelligent-ui)
branch. This integrates its rendering and MCP pattern; it does not install the
full CopilotKit React, AG-UI or LangGraph stack.

## On Android

The sun/moon button in Home and Chat changes the app and widget theme together.
**Settings → Appearance** offers **System**, **Light** and **Dark**. The choice is
saved across app restarts. System follows changes to Android's appearance.

Interactive cards have expand and image-share controls. **Maximize message** opens
the entire response, including its text and interactive cards, at full screen
width. Closing the view, changing the theme or rotating the device preserves
opt-in widget control state. This is local view state, not server-synchronized
application data. Tablet layouts use the available width.

| Token | Light | Dark |
| --- | --- | --- |
| Background | `#FCFCFD` | `#171C27` |
| Panel | `#F1F4F8` | `#222A39` |
| Text | `#172133` | `#EFF3FA` |
| Muted text | `#57657B` | `#B1BED0` |
| Accent | `#5151D8` | `#B0A9FF` |
| Border | `#E3E8F0` | `#333E51` |

## Install the backend and agent tools

See [MCP setup](mcp-setup.md) for exact Codex, Claude and OpenCode registration
commands, configuration entries and troubleshooting.

`./install.sh` builds and installs the backend, installs the stdio MCP server and
its stylesheet in `/opt/agentshell/ui`, and registers **agentshell-ui** globally
for installed Codex, Claude and OpenCode CLIs. Unrelated agent configuration is
preserved and existing configuration files are backed up before editing.
The daemon URL and authentication token live in the owner's private
`~/.config/agentshell/interactive-ui.json` (mode `0600`). They are never passed
into the rendered document.

New agent processes load the MCP configuration. Existing running agents need
their usual restart/reload before they discover the new tools. The installer
does not restart live agents. OpenCode JSONC configuration is preserved and
requires adding the documented server entry manually if JSONC is in use.

For manual registration, use a configured private runtime file and:

```json
{
  "command": "/usr/bin/python3",
  "args": ["/opt/agentshell/ui/agent-ui-mcp.py"],
  "env": {
    "AGENTSHELL_UI_CONFIG": "/home/YOUR_USER/.config/agentshell/interactive-ui.json"
  }
}
```

This is a stdio server. Codex uses `codex mcp add`, Claude uses `mcpServers`,
and OpenCode uses a local `mcp` entry with a command array and `environment`.
The server inherits the agent's `TMUX_PANE` and finds the owning process through
its parent chain. It requires a known native conversation binding. Direct
ACP/app-server sessions without a TMUX pane do not publish through this adapter.

No project `AGENTS.md` change is required. Tool descriptions explain the feature.
Optional guidance in [AGENT_GUIDANCE.md](../poc/open-intelligent-ui/AGENT_GUIDANCE.md)
can encourage agents to use it when an interactive answer helps.

## Author a view

1. Call **get_ui_design_system** for CSS variables and reusable mobile controls.
2. Call **render_interactive_ui** with `{ "title": "…", "html": "…" }`, using a
   complete self-contained document. Use actual task data and label assumptions.
3. Keep controls labelled and at least 44px tall, support 280px screens, use system
   fonts and theme variables, and respect reduced motion.

Sizing and theme application are automatic. Local libraries are available at:

```text
https://appassets.androidplatform.net/assets/ui/vendor/three.js
https://appassets.androidplatform.net/assets/ui/vendor/leaflet.js
https://appassets.androidplatform.net/assets/ui/vendor/leaflet.css
https://appassets.androidplatform.net/assets/ui/runtime.js
```

The runtime helper implements opt-in input/custom-state saving and restoration.
Do not include sensitive values in control state. A contextual follow-up from
a user-clicked button uses:

```javascript
parent.postMessage({type: 'send-prompt', text: 'Analyze the selected scenario'}, '*');
```

The app supplies the current widget and binding IDs. HTML cannot select a
different conversation. Generated views remain agent-authored code: maintain a
text explanation and validate calculations and input ranges.

## Boundaries and recovery

- Publication uses authenticated `POST /api/chat/ui`, verifies the pane's current
  agent PID/start time and native conversation identity, and persists the card in
  conversation-scoped SQLite history.
- Follow-ups require the current binding lease and a widget owned by that
  conversation. Cross-conversation and stale-after-resume actions are rejected.
- Documents run in an opaque-origin iframe with scripts enabled. They cannot
  access the host document, native bridge, files, storage, forms, popups or
  arbitrary network resources. The native listener accepts only the trusted
  local main-frame origin. A content policy is prepended before generated HTML.
- Scripts, CSS and libraries are local. The sole remote exception is HTTPS GET
  for numeric USGS map-tile paths; map views should retain an offline fallback.
- Title/document/action/state sizes are limited. An unready or failed view shows
  **Reopen view**. Unsupported WebView versions show an update prompt. An infinite
  agent-authored JavaScript loop is not governed by a per-widget CPU quota.
- The gallery and test launcher are debug-only. The release APK contains the
  shared renderer and local libraries, and disables WebView debugging.

## Validation

The isolated harness in `poc/open-intelligent-ui/` exercises real MCP publication,
two independent fake agents sharing one folder, browser interaction, conversation
ownership, stale bindings, mobile layouts, maps and Android WebView behavior.
`test_native_mobile.py --fullscreen-only` verifies the final form action remains
above three-button Android navigation and a physical tap reaches the scoped agent.
`test_host_security.py` checks policy placement, blocked external resources,
host isolation and explicit-click follow-ups. `test_app_theme.py` tests the regular
app's theme controls, exact colors, process-restart persistence and System mode.
Use only an isolated emulator for these scripts; they change emulator preferences.

The reference branch retains the original POC. Production uses this same
renderer in `src/main/assets/ui` and does not require a POC daemon flag.

The release manifest is not debuggable. Validation uses an Android 15 `userdebug`
emulator, whose WebView exposes DevTools independently of the app's debug setting;
this engineering-image behavior is described in [Chromium's documentation](https://github.com/chromium/chromium/blob/main/android_webview/docs/net-debugging.md).
