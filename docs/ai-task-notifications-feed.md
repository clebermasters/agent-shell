# AI Task Notifications Feed

## Goal

Enable AI agents running via cron jobs to send notifications to users through a dedicated alerts screen in the Flutter app. Users receive real-time push notifications when tasks complete, and can view a persistent notification feed accessible from the home screen.

**Key Requirements:**
- AI agents can create/modify/delete cron jobs via REST API (on behalf of the user)
- AI agents can send notifications when they decide something is worth notifying
- Users receive push notifications even when app is closed/phone is off
- Users can view a scrollable feed of all past notifications
- Notifications can include file attachments (generated reports, etc.)
- Per-server notification scope (notifications tied to the backend you're connected to)

---

## Architecture

```
System Cron → skill-agent (prompt + workdir + llm config) 
    → AI agent executes task 
    → AI calls POST /api/notifications 
    → Backend stores in SQLite 
    → WebSocket pushes to connected clients 
    → Local push notification fires
```

### Data Flow

```
AI Agent                    Backend                     Flutter App
   |                           |                              |
   |--POST /api/notifications->|                              |
   |                           |--store in SQLite----------->|
   |                           |--WS: notification-event----->|
   |                           |--FCM/local push----------->|
   |                           |<--GET /api/notifications---|
   |                           |---[notification list]----->|
```

---

## What Was Built

### Backend

| Component | File | Description |
|----------|------|-------------|
| Notification model | `backend-rust/src/notification.rs` | `Notification`, `NotificationFile`, `CreateNotificationRequest` structs |
| NotificationStore | `backend-rust/src/notification_store.rs` | SQLite-backed storage for notifications and files |
| REST handlers | `backend-rust/src/notification_handler.rs` | Endpoints for create, list, mark-read |
| Cron REST handlers | `backend-rust/src/cron_handler.rs` | Full CRUD for cron jobs via REST API |
| WebSocket event | `backend-rust/src/types/mod.rs` | Added `NotificationEvent` to `ServerMessage` |
| Agent documentation | `docs/agent-cron-api.md` | API docs for AI agents |

**REST API Endpoints:**

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/api/notifications` | List notifications (paginated) |
| POST | `/api/notifications` | Create notification |
| POST | `/api/notifications/:id/read` | Mark as read |
| GET | `/api/notifications/files/:id` | Download attachment |
| GET | `/api/cron/jobs` | List cron jobs |
| POST | `/api/cron/jobs` | Create cron job |
| GET | `/api/cron/jobs/:id` | Get specific job |
| PUT | `/api/cron/jobs/:id` | Update job |
| DELETE | `/api/cron/jobs/:id` | Delete job |
| POST | `/api/cron/jobs/:id/toggle` | Enable/disable |
| POST | `/api/cron/jobs/:id/test` | Dry-run test |

### Flutter

| Component | File | Description |
|----------|------|-------------|
| Notification model | `flutter/lib/data/models/notification.dart` | `Notification` and `NotificationFile` classes |
| CronJob AI fields | `flutter/lib/data/models/cron_job.dart` | Added `workdir`, `prompt`, `llmProvider`, `llmModel` |
| WebSocket handler | `flutter/lib/data/services/websocket_service.dart` | Added `notificationStream` |
| AlertsProvider | `flutter/lib/features/alerts/providers/alerts_provider.dart` | StateNotifier for notifications |
| AlertsScreen | `flutter/lib/features/alerts/screens/alerts_screen.dart` | Scrollable notification feed UI |
| NotificationCard | `flutter/lib/features/alerts/widgets/notification_card.dart` | Individual notification card |
| Home bell icon | `flutter/lib/features/home/screens/home_screen.dart` | Notification bell with unread badge |
| Cron editor AI fields | `flutter/lib/features/cron/screens/cron_job_editor_screen.dart` | AI Options section |

---

## Cron Job AI Fields

The `CronJob` model was extended with AI-specific fields:

```json
{
  "name": "Daily AI News Report",
  "workdir": "/home/user/reports",
  "prompt": "Search for the latest AI news and write an HTML report",
  "llmProvider": "openai",
  "llmModel": "claude-sonnet-4-6",
  "schedule": "0 8 * * *",
  "enabled": true
}
```

The backend constructs: `cd {workdir} && skill-agent --streaming --llm-provider {llmProvider} --llm-model {llmModel} agent "{prompt}"`

---

## Key Design Decisions

| Decision | Choice |
|----------|--------|
| Notification trigger | AI agent calls REST endpoint |
| Notification decision | AI agent decides when to send |
| Feed location | Dedicated Alerts screen from Home |
| Background handling | Push notification + persistent feed |
| Scope | Per-server (tied to connected backend) |
| File handling | Backend stores, Flutter downloads on demand |
| Persistence | SQLite on backend |
| Real-time delivery | WebSocket push + polling on connect |

---

## How AI Agents Use This

### Authentication
All requests require `Authorization: Bearer <AUTH_TOKEN>` header.

### Sending a Notification

```bash
curl -X POST http://localhost:4010/api/notifications \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{
    "title": "Daily Report Complete",
    "body": "AI news report generated successfully",
    "source": "cron",
    "sourceDetail": "daily-ai-news-job",
    "files": [
      {"filename": "report.html", "mimeType": "text/html", "data": "<base64>"}
    ]
  }'
```

### Creating a Cron Job

```bash
curl -X POST http://localhost:4010/api/cron/jobs \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{
    "name": "Daily AI News",
    "workdir": "/home/user/reports",
    "prompt": "Search for the latest AI news and write an HTML report",
    "llmProvider": "openai",
    "llmModel": "claude-sonnet-4-6",
    "schedule": "0 8 * * *",
    "enabled": true
  }'
```

### When to Send Notifications
- When a cron job completes successfully
- When a task the user asked about is done
- When an error occurs that the user should know about
- When significant progress is made on a long-running task
- Do NOT send notifications for minor intermediate steps

---

## How Users Access

1. **Notification Bell** - Top-right of home screen header
   - Shows unread count badge
   - Tap to open Alerts screen

2. **Alerts Screen** - Scrollable feed of all notifications
   - Pull-to-refresh
   - Tap notification to expand
   - Mark as read on tap
   - "Mark all read" button

---

## Out of Scope (Not Implemented)

- Notification email/SMS delivery
- Notification templates or customization
- Scheduled digest (daily/weekly summary)
- Notification categories or filters
- Two-way notification interaction (marking read from push notification)
- Cron job result parsing from log files
- Cron job execution (relies on system cron + skill-agent)

---

## Testing

- Backend: `cargo test` — 421 tests passing
- Backend: `cargo check` — compiles successfully

---

## Git Branch

`feature/notifications-feed` at `~/.config/superpowers/worktrees/webmux-notifications`

---

## Next Steps

1. **Build and test** — Run Flutter build in Docker container to verify APK
2. **User testing** — Test notification delivery end-to-end
3. **Documentation** — Share `docs/agent-cron-api.md` with AI agent developers
