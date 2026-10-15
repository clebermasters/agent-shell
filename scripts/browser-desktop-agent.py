#!/usr/bin/python3
"""Same-user graphical-session broker for Android's Send to computer action.

Started by graphical-session.target/XDG autostart. Has no TCP listener and never
guesses a display or changes Chrome/profile ownership. No browser API token is
passed in arguments, published over IPC or exported into the session manager.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import threading
import uuid

spec = importlib.util.spec_from_file_location('desktop', Path(__file__).with_name('browser-desktop.py'))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


def register():
    if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
        raise desktop.Failure('NO_DESKTOP')
    # Only graphical connection metadata. Never import the full environment.
    names = [key for key in ['DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY', 'XDG_SESSION_TYPE',
                             'XDG_CURRENT_DESKTOP'] if os.environ.get(key)]
    subprocess.run(['systemctl', '--user', 'import-environment', *names], check=True, capture_output=True)
    subprocess.run(['systemctl', '--user', 'daemon-reload'], check=True, capture_output=True)
    subprocess.run(['systemctl', '--user', 'enable', 'agentshell-browser-desktop-agent.service'],
                   check=True, capture_output=True)
    subprocess.run(['systemctl', '--user', 'restart', 'agentshell-browser-desktop-agent.service'],
                   check=True, capture_output=True)


def serve(config):
    if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
        raise desktop.Failure('NO_DESKTOP')
    # XDG autostart imports real session metadata before starting the service.
    # If an inherited display cannot be opened, do not claim desktop readiness.
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk
    if not Gtk.init_check()[0]:
        raise desktop.Failure('NO_DESKTOP')
    lock = (config.state / 'desktop-agent.lock').open('a')
    os.chmod(lock.name, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    path = config.state / 'desktop-agent.sock'
    path.unlink(missing_ok=True)
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(str(path))
    path.chmod(0o600)
    server.listen(4)
    server.settimeout(0.5)
    stopping = threading.Event()
    for name in (signal.SIGTERM, signal.SIGINT):
        signal.signal(name, lambda *_: stopping.set())
    try:
        while not stopping.is_set():
            try:
                client, _ = server.accept()
            except socket.timeout:
                continue
            with client:
                client.settimeout(54)
                try:
                    _, uid, _ = struct.unpack('3i', client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    if uid != os.getuid():
                        continue
                    request = desktop.receive(client)
                    command=request.get('command')
                    if command not in ('desktop','native-desktop','native-phone'):
                        continue
                    generation = request['generation']
                    if not isinstance(generation, str) or not 1 <= len(generation) <= 256:
                        continue
                    if command=='desktop':
                        handoff=str(uuid.UUID(request['handoffId']))
                        arguments=['/usr/bin/python3',str(Path(__file__).with_name('browser-desktop.py')),
                            '--config',str(config.path),'--handoff-id',handoff,'--generation',generation,'--json','desktop']
                        timeout=52
                    else:
                        native_config=config.path.with_name('browser.json')
                        arguments=['/usr/bin/python3',str(Path(__file__).with_name('browser-native.py')),
                            '--config',str(native_config),'--generation',generation,command.removeprefix('native-')]
                        timeout=65
                    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
                    value = json.loads(result.stdout)
                    # Return only status, never raw logs/exception details.
                    response = {'code':value.get('code','VIEWER_UNAVAILABLE'), 'connected':bool(value.get('connected')),
                        **{k:value[k] for k in ['destination','session','phonePreserved','normalExit'] if k in value}}
                    client.sendall(json.dumps(response).encode()+b'\n')
                except (OSError, ValueError, KeyError, TypeError, desktop.Failure, subprocess.TimeoutExpired):
                    try:
                        client.sendall(b'{"code":"DESKTOP_UNAVAILABLE","connected":false}\n')
                    except OSError:
                        pass
    finally:
        server.close()
        path.unlink(missing_ok=True)
        lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path.home()/'.config/agentshell/browser-desktop.json')
    parser.add_argument('--register-session', action='store_true')
    args = parser.parse_args()
    try:
        if args.register_session:
            register()
        else:
            serve(desktop.Config(args.config))
    except (desktop.Failure, OSError, subprocess.CalledProcessError):
        raise SystemExit('Desktop session is unavailable; Android can continue controlling Chrome.')


if __name__ == '__main__':
    main()
