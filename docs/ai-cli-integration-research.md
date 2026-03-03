# Research Report: Supporting Gemini CLI & Kiro CLI in WebMux

**Date:** March 2, 2026  
**Purpose:** Document research findings for integrating AI CLI tools (Gemini CLI, Kiro CLI) into WebMux

---

## Executive Summary

This report explores how to integrate Gemini CLI and Kiro CLI into WebMux's web-based terminal interface, following the patterns established by Claude Code, Codex, and OpenCode. Several existing projects already demonstrate this architecture.

WebMux already has a solid foundation with PTY support via Rust's `portable-pty` crate. The main work involves:

1. Adding process spawning for AI CLI tools
2. Implementing WebSocket communication for real-time I/O
3. Building session management for conversation persistence
4. Creating a frontend UI for session control

---

## AI CLI Tools Overview

| Tool | Provider | Architecture | PTY Support | Web UI Projects |
|------|----------|--------------|-------------|-----------------|
| **Claude Code** | Anthropic | Go binary + TUI | Native | Claude Code UI |
| **OpenCode** | SST | Bun + Go TUI | Built-in | Multiple |
| **Gemini CLI** | Google | Node.js | Native | Gemini-CLI-UI |
| **Kiro CLI** | AWS | Node.js/Electron | node-pty | PARK |
| **Codex CLI** | OpenAI | Go binary | Native | Control-PC-Terminal |

---

## 1. Gemini CLI

### Overview

- **Release:** June 2025
- **License:** Apache 2.0
- **Stars:** 96.3k+ on GitHub
- **Installation:** npm, Homebrew, or direct download

### Key Features

- Free tier: 60 requests/min and 1,000 requests/day
- Gemini 3 models with 1M token context window
- Built-in tools: Google Search grounding, file operations, shell commands, web fetching
- MCP (Model Context Protocol) support for custom integrations
- Terminal-first design

### Authentication Options

1. **Login with Google (OAuth)** - Free tier, no API key needed
2. **Gemini API Key** - 1000 requests/day free
3. **Vertex AI** - Enterprise with billing

### Session Storage

- Location: `~/.gemini/projects/`
- Format: JSONL files
- Supports checkpointing and conversation persistence

### Usage

```bash
# Start interactive chat
gemini chat

# Include multiple directories
gemini --include-directories ../lib,../docs

# Use specific model
gemini -m gemini-2.5-flash

# Non-interactive mode
gemini -p "Explain the architecture"
```

---

## 2. Kiro CLI

### Overview

- **Provider:** Amazon Web Services (AWS)
- **Release:** November 2025
- **Type:** Agentic IDE + CLI

### Key Features

- Conversation to code to deployment
- Spec-driven development
- Terminal integration with AI-powered assistance
- Multiple agent types
- Session persistence

### Session Management

- Location: `~/.kiro/sessions/`
- Directory-based persistence
- Multi-line statements via `/editor` command or `Ctrl+J`

### Usage

```bash
# Start chat session
kiro-cli

# Start with specific agent
kiro-cli --agent myagent

# Resume previous conversation
kiro-cli chat --resume

# Interactive session picker
kiro-cli chat --resume-picker
```

---

## 3. Architecture Patterns

### 3.1 Process Spawning & PTY Management

All AI CLI tools run as child processes with PTY (pseudo-terminal) support:

```javascript
// Using node-pty for terminal emulation
const pty = require('node-pty');
const shell = pty.spawn('gemini', ['chat'], {
  name: 'xterm-color',
  cols: 80,
  rows: 30,
  cwd: process.env.HOME,
  env: process.env
});

// Stream output to web client
shell.onData((data) => {
  ws.send(JSON.stringify({ type: 'output', data }));
});
```

### 3.2 WebSocket Communication Layer

```
┌─────────────┐    WebSocket    ┌─────────────┐    PTY    ┌─────────────┐
│  Web Client │ ◄──────────────► │  Backend    │ ◄────────► │  AI CLI     │
│  (xterm.js) │   (JSON msgs)   │  (Express)  │  (stream)  │  Process    │
└─────────────┘                 └─────────────┘            └─────────────┘
```

**Message Protocol:**

```javascript
// Client → Server
{ type: 'input', data: 'prompt\n' }
{ type: 'resize', cols: 80, rows: 30 }

// Server → Client
{ type: 'output', data: 'AI response...' }
{ type: 'exit', code: 0 }
{ type: 'error', message: 'error description' }
```

