#!/usr/bin/env python3
"""Checks for the native window bridge; optional end-to-end check uses a disposable Chrome profile."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.request
import uuid
from unittest.mock import patch

import browser_windows as windows

runtime = windows.runtime_module()
source = Path(__file__).resolve().parent.parent / "deployment/browser/window-extension"


class BridgeTests(unittest.TestCase):
    def test_background_list_cannot_open_a_pairing_page(self):
        with tempfile.TemporaryDirectory() as root:
            scoped = ({"runtimeDir":root}, {}, "Default", {"generation":"one","profile":"chosen"})
            with patch.object(windows, "identity", return_value=scoped), patch.object(windows, "pair") as pair:
                with self.assertRaisesRegex(windows.WindowError, "WINDOW_PAIRING_REQUIRED"):
                    windows.query(runtime, Path(root)/"config", "one", {"id":"read","action":"list"})
                with self.assertRaisesRegex(windows.WindowError, "WINDOW_CATALOG_STALE"):
                    windows.query(runtime, Path(root)/"config", "one", {"id":"write","action":"merge"})
                pair.assert_not_called()

    def test_frames_enforce_length_and_dictionary(self):
        self.assertEqual(windows.read_packet(io.BytesIO(windows.packet({"x":"é"}))), {"x":"é"})
        for raw in (b"", b"\x01", b"\xff\xff\xff\xff", windows.packet([0])):
            with self.assertRaises((EOFError, windows.WindowError)):
                windows.read_packet(io.BytesIO(raw))

    def test_prior_runtime_requires_an_unambiguous_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("Default", "Profile 1"):
                (root / name).mkdir()
            value = {"userDataDir": directory}
            runtime.write_json(root / "Local State", {"profile":{"info_cache":{"Default":{}, "Profile 1":{}}}})
            with self.assertRaisesRegex(windows.WindowError, "WINDOW_PROFILE_REQUIRED"):
                windows.selected_profile(runtime, value, {})
            self.assertEqual(windows.selected_profile(runtime, value, {"windowProfileDirectory":"Default"}), "Default")

    def test_stale_or_different_singleton_peer_receives_no_tab(self):
        with tempfile.TemporaryDirectory() as directory:
            value = {"userDataDir":directory, "windowExtension":{"id":"a" * 32}}
            path = Path(directory) / "SingletonSocket"
            with socket.socket(socket.AF_UNIX) as server:
                server.bind(str(path))
                server.listen()
                for owner in ({"chromePid":1,"chromeGeneration":"x"},
                              {"chromePid":os.getpid(),"chromeGeneration":"old"}):
                    with self.assertRaisesRegex(windows.WindowError, windows.STALE):
                        windows.start_pairing(runtime, value, owner, "Default", "0" * 64)
                    client, _ = server.accept()
                    with client:
                        self.assertEqual(client.recv(1), b"")

    def test_signed_package_keeps_identity_and_private_key_across_updates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.dict(os.environ, {"HOME":directory, "XDG_CONFIG_HOME":str(root/"config"),
                                          "XDG_CACHE_HOME":str(root/"cache")}):
                settings = windows.install_package(root / "config.json", source, root / "out")
                self.assertEqual(settings, windows.install_package(root / "config.json", source, root / "out"))
            self.assertRegex(settings["id"], "^[a-p]{32}$")
            self.assertEqual(Path(settings["package"]).read_bytes()[:8], b"Cr24\x03\0\0\0")
            self.assertEqual((root / "out/extension.pem").stat().st_mode & 0o777, 0o600)
            value = {"userDataDir":str(root / "data"), "windowExtension":settings}
            windows.register_host(runtime, value)
            manifest = runtime.read_json(root / "data/NativeMessagingHosts/com.agentshell.windows.json")
            self.assertEqual(manifest["allowed_origins"], ["chrome-extension://" + settings["id"] + "/"])


def eventually(check, seconds=8):
    limit = time.monotonic() + seconds
    while time.monotonic() < limit:
        result = check()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError("Timed out waiting for " + check.__name__)


def folder_picker(path):
    """Private desktop portal fixture: select the test extension in Chrome's normal picker."""
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio, GLib
    xml = """<node><interface name="org.freedesktop.portal.FileChooser">
        <method name="OpenFile"><arg type="s" direction="in"/><arg type="s" direction="in"/>
        <arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
        <property name="version" type="u" access="read"/></interface></node>"""
    bus = Gio.bus_get_sync(Gio.BusType.SESSION)
    def open_file(conn, sender, _, interface, method, args, call):
        _, _, opts = args.unpack()
        assert method == "OpenFile" and opts.get("directory")
        obj = "/org/freedesktop/portal/desktop/request/" + sender[1:].replace(".", "_") + "/" + opts["handle_token"]
        call.return_value(GLib.Variant("(o)", (obj,)))
        def respond():
            conn.emit_signal(sender, obj, "org.freedesktop.portal.Request", "Response", GLib.Variant(
                "(ua{sv})", (0, {"uris":GLib.Variant("as",[path.as_uri()])})))
            return False
        GLib.timeout_add(100, respond)
    iface = Gio.DBusNodeInfo.new_for_xml(xml).interfaces[0]
    bus.register_object("/org/freedesktop/portal/desktop", iface, open_file,
                        lambda *unused: GLib.Variant("u", 3), None)
    Gio.bus_own_name_on_connection(bus, "org.freedesktop.portal.Desktop", Gio.BusNameOwnerFlags.NONE,
                                   lambda *_: (path.parent/"picker-ready").touch(), None)
    GLib.MainLoop().run()


