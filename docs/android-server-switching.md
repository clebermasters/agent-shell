# Android server switching

Tap the **Server** button in the main header, then tap a server to connect immediately. The selector is available on Sessions, Cron, Dotfiles and System. It shows the current server and connection status. **Manage servers** opens the existing add/edit/delete screen directly.

Switching replaces the Home navigation entry so session lists, open dialogs and screen ViewModels reload for the selected host. Automatic attachment to the last terminal is suppressed for a deliberate switch. Shared system statistics are cleared. WebSocket commands buffered for the previous server are discarded, while normal reconnection to the same server retains its queue.

## Build defaults

The private, gitignored repository-root `.env` contains `SERVER_LIST`. Both the local Gradle generator (`android-native/app/build.gradle.kts`) and Docker build use it to generate `core/config/BuildConfig.kt`, which is also gitignored. For example:

```dotenv
SERVER_LIST=public.example.com:443,Public|192.168.0.38:4010,Homelab|192.168.0.76:4010,Local LAN
```

Append entries separated by `|`; each entry is `address:port,Label` or `https://address:port,Label`. Port 443 defaults to TLS; other legacy entries default to plain HTTP/WebSocket. An explicit `https://` enables TLS on ports such as 4443. TLS can also be changed in the server editor. LAN addresses require access to that network. See [direct LAN HTTPS setup](lan-browser-https.md) for browser streaming and automatic certificate provisioning.

Newly bundled endpoints are imported on upgrades as well as first installation. Existing endpoint matches keep their IDs, names, order and selection. Moving a previously imported, unchanged default to a new endpoint under the same label preserves its host ID and selection, including the LAN HTTP-to-HTTPS upgrade. A preference ledger records processed default endpoints so later edits/deletions are respected. Older APKs did not store this ledger, so their first upgrade reconciles the bundled defaults once; historically deleted defaults cannot be distinguished from defaults that were never imported. Copy `.env` securely when building on another computer; it contains private credentials and is not part of Git.

## Verification

`HostRepositoryTest` covers upgrade imports, duplicate suppression, preserved selection, concurrent startup, transport identity and subsequent user edits/deletions. `scripts/test_android_server_switching.py` exercises the production navigation and WebSocket stack against three owned loopback fake servers in the `agentshell-ui-poc` emulator. It checks switching from the main header without Settings, host-specific data refresh, switching during an offline queue, returning to a previous server and persistence after process restart. Its activity exists only under `src/debug` and requires `-PuiPoc=true`; the release APK excludes it.
