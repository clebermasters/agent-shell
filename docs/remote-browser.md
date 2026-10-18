# Remote Chrome in AgentShell Android

AgentShell can display and control a complete Google Chrome browser running on
the selected Linux server. Chrome's address bar, tabs, menus, popups and file
dialogs stay inside a dedicated graphical display. The Android Browser screen
provides keyboard shortcuts, fullscreen, viewer zoom and connection recovery.

## Install

The first runtime supports Ubuntu 22.04 and Pop!_OS 22.04 on amd64. Use the same
backend installer, opting into the browser component:

```bash
./install.sh --with-browser kasmvnc \
  --browser-origin https://your-agentshell-host \
  --browser-user-data-dir "$HOME/.local/share/agentshell/chrome"
```

The origin must match the HTTPS server address selected in Android, with no
path or trailing slash. If omitted, the installer retains the existing browser
origin or uses the first port-443 server in the project's private `.env`.
Custom HTTPS ports work by enabling **Use HTTPS / WSS** when editing that server
in Android. Certificates must pass normal platform trust and hostname checks;
selecting HTTPS does not make a self-signed certificate trusted.

The installer checks and installs missing dependencies, verifies the pinned
KasmVNC package and client hashes, copies runtime support files, and creates an
independent user service. A working Google Chrome installation is retained.
Google Chrome is installed from Google's official download only when missing.
The same command installs missing agent CLIs, registers their Smart UI MCP,
and provisions the keyring service and login integration needed on a clean host.
For an already provisioned host, `--agents installed` keeps registration limited
to its existing working agents; use the default command above on a new computer.
Repeated installation retains browser identity, profile selection and private
credentials. `--no-start` prepares files without touching service state.

Chrome chooses its default/last-used profile automatically. No profile directory
name or account email is hardcoded, and the installer does not rewrite Chrome's
profile-selection state. For an existing custom user data location, specify its
enclosing directory:

```bash
./install.sh --with-browser kasmvnc \
  --browser-origin https://your-agentshell-host \
  --browser-user-data-dir /home/your-user/.config/google-chrome
```

Do not point `--browser-user-data-dir` at an individual profile subdirectory.
`--browser-profile` is an optional explicit override. Existing configured
overrides are retained on update to avoid switching a running session's profile.
Profile selection is configured on the server; Android clients cannot submit
arbitrary filesystem paths or launch commands.

Installation does not stop desktop Chrome, copy its profile, or automatically
enable browser launch on boot. User-service lingering keeps an explicitly
started managed session alive independently of the AgentShell daemon. After a
cold boot, an existing credential store may require the desktop login/unlock.

## Independent desktop and VNC browsers

For a fresh installation, the command above selects a dedicated VNC data
directory. Desktop Chrome keeps its ordinary user data directory, so both
instances can run concurrently. Fresh installs now default to this independent
directory when the flag is omitted; existing configurations are retained on
updates. A second profile inside one user data directory still shares Chrome's
instance lock and does not provide this separation.

To reuse existing desktop data, make an offline copy of the complete user data
directory into a new, private VNC directory. Stop its Chrome owner before copying,
retain a backup, omit singleton/socket and debugging-port artifacts, verify the
copy, and explicitly reconfigure the stopped runtime. Do not copy a live profile
or overwrite an existing destination. Chrome selects its copied default/last-used
profile automatically. The two browsers then maintain independent live tabs,
cookies and sessions; authentication may need renewal on some sites.

On this host, the offline migration on 2026-10-13 UTC copied and verified 13,212
files into `~/.local/share/agentshell/chrome`. A private snapshot and prior
configuration are under
`~/.local/state/agentshell/profile-migrations/20261013T133445Z/`. The installed
runtime helper was also updated to support automatic profile selection.

The [new-device installation plan](new-device-installation-plan.md) also proposes
a supported, transactional import flow. Automatic profile import remains planned.

## Continue the same browser on the computer and phone

