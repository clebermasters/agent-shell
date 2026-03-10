# AgentShell Plugin for OpenCode

This plugin provides AgentShell environment variables to OpenCode skills, enabling skills to automatically send files to the current ACP chat session.

## What It Does

The plugin injects environment variables for every shell command:

| Variable | Description |
|----------|-------------|
| `AGENTSHELL_ACP_SESSION_ID` | Current ACP session ID (e.g., `ses_xxx`) |
| `AGENTSHELL_ACP_CWD` | Working directory of the ACP session |
| `AGENTSHELL_WS_URL` | WebSocket URL for AgentShell (default: `ws://localhost:5173/ws`) |

Also supports legacy `WEBMUX_*` variables for backward compatibility.

## Installation

```bash
cd /path/to/agentshell/flutter/plugins/agentshell-plugin
./install.sh
```

This will:
1. Create a symlink in `~/.config/opencode/plugins/agentshell.js`
2. Create the session directory `~/.agentshell/`
3. Create a placeholder session file

## Uninstallation

```bash
./uninstall.sh
```

## How It Works

### Session File

The plugin reads session information from `~/.agentshell/acp_session`:

```json
{
  "sessionId": "ses_32b50f9d3ffeislY7QuiZngiWy",
  "cwd": "/home/user/project",
  "wsUrl": "ws://localhost:5173/ws"
}
```

### Updating the Session

When you open an ACP session in the AgentShell Flutter app, the session file should be updated. There are several ways to do this:

#### Option 1: Backend Update (Recommended)

The AgentShell backend can write to the session file when a client connects. Add this to your backend:

```rust
// When ACP session is resumed/created
let session_info = serde_json::json!({
    "sessionId": session_id,
    "cwd": cwd,
    "wsUrl": ws_url
});

// Write to session file (requires backend has access to user's home)
// This could be done via a command or HTTP endpoint
```

#### Option 2: Flutter Direct Write

The Flutter app could write directly via ADB:

```dart
// In Flutter, when ACP session is opened
Process.run('adb', ['shell', 'echo', sessionJson, '>', '/data/local/tmp/acp_session']);
// Then sync to host
```

#### Option 3: Manual Update

For testing, you can manually update:

```bash
echo '{"sessionId": "ses_xxx", "cwd": "/path", "wsUrl": "ws://localhost:5173/ws"}' > ~/.agentshell/acp_session
```

## Using in Skills

Skills can now use the environment variables automatically:

```python
import os

async def send_file_to_acp(file_path: str):
    # Read from environment variables
    session_id = os.environ.get('WEBMUX_ACP_SESSION_ID')
    cwd = os.environ.get('WEBMUX_ACP_CWD')
    ws_url = os.environ.get('WEBMUX_WS_URL', 'ws://localhost:5173/ws')
    
    if not session_id:
        raise ValueError("No ACP session available")
    
    await send_file_to_acp_chat(
        server_url=ws_url,
        session_id=session_id,
        cwd=cwd,
        file_path=file_path
    )
```

## For Skill Developers

To use AgentShell environment in your skill:

1. The skill will automatically have access to the env vars when running in OpenCode
2. Check for the vars and use sensible defaults if not set
3. Document the required environment variables in your SKILL.md

Example:

```python
session_id = os.environ.get('WEBMUX_ACP_SESSION_ID')
if not session_id:
    # Fallback: list sessions and use most recent
    sessions = await list_acp_sessions()
    session_id = sessions[0]['sessionId']
```

## Requirements

- OpenCode with plugin support
- Access to `~/.config/opencode/plugins/` directory
- Write access to `~/.agentshell/` directory

## Files

```
agentshell-plugin/
├── agentshell.js      # Main plugin file
├── install.sh     # Installation script
├── uninstall.sh   # Uninstallation script
└── README.md      # This file
```

## License

Same as AgentShell project.
