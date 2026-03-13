# AgentShell

<p align="center">
  <img src="logo-square.png" alt="AgentShell Logo" width="200">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rust-DEA584?style=flat&logo=rust&logoColor=white" alt="Rust">
  <img src="https://img.shields.io/badge/Flutter-02569B?style=flat&logo=flutter&logoColor=white" alt="Flutter">
  <img src="https://img.shields.io/badge/OpenCode-FF6B6B?style=flat" alt="OpenCode ACP">
</p>

AgentShell is a **high-performance, Rust-powered terminal session manager** that gives you complete control over your development agents—anywhere, anytime.

Built with Rust for maximum speed and reliability, AgentShell lets you manage TMUX sessions and AI-powered development workflows from your Android device or web browser. Whether you're monitoring autonomous coding agents, controlling remote development sessions via Claude Code or OpenCode, or simply need mobile access to your terminal workflows, AgentShell delivers a seamless, real-time experience.

**Control your AI agents. Control your development. From anywhere.**

> 🚀 **Remote Claude Code**: Run Claude Code in a TMUX session and control it remotely from your phone or browser — send prompts, view output, and supervise your AI coding agent in real-time.

> 🚀 **OpenCode via ACP + TMUX**: Full OpenCode support — either through the native ACP (Agent Control Protocol) integration for structured AI sessions, or by running OpenCode directly in a TMUX session for classic terminal control.

## Features

- **Remote Claude Code**: Run Claude Code in a TMUX session and control it from anywhere — send input, read output, and supervise your AI coding agent remotely
- **Remote OpenCode**: Control OpenCode via native ACP integration or directly through a TMUX session
- **Agent Control**: Monitor and control AI coding agents running in TMUX sessions
- **TMUX Session Management**: Create, attach, rename, and kill TMUX sessions
- **Window Management**: Create, switch, rename, and kill windows within sessions
- **Real-time Communication**: WebSocket-based architecture for live terminal I/O
- **Chat Support**: Full chat experience with text, images, audio, and file sharing
- **Audio Streaming**: Record and play audio messages with transcription support
- **Image Viewer**: View images inline with full-screen mode and save to device
- **File Management**: View and manage files in chat sessions
- **Responsive Design**: Modern Android interface built with Flutter
- **Performance Optimized**: Rust-powered backend handles large outputs with buffering and flow control
- **Mobile Optimized**: Touch-friendly interface with iOS safe area support
- **Network Accessible**: Access via local network or Tailscale IPs from anywhere
- **Session Isolation**: Alternative session manager to avoid attachment conflicts

## AI Agent Integrations

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          AgentShell Workflows                                   │
│                                                                                 │
│  ╔═══════════════════════════════════════════════════════════════════════════╗   │
│  ║  TMUX Mode — Claude Code, OpenCode, or any CLI tool                     ║   │
│  ║                                                                         ║   │
│  ║  ┌──────────┐  WebSocket  ┌─────────────┐  TMUX API  ┌──────────────┐  ║   │
│  ║  │ 📱 Phone │────────────►│  AgentShell │───────────►│ TMUX Session │  ║   │
│  ║  │ 🌐 Web   │◄────────────│  (Backend)  │◄───────────│              │  ║   │
│  ║  └──────────┘  terminal   └─────────────┘  send-keys  │ Claude Code │  ║   │
│  ║                I/O stream                  capture-pane│ OpenCode    │  ║   │
│  ║                                                        │ vim, htop…  │  ║   │
│  ║  Flow:                                                 └──────────────┘  ║   │
│  ║  1. Start any CLI in a TMUX session on your server                      ║   │
│  ║  2. Open AgentShell → attach to session                                 ║   │
│  ║  3. Full terminal control: type, scroll, resize                         ║   │
│  ╚═════════════════════════════════════════════════════════════════════════╝   │
│                                                                                 │
│  ╔═══════════════════════════════════════════════════════════════════════════╗   │
│  ║  ACP Mode — OpenCode with structured AI session control                 ║   │
│  ║                                                                         ║   │
│  ║  ┌──────────┐  WebSocket  ┌─────────────┐   ACP    ┌────────────────┐  ║   │
│  ║  │ 📱 Phone │────────────►│  AgentShell │─────────►│ OpenCode Agent │  ║   │
│  ║  │ 🌐 Web   │◄────────────│  (Backend)  │◄─────────│   Service      │  ║   │
│  ║  └──────────┘  chat UI    └─────────────┘  events   └────────────────┘  ║   │
│  ║                messages,                   tool calls,                    ║   │
│  ║                permissions                 streaming                     ║   │
│  ║                                                                         ║   │
│  ║  Flow:                                                                  ║   │
│  ║  1. Create an ACP session in AgentShell (Chat interface)                ║   │
│  ║  2. Send prompts → agent thinks → streams responses                     ║   │
│  ║  3. Agent requests tool use → you approve/deny → agent executes         ║   │
│  ║  4. Full history persisted in SQLite for later review                   ║   │
│  ╚═════════════════════════════════════════════════════════════════════════╝   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Claude Code (via TMUX)

