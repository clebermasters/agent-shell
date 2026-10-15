# Straightforward setup on a new computer

Status: partly implemented. Independent browser data is now the fresh-install
default, and the unified installer provisions the shared Chrome desktop viewer
and phone/computer transfer commands. The guided flow, automatic profile import,
pairing and other changes below remain proposals.

The target experience is one installer, a few setup choices, and a connection
that can be added to the existing Android app. The installer should provision
the backend, Smart UI and MCP, agent tools, and optional Chrome streaming;
desktop Chrome should continue to work independently.

## What already exists

`install.sh` bootstraps Python and delegates to `scripts/install-backend.py`.
That installer already installs missing dependencies, preserves usable tools,
deploys the backend and all Smart UI/MCP assets, merges MCP registrations,
creates services, checks the MCP handshake, and supports the pinned KasmVNC
runtime. The existing profile flag can already isolate a fresh installation:

```bash
./install.sh --with-browser kasmvnc \
  --browser-origin https://YOUR_HOST \
  --browser-user-data-dir "$HOME/.local/share/agentshell/chrome"
```

This is a supported command today, not an automated profile import. Provider
sign-ins and the Android app's matching authentication token still need setup.
For an existing installation, preserve its current configuration; changing its
profile requires a deliberate migration rather than passing a different path.

The friction found in the code is concrete:

- Fresh browser setup formerly defaulted to `~/.config/google-chrome`; the
  installer now uses an independent data directory and retains existing paths.
- Existing-profile import has no supported offline-copy workflow in the installer.
- Trusted HTTPS must be prepared separately; a missing origin is detected late
  in the installation, after dependencies and the backend build.
- `Host` has no per-host credential field, and `BrowserRepository` uses
  `BuildConfig.AUTH_TOKEN`. Pairing a newly generated server token therefore
  requires Android credential support; a QR code alone cannot solve it.
- Backend source compilation and account/keyring setup add time and manual steps.
- Binary-only updates can leave runtime helpers behind. This host still had a
  browser helper requiring an explicit profile name when the project already
  supported automatic selection. Deploy and verify the complete versioned
  runtime payload together, including configuration-schema compatibility.

## 1. Independent browser data by default

On fresh installations, use `~/.local/share/agentshell/chrome` for VNC. Let
Chrome select its default/last-used profile; do not hardcode `Default`, another
profile name, or an email address. Retain an existing configured directory and
explicit profile override on ordinary updates.

Offer two normal setup choices:

1. **Fresh VNC browser**: create a private data directory and sign in once.
2. **Import the desktop browser**: make an offline copy into that directory.

Import should identify the source directory, active owner, destination, space
required, and profile-selection metadata without showing browsing data. If
Chrome is running, require an explicit close-and-import choice. Do not stop it
as an incidental effect of installation.

The migration should be resumable and transactional: stop the selected owner,
confirm the source remains offline, take a private backup, copy all persistent
profile data, omit singleton/socket and debugging-port artifacts, verify the
copy, and atomically update the runtime configuration. Preserve browser identity,
credentials and unrelated settings. A failed import keeps the original usable
and reports the saved backup and incomplete stage. Never overwrite an existing
destination or copy a live profile silently.

For a different computer, account sign-in may still be necessary: encrypted
cookies/passwords can depend on the original OS credential store. Installing
software and restoring account authentication are separate completion states.

## 2. Guided setup through the same entry point

Add a full-setup preset and an interactive first-run flow to `install.sh`.
Keep equivalent noninteractive options for automation; their names are to be
defined during implementation. The choices should be limited to:

1. Service user and backend/browser components.
2. The HTTPS address Android will use.
3. Fresh browser data or an explicit offline import.
4. Agent tools to install or retain.

Run preflight before package installation or compilation: supported OS/CPU,
sudo/systemd availability, ports, disk space, existing browser ownership,
HTTPS inputs and service-user keyring availability. Headless and unsupported
browser environments should receive a clear component-specific result, while
backend and MCP setup remain available.

Prefer a verified prebuilt backend for supported platforms; retain a source
build fallback. Pin dependencies and client assets in the existing manifests.
Keep every required runtime/helper in the same installation payload. Updates
must preserve authentication, databases, profiles, custom tools and MCP entries.

Account login should be shown as a remaining user action when necessary. Do
not report Chrome or an agent as ready merely because its package was installed.
Retain the existing keyring and credential proxy; do not weaken Chrome's sandbox
or switch to a plaintext password store to make first startup appear successful.

## 3. HTTPS and Android connection setup

Support the existing trusted reverse proxy first, with an optional managed
Cloudflare tunnel flow. Use the same HTTPS host and port for the daemon and
browser viewer. Keep VNC on loopback. For a tunnel, keep its Nginx listener
separate from existing public virtual hosts, as in the homelab loopback setup.

Back up changed network configuration, preserve unrelated sites and zone-wide
SSL settings, and make repeated setup reuse the same hostname/tunnel identity.
Store credentials privately rather than in the repository or diagnostic output.
Verify DNS, hostname/TLS trust, authenticated API requests and WebSocket upgrade
through the public route. Do not silently bypass certificate validation.

For the current Android app, document the existing matching-token setup. Then
add per-host credentials through a centralized resolver used by WebSocket,
browser and other HTTP clients. Store host secrets using Android-protected
storage, with migration/fallback for existing installations.

Only after that support exists, add a short-lived, single-use pairing code or QR
flow that imports the host address and credentials into Android. Adding another
computer should not require rebuilding the APK or displaying a permanent server
token in a public artifact.

## 4. Clear completion and diagnostics

End setup with a concise report: installed components, host address, service
readiness, browser profile mode, MCP registrations, and remaining sign-in or
keyring actions. Provide a reusable read-only check and a diagnostic bundle that
omits tokens, browser data, private keys and account contents. Keep stage results
so an interrupted installation resumes without repeating completed downloads.

## Implementation order and acceptance

1. Independent fresh-install default, upgrade retention, and explicit offline
   profile import with backup and rollback.
2. Early preflight, guided/full setup, and verified prebuilt-backend support.
3. Integrated HTTPS configuration and end-to-end route checks.
4. Per-host Android credentials, then one-time pairing.
5. Completion report and resumable diagnostics across these stages.

Use disposable Ubuntu/Pop!_OS-compatible fixtures with synthetic browser data.
Verify a fresh machine, a repeated install, an interrupted import, low disk
space, an active source profile, missing client assets, and a stopped/locked
keyring. Demonstrate desktop and VNC Chrome running simultaneously with distinct
directory owners and independent database files. Test MCP registration without
publishing into a user's conversation. Verify Android connection, browser frames,
keyboard, gestures, dialogs and reconnect against the final public route.

The first deliverable should remove desktop-profile contention without needing
an Xpra migration. The pairing work is a separate Android/backend change with
its own compatibility tests.
