# Codex App Server Integration

This document explains how the Codex direct-chat integration works in `webmux`, from the Android UI down to the backend process that talks to `codex app-server`.

## What This Integration Is

`webmux` now supports two direct AI backends behind the existing "direct session" UX:

- `OpenCode`, which still uses the ACP client path
- `Codex`, which uses `codex app-server`

The important architectural point is that Codex is not using ACP. It is exposed through the same websocket feature family (`acp-*` messages), but the backend routes those messages to a different implementation when the session belongs to the `codex` provider.

## User Flow On Android

To start a Codex direct chat from Android:

1. Open the `Sessions` screen.
2. Tap `New`.
3. In the create dialog, choose `Codex`.
4. Enter a working directory.
5. Tap `Create`.

That create dialog is implemented in [android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsScreen.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsScreen.kt).

The Android call chain is:

1. `SessionsScreen` calls `viewModel.createAcpSession(cwd, "codex")`
2. `SessionsViewModel.createAcpSession()` sends:
   - `select-backend` with `backend = "codex"`
   - `acp-create-session` with `backend = "codex"`
3. When the backend replies with `acp-session-created`, Android navigates to the chat screen.
4. The chat screen resumes and watches the direct session automatically.

Relevant files:

- [android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt)
- [android-native/app/src/main/java/com/agentshell/data/remote/WebSocketCommands.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/data/remote/WebSocketCommands.kt)
- [android-native/app/src/main/java/com/agentshell/feature/chat/ChatViewModel.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/chat/ChatViewModel.kt)

## High-Level Architecture

The flow is:

1. Android chooses `codex` as the selected direct backend.
2. The backend websocket layer normalizes that provider choice.
3. The backend ensures a shared `CodexAppClient` exists.
4. `CodexAppClient` spawns `codex app-server` as a child process.
5. `webmux` communicates with Codex over `stdin` and `stdout` using JSON messages.
6. Codex app-server notifications are converted into the existing websocket server messages used by the chat UI.

Main backend files:

- [backend-rust/src/main.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/main.rs)
- [backend-rust/src/codex_app/client.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/codex_app/client.rs)
- [backend-rust/src/websocket/acp_cmds.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/websocket/acp_cmds.rs)
- [backend-rust/src/websocket/chat_cmds.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/websocket/chat_cmds.rs)

## Process Startup

Codex is started by the backend, not by Android and not by tmux.

`CodexAppClient::start()` runs:

```bash
codex app-server
```

The process is spawned with piped `stdin` and `stdout`, and then `webmux` performs:

1. `initialize`
2. `initialized`

The client advertises:

```json
{
  "clientInfo": {
    "name": "webmux",
    "version": "<cargo package version>"
  },
  "capabilities": {
    "experimentalApi": true
  }
}
```

If `codex` is not installed or not on `PATH`, session creation fails on the backend side when the spawn happens.

## Session Identity Model

Direct sessions are provider-prefixed.

Examples:

- OpenCode direct session: `opencode:abc123`
- Codex direct session: `codex:thread_123`

This lets the existing direct-chat UI keep a single list while the backend still knows which implementation to use.

There are three related ID forms in the system:

- external session ID: `codex:<thread_id>`
- raw provider ID: `<thread_id>`
- local chat key: `acp_codex:<thread_id>`

The local chat key is used for persisted overlays in `chat_event_store` and clear-history tracking.

## Why Codex Reuses The ACP Websocket Surface

The Android client already had a complete direct-chat transport:

- create session
- resume session
- send prompt
- stream chunks
- show tool calls
- show permission cards
- load history

Instead of inventing a second mobile transport just for Codex, `webmux` keeps the same websocket message family and switches behavior by provider.

This means:

- Android still sends `acp-create-session`, `acp-resume-session`, `acp-send-prompt`, and so on
- the backend inspects the provider and dispatches to either OpenCode ACP or Codex app-server

So the naming is historical. The transport is shared, but the implementation is provider-specific.

## Backend Routing

The main routing happens in [backend-rust/src/websocket/acp_cmds.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/websocket/acp_cmds.rs).

Supported provider-aware operations include:

