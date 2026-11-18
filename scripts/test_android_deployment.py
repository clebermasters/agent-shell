#!/usr/bin/env python3
"""Exercise both Deployment entry points through MainActivity on agentshell-ui-poc.

Use a -PuiPoc=true debug APK whose debug browser_fixture_ca trusts the fixture
certificate in --tls-directory (cert.pem and key.pem for 127.0.0.1). Only the
isolated debug package on the named emulator is reset.
"""
import argparse
import asyncio
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shlex
import ssl
import threading
import urllib.request
import uuid

import websockets
from test_android_chat_split import Fixture, PORT, SESSION, SplitAndroid, TOKEN, verify_apk

BROWSER_PORT = 18998
PACKAGE = "com.agentshell.debug"
ACTIVITY = PACKAGE + "/com.agentshell.MainActivity"
FRAME = "Deployment fixture page"
DIVIDER = "Resize chat and browser"


@contextmanager
def browser_fixture(directory):
    session = str(uuid.uuid4())
    grants = []
    origin = f"https://127.0.0.1:{BROWSER_PORT}"

    class Browser(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, payload, media_type="application/json"):
            body = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", media_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/api/browser/capabilities" and self.headers.get("X-Auth-Token") == TOKEN:
                self.reply(dict(available=True, viewerOrigin=origin, httpPort=PORT,
                                session=dict(state="ready", sessionId=session, runtimeGeneration="fixture")))
            elif self.path == "/favicon.ico":
                self.reply("", "image/x-icon")
            else:
                self.send_error(404)

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            if self.path == f"/api/browser/sessions/{session}/viewer-grants" and self.headers.get("X-Auth-Token") == TOKEN:
                grants.append(json.loads(body)["viewerId"])
                self.reply(dict(grant="fixture"))
            elif self.path == f"/browser/{session}/bootstrap" and body == b"grant=fixture":
                self.reply('<title>' + FRAME + '</title><link rel="icon" href="data:,">'
                           '<meta name="viewport" content="width=device-width"><p>' + FRAME + '</p>'
                           '<button onclick="this.textContent=\'Page selected\'">Select page</button>'
                           '<script>BrowserHost.postMessage(JSON.stringify({type:"connection",state:"connected"}))</script>',
                           "text/html")
            else:
                self.send_error(404)

    with ThreadingHTTPServer(("127.0.0.1", BROWSER_PORT), Browser) as server:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(directory / "cert.pem", directory / "key.pem")
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield grants
        finally:
            server.shutdown()
            thread.join()


