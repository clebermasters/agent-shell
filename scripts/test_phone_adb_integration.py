#!/usr/bin/env python3
"""HTTP/WebSocket smoke against an isolated, locally built Rust backend."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid

try:
    import websocket
except ImportError:
    websocket = None

spec = importlib.util.spec_from_file_location("phone_adb", Path(__file__).with_name("phone-adb.py"))
phone_adb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phone_adb)
BINARY = Path(__file__).resolve().parents[1] / "backend-rust/target/debug/agentshell-backend"


@unittest.skipUnless(websocket is not None and BINARY.exists(), "Build backend and install websocket-client for smoke test")
class DeviceAdbIntegrationTest(unittest.TestCase):
    def test_authenticated_live_reporting_rotation_and_disconnect(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        token = uuid.uuid4().hex
        base_url = f"http://127.0.0.1:{port}"
        device_id = str(uuid.uuid4())
        report = {"type": "report-device-adb", "deviceId": device_id, "deviceName": "Smoke phone", "host": "192.168.1.50", "port": 37123}
        connections = []
        with tempfile.TemporaryDirectory(prefix="agentshell-adb-smoke-") as directory:
            with (Path(directory) / "backend.log").open("w+") as log:
                environment = dict(os.environ, AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(port), AGENTSHELL_TLS_CERT_PATH=directory + "/missing-cert", RUST_LOG="error")
                process = subprocess.Popen([str(BINARY)], cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 15
                    while True:
                        try:
                            devices = phone_adb.fetch_devices(base_url, token)
                            break
                        except phone_adb.PhoneAdbError:
                            if process.poll() is not None or time.monotonic() >= deadline:
                                self.fail("Isolated backend failed to start")
                            time.sleep(0.1)
                    self.assertEqual(devices, [])
                    with self.assertRaises(urllib.error.HTTPError) as rejected:
                        urllib.request.urlopen(base_url + "/api/devices", timeout=3)
                    self.assertEqual(rejected.exception.code, 401)

                    def open_socket():
                        connection = websocket.create_connection(f"ws://127.0.0.1:{port}/ws", header=["X-Auth-Token: " + token], timeout=5)
                        connections.append(connection)
                        return connection

                    def wait_for(status, endpoint=None):
                        deadline = time.monotonic() + 5
                        while time.monotonic() < deadline:
                            devices = phone_adb.fetch_devices(base_url, token)
                            if len(devices) == 1 and devices[0]["status"] == status:
                                if endpoint is None or devices[0]["endpoint"]["address"] == endpoint:
                                    return devices[0]
                            time.sleep(0.05)
                        self.fail(f"Expected device status {status}")

                    old = open_socket()
                    old.send(json.dumps(report))
                    snapshot = wait_for("available", "192.168.1.50:37123")
                    self.assertEqual(phone_adb.endpoint_address(snapshot), "192.168.1.50:37123")
                    self.assertEqual(snapshot["networkStatus"], "unknown")
                    self.assertEqual(snapshot["networks"], [])
                    request = urllib.request.Request(base_url + f"/api/devices/{device_id}/adb", headers={"X-Auth-Token": token})
                    with urllib.request.urlopen(request, timeout=3) as response:
                        self.assertEqual(response.headers["Cache-Control"], "no-store")
                        self.assertEqual(json.load(response), snapshot)

                    current = open_socket()
                    current.send(json.dumps(dict(report, port=41234)))
                    wait_for("available", "192.168.1.50:41234")
                    old.close()
                    old = None
                    wait_for("available", "192.168.1.50:41234")
                    cellular = {"transport": "cellular", "interface": "rmnet0", "addresses": ["100.95.1.2"]}
                    vpn = {"transport": "vpn", "interface": "tun0", "addresses": ["10.8.0.4"]}
                    current.send(json.dumps(dict(report, host=None, port=None, networks=[cellular, vpn])))
                    network_snapshot = wait_for("unavailable")
                    self.assertIsNone(network_snapshot["endpoint"])
                    self.assertEqual(network_snapshot["networkStatus"], "current")
                    self.assertEqual(phone_adb.select_network_address([network_snapshot], "vpn"), "10.8.0.4")
                    request = urllib.request.Request(base_url + f"/api/devices/{device_id}/network", headers={"X-Auth-Token": token})
                    with urllib.request.urlopen(request, timeout=3) as response:
                        self.assertEqual(response.headers["Cache-Control"], "no-store")
                        metadata = json.load(response)
                        self.assertEqual(metadata["networkStatus"], "current")
                        self.assertEqual(metadata["networks"], [cellular, vpn])

                    vpn["addresses"] = ["10.8.0.9"]
                    current.send(json.dumps(dict(report, host=None, port=None, networks=[cellular, vpn])))
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        network_snapshot = phone_adb.fetch_devices(base_url, token)[0]
                        if phone_adb.select_network_address([network_snapshot], "vpn") == "10.8.0.9":
                            break
                        time.sleep(.05)
                    else:
                        self.fail("VPN address did not rotate")

                    current.send(json.dumps(dict(report, host=None, port=None, networks=[cellular])))
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        network_snapshot = phone_adb.fetch_devices(base_url, token)[0]
                        if phone_adb.network_addresses(network_snapshot, "vpn") == []:
                            break
                        time.sleep(.05)
                    else:
                        self.fail("Lost VPN address was retained")
                    self.assertEqual(phone_adb.select_network_address([network_snapshot], "cellular"), "100.95.1.2")
                    current.send(json.dumps(dict(report, port=41999)))
                    wait_for("available", "192.168.1.50:41999")
                    current.close()
                    offline = wait_for("offline")
                    self.assertIsNone(offline["endpoint"])
                    self.assertEqual(offline["networkStatus"], "offline")
                    self.assertEqual(offline["networks"], [])
                finally:
                    for connection in connections:
                        connection.close()
                    process.terminate()
                    try:
                        process.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == "__main__":
    unittest.main()
