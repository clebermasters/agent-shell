# Android Native — Missing Features vs Flutter

Tracking file for features not yet implemented in the native Android app compared to the Flutter reference.

## Completed This Session

- [x] Fix thinking blocks not displayed (`.env` `SHOW_THINKING=true`)
- [x] File copy/cut/paste operations (backend + Android full stack)

## Previously Completed

- [x] Chat file upload (file picker, preview, Base64, WebSocket send)
- [x] Chat file blocks (images, audio, files — load/play/download)
- [x] Terminal scroll without keyboard toggle
- [x] Hide tool call bubbles when setting disabled
- [x] Back button exits fullscreen terminal
- [x] Cancel voice recording button
- [x] BuildConfig defaults from .env (API key, show thinking, show tool calls)

## Still Missing

### High Priority

- [ ] **Cold-start notification tap** — Flutter handles `notificationResponse.payload` to open specific alert/chat on notification tap from killed state. Android has local notifications but no cold-start routing.

### Medium Priority

- [ ] **Login screen** — Flutter has a functional login screen. Android has `Routes.LOGIN` placeholder only.
- [ ] **Web auth token flow** — Flutter implements web auth token persistence and usage. Android partially supports via preferences but not fully wired.

### Low Priority / Polish

- [ ] **Chat file type icons** — Android uses generic `InsertDriveFile` icon in chat file block preview. Flutter uses extension-specific icons (image, audio, pdf, doc, spreadsheet, archive).
- [ ] **Audio alert viewer** — Android has basic AudioViewer. Flutter's `AlertAudioSheet` may have richer playback controls.
- [ ] **Bell notification badge** — Flutter has dedicated `AlertsBellButton` widget with unread count. Android integrates alerts in-app only.

## Feature Parity Achieved

- Chat: text, thinking, tool calls, tool results, images, audio, files
- Terminal: accessory bar, modifiers, fullscreen, selection, voice, scroll
- Sessions: list, create, rename, delete, multiple windows
- File browser: browse, rename, delete, multi-select, copy, cut, paste, search, sort, hidden files
- Settings: API key, voice, thinking, tool calls, font size, host selection
- System monitoring: CPU, memory, disk, uptime, hostname
- Cron jobs: list, create, edit, delete
- Dotfiles: list, edit, create, delete, templates
- Alerts: image, audio, markdown, HTML, text viewers
- Navigation: all major routes