### 3.3 Session Management

AI CLIs store sessions in structured formats:

- **Gemini CLI**: `~/.gemini/projects/` (JSONL files)
- **Kiro CLI**: `~/.kiro/sessions/` (JSON files)
- **Claude Code**: `~/.claude/sessions/`
- **OpenCode**: `~/.opencode/sessions/`

---

## 4. Existing Reference Implementations

### 4.1 Gemini-CLI-UI

**GitHub:** https://github.com/cruzyjapan/Gemini-CLI-UI  
**Stars:** 611 | **License:** GPL-3.0

**Architecture:**

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Frontend      │    │   Backend       │    │  Gemini CLI     │
│   (React/Vite)  │◄──►│ (Express/WS)    │◄──►│  Integration    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

**Tech Stack:**

- Frontend: React 18 + Vite + TailwindCSS + CodeMirror
- Backend: Express + WebSocket Server
- Terminal: xterm.js
- Database: SQLite (for auth)

**Key Features:**

- Responsive design (mobile, tablet, desktop)
- Interactive chat interface
- Integrated shell terminal
- File explorer with syntax highlighting
- Git integration
- Session management
- Model selection
- YOLO mode (skip confirmations)

### 4.2 PARK (Parallel Agent Runtime for Kiro)

**GitHub:** https://github.com/13shivam/park  
**License:** MIT

**Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│                     Presentation Layer                      │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐   │
│   │ SessionList │  │ NewSession  │  │  xterm.js       │   │
│   └─────────────┘  └─────────────┘  └─────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                   Frontend Business Logic                   │
│   ┌─────────────────┐  ┌─────────────────────────────┐   │
│   │ SessionManager  │  │ TerminalManager              │   │
│   └─────────────────┘  └─────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                    Application Layer                         │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐   │
│   │ Express     │  │ WebSocket   │  │ REST API        │   │
│   └─────────────┘  └─────────────┘  └─────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                    Backend Business Logic                    │
│   ┌─────────────────┐  ┌─────────────────────────────────┐ │
│   │ SessionManager  │  │ PTYInstance / ProcessInstance   │ │
│   └─────────────────┘  └─────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                        Data Layer                           │
│   ┌─────────────┐  ┌──────────────────────────────────┐   │
│   │ SQLite      │  │ File System                      │   │
│   └─────────────┘  └──────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                       Process Layer                         │
│   ┌─────────────────────┐  ┌──────────────────────────┐  │
│   │ Kiro CLI PTY        │  │ Kiro CLI Spawn           │  │
│   │ (Interactive)        │  │ (Non-interactive)        │  │
│   └─────────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**Session Types:**

1. **Interactive PTY** (`interactive-pty`)
   - Uses node-pty for pseudo-terminal
   - Full terminal emulation with ANSI escape codes
   - Supports terminal resize
   - Bidirectional I/O streaming

2. **Non-Interactive Process** (`non-interactive`)
   - Uses Node.js child_process.spawn
   - Captures stdout/stderr
   - No terminal emulation

**Database Schema:**

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  directory TEXT NOT NULL,
  command TEXT NOT NULL,
  status TEXT CHECK(status IN ('active', 'configured', 'stopped', 'completed')),
  type TEXT CHECK(type IN ('interactive-pty', 'non-interactive')),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  pid INTEGER
);
```

**API Endpoints:**

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sessions` | List all sessions |
| GET | `/api/sessions/:id` | Get session details |
| POST | `/api/sessions` | Create and launch session |
| POST | `/api/sessions/config` | Save session config |
| POST | `/api/sessions/launch` | Launch multiple sessions |
| POST | `/api/sessions/:id/stop` | Stop running session |
| DELETE | `/api/sessions/:id` | Delete session |

---

## 5. Integration Strategy for WebMux

### 5.1 Phase 1: Core Infrastructure

#### Backend Changes (Rust)

1. **Add PTY Support to Backend**
   - Use `portable-pty` crate (already in use by WebMux)
   - Implement process spawning for each CLI tool

2. **WebSocket Protocol Extension**

   ```rust
   // New message types for AI CLI sessions
   enum CliMessage {
       StartSession { 
           tool: String,  // "gemini", "kiro", "claude", "codex"
           mode: String,  // "chat", "interactive"
           cwd: String,
           args: Vec<String>
       },
       Input { session_id: String, data: String },
       Resize { session_id: String, cols: u16, rows: u16 },
       StopSession { session_id: String },
       ListSessions { tool: String },
   }
   ```

