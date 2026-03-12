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

Built with Rust for maximum speed and reliability, AgentShell lets you manage TMUX sessions and AI-powered development workflows from your Android device or web browser. Whether you're monitoring autonomous coding agents, controlling remote development sessions via OpenCode, or simply need mobile access to your terminal workflows, AgentShell delivers a seamless, real-time experience.

**Control your AI agents. Control your development. From anywhere.**

> 🚀 **Now with ACP (Agent Control Protocol) support for OpenCode!** - Control AI-powered development sessions directly from your devices.

## Features

- **Agent Control**: Monitor and control AI coding agents running in TMUX sessions
- **Remote OpenCode Sessions**: Start, monitor, and interact with OpenCode AI development sessions remotely
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

## ACP with OpenCode

AgentShell supports **ACP (Agent Control Protocol)** - enabling seamless integration with [OpenCode](https://opencode.ai), a powerful AI coding assistant. This feature brings AI-driven development directly to your terminal sessions.

### What is ACP?

ACP is a protocol that allows AI agents to interact with your development environment through AgentShell. When you connect OpenCode to AgentShell, you get:

- **AI-Powered Sessions**: Create and control development sessions powered by AI agents
- **Real-time Interaction**: Watch as the AI agent executes commands, makes edits, and interacts with your codebase
- **Tool Execution**: AI agents can run terminal commands, read/write files, and perform complex development tasks
- **Permission Control**: Approve or deny tool execution requests from AI agents
- **Session Persistence**: Resume AI-powered sessions anytime

### Getting Started with OpenCode

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

### ACP Features

- **Session Management**: Create, resume, fork, and list ACP sessions
- **Message Streaming**: Real-time streaming of AI thoughts and responses
- **Tool Calls**: AI agents can execute shell commands, read files, write files, and more
- **Permission System**: Review and approve/deny tool execution requests
- **Event History**: All session events are persisted for later review

### Architecture

```
┌─────────────┐    WebSocket    ┌─────────────┐    ACP    ┌─────────────┐
│   OpenCode  │◄───────────────►│ AgentShell  │◄────────►│  AI Agent   │
│   (Client)  │                 │  (Backend)  │           │  (Remote)   │
└─────────────┘                 └─────────────┘           └─────────────┘
```

The ACP client in AgentShell acts as a bridge between OpenCode's WebSocket connection and the remote AI agent service.

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

## Security Notes

- The application is designed for use on trusted networks
- Consider proper authentication for production deployments

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Backend powered by [Rust](https://www.rust-lang.org/) and [Axum](https://github.com/tokio-rs/axum)
- Android app built with [Flutter](https://flutter.dev/)
- Real-time communication via [WebSocket](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