The browser installation includes **AgentShell Chrome** in the Linux application
menu, a native GTK/WebKit desktop viewer of the managed Chrome session. Start
shared browsing in this launcher. Its tabs belong to the same Chrome that
Android displays; ordinary desktop Chrome remains an independent browser.

```bash
agentshell-browser open      # Open the desktop viewer; start managed Chrome if stopped
agentshell-browser phone     # Release desktop control; keep Chrome running
agentshell-browser desktop   # Return control from an active phone to the computer
agentshell-browser status    # Read status without URLs, page content or credentials
agentshell-browser quit      # Close only the desktop viewer
```

Equivalent shortcuts are `agentshell-send-to-phone` and
`agentshell-return-to-computer`. The desktop window and application-menu actions
also offer **Send to phone** and **Return to computer**. F11 toggles the viewer's
fullscreen mode. Commands are installed in `~/.local/bin`; use their full path if
an existing shell has not loaded that directory into PATH.

After **Send to phone**, open Browser in Android on the same configured host.
If Browser was already open with a busy/error message, tap **Reconnect**.
The command does not remotely launch the Android app. **Return to computer**
works while the phone is still displaying Browser: its viewer disconnects and
cannot silently retake control. Open/reconnect never displaces another viewer;
only the explicit Return action requests takeover.

The Android Browser toolbar now has a **Move browser** (computer/phone) icon.
Its menu offers **Use on this phone** and **Send to computer**. Fullscreen has
the same actions under **Fullscreen controls**. Sending to the computer shows
progress, then a **Use on this phone** button on the computer-destination screen.
Backgrounding/resuming that screen does not silently take control back.

Android can launch a closed desktop viewer through a small same-user graphical
session service. The installer provisions
`agentshell-browser-desktop-agent.service` and its XDG autostart entry. At login,
the entry imports only the real graphical connection metadata into the user
service manager; no display number, Chrome profile or account name is guessed.
The service listens on a mode-600 Unix socket in the private desktop state
directory. It has no TCP endpoint and does not launch Chrome or open a viewer
until requested. It follows `graphical-session.target` and stops with the desktop
session. Installing from a logged-in desktop activates it immediately; installing
through SSH/headlessly configures it for the next desktop login.

The computer must have an available logged-in graphical session to receive a
transfer. If none is available, the same phone viewer stays connected. If the
desktop claims control but cannot finish connecting, the backend releases that
claim and Android obtains fresh access. Each transfer has a unique ID: cancellation
and phone recovery invalidate that ID atomically, so a delayed desktop launch
cannot steal control back. Recovery also respects a newer explicit controller
change. These operations use authenticated API routes and fixed installed helpers;
Android cannot submit executable paths, shell commands or display variables.

Chrome stays on its managed display throughout these switches. Its PID, profile,
tabs, cookies and live page memory are retained; the computer must remain awake.
Switching viewers does not migrate tabs from an unrelated, already running
ordinary Chrome window. To share that exact ordinary profile initially, use the
deliberate offline handoff below; after that, use the shared launcher for desktop
browsing. No profile copies, browser restarts or singleton-lock removal occur
during viewer switches.

Install/update the complete backend and browser payload together:

```bash
./install.sh --with-browser kasmvnc --agents installed
```

An existing origin/profile/token is retained. A new machine additionally needs
`--browser-origin https://YOUR_HOST`. The installer provisions Python GI, GTK 3,
WebKitGTK 4.0/Soup 2.4 and the launcher alongside the pinned browser dependencies.
The desktop viewer uses `~/.config/agentshell/browser-desktop.json` (mode 600),
references the installed private `runtime.env`, and communicates locally over a
same-user Unix socket in a mode-700 directory. It has no TCP control listener.

The token is read in Python and never put into HTML, JavaScript, URLs or command
arguments. WebKit uses an ephemeral cookie store with the gateway's scoped
HttpOnly/Secure cookie. HTTPS follows normal certificate/hostname verification.
For a separately administered private CA, an explicit `caFile` in the private
desktop config validates the peer certificate before granting WebKit that exact
certificate for the configured host; there is no ignore-certificate-errors mode.