- `SelectBackend`
- `AcpCreateSession`
- `AcpResumeSession`
- `AcpForkSession`
- `AcpListSessions`
- `AcpSendPrompt`
- `AcpRespondPermission`
- `AcpLoadHistory`
- `AcpDeleteSession`
- `AcpClearHistory`

The backend keeps one shared optional OpenCode client and one shared optional Codex client inside `AppState`.

## Codex Thread Lifecycle

Codex app-server is thread-based. `webmux` maps direct-session operations onto Codex thread operations like this:

- create session -> `thread/start`
- resume existing session -> `thread/resume`
- fork session -> `thread/fork`
- send a prompt -> `turn/start`
- load full transcript -> `thread/read`
- list sessions -> `thread/list`

`persistFullHistory: true` is passed on start, resume, and fork. That matters because it keeps the rich structured history available for later `thread/read` calls.

## Session Listing

When the app asks for direct sessions, the backend merges two sources:

- OpenCode sessions
- Codex threads

Codex sessions are retrieved via paginated `thread/list` calls. Each returned thread summary is converted into a `SessionInfo` with:

- prefixed session ID
- cwd
- title
- updated timestamp
- `provider = "codex"`

That provider field is what lets Android label the row as `Codex` in the direct sessions list.

## Creating A Codex Session

When Android sends `acp-create-session` with `backend = "codex"`, the backend:

1. ensures the shared Codex app-server client exists
2. calls `thread/start`
3. trims trailing `/` from the cwd
4. requests `persistFullHistory`
5. returns `acp-session-created`

The response includes:

- `sessionId`
- `cwd`
- `currentModelId`

Android listens for `acp-session-created` and immediately navigates into the direct chat screen.

## Opening An Existing Codex Direct Session

When the chat screen opens for a direct session, Android does three things:

1. selects the correct backend based on the session ID prefix
2. sends `acp-resume-session`
3. starts `watch-acp-chat-log`

For Codex, the backend uses the `codex:` prefix to route resume calls to `thread/resume`.

The Android helper that decides the backend is based on the raw session ID prefix:

- `codex:...` -> `codex`
- everything else -> `opencode`

## History Loading

Codex direct-session history does not come from the tmux Codex rollout parser.

Instead, direct-session history is loaded from app-server using:

```json
{
  "method": "thread/read",
  "params": {
    "threadId": "<raw_thread_id>",
    "includeTurns": true
  }
}
```

The response contains structured turns and items. `webmux` converts those items into the internal `ChatMessage` format used everywhere else.

After that, the backend merges in overlay events from `chat_event_store`, such as:

- locally persisted tool events
- webhook-style additions
- file attachment events

This is why Codex direct chats can now reload consistently without depending on the terminal log parser.

## Live Streaming

The app-server client listens to Codex notifications and maps them to the existing websocket chat stream.

Important notification mappings:

- `item/agentMessage/delta` -> assistant text chunk
- `item/reasoning/summaryTextDelta` -> thinking chunk
- `item/reasoning/textDelta` -> thinking chunk
- `item/started` -> tool call started
- `item/completed` -> tool result completed
- `turn/completed` -> prompt finished

Those are converted into existing websocket messages such as:

- `acp-message-chunk`
- `acp-tool-call`
- `acp-tool-result`
- `acp-prompt-done`
- `acp-permission-request`

This is why the Android chat UI did not need a new rendering system for Codex. It already knew how to render that message family.

## Tool Mapping

Codex thread items are normalized into the same conceptual UI primitives used for other direct backends:

- assistant text
- assistant thinking
- tool call
- tool result

Examples of Codex item types handled by the history/live mapping:

- command execution
- file changes
- MCP tool calls
- dynamic tool calls
- web search
- reasoning
- agent messages
- user messages

That lets the chat UI show Codex sessions with the same kind of message bubble structure already used for OpenCode, Claude, Kiro, and tmux-parsed Codex logs.

## Approval Handling

Codex app-server can ask the client for approval. `webmux` supports three approval request families:

- command execution approval
- file change approval
- permissions approval

When Codex sends one of those requests, the backend:

1. stores the pending request in a map
2. creates an external request ID like `codex:1`
3. sends `acp-permission-request` to Android

Android responds with `acp-respond-permission`.

