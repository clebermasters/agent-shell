# OpenIntelligentUI / AgentShell POC

Branch: `poc/open-intelligent-ui`.

This POC adapts CopilotKit/OpenIntelligentUI's MIT-licensed design system and
HTML/MCP integration pattern. It adds a real Android chat widget block and a
minimal MCP publishing adapter. It is not the complete CopilotKit React,
AG-UI, LangGraph, or TypeSafe/Jev stack.

## What works

- Interactive chart/table, calculator, SVG diagram, Three.js 3D scene, and Leaflet map.
- Native Android message bubbles containing an opaque-origin, script-enabled iframe.
- Widget follow-ups use an explicit widget ID, current binding ID, and server-validated
  conversation ownership. Widget JavaScript never receives the daemon token or terminal bridge.
- UI artifacts persist in the existing conversation-scoped SQLite event history.
- Cross-conversation and stale-after-resume actions are rejected.
- An offline sample gallery is available through the debug APK's **Interactive UI POC** launcher.
- Optional live demo connects to fake agents in a separate TMUX server and temporary backend.

## Isolation

The publisher and action API require `AGENTSHELL_UI_POC=1`, plus normal daemon authentication.
The Android renderer is restricted to debuggable builds. Test activities and widget assets
live in `src/debug`; release builds do not include the gallery or its libraries.
Build with `-PuiPoc=true` to replace embedded server/API credentials with test-only defaults.
The normal `.env` is never modified. No production service, agent configuration, hooks,
provider keys, or real agent prompts are used by the POC harness.

## Build and run

From the repository root:

```bash
cd poc/open-intelligent-ui
npm ci
npm run build
cd ../../backend-rust
CARGO_TARGET_DIR=/tmp/agentshell-ui-poc-rust cargo build --locked
cd ..
python3 poc/open-intelligent-ui/serve.py --binary /tmp/agentshell-ui-poc-rust/debug/agentshell-backend
```

The launcher prints a private `runtime.json` path. Leave it running during live tests.
It seeds two fake Codex-like agents (`ui-A`, `ui-B`) sharing the same folder, and
publishes the widgets through actual MCP `initialize`, `tools/list`, and `tools/call` messages.
Ctrl+C stops the isolated backend and TMUX server. Use a fresh directory for each run.

Build the credential-isolated debug APK:

```bash
cd android-native
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest -PuiPoc=true
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug -PuiPoc=true
```

Install it as `com.agentshell.debug`; open **Interactive UI POC** to explore the gallery
without a backend. For live actions, paste the isolated runtime's WebSocket URL into
its connection field. On an emulator, replace the host with `10.0.2.2`. The normal
AgentShell launcher also remains available, with test-only connection defaults.

## Automated checks

```bash
python3 poc/open-intelligent-ui/test_browser.py /path/to/runtime.json --output /tmp/ui-evidence
python3 poc/open-intelligent-ui/test_api.py /path/to/runtime.json --binary /tmp/agentshell-ui-poc-rust/debug/agentshell-backend --output /tmp/ui-evidence/api-results.json
```

Browser checks use installed Python Playwright and Google Chrome. They cover all five
widgets, numerical/visual changes, actual agent replies, iframe isolation, forged host
messages, cross-conversation rejection, and immediate resume rejection.

For Android, launch `UiPocActivity` with an `endpoint` intent extra, enable its built-in
debug WebViews, and forward the app's `webview_devtools_remote_<pid>` socket to port 9223:

```bash
python3 poc/open-intelligent-ui/test_native.py /path/to/runtime.json --output /tmp/ui-evidence
```

The native test uses the real Android WebView DevTools protocol, not a browser mock.
See `evidence/summary.json`, the detailed test reports, and `evidence/native-chat.png`.
The attached evidence was collected on Android 15/API 35, WebView 124.0.6367.219.

## MCP adapter

Run `python3 poc/open-intelligent-ui/mcp_server.py` using stdio transport.
Set `AGENTSHELL_UI_URL`, `AGENTSHELL_UI_TOKEN`, and inherit `TMUX_PANE` from the
owning native agent. The adapter derives the agent PID/incarnation from its parent
chain. `AGENTSHELL_UI_PANE` and `AGENTSHELL_UI_AGENT_PID` are fixture overrides.
The `render_interactive_ui` tool accepts `{title, html}` containing a complete,
self-contained document. The Rust publisher resolves the native conversation itself;
the caller cannot choose a conversation by folder or submit a conversation ID.

## Remaining work before production

This proves rendering, MCP delivery, persistence format, and conversation-safe actions.
Automatic visualization selection, model-generated output quality, incremental/AG-UI
streaming, export/fullscreen UX, persistent widget control state, split-chat rendering,
and production CPU/network/lifecycle hardening are not implemented here. Maps need
network access for USGS tiles; their connections are illustrative, not driving routes.
A real Codex/Claude MCP setup and provider smoke test are separate steps; no global
agent configuration was changed during this POC.

## Attribution

Design system: CopilotKit/OpenIntelligentUI revision
`f6e4388b26a64b9a0714943b08a1ce622b924eec`; MIT license retained in `upstream/` and assets.
Three.js 0.186.1 and Leaflet 1.9.4 are bundled locally, with their licenses.