Takeover revokes old cookies and unused grants, cancels the old controller, and
reserves access for the target viewer for at most 60 seconds. A failed desktop
load releases that reservation. Release addresses only the desktop's viewer ID,
so repeating Send to phone cannot disconnect a phone already in control. Backend
restarts keep Chrome alive; the desktop obtains fresh access on reconnect.

## Sharing the current desktop directory (one environment at a time)

Open **Remote browser** from AgentShell's Home toolbar. If desktop Chrome owns
the configured directory, the screen reports **Chrome is already open**.
Chrome locks the whole user data directory, including its other profiles; a
second independent instance cannot safely share it.

For the initial handoff:

1. Close the owning desktop Chrome normally and wait for it to exit.
2. Make an offline, private backup of the complete user data directory.
3. Tap **Start browser** in Android. Chrome starts on the managed display under
   the same OS user, using the same profile path and credential store.
4. Verify the intended account, familiar signed-in browsing, popups and file
   dialogs before relying on the managed session.

Keeping the original OS user and path reduces migration risk. Profile files
alone do not guarantee working encrypted credentials; Linux Chrome uses the
user's secret service or KWallet. Website sessions can also expire normally.
Do not use `--no-sandbox`, a plaintext password store, or delete a live
`SingletonLock` to bypass this workflow.

Going back, backgrounding Android, or losing the network disconnects the
viewer while Chrome keeps running. **Stop remote Chrome** closes the managed
browser gracefully and retains its profile. To return to ordinary desktop
Chrome, stop the managed browser first, then open Chrome on the computer.

The gateway allows one controlling viewer. Another device gets an explicit busy
response; the desktop's explicit Return action transfers control. Concurrent
independent Chrome instances using this directory are unsupported.

## Optional native Chrome handoff

The existing **Send to computer** action opens the desktop VNC viewer and keeps
Chrome running. **Open native Chrome on computer** is a separate Android menu
action that closes the managed browser normally and reopens that same profile
directly on the logged-in desktop, without a VNC viewer. While native Chrome is
active, **Use on this phone** offers the reverse handoff. Both native actions
show a confirmation: tabs are restored, but unsaved page inputs and JavaScript
memory cannot survive the restart. Cancel leaves the current browser untouched.
If you close native Chrome normally yourself, the return action still restores
its tabs on the phone; it does not require leaving the native window open.

This uses the configured AgentShell data directory, not a copy or a profile
chosen by account email. Chrome retains automatic default/last-used selection.
An unrelated ordinary desktop Chrome with a different data directory is left
alone. The native browser runs under the same user and original credential
store; the service and its launcher are installed by the same backend installer.

The graphical-session broker starts `agentshell-browser-native.service` on demand
using the real desktop environment. The native and VNC runtimes cannot own the
profile concurrently. Runtime generations change on these restarts, and old
viewer access is revoked. The physical desktop must be available for either
native handoff; desktop-session/credential-store checks happen before closing.

