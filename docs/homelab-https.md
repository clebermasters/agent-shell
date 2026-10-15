# Homelab AgentShell HTTPS deployment

The homelab endpoint is `https://homelab-agent.bitslovers.com` (port 443).
Its path is Cloudflare HTTPS → encrypted cloudflared tunnel → Nginx on
`127.0.0.1:18080` → AgentShell on `127.0.0.1:4010`. Existing LAN access on port
4010 is retained. The loopback listener keeps this endpoint separate from the
existing public virtual hosts on port 80, including `agent-backend.bitslovers.com`.

The tunnel provides an encrypted origin connection without changing the zone's
existing SSL mode or the other Nginx virtual hosts. Configuration references:

- `deployment/templates/agentshell-nginx.conf`
- `deployment/templates/agentshell-cloudflared.yml`
- `deployment/templates/agentshell-cloudflared.service`
- `deployment/network-dependencies.json` (download and SHA-256 pin)

Installed on the homelab:

- Nginx: `/etc/nginx/conf.d/agentshell-homelab.conf`
- Tunnel service: `agentshell-cloudflare.service`
- Private tunnel configuration: `~/.config/agentshell/cloudflare/`
- Browser configuration: `~/.config/agentshell/browser.json`

Tunnel credentials stay in the private directory, mode 600; they are not part
of the project. The browser delegates profile selection to Chrome and starts
when requested from Android. Installation does not start a second desktop
Chrome or force a profile directory name.

In Android, add/select `homelab-agent.bitslovers.com:443` with HTTPS enabled and
the existing homelab authentication token. Open Browser and tap Start browser.

## Nginx backup

The complete configuration tree was archived privately on the homelab:

```text
/home/cleber_rodrigues/.local/state/agentshell/installations/20261012-browser-update/nginx-configs-20261013T013408Z.tar.gz
```

SHA-256: `58b0f49d63e343b63d8fc9df409e93de0a6060583cc8c8d0cf0fef4c43d6e8fa`.
The archive contains 44 entries and has mode 600. It includes the newly added
AgentShell virtual host; existing configurations had not been edited.

Before restoring, review the archive and preserve any subsequent changes.
Validate with `sudo nginx -t` before reloading Nginx. To disable only this
endpoint, stop `agentshell-cloudflare.service` and remove only its virtual-host
file; the existing sites and LAN daemon remain separate.

## Validation on 2026-10-13 UTC

Nginx configuration/ingress validation, authenticated public HTTPS API and WSS
stats checks passed. Unauthenticated API access returned 401. The daemon token
and all four database files were preserved. Public DNS-over-HTTPS returned the
new hostname; the local network resolver still returned no record, including
after its local cache was refreshed. Public HTTPS was verified with explicit
Cloudflare address resolution while preserving normal hostname/TLS validation.

The local/main browser retained its original running process and runtime
generation. The homelab browser was configured but left stopped to avoid
unnecessary CPU use until Android requests it.

## Investigation of the existing main proxy's latency

`agent-backend.bitslovers.com` continues to use the existing Nginx virtual host
on homelab's port 80, forwarding to `192.168.0.76:4010`. It does not use the new
`agentshell-cloudflare` tunnel. Comparison with the configuration backup found
29 files unchanged; only the newly added homelab virtual host had changed.

The added virtual host initially shared port 80. Because its filename sorts
before the original AgentShell virtual host, it could change the default host
for requests without a matching Host header. It was moved to the dedicated
loopback listener `127.0.0.1:18080`, with the tunnel ingress changed to match.
This restores the original port-80 default behavior. It is a configuration
correction, not a demonstrated cause of the reported latency.

Measured on 2026-10-13 UTC:

| Probe | Result |
| --- | --- |
| Local/main daemon, loopback HTTP | Approximately 1 ms |
| Homelab's existing port-80 proxy to the main daemon | Approximately 6–12 ms |
| Main public API from this host, IPv4 | Approximately 90–120 ms |
| Main public API from this host, IPv6 | Intermittently 1–3 seconds |
| Main public WSS, IPv4 | 118 ms to connect; approximately 19 ms ping round trips |
| Main public WSS, IPv6 | 484 ms to connect in one probe; ping round trips 18–131 ms |

Stopping and restarting only the added cloudflared service did not materially
change the existing Nginx proxy's latency. Its steady CPU use was approximately
0.1% during that comparison. The managed homelab browser was not running.

The delay was also reproduced with `https://agent-backend.bitslovers.com/cdn-cgi/trace`
and the equivalent endpoint on `ssh.bitslovers.com`: IPv4 responses took about
65–120 ms, while IPv6 responses sometimes took over 2 seconds, including delayed
TLS handshakes. These Cloudflare responses bypass the homelab origin and VNC.
Both reported the ATL edge location. Cloudflare's own `www.cloudflare.com`
endpoint, reporting BNA, responded in about 100 ms over either address family.
IPv6 was also occasionally fast, so this is an intermittent public-path issue,
not an established blanket failure of IPv6.

The measurements locate a delay before the homelab origin, but do not identify
whether the underlying fault belongs to the ISP/router path or Cloudflare's
edge path. They do not prove the cause of every reported browser slowdown.
For a repeat incident, compare IPv4 and IPv6 on the same public hostname and
Cloudflare-only endpoint, then compare the LAN proxy. Record the connection,
TLS, and first-byte times separately. Compare the same address and display size
with the original browser setup before drawing a before/after conclusion.

### Browser-stream encryption

The Android-facing browser stream uses HTTPS/WSS. Inside the backend host,
`browser.rs` relays to `ws://127.0.0.1:<port>/websockify`; the managed Xvnc process
binds to loopback and uses `SecurityTypes None`. There is no additional VNC TLS
layer on that local connection. The new encrypted cloudflared tunnel belongs
only to `homelab-agent.bitslovers.com`; the main public hostname retains its
existing route and the zone's existing Flexible SSL setting.

The [Nginx WebSocket guide](https://nginx.org/en/docs/http/websocket.html) and
[Cloudflare tunnel configuration guide](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/local-management/configuration-file/)
describe the upstream headers and tunnel ingress used here.
