#!/usr/bin/env python3
"""Exercise compact chat and persistent terminal splits on the owned test emulator.

Requires a debug APK built with -PuiPoc=true. Only the verified synthetic debug
app on agentshell-ui-poc is reset. No phone, real agent or backend is accessed.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

import websockets
from test_android_chat_autoscroll import Android

TOKEN = "isolated-ui-poc-only"
SESSION = "Split fixture"
OTHER_SESSION = "Another fixture"
PORT = 40199


class Fixture:
    def __init__(self):
        self.events = []
        self.clients = set()

    async def handle(self, ws):
        assert TOKEN in ws.request.path
        self.clients.add(ws)
        try:
            async for raw in ws:
                request = json.loads(raw)
                self.events.append(request)
                kind = request.get("type")
                session = request.get("sessionName", SESSION)
                window = request.get("windowIndex", 0)
                async def send(kind, **values):
                    await ws.send(json.dumps(dict(type=kind, sessionName=session, windowIndex=window, **values)))
                if kind == "list-sessions":
                    await send("sessions-list", sessions=[dict(name=name, attached=False, windows=2,
                        tool="codex", created="2026-11-13T10:00:00Z") for name in [SESSION, OTHER_SESSION]])
                elif kind == "list-windows":
                    await send("windows-list", windows=[dict(index=i, id=i, name=f"window-{i}", active=i == 0, panes=1) for i in [0, 1]])
                elif kind == "watch-chat-log":
                    key = f"fixture:{session}:{window}"
                    await send("chat-binding", state=dict(status="bound", paneToken="fixture-pane", tool="codex",
                        binding=dict(bindingId=key, conversationId=key, conversationKey=key, source="codex"), candidates=[], detail=""))
                    await send("chat-history", bindingId=key, conversationKey=key, tool="codex", hasMore=False, totalCount=5,
                        messages=[dict(id=f"{window}-reply-{i}", role="assistant", timestamp="2026-11-13T10:00:00Z",
                            blocks=[dict(type="text", text=f"FIXTURE_{i}: A readable chat message in {session}, window {window}.")]) for i in range(5)])
                    await send("chat-activity", state=dict(status="idle", detail="Ready for your next message", source="codex",
                        confidence="confirmed", conversationKey=key, observedAt=int(time.time()*1000), observerId="split-fixture", sequence=len(self.events)))
                elif kind == "attach-session":
                    assert session in [SESSION, OTHER_SESSION] and window in [0, 1]
                    await send("attached")
                    await send("output", data=f"\u001b[32mAgentShell terminal fixture\u001b[0m\r\nSession: {session} / window {window}\r\n$ ")
                elif kind == "input":
                    await send("output", data=request.get("data", ""))
                elif kind == "ping":
                    await send("pong")
                elif kind == "get-stats":
                    await send("stats", cpuUsage=0, memoryUsage=0, memoryTotal=1000)
                elif kind == "list-favorites":
                    await send("favorites-list", favorites=[])
                elif kind == "list-tags":
                    await send("tags-list", tags=[])
                elif kind == "acp-list-sessions":
                    await send("acp-sessions-listed", sessions=[])
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)


class SplitAndroid(Android):
    async def root(self):
        remote = "/data/local/tmp/agentshell-chat-split.xml"
        for attempt in range(4):
            try:
                await self.adb("shell", "uiautomator", "dump", remote)
                return ET.fromstring(await self.adb("shell", "cat", remote))
            except subprocess.CalledProcessError:
                if attempt == 3: raise
                await asyncio.sleep(.5)

    @staticmethod
    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    async def click(self, label):
        root = await self.root()
        nodes = [n for n in root.iter("node") if n.get("content-desc") == label]
        if not nodes:
            nodes = [n for n in root.iter("node") if n.get("clickable") == "true" and
                any(c.get("text") == label for c in n.iter("node"))]
        assert nodes, f"Missing control {label!r}; labels: {[n.get('text') or n.get('content-desc') for n in root.iter('node')]}"
        def area(n):
            x1,y1,x2,y2 = self.bounds(n); return (x2-x1)*(y2-y1)
        x1,y1,x2,y2 = self.bounds(min(nodes,key=area))
        await self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        await asyncio.sleep(.6)

    async def find(self, label, timeout=15):
        deadline = time.monotonic()+timeout
        while True:
            nodes = [n for n in (await self.root()).iter("node") if n.get("content-desc") == label or n.get("text") == label]
            if nodes: return nodes[0]
            assert time.monotonic() < deadline, f"Missing {label!r}"
            await asyncio.sleep(.3)

    async def screenshot(self, name):
        (self.output/f"{name}.png").write_bytes(await self.adb("exec-out", "screencap", "-p"))
        ET.ElementTree(await self.root()).write(self.output/f"{name}.xml")
        print(f"Screenshot: {self.output/name}.png", flush=True)

    async def open_chat(self, session=SESSION, window=0):
        await self.find(session)
        root = await self.root()
        matches = [n for n in root.iter("node") if any(c.get("text") == session for c in n.iter("node")) and
            any(c.get("content-desc") == "Chat" or c.get("text") == "Chat" for c in n.iter("node"))]
        assert matches, f"Missing fixture session {session}"
        row = min(matches, key=lambda n: len(list(n.iter("node"))))
        button = next(n for n in row.iter("node") if n.get("content-desc") == "Chat" or n.get("text") == "Chat")
        x1,y1,x2,y2 = self.bounds(button)
        await self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        await asyncio.sleep(.6)
        if any(n.get("text") == "Select window" for n in (await self.root()).iter("node")):
            await self.click(f"Window {window}")
        elif any(n.get("text") == f"Window {window}" for n in (await self.root()).iter("node")):
            await self.click(f"Window {window}")
        deadline = time.monotonic()+15
        while True:
            root = await self.root()
            if any(n.get("content-desc") in ["Split chat and terminal", "Close terminal split"] for n in root.iter("node")): break
            assert time.monotonic() < deadline, "Did not enter the fixture chat"
            await asyncio.sleep(.3)

    async def field(self):
        fields = [n for n in (await self.root()).iter("node") if n.get("class") == "android.widget.EditText"]
        assert len(fields) == 1, f"Expected one composer, got {len(fields)}"
        return fields[0]

    async def set_draft(self, text):
        node = await self.field(); x1,y1,x2,y2 = self.bounds(node)
        await self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        await self.adb("shell", "input", "text", text)
        await self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        await asyncio.sleep(.5)

    async def divider(self):
        return self.bounds(await self.find("Resize chat and terminal"))

    async def options(self):
        await self.click("Chat options")
        await self.click("Split layout…")

    async def orientation(self, label):
        await self.options(); await self.click(label); await self.click("Done")


def verify_apk(apk):
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home()/"android-sdk")))
    analyzer = str(sdk/"cmdline-tools/latest/bin/apkanalyzer")
    package = subprocess.run([analyzer, "manifest", "application-id", str(apk)],
        capture_output=True, text=True, check=True)
    assert package.stdout.strip() == "com.agentshell.debug"
    result = subprocess.run([analyzer, "dex", "code", "--class",
        "com.agentshell.core.config.BuildConfig", str(apk)], capture_output=True, text=True, check=True)
    assert re.search(r'\.field[^\n]* AUTH_TOKEN:[^\n]*= "isolated-ui-poc-only"', result.stdout)
    assert re.search(r'\.field[^\n]* DEFAULT_SERVER_LIST:[^\n]*= "127\.0\.0\.1:40199,Isolated UI POC"', result.stdout)


async def run(args):
    verify_apk(args.apk)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    ui = SplitAndroid(args.serial, output)
    assert args.serial.startswith("emulator-")
    assert (await ui.adb("emu", "avd", "name")).decode().splitlines()[0] == "agentshell-ui-poc"
    fixture = Fixture()
    async with websockets.serve(fixture.handle, "127.0.0.1", PORT, close_timeout=1):
        await ui.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        await ui.adb("install", "-r", str(args.apk.resolve()))
        await ui.adb("shell", "pm", "clear", "com.agentshell.debug")
        await ui.adb("shell", "pm", "grant", "com.agentshell.debug", "android.permission.POST_NOTIFICATIONS")
        await ui.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        await ui.adb("shell", "settings", "put", "system", "user_rotation", "0")
        await ui.adb("shell", "am", "start", "-n", "com.agentshell.debug/com.agentshell.MainActivity")
        await asyncio.sleep(4)
        await ui.open_chat()
        await ui.screenshot("01-compact-chat")
        root = await ui.root()
        button = min([n for n in root.iter("node") if n.get("clickable") == "true" and
            any(c.get("content-desc") == "Split chat and terminal" for c in n.iter("node"))],
            key=lambda n: len(list(n.iter("node"))))
        x1,y1,x2,y2 = ui.bounds(button)
        density_raw = (await ui.adb("shell", "wm", "density")).decode()
        density = int(re.findall(r"\d+", density_raw)[-1])/160
        await ui.check("compact_header_touch_target", abs((y2-y1)/density-48) < 2)
        await ui.set_draft("PersistentFixtureDraft")
        await ui.click("Split chat and terminal")
        before = await ui.divider()
        await asyncio.sleep(2)
        await ui.check("same_session_window_terminal", any(e.get("type") == "attach-session" and e.get("sessionName") == SESSION and e.get("windowIndex") == 0 for e in fixture.events))
        attaches = sum(e.get("type") == "attach-session" for e in fixture.events)
        await ui.screenshot("02-stacked-split")
        x1,y1,x2,y2 = before; center=(x1+x2)//2; start=(y1+y2)//2
        await ui.adb("shell", "input", "swipe", str(center), str(start), str(center), str(start-240), "800")
        await asyncio.sleep(1)
        dragged = await ui.divider()
        await ui.check("divider_drags", dragged[1] < before[1]-150)
        await ui.check("draft_survives_split_and_resize", (await ui.field()).get("text") == "PersistentFixtureDraft")
        await ui.options(); await ui.click("Swap chat and terminal"); await ui.click("Done")
        swapped = await ui.divider()
        await ui.check("swap_moves_chat", swapped[1] != dragged[1])
        await ui.check("draft_survives_swap", (await ui.field()).get("text") == "PersistentFixtureDraft")
        await ui.screenshot("03-swapped-split")
        await ui.orientation("Side by side")
        side = await ui.divider()
        await ui.check("forced_side_by_side", side[3]-side[1] > side[2]-side[0])
        await ui.check("swap_and_orientation_retain_terminal", sum(e.get("type") == "attach-session" for e in fixture.events) == attaches)
        editor = ui.bounds(await ui.field())
        await ui.check("narrow_chat_editor_keeps_usable_width", editor[2]-editor[0] >= 350)
        await ui.screenshot("04-side-by-side")
        await ui.orientation("Top / bottom")
        await ui.check("orientation_keeps_saved_ratio", abs((await ui.divider())[1]-swapped[1]) <= 3)
        # Force-stop removes Compose memory, so restoration must come from disk.
        await ui.adb("shell", "am", "force-stop", "com.agentshell.debug")
        await ui.adb("shell", "am", "start", "-n", "com.agentshell.debug/com.agentshell.MainActivity")
        await asyncio.sleep(3)
        root = await ui.root()
        if not any(n.get("content-desc") == "Close terminal split" for n in root.iter("node")):
            await ui.open_chat()
        await ui.find("Close terminal split")
        await ui.check("split_and_resize_restore_after_restart", abs((await ui.divider())[1]-swapped[1]) <= 3)
        await ui.check("draft_restores_after_restart", (await ui.field()).get("text") == "PersistentFixtureDraft")
        await ui.orientation("Auto · follow screen")
        await ui.adb("shell", "settings", "put", "system", "user_rotation", "1")
        await asyncio.sleep(2)
        auto = await ui.divider()
        await ui.check("auto_follows_landscape", auto[3]-auto[1] > auto[2]-auto[0])
        await ui.screenshot("05-auto-landscape")
        await ui.adb("shell", "settings", "put", "system", "user_rotation", "0")
        await asyncio.sleep(2)
        await ui.click("Close terminal split")
        await ui.check("close_returns_full_chat", not any(n.get("content-desc") == "Resize chat and terminal" for n in (await ui.root()).iter("node")))
        await ui.click("Split chat and terminal")
        await ui.check("reopening_preserves_layout", abs((await ui.divider())[1]-swapped[1]) <= 3)
        await ui.click("Back")
        await ui.open_chat(SESSION, 1)
        await ui.check("window_preferences_are_independent", not any(n.get("content-desc") == "Resize chat and terminal" for n in (await ui.root()).iter("node")))
        await ui.click("Split chat and terminal"); await ui.divider(); await asyncio.sleep(1)
        await ui.check("nonzero_window_attaches_correctly", any(e.get("type") == "attach-session" and e.get("sessionName") == SESSION and e.get("windowIndex") == 1 for e in fixture.events))
        await ui.click("Back"); await ui.open_chat(OTHER_SESSION)
        await ui.check("session_preferences_are_independent", not any(n.get("content-desc") == "Resize chat and terminal" for n in (await ui.root()).iter("node")))
        await ui.click("Back"); await ui.open_chat(SESSION)
        await ui.find("Close terminal split")
        await ui.click(f"Terminal for {SESSION}, window 0")
        await ui.adb("shell", "input", "text", "TerminalFixtureInput")
        await asyncio.sleep(.6)
        terminal_input = "".join(e.get("data", "") for e in fixture.events if e.get("type") == "input")
        await ui.check("terminal_accepts_keyboard_input", "TerminalFixtureInput" in terminal_input)
        field = await ui.field(); x1,y1,x2,y2 = ui.bounds(field)
        await ui.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        await ui.adb("shell", "input", "text", "ChatAgain")
        await ui.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        await asyncio.sleep(.6)
        edited = (await ui.field()).get("text", "")
        await ui.check("composer_reclaims_keyboard_focus", edited.count("ChatAgain") == 1 and
            edited.replace("ChatAgain", "") == "PersistentFixtureDraft")
        await ui.screenshot("06-keyboard-focus-returned")
        (output/"websocket-events.json").write_text(json.dumps(fixture.events, indent=2))
        print(json.dumps(ui.results, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--apk", type=Path, default=Path("android-native/app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/agentshell-chat-split-verification"))
    asyncio.run(run(parser.parse_args()))
