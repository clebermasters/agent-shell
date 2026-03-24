# AI Task Notifications Feed — Implementation Summary

> **Date:** March 24, 2026  
> **Feature:** Notification Feed for AI Agent Task Completions

---

## Goal

Enable AI agents running via cron jobs to send notifications to users through a dedicated alerts screen in the Flutter app. Users receive real-time push notifications when tasks complete, and can view a persistent notification feed accessible from the home screen.

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

---

## What Was Built

### Backend Changes

| Component | File | Description |
|----------|------|-------------|
| Notification model | `backend-rust/src/notification.rs` | `Notification`, `NotificationFile`, `CreateNotificationRequest` structs |
| NotificationStore | `backend-rust/src/notification_store.rs` | SQLite-backed storage for notifications and files |
| REST handlers | `backend-rust/src/notification_handler.rs` | Endpoints for create, list, mark-read, serve-files |
| Cron REST handlers | `backend-rust/src/cron_handler.rs` | Full CRUD for cron jobs via REST API |
| WebSocket event | `backend-rust/src/types/mod.rs` | Added `NotificationEvent` to `ServerMessage` |
| Agent documentation | `docs/agent-cron-api.md` | API docs for AI agents |

**REST API Endpoints Added:**

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

### Flutter Changes

| Component | File | Description |
|----------|------|-------------|
| Notification model | `flutter/lib/data/models/notification.dart` | `Notification` and `NotificationFile` classes |
| CronJob AI fields | `flutter/lib/data/models/cron_job.dart` | Added `workdir`, `prompt`, `llmProvider`, `llmModel` |
| WebSocket handler | `flutter/lib/data/services/websocket_service.dart` | Added `notificationStream` |
| AlertsProvider | `flutter/lib/features/alerts/providers/alerts_provider.dart` | StateNotifier for notifications |
| AlertsScreen | `flutter/lib/features/alerts/screens/alerts_screen.dart` | Scrollable notification feed UI |
| NotificationCard | `flutter/lib/features/alerts/widgets/notification_card.dart` | Individual notification card |
| Home bell icon | `flutter/lib/features/home/screens/home_screen.dart` | Notification bell with unread badge |

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

## Files Created/Modified

### Backend (11 files)
- `backend-rust/Cargo.toml` — added reqwest dependency
- `backend-rust/src/notification.rs` — new
- `backend-rust/src/notification_store.rs` — new
- `backend-rust/src/notification_handler.rs` — new
- `backend-rust/src/cron_handler.rs` — new
- `backend-rust/src/main.rs` — modified (AppState, routes)
- `backend-rust/src/types/mod.rs` — added NotificationEvent
- `backend-rust/src/cron/mod.rs` — updated test fixtures
- `backend-rust/src/websocket/cron_cmds.rs` — updated test fixtures
- `docs/agent-cron-api.md` — new

### Flutter (8 files)
- `flutter/lib/data/models/notification.dart` — new
- `flutter/lib/data/models/models.dart` — added export
- `flutter/lib/data/models/cron_job.dart` — added AI fields
- `flutter/lib/data/services/websocket_service.dart` — added notificationStream
- `flutter/lib/features/alerts/providers/alerts_provider.dart` — new
- `flutter/lib/features/alerts/screens/alerts_screen.dart` — new
- `flutter/lib/features/alerts/widgets/notification_card.dart` — new
- `flutter/lib/features/home/screens/home_screen.dart` — added bell icon

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
- Flutter: Code follows existing patterns; verified via static analysis

---

## Git Commits

```
550841f0 - Add AI fields to CronJob model
481149a0 - Add REST API endpoints for notifications
873b2ce4 - Add NotificationStore for SQLite-backed notifications
c0b319fd - Add Notification data model
c7f7beea - Add reqwest to production dependencies for REST API calls
e97d463d - docs: add agent cron & notification API documentation
092d5d00 - Add Notification model for push notification support
9f5c6601 - Add AI fields to CronJob model (Flutter)
bb33b5c1 - feat(websocket): add notification-event stream handler
```

---

## Next Steps

1. **Build and test** — Run Flutter build in Docker container to verify APK
2. **User testing** — Test notification delivery end-to-end
3. **Cron editor update** — Update `cron_job_editor_screen.dart` to include new AI fields (workdir, prompt, llmProvider, llmModel) for creating AI-powered cron jobs
4. **Documentation** — Share `docs/agent-cron-api.md` with AI agent developers