async def run(args):
    verify_apk(args.apk)
    ui = SplitAndroid(args.serial, args.output)
    assert args.serial.startswith("emulator-")
    assert (await ui.adb("emu", "avd", "name")).decode().splitlines()[0] == "agentshell-ui-poc"
    args.output.mkdir(parents=True, exist_ok=True)
    chat = Fixture()

    async def start():
        await ui.adb("shell", "am", "start", "-n", ACTIVITY)

    async def key(name):
        await ui.adb("shell", "input", "keyevent", "KEYCODE_" + name)

    async def visible_browser_document():
        # Android's accessibility snapshot can omit the HTML subtree after a
        # WebView moves. Read the debug WebView's actual page in that case.
        pid = (await ui.adb("shell", "pidof", PACKAGE)).decode().strip()
        port = (await ui.adb("forward", "tcp:0", "localabstract:webview_devtools_remote_" + pid)).decode().strip()
        def matches():
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=2) as response:
                return any(t["title"] == FRAME and t["url"].startswith(f"https://127.0.0.1:{BROWSER_PORT}/browser/")
                           for t in json.load(response))
        try:
            return await asyncio.to_thread(matches)
        except OSError:
            return False
        finally:
            await ui.adb("forward", "--remove", "tcp:" + port)

    async def workspace():
        async with asyncio.timeout(25):
            while True:
                nodes = list((await ui.root()).iter("node"))
                labels = {n.get("text") or n.get("content-desc") for n in nodes}
                browser_view = any(n.get("class") == "android.webkit.WebView" for n in nodes)
                if {"Close split", DIVIDER} <= labels and browser_view and (FRAME in labels or await visible_browser_document()):
                    assert sum(n.get("class") == "android.widget.EditText" for n in nodes) == 1
                    return ui.bounds(next(n for n in nodes if n.get("content-desc") == DIVIDER))
                await asyncio.sleep(0.3)

    async def layout(action):
        await ui.click(DIVIDER)
        await ui.click(action)
        await ui.click("Done")

    async def choose_window(index):
        label = f"Window {index} · window-{index}"
        await ui.find(label)
        rows = [n for n in (await ui.root()).iter("node") if n.get("clickable") == "true"
                and {SESSION, label}.issubset({c.get("text") for c in n.iter("node")})]
        x1, y1, x2, y2 = ui.bounds(min(rows, key=lambda n: len(list(n.iter("node")))))
        await ui.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    async with websockets.serve(chat.handle, "127.0.0.1", PORT, close_timeout=1):
        with browser_fixture(args.tls_directory) as grants:
            try:
                for port in (PORT, BROWSER_PORT):
                    await ui.adb("reverse", f"tcp:{port}", f"tcp:{port}")
                await ui.adb("install", "-r", str(args.apk.resolve()))
                await ui.adb("shell", "pm", "clear", PACKAGE)
                await ui.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
                await start()
                await ui.find(SESSION)
                assert any(e.get("type") == "list-sessions" for e in chat.events)
                # Add an HTTPS alias for the fixture's legacy HTTP chat address.
                # Stopping the fixture app makes Room see it on its next launch.
                await ui.adb("shell", "am", "force-stop", PACKAGE)
                sql = ("INSERT INTO hosts (id,name,address,port,sortOrder,tls) VALUES "
                       f"('deployment-https','Fixture TLS','127.0.0.1',{BROWSER_PORT},2,1)")
                await ui.adb("shell", shlex.join(["run-as", PACKAGE, "sqlite3", "databases/agentshell_db", sql]))
                await start()
                await ui.find(SESSION)
                await ui.click("Remote browser")
                await ui.find(FRAME)
                original_grants = len(grants)
                await ui.click("Deployment")
                await ui.find("Choose a chat")
                await choose_window(1)
                await workspace()
                assert any(e.get("type") == "watch-chat-log" and e.get("sessionName") == SESSION
                           and e.get("windowIndex") == 1 for e in chat.events)
                assert len(grants) == original_grants, "Existing browser viewer reconnected on entry"
                await ui.click("Select page")
                await ui.find("Page selected")
                binding = f"fixture:{SESSION}:1"
                reply = "Live reply in Deployment"
                event = dict(type="chat-event", sessionName=SESSION, windowIndex=1, tool="codex",
                             bindingId=binding, conversationKey=binding, message=dict(id="deployment-live",
                             role="assistant", timestamp="2026-11-13T10:05:00Z", blocks=[dict(type="text", text=reply)]))
                await asyncio.gather(*(ws.send(json.dumps(event)) for ws in list(chat.clients)))
                await ui.find(reply)
                print("Browser entry kept its viewer and opened the selected tmux window", flush=True)
                await layout("Top / bottom")
                before = await workspace()
                assert before[2] - before[0] > before[3] - before[1]
                mid_x, mid_y = (before[0] + before[2]) // 2, (before[1] + before[3]) // 2
                await ui.adb("shell", "input", "swipe", str(mid_x), str(mid_y), str(mid_x), str(mid_y - 150), "600")
                resized = await workspace()
                assert resized[1] < before[1] - 80
                assert ui.bounds(await ui.field())[3] <= resized[1]
                await ui.screenshot("deployment-stacked")
                await layout("Swap chat and browser")
                swapped = await workspace()
                assert ui.bounds(await ui.field())[1] >= swapped[3]
                await layout("Side by side")
                side = await workspace()
                assert side[3] - side[1] > side[2] - side[0]
                assert len(grants) == original_grants, "Changing layout recreated the browser viewer"
                await ui.find("Page selected")
                await ui.screenshot("deployment-side-by-side")
                await key("HOME")
                await start()
                assert await workspace() == side
                await key("BACK")
                await ui.find(SESSION)
                assert any(e.get("type") == "unwatch-chat-log" for e in chat.events)
                await ui.click("Remote browser")
                assert await workspace() == side
                await ui.adb("shell", "am", "force-stop", PACKAGE)
                await start()
                await ui.find("Remote browser")
                await ui.click("Remote browser")
                assert await workspace() == side
                await layout("Top / bottom")
                assert abs((await workspace())[1] - swapped[1]) <= 3
                print("Resize, swap, orientation, minimize, Back and cold reopen passed", flush=True)
                await ui.click("Close split")
                await ui.find("Deployment")
                await key("BACK")
                await ui.open_chat(window=0)
                await ui.set_draft("DeploymentDraft")
                await ui.click("Chat options")
                await ui.click("Deployment")
                await workspace()
                assert (await ui.field()).get("text") == "DeploymentDraft"
                await key("BACK")
                await ui.open_chat(window=0, deployment=True)
                await workspace()
                assert (await ui.field()).get("text") == "DeploymentDraft"
                await ui.click("Close split")
                await ui.find("Chat options")
                assert (await ui.field()).get("text") == "DeploymentDraft"
                await key("BACK")
                await ui.find(SESSION)
                await ui.click("Remote browser")
                await ui.find(FRAME)
                assert not any(n.get("content-desc") == "Close split" for n in (await ui.root()).iter("node"))
                print("Chat entry preserved draft and closing the split disabled restore", flush=True)
            except Exception:
                await ui.screenshot("deployment-failure")
                (args.output / "fixture-events.json").write_text(json.dumps(chat.events, indent=2))
                raise
            finally:
                await ui.adb("shell", "am", "force-stop", PACKAGE)
                for port in (PORT, BROWSER_PORT):
                    await ui.adb("reverse", "--remove", f"tcp:{port}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--apk", type=Path, default=Path("android-native/app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--tls-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    asyncio.run(run(parser.parse_args()))
