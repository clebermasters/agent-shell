#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import MagicMock, patch


spec = importlib.util.spec_from_file_location("phone_adb", Path(__file__).with_name("phone-adb.py"))
phone_adb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phone_adb)


def phone(device_id="phone-1", status="available", host="192.168.1.50", port=37123):
    return {
        "deviceId": device_id,
        "deviceName": "My phone",
        "status": status,
        "endpoint": {"host": host, "port": port} if status == "available" else None,
    }


def cellular_phone(device_id="phone-1", vpn_ip="10.8.0.4", network_status="current"):
    return dict(phone(device_id, "unavailable"), networkStatus=network_status, networks=[
        {"transport": "cellular", "interface": "rmnet0", "addresses": ["100.95.1.2"]},
        {"transport": "vpn", "interface": "tun0", "addresses": [vpn_ip]},
    ])


class PhoneAdbTests(unittest.TestCase):
    def test_api_uses_header_auth_and_preserves_server_prefix(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = io.BytesIO(json.dumps({"devices": [phone()]}).encode())
        with patch.object(phone_adb.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(phone_adb.fetch_devices("https://example.test/agentshell/", "secret"), [phone()])
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://example.test/agentshell/api/devices")
        self.assertEqual(request.get_header("X-auth-token"), "secret")
        self.assertNotIn("secret", request.full_url)

    def test_auth_token_never_follows_a_redirect(self):
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.test")

    def test_missing_auth_and_bad_server_fail_before_network(self):
        with patch.object(phone_adb.urllib.request, "build_opener", side_effect=AssertionError("No HTTP request")):
            for server, token in [("http://localhost:4010", ""), ("file:///tmp/data", "secret"), ("http://host/?token=secret", "secret")]:
                with self.subTest(server=server):
                    with self.assertRaises(phone_adb.PhoneAdbError):
                        phone_adb.fetch_devices(server, token)

    def test_ignores_expired_and_disconnected_phones(self):
        devices = [phone("stale", "stale"), phone("offline", "offline"), phone("live")]
        self.assertEqual(phone_adb.select_device(devices)["deviceId"], "live")
        for device_id in ("stale", "offline"):
            with self.assertRaises(phone_adb.PhoneAdbError):
                phone_adb.select_device(devices, device_id)

    def test_multiple_phones_require_explicit_selection(self):
        devices = [phone("one"), phone("two")]
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.select_device(devices)
        self.assertEqual(phone_adb.select_device(devices, "two")["deviceId"], "two")

    def test_ipv6_and_rotated_port_are_formatted_from_current_fields(self):
        device = phone(host="fd12::50", port=41999)
        device["endpoint"]["address"] = "192.168.1.50:5555"
        self.assertEqual(phone_adb.endpoint_address(device), "[fd12::50]:41999")

    def test_invalid_endpoints_never_reach_adb(self):
        for host, port in [("127.0.0.1", 42), ("0.0.0.0", 42), ("224.1.2.3", 42), ("::ffff:127.0.0.1", 42), ("255.255.255.255", 42), (3232235777, 42), ("192.168.1.5; touch /tmp/x", 42), ("192.168.1.5", 0), ("192.168.1.5", True), ("192.168.1.5", "123")]:
            with self.subTest(host=host, port=port):
                with self.assertRaises(phone_adb.PhoneAdbError):
                    phone_adb.endpoint_address(phone(host=host, port=port))

    def test_adb_zero_exit_without_authorized_device_is_failure(self):
        results = [subprocess.CompletedProcess([], 0, "failed to connect", ""), subprocess.CompletedProcess([], 1, "", "device not found")]
        with patch.object(phone_adb.subprocess, "run", side_effect=results), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(phone_adb.PhoneAdbError):
                phone_adb.connect_adb("192.168.1.50:37123")

    def test_connect_stdout_is_only_the_endpoint_for_extractors(self):
        results = [subprocess.CompletedProcess([], 0, "connected to phone", ""), subprocess.CompletedProcess([], 0, "device\n", "")]
        output = io.StringIO()
        with patch.object(phone_adb, "fetch_devices", return_value=[phone()]), patch.object(phone_adb.subprocess, "run", side_effect=results), contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(phone_adb.main(["--connect"]), 0)
        self.assertEqual(output.getvalue(), "192.168.1.50:37123\n")

    def test_adb_command_is_pinned_to_selected_phone_and_preserves_arguments(self):
        result = subprocess.CompletedProcess([], 7)
        with patch.object(phone_adb, "fetch_devices", return_value=[phone()]), patch.object(phone_adb, "connect_adb") as connect, patch.object(phone_adb.subprocess, "run", return_value=result) as run:
            self.assertEqual(phone_adb.main(["--", "install", "-r", "/tmp/my app.apk"]), 7)
        connect.assert_called_once_with("192.168.1.50:37123")
        run.assert_called_once_with(["adb", "-s", "192.168.1.50:37123", "install", "-r", "/tmp/my app.apk"])

    def test_cellular_vpn_ip_is_reported_even_when_adb_is_unavailable(self):
        device = cellular_phone()
        self.assertEqual(phone_adb.select_network_address([device], "vpn"), "10.8.0.4")
        self.assertEqual(phone_adb.select_network_address([device], "cellular"), "100.95.1.2")
        with patch.object(phone_adb, "fetch_devices", return_value=[device]), patch.object(phone_adb, "connect_adb", side_effect=AssertionError("IP lookup must not connect")), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(phone_adb.main(["--transport", "vpn", "--ip"]), 0)
        self.assertEqual(output.getvalue(), "10.8.0.4\n")

    def test_network_mode_ignores_stale_and_unknown_metadata(self):
        for state in ("offline", "stale", "unknown"):
            device = cellular_phone(network_status=state)
            with self.subTest(state=state):
                with self.assertRaises(phone_adb.PhoneAdbError):
                    phone_adb.select_network_address([device], "vpn", "phone-1")
                with self.assertRaises(phone_adb.PhoneAdbError):
                    phone_adb.select_network_address([device], "vpn")
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.select_network_address([phone()], "vpn")

    def test_vpn_loss_does_not_reuse_a_previous_address(self):
        device = cellular_phone()
        self.assertEqual(phone_adb.select_network_address([device], "vpn"), "10.8.0.4")
        device["networks"] = [device["networks"][0]]
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.select_network_address([device], "vpn")

    def test_multiple_network_devices_or_addresses_are_not_silently_selected(self):
        devices = [cellular_phone("one"), cellular_phone("two", "10.8.0.5")]
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.select_network_address(devices, "vpn")
        self.assertEqual(phone_adb.select_network_address(devices, "vpn", "two"), "10.8.0.5")
        devices[0]["networks"][1]["addresses"].append("10.8.0.6")
        with self.assertRaises(phone_adb.PhoneAdbError):
            phone_adb.select_network_address(devices[:1], "vpn")

    def test_network_mode_prefers_ipv4_and_supports_global_ipv6(self):
        device = cellular_phone()
        device["networks"][1]["addresses"].append("fd12::4")
        self.assertEqual(phone_adb.select_network_address([device], "vpn"), "10.8.0.4")
        device["networks"][1]["addresses"] = ["fd12::4"]
        self.assertEqual(phone_adb.select_network_address([device], "vpn"), "fd12::4")

    def test_network_mode_rejects_unsafe_or_scoped_addresses(self):
        for address in ("127.0.0.1", "::ffff:127.0.0.1", "fe80::4", "fd12::4%tun0", "10.8.0.4; echo test", True):
            with self.subTest(address=address):
                with self.assertRaises(phone_adb.PhoneAdbError):
                    phone_adb.select_network_address([cellular_phone(vpn_ip=address)], "vpn")

    def test_network_adb_requires_an_explicit_enabled_port(self):
        with patch.object(phone_adb, "fetch_devices", side_effect=AssertionError("No request expected")), contextlib.redirect_stderr(io.StringIO()):
            for arguments in (["--transport", "vpn", "--connect"], ["--port", "5555"], ["--ip"], ["--transport", "vpn", "--ip", "--connect"], ["--transport", "vpn", "--port", "0"]):
                with self.subTest(arguments=arguments):
                    with self.assertRaises(SystemExit) as error:
                        phone_adb.main(arguments)
                    self.assertEqual(error.exception.code, 2)

    def test_explicit_vpn_adb_command_uses_the_new_reported_address(self):
        for address in ("10.8.0.4", "10.8.0.9"):
            with self.subTest(address=address):
                with patch.object(phone_adb, "fetch_devices", return_value=[cellular_phone(vpn_ip=address)]), patch.object(phone_adb, "connect_adb") as connect, patch.object(phone_adb.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
                    self.assertEqual(phone_adb.main(["--transport", "vpn", "--port", "5555", "shell", "getprop", "ro.product.model"]), 0)
                connect.assert_called_once_with(address + ":5555")
                run.assert_called_once_with(["adb", "-s", address + ":5555", "shell", "getprop", "ro.product.model"])


if __name__ == "__main__":
    unittest.main()