3. **Session Manager**
   - Track multiple concurrent AI CLI sessions
   - Handle process lifecycle (start, stop, restart)
   - Persist session state

#### Frontend Changes (Vue.js)

1. **Enhanced Terminal Component**
   - Support for ANSI colors
   - Cursor control
   - Better handling of escape sequences

2. **AI Agent Panel**
   - Sidebar for managing AI CLI sessions
   - Session list with status indicators
   - Quick actions (start, stop, resume)

### 5.2 Phase 2: CLI-Specific Adapters

| CLI | Command | Session Storage | Special Flags |
|-----|---------|-----------------|---------------|
| Gemini CLI | `gemini chat` | `~/.gemini/projects/` | `--model`, `--include-directories` |
| Kiro CLI | `kiro-cli chat` | `~/.kiro/sessions/` | `--agent`, `--resume` |
| Claude Code | `claude` | `~/.claude/sessions/` | `--model`, `--permission-mode` |
| Codex CLI | `codex` | `~/.codex/sessions/` | `--model` |

#### Gemini CLI Adapter Example

```rust
struct GeminiAdapter {
    config: CliConfig,
}

impl GeminiAdapter {
    fn new() -> Self {
        Self {
            config: CliConfig {
                command: "gemini".to_string(),
                args: vec!["chat".to_string()],
                session_dir: PathBuf::from(".gemini/projects"),
                ..Default::default()
            }
        }
    }
    
    fn spawn_session(&self, cwd: &Path, model: Option<&str>) -> Result<PtySession> {
        let mut args = vec!["chat".to_string()];
        if let Some(m) = model {
            args.extend(["-m".to_string(), m.to_string()]);
        }
        // Spawn PTY process...
    }
}
```

### 5.3 Phase 3: Frontend Integration

1. **New AI Agent Panel** - Sidebar for managing AI CLI sessions
2. **Terminal View Enhancement** - Support for ANSI colors, cursor control
3. **Chat Interface** - Optional chat overlay for non-PTY interactions
4. **Session Picker** - Resume previous conversations
5. **Settings** - Configure default CLI, model preferences

---

## 6. Technical Challenges & Solutions

### 6.1 Output Parsing

AI CLIs emit mixed content (text, tool results, errors). Need to:

- Buffer and parse stream in real-time
- Distinguish between user output and system messages
- Handle markdown rendering in terminal

**Solution:**

```rust
struct OutputParser {
    buffer: String,
    state: ParseState,
}

enum ParseState {
    Normal,
    EscapeSequence,
    ToolCall,
}

impl OutputParser {
    fn feed(&mut self, data: &str) -> Vec<ParsedOutput> {
        // Parse and categorize output
    }
}
```

### 6.2 Authentication

Each CLI has different auth mechanisms:

- **Gemini CLI**: OAuth, API key, or Vertex AI
- **Kiro CLI**: AWS credentials
- **Claude Code**: Anthropic API or subscription
- **Codex CLI**: OpenAI API key

**Solution:** CLI must be pre-configured on the system; WebMux handles process spawning only.

### 6.3 Resource Management

- Limit concurrent sessions (recommend: 5 max per user)
- Implement timeout for idle sessions
- Clean up zombie processes
- Monitor memory and CPU usage

**Solution:**

```rust
struct SessionLimits {
    max_concurrent: usize,
    max_idle_minutes: u32,
    max_memory_mb: u64,
}

impl Default for SessionLimits {
    fn default() -> Self {
        Self {
            max_concurrent: 5,
            max_idle_minutes: 30,
            max_memory_mb: 512,
        }
    }
}
```

### 6.4 Security