The backend then writes the correct app-server response envelope back to Codex. For example:

- command execution -> `{ "decision": "accept" | "decline" }`
- file change -> `{ "decision": "accept" | "decline" }`
- permissions -> `{ "permissions": {...}, "scope": "turn" }`

This keeps the mobile UI generic while still matching the Codex protocol shape.

## Chat State Matching Rules

One subtle part of the integration is that different layers refer to the same chat using slightly different names:

- backend live events use provider-prefixed external IDs like `codex:thread_123`
- the chat screen stores local state as `acp_codex:thread_123`
- split-screen and reopen flows may sometimes use raw external IDs

To avoid broken updates, both Android and Flutter now use helper logic that accepts:

- exact state session name
- raw provider-prefixed direct ID
- `acp_`-prefixed local key

Without that matching logic, live chunks and tool results can arrive but be ignored by the client because the session names do not match exactly.

## Difference From Tmux Codex Parsing

There are now two different Codex paths in `webmux`:

1. tmux/rollout parsing
2. direct app-server sessions

They solve different problems.

Tmux/rollout parsing is for:

- existing Codex terminal sessions
- passive history extraction from rollout files
- usage/context extraction from Codex logs

App-server direct sessions are for:

- first-class direct chats created from the app
- structured history from `thread/read`
- live notifications over stdio
- explicit approval requests

The direct app-server path does not depend on the rollout parser for its core transcript.

## Current Limitations

These direct-session actions are still not implemented for Codex:

- prompt cancellation
- model switching
- mode switching

The backend currently returns explicit errors for those actions on the Codex provider path.

Other operational constraints:

- the backend host must have the `codex` CLI installed
- `codex` must be available on `PATH`
- the backend process owns the child `codex app-server` process
- this path is backend-driven; Android does not launch Codex itself

## Why `thread/read` Is Preferred Over Rollout Parsing For Direct Sessions

For app-created direct sessions, `thread/read` is the authoritative source because it is:

- structured
- provider-native
- independent of terminal rendering quirks
- better for tool event fidelity
- better for exact session/thread identity

Rollout parsing is still useful for tmux-backed Codex sessions and for usage/context extraction, but it is the wrong primary source for a direct app-server transcript.

## Debugging Checklist

If Codex direct chat creation fails, check these first:

1. `codex` is installed on the backend host
2. `codex app-server` can be launched manually
3. the backend process can find `codex` on `PATH`
4. the working directory sent from Android exists on the backend host
5. the websocket shows `backend-selected` for `codex` before session creation

If history loads but live chunks do not show up, check:

1. session ID prefix is `codex:`
2. Android stored session name is `acp_codex:...`
3. live websocket events carry the same logical session
4. the client-side session matching helpers were not bypassed

## Relevant Files

Android:

- [android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsScreen.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsScreen.kt)
- [android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/sessions/SessionsViewModel.kt)
- [android-native/app/src/main/java/com/agentshell/data/remote/WebSocketCommands.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/data/remote/WebSocketCommands.kt)
- [android-native/app/src/main/java/com/agentshell/feature/chat/ChatViewModel.kt](/home/cleber_rodrigues/project/webmux/android-native/app/src/main/java/com/agentshell/feature/chat/ChatViewModel.kt)

Backend:

- [backend-rust/src/main.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/main.rs)
- [backend-rust/src/codex_app/client.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/codex_app/client.rs)
- [backend-rust/src/codex_app/mod.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/codex_app/mod.rs)
- [backend-rust/src/websocket/acp_cmds.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/websocket/acp_cmds.rs)
- [backend-rust/src/websocket/chat_cmds.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/websocket/chat_cmds.rs)
- [backend-rust/src/acp/session.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/acp/session.rs)

Related, but separate:

- [backend-rust/src/chat_log/codex_parser.rs](/home/cleber_rodrigues/project/webmux/backend-rust/src/chat_log/codex_parser.rs)

## Summary

The Codex direct integration works by keeping the existing direct-chat websocket surface intact while routing the Codex provider to a dedicated app-server client. Android starts the flow from the Sessions screen, the backend launches `codex app-server`, and history plus live updates are translated into the same UI event shapes already used elsewhere in the app.
