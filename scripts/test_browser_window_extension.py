#!/usr/bin/env python3
"""Test the Chrome window operations on a disposable Chromium and private X display."""
import contextlib
import html
import importlib.util
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPTS = Path(__file__).resolve().parent


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def run(temp):
    results = queue.Queue()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path == '/active-window':
                current = subprocess.check_output(['xprop', '-root', '_NET_ACTIVE_WINDOW'], env=env, text=True)
                window = re.search(r'0x[0-9a-fA-F]+', current)
                title = subprocess.run(['xprop', '-id', window[0], '_NET_WM_NAME'],
                                       capture_output=True, env=env, text=True).stdout if window else ''
                self.send_response(200)
                self.end_headers()
                self.wfile.write(title.encode())
                return
            self.send_response(200)
            self.send_header('Content-Type', 'text/html')
            self.end_headers()
            title = 'Fixture ' + html.escape(self.path.lstrip('/'))
            self.wfile.write(('<!doctype html><title>' + title + '</title><textarea></textarea>').encode())

        def do_POST(self):
            if self.path != '/result':
                self.send_error(404)
                return
            results.put(json.loads(self.rfile.read(min(65536, int(self.headers['Content-Length'])))))
            self.send_response(204)
            self.end_headers()

    spec = importlib.util.spec_from_file_location('window_test_runtime', SCRIPTS / 'browser-runtime.py')
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    children = []
    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server, contextlib.ExitStack() as cleanup:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        cleanup.callback(server.shutdown)
        base = f'http://127.0.0.1:{server.server_port}'
        ext = temp / 'extension'
        shutil.copytree(SCRIPTS.parent / 'deployment/browser/window-extension', ext)
        # The synthetic test has permission to type into its loopback fixture pages.
        manifest = json.loads((ext / 'manifest.json').read_text())
        manifest['permissions'] = ['tabs', 'tabGroups', 'scripting']
        manifest['host_permissions'] = ['http://127.0.0.1/*']
        manifest['background']['service_worker'] = 'exercise.js'
        (ext / 'manifest.json').write_text(json.dumps(manifest))
        shutil.copyfile(SCRIPTS / 'test_browser_window_extension.js', ext / 'verify.js')
        (ext / 'exercise.js').write_text(
            "importScripts('windows.js','verify.js');\n"
            'chrome.runtime.onInstalled.addListener(async () => {\n'
            '  let result;\n'
            f'  try {{ result = await verifyWindows({json.dumps(base)}); }}\n'
            '  catch (error) { result = {passed:false,error:error.stack}; }\n'
            f"  await fetch('{base}/result', {{method:'POST',body:JSON.stringify(result)}});\n"
            '});\n')

        display = next(d for d in range(110, 240) if not Path(f'/tmp/.X11-unix/X{d}').exists()
                       and not Path(f'/tmp/.X{d}-lock').exists())
        home = temp / 'home'
        home.mkdir()
        runtime_dir = temp / 'runtime'
        runtime_dir.mkdir(mode=0o700)
        env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(home / 'config'),
                   XDG_CACHE_HOME=str(home / 'cache'), XDG_DATA_HOME=str(home / 'data'),
                   XDG_RUNTIME_DIR=str(runtime_dir), DISPLAY=f':{display}', XAUTHORITY=str(temp / 'authority'),
                   GDK_BACKEND='x11', GTK_USE_PORTAL='0', TMPDIR=str(temp))
        env.pop('WAYLAND_DISPLAY', None)
        env.pop('DBUS_SESSION_BUS_ADDRESS', None)
        subprocess.run(['xauth', '-f', env['XAUTHORITY'], 'add', f':{display}', '.', secrets.token_hex(16)],
                       env=env, check=True, capture_output=True)
        bus = subprocess.run(['dbus-daemon', '--session', '--fork', '--print-address', '--print-pid'],
                             env=env, text=True, capture_output=True, check=True).stdout.splitlines()
        env['DBUS_SESSION_BUS_ADDRESS'] = bus[0]
        cleanup.callback(os.kill, int(bus[1]), signal.SIGTERM)
        def stop_children():
            for proc in reversed(children):
                if proc.poll() is not None:
                    continue
                proc.terminate()
                try:
                    proc.wait(timeout=7)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
        cleanup.callback(stop_children)

        def launch(args):
            log = cleanup.enter_context((temp / 'process.log').open('ab'))
            children.append(subprocess.Popen(args, env=env, stdout=log, stderr=log))
            return children[-1]

        args = [f':{display}', '-auth', env['XAUTHORITY'], '-nolisten', 'tcp']
        if shutil.which('Xvfb'):
            launch(['Xvfb', *args, '-screen', '0', '1280x900x24'])
        else:
            password = temp / 'kasm-password'
            password.write_text('fixture:' + secrets.token_hex(16) + '\n')
            password.chmod(0o600)
            launch(runtime.display_command({'isolateHostFfmpeg': Path('/etc/arch-release').exists()},
                   [*args, '-geometry', '1280x900', '-depth', '24', '-interface', '127.0.0.1',
                    '-websocketPort', str(free_port()), '-KasmPasswordFile', str(password)]))
        for _ in range(120):
            if Path(f'/tmp/.X11-unix/X{display}').exists():
                break
            if children[-1].poll() is not None:
                raise RuntimeError('Private X server exited: ' + (temp / 'process.log').read_text()[-2000:])
            time.sleep(.05)
        else:
            raise RuntimeError('Private X server failed to start')
        launch(['openbox'])
        for _ in range(100):
            prop = subprocess.run(['xprop', '-root', '_NET_SUPPORTING_WM_CHECK'], env=env, capture_output=True)
            if b'window id' in prop.stdout:
                break
            time.sleep(.05)
        else:
            raise RuntimeError('Private window manager failed to start')
        launch(['chromium', '--user-data-dir=' + str(temp / 'profile'), '--ozone-platform=x11',
                '--password-store=basic', '--disable-gpu', '--disable-background-networking', '--disable-sync',
                '--no-first-run', '--no-default-browser-check', '--disable-extensions-except=' + str(ext),
                '--load-extension=' + str(ext), 'about:blank'])
        try:
            result = results.get(timeout=70)
        except queue.Empty:
            raise RuntimeError('No extension result: ' + (temp / 'process.log').read_text(errors='replace')[-4000:])
        if not result.get('passed'):
            raise AssertionError(result)
        print(json.dumps(result))


if __name__ == '__main__':
    with tempfile.TemporaryDirectory(prefix='agentshell-window-test-') as directory:
        run(Path(directory))
