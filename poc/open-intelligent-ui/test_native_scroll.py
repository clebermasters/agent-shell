#!/usr/bin/env python3
"""Exercise Smart UI overflow with real Android touches on the owned test AVD.

Build an isolated debug APK with -PuiPoc=true first. Requires adb and websockets.
Uses only a loopback synthetic server and com.agentshell.debug; never a phone.
"""

import argparse
import asyncio
import json
from pathlib import Path
import re
import subprocess
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile

import websockets

from test_native import Page

PROJECT = Path(__file__).resolve().parents[2]
PACKAGE = "com.agentshell.debug"


def document(rows):
    content = "".join(f'<p style="height:40px">Synthetic row {n}</p>' for n in range(rows))
    return f'''<!doctype html><html><body><h3>SCROLL TOP</h3>{content}
<label for="range">BOTTOM RANGE</label>
<input id="range" type="range" min="0" max="100" value="10" aria-label="BOTTOM RANGE">
<label for="field">BOTTOM INPUT</label>
<input id="field" aria-label="BOTTOM INPUT" placeholder="BOTTOM INPUT"
 style="display:block;width:100%;height:48px;margin:8px 0">
<button id="footer" onclick="this.textContent='BOTTOM CLICKED'">BOTTOM CONTROL</button>
</body></html>'''


class Fixture:
    rows = 60

    async def handle(self, socket):
        try:
            async for raw in socket:
                if json.loads(raw).get("type") != "watch-chat-log":
                    continue
                common = {"sessionName": "ui-A", "windowIndex": 0, "bindingId": "scroll-fixture"}
                await socket.send(json.dumps({"type": "chat-binding", **common, "state": {
                    "status": "bound", "binding": {"bindingId": "scroll-fixture",
                    "conversationId": "synthetic", "conversationKey": "synthetic", "source": "fixture"}}}))
                await socket.send(json.dumps({"type": "chat-history", **common, "messages": [{
                    "id": "scroll-fixture", "type": "assistant", "timestamp": 0, "blocks": [{
                        "type": "ui_widget", "id": "scroll-fixture", "title": "Synthetic scroll card",
                        "html": document(self.rows)}]}]}))
        except websockets.ConnectionClosed:
            pass


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node["bounds"])))


class Android:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.forward_port = None

    async def adb(self, *args):
        result = await asyncio.to_thread(subprocess.run, ["adb", "-s", self.serial, *args],
                                         capture_output=True, check=True, timeout=30)
        return result.stdout

    async def nodes(self):
        for attempt in range(3):
            try:
                await self.adb("shell", "rm", "-f", "/data/local/tmp/smartui-scroll.xml")
                await self.adb("shell", "uiautomator", "dump", "/data/local/tmp/smartui-scroll.xml")
                raw = await self.adb("shell", "cat", "/data/local/tmp/smartui-scroll.xml")
                break
            except subprocess.CalledProcessError as error:
                # The standalone UIAutomator process occasionally dies while
                # reconnecting accessibility after an APK reinstall on the AVD.
                if error.returncode != 137 or attempt == 2:
                    raise
                await asyncio.sleep(.5)
        (self.output / "latest.xml").write_bytes(raw)
        return [node.attrib for node in ET.fromstring(raw).iter("node")]

    async def node(self, label):
        for node in await self.nodes():
            if label in (node.get("text"), node.get("content-desc")):
                left, top, right, bottom = bounds(node)
                # WebView accessibility bounds can extend under the Android
                # navigation bar even when Compose clips the actual pixels.
                if right > left and bottom > top and bottom < self.height * .9:
                    return node
        return None

    async def click(self, label):
        node = await self.node(label)
        assert node is not None, f"Control is clipped: {label}"
        left, top, right, bottom = bounds(node)
        await self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        await asyncio.sleep(.4)
        return node

    async def swipe(self, upward=True):
        # The gallery keeps its controls above the scroll viewport. Begin the
        # reverse drag below that fixed header, including on the inline card.
        start, end = (.75, .25) if upward else (.4, .8)
        await self.adb("shell", "input", "swipe", str(round(self.width * .6)),
                       str(round(self.height * start)), str(round(self.width * .6)),
                       str(round(self.height * end)), "500")
        await asyncio.sleep(.25)

    async def screenshot(self, name):
        (self.output / f"{name}.png").write_bytes(await self.adb("exec-out", "screencap", "-p"))

    async def target(self):
        for _ in range(80):
            try:
                pid = (await self.adb("shell", "pidof", PACKAGE)).decode().strip()
                if self.forward_port is not None:
                    await self.adb("forward", "--remove", f"tcp:{self.forward_port}")
                self.forward_port = int((await self.adb("forward", "tcp:0",
                    "localabstract:webview_devtools_remote_" + pid)).decode().strip())
                def read():
                    with urlopen(f"http://127.0.0.1:{self.forward_port}/json", timeout=2) as response:
                        return json.load(response)
                targets = await asyncio.to_thread(read)
                return next(t for t in targets if t["url"].endswith("/ui/host.html"))
            except (OSError, ValueError, StopIteration, subprocess.CalledProcessError):
                await asyncio.sleep(.2)
        raise AssertionError("Smart UI WebView did not load")