def exercise_chrome(temp):
    from websockets.sync.client import connect
    chrome = "/opt/google/chrome/chrome"
    if not Path(chrome).is_file():
        raise RuntimeError("Stock Google Chrome required")
    # The package and the display are temporary. No existing profile or desktop is used.
    display = next(n for n in range(140, 200) if not Path(f"/tmp/.X11-unix/X{n}").exists()
                   and not Path(f"/tmp/.X{n}-lock").exists())
    os.environ.update(HOME=str(temp), TMPDIR=str(temp), XDG_CONFIG_HOME=str(temp/"config"),
                      XDG_CACHE_HOME=str(temp/"cache"), XDG_DATA_HOME=str(temp/"share"),
                      XDG_RUNTIME_DIR=str(temp/"xdg"), DISPLAY=f":{display}",
                      XAUTHORITY=str(temp/"auth"), GTK_USE_PORTAL="0", GDK_BACKEND="x11",
                      GIO_USE_VFS="local", GIO_USE_VOLUME_MONITOR="unix")
    os.environ.pop("WAYLAND_DISPLAY", None)
    (temp/"xdg").mkdir(mode=0o700)
    with contextlib.ExitStack() as cleanup:
        log = cleanup.enter_context((temp/"test.log").open("ab"))
        def spawn(args):
            proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
            def stop():
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGTERM)
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait()
            cleanup.callback(stop)
            return proc
        bus = subprocess.check_output(["dbus-daemon","--session","--fork","--print-address","--print-pid"], text=True).splitlines()
        os.environ["DBUS_SESSION_BUS_ADDRESS"] = bus[0]
        def unmount_private_portals():
            for name in ("doc","gvfs"):
                point = temp / "xdg" / name
                if point.exists() or point.is_mount():
                    subprocess.run(["fusermount3","-uz",str(point)], stdout=log, stderr=log, check=False)
        if shutil.which("fusermount3"):
            cleanup.callback(unmount_private_portals)
        cleanup.callback(os.kill, int(bus[1]), signal.SIGTERM)
        subprocess.run(["xauth","-f",os.environ["XAUTHORITY"],"add",f":{display}",".",secrets.token_hex(16)], check=True, capture_output=True)
        args = [f":{display}", "-auth", os.environ["XAUTHORITY"], "-nolisten", "tcp"]
        if shutil.which("Xvfb"):
            spawn(["Xvfb", *args, "-screen", "0", "1280x900x24"])
        else:
            passwd = temp/"password"
            passwd.write_text("fixture:" + secrets.token_hex(16) + "\n")
            with socket.socket() as listener:
                listener.bind(("127.0.0.1",0))
                port = listener.getsockname()[1]
            spawn(runtime.display_command({"isolateHostFfmpeg":Path("/etc/arch-release").exists()},
                  [*args,"-geometry","1280x900","-depth","24","-interface","127.0.0.1",
                   "-websocketPort",str(port),"-KasmPasswordFile",str(passwd)]))
        eventually(lambda: Path(f"/tmp/.X11-unix/X{display}").exists())
        spawn(["openbox"])
        eventually(lambda: b"window id" in subprocess.check_output(["xprop","-root","_NET_SUPPORTING_WM_CHECK"]))
        config = temp/"config.json"
        package = windows.install_package(config, source, temp/"out")
        root = temp/"browser"
        run = temp/"runtime"
        run.mkdir(mode=0o700)
        value = dict(sessionId=str(uuid.uuid4()), userDataDir=str(root), runtimeDir=str(run),
                     display=display,port=27001,password="fixture",windowExtension=package)
        runtime.write_json(config, value)
        windows.register_host(runtime, value)
        spawn(["/usr/bin/python3",__file__,"--fixture-portal",str(temp/"out/extension")])
        eventually(lambda: (temp/"out/picker-ready").exists())
        proc = spawn([chrome, "--user-data-dir=" + str(root), "--ozone-platform=x11", "--disable-gpu",
                      "--no-first-run","--no-default-browser-check","--disable-sync","--disable-background-networking",
                      "--password-store=basic","--remote-debugging-port=0","chrome://extensions"])
        generation = str(uuid.uuid4())
        record = dict(state="ready", pid=os.getpid(), processGeneration=runtime.generation(os.getpid()),
                      chromePid=proc.pid, chromeGeneration=runtime.generation(proc.pid), runtimeGeneration=generation,
                      configFingerprint=runtime.fingerprint(value),windowProfileDirectory="Default")
        runtime.write_json(run/"runtime.json", record)
        eventually(lambda: (root/"DevToolsActivePort").exists())
        debug_port = (root/"DevToolsActivePort").read_text().splitlines()[0]
        def targets():
            with urllib.request.urlopen("http://127.0.0.1:"+debug_port+"/json/list", timeout=2) as response:
                return json.load(response)
        tab = eventually(lambda: next((t for t in targets() if t["type"] == "page"), None))
        def load_current_profile_extension(tab):
            with connect(tab["webSocketDebuggerUrl"]) as ws:
                ws.send(json.dumps({"id":0,"method":"Page.navigate","params":{"url":"chrome://extensions/"}}))
                eventually(lambda: next((t for t in targets() if t["url"].startswith("chrome://extensions")), None))
                def evaluate(expression):
                    ws.send(json.dumps({"id":1,"method":"Runtime.evaluate","params":{"expression":expression,
                                        "returnByValue":True,"userGesture":True}}))
                    while True:
                        result = json.loads(ws.recv(timeout=3))
                        if result.get("id") == 1:
                            return result["result"]["result"].get("value")
                toolbar = 'document.querySelector("extensions-manager")?.shadowRoot?.querySelector("extensions-toolbar")?.shadowRoot'
                eventually(lambda: evaluate("!!"+toolbar))
                evaluate(toolbar+'.querySelector("#devMode").click()')
                eventually(lambda: evaluate(toolbar+'.querySelector("#loadUnpacked").getBoundingClientRect().width>0'))
                evaluate(toolbar+'.querySelector("#loadUnpacked").click()')
        load_current_profile_extension(tab)
        eventually(lambda: list((run/"windows").glob("worker-*")), 15)
        def action(name, revision=None, **fields):
            request = dict(id=str(uuid.uuid4()),action=name,**fields)
            if revision: request["revision"] = revision
            if name == "list":
                request["allowPairing"] = True
            runner = Path(runtime.__file__)
            reply = json.loads(subprocess.check_output(["/usr/bin/python3",str(runner),"--config",str(config),
                "--generation",generation,"--request",json.dumps(request),"windows"]))
            assert reply.get("ok"), reply.get("code")
            return reply["snapshot"]
        def open_fixture(profile, name):
            page = temp / (name + ".html")
            page.write_text("<title>" + name + "</title><textarea>draft</textarea>")
            # These CLI invocations target only the owned throwaway profile and display.
            subprocess.run([chrome, "--user-data-dir=" + str(root), "--profile-directory=" + profile, page.as_uri()],
                           stdout=log, stderr=log, check=True, timeout=5)
            return eventually(lambda: next((t for t in targets() if t["title"] == name), None))
        sibling = open_fixture("Profile 1", "sibling-private")
        load_current_profile_extension(sibling)
        eventually(lambda: len(list((run/"windows").glob("worker-*"))) == 2, 15)
        open_fixture("Default", "owned-draft")
        open_fixture("Profile 1", "sibling-private")
        assert runtime.profile_owner(str(root)) == proc.pid
        snap = action("list")
        tabs = [t for w in snap["windows"] for t in w["tabs"]]
        assert snap["splitSupported"] and any(t["title"] == "owned-draft" for t in tabs)
        assert all(t["title"] != "sibling-private" for t in tabs)
        original = next(w for w in snap["windows"] if any(t["title"] == "owned-draft" for t in w["tabs"]))
        tab_id = next(t["id"] for t in original["tabs"] if t["title"] == "owned-draft")
        split = action("detach",snap["revision"],windowId=original["id"],tabId=tab_id)
        new = next(w for w in split["windows"] if any(t["id"] == tab_id for t in w["tabs"]))
        assert new["id"] != original["id"]
        merged = action("merge",split["revision"],targetWindowId=original["id"])
        assert len(merged["windows"]) == 1 and any(t["id"] == tab_id for t in merged["windows"][0]["tabs"])
        assert any(t["title"] == "sibling-private" for t in targets())
        print("Stock Chrome picker install, two-profile port isolation, live detach and merge verified.")



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--chrome", action="store_true")
    parser.add_argument("--fixture-portal", type=Path)
    args = parser.parse_args()
    if args.fixture_portal:
        folder_picker(args.fixture_portal)
        raise SystemExit(0)
    if args.chrome:
        with tempfile.TemporaryDirectory(prefix="agentshell-native-window-") as root:
            exercise_chrome(Path(root))
    else:
        unittest.main(argv=[__file__])