AgentShell lets you run **Claude Code** inside a TMUX session and control it remotely from your Android device or web browser. No special protocol needed — just launch Claude Code in a session and attach from anywhere:

```bash
# On your server: start Claude Code in a named TMUX session
tmux new-session -s claude -d "claude"
```

Then open AgentShell, attach to the `claude` session, and you have full keyboard control — send prompts, approve actions, view streaming output, all from your phone or browser.

### OpenCode (ACP + TMUX)

AgentShell supports OpenCode in two ways:

- **Native ACP integration** (recommended): Structured AI sessions with real-time streaming, tool call visibility, and permission control — managed through the Chat interface.
- **TMUX mode**: Run `opencode` directly in a TMUX session for classic terminal interaction, same as any other CLI tool.

#### What is ACP?

ACP (Agent Control Protocol) is a protocol that allows AI agents to interact with your development environment through AgentShell. When you connect OpenCode to AgentShell via ACP, you get:

- **AI-Powered Sessions**: Create and control development sessions powered by AI agents
- **Real-time Interaction**: Watch as the AI agent executes commands, makes edits, and interacts with your codebase
- **Tool Execution**: AI agents can run terminal commands, read/write files, and perform complex development tasks
- **Permission Control**: Approve or deny tool execution requests from AI agents
- **Session Persistence**: Resume AI-powered sessions anytime

#### Getting Started with ACP

1. **Start AgentShell backend**:
   ```bash
   cd backend-rust
   cargo run --release
   ```

2. **Connect OpenCode**: In your OpenCode configuration, set the AgentShell WebSocket URL:
   ```
   ws://localhost:4010/ws
   ```

3. **Start a new ACP session**: Create a new session with your desired working directory.

4. **Watch the AI in action**: OpenCode will connect and begin interacting with your development environment in real-time!

#### ACP Features

- **Session Management**: Create, resume, fork, and list ACP sessions
- **Message Streaming**: Real-time streaming of AI thoughts and responses
- **Tool Calls**: AI agents can execute shell commands, read files, write files, and more
- **Permission System**: Review and approve/deny tool execution requests
- **Event History**: All session events are persisted for later review

## Android App (Flutter)

AgentShell includes a high-performance **Android application** built with Flutter. This app allows you to control your Tmux sessions directly from your phone.

### Key Features

