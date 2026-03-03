# Using Opencode in Webmux

Webmux now supports **Opencode** as a first-class AI coding agent, alongside Claude Code and Codex. This integration allows you to use Opencode directly within a Webmux terminal pane and view its rich, structured output (such as tool calls, bash commands, and AI responses) natively in the Flutter Chat UI.

## How It Works

Unlike other CLI agents that write simple append-only `.jsonl` logs, Opencode maintains a robust, local SQLite database for its session states. Webmux's Rust backend intelligently connects to this database (`~/.local/share/opencode/opencode.db`) in real-time. 

When you run `opencode` in a tmux window managed by Webmux, the backend detects the process, finds the corresponding session in the SQLite database based on your current working directory, and starts polling for new messages. These messages are then streamed directly to your Flutter app.

## Prerequisites

1. Ensure you have the latest version of the Webmux Rust backend running.
2. Ensure you have `opencode` installed on your system.
3. Your Webmux Flutter app must be connected to the backend.

## Step-by-Step Guide

### 1. Open a Terminal Session
Open your Webmux Flutter app and navigate to the terminal view. Create or attach to a tmux session.

### 2. Start Opencode
In the terminal pane, navigate to your desired project directory and simply start Opencode:

```bash
cd /path/to/your/project
opencode
```

### 3. Open the Chat View
Once Opencode starts running, open the **Chat Sidebar/View** in the Webmux Flutter UI.

You will see:
- A status indicator showing that **Opencode** has been detected.
- The UI will transition from the "Start a conversation with Claude Code or Opencode" empty state.

### 4. Interact with Opencode
You can interact with Opencode in two ways:
1. **From the Terminal:** Type directly into the terminal prompt.
2. **From the Chat UI:** Use the chat input box at the bottom of the Chat view to send messages to the Opencode process.

As Opencode "thinks" and executes tasks, the Chat UI will automatically populate with:
- **Assistant Messages:** Standard text responses from Opencode.
- **Tool Calls:** Visual indicators when Opencode runs bash commands, reads files, or interacts with the system.
- **Tool Results:** The output/results of those tool executions.

## Troubleshooting

- **No Output in Chat UI?** Ensure that Opencode is actually running as the active process in the terminal pane. If you run it inside a complex wrapper script, the backend might struggle to detect the process name as exactly `"opencode"`.
- **Database Location:** The backend expects the Opencode SQLite database to be located at `~/.local/share/opencode/opencode.db`. If you have configured Opencode to store its data elsewhere, the integration may not find the session telemetry.