async def main_value(page, expression):
    result = await page.call("Runtime.evaluate", {"expression": expression, "returnByValue": True})
    assert "exceptionDetails" not in result, result
    return result["result"].get("value")


async def child_context(page):
    for _ in range(60):
        try:
            tree = await page.call("Page.getFrameTree")
            children = tree["frameTree"].get("childFrames", [])
            if children:
                world = await page.call("Page.createIsolatedWorld", {
                    "frameId": children[0]["frame"]["id"], "worldName": "scroll-regression"})
                context = world["executionContextId"]
                if await page.evaluate(context, "!!document.getElementById('footer')"):
                    return context
        except RuntimeError as error:
            # srcdoc replaces the initial blank frame while the host mounts.
            if not any(text in str(error) for text in ["Cannot find context", "No frame for given id"]):
                raise
        await asyncio.sleep(.1)
    raise AssertionError("Synthetic document did not mount")


async def case(android, fixture, port, name, expand=None, rows=60):
    fixture.rows = rows
    await android.adb("shell", "am", "force-stop", PACKAGE)
    await android.adb("shell", "am", "start", "-n", PACKAGE + "/com.agentshell.UiPocActivity",
                      "--es", "endpoint", f"ws://127.0.0.1:{port}", "--ez", "message", "true")
    await asyncio.sleep(1)
    await android.target()
    if expand:
        await android.click(expand)
    target = await android.target()
    async with websockets.connect(target["webSocketDebuggerUrl"]) as socket:
        page = Page(socket)
        context = await child_context(page)
        # The default reveal transform briefly adds a few pixels of overflow.
        # Establish the fixture's settled geometry before checking scroll ranges.
        await page.call("Runtime.evaluate", {"contextId": context, "awaitPromise": True,
            "expression": """(async()=>{await document.fonts.ready;
                await Promise.all(document.getAnimations().filter(a=>a.effect.getTiming().iterations<Infinity)
                    .map(a=>a.finished.catch(()=>{})));
                await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))})()"""})
        initial = await main_value(page, "({nativeHeight:innerHeight,iframeHeight:document.querySelector('iframe').getBoundingClientRect().height})")
        assert initial["nativeHeight"] <= 1601 and initial["iframeHeight"] <= 1600, initial
        async def scroll():
            return await page.evaluate(context, "({top:scrollY,max:document.scrollingElement.scrollHeight-innerHeight})")
        before = await scroll()
        assert (before["max"] > 800) if rows >= 60 else (before["max"] <= 1), before
        initial_views = [n["bounds"] for n in await android.nodes() if n.get("class") == "android.webkit.WebView"]
        initial_top = bounds({"bounds": initial_views[0]})[1]
        # No DevTools scrolling, gesture injection, bridge messages, or DOM changes:
        # native swipes must reach the actual button through both scroll containers.
        limit = 45 if rows > 100 else 18
        for _ in range(limit):
            await android.swipe()
            position = await scroll()
            if position["top"] >= position["max"] - 2 and await android.node("BOTTOM CONTROL"):
                break
        await android.screenshot(name + "-bottom")
        await android.click("BOTTOM CONTROL")
        assert await page.evaluate(context, "document.getElementById('footer').textContent") == "BOTTOM CLICKED"
        print(name + ": footer native tap passed", flush=True)
        bottom = await scroll()
        assert bottom["top"] >= bottom["max"] - 2, bottom
        final_views = [n["bounds"] for n in await android.nodes() if n.get("class") == "android.webkit.WebView"]
        assert final_views != initial_views, "Bottom edge did not hand scrolling to the outer container"

        slider = next(n for n in await android.nodes() if n.get("class") == "android.widget.SeekBar")
        left, top, right, bottom_y = bounds(slider)
        await android.adb("shell", "input", "swipe", str(round(left + (right - left) * .2)),
                          str((top + bottom_y) // 2), str(round(left + (right - left) * .8)),
                          str((top + bottom_y) // 2), "500")
        assert int(await page.evaluate(context, "document.getElementById('range').value")) > 50
        assert abs((await scroll())["top"] - bottom["top"]) <= 2, "Horizontal control moved the document"

        await android.click("BOTTOM INPUT")
        await asyncio.sleep(1)
        await android.adb("shell", "input", "text", "native-scroll")
        assert await page.evaluate(context, "document.getElementById('field').value") == "native-scroll"
        info = (await android.adb("shell", "dumpsys", "input_method")).decode()
        assert re.search(r"(?:mInputShown|inputShown|mIsInputViewShown)[\s:=]+true", info), "IME did not open"
        nodes = await android.nodes()
        focused = next(n for n in nodes if n.get("class") == "android.widget.EditText" and n.get("focused") == "true")
        windows = (await android.adb("shell", "dumpsys", "activity", "top")).decode()
        ime_tops = [int(m.group(1)) for m in re.finditer(
            r"type=ime frame=\[\d+,(\d+)\]\[\d+,\d+\][^\n]*visible=true", windows)]
        assert ime_tops, "Missing visible Android IME bounds"
        assert bounds(focused)[3] <= min(ime_tops) + 4, (focused, ime_tops)
        containers = [n for n in nodes if n.get("class") == "android.widget.ScrollView"]
        assert containers and all(bounds(n)[3] <= min(ime_tops) + 4 for n in containers), (
            "Compose scroll viewport overlaps the IME", [n["bounds"] for n in containers], ime_tops)
        print(name + ": bottom input visible above IME", flush=True)
        await android.screenshot(name + "-keyboard")
        await android.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        await asyncio.sleep(.5)

        for _ in range(limit):
            await android.swipe(upward=False)
            if (await scroll())["top"] > 1:
                continue
            heading = await android.node("SCROLL TOP")
            if heading and bounds(heading)[1] >= initial_top:
                break
        assert (await scroll())["top"] <= 1, "Widget cannot scroll back to its start"
        heading = await android.node("SCROLL TOP")
        assert heading and bounds(heading)[1] >= initial_top, "Top edge did not hand scrolling back to the outer container"
        await android.screenshot(name + "-top")
        return {"viewport": initial, "documentScrollRange": before["max"],
                "footerNativeTap": True, "bottomHandoff": True, "topHandoff": True,
                "horizontalRangeTouch": True, "bottomInputTypedAboveIme": True}


async def run(args):
    android = Android(args.serial, args.output)
    assert args.serial.startswith("emulator-"), "Use the owned test emulator; never a phone"
    assert (await android.adb("emu", "avd", "name")).decode().splitlines()[0] == "agentshell-ui-poc"
    with zipfile.ZipFile(args.apk) as archive:
        assert any(b"isolated-ui-poc-only" in archive.read(n) for n in archive.namelist() if n.endswith(".dex")), "Build APK with -PuiPoc=true"
    args.output.mkdir(parents=True, exist_ok=True)
    sizes = re.findall(r"(\d+)x(\d+)", (await android.adb("shell", "wm", "size")).decode())
    android.width, android.height = map(int, sizes[-1])
    assert android.height > android.width, "Use the test emulator in portrait"
    await android.adb("install", "-r", str(args.apk.resolve()))
    fixture = Fixture()
    results = {}
    async with websockets.serve(fixture.handle, "127.0.0.1", 0, close_timeout=1) as server:
        port = server.sockets[0].getsockname()[1]
        await android.adb("reverse", f"tcp:{port}", f"tcp:{port}")
        try:
            for name, expand, rows in [("inline", None, 60), ("message-expanded", "Maximize message", 60),
                                       ("widget-expanded", "Expand interactive view", 60),
                                       ("very-long-inline", None, 275), ("short-inline", None, 8)]:
                if args.case and name not in args.case:
                    continue
                results[name] = await case(android, fixture, port, name, expand, rows)
                (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
                print(name + ": passed", flush=True)
        finally:
            await android.adb("reverse", "--remove", f"tcp:{port}")
            if android.forward_port is not None:
                await android.adb("forward", "--remove", f"tcp:{android.forward_port}")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--apk", type=Path, default=PROJECT / "android-native/app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", action="append", choices=["inline", "message-expanded", "widget-expanded", "very-long-inline", "short-inline"])
    asyncio.run(run(parser.parse_args()))
