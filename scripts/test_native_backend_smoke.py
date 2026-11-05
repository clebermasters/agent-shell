#!/usr/bin/env python3
"""Run a native backend in disposable HOME/DB/TMUX, including real PTY I/O.

No agent, production daemon, default TMUX socket, phone or personal settings are
used. This runs on Linux and macOS and needs Python websockets 13–15.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from unittest.mock import patch

import websockets
import process_identity


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0)); return connection.getsockname()[1]


def api(port, token=None):
    request = urllib.request.Request(f'http://127.0.0.1:{port}/api/clients', headers={'X-Auth-Token': token} if token else {})
    try:
        with urllib.request.urlopen(request, timeout=2) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as response: return response.code, {}


async def receive(connection, kind, trace=None):
    async def read():
        while True:
            value = json.loads(await connection.recv())
            if trace is not None: trace.append({'type': value.get('type'), 'data': value.get('data') if value.get('type') == 'output' else None})
            if value.get('type') == kind: return value
    return await asyncio.wait_for(read(), 8)


def run(binary, output=None):
    binary = binary.resolve(); assert binary.is_file()
    socket_name = 'as-native-' + uuid.uuid4().hex[:12]
    checks = {}; backend = None; trace = []
    if output is not None: output=output.resolve();output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    with tempfile.TemporaryDirectory(prefix='as-tmux-', dir='/tmp') as socket_directory, \
         tempfile.TemporaryDirectory(prefix='agentshell-native-smoke-') as temporary:
        root = Path(temporary); data = root / 'backend'; data.mkdir(); home = root / 'home'; home.mkdir()
        token = secrets.token_urlsafe(32); port = free_port()
        tmux_environment = dict(os.environ, TMUX_TMPDIR=socket_directory, HOME=str(home), XDG_CONFIG_HOME=str(root / 'config'))
        for name in ['ENV', 'BASH_ENV']: tmux_environment.pop(name, None)
        def tmux(*arguments):
            return subprocess.check_output(['tmux', '-f', '/dev/null', '-L', socket_name, *arguments], env=tmux_environment, text=True).strip()
        def stop():
            if backend is not None and backend.poll() is None:
                backend.terminate()
                try: backend.wait(timeout=10)
                except subprocess.TimeoutExpired: backend.kill(); backend.wait(timeout=5)
        try:
            tmux('new-session', '-d', '-s', 'native-smoke', '-c', str(home), '/bin/sh')
            pane_pid = int(tmux('display-message', '-p', '-t', 'native-smoke', '#{pane_pid}'))
            context = tmux('display-message', '-p', '-t', 'native-smoke', '#{socket_path},#{pid},0')
            environment = dict(tmux_environment, HOME=str(home), XDG_CONFIG_HOME=str(root / 'config'), TMUX=context,
                AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(port), AGENTSHELL_HTTPS_PORT=str(free_port()),
                AGENTSHELL_TLS_CERT_PATH=str(root / 'missing-cert.pem'), AGENTSHELL_TLS_KEY_PATH=str(root / 'missing-key.pem'),
                AGENTSHELL_BROWSER_CONFIG=str(root / 'unconfigured-browser.json'), AGENTSHELL_BACKEND_BIN=str(binary),
                RUST_LOG='warn,agentshell_backend::websocket::terminal_cmds=debug', TOKIO_WORKER_THREADS='2')
            marker = root / 'open-file.txt'
            with marker.open('w'):
                native = subprocess.run([str(binary), 'process-info', '--pid', str(os.getpid()), '--open-files'],
                    cwd=data, env=environment, capture_output=True, text=True, check=True)
                identity = json.loads(native.stdout)
                with patch.dict(os.environ, AGENTSHELL_BACKEND_BIN=str(binary)):
                    python_identity = process_identity.process_info(os.getpid(), True)
                assert identity['pid'] == os.getpid() and identity['generation'] == python_identity['generation']
                assert str(marker.resolve()) in identity['openFiles']
                assert not list(data.iterdir()), 'process-info initialized daemon databases'
            checks['canonical_native_python_process_identity_and_open_files'] = True
            with (root / 'backend.log').open('wb') as log:
                def start():
                    process = subprocess.Popen([str(binary)], cwd=data, env=environment,
                        stdin=subprocess.DEVNULL, stdout=log, stderr=log)
                    deadline = time.monotonic() + 15
                    while True:
                        assert process.poll() is None, 'Owned backend exited'
                        try:
                            if api(port, token)[0] == 200: return process
                        except OSError: pass
                        assert time.monotonic() < deadline, 'Owned backend did not become healthy'
                        time.sleep(.05)
                backend = start()
                assert api(port)[0] == 401; checks['http_authentication'] = True
                async def verify():
                    async with websockets.connect(f'ws://127.0.0.1:{port}/ws',
                        additional_headers={'X-Auth-Token': token}, max_size=4*1024*1024) as connection:
                        await connection.send(json.dumps({'type': 'ping'})); await receive(connection, 'pong', trace)
                        checks['websocket_ping'] = True
                        await connection.send(json.dumps({'type': 'list-sessions'}))
                        sessions = await receive(connection, 'sessions-list', trace)
                        assert any(row['name'] == 'native-smoke' for row in sessions['sessions'])
                        checks['owned_tmux_session_discovery'] = True
                        # Cover a full five-second detail refresh. One delayed
                        # initial monitor snapshot is allowed after the explicit
                        # response, but an ordinary shell must not cause a
                        # broadcast every second because its tool is None.
                        idle_lists = []
                        idle_deadline = asyncio.get_running_loop().time() + 6
                        while asyncio.get_running_loop().time() < idle_deadline:
                            try:
                                packet = json.loads(await asyncio.wait_for(connection.recv(),
                                    idle_deadline - asyncio.get_running_loop().time()))
                            except asyncio.TimeoutError:
                                break
                            if packet.get('type') == 'sessions-list':
                                idle_lists.append(packet)
                        assert len(idle_lists) <= 1, f'Unchanged monitor sent {len(idle_lists)} unsolicited session lists'
                        checks['owned_monitor_quiet_when_idle'] = True

                        tmux('new-window', '-d', '-t', 'native-smoke', '-n', 'metadata-smoke', '/bin/sh')
                        updated = await receive(connection, 'sessions-list', trace)
                        assert next(row for row in updated['sessions'] if row['name'] == 'native-smoke')['windows'] == 2
                        tmux('kill-window', '-t', 'native-smoke:metadata-smoke')
                        updated = await receive(connection, 'sessions-list', trace)
                        assert next(row for row in updated['sessions'] if row['name'] == 'native-smoke')['windows'] == 1
                        checks['owned_monitor_structural_changes'] = True
                        await connection.send(json.dumps({'type': 'attach-session', 'sessionName': 'native-smoke',
                            'windowIndex': 0, 'cols': 80, 'rows': 24}))
                        await receive(connection, 'attached', trace)
                        # Attached confirms process creation, not that tmux has
                        # put its client TTY into raw mode. Early input can be
                        # discarded by that initialization. Wait for live PTY
                        # output from the new client before typing.
                        await receive(connection, 'output', trace)
                        proof = root / 'terminal-proof'
                        await connection.send(json.dumps({'type': 'input',
                            'data': 'printf AGENTSHELL_NATIVE_OK > ' + shlex.quote(str(proof)) + '\r'}))
                        deadline = time.monotonic() + 8
                        while not proof.is_file():
                            assert time.monotonic() < deadline, 'Owned PTY input was not executed'
                            await asyncio.sleep(.05)
                        assert proof.read_text() == 'AGENTSHELL_NATIVE_OK'
                        checks['native_portable_pty_input'] = True
                asyncio.run(verify())
                stop(); backend = start()
                assert int(tmux('display-message', '-p', '-t', 'native-smoke', '#{pane_pid}')) == pane_pid
                checks['daemon_restart_preserves_tmux'] = True
        finally:
            if output is not None:
                (output / 'wire-events.json').write_text(json.dumps(trace, indent=2) + '\n')
                try: (output / 'pane.txt').write_text(tmux('capture-pane', '-p', '-t', 'native-smoke'))
                except subprocess.CalledProcessError: pass
            stop()
            subprocess.run(['tmux', '-L', socket_name, 'kill-server'], env=tmux_environment, capture_output=True)
            if output is not None and (root / 'backend.log').is_file(): shutil.copyfile(root / 'backend.log', output / 'backend.log')
    print(json.dumps({'passed': len(checks), 'checks': checks}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Retain only owned-fixture diagnostics, including on failure')
    args=parser.parse_args();run(args.binary,args.output)