- Sandbox AI CLI access to project directories only
- Implement permission prompts (similar to Claude Code's approval system)
- Log all tool executions
- Validate all input/output

**Solution:**

```rust
struct SecurityPolicy {
    allowed_directories: Vec<PathBuf>,
    require_confirmation: Vec<String>,
    blocked_commands: Vec<String>,
}

impl SecurityPolicy {
    fn validate_command(&self, command: &str) -> Result<()> {
        // Check against blocked commands
        // Verify directory access
    }
}
```

---

## 7. Recommended Implementation Order

### Step 1: Backend - PTY Manager
- Extend existing Rust PTY infrastructure
- Add process spawning capability
- Implement session lifecycle management

### Step 2: Backend - Process Registry
- Track AI CLI processes
- Store session metadata
- Handle WebSocket connections

### Step 3: Backend - WebSocket Handler
- New endpoints for AI CLI
- Message protocol implementation
- Error handling and recovery

### Step 4: Frontend - Terminal Component
- Enhance xterm.js integration
- Add ANSI color support
- Handle terminal resize

### Step 5: Frontend - Session Manager UI
- List, create, resume sessions
- Display session status
- Add quick actions

### Step 6: CLI Adapters
- Per-CLI spawning logic
- Authentication handling
- Special features (models, agents)

---

## 8. API Reference Examples

### Start Gemini CLI Session

```javascript
// Request
{
  "type": "start-session",
  "tool": "gemini",
  "mode": "chat",
  "cwd": "/home/user/project",
  "model": "gemini-2.5-flash"
}

// Response
{
  "type": "session-started",
  "session_id": "gemini-abc123",
  "pid": 12345
}
```

### Send Input to Session

```javascript
// Request
{
  "type": "input",
  "session_id": "gemini-abc123",
  "data": "Explain this codebase\n"
}

// Server streams output back via WebSocket
{
  "type": "output",
  "data": "I'll analyze the codebase..."
}
```

### Resize Terminal

```javascript
// Request
{
  "type": "resize",
  "session_id": "gemini-abc123",
  "cols": 120,
  "rows": 40
}
```

---

## 9. Resources & References

### Official Documentation

- **Gemini CLI:** https://github.com/google-gemini/gemini-cli
- **Gemini CLI Docs:** https://geminicli.com/docs/
- **Kiro CLI:** https://kiro.dev/docs/cli/chat/
- **Claude Code:** https://code.claude.com/docs/
- **OpenCode:** https://opencode.ai/docs/

### Reference Implementations

- **Gemini-CLI-UI:** https://github.com/cruzyjapan/Gemini-CLI-UI
- https://github.com **PARK:**/13shivam/park

### Technical Articles

- **OpenCode Architecture Deep Dive:** https://cefboud.com/posts/coding-agents-internals-opencode-deepdive/
- **Claude Code Comprehensive Guide:** https://introl.com/blog/claude-code-cli-comprehensive-guide-2025

### Key Libraries

| Library | Purpose | Language |
|---------|---------|----------|
| portable-pty | PTY support | Rust |
| node-pty | PTY support | Node.js |
| xterm.js | Terminal emulator | JavaScript |
| xterm-addon-fit | Terminal fitting | JavaScript |
| tokio | Async runtime | Rust |
ungstenite || tokio-t WebSocket | Rust |

---

## 10. Appendix: WebMux Current Architecture

### Existing Backend (Rust + Axum)

- **Web Framework:** Axum for HTTP/WebSocket
- **Async Runtime:** Tokio
- **Terminal Interface:** portable-pty
- **Session Management:** TMUX integration

### Existing Frontend (Vue 3 + TypeScript)

- **Framework:** Vue 3 with Composition API
- **Build Tool:** Vite
- **Terminal:** xterm.js
- **Styling:** Tailwind CSS
- **State:** TanStack Vue Query
- **Communication:** WebSocket

### WebSocket Protocol (Current)

```
Client → Server:  { type: 'list-sessions' }
            { type: 'create-session', name: string }
            { type: 'attach-session', sessionName: string, cols, rows }
            { type: 'input', data: string }
            { type: 'resize', cols, rows }

Server → Client:  { type: 'sessions-list', sessions: Session[] }
            { type: 'output', data: string }
            { type: 'tmux-update', event: string }
```

### Extension for AI CLI

```
New Client → Server Messages:
            { type: 'start-ai-session', tool: string, mode: string, cwd: string }
            { type: 'ai-input', session_id: string, data: string }
            { type: 'ai-resize', session_id: string, cols, rows }
            { type: 'stop-ai-session', session_id: string }
            { type: 'list-ai-sessions', tool: string }

New Server → Client Messages:
            { type: 'ai-session-started', session_id: string, pid: number }
            { type: 'ai-output', session_id: string, data: string }
            { type: 'ai-session-ended', session_id: string, exit_code: number }
            { type: 'ai-error', session_id: string, message: string }
```

---

*End of Document*
