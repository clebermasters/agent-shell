# Phone network and ADB discovery

AgentShell reports its phone's current Wi-Fi, cellular, and VPN addresses to the
Rust backend. It separately discovers the Wi-Fi wireless debugging connection
endpoint. Computer sessions retrieve current information when they need it.
The phone uses native Kotlin and Android network service discovery; the helper
uses Python's standard library. No JavaScript is involved.

## Setup

1. Install a build containing the phone ADB reporter and run the updated backend.
2. Connect AgentShell on the phone to that backend.
3. Enable **Developer options → Wireless debugging** on the phone.
4. Pair the computer once using Android's **Pair device with pairing code** and
   `adb pair IP:PAIRING_PORT`. The pairing port differs from the connection port.
5. Give the computer a route to the phone's Wi-Fi address, normally by joining
   the same LAN. Relaying an address through the backend does not tunnel ADB.

Network reporting works while the phone is on cellular data, including when
BitSlovers VPN is connected. It reports each visible network's transport,
interface, and numeric IPv4/IPv6 addresses. These are interface addresses, not a
guarantee that the computer can reach them. In particular, a carrier address may
be behind carrier-grade NAT. BVPN supplies a private address on its own network;
use the phone's reported VPN address, not the computer's BVPN address.

For Wi-Fi ADB, the app discovers `_adb-tls-connect._tcp`, accepts only addresses belonging to
its own Wi-Fi interface, and reports the connection endpoint over its existing
authenticated WebSocket. Other phones' advertisements are ignored. It reports
updates and refreshes its snapshot every 30 seconds while its connection is
enabled. The backend expires reports after 90 seconds and marks closed reporting
connections offline. Reports live in memory and must be republished after a
backend restart.

Wi-Fi ADB discovery depends on Android/OEM mDNS behavior, including discovering the phone's
own advertisement. If the API says unavailable, check that Wireless debugging
and Wi-Fi are enabled and AgentShell is connected. An unavailable report never
falls back to a guessed port such as 5555. Android 11 or newer is required for
this TLS wireless debugging discovery.

## Use from a session

Set these variables in the shell that runs the extractor. Use the token already
configured for that backend; keep it out of command arguments and URLs.

```sh
export AGENTSHELL_URL=http://127.0.0.1:4010
export AUTH_TOKEN='your-backend-token'

# List device IDs and availability.
python3 scripts/phone-adb.py --list

# Fetch the current address, connect, and verify ADB authorization.
python3 scripts/phone-adb.py --connect

# Report the current BVPN/private VPN IP, even when ADB is unavailable.
python3 scripts/phone-adb.py --transport vpn --ip

# Report the phone's cellular interface address for diagnostics.
python3 scripts/phone-adb.py --transport cellular --ip

# Connect, then install specifically on this phone (even with an emulator open).
python3 scripts/phone-adb.py install -r path/to/app.apk
```

If several phones are available, pass `--device DEVICE_ID` or set
`AGENTSHELL_DEVICE_ID`. The helper refuses to choose between multiple available
phones. `AGENTSHELL_AUTH_TOKEN` can be used instead of `AUTH_TOKEN`.

To wrap an existing extractor that uses ADB's `ANDROID_SERIAL` variable:

```sh
run_phone_extractor() {
    local serial
    serial=$(python3 /absolute/path/to/webmux/scripts/phone-adb.py --connect) || return
    ANDROID_SERIAL="$serial" your-extractor "$@"
}
```

Replace `your-extractor` with the actual command. Every invocation fetches the
current endpoint and stops if it is unavailable or ADB cannot connect.

## ADB over BVPN while using cellular

Reporting a VPN IP does not start an ADB listener. Android's standard Wireless
debugging stops when Wi-Fi disconnects. To test cellular ADB, first enable TCP
ADB through an already-authorized USB or working Wi-Fi ADB connection:

```sh
adb -s CURRENT_SERIAL tcpip 5555
```

Connect both devices to the same BVPN server. Then, after switching the phone
to cellular, use the enabled port explicitly:

```sh
python3 scripts/phone-adb.py --transport vpn --port 5555 --connect
python3 scripts/phone-adb.py --transport vpn --port 5555 shell getprop ro.product.model
```

The helper resolves the current VPN IP for each invocation and verifies the ADB
connection. It never assumes that port 5555, or any other port, is open merely
because the phone reported a VPN address. The BVPN server must allow traffic
between these clients; its internet forwarding rules alone do not prove that.
Keep legacy TCP ADB inside the private VPN. Bootstrap may need repeating after
a phone reboot, and Samsung firmware behavior needs a real cellular test.

