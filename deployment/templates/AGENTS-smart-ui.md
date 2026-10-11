## AgentShell Smart UI

When an interactive explanation helps, call `get_ui_design_system`, then
`render_interactive_ui` with a useful title and complete self-contained HTML.
Use the supplied mobile styles and light/dark theme variables, accurate labelled
data, 44px controls, and layouts that fit 280px screens. Keep a text explanation
with the widget. Calculators should validate inputs and handle empty/error cases.

Scripts and assets must be inline or use AgentShell's bundled libraries.
For an explicit user-clicked follow-up button, use:

```javascript
parent.postMessage({type: 'send-prompt', text: 'Analyze this selected scenario'}, '*');
```

Publication belongs to the current native conversation. Do not guess another
chat by directory or title. If publication fails, preserve the exact detailed
error and consult the project's MCP and chat-binding troubleshooting guides.

This guidance is optional. The installed MCP tool descriptions support discovery
without a project-specific `AGENTS.md` change.
