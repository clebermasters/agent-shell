# AgentShell interactive canvas preview

Branch: `poc/open-intelligent-ui`.

This POC adapts CopilotKit/OpenIntelligentUI's MIT-licensed design system and
HTML/MCP integration pattern. It adds a real Android chat widget block and a
minimal MCP publishing adapter. It is not the complete CopilotKit React,
AG-UI, LangGraph, or TypeSafe/Jev stack.

## What works

- Selectable revenue chart/table with scenarios, validated bill splitting with tip presets,
  a release pipeline explorer, touch-controlled Three.js product inspection, and an offline
  Leaflet itinerary with a bundled schematic and optional live USGS basemap.
- A consistent mobile visual system, light/dark themes, reduced motion, labelled controls,
  44px touch targets, and layouts checked from 280px phones to tablets.
- Expand individual widgets or maximize entire chat messages. Controls survive closing,
  theme changes, gallery navigation, and Android activity recreation. Share a complete PNG.
- Native Android message bubbles containing an opaque-origin, script-enabled iframe.
- Widget follow-ups use an explicit widget ID, current binding ID, and server-validated
  conversation ownership. Widget JavaScript never receives the daemon token or terminal bridge.
- UI artifacts persist in the existing conversation-scoped SQLite event history.
- Cross-conversation and stale-after-resume actions are rejected.
- An offline sample gallery is available through the debug APK's **Interactive canvas** launcher.
- Normal and split chats use the shared interactive renderer; split widget actions retain
  their own socket and conversation binding.
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

Install it as `com.agentshell.debug`; open **Interactive canvas** to explore the gallery
without a backend. For live actions, paste the isolated runtime's WebSocket URL into
its connection field. On an emulator, replace the host with `10.0.2.2`. The normal
AgentShell launcher also remains available, with test-only connection defaults.

## Automated checks

```bash
python3 poc/open-intelligent-ui/test_mobile.py /path/to/runtime.json --output /tmp/ui-evidence
python3 poc/open-intelligent-ui/test_browser.py /path/to/runtime.json --output /tmp/ui-evidence
python3 poc/open-intelligent-ui/test_api.py /path/to/runtime.json --binary /tmp/agentshell-ui-poc-rust/debug/agentshell-backend --output /tmp/ui-evidence/api-results.json
```

Browser checks use installed Python Playwright and Google Chrome. The mobile suite covers
50 width/theme combinations, control sizing, names, text contrast, scenario arithmetic,
invalid-input handling, actual pointer drag, state restoration and unavailable-WebGL fallback.
All five showcases function without external network requests by default. The optional
live basemap retains the original map capability, with an automatic offline fallback.
`test_map_modes.py` fetches actual per-coordinate tiles and verifies saved mode and a simulated outage:

```bash
python3 poc/open-intelligent-ui/test_map_modes.py /path/to/runtime.json --output /tmp/ui-evidence
```

The protocol suite covers all five
widgets, numerical/visual changes, actual agent replies, iframe isolation, forged host
messages, cross-conversation rejection, and immediate resume rejection.

For Android, boot an isolated emulator and install the preview APK. The native script launches
the activity with its own endpoint and forwards each active WebView's DevTools socket:

```bash
python3 poc/open-intelligent-ui/test_native_mobile.py /path/to/separate-runtime.json --output /tmp/ui-evidence --serial emulator-5560
python3 poc/open-intelligent-ui/test_native_keyboard.py /path/to/separate-runtime.json --output /tmp/ui-evidence
```

Use a separate fresh runtime: the protocol browser test deliberately resumes conversation A.
The native suite uses real ADB taps and Android WebView DevTools. It verifies actual painted
pixels, scoped replies, fullscreen/close, theme switching, tab restoration, rotation, error
recovery, exported PNG pixels and whole-message maximization at phone/tablet widths.
The Android preview renders one active WebView at a time. See `ACCEPTANCE.md` and `evidence/`.
Evidence was collected on Android 15/API 35, WebView 124.0.6367.219. The tablet check resizes
the emulator to 1600×2560 at 240dpi; it is not a physical foldable hardware test.
Split chat's renderer/protocol compile and share the tested implementation; full split-screen
navigation and TalkBack service behavior have not been exercised by this harness.

## MCP adapter

Run `python3 poc/open-intelligent-ui/mcp_server.py` using stdio transport.
Set `AGENTSHELL_UI_URL`, `AGENTSHELL_UI_TOKEN`, and inherit `TMUX_PANE` from the
owning native agent. The adapter derives the agent PID/incarnation from its parent
chain. `AGENTSHELL_UI_PANE` and `AGENTSHELL_UI_AGENT_PID` are fixture overrides.
The `render_interactive_ui` tool accepts `{title, html}` containing a complete,
self-contained document. The Rust publisher resolves the native conversation itself;
the caller cannot choose a conversation by folder or submit a conversation ID.
The tool description includes mobile design and bridge guidance. `AGENT_GUIDANCE.md` is
an optional instruction snippet, suitable for project or global agent instructions.
No MCP configuration or instruction files are installed into real agent environments.

## Remaining work before production

This proves rendering, MCP delivery, persistence format, and conversation-safe actions.
Automatic visualization selection, model-generated output quality, incremental/AG-UI
streaming, and production sandbox/network/resource hardening remain separate work.
Control state is local view state, not server-synchronized application data. Maps are
authored offline schematics or optional [USGS basemap tiles](https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer),
with illustrative connections, not driving directions.
A real Codex/Claude MCP setup and provider smoke test are separate steps; no global
agent configuration was changed during this POC.

## Attribution

Design system: CopilotKit/OpenIntelligentUI revision
`f6e4388b26a64b9a0714943b08a1ce622b924eec`; MIT license retained in `upstream/` and assets.
Three.js 0.186.1 and Leaflet 1.9.4 are bundled locally, with their licenses.
