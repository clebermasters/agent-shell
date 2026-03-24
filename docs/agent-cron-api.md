# Agent Cron & Notification API

AI agents use this API to send notifications to users and manage cron jobs on their behalf.

## Authentication

All requests require the `Authorization: Bearer <AUTH_TOKEN>` header. The token is set by the server administrator.

## Base URL

```
http(s)://<host>:<port>
```

Port is typically `4010`.

---

## Notification API

### POST /api/notifications — Send a notification

Send a notification to the user.

**Request body:**

```json
{
  "title": "Task Complete",
  "body": "Your daily report has been generated",
  "source": "agent",
  "sourceDetail": "daily-report-job",
  "files": [
    {
      "filename": "report.html",
      "mimeType": "text/html",
      "data": "<base64-encoded-content>"
    }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | Yes | Notification title |
| `body` | string | Yes | Notification body text |
| `source` | string | Yes | Who sent it: `agent`, `cron`, or `webhook` |
| `sourceDetail` | string | No | Identifier for the source (e.g., job name) |
| `files` | array | No | Attached files with base64-encoded content |

**Response:** `201 Created`

```json
{
  "id": "uuid",
  "received": true
}
```

### GET /api/notifications — List notifications

```
GET /api/notifications?limit=50&before=<timestamp>
```

**Query parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | integer | `50` | Max notifications to return |
| `before` | timestamp | none | Return notifications before this Unix timestamp |

**Response:** `200 OK`

```json
{
  "notifications": [
    {
      "id": "uuid",
      "title": "Task Complete",
      "body": "Your daily report has been generated",
      "source": "agent",
      "sourceDetail": "daily-report-job",
      "read": false,
      "createdAt": 1710000000,
      "files": [...]
    }
  ],
  "hasMore": false
}
```

### POST /api/notifications/:id/read — Mark as read

Mark a notification as read.

**Response:** `200 OK`

```json
{
  "success": true
}
```

### GET /api/chat/files/:id — Download a file

Download a file attached to a notification.

**Response:** Binary file data with appropriate `Content-Type` header.

---

## Cron Job API

### POST /api/cron/jobs — Create a cron job

Create a new scheduled job.

**Request body:**

```json
{
  "name": "Daily AI News",
  "workdir": "/home/user/reports",
  "prompt": "Search for the latest AI news and write an HTML report",
  "llmProvider": "openai",
  "llmModel": "claude-sonnet-4-6",
  "schedule": "0 8 * * *",
  "enabled": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Display name for the job |
| `workdir` | string | Yes | Working directory for execution |
| `prompt` | string | Yes | Prompt sent to the AI agent |
| `llmProvider` | string | Yes | LLM provider to use |
| `llmModel` | string | Yes | LLM model to use |
| `schedule` | string | Yes | Cron expression (5 fields) |
| `enabled` | boolean | Yes | Whether the job is active |

The backend constructs the following command:

```bash
cd {workdir} && skill-agent --streaming --llm-provider {llmProvider} --llm-model {llmModel} agent "{prompt}"
```

**Response:** `201 Created`

```json
{
  "id": "uuid",
  "name": "Daily AI News",
  "workdir": "/home/user/reports",
  "prompt": "Search for the latest AI news and write an HTML report",
  "llmProvider": "openai",
  "llmModel": "claude-sonnet-4-6",
  "schedule": "0 8 * * *",
  "enabled": true,
  "createdAt": 1710000000
}
```

### GET /api/cron/jobs — List all jobs

**Response:** `200 OK`

```json
{
  "jobs": [
    {
      "id": "uuid",
      "name": "Daily AI News",
      "workdir": "/home/user/reports",
      "prompt": "Search for the latest AI news",
      "llmProvider": "openai",
      "llmModel": "claude-sonnet-4-6",
      "schedule": "0 8 * * *",
      "enabled": true,
      "createdAt": 1710000000
    }
  ]
}
```

### GET /api/cron/jobs/:id — Get a job

**Response:** `200 OK` — Full CronJob object

### PUT /api/cron/jobs/:id — Update a job

Update an existing job. Same body as create.

**Response:** `200 OK` — Full updated CronJob object

### DELETE /api/cron/jobs/:id — Delete a job

**Response:** `204 No Content`

### POST /api/cron/jobs/:id/toggle — Enable/disable a job

Toggle whether a job is active.

**Response:** `200 OK`

```json
{
  "id": "uuid",
  "enabled": false
}
```

### POST /api/cron/jobs/:id/test — Dry-run test

Test what would be executed without running the job.

**Response:** `200 OK`

```json
{
  "command": "cd /home/user/reports && skill-agent --streaming --llm-provider openai --llm-model claude-sonnet-4-6 agent \"Search for the latest AI news and write an HTML report\"",
  "job": { ... }
}
```

---

## Available LLM Providers and Models

| Provider | Model | Auth |
|----------|-------|------|
| `openai` | `claude-opus-4-6`, `claude-sonnet-4-6` | Via localhost wrapper |
| `minimax` | `MiniMax-M2.5` | `MINIMAX_API_KEY` env var |
| `bedrock` | `amazon.nova-pro-v1:0`, etc | AWS credentials |
| `ollama` | Any local model | `OLLAMA_URL` env var |

---

## When to Send Notifications

Send a notification when:

- A cron job completes successfully
- A task the user asked about is done
- An error occurs that the user should know about
- Significant progress is made on a long-running task

Do NOT send notifications for:

- Minor intermediate steps
- Routine operations the user doesn't care about
- Debug/internal state changes

---

## User Control

Users can:

- View, edit, delete, and toggle cron jobs from the Flutter UI
- Receive push notifications on their device
- Mark notifications as read from the app
