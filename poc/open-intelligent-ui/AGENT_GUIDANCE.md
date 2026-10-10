# Agent guidance for the mobile canvas

This is an optional instruction snippet for an agent that has the
`render_interactive_ui` MCP tool configured. It is not automatically installed
in any global configuration or working folder.

Use an interactive view when it makes the answer materially easier to explore:
selecting chart values, comparing scenarios, calculating a result, navigating
a process, inspecting a 3D object or exploring locations. Keep a short text
explanation in the conversation. Do not generate a widget for a single fact.

Call `render_interactive_ui` with a concise title and a complete HTML document.
Use real task data; label illustrative data and assumptions explicitly.
Design for 280px first, with readable typography, no horizontal page overflow,
44px controls, labelled inputs, visible focus, validation, both color schemes,
and reduced motion. Keep network-independent functionality where possible.
Do not include credentials, account data unrelated to the question, tracking,
remote scripts, or arbitrary links. The iframe has no terminal bridge.

The host automatically sizes complete documents, expands widgets or whole
messages, and shares rendered images. It cannot make an unusable document
responsive. Use the sample widgets in `widgets/` as a design and bridge reference.

For contextual questions:

```javascript
parent.postMessage({type: 'send-prompt', text: 'Analyze this selected scenario…'}, '*');
```

The app validates the widget ID and current conversation binding before sending.
The HTML cannot choose another conversation or bypass that validation.

Control state is opt-in, size-limited and scoped to the widget. Post
`{type:'widget-state', state:{inputs,custom}}` after user edits. Listen for
`widget-context` from `parent` to restore `state` and apply `theme`; listen for
`widget-active` to stop work when hidden. Do not store sensitive information in
control state. See `widgets/runtime.js` for a working implementation.

MCP registration is required for an agent to call the publisher. This guidance
can live in a project instruction file or globally; it need not be copied into
every working folder. The offline showcase works without MCP configuration.
