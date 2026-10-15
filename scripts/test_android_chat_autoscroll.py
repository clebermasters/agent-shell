#!/usr/bin/env python3
"""Exercise the real Android ChatScreen against synthetic WebSocket messages.

Requires adb, websockets >= 14, an owned API 35 emulator, and a debug APK built
with -PuiPoc=true. Only com.agentshell.debug on the named emulator is reset.
No production backend, agent, conversation database, or phone is accessed.
"""

import argparse
import asyncio
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import websockets

SESSION = "Scroll fixture"
PORT = 40199


class Fixture:
    def __init__(self):
        self.clients = {}
        self.sequence = 30
        self.messages = [self.message(f"history-{i}", [f"HISTORY_{i:03d}: A short fixture reply to read."], i)
                         for i in range(30)]

    def message(self, identifier, texts, sequence=None):
        timestamp = datetime(2026, 10, 10, tzinfo=timezone.utc) + timedelta(
            seconds=self.sequence if sequence is None else sequence)
        return {"id": identifier, "role": "assistant", "timestamp": timestamp.isoformat(),
                "blocks": [{"type": "text", "text": text} for text in texts]}

    async def broadcast(self, kind, **fields):
        payload = json.dumps({"type": kind, "sessionName": SESSION, "windowIndex": 0, **fields})
        for ws, watching in list(self.clients.items()):
            if watching:
                try:
                    await ws.send(payload)
                except websockets.ConnectionClosed:
                    pass

    async def history(self):
        await self.broadcast("chat-history", messages=self.messages, tool="codex",
                             hasMore=False, totalCount=len(self.messages))

    async def add(self, tail, lines=0, grow=False):
        self.sequence += 1
        texts = [f"READ_LINE_{i:03d}: Fixture paragraph for scrolling." for i in range(lines)] + [tail]
        message = self.message(self.messages[-1]["id"] if grow else f"new-{self.sequence}", texts)
        if grow:
            self.messages[-1] = message
        else:
            self.messages.append(message)
        await self.broadcast("chat-event", message=message, source="codex")

    async def prepend(self):
        older = [self.message(f"older-{i}", [f"OLDER_{i:03d}: Loaded earlier history."], i - 20)
                 for i in range(20)]
        self.messages = older + self.messages
        await self.broadcast("chat-history-chunk", messages=older, hasMore=False,
                             totalCount=len(self.messages))

    async def handle(self, ws):
        if "isolated-ui-poc-only" not in ws.request.path:
            await ws.close()
            return
        self.clients[ws] = False
        try:
            async for raw in ws:
                kind = json.loads(raw).get("type")
                if kind == "list-sessions":
                    await ws.send(json.dumps({"type": "sessions-list", "sessions": [
                        {"name": SESSION, "attached": False, "windows": 1, "tool": "codex"}]}))
                elif kind == "watch-chat-log":
                    self.clients[ws] = True
                    await self.history()
                elif kind == "unwatch-chat-log":
                    self.clients[ws] = False
                elif kind == "ping":
                    await ws.send('{"type":"pong"}')
                elif kind == "get-stats":
                    await ws.send('{"type":"stats","cpuUsage":0,"memoryUsage":0,"memoryTotal":1000}')
                elif kind == "list-favorites":
                    await ws.send('{"type":"favorites-list","favorites":[]}')
                elif kind == "list-tags":
                    await ws.send('{"type":"tags-list","tags":[]}')
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.pop(ws, None)


class Android:
    def __init__(self, serial, output):
        self.serial = serial
        self.output = output
        self.results = {}

    async def adb(self, *args):
        result = await asyncio.to_thread(subprocess.run, ["adb", "-s", self.serial, *args],
                                         capture_output=True, check=True)
        return result.stdout

    async def nodes(self):
        await self.adb("shell", "uiautomator", "dump", "/sdcard/chat-scroll-test.xml")
        raw = await self.adb("shell", "cat", "/sdcard/chat-scroll-test.xml")
        (self.output / "latest.xml").write_bytes(raw)
        return [node.attrib for node in ET.fromstring(raw).iter("node")]

    async def tap(self, label):
        for node in await self.nodes():
            if node.get("text") == label or node.get("content-desc") == label:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
                await self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                await asyncio.sleep(.5)
                return
        raise AssertionError(f"No button {label!r}")

    async def visible(self, text):
        return any(node.get("text") == text for node in await self.nodes())

    async def anchor(self, prefix):
        for node in await self.nodes():
            if node.get("text", "").startswith(prefix):
                _, top, _, bottom = map(int, re.findall(r"\d+", node["bounds"]))
                # Choose a fully visible paragraph below the status bar.
                if top >= 340 and bottom < 1450:
                    return node["text"], node["bounds"]
        raise AssertionError(f"No visible reading anchor {prefix!r}")

    async def check(self, name, condition):
        self.results[name] = bool(condition)
        print(f"{name}: {'PASS' if condition else 'FAIL'}", flush=True)
        (self.output / "results.json").write_text(json.dumps(self.results, indent=2) + "\n")
        if not condition:
            (self.output / f"{name}.png").write_bytes(await self.adb("exec-out", "screencap", "-p"))
            raise AssertionError(name)

    async def same_anchor(self, before, prefix):
        after = await self.anchor(prefix)
        if before != after:
            print(f"Reading anchor changed: {before!r} -> {after!r}", flush=True)
        return before == after

    async def swipe(self, up=False):
        # The owned test AVD uses 1080x1920. Avoid the composer and status bar.
        start, end = (1450, 500) if up else (500, 1350)
        await self.adb("shell", "input", "swipe", "500", str(start), "500", str(end), "350")
        await asyncio.sleep(.6)