- **SSH-Free Access**: Connect directly to the AgentShell backend via WebSocket.
- **Terminal Accessory Bar**: A horizontally scrollable bar featuring `CTRL`, `ALT`, `ESC`, `TAB`, arrow keys, and `F1-F12` for effortless Tmux shortcuts.
- **Enhanced Native Keyboard**: Optimized support for the native Android keyboard, including "sticky" modifiers (e.g., tap `CTRL` then `l` to clear screen).
- **Session Persistence**: The app remembers your active session and navigation tab, automatically restoring them even after app restarts or screen-off events.
- **Real-time Synchronization**: High-performance terminal rendering with instant cursor updates.
- **Audio Playback & Recording**: Play audio messages and record voice notes with transcription support.
- **Image Viewer**: View images inline with full-screen mode and save to device.
- **File Support**: View and manage files shared in chat sessions.

### Building from Source

1. Ensure Docker is installed and running.
2. Navigate to the `flutter` directory:
   ```bash
   cd flutter
   ```
3. Run the build script:
   ```bash
   ./build.sh
   ```
4. The generated APK will be available in the project root as `agentshell-flutter-debug.apk`.

## Prerequisites

- Rust (latest stable version) - Install from [rustup.rs](https://rustup.rs/)
- cargo (comes with Rust)
- TMUX installed on your system
- Modern web browser with WebSocket support (for WebUI)

## Quick Start

### Backend Only

Run the Rust backend:

```bash
cd backend-rust
cargo run --release
```

The backend runs on `http://localhost:4010` with WebSocket at `/ws`.

### Backend + WebUI

If you want a simple web interface, you can serve static files from the backend or use a separate frontend.

## Network Access

The application accepts connections from any network interface:
- **Local access**: `http://localhost:4010`
- **Network access**: `http://[YOUR-IP]:4010` (e.g., `http://192.168.1.100:4010`)
- **Tailscale access**: `http://[TAILSCALE-IP]:4010` (e.g., `http://100.x.x.x:4010`)

## WebSocket Protocol

All communication with the backend happens through WebSocket connections.

Connect to `/ws` endpoint for terminal session management.

**Client → Server Messages:**
```javascript
// Session Management
{ type: 'list-sessions' }
{ type: 'create-session', name: string }
{ type: 'attach-session', sessionName: string, cols: number, rows: number }
{ type: 'kill-session', sessionName: string }
{ type: 'rename-session', sessionName: string, newName: string }

// Terminal I/O
{ type: 'input', data: string }
{ type: 'resize', cols: number, rows: number }

// Window Management
{ type: 'list-windows', sessionName: string }
{ type: 'create-window', sessionName: string, windowName?: string }
{ type: 'select-window', sessionName: string, windowIndex: number }
{ type: 'kill-window', sessionName: string, windowIndex: number }
{ type: 'rename-window', sessionName: string, windowIndex: number, newName: string }
```

**Server → Client Messages:**
```javascript
// Session Updates
{ type: 'sessions-list', sessions: Session[] }
{ type: 'session-created', session: Session }
{ type: 'session-killed', sessionName: string }
{ type: 'session-renamed', oldName: string, newName: string }
{ type: 'attached', sessionName: string }
{ type: 'disconnected' }

// Terminal Output
{ type: 'output', data: string }

// Window Updates
{ type: 'windows-list', windows: Window[] }
{ type: 'window-created', window: Window }
{ type: 'window-selected', windowIndex: number }
{ type: 'window-killed', windowIndex: number }
{ type: 'window-renamed', windowIndex: number, newName: string }

// Real-time Updates (from monitor)
{ type: 'tmux-update', event: 'session-added' | 'session-removed' | 'window-added' | 'window-removed' }
```

## Architecture

### Backend (Rust + Axum)

- **Web Framework**: Axum for high-performance async HTTP/WebSocket handling
- **Async Runtime**: Tokio for concurrent operations
- **Terminal Interface**: portable-pty for cross-platform PTY support
- **WebSocket**: tokio-tungstenite for real-time communication
- **Session Management**: Two approaches:
  - Direct attachment via `tmux attach-session`
  - Alternative manager using `send-keys` and `capture-pane` for better isolation

### Android App (Flutter)

- **Framework**: Flutter with Dart
- **State Management**: Riverpod
- **WebSocket Client**: Native WebSocket API with reconnection logic

## Troubleshooting

### Common Issues

**Keyboard input not working**
- Click anywhere in the terminal area to ensure it has focus

**Session not responding**
- Refresh the connection and re-select the session from the list

**Window switching fails**
- Ensure you're attached to the session first

**Terminal freezes with large output**
- The system includes output buffering and flow control
- Check server logs for debug information

### Debug Mode

Enable detailed logging:

```bash
cd backend-rust
RUST_LOG=debug cargo run --release
```

## Performance Considerations

- **Output Buffering**: Server buffers PTY output to prevent WebSocket overflow
- **Flow Control**: PTY pauses when WebSocket buffer is full
- **Client Batching**: Terminal writes are batched for smooth rendering
- **Session Isolation**: Alternative session manager available for better multi-client support

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing-feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Configuration

AgentShell reads configuration from a `.env` file in the project root. Create one before building:

```bash
cp .env.example .env   # if available, or create manually
```

| Variable | Description | Required |
|----------|-------------|----------|
| `AUTH_TOKEN` | Shared secret that protects all API and WebSocket endpoints. When set, every request must include this token or it will be rejected with HTTP 401. On **web**, this token is also the password entered on the login page — it is never baked into the JS bundle. If empty or not set, the backend remains fully open (no authentication). | Recommended |
| `SERVER_LIST` | Comma-separated list of backend servers for the Flutter app. Format: `host:port,LABEL\|host:port,LABEL`. Example: `192.168.0.10:4010,HOME\|myserver.com:443,CLOUD` | Yes |
| `OPENAI_API_KEY` | OpenAI API key used by AI-powered features. | Optional |
| `SHOW_THINKING` | Show AI thinking/reasoning in chat UI (`true`/`false`). Default: `true`. | Optional |
| `SHOW_TOOL_CALLS` | Show AI tool call details in chat UI (`true`/`false`). Default: `true`. | Optional |

These values are baked into the Flutter app at build time via `BuildConfig`. The backend reads `AUTH_TOKEN` from the process environment at runtime (set automatically by the systemd service when using `install.sh`).

### Example `.env`

```env
SERVER_LIST=192.168.0.10:4010,HOME|myserver.com:443,CLOUD
AUTH_TOKEN=your-secret-token-here
OPENAI_API_KEY=sk-...
SHOW_THINKING=false
SHOW_TOOL_CALLS=false
```

## Security

> **⚠️ IMPORTANT: If you are exposing AgentShell to the public internet (e.g., via Cloudflare, reverse proxy, or port forwarding) without a VPN, you MUST set `AUTH_TOKEN` in your `.env` before building and deploying.** Without it, anyone who discovers your server URL will have full access to your terminal sessions, can execute commands, read files, and control your AI agents. Set a strong, unique token and rebuild all clients (Android, Web, Linux) after adding it.

AgentShell supports token-based authentication to protect all API and WebSocket endpoints.

### How it works

```
                        ┌──────────────────────────────────────────────┐
                        │              Rust Backend                    │
                        │                                              │
┌───────────┐  ?token=  │  ┌──────────────┐    ┌───────────────────┐   │
│  Android  │──────────►│  │              │ ✅ │  /ws              │   │
│  (Flutter)│  ws/wss   │  │              │───►│  /api/clients     │   │
└───────────┘           │  │  Auth        │    │  /api/chat/files  │   │
                        │  │  Middleware   │    │  /api/tmux/input  │   │
┌───────────┐  ?token=  │  │              │    └───────────────────┘   │
│    Web    │──────────►│  │  Validates   │                            │
│  (Flutter)│  wss      │  │  AUTH_TOKEN  │    ┌───────────────────┐   │
└───────────┘           │  │              │ ❌ │  HTTP 401         │   │
                        │  │              │───►│  Unauthorized     │   │
┌───────────┐  ?token=  │  │              │    └───────────────────┘   │
│   Linux   │──────────►│  │              │                            │
│  (Flutter)│  ws       │  └──────────────┘    ┌───────────────────┐   │
└───────────┘           │                      │  Static files     │   │
                        │         No auth ────►│  (index.html, js) │   │
                        │                      └───────────────────┘   │
                        └──────────────────────────────────────────────┘
```

1. Set `AUTH_TOKEN` in your `.env` file
2. Build the Flutter apps (Android, Web, Linux) — the token is embedded at build time
3. Run `sudo ./install.sh` to deploy the backend — the token is passed to the systemd service
4. All requests without a valid token are rejected with HTTP 401

The token can be provided via:
- **Query parameter**: `?token=your-token` (used by Flutter clients for WebSocket connections)
- **Header**: `X-Auth-Token: your-token` (alternative for HTTP API calls)

Static files (the web frontend) are served without authentication.

### Web Login Page

When accessing AgentShell from a **browser**, the Flutter web app always shows a login screen before granting access — regardless of how the app was built.

```
┌─────────────────────────────────────────────────────┐
│                  Browser (Web)                      │
│                                                     │
│  Open URL                                           │
│      │                                              │
│      ▼                                              │
│  web_auth_token in localStorage?                    │
│      │                                              │
│      ├── No ──► LoginScreen                         │
│      │              │                               │
│      │          Enter password                      │
│      │              │                               │
│      │          Validate: GET /api/clients?token=   │
│      │              │                               │
│      │          200 OK ──► save to localStorage     │
│      │              │              │                │
│      │           401/err       HomeScreen           │
│      │              │                               │
│      │          Show error                          │
│      │                                              │
│      └── Yes ──► HomeScreen (skip login)            │
└─────────────────────────────────────────────────────┘
```

**The password is your `AUTH_TOKEN`.** The web app validates the entered password against the backend's `/api/clients` endpoint. On success, the token is stored in the browser's `localStorage` so the user does not need to log in again on subsequent visits.

**To configure:**

1. Set `AUTH_TOKEN` in your `.env` file:
   ```env
   AUTH_TOKEN=your-strong-secret-here
   ```
2. Rebuild and redeploy the web app:
   ```bash
   cd flutter && ./build.sh web
   ./scripts/deploy-web.sh
   ```
3. Open the web app in your browser — you will be prompted for the password.

> **Note:** Unlike Android/Linux builds (where the token is embedded in the binary), the web build intentionally **does not** bake `AUTH_TOKEN` into the compiled JavaScript. This prevents the secret from being visible in browser developer tools or the page source. Instead, the user enters it at login time and the browser stores it locally.

**Session persistence:** The token is kept in `localStorage` indefinitely. To end a session, use the **Log Out** button in **Settings** — this clears the stored token and returns to the login screen.

**Android and Linux** apps bypass the login screen entirely and connect directly using the token embedded at build time.

### Backwards compatibility

If `AUTH_TOKEN` is not set, the backend remains fully open — no authentication is required. This preserves the original behavior for local/trusted network setups.

### Security details

- Token comparison uses **constant-time equality** to prevent timing attacks
- Tokens are **never logged** — only the request path (without query string) appears in logs
- The token is embedded in the app binary at build time, not transmitted in plain text headers (WebSocket API does not support custom headers)

### Exposing to the Internet

For a complete guide on exposing AgentShell to the public internet using **Cloudflare** and **Nginx** as a reverse proxy, see **[docs/deployment.md](docs/deployment.md)**. It covers:

- Nginx reverse proxy configuration with WebSocket support
- Cloudflare DNS, TLS, and WebSocket settings
- S3 + Cloudflare deployment for the Flutter web app
- Building all clients with the auth token
- Verification steps and troubleshooting

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Backend powered by [Rust](https://www.rust-lang.org/) and [Axum](https://github.com/tokio-rs/axum)
- Android app built with [Flutter](https://flutter.dev/)
- Real-time communication via [WebSocket](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