Chrome's Linux SIGINT path requests normal application exit. The helper verifies
the profile owner and process generation, pins the signal to a Linux pidfd, and
waits for Chrome to exit without a second signal or force kill. If an unload or
download confirmation prevents closure, Chrome stays open and the handoff fails
safely. Resolve the dialog or quit Chrome normally before retrying. See
[Chromium's shutdown implementation](https://chromium.googlesource.com/chromium/src/+/HEAD/chrome/browser/chrome_browser_main_posix.cc).

The helper checks Chrome's recorded clean exit, requests `--restore-last-session`
without opening an extra blank tab, and restarts the original location if the
destination launch fails. It never copies a live profile, removes a live lock,
enables production DevTools, disables the sandbox or selects plaintext password
storage. The previous viewer-transfer workflow and its recovery remain available.

Verification used two independent owned X displays and a synthetic profile. Two
native round trips restored both original tabs, the selected tab, and session/
persistent authentication cookies. The test also ran with encrypted synthetic
cookies using the existing OS keyring, verified no VNC display remained while
native Chrome was open, and exercised unload-confirmation refusal. Android menu
actions passed two additional native round trips and cancellation of the restart
notice. A failed native launch after the normal close restored the same profile
in VNC. Real Google account authentication was not inspected or guaranteed by
the synthetic tests; website sessions can expire normally.

```bash
python3 scripts/test_browser_native_unit.py
python3 scripts/test_browser_native.py --encrypted-profile
python3 scripts/test_browser_native.py --android-serial emulator-5560
systemctl --user status agentshell-browser-native.service
```

## Fullscreen and screen fitting

The browser follows the viewer's available aspect ratio, including rotation,
tablets and foldable layouts. The remote desktop resizes through KasmVNC while
the same Chrome process and profile keep running. Logical dimensions are bounded
(up to 1600 by 2160 pixels), avoiding an unnecessarily large physical-pixel stream
on high-density displays. Unsupported desktop resizing retains the existing
scaled view. Older APKs keep their previous scaling behavior.

**Expand browser** hides the app toolbar, keyboard strip, status bar and navigation
bar. The viewer uses the remaining screen while respecting camera cutouts. A
small floating menu provides keyboard, mouse, fit, browser options and Escape;
the adjacent button exits fullscreen. Back also exits fullscreen, and system
navigation can be revealed with an edge swipe. Leaving the screen restores the
system bars. Opening the keyboard retains the remote desktop geometry, the
viewer's zoom and its pan position. The keyboard crops the visible area instead
of shrinking the browser while typing.

If the keyboard interrupts a pending screen resize, that resize is retried after
the keyboard closes. The fullscreen Keyboard action waits for the floating menu
to release window focus before opening the IME.

This improvement requires the updated APK plus the backend viewer adapter.
The native client supplies viewport/keyboard state; the viewer assets remain
served by the same configured HTTPS host and port.

## Interaction

- **Keyboard** opens the Android IME. **Esc**, **Tab** and **Enter** send actual
  remote key events. Accented text and composition are handled by the client.
- **Expand browser** uses the available screen, including tablets and foldables.
- **Zoom browser view in** and pinch enlarge the streamed view. In direct touch
  mode, two-finger dragging pans the zoomed viewer. In mouse-pointer mode, drag
  three fingers to pan; **Fit browser to screen** resets the view.
  Pinch does not also zoom the remote web page.
- Normal taps, dragging, scrolling and right-click gestures retain KasmVNC's
  touch handling. The interaction-options control exposes additional client
  keyboard and viewing controls.
- **Reconnect browser** gets fresh, scoped access to the same running session.

Files selected by remote Chrome are server files. Existing AgentShell file
manager uploads/downloads can transfer those files separately; browser-native
Android picker integration, larger transfers, audio, live website microphone and camera
forwarding are follow-up work. Chrome's profile/theme is remote; Android's
light/dark controls style the native app around the viewer.

### Dictation at the browser cursor

Tap a text field in remote Chrome to position the caret, then tap **Dictate**
in the Browser controls. Fullscreen has a microphone button beside its floating
controls. Allow microphone access if prompted, speak, and tap **Stop**. **Cancel**
discards the current recording.

The existing AgentShell transcription queue and saved Voice Input API key are
reused. When transcription finishes on the same uninterrupted browser
connection, the text is pasted into the focused field, replacing any selected
text. Unicode and multiline text use clipboard paste. No Enter key is sent, and
the browser does not use the chat/terminal auto-enter preference.

If the viewer disconnects, is paused, changes server, or moves to the computer,
automatic insertion is cancelled. Completed text is retained with **Paste at
cursor**, **Copy**, and **Dismiss** actions. Explicit paste is available only in
the original browser runtime; focus the intended field before using it. If a
paste acknowledgement is lost, check the field before retrying. Duplicate
completion notifications cannot automatically insert the same recording twice.
Transcription errors offer **Retry transcription** when the audio is retained;
manually retried results require an explicit paste.

This requires the updated APK and backend viewer adapter. Recording occurs on
the phone; it does not give the remote website access to the phone's microphone.

### Touch mouse pointer mode

In Android, the former controller button becomes **Touch mouse pointer mode**.
Enable it to use the browser surface as a trackpad with a visible cursor: drag
one finger to move it, tap to left-click, tap with two fingers to right-click,
and drag two fingers to scroll at the cursor. Hold one finger briefly, then drag
to select or move items. Pinching with two fingers still zooms the viewer. Drag
three fingers to move around the zoomed view, without scrolling or clicking the
remote page. The view stays where you leave it; moving the mouse again brings
an off-screen pointer into the visible area. Toggle the mode off to return to
ordinary direct touch. The mode is remembered for the configured browser.

This mode sends normal mouse events to the remote browser. It does not request
WebView pointer lock. The original game pointer mode requested that API and
reproduced both an "Unable to enter pointer lock mode" warning and a fatal
Chromium-error overlay. The API existed but rejected the request. Chromium's
[Android pointer-lock announcement](https://groups.google.com/a/chromium.org/g/blink-dev/c/jXlkRs1KK3Y)
excluded Android WebView. The desktop-only pointer-lock setting is therefore
hidden and old preferences are cleared. Standalone browsers retain their normal
capability detection.

The adapter releases held mouse buttons on cancellation, mode changes and
viewer pause. Pointer positioning accounts for viewer zoom and pan. This touch
trackpad does not capture a Bluetooth/USB mouse for game-style relative input;
that requires a separate native implementation.

Reconnect after a backend update to load the new pointer control. No APK change
is needed for the web-client implementation.

## Files and dependencies

| Component | Location / purpose |
| --- | --- |
| Rust session API and streaming gateway | `backend-rust/src/browser.rs` |
| Native viewer and controls | `android-native/app/src/main/java/com/agentshell/feature/browser/` |
| Native control requests | `data/repository/BrowserRepository.kt` |
| Supervised display/Chrome process | `scripts/browser-runtime.py`, installed at `/opt/agentshell/browser/browser-runtime.py` |
| Dependency versions, download checksum, client hashes | `deployment/browser/dependencies.json` |
| Version-specific WebView adapter and touch pointer | `deployment/browser/viewer-bridge.js` and `touch-mouse.js`, compiled into the backend |
| User service template | `deployment/browser/agentshell-browser.service` |
| Private browser configuration | `~/.config/agentshell/browser.json`, mode 600 |
| Runtime ownership/generation record and private logs | `~/.local/state/agentshell/browser/`, mode 700 |
| Matching KasmVNC HTML/JS/CSS/WASM assets | `/usr/share/kasmvnc/www`, from the pinned package |
| Chrome profile and credential store | Original user-owned locations, never included in deployment packages |

The current package is KasmVNC 1.5.0, with Openbox, Xauthority support, fonts,
D-Bus support, `xdg-dbus-proxy` and the existing Google Chrome. Rust adds `sha2` and
`tokio-tungstenite`; the production Python runtime uses the standard library.
KasmVNC's protocol/client differs from standard VNC/noVNC. All viewer assets
are served from the installed package through the authenticated gateway.
There is no runtime CDN dependency for the viewer.

The runtime exposes the original user's `org.freedesktop.secrets` service
through a private, supervised D-Bus proxy. It excludes the desktop portal and
other desktop services. This preserves keyring access while making Chrome use
a native file chooser on the managed display. Sharing the unrestricted desktop
bus sent file dialogs to the original desktop during the personal-profile test;
simply changing `DISPLAY` was insufficient. The proxy socket is private and
stops with the managed session. The installer provisions the proxy dependency.

No MCP server is required for display or user interaction. Existing Chrome
DevTools MCP configuration does not automatically target this browser. Chrome
136+ restricts remote-debugging switches against the default user data
directory; agent automation of a personal profile needs a separate design.

## Identity, authentication and recovery

Browser identity is independent of TMUX, working directory, chat conversation
and account-email heuristics. A stable session UUID identifies the configured
browser; a separate runtime generation identifies each actual launch. PID
start time and boot identity guard against recycled PIDs.

The runtime record also fingerprints profile, display and upstream-credential
configuration. A running browser started with different settings cannot be
reported ready. Configuration changes require an explicit managed stop and
restart; no automatic fallback opens a different profile.

Native session-control requests use the normal AgentShell token over HTTPS.
That token is never placed in viewer HTML, JavaScript, URLs or localStorage.
A short-lived single-use bootstrap grant creates an HttpOnly, Secure,
SameSite cookie scoped to the exact browser path and runtime. WebSocket
upgrades validate the origin and enforce one controller. Credentials for the
private KasmVNC endpoint remain on the server; the viewer has no owner API
credentials. Viewer CSP confines network requests to its own origin.

The browser stream uses a separate binary WebSocket with bounded messages and
send timeouts. It does not enter the shared JSON chat/terminal queue. Backend
restarts revoke old viewer access while the independently supervised Chrome
continues running. Android obtains fresh access when reconnecting.

The existing shared administrative token remains the authorization model;
this feature does not add independent multi-user accounts.

## Troubleshooting

```bash
systemctl --user status agentshell-browser.service
systemctl --user status agentshell-browser-desktop-agent.service
python3 /opt/agentshell/browser/browser-runtime.py \
  --config ~/.config/agentshell/browser.json status
```

Runtime logs are private and may contain visited URLs; review them locally and
redact them before sharing. Never print or paste `browser.json` credentials.

| Code | Meaning and action |
| --- | --- |
| `BROWSER_NOT_CONFIGURED` | Install/configure the optional browser component and restart the backend. |
| `PROFILE_IN_USE` | A live Chrome owns the directory. Close it normally, back up offline, then start the managed browser. |
| `RUNTIME_CONFIGURATION_CHANGED` | Settings differ from the running runtime. Stop the managed service explicitly, then start with the new configuration. |
| `CREDENTIAL_STORE_UNAVAILABLE` | Restore the user's D-Bus secret service / unlock the desktop session. |
| `CREDENTIAL_BUS_START_FAILED` | Check that `xdg-dbus-proxy` is installed and the original user bus is available, then restart the managed browser. |
| `DISPLAY_IN_USE` / `PORT_IN_USE` | Another process owns the configured private display/port. Choose an unused value after stopping this runtime. |
| `VIEWER_VERSION_MISMATCH` | Installed assets differ from the tested release. Reinstall the checksum-pinned browser component; do not edit the minified client manually. |
| `CONTROLLER_IN_USE` | Choose Use on this phone in Android, or Return to computer on the desktop. Older APKs need Send to phone on the desktop followed by reconnect. |
| `DESKTOP_UNAVAILABLE` | Sign in to the computer's graphical desktop and check the desktop-agent user service. Android keeps control. |
| `HANDOFF_SUPERSEDED` | A newer action changed control. Select the desired destination explicitly. |
| `HANDOFF_NOT_SUPPORTED` | Update the backend and desktop viewer together with the unified browser installer. |
| `NO_DESKTOP` | Open the desktop viewer from the logged-in graphical session; it cannot create a desktop from SSH alone. |
| `VIEWER_UNAVAILABLE` | Check the GTK/WebKit dependencies and the private desktop viewer log, then reconnect. Chrome remains independently managed. |
| `VIEWER_AUTHORIZATION_REQUIRED` | Access expired, was used already, or belongs to an earlier runtime/daemon. Reconnect. |
| `HTTPS_REQUIRED` / certificate error | Use the configured, trusted HTTPS server. Do not disable certificate checks. |

The adapter addresses two observed Android WebView issues: the optional
Permissions API is absent, and initial `vh` sizing can resolve to zero while
Compose attaches the WebView. This also affected the sidebar and settings panels:
their `max-height: 100vh` became zero, collapsing the sidebar into a horizontal
line and leaving only its handle visible. The adapter uses measured viewport
pixels for those panels, keeps their contents scrollable, and constrains their
width on narrow screens. A compact icon rail leaves room for settings; switch
labels wrap in complete rows. The native options action also cancels the
client's startup auto-close timer and releases its hidden IME field; otherwise
an immediate settings tap could reopen the keyboard and collapse the viewport.
Hash checks bind the small adapter to the tested
client release. A different runtime/client pair must pass the same suite
before replacing it; TigerVNC/noVNC remains a fallback deployment choice.

If Chrome is visible but the interaction drawer appears as a thin line with
only its handle, update the backend containing the viewport adapter, then
reconnect the Browser screen. The adapter is compiled into the Rust binary:
copying its JavaScript file alone is insufficient. An adapter-only fix does not
require a new Android APK.

## Verification

On 2026-10-12, the local backend/browser component was installed and checked
through the configured HTTPS origin. The release APK was built with the debug
fixture and test CA excluded. After the user approved closing desktop Chrome,
the complete profile was backed up offline into a private directory under
`/var/tmp/agentshell-chrome-backups/`. The original `Default` profile was then
started on the managed display. The production APK displayed that browser and
its native file chooser over HTTPS. The original keyring remained unlocked and
accessible through the filtered bus; the desktop portal was inaccessible there.

Validation passed: 618 Rust tests, 40 selected Android unit/regression tests,
ten runtime ownership/restart tests, nine installer regression tests, nine live
gateway/recovery checks, fifteen Android HTTPS/lifecycle/layout checks, and a fresh Ubuntu
dependency/install check including idempotent reinstallation. The fresh
container received only packaged deployment sources/assets and a prebuilt
binary; no personal configuration or Chrome profile was mounted.

The initial native-client experiment passed Portuguese IME commits, Enter and
Backspace, visible Chrome frames, a second browser window, a file dialog and
reconnect to the same Chrome process. Viewer zoom/pan and zoomed pointer mapping
also passed; pinch produced no remote keyboard events. The settings regression
was reproduced before the fix with a zero-height sidebar. Real WebView touch
tests now open, scroll and close settings on portrait, landscape, tablet, the reported foldable dimensions and
280-dp phone layouts, checking panel bounds, label visibility, and absence of
unrequested IME focus. Resolution fixtures restart the test activity between
devices; the landscape case rotates the existing viewer.

The measured local emulator run used 100 input-to-decoded-pixel samples, with
p95 approximately 103 ms. This is local-loopback evidence, not measured
physical-phone Wi-Fi or mobile-data performance. The proposed Wi-Fi target is
p95 below 200 ms on a defined device/network.

Useful checks:

```bash
python3 scripts/test_browser_runtime.py
python3 scripts/test_browser_desktop.py
# Desktop transfer and optional real Android screen tests, using disposable data:
python3 scripts/test_browser_handoff.py --android-serial emulator-5560
# With the owned Android TLS fixture and emulator running:
python3 scripts/test_android_browser_pointer_lock.py
python3 scripts/test_android_browser_mouse.py
python3 scripts/test_android_browser_fullscreen.py
python3 scripts/test_android_browser_viewport.py --output /tmp/agentshell-viewport-check
python3 scripts/test_backend_install.py
python3 scripts/test_browser_streaming.py --keep-running
# On a logged-in Linux desktop, additionally verify keyring/portal isolation:
python3 scripts/test_browser_streaming.py --shared-credential-store
python3 scripts/test_browser_streaming.py --automatic-profile-check
python3 scripts/test_fresh_browser_install.py
cd backend-rust && cargo test --locked
```

Android fixture builds require `-PuiPoc=true`, an owned emulator and the
debug-only CA used by the local TLS fixture. The fixture activities use an
isolated host database and cannot load production credentials. Debug CA,
fixture activities and cleartext fixture support do not ship in release APKs.
Private TLS keys stay in the test output directory, outside the repository.

Touch mouse verification additionally passed eight live input checks against
mouse events recorded by a separate Chrome page, plus five pointer-lock safety
and settings checks. This includes real Android drag injection, click/right-click,
scrolling, held-button cancellation and coordinate correctness after zoom.
The original controller failure was reproduced before adding the alternative.
These are emulator results; Bluetooth/USB pointer capture is separate work.

Fullscreen verification passed twelve checks across phone, foldable and tablet
layouts, including live rotation, Back/exit handling and keyboard geometry
retention and interrupted-resize recovery. Measured viewer coverage improved from 64% on the foldable fixture
to approximately 100% across the tested portrait/landscape layouts. The remote
desktop resized in the existing Chrome session. Touch mouse checks also passed
with the adaptive desktop dimensions. Fullscreen background/resume and the
floating mouse/fit controls were checked separately. The keyboard control passed
three consecutive open/close attempts after correcting menu focus restoration.

On 2026-10-13 UTC, mouse-mode panning and keyboard zoom loss were reproduced with
real WebView touch input before the fix. Three-finger dragging now pans locally;
the pointer follows only when explicitly moved, so periodic cursor painting no
longer pulls the view back. The viewer retains its pre-keyboard fit scale and
pan offsets while the IME crops the visible area. Pinch, direct-touch panning,
normal mouse clicks, page scrolling and cancellation retain their controls.

Validation passed 102 gesture/keyboard checks across normal and fullscreen phone,
foldable and tablet layouts; five direct-touch checks; eight remote Chrome mouse
event checks; twelve fullscreen checks; and seven browser gateway checks.
The scroll regression uses continuous touch movement across frames, rather than
relying on a single synthetic wheel tick. The release APK build and signature
checks passed, with debug fixtures excluded. These adapter changes are served by
the backend; the existing APK already provides the required viewport/IME state.
Reconnect the Browser viewer to fetch the updated adapters.

Physical-device keyboard/network transitions, thermal/battery behavior and
signed-in website behavior require their own verification; a desktop browser or
emulator result must not be substituted for them. Existing account sync state
and website-session expiration are not changed or certified by the viewer test.

On 2026-10-13, the production desktop viewer passed native mouse/keyboard input,
four simulated-controller round trips and four Android BrowserScreen round trips
in an isolated profile. The same Chrome process/runtime, selected secondary tab,
synthetic session and persistent cookies, live JavaScript memory and unsaved text
survived every switch. Taking control from a foreground Android viewer revoked
its old access and prevented its automatic reconnect from stealing control back.
The held-modifier takeover check, daemon restart/reconnect, repeated phone-release
and desktop-window close checks also passed. Actual Google account authentication
was not inspected; the authentication fixture used synthetic cookies in disposable
browser data, with its test-only basic password store.

All 622 Rust tests passed with four test threads. The first default-parallel run
timed out in the existing large TMUX paste test; that unchanged test passed on
its own and in the complete rerun. Eight desktop configuration/credential/
distribution regressions and the existing installer/runtime suites passed. A
fresh Ubuntu 22.04 installation provisioned GTK/WebKit, the launcher/commands,
Chrome, all agent CLIs and MCP; repeat installation preserved private configuration,
identity and token, retained unrelated MCP entries, and repaired damaged viewer
assets.

The complete payload was then installed locally and verified through the existing
trusted HTTPS origin. The installed desktop viewer connected successfully. Both
Chrome owners/runtime identities, browser configuration, backend token and four
database inodes were preserved. That desktop-only update required no new Android APK.

The subsequent Android-button update does require its new APK. Validation passed
626 Rust tests, the Android browser security/migration tests, nine desktop/
distribution checks, and the full clean Ubuntu installer including the graphical
session service. Live Android tests passed four button-initiated round trips,
launching a closed viewer, background/resume without retaking control, unavailable
desktop retention of the same WebView, and recovery after a desktop claim fails.
A replay of that cancelled transfer's ID was rejected through the actual API.
The Chrome process, selected tab, synthetic authentication, live memory and draft
remained unchanged. The release APK retained the existing signing identity and
excluded the isolated test activities/token/CA. Real Google authentication and
physical-device performance were not inspected by those tests.
