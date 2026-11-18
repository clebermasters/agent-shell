# Files from an agent to the phone

An agent can publish a requested image, audio clip, document or other local file
directly into its current AgentShell chat. No specially named TMUX session is
required. The Android app renders the existing attachment cards and applies its
normal unread-reply notification settings.

## Send a file

The `agentshell-ui` MCP server provides **send_file_to_chat**:

```json
{
  "path": "/absolute/path/to/chart.png",
  "description": "The chart you requested"
}
```

Use it when the user requests file delivery. For shell-based agents, the same
publisher is available through the maintained helper:

```bash
python3 scripts/agentshell-send-file.py /path/to/chart.png \
  --prompt "The chart you requested"
```

Installation copies the helper beside the MCP adapter at
`/opt/agentshell/ui/agentshell-send-file.py`. The existing
`agentshell-chat-files` skill's `send_to_chat.py` wrapper delegates to this helper.
`auto`, `current`, and `tmux` modes all select the verified calling conversation.
There is no named fallback and `WEBMUX_TMUX_SESSION` does not select a chat.

The private publisher configuration is
`~/.config/agentshell/interactive-ui.json`, shared with interactive views.
`AGENTSHELL_UI_CONFIG`, `AGENTSHELL_UI_URL`, `AGENTSHELL_UI_TOKEN`,
`AGENTSHELL_AUTH_TOKEN`, and `AUTH_TOKEN` provide supported overrides. Credentials
belong in the private configuration or environment, never in a command argument
or documentation example.

Optional `--session-name` and `--window-index` values assert the expected
destination. They do not redirect a publication into a different agent's chat.
The helper rejects obsolete ACP/input modes; sending a file **to an agent** is a
separate operation in the app.

## How the destination is identified

1. The publisher proves the native agent process ID and its process start time.
2. Codex MCP calls supply their per-call thread ID. The CLI supplies its current
   `CODEX_THREAD_ID` or `CODEX_SESSION_ID`. A shared daemon's inherited pane cannot
   override this native identity.
3. Other supported native agents use their exact `TMUX_PANE`, verified against
   the live foreground process and its registered conversation.
4. The backend resolves the conversation binding, then looks up that pane's
   current session and window. A working directory, recent transcript, or fixed
   name is never used to break an ambiguity.
5. After preparing attachment bytes, the backend revalidates the publisher,
   binding, and destination. It stores one **assistant** message in the native
   conversation's history and broadcasts a conversation-bound event.

Nothing is typed into TMUX, and no file reference is submitted back to the agent.
An unrecognized, ambiguous, restarted, or resumed publisher fails explicitly.
Opening the same conversation in multiple possible terminals is also ambiguous.
Session renames and window moves retain the conversation identity; replacing
the pane or resuming another conversation invalidates the prior binding.

## Backend API

`POST /api/chat/publish` requires the existing `X-Auth-Token` authentication. Its
camel-case JSON body contains `agentPid`, `agentStart`, either `codexThreadId` or
`paneId`, optional `text`, and optional `files` entries with `filename`,
`mimeType`, and base64 `data`. At least text or a file is required. Optional
`expectedSessionName` and `expectedWindowIndex` are destination guards.

The response includes `published: true`, `bindingId`, `conversationKey`,
`sessionName`, `windowIndex`, and `fileIds`. The helper validates this full receipt
before reporting success. It confirms saved backend history and the destination;
it does not claim an offline phone has already downloaded the file. Saved files
remain available through the authenticated `GET /api/chat/files/:id` endpoint and
are included when that conversation's history is loaded again.

Content validation errors return HTTP 400, oversized request bodies return 413,
and unavailable or changed publisher identities return 409. The upload limits
are 10 MiB of decoded attachments in total, 64 KiB of text, and a 16 MiB JSON
body. These are upload limits; the Android history cache remains unlimited.

The global Alerts feed at `POST /api/notifications` remains a separate feature
for cron jobs and other general alerts. It has no implicit TMUX chat target.

## Checks

`scripts/test_chat_file_publication.py` runs a disposable backend and private
TMUX server to check arbitrary session names, nonzero windows, two chats sharing
one folder, wrong or stale identities, renames/moves, conversation changes,
attachment rollback, history isolation, daemon restart, and absence of agent
input. It sends no notifications to the user's running backend.

`scripts/test_ui_mcp.py`, `scripts/test_agentshell_send_file.py`, and the Android
binding/repository tests cover receipt validation, native identity forwarding,
renamed aliases, host isolation, and stale binding rejection.
