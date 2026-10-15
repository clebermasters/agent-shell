# Direct LAN access for Android and Chrome streaming

Select **LOCAL_LAN** from the Android main-header server selector. On this host it connects to `https://agentshell-lan.bitslovers.com:4443`, whose DNS-only A record points directly to `192.168.0.76`. Browser frames, keyboard input, API requests and terminal WebSockets stay on the LAN. The homelab reverse proxy and Cloudflare HTTP proxy are not in this path. DNS resolution and certificate issuance/renewal use Internet services.

The Android release uses normal system certificate trust and hostname verification. No certificate-warning bypass, custom CA installation or HTTP viewer exception is needed. This endpoint uses a hostname because publicly trusted certificates cannot identify a private RFC1918 address. The hostname resolves to that private address and is reachable only from the LAN or a routed VPN.

## Prepare a new host with the same installer

Use a separate LAN hostname for each computer. Keep Cloudflare credentials in the private repository `.env`, or provide a Certbot credentials INI. A scoped Cloudflare API token needs access to the relevant DNS zone; `CF_API_TOKEN` and `CF_ZONE_ID` are supported. Existing `CF_EMAIL`/`CF_API_KEY` installations are also supported. Credentials are copied into a root-only file for unattended renewals, never into the APK or Git.

```bash
./install.sh --with-browser kasmvnc \
  --browser-origin https://your-public-agentshell-host \
  --lan-hostname your-computer-lan.example.com \
  --lan-interface enp3s0
```

For the current host:

```bash
./install.sh --with-browser kasmvnc --agents installed \
  --lan-hostname agentshell-lan.bitslovers.com \
  --lan-interface wlo1
```

The interface is auto-detected when exactly one physical interface has a private IPv4 address. Use `--lan-address 192.168.x.y` for a fixed address without interface tracking. `--lan-zone-id` and `--lan-credentials /private/cloudflare.ini` override the private `.env` inputs. If there is no public browser origin yet, the LAN HTTPS origin can serve as the primary origin. `--no-start` prepares files and the certificate without restarting the backend; `--deps-only` installs selected dependencies. DNS-01 validation does not require opening web ports to the Internet.

The installer performs these steps:

1. Installs Certbot, its Cloudflare DNS plugin and `iproute2`, in addition to the normal backend/browser dependencies.
2. Obtains a publicly trusted Let's Encrypt certificate through DNS-01 and validates its hostname, expiry, chain and matching private key.
3. Creates or updates only the exact selected DNS-only A record. Conflicting record types are rejected.
4. Installs root-owned TLS files, credentials and maintenance code. The backend user can read only the TLS key it needs.
5. Adds the LAN address as an explicit allowed browser origin and preserves the public origin, browser profile and running Chrome instance.
6. Enables `certbot.timer` for renewal and `agentshell-lan-dns.timer` for DHCP address changes. The DNS job checks the selected interface every five minutes; an unchanged address normally needs no API call. A DNS record check also runs hourly.
7. Reloads renewed TLS certificates with SIGHUP, keeping the backend PID, connections and Chrome runtime intact.

Certificates live under `/etc/agentshell/lan-tls/HOSTNAME/`; root-only credentials/configuration are under `/etc/agentshell/`. The root-owned helper is `/usr/local/lib/agentshell/lan-https.py`. Certbot's issuance log is `/var/log/agentshell-lan-https/certbot.log` and is root-only. The helper and service/timer templates are backed up in this repository and listed in the deployment manifest.

## Include the host in an APK

The repository-root private `.env` supports explicit URL schemes in `SERVER_LIST`, including HTTPS on nonstandard ports:

```dotenv
SERVER_LIST=public.example.com:443,Public|192.168.0.38:4010,Homelab|https://your-computer-lan.example.com:4443,LOCAL_LAN
```

Then build the APK normally. A bundled endpoint moved to HTTPS under the same label updates its previously imported, unchanged entry while preserving the host ID, order and current selection. User-edited endpoints are retained. A manually added server can also use the hostname, port 4443 and **TLS enabled** in Manage servers.

## Browser authentication and compatibility

### Existing HTTP IP bookmarks

Android 1.0.1 also supports saved LAN IP entries that still use HTTP (for example, a custom-named `192.168.0.76:4010` entry). These entries do not need to be renamed, deleted or recreated. For browser operations, Android finds a saved or bundled HTTPS endpoint whose DNS address matches the selected private IP. It connects over validated HTTPS and requires the capabilities response to advertise both the selected backend HTTP port and the trusted viewer origin before using it. All browser controls, grants and handoffs then use that HTTPS endpoint. Saved host IDs, names, selection and the user's configured chat/terminal transport are preserved. It never sends browser discovery or viewer grants over the HTTP bookmark, guesses TLS ports, or follows an unrelated server.

The backend capabilities include `httpPort` and `httpsPort` for this association. Secure bookmarks remain compatible with older backend versions. For an HTTP bookmark on an older backend without the port metadata, select its configured HTTPS entry until that backend is updated.

This closes an upgrade gap found on 2026-10-14: the previous default import updated only unchanged bundled entries named `LOCAL_LAN`; custom-named or manually saved IP entries retained HTTP and still displayed `HTTPS_REQUIRED`. The original failure was reproduced with a custom IP bookmark on the previous APK, then retested with that bookmark retained during an in-place APK update. The regression fixture also seeds an HTTP IP bookmark alongside a known HTTPS endpoint, so fresh HTTPS-only installation tests cannot miss this case again.

Capabilities expose both the primary `viewerOrigin` and explicit `viewerOrigins`. Android chooses its selected origin only when it appears in this list. Single-origin older backends remain supported. LAN grants and their HttpOnly, Secure cookies are bound to the selected origin; a cookie issued for the LAN viewer cannot authenticate a WebSocket from the public origin, or vice versa. Unknown origins and cleartext LAN origins remain rejected. Only one controller can drive the same browser at a time, regardless of which address it uses. Moving the viewer to the computer continues through the configured public origin.

## Checks and troubleshooting

```bash
./install.sh --check --with-browser kasmvnc --lan-hostname your-computer-lan.example.com
systemctl status agentshell certbot.timer agentshell-lan-dns.timer
sudo systemctl start agentshell-lan-dns.service
sudo systemctl reload agentshell
```

Android tries system DNS first. If a router filters private-address answers, the app resolves the server through HTTPS DNS and routes only that viewer hostname through a temporary loopback CONNECT tunnel. The tunnel forwards encrypted bytes directly to the resolved private IP; WebView still validates the original HTTPS hostname and certificate. The tunnel closes when the viewer closes. Other browsers using the router DNS may still need a local DNS override for this exact hostname. An outdated Android System WebView without scoped proxy support must be updated for this fallback. Phones must be on the same reachable LAN or VPN. No router port-forward is needed. Changing the hostname requires rerunning the installer and updating the saved server/build default; changing the tracked interface's IP is automatic.

Tests cover private-address/hostname validation, exact DNS changes and conflicts, DHCP tracking, unrelated renewal isolation, Android default migration and origin selection, real HTTPS WebSocket origin binding, and Android WebView rendering through both the primary and secondary origins with synthetic browser pages.

References: [Let's Encrypt DNS-01](https://letsencrypt.org/docs/challenge-types/#dns-01-challenge), [Certbot Cloudflare plugin](https://certbot-dns-cloudflare.readthedocs.io/en/stable/), [Cloudflare DNS-only records](https://developers.cloudflare.com/dns/proxy-status/).
