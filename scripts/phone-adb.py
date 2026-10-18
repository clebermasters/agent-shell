#!/usr/bin/env python3
"""Resolve current phone network addresses and ADB endpoints through AgentShell."""

import argparse
import ipaddress
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


class PhoneAdbError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep the API token on the configured server."""

    def redirect_request(self, request, response, code, message, headers, new_url):
        raise PhoneAdbError("The backend redirected the request; use its final URL with --server.")


def fetch_devices(server, token):
    parts = urllib.parse.urlsplit(server)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise PhoneAdbError("--server must be an http:// or https:// backend URL.")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise PhoneAdbError("Use a server URL without credentials, query parameters, or a fragment.")
    if not token:
        raise PhoneAdbError("Set AUTH_TOKEN or AGENTSHELL_AUTH_TOKEN to the backend token.")
    request = urllib.request.Request(
        server.rstrip("/") + "/api/devices",
        headers={"X-Auth-Token": token, "Accept": "application/json"},
    )
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=5) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise PhoneAdbError("Backend rejected the auth token (HTTP 401).") from None
        raise PhoneAdbError(f"Backend returned HTTP {error.code}; check the URL and backend version.") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise PhoneAdbError("Cannot reach the backend; check --server and your network connection.") from None
    except (ValueError, UnicodeError):
        raise PhoneAdbError("Backend returned invalid JSON; check the URL and backend version.") from None
    devices = payload.get("devices") if isinstance(payload, dict) else None
    if not isinstance(devices, list) or any(not isinstance(device, dict) for device in devices):
        raise PhoneAdbError("Backend response does not contain a devices list.")
    return devices


def select_device(devices, device_id=None):
    if device_id:
        matches = [device for device in devices if device.get("deviceId") == device_id]
        if len(matches) != 1:
            raise PhoneAdbError("Device ID not found. Run with --list to see reporting phones.")
        device = matches[0]
        if device.get("status") != "available":
            raise PhoneAdbError(f"Phone ADB endpoint is {device.get('status', 'unavailable')}; open AgentShell and enable Wireless debugging.")
        return device
    available = [device for device in devices if device.get("status") == "available"]
    if not available:
        raise PhoneAdbError("No current phone ADB endpoint. Open AgentShell, connect it to this backend, and enable Wireless debugging.")
    if len(available) != 1:
        raise PhoneAdbError("Multiple phones are available. Use --list, then select one with --device ID.")
    return available[0]


def endpoint_address(device):
    endpoint = device.get("endpoint")
    if not isinstance(endpoint, dict):
        raise PhoneAdbError("Backend did not provide an ADB endpoint.")
    try:
        if not isinstance(endpoint["host"], str):
            raise ValueError("IP address must be a string")
        host = ipaddress.ip_address(endpoint["host"])
        unicast = host.ipv4_mapped if isinstance(host, ipaddress.IPv6Address) and host.ipv4_mapped else host
        port = endpoint["port"]
        if unicast.is_unspecified or unicast.is_loopback or unicast.is_multicast or str(unicast) == "255.255.255.255":
            raise ValueError("Invalid device address")
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("Invalid device port")
    except (KeyError, ValueError, TypeError):
        raise PhoneAdbError("Backend provided an invalid ADB IP address or port.") from None
    return f"[{host}]:{port}" if host.version == 6 else f"{host}:{port}"


def network_addresses(device, transport):
    """Return reported addresses, without implying that they are reachable."""
    if device.get("networkStatus") != "current":
        return []
    networks = device.get("networks")
    if not isinstance(networks, list):
        raise PhoneAdbError("Backend did not provide a valid phone networks list.")
    addresses = set()
    for network in networks:
        if not isinstance(network, dict):
            raise PhoneAdbError("Backend provided invalid phone network metadata.")
        if network.get("transport") != transport:
            continue
        values = network.get("addresses")
        if not isinstance(values, list):
            raise PhoneAdbError("Backend provided invalid phone network addresses.")
        for value in values:
            # Reuse endpoint validation; this temporary port is never returned or contacted.
            endpoint_address({"endpoint": {"host": value, "port": 1}})
            host = ipaddress.ip_address(value)
            if isinstance(host, ipaddress.IPv6Address) and (host.is_link_local or host.scope_id):
                raise PhoneAdbError("Phone reported an IPv6 address requiring a local interface scope.")
            addresses.add(str(host))
    return sorted(addresses, key=lambda value: (ipaddress.ip_address(value).version, value))


def select_network_address(devices, transport, device_id=None):
    if device_id:
        matches = [device for device in devices if device.get("deviceId") == device_id]
        if len(matches) != 1:
            raise PhoneAdbError("Device ID not found. Run with --list to see reporting phones.")
        device = matches[0]
        state = device.get("networkStatus", "unknown")
        if state != "current":
            raise PhoneAdbError(f"Phone network report is {state}; update/open AgentShell and connect it to this backend.")
        candidates = network_addresses(device, transport)
    else:
        matches = [(device, network_addresses(device, transport)) for device in devices]
        matches = [(device, values) for device, values in matches if values]
        if len(matches) > 1:
            raise PhoneAdbError("Multiple phones report this network. Use --list, then select one with --device ID.")
        candidates = matches[0][1] if matches else []
    if not candidates:
        raise PhoneAdbError(f"No current {transport} address reported. Update/open AgentShell and enable that network on the phone.")
    ipv4 = [value for value in candidates if ipaddress.ip_address(value).version == 4]
    preferred = ipv4 or candidates
    if len(preferred) != 1:
        raise PhoneAdbError(f"Multiple {transport} addresses reported; inspect --list and choose an explicit address for ADB.")
    return preferred[0]


def connect_adb(address):
    try:
        result = subprocess.run(["adb", "connect", address], capture_output=True, text=True, timeout=15)
        if result.stdout.strip():
            print(result.stdout.strip(), file=sys.stderr)
        # adb connect sometimes exits successfully even when connection failed.
        state = subprocess.run(["adb", "-s", address, "get-state"], capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        raise PhoneAdbError("adb is missing. Install Android SDK Platform Tools on this computer.") from None
    except subprocess.TimeoutExpired:
        raise PhoneAdbError("ADB connection timed out; the computer needs a route to the phone's reported IP.") from None
    if result.returncode or state.returncode or state.stdout.strip() != "device":
        raise PhoneAdbError("ADB could not connect to an authorized device. Check network reachability and pair this computer with the phone first.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default=os.environ.get("AGENTSHELL_URL", "http://127.0.0.1:4010"))
    parser.add_argument("--device", default=os.environ.get("AGENTSHELL_DEVICE_ID"), help="Persistent phone ID from --list")
    parser.add_argument("--list", action="store_true", help="Print reporting phones as JSON")
    parser.add_argument("--connect", action="store_true", help="Connect and verify ADB before printing the address")
    parser.add_argument("--transport", choices=("vpn", "cellular", "wifi", "ethernet", "other"), help="Select a reported phone network instead of Wi-Fi ADB discovery")
    parser.add_argument("--ip", action="store_true", help="Print the selected network IP without a port or ADB connection")
    parser.add_argument("--port", type=int, help="Explicit, previously enabled ADB TCP port on the selected network")
    parser.add_argument("adb_args", nargs=argparse.REMAINDER, help="Optional ADB command, e.g. install -r app.apk")
    args = parser.parse_args(argv)
    if args.adb_args[:1] == ["--"]:
        args.adb_args = args.adb_args[1:]
    if args.list and (args.connect or args.adb_args or args.transport or args.ip or args.port is not None):
        parser.error("--list cannot be combined with connection or network selection options")
    if (args.ip or args.port is not None) and not args.transport:
        parser.error("--ip and --port require --transport")
    if args.transport and not (args.ip or args.port is not None):
        parser.error("--transport requires --ip or an explicitly configured ADB --port")
    if args.ip and (args.connect or args.adb_args or args.port is not None):
        parser.error("--ip cannot be combined with a port, --connect, or an ADB command")
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    try:
        token = os.environ.get("AGENTSHELL_AUTH_TOKEN") or os.environ.get("AUTH_TOKEN", "")
        devices = fetch_devices(args.server, token)
        if args.list:
            print(json.dumps({"devices": devices}, indent=2))
            return 0
        if args.transport:
            host = select_network_address(devices, args.transport, args.device)
            if args.ip:
                print(host)
                return 0
            address = endpoint_address({"endpoint": {"host": host, "port": args.port}})
        else:
            address = endpoint_address(select_device(devices, args.device))
        if args.connect or args.adb_args:
            connect_adb(address)
        if args.adb_args:
            return subprocess.run(["adb", "-s", address, *args.adb_args]).returncode
        print(address)
        return 0
    except (PhoneAdbError, OSError) as error:
        print(f"phone-adb: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
