# WSS Connection Fix — Investigation & Status

## Problem

The Flutter web app at `https://agent.bitslovers.com` was failing to connect to the backend with:

```
Mixed Content: The page at 'https://agent.bitslovers.com/' was loaded over HTTPS,
but attempted to connect to the insecure WebSocket endpoint
'ws://agent-backend.bitslovers.com:443/ws'. This request has been blocked;
this endpoint must be available over WSS.
```

The browser blocks `ws://` connections from HTTPS pages. The app needed to use `wss://`.

---

## Root Causes Found (in order of discovery)

### 1. `host.dart` always generated `ws://`

```dart
// Before
String get wsUrl => 'ws://$address:$port';

// After
String get wsUrl => kIsWeb ? 'wss://$address' : 'ws://$address:$port';
```

On web, the scheme is now `wss://` and the port is omitted (Cloudflare handles 443).

### 2. Backend was not running

The `agentshell` systemd service did not exist on the nginx host (`192.168.0.38`). The backend was actually running on a separate machine at `192.168.0.76:4010`, which nginx was already proxying correctly.

### 3. Cloudflare was stripping the `Upgrade` header

With Cloudflare proxy enabled, WebSocket upgrade requests arrived at nginx without the `Upgrade: websocket` header. nginx forwarded an empty `Upgrade:` to the backend, which rejected it with HTTP 400.

**Fix** — hardcode the header in nginx instead of relying on `$http_upgrade`:

```nginx
proxy_set_header Upgrade websocket;
proxy_set_header Connection upgrade;
```

### 4. Cloudflare proxy was initially disabled

Disabling the Cloudflare proxy (grey cloud) removed TLS termination, making `wss://` impossible since nginx only listens on port 80. Re-enabling the proxy (orange cloud) with WebSockets toggled on in **Network → WebSockets** restored TLS and WebSocket support.

---

## Verification

The WebSocket endpoint is confirmed working end-to-end:

```bash
python3 scripts/test-ws.py
# Connecting to wss://agent-backend.bitslovers.com/ws...
# Connected ✓
# Response: { "type": "sessions-list", "sessions": [...7 sessions...] }
```

---

## Remaining Issue

The Flutter web app in the browser **still sends `ws://`** despite the fix in `host.dart`. The compiled `main.dart.js` in S3 is from before the fix (timestamp `2026-03-12 16:26 UTC`). The browser service worker is aggressively caching the old file.

### What has been tried

| Attempt | Result |
|---|---|
| `aws s3 sync --delete` | Skipped `main.dart.js` (content hash matched old file) |
| `aws s3 sync --exact-timestamps` | Same — local build timestamp also 11:26 |
| Force `aws s3 cp main.dart.js` | Uploaded, but compiled output is byte-for-byte identical |
| Upload new `flutter_service_worker.js` with `no-cache` headers | Browser still activates old SW |

### Why the compiled output is identical

`kIsWeb` is a compile-time constant (`true`) on web builds. The dart2js compiler tree-shakes the `else` branch entirely. However, `websocket_service.dart` already had an upgrade fallback:

```dart
if (kIsWeb && url.startsWith('ws://')) {
  url = 'wss://${url.substring(5)}';
}
```

This means the compiled JS already upgrades `ws://` → `wss://` at runtime — **but only if the new build is actually loaded**. The service worker is preventing that.

### Next steps to unblock

The user must force the browser to drop the cached service worker:

1. Open `https://agent.bitslovers.com` in Chrome
2. DevTools (F12) → **Application** → **Service Workers**
3. Click **Unregister** for `agent.bitslovers.com`
4. Hard refresh: **Ctrl + Shift + R**

Or open the site once in **incognito** — if it still fails there, the issue is the compiled JS itself, not the cache.
