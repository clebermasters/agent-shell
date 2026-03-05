# Architectural Reflection: The OpenCode Integration Dilemma

We have been trying to solve a fundamental architectural mismatch by adding increasingly complex solutions to the backend. It's time to look at the actual physics of the systems we are trying to connect and understand our constraints.

## The Goal
We want the Flutter WebMux app to provide a clean, rich chat interface for the `opencode` CLI tool. Crucially, we want **Pane Isolation**: if a user has `agent` and `temp` tmux sessions open in the same directory, sending a chat message to the `temp` pane should only show the response in the `temp` chat UI.

## The Reality of OpenCode's Architecture
OpenCode was designed as a single-player, single-context CLI tool. Its architecture determines its behavior:

1. **The Source of Truth is the Database:** OpenCode stores all history in `~/.opencode/opencode.db`.
2. **Session Scope = Directory:** OpenCode groups conversations into sessions. It defines a session almost entirely by the current working directory (`project_id='global'`). 
3. **No Process Awareness:** The database schema has absolutely no concept of *which* terminal (PID, TTY, or Tmux pane) submitted a prompt. 

## Why Our Solutions Failed

We spent hours trying to force OpenCode's *Directory-based* reality into a *Pane-based* abstraction using only the database as our lens.

1. **Log Parsing / PIDs:** Failed because there are no process IDs in the logs mapping to specific DB messages. The log file is shared globally.
2. **Lineage / Timestamps:** Failed because OpenCode does background tasks (like generating session titles) that pollute the timeline, and timestamps between processes are too close to reliably separate.
3. **Text Correlation (The Shared Broadcast):** We tried to match the user's typed text to the DB. We succeeded in finding the right session! But we discovered that **OpenCode itself dumps messages from BOTH tmux panes into the EXACT SAME database session**. Because both backend watchers locked onto that one session, both Flutter chats broadcasted identical messages.
4. **Per-Pane Partitioning (The Complete Failure):** We tried to be clever by reading the shared session and saying "Only show messages that match the exact string sent via Tmux to this pane." This failed catastrophically resulting in *no* messages for a simple reason: the terminal buffer. As we saw in the logs, sending `"debug_test_temp_42"` when the word `"hey"` was already in the prompt resulted in `"heydebug_test_temp_42"` being sent to the DB. Our exact string matcher failed to find `"debug_test_temp_42"`, assumed the pane owned nothing, and swallowed all output.

### The Fundamental Conclusion
**We cannot reliably achieve Pane Isolation by reading `opencode.db`.** The database destroys the very context (which pane sent the message) that we need to maintain isolation. 

---

## The Paths Forward

If we accept this limitation, we have four distinct architectural paths we can take. We must choose one.

### 1. Modifying OpenCode (The Upstream Fix)
If we cannot extract context from the DB, we must force OpenCode to save that context into the DB.
* **How it works:** We modify the `opencode` source code so that when it launches, it receives a `TMUX_PANE_ID` (via CLI arg or env var). OpenCode then writes this ID into the database alongside every message, or uses it to enforce discrete sessions.
* **Pros:** Solves the root cause elegantly. Keeps the DB as the reliable source of truth. Allows perfect 1:1 pane mapping.
* **Cons:** Requires us to have control over the `opencode` binary and the ability to modify its internal SQLite schemas and rust/python code.

### 2. The "Slack Channel" Model (Embrace the Shared State)
If two panes are in the same folder, they *are* in the same OpenCode session. Instead of fighting it, we change the Flutter UI concept.
* **How it works:** We stop trying to isolate the chat per pane. The chat tab becomes a "Project Chat" tab. Whether you are looking at `agent:0` or `temp:0`, the rich chat UI displays the shared OpenCode session for that directory.
* **Pros:** Trivially easy to implement. Highly robust. Accurately reflects what OpenCode is actually doing.
* **Cons:** Changes the user experience. You lose the ability to have two completely separate AI conversations in the same directory simultaneously.

### 3. Virtual Workspaces (The Directory Hack)
If OpenCode groups by directory, we trick OpenCode into thinking it is in different directories.
* **How it works:** We create virtual directories for each pane (e.g., `/tmp/webmux-workspaces/agent/` and `/tmp/webmux-workspaces/temp/`). We symlink the actual project files into these directories, and run `opencode` in the virtual folder.
* **Pros:** Forces OpenCode to create genuinely separate DB sessions without modifying OpenCode's code.
* **Cons:** Dangerously brittle. Symlinks often break language servers (LSP), build tools (`cargo`, `npm`), and git integrations inside the AI tool.

### 4. PTY Scraping (Abandon the DB entirely)
If the DB doesn't have the data, but the terminal screen does, we read the screen.
* **How it works:** The backend manages the `tmux` pane. We use regex or ANSI parsing to constantly scrape the raw text output of the terminal pane to extract the AI's responses, bypassing the SQLite database entirely.
* **Pros:** Guarantees 100% pane isolation because we are reading exactly what the user sees in that specific pane.
* **Cons:** The hardest to build. Parsing a complex TUI (like OpenCode with its spinners, markdown rendering, clear-screen codes, and layout boxes) purely from raw text and ANSI escape codes is extremely error-prone and brittle to any UI updates in OpenCode.

## Next Steps
We must agree on one of these architectural paths. Our attempts to hack the DB to pretend it has data it doesn't have are a dead end. We must either embrace the shared state (Option 2) or change how OpenCode creates state (Option 1 or 3).