async def run(args):
    if not args.serial.startswith("emulator-"):
        raise ValueError("Use an owned emulator, not a physical phone")
    with zipfile.ZipFile(args.apk) as apk:
        if not any(b"isolated-ui-poc-only" in apk.read(name)
                   for name in apk.namelist() if name.endswith(".dex")):
            raise ValueError("APK must be built with -PuiPoc=true")
    output = args.output or Path(tempfile.mkdtemp(prefix="agentshell-chat-scroll-"))
    output.mkdir(parents=True, exist_ok=True)
    android = Android(args.serial, output)
    fixture = Fixture()
    async with websockets.serve(fixture.handle, "127.0.0.1", PORT, close_timeout=1):
        await android.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        await android.adb("install", "-r", str(args.apk.resolve()))
        await android.adb("shell", "pm", "clear", "com.agentshell.debug")
        await android.adb("shell", "pm", "grant", "com.agentshell.debug", "android.permission.POST_NOTIFICATIONS")
        await android.adb("shell", "am", "start", "-n", "com.agentshell.debug/com.agentshell.MainActivity")
        await asyncio.sleep(3)
        await android.tap("Chat")
        await android.check("initial_latest", await android.visible("HISTORY_029: A short fixture reply to read."))

        await fixture.add("TAIL_APPEND", lines=45)
        await android.check("long_reply_follows", await android.visible("TAIL_APPEND"))
        await fixture.add("TAIL_STREAM", lines=70, grow=True)
        await android.check("same_id_growth_follows", await android.visible("TAIL_STREAM"))

        await android.swipe()
        anchor = await android.anchor("READ_LINE_")
        await fixture.add("TAIL_PAUSED_GROWTH", lines=90, grow=True)
        await android.check("growth_preserves_reading_pixel", await android.same_anchor(anchor, "READ_LINE_"))
        await fixture.add("SHORT_WHILE_READING")
        await android.check("append_preserves_reading_pixel", await android.same_anchor(anchor, "READ_LINE_"))
        await fixture.history()
        await android.check("history_refresh_preserves_reading_pixel", await android.same_anchor(anchor, "READ_LINE_"))

        await android.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        await fixture.add("SHORT_WHILE_BACKGROUND")
        await android.adb("shell", "am", "start", "-n", "com.agentshell.debug/com.agentshell.MainActivity")
        await android.check("resume_preserves_reading_pixel", await android.same_anchor(anchor, "READ_LINE_"))

        await android.tap("Scroll to bottom")
        await fixture.add("SHORT_AFTER_BUTTON")
        await android.check("button_resumes_following", await android.visible("SHORT_AFTER_BUTTON"))
        await android.swipe()
        for _ in range(3):
            await android.swipe(up=True)
        await fixture.add("SHORT_AFTER_MANUAL_BOTTOM")
        await android.check("manual_bottom_resumes_following", await android.visible("SHORT_AFTER_MANUAL_BOTTOM"))

        # Returning partway up the *last* tall row must also pause following.
        await fixture.add("TAIL_PARTIAL", lines=50)
        await android.swipe()
        anchor = await android.anchor("READ_LINE_")
        await fixture.add("SHORT_PARTIAL_LAST_ROW")
        await android.check("partial_last_row_stays_paused", await android.same_anchor(anchor, "READ_LINE_"))

        await android.tap("Scroll to bottom")
        for index in range(8):
            await fixture.add(f"RAPID_{index}")
            await asyncio.sleep(.03)
        await android.check("rapid_replies_follow", await android.visible("RAPID_7"))

        gesture = asyncio.create_task(android.adb("shell", "input", "swipe", "500", "500", "500", "1350", "1200"))
        await asyncio.sleep(.5)
        await fixture.add("ARRIVED_DURING_DRAG")
        await gesture
        await android.check("arrival_during_drag_stays_paused", not await android.visible("ARRIVED_DURING_DRAG"))

        # Find a normal older row, then prepend history before that stable key.
        for _ in range(12):
            await android.swipe()
            try:
                anchor = await android.anchor("HISTORY_")
                break
            except AssertionError:
                pass
        else:
            raise AssertionError("Could not reach older messages")
        await fixture.add("SHORT_WHILE_OLD_HISTORY")
        await android.check("old_history_append_preserves_pixel", await android.same_anchor(anchor, "HISTORY_"))
        await fixture.prepend()
        await android.check("prepend_preserves_reading_pixel", await android.same_anchor(anchor, "HISTORY_"))

        await android.tap("Scroll to bottom")
        await android.visible("SHORT_WHILE_OLD_HISTORY")  # Settle foreground read tracking.
        await android.tap("Back")
        # Home briefly shows a "Connected" banner when returning; let it leave
        # before using accessibility bounds to tap the session card.
        await asyncio.sleep(3)
        await fixture.add("TAIL_UNREAD", lines=45)
        for index in range(25):
            await fixture.add(f"UNREAD_AFTER_{index}")
        await android.tap("Chat")
        await android.check("reopen_at_first_unread", await android.visible("READ_LINE_000: Fixture paragraph for scrolling."))
        await android.check("unread_open_stays_at_boundary", not await android.visible("UNREAD_AFTER_24"))
        anchor = await android.anchor("READ_LINE_")
        await fixture.add("NEW_WHILE_UNREAD_READING")
        await android.check("unread_arrival_preserves_pixel", await android.same_anchor(anchor, "READ_LINE_"))
    print(f"Evidence: {output}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Owned emulator serial")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    asyncio.run(run(parser.parse_args()))
