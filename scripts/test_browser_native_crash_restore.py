#!/usr/bin/env python3
"""Real Linux Chrome handoff after a crash in a disposable two-tab profile.

Requires Google Chrome, KasmVNC, Openbox, xauth, GTK 3 and AT-SPI 2. All browser
processes, displays, preferences and session files belong to this fixture.
"""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse
import uuid
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(check, message, timeout=15):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if check():
            return
        time.sleep(.15)
    raise AssertionError(message)


def wait_for_restore_prompt(env):
    """Check the real Chrome alert on the fixture's private accessibility bus."""
    with patch.dict(os.environ, env, clear=True):
        import gi
        gi.require_version("Atspi", "2.0")
        from gi.repository import Atspi

        def find(node, in_alert=False):
            role, name = node.get_role_name(), node.get_name()
            in_alert |= role == "alert" and name == "Restore pages?"
            if in_alert and role == "button" and name == "Restore":
                return node.get_state_set().contains(Atspi.StateType.SHOWING)
            return any(find(child, in_alert) for i in range(node.get_child_count())
                       if (child := node.get_child_at_index(i)) is not None)

        wait_for(lambda: find(Atspi.get_desktop(0)), "Chrome did not offer its original session on the private desktop")


def exercise(root, cleanup):
    source = SCRIPTS / "browser-runtime.py"
    spec = importlib.util.spec_from_file_location("handoff_fixture_runtime", source)
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    displays = iter(d for d in range(120, 200) if not Path(f"/tmp/.X11-unix/X{d}").exists()
                    and not Path(f"/tmp/.X{d}-lock").exists())
    desktop, phone = next(displays), next(displays)
    profile, records = root / "fixture-profile", root / "records"
    records.mkdir(mode=0o700)
    os_runtime = root / "xdg"
    os_runtime.mkdir(mode=0o700)
    debug = port()
    # Only this private Chrome advertises the test pages to the loopback probe.
    chrome = shutil.which("google-chrome-stable", path=os.defpath) or shutil.which("google-chrome", path=os.defpath)
    assert chrome, "Install Google Chrome"
    wrapper = root / "google-chrome"
    wrapper.write_text("#!/bin/sh\nexec " + shlex.quote(chrome) + " --ozone-platform=x11 --password-store=basic --force-renderer-accessibility "
                       f"--remote-debugging-port={debug} --disable-background-networking \"$@\"\n")
    wrapper.chmod(0o700)
    env = dict(HOME=str(root), PATH=f"{root}:{os.defpath}", LANG="C.UTF-8", DISPLAY=f":{desktop}",
               XDG_RUNTIME_DIR=str(os_runtime), XAUTHORITY=str(root / "authority"), GDK_BACKEND="x11",
               GNOME_ACCESSIBILITY="1", ACCESSIBILITY_ENABLED="1")
    bus_services = root / "services"
    bus_services.mkdir()
    shutil.copy("/usr/share/dbus-1/services/org.a11y.Bus.service", bus_services)
    bus_config = root / "bus.conf"
    bus_config.write_text(f'<busconfig><type>session</type><listen>unix:tmpdir={os_runtime}</listen>'
                          f'<servicedir>{bus_services}</servicedir><policy context="default">'
                          '<allow send_destination="*"/><allow receive_sender="*"/><allow own="*"/></policy></busconfig>')
    address, bus_pid = subprocess.check_output(["dbus-daemon", "--config-file=" + str(bus_config), "--fork", "--print-address", "--print-pid"],
                                               env=env, text=True).splitlines()
    env["DBUS_SESSION_BUS_ADDRESS"] = address
    cleanup.callback(os.kill, int(bus_pid), signal.SIGTERM)
    value = dict(sessionId=str(uuid.uuid4()), userDataDir=str(profile), runtimeDir=str(records),
                 runner=str(source), profileDirectory="Default", display=phone, port=port(), password=secrets.token_hex(32),
                 privateBus=True, supervision="process", includeDefaultProfiles=False,
                 isolateHostFfmpeg=Path("/etc/arch-release").exists())
    config = root / "config.json"
    runtime.write_json(config, value)
    def command(helper, *args):
        output = subprocess.check_output(["/usr/bin/python3", str(SCRIPTS / helper), "--config", str(config), *args],
                                         env=env, text=True, timeout=55)
        return json.loads(output)

    subprocess.run(["xauth", "-f", env["XAUTHORITY"], "add", env["DISPLAY"], ".", secrets.token_hex(16)],
                   env=env, check=True, capture_output=True)
    password = root / "kasm-passwd"
    subprocess.run(["kasmvncpasswd", "-u", "fixture", "-w", str(password)],
                   input=(value["password"] + "\n") * 2, text=True, check=True, capture_output=True)
    log = cleanup.enter_context((root / "fixture.log").open("ab"))
    server = subprocess.Popen(runtime.display_command(value, [env["DISPLAY"], "-auth", env["XAUTHORITY"], "-nolisten", "tcp",
                              "-geometry", "1280x800", "-depth", "24", "-interface", "127.0.0.1", "-websocketPort",
                              str(port()), "-KasmPasswordFile", str(password)]), env=env, stdout=log, stderr=log, start_new_session=True)
    def stop_display():
        if server.poll() is None:
            os.killpg(server.pid, signal.SIGTERM)
            server.wait(timeout=8)
    cleanup.callback(stop_display)
    wait_for(lambda: Path(f"/tmp/.X11-unix/X{desktop}").is_socket(), "Fixture desktop did not start")
    pages = set()
    for name in ("first", "second"):
        page = root / (name + ".html")
        page.write_text("<title>" + name + "</title>")
        pages.add(page.as_uri())
    def visible_pages():
        with urllib.request.urlopen(f"http://127.0.0.1:{debug}/json/list", timeout=1) as response:
            return {p["url"]: p["id"] for p in json.load(response) if p.get("type") == "page"}
    def restored():
        try:
            return set(visible_pages()) == pages
        except OSError:
            return False
    def on_disk():
        try:
            return json.loads((profile / "Default/Preferences").read_text())["profile"]["exit_type"]
        except (OSError, KeyError, ValueError):
            return None
    def has_checkpoint():
        return any(all(url.encode() in session.read_bytes() for url in pages)
                   for session in (profile / "Default/Sessions").glob("Session_*"))
    with subprocess.Popen([str(wrapper), "--user-data-dir=" + str(profile), "--no-first-run", "--no-default-browser-check",
                           *sorted(pages)], env=env, stdout=log, stderr=log) as seed:
        try:
            wait_for(restored, "Chrome did not open the two fixture pages")
            wait_for(lambda: on_disk() == "Crashed" and has_checkpoint(), "Chrome did not persist its fixture session")
            assert runtime.profile_owner(str(profile)) == seed.pid
        finally:
            # A genuine interruption of the exact test subprocess. Chrome writes all
            # Preferences and session files itself throughout this test.
            seed.kill()
    assert on_disk() == "Crashed"
    runtime.write_json(records / "restore-next.json", {"restore": True})
    state = command("browser-runtime.py", "start")
    def stop_fixture():
        for name in ("native.json", "runtime.json"):
            record = runtime.read_json(records / name)
            pid = record.get("chromePid")
            if pid and runtime.generation(pid) == record.get("chromeGeneration") and runtime.profile_owner(str(profile)) == pid:
                os.kill(pid, signal.SIGINT)
                wait_for(lambda: runtime.profile_owner(str(profile)) != pid, "Owned test Chrome failed to close")
            supervisor = record.get("pid")
            if supervisor and record.get("processGeneration"):
                wait_for(lambda: runtime.generation(supervisor) != record["processGeneration"], "Owned test supervisor failed to exit")
    cleanup.callback(stop_fixture)
    assert state["state"] == "ready", state
    # On a genuinely interrupted profile Chrome may show its recovery bubble
    # and a New Tab. Simulate browsing both pages without acknowledging it.
    def ready():
        try:
            return bool(visible_pages())
        except OSError:
            return False
    wait_for(ready, "Virtual Chrome did not open after the crash")
    current = visible_pages()
    for url in pages - current.keys():
        request = urllib.request.Request(f"http://127.0.0.1:{debug}/json/new?" + urllib.parse.quote(url, safe=""), method="PUT")
        with urllib.request.urlopen(request, timeout=2):
            pass
    for url in current.keys() - pages:
        with urllib.request.urlopen(f"http://127.0.0.1:{debug}/json/close/{current[url]}", timeout=2):
            pass
    wait_for(restored, "The managed browser did not show both fixture pages")
    for destination, expected, record_name in [("desktop", "native", "runtime.json"), ("phone", "ready", "native.json")]:
        owner = runtime.read_json(records / record_name)
        moved = command("browser-native.py", "--generation", state["runtimeGeneration"], destination)
        assert moved.get("connected") and moved.get("normalExit"), moved
        state = moved["session"]
        assert state["state"] == expected and runtime.generation(owner["chromePid"]) != owner["chromeGeneration"], state
        if destination == "desktop":
            # An existing crash acknowledgement belongs to Chrome. Present the
            # saved session on the new desktop and let the user choose Restore.
            wait_for(ready, "Native Chrome did not expose its private test window")
            assert not restored(), "The saved session reopened before the recovery choice"
            wait_for_restore_prompt(env)
        wait_for(ready, f"Chrome on {destination} did not finish launching")
        assert has_checkpoint(), "The original two-tab recovery checkpoint was lost"
        assert runtime.read_json(records / record_name)["chromeExitCode"] == 0
        print(f"Verified {destination}: live destination, zero exit and intact two-tab recovery checkpoint", flush=True)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="agentshell-crash-restore-") as directory, contextlib.ExitStack() as cleanup:
        exercise(Path(directory), cleanup)
