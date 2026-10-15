#!/usr/bin/env python3
"""Test the real Android navigation against owned fake daemons; never a physical phone.

Build with -PuiPoc=true and start the agentshell-ui-poc AVD on emulator-5560.
The debug fixture seeds three loopback hosts in the emulator's debug-app database.
"""
import argparse
import asyncio
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import parse_qs, urlsplit
import xml.etree.ElementTree as ET
import zipfile

import websockets

PROJECT = Path(__file__).resolve().parents[1]
ACTIVITY = "com.agentshell.debug/com.agentshell.ServerSwitchPocActivity"
HOSTS = {40199: "server-a", 40200: "server-b", 40201: "server-c"}


class Fixture:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.received = []
        self.results = {}

    async def adb(self, *args):
        p = await asyncio.to_thread(subprocess.run, ["adb", "-s", self.serial, *args], capture_output=True, check=True)
        return p.stdout

    async def nodes(self):
        await self.adb("shell", "uiautomator", "dump", "/data/local/tmp/server-switch-fixture.xml")
        return list(ET.fromstring(await self.adb("shell", "cat", "/data/local/tmp/server-switch-fixture.xml")).iter("node"))

    async def wait_text(self, text):
        deadline = asyncio.get_running_loop().time() + 30
        while asyncio.get_running_loop().time() < deadline:
            nodes = await self.nodes()
            if any(text in [n.get("text"), n.get("content-desc")] for n in nodes):
                return nodes
            await asyncio.sleep(.2)
        raise AssertionError("Missing UI text: " + text)

    async def click(self, label):
        nodes = await self.wait_text(label)
        n = next(n for n in nodes if label in [n.get("text"), n.get("content-desc")])
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        await self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    async def screenshot(self, name):
        (self.output / (name + ".png")).write_bytes(await self.adb("exec-out", "screencap", "-p"))

    async def switch(self, label):
        await self.click("Switch server")
        nodes = await self.wait_text("Manage servers")
        assert all(any(n.get("content-desc") == "Connect to " + name for n in nodes)
                   for name in ["Isolated UI POC", "HOMELAB fixture", "LOCAL_LAN fixture"])
        await self.click("Connect to " + label)

    async def handler(self, socket, name):
        assert parse_qs(urlsplit(socket.request.path).query).get("token") == ["isolated-ui-poc-only"]
        try:
            async for raw in socket:
                msg = json.loads(raw)
                kind = msg.get("type")
                self.received.append((name, kind))
                response = None
                if kind == "list-sessions":
                    response = {"type": "sessions-list", "sessions": [{"name": name + "-terminal", "windows": 1, "attached": False}]}
                elif kind == "acp-list-sessions":
                    response = {"type": "acp-sessions-listed", "sessions": []}
                elif kind == "get-favorites":
                    response = {"type": "favorites-list", "favorites": []}
                elif kind == "get-tags":
                    response = {"type": "tags-list", "tags": []}
                elif kind == "get-tag-assignments":
                    response = {"type": "tag-assignments-list", "assignments": []}
                elif kind == "list-cron-jobs":
                    response = {"type": "cron-jobs-list", "jobs": [{"id": name, "name": name + "-cron", "command": "true", "schedule": "0 0 * * *", "enabled": True}]}
                elif kind == "list-dotfiles":
                    response = {"type": "dotfiles-list", "files": [{"path": "~/" + name + "-config", "name": name + "-config", "fileType": "other", "exists": True}]}
                elif kind == "get-stats":
                    cpu = {"server-a": 10, "server-b": 20, "server-c": 30}[name]
                    response = {"type": "stats", "stats": {"hostname": name + "-host", "platform": "linux", "cpu": {"usage": cpu, "cores": 2}, "memory": {"used": 100, "total": 1000}, "disk": {"used": 100, "total": 1000}}}
                elif kind == "ping":
                    response = {"type": "pong"}
                if response:
                    await socket.send(json.dumps(response))
        except websockets.ConnectionClosed:
            pass

    async def run(self, apk):
        assert self.serial.startswith("emulator-")
        assert (await self.adb("emu", "avd", "name")).decode().splitlines()[0] == "agentshell-ui-poc"
        with zipfile.ZipFile(apk) as archive:
            assert any(b"isolated-ui-poc-only" in archive.read(n) for n in archive.namelist() if n.endswith(".dex"))
        self.output.mkdir(parents=True, exist_ok=True)
        servers = []
        try:
            for port, name in HOSTS.items():
                servers.append(await websockets.serve(lambda socket, name=name: self.handler(socket, name), "127.0.0.1", port))
                await self.adb("reverse", "tcp:" + str(port), "tcp:" + str(port))
            await self.adb("install", "-r", str(apk))
            await self.adb("shell", "pm", "clear", "com.agentshell.debug")
            await self.adb("shell", "wm", "size", "reset")
            await self.adb("shell", "am", "start", "-n", ACTIVITY)
            await self.wait_text("server-a-terminal")
            await self.click("Switch server")
            await self.wait_text("Manage servers")
            await self.screenshot("server-picker")
            await self.click("Connect to HOMELAB fixture")
            nodes = await self.wait_text("server-b-terminal")
            assert not any(n.get("text") == "server-a-terminal" for n in nodes)
            self.results["two_taps_from_home_without_settings"] = True
            await self.screenshot("homelab-selected")
            await self.switch("Isolated UI POC")
            await self.wait_text("server-a-terminal")
            self.results["return_to_previous_server"] = True
            print("Header switches and return passed", flush=True)
            await self.click("Cron")
            await self.wait_text("server-a-cron")
            await self.switch("LOCAL_LAN fixture")
            nodes = await self.wait_text("server-c-cron")
            assert not any(n.get("text") == "server-a-cron" for n in nodes)
            self.results["switch_from_cron_refreshes_host_data"] = True
            print("Cron switch passed", flush=True)
            await self.click("Dotfiles")
            await self.wait_text("server-c-config")
            await self.switch("HOMELAB fixture")
            nodes = await self.wait_text("server-b-config")
            assert not any(n.get("text") == "server-c-config" for n in nodes)
            self.results["switch_from_dotfiles_refreshes_host_data"] = True
            print("Dotfiles switch passed", flush=True)
            await self.click("System")
            await self.wait_text("CPU 20.0%")
            await self.switch("Isolated UI POC")
            nodes = await self.wait_text("CPU 10.0%")
            assert not any(n.get("text") == "CPU 20.0%" for n in nodes)
            self.results["switch_from_system_clears_old_statistics"] = True
            print("System switch passed", flush=True)
            await self.click("Sessions")
            await self.wait_text("server-a-terminal")
            # An Activity resume checks the socket and reconnects automatically. Stop A
            # first so the pending command is actually offline while the user switches.
            servers[0].close()
            await servers[0].wait_closed()
            await self.adb("shell", "am", "start", "-n", ACTIVITY, "--ez", "queueOldInput", "true")
            await asyncio.sleep(.5)
            assert not any(kind == "input-via-tmux" for _, kind in self.received)
            await self.switch("LOCAL_LAN fixture")
            await self.wait_text("server-c-terminal")
            await asyncio.sleep(.5)
            assert not any(kind == "input-via-tmux" for _, kind in self.received), "Old queued terminal input crossed server boundary"
            self.results["offline_terminal_queue_does_not_cross_hosts"] = True
            print("Offline queue isolation passed", flush=True)
            await self.adb("shell", "am", "force-stop", "com.agentshell.debug")
            await self.adb("shell", "am", "start", "-n", ACTIVITY)
            await self.wait_text("server-c-terminal")
            self.results["selected_server_survives_process_restart"] = True
            await self.click("Switch to dark mode")
            await self.click("Switch server")
            await self.wait_text("Connect to LOCAL_LAN fixture")
            await self.screenshot("server-picker-dark")
            await self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            await self.adb("shell", "wm", "size", "840x1680")  # 320 dp at the owned AVD's 420 dpi.
            await asyncio.sleep(.5)
            await self.click("Switch server")
            await self.wait_text("Connect to LOCAL_LAN fixture")
            await self.screenshot("server-picker-narrow")
            self.results["light_dark_and_narrow_phone"] = True
            (self.output / "results.json").write_text(json.dumps(self.results, indent=2) + "\n")
            print(json.dumps({"results": self.results, "artifacts": str(self.output)}, indent=2))
        finally:
            for server in servers:
                server.close()
            await asyncio.gather(*(s.wait_closed() for s in servers))
            await self.adb("shell", "wm", "size", "reset")
            await self.adb("shell", "am", "force-stop", "com.agentshell.debug")
            for port in HOSTS:
                await self.adb("reverse", "--remove", "tcp:" + str(port))


async def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", default="emulator-5560")
    p.add_argument("--apk", type=Path, default=PROJECT / "android-native/app/build/outputs/apk/debug/app-debug.apk")
    p.add_argument("--output", type=Path, default=Path(tempfile.mkdtemp(prefix="as-server-switch-")))
    args = p.parse_args()
    await Fixture(args.serial, args.output).run(args.apk)


if __name__ == "__main__":
    asyncio.run(main())
