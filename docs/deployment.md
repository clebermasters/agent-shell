# Deployment Guide

This guide explains how to expose AgentShell to the internet using Cloudflare and Nginx as a reverse proxy.

## Architecture Overview

```
┌──────────┐       ┌─────────────┐       ┌──────────────┐       ┌──────────────────┐
│  Client   │──────►│  Cloudflare │──────►│    Nginx      │──────►│  AgentShell      │
│  (App)    │ HTTPS │  (CDN/WAF)  │ HTTPS │  (Reverse     │  HTTP │  Backend (Rust)  │
│           │◄──────│             │◄──────│   Proxy)      │◄──────│  :4010           │
└──────────┘  wss   └─────────────┘  wss  └──────────────┘  ws   └──────────────────┘
```

**Traffic flow:**
1. Client connects via `wss://your-domain.com/ws?token=AUTH_TOKEN`
2. Cloudflare terminates TLS and proxies to your Nginx server
3. Nginx upgrades the connection to WebSocket and forwards to the backend on port 4010
4. The Rust backend validates the token and serves the request

## Prerequisites

- A domain managed by Cloudflare (e.g., `your-domain.com`)
- A server with a public IP (or Cloudflare Tunnel)
- Nginx installed on the server
- AgentShell backend installed via `install.sh`

## Step 1: Backend Setup

Install and start the backend with authentication:

```bash
# 1. Set your auth token in .env
echo "AUTH_TOKEN=your-strong-secret-here" >> .env

# 2. Install the backend (builds, creates systemd service, starts it)
sudo ./install.sh

# 3. Verify it's running
sudo systemctl status agentshell

# 4. Check logs — should say "AUTH_TOKEN is set"
sudo journalctl -u agentshell -n 10
```

The backend listens on `http://0.0.0.0:4010` (HTTP only). TLS is handled by Cloudflare + Nginx.

## Step 2: Nginx Reverse Proxy

Create an Nginx config to proxy traffic to the backend. This handles both regular HTTP requests and WebSocket upgrades.

### `/etc/nginx/sites-available/agentshell`

```nginx
server {
    listen 80;
    server_name agent-backend.your-domain.com;

    location / {
        proxy_pass http://127.0.0.1:4010;
        proxy_http_version 1.1;

        # WebSocket support — required for /ws endpoint
        proxy_set_header Upgrade websocket;
        proxy_set_header Connection upgrade;

        # Forward client info
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts — keep WebSocket connections alive
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

> **Note:** The `Upgrade` header is hardcoded to `websocket` instead of using `$http_upgrade`. This avoids issues where Cloudflare strips the original `Upgrade` header before forwarding to your origin server.

Enable and restart:

```bash
sudo ln -s /etc/nginx/sites-available/agentshell /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### Why not use `$http_upgrade`?

When Cloudflare proxies a WebSocket connection to your origin, it may strip the original `Upgrade: websocket` header. If your Nginx config uses the common pattern:

```nginx
# This can FAIL behind Cloudflare:
proxy_set_header Upgrade $http_upgrade;
```

The `$http_upgrade` variable will be empty, and the WebSocket handshake will fail silently — the backend never sees the upgrade request. Hardcoding `websocket` ensures it always works.

## Step 3: Cloudflare Configuration

### DNS Record

Create an `A` record pointing to your server:

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| A | `agent-backend` | `YOUR_SERVER_IP` | Proxied (orange cloud) |

> Keep the orange cloud (Proxied) enabled. This routes traffic through Cloudflare's network, providing TLS, DDoS protection, and caching.

### Enable WebSockets

WebSocket support must be enabled in Cloudflare:

1. Go to your domain in the Cloudflare dashboard
2. Navigate to **Network**
3. Enable **WebSockets**

Without this, Cloudflare will reject all WebSocket upgrade requests.

### SSL/TLS Mode

Set the SSL/TLS encryption mode:

1. Go to **SSL/TLS** > **Overview**
2. Set mode to **Flexible** (Cloudflare → Nginx over HTTP) or **Full** if you have certs on Nginx

**Flexible** is the simplest setup since the backend only serves HTTP. Cloudflare handles TLS termination for the client.

```
Client ──HTTPS──► Cloudflare ──HTTP──► Nginx ──HTTP──► Backend :4010
```

## Step 4: Flutter Web Deployment (Optional)

If you want to serve the Flutter web app from a CDN:

### S3 + Cloudflare

```bash
# Build the web app with token baked in
./flutter/build.sh web

# Deploy to S3 and purge Cloudflare cache
./scripts/deploy-web.sh
```

The deploy script:
1. Uploads entry-point files (`index.html`, `flutter_service_worker.js`) with `no-cache` headers
2. Syncs hashed assets with 1-year cache (`immutable`)
3. Purges Cloudflare cache for critical files

Configure these in `.env`:

```env
S3_WEB_BUCKET=s3://your-bucket-name
CF_API_KEY=your-cloudflare-api-key
CF_EMAIL=your-cloudflare-email
CF_DOMAIN=agent.your-domain.com
```

## Step 5: Build Clients with Token

All Flutter clients must be rebuilt after setting `AUTH_TOKEN` so the token gets embedded:

```bash
# Android APK
./flutter/build.sh release

# Web
./flutter/build.sh web

# Linux native
./flutter/build.sh linux
```

> **Docker caching:** If you previously built without a token, Docker may serve a cached layer. The build script passes `BUILD_TIMESTAMP` as an ARG to bust the cache, but if in doubt use `docker build --no-cache`.

## Verifying the Setup

### Test without token (should fail)

```bash
# WebSocket — expect HTTP 401
python3 scripts/test-ws.py agent-backend.your-domain.com

# API — expect 401
curl -s -o /dev/null -w "%{http_code}" https://agent-backend.your-domain.com/api/clients
```

### Test with token (should succeed)

```bash
# WebSocket
python3 -c "
import asyncio, json, websockets
async def t():
    async with websockets.connect('wss://agent-backend.your-domain.com/ws?token=YOUR_TOKEN') as ws:
        await ws.send(json.dumps({'type': 'list-sessions'}))
        print(json.loads(await asyncio.wait_for(ws.recv(), timeout=5)))
asyncio.run(t())
"

# API
curl -s "https://agent-backend.your-domain.com/api/clients?token=YOUR_TOKEN"
```

### Check backend logs

```bash
sudo journalctl -u agentshell -f
```

You should see:
- `AUTH_TOKEN is set — all API/WebSocket requests require valid token` at startup
- `Rejected request with missing auth token: GET /ws` for unauthorized attempts
- `WebSocket upgrade request received` for successful connections

## Troubleshooting

### WebSocket connects but immediately closes

- **Cause:** Cloudflare WebSockets not enabled
- **Fix:** Cloudflare dashboard > Network > Enable WebSockets

### HTTP 502 Bad Gateway

- **Cause:** Nginx can't reach the backend
- **Fix:** Check `sudo systemctl status agentshell` and ensure it's running on port 4010

### Sessions list is empty

- **Cause:** Backend running as wrong user (e.g., `root` instead of your user)
- **Fix:** Re-run `sudo ./install.sh` — it uses `SUDO_USER` to run the service as your user

### Flutter app connects but gets 401

- **Cause:** App was built without `AUTH_TOKEN` in `.env`
- **Fix:** Add token to `.env`, rebuild the app, and reinstall

### Mixed content error on web

- **Cause:** Web app trying `ws://` on an HTTPS page
- **Fix:** Already handled — the Flutter client auto-upgrades `ws://` to `wss://` on web