`--ip` is a read-only address lookup and cannot be combined with ADB commands.
It prefers IPv4 when both IP families are reported and refuses ambiguous phones
or multiple addresses within the preferred family. Helper flags must precede
the ADB subcommand. No Wi-Fi address is substituted when a VPN disappears.

## HTTP / WebSocket contract

All routes require the existing backend `X-Auth-Token` authentication. The backend
currently has one shared token; device visibility follows that same trust scope.

- `GET /api/devices` returns `{ "devices": [ ... ] }`.
- `GET /api/devices/:deviceId/adb` returns one device snapshot, or HTTP 404.
- `GET /api/devices/:deviceId/network` returns network metadata, or HTTP 404.

Example available device snapshot:

```json
{
  "deviceId": "9c1e2e4a-f0a5-4e17-9b4b-af60a850088f",
  "deviceName": "Samsung SM-F966U",
  "status": "available",
  "networkStatus": "current",
  "networks": [
    {"transport": "wifi", "interface": "wlan0", "addresses": ["192.168.0.187"]},
    {"transport": "vpn", "interface": "tun0", "addresses": ["10.8.0.4"]}
  ],
  "endpoint": {
    "host": "192.168.0.187",
    "port": 35567,
    "address": "192.168.0.187:35567"
  },
  "updatedAt": 1792200000
}
```

Other statuses are `unavailable`, `offline`, and `stale`; their `endpoint` is
`null`. `updatedAt` is backend receipt time in Unix seconds.

`networkStatus` is independent of ADB `status`: it is `current` for a fresh,
connected network report, `unknown` for older clients that omit network data,
and `offline` or `stale` when the report cannot be used. Expired/disconnected
reports expose `networks: []`. A cellular phone can have `status: unavailable`,
`endpoint: null`, and `networkStatus: current` with its BVPN address present.
Network status describes reporting freshness, not end-to-end reachability.

The `/network` route returns `deviceId`, `deviceName`, `networkStatus`, `networks`,
and `updatedAt`. Transport values are `wifi`, `cellular`, `vpn`, `ethernet`, and
`other`. Interface identifiers and addresses are bounded; credentials, SSIDs,
MAC addresses, and carrier identifiers are not reported.

The app publishes the following authenticated WebSocket message. It sends both
`host` and `port` as `null` when no current endpoint is known. `deviceId` is an
installation UUID, not a hardware identifier.

```json
{
  "type": "report-device-adb",
  "deviceId": "9c1e2e4a-f0a5-4e17-9b4b-af60a850088f",
  "deviceName": "Samsung SM-F966U",
  "networks": [
    {"transport": "cellular", "interface": "rmnet0", "addresses": ["100.95.1.2"]},
    {"transport": "vpn", "interface": "tun0", "addresses": ["10.8.0.4"]}
  ],
  "host": null,
  "port": null
}
```

Android references: [wireless ADB setup](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi),
[ADB Wi-Fi architecture and advertised service types](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md),
and [native network service discovery](https://developer.android.com/reference/android/net/nsd/NsdManager).

## Verification

```sh
cd backend-rust
cargo test device_adb
cd ..
python3 -m unittest discover -s scripts -p 'test_phone_adb*.py'
cd android-native
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests com.agentshell.data.services.DeviceAdbReporterTest -PuiPoc=true
```

The HTTP/WebSocket smoke requires a current `backend-rust/target/debug/agentshell-backend`
binary and the Python `websocket-client` test dependency. It starts a separate
backend on a temporary port with a temporary data directory and test token.

Native self-discovery was also verified on a Samsung SM-F966U running Android 17
(API 37): the app reported the live connection endpoint, the API returned it,
and the helper connected and verified ADB authorization. The temporary debug
app and port forwarding used for that check were removed afterward.

The extended release was then installed on the same phone. Its live API reported
Wi-Fi, cellular, and BVPN addresses independently. After authorized Wi-Fi ADB
enabled TCP port 5555, Wi-Fi was disabled and BVPN assigned a new address. The
global find-phone helper picked up that address and successfully executed
`shell getprop ro.product.model` over cellular+BVPN without restarting ADB.
This verifies network handoff on this Samsung; it does not establish automatic
ADB recovery after reboot.
