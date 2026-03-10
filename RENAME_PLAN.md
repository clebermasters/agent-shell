# WebMux → AgentShell Rename Plan

This document outlines all the steps required to rename the project from **WebMux** to **AgentShell**.

---

## Phase 1: Documentation

- [x] Update `README.md`
  - Rename title from "WebMux" to "AgentShell"
  - Update all references to "WebMux" throughout the document
  - Update the architecture diagram (replace WebMux with AgentShell)
  - Update download links and filenames
  - Update git clone URL and directory name

- [x] Update `ANDROID_DEBUG.md`
  - Rename title from "WebMux Android App" to "AgentShell Android App"
  - Update docker build image name: `webmux-android-builder` → `agentshell-android-builder`
  - Update output APK filename: `webmux-flutter-debug.apk` → `agentshell-flutter-debug.apk`
  - Update S3 upload path

---

## Phase 2: Package Configuration

- [x] Update `package.json`
  - Change `name` from "webmux" to "agentshell"

- [x] Update `backend-rust/Cargo.toml`
  - Change `name` from "webmux-backend" to "agentshell-backend"

- [x] Update `capacitor.config.ts`
  - Change `appId` from "com.webmux.app" to "com.agentshell.app"
  - Change `appName` from "WebMux" to "AgentShell"

- [x] Update `Dockerfile`
  - Update base image name references
  - Update comments

---

## Phase 3: Android App

- [ ] Update `android/app/build.gradle`
  - Change `namespace` from "com.webmux.app" to "com.agentshell.app"
  - Change `applicationId` from "com.webmux.app" to "com.agentshell.app"

- [ ] Update `android/app/src/main/java/com/webmux/app/MainActivity.java`
  - Change package from `com.webmux.app` to `com.agentshell.app`
  - Move file to new directory: `com/webmux/app/` → `com/agentshell/app/`

- [ ] Update `android/app/src/main/res/values/strings.xml`
  - Change `app_name` from "WebMux" to "AgentShell"
  - Change `title_activity_main` from "WebMux" to "AgentShell"
  - Change `package_name` from "com.webmux.app" to "com.agentshell.app"
  - Change `custom_url_scheme` from "com.webmux.app" to "com.agentshell.app"

---

## Phase 4: Frontend

- [x] Update `index.html`
  - Change `apple-mobile-web-app-title` from "WebMux" to "AgentShell"
  - Change `<title>` from "WebMux - TMUX Session Manager" to "AgentShell - TMUX Session Manager"

- [x] Update `public/index.html`
  - Change `<title>` from "WebMux - TMUX Session Viewer" to "AgentShell - TMUX Session Viewer"
  - Update `<h1>` header from "WebMux" to "AgentShell"

- [x] Update `generate-pwa-icons.html`
  - Change page title from "WebMux PWA Icon Generator" to "AgentShell PWA Icon Generator"

- [x] Update `generate-icons.js`
  - Update comment: "Simple SVG icon generator for WebMux" → "Simple SVG icon generator for AgentShell"

---

## Phase 5: Backend (Rust)

- [ ] Update `backend-rust/src/main.rs`
  - Replace all log messages mentioning "WebMux" with "AgentShell"
  - Example: `info!("WebMux HTTPS server running on {}", https_addr);`

- [ ] Update `backend-rust/src/websocket/mod.rs`
  - Change session directory: `.webmux` → `.agentshell`
  - Update `WEBMUX_WS_URL` environment variable (consider backward compatibility)
  - Update all `WEBMUX_*` references to `AGENTSHELL_*`
  - Update log messages

- [x] Update `backend-rust/src/chat_log/watcher.rs`
  - Change file pattern: `/tmp/webmux-codex-*.jsonl` → `/tmp/agentshell-codex-*.jsonl`

- [x] Update `backend-rust/src/cron/mod.rs`
  - Change crontab markers: `# WebMux-Job-Start:` → `# AgentShell-Job-Start:`
  - Change crontab markers: `# WebMux-Job-End:` → `# AgentShell-Job-End:`

- [x] Update `backend-rust/src/dotfiles/mod.rs`
  - Change test file: `.webmux_write_test` → `.agentshell_write_test`

---

## Phase 6: Flutter Plugin

- [ ] Rename directory: `flutter/plugins/webmux-plugin/` → `flutter/plugins/agentshell-plugin/`

- [ ] Update `flutter/plugins/agentshell-plugin/webmux.js` (consider renaming to `agentshell.js`)
  - Update file comments
  - Change `SESSION_FILE` from `.webmux/acp_session` to `.agentshell/acp_session`
  - Update all `WEBMUX_*` environment variables to `AGENTSHELL_*`
  - Update console log prefixes: `[webmux-plugin]` → `[agentshell-plugin]`
  - Update plugin export name: `WebMuxPlugin` → `AgentShellPlugin`
  - Update context in system prompt

---

## Phase 7: Build Artifacts

- [ ] Delete `package-lock.json` and regenerate with `npm install`
- [ ] Delete `backend-rust/Cargo.lock` and regenerate with `cargo build`
- [ ] Delete old APK files: `webmux-flutter-release.apk`, `output/app-debug.apk`
- [ ] Rebuild Android app with new package name

---

## Phase 8: Post-Rename Tasks

- [ ] Update git remote URL (if hosting on GitHub)
- [ ] Update any CI/CD pipelines
- [ ] Update Docker Hub / container registry references
- [ ] Update domain/URL if using a custom domain
- [ ] Notify users about environment variable changes (if breaking changes)
- [ ] Consider adding backward compatibility for environment variables:
  - `WEBMUX_WS_URL` → also accept `AGENTSHELL_WS_URL`
  - `WEBMUX_ACP_SESSION_ID` → also accept `AGENTSHELL_ACP_SESSION_ID`
  - etc.

---

## Notes

### Environment Variable Compatibility
The following environment variables are used by external tools (OpenCode). Consider adding backward compatibility:

| Old Variable | New Variable |
|--------------|--------------|
| `WEBMUX_WS_URL` | `AGENTSHELL_WS_URL` |
| `WEBMUX_ACP_SESSION_ID` | `AGENTSHELL_ACP_SESSION_ID` |
| `WEBMUX_ACP_CWD` | `AGENTSHELL_ACP_CWD` |

### Directory Compatibility
Consider creating a symlink or alias:
```bash
ln -s ~/.agentshell ~/.webmux
```

This allows existing session files to continue working.

---

## Estimated Effort

| Phase | Files | Complexity |
|-------|-------|------------|
| Phase 1: Documentation | 2 | Easy |
| Phase 2: Package Config | 4 | Easy |
| Phase 3: Android App | 3 | Medium |
| Phase 4: Frontend | 4 | Easy |
| Phase 5: Backend | 5 | Easy |
| Phase 6: Flutter Plugin | 1 | Medium |
| Phase 7: Build Artifacts | 4 | Easy |

**Total: ~23 files**

---

## Completed ✅

- Phase 1: Documentation (2 files)
- Phase 2: Package Configuration (4 files)
- Phase 4: Frontend (4 files)
- Phase 5: Backend (5 files) - partial (main.rs and websocket/mod.rs pending)