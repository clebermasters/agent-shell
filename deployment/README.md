# Backend and Smart UI installation reference

Run **one script** from a clone of this repository:

```bash
./install.sh
```

Supported hosts are Ubuntu/Pop!_OS 22.04+, Debian 12+, and compatible Debian-based
Linux systems with Bash, apt and systemd. Automatic Node installation supports
x64 and ARM64. The first installation needs internet access and sudo/root access
for system packages and service files. Run as your normal account so the service,
agents, conversation logs and MCP registration use that account.

## What the script installs

1. Missing Python, system build tools, OpenSSL dependencies, TMUX, Git, cron,
   FFmpeg, ripgrep and Python venv support. The native MCP refresh library is
   pinned in an isolated `/opt/agentshell/venv`; user/system Python packages are
   retained. The stdio Smart UI publisher itself uses the Python standard library.
2. Rust when missing or below the build minimum, plus Node/npm when selected
   agents need them. Working compatible installations are retained.
3. Missing or unusable **Codex, Claude Code and OpenCode 1** CLIs. New CLI packages
   use a service-user-owned prefix under `/opt/agentshell/tools`; managed Node
   and npm cache also stay under `/opt/agentshell`. Existing working agent
   commands are retained. Custom launchers are not overwritten. A managed PATH
   file is sourced from the service user's shell profiles for new terminals;
   existing profile content is retained and backed up.
4. The Rust backend, built with `Cargo.lock`, and every repository Smart UI asset:
   host/runtime JavaScript, stylesheets, Three.js, Leaflet, marker images and
   licenses. A local TLS pair is generated when absent; existing pairs are kept.
5. Native identity hooks, Codex's full conversation ID footer, the **agentshell-ui**
   stdio MCP server, and private configuration using this machine's token.
6. MCP registration for the selected agents. Existing servers and settings are
   merged; OpenCode JSONC comments and unrelated entries are retained.
7. The systemd service, an authenticated HTTP startup check, and a native MCP
   configuration refresh for a live local Codex daemon. Existing model turns are
   not restarted. If refresh fails, the script explicitly reports the pending
   reload rather than silently claiming a running client is updated.

The script verifies the installed MCP handshake, both tools and design system
without publishing a card into a user conversation. New agent accounts still
need their normal provider login/API configuration. Codex's normal hook review
applies; no hook-trust bypass is configured.

## Versioned references and backups

The repository is the installation reference. No copy of a current machine's
credentials is needed to recreate its software setup.

| Reference | Purpose |
| --- | --- |
| [backend-dependencies.json](backend-dependencies.json) | System packages, pinned download versions, agent npm packages and source-to-install mappings. |
| [templates/](templates/) | Credential-free service, runtime, MCP registration and optional agent-guidance examples. |
| [MCP publisher](../scripts/agent-ui-mcp.py) | Canonical source installed as `ui/agent-ui-mcp.py`. |
| [Identity hook](../scripts/chat-binding-hook.py) | Canonical source installed as `hooks/chat-binding-hook.py`. |
| [Native MCP refresh](../scripts/reload-codex-mcp.py) | Locates the current daemon socket and queues a configuration refresh. |
| [UI assets](../android-native/app/src/main/assets/ui/) | Complete renderer/library reference, bundled in Android and copied by backend installation. |
| [Cargo.lock](../backend-rust/Cargo.lock) | Rust dependency versions for the backend build. |

The dependency manifest pins fresh-install versions. Existing working versions
are not automatically downgraded to match it. Node archives are checked against
the official SHA-256 list; npm verifies downloaded package integrity. Updating
these pins is a repository change that should be validated before deployment.

Changed installed files receive timestamped `.agentshell-backup-*` copies beside
the original. Agent configuration installers also keep their existing backup
conventions. `/opt/agentshell/installation.json` records source file hashes and
the dependency manifest; it contains no runtime credentials.

## Files kept outside Git

| Location | Contents |
| --- | --- |
| `/opt/agentshell/backend/runtime.env` | Authentication, allowed origin, logging and ports; mode `0600`. |
| `~/.config/agentshell/interactive-ui.json` | Local publisher URL and authentication; mode `0600`. |
| `~/.config/agentshell/chat-binding-hook.json` | Local identity-hook URL and authentication. |
| Agent configuration in the service user's home | Merged MCP entries and identity hooks; unrelated configuration is preserved. |
| `/opt/agentshell/backend/*.db` | Existing chat history, bindings and other application data; installation does not replace these databases. |
| `/opt/agentshell/certs/key.pem` | This machine's private TLS key; mode `0600`. |

Token precedence is an explicit `AUTH_TOKEN` environment value, then the
gitignored repository `.env`, then existing runtime/MCP configuration or the
legacy service-unit token. An absent/placeholder token generates a new private
token. Reinstallation retains an existing token. For a new machine, configure
Android with that machine's address and token; credentials are not embedded in
the reference templates or copied from this computer into Git.

## Useful options

```bash
./install.sh --check                         # Read-only dependency/source report
./install.sh --deps-only                     # Provision dependencies without deploying
./install.sh --agents codex                  # Install/register only Codex
./install.sh --agents installed              # Register working existing agents
./install.sh --agents none                   # Backend/MCP files, no agent CLI packages
./install.sh --no-start                      # Prepare an image without starting systemd
./install.sh --skip-dependencies             # Use an already provisioned host
./install.sh --binary /path/to/backend       # Deploy a prebuilt backend
```

`--install-dir`, `--unit-dir`, `--service-user`, `--http-port` and `--https-port`
support custom deployments. `--no-tls` skips generating a missing TLS pair;
it does not delete existing certificates. On startup failure, previous backend,
service and runtime files are restored when their backups exist.

Android's renderer and libraries remain part of the APK; Android System WebView
is managed on the device. The backend script does not install an Android SDK,
build an APK or modify a phone. See [interactive UI](../docs/interactive-ui.md).

## Checks

```bash
python3 scripts/test_backend_install.py
python3 scripts/test_ui_mcp.py
```

Installer validation must include a clean Linux environment with dependencies
missing, a second install preserving token/history/settings, installed asset
hashes, MCP tools/design-system discovery, and authenticated backend access.
Never use production credentials or user databases in that clean-machine test.

### Validation record: 2026-10-10

A clean Ubuntu 22.04 container started without Python or backend/agent tooling.
`./install.sh --no-start` installed dependencies, built the backend from source,
copied all Smart UI files and registered Codex, Claude and OpenCode MCP. The
isolated runtime pins WebSockets 14.2 because Ubuntu's older packaged library
failed against Python 3.10 during the actual transport test.

`scripts/test_fresh_backend_install.py` passed repeat-install, existing
history/token, unrelated MCP, JSONC comments, shell PATH, private file modes,
system-directory ownership, service validation, 20 file hashes, MCP discovery,
design-system, authenticated HTTP and real WebSocket checks. This script has a
container guard and requires `AGENTSHELL_INSTALL_TEST=1`.

The same installer was then run on the existing Pop!_OS host. Its real systemd
startup check caught an environment-file formatting issue and restored the
previous service. Scalar service paths were corrected and validation was added
before restart. The final run passed authentication and health checks and
refreshed the existing Codex MCP connection. Current agent conversations and
history stayed in place. Nine installer/configuration tests and seven MCP
adapter tests passed.
