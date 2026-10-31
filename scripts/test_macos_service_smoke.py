#!/usr/bin/env python3
"""Exercise the real per-user macOS installer/launchctl in disposable CI state.

Refuses non-CI hosts and any existing AgentShell job. The actual backend binary
is installed unchanged; a temporary PATH shim confines all tmux and crontab
operations to this fixture. No Chrome, agent CLI or privileged service is used.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import plistlib
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

import websockets

PROJECT = Path(__file__).resolve().parents[1]
LABEL = 'com.agentshell.backend'


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0)); return probe.getsockname()[1]


def run(binary, output):
    if sys.platform != 'darwin' or os.environ.get('CI', '').lower() != 'true':
        raise ValueError('This service smoke requires macOS and CI=true; it never installs on a personal host')
    binary = binary.resolve(); assert binary.is_file()
    domain = f'gui/{os.getuid()}'; target = domain + '/' + LABEL
    for scope in [f'user/{os.getuid()}', domain]:
        if subprocess.run(['launchctl', 'print', scope + '/' + LABEL], capture_output=True).returncode == 0:
            raise ValueError('An AgentShell job already exists; refusing to change it')
    assert subprocess.run(['launchctl', 'print', domain], capture_output=True).returncode == 0, 'Current user launchd domain is unavailable'
    real_tmux = shutil.which('tmux'); assert real_tmux, 'tmux is required'
    output = output.resolve(); output.mkdir(parents=True, exist_ok=True); output.chmod(0o700)
    checks = {}; token = secrets.token_urlsafe(32); touched = False
    with tempfile.TemporaryDirectory(prefix='as-svc-', dir='/tmp') as temporary:
        root = Path(temporary).resolve(); home = root / 'home'; home.mkdir()
        prefix = home / 'Library/Application Support/AgentShell'; commands = prefix / 'bin'; commands.mkdir(parents=True)
        socket_path = str(root / 'tmux.sock')
        shim = commands / 'tmux'
        shim.write_text('#!/bin/sh\nexec env -u TMUX -u ENV -u BASH_ENV ' + shlex.quote(real_tmux) +
                        ' -f /dev/null -S ' + shlex.quote(socket_path) + ' "$@"\n'); shim.chmod(0o755)
        cron = commands / 'crontab'; cron.write_text('#!/bin/sh\nexit 1\n'); cron.chmod(0o755)
        env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(home / '.config'), AUTH_TOKEN=token, RUST_LOG='warn',
                   AGENTSHELL_INSTALL_DIAGNOSTICS=str(output / 'installer-failure'))
        env.pop('TMUX', None)
        http, https = free_port(), free_port()
        while https == http: https = free_port()
        runtime = prefix / 'backend/runtime.env'
        plist = home / 'Library/LaunchAgents' / (LABEL + '.plist')
        logs = home / 'Library/Logs/AgentShell'
        def command(arguments, name):
            result = subprocess.run(arguments, env=env, capture_output=True, text=True, timeout=90)
            (output / (name + '.log')).write_text(result.stdout + result.stderr)
            assert token not in result.stdout + result.stderr, 'A command printed the synthetic credential'
            result.check_returncode(); return result
        def service(action, name=None):
            return command([sys.executable, str(PROJECT / 'scripts/backend-runtime.py'), 'service', action,
                            '--install-dir', str(prefix)], name or action)
        def health(expected=200):
            request = urllib.request.Request(f'http://127.0.0.1:{http}/api/clients',
                headers={'X-Auth-Token': token} if expected == 200 else {})
            try:
                with urllib.request.urlopen(request, timeout=2) as response:
                    return response.status == expected and isinstance(json.load(response).get('clients'), int)
            except urllib.error.HTTPError as error: return error.code == expected
        def ready():
            deadline = time.monotonic() + 20
            while True:
                try:
                    if health(): return
                except OSError: pass
                assert time.monotonic() < deadline, 'Installed launchd daemon did not become healthy'
                time.sleep(.1)
        def pane_pid():
            return subprocess.check_output([str(shim), 'display-message', '-p', '-t', 'launchd-smoke', '#{pane_pid}'],
                                           env=env, text=True).strip()
        try:
            touched = True
            command([sys.executable, str(PROJECT / 'scripts/install-macos.py'), '--binary', str(binary),
                '--install-dir', str(prefix), '--launch-agent-dir', str(plist.parent), '--log-dir', str(logs),
                '--skip-dependencies', '--agents', 'none', '--no-tls', '--http-port', str(http),
                '--https-port', str(https)], 'installer')
            ready(); assert health(401); checks['real_installer_authenticated_launchd_start'] = True
            assert runtime.stat().st_mode & 0o077 == 0
            definition = plistlib.loads(plist.read_bytes())
            assert token.encode() not in plist.read_bytes() and definition['AbandonProcessGroup'] is True
            assert definition['EnvironmentVariables']['HOME'] == str(home)
            checks['private_token_and_tmux_safe_plist'] = True
            service('status'); checks['runtime_service_status'] = True
            async def create_owned_session():
                async with websockets.connect(f'ws://127.0.0.1:{http}/ws', additional_headers={'X-Auth-Token': token}) as connection:
                    await connection.send(json.dumps({'type': 'create-session', 'name': 'launchd-smoke', 'startDirectory': str(home)}))
                    while True:
                        message = json.loads(await asyncio.wait_for(connection.recv(), 8))
                        if message.get('type') == 'session-created':
                            assert message.get('success') is True; return
            asyncio.run(create_owned_session()); before = pane_pid()
            service('restart'); ready(); assert pane_pid() == before
            checks['real_kickstart_restart_preserves_owned_tmux'] = True
            service('stop')
            assert subprocess.run(['launchctl', 'print', target], capture_output=True).returncode != 0
            assert pane_pid() == before; checks['real_bootout_stop_preserves_owned_tmux'] = True
            service('start'); ready(); assert pane_pid() == before
            checks['runtime_service_bootstrap_start'] = True
        finally:
            if touched:
                loaded = subprocess.run(['launchctl', 'print', target], capture_output=True, text=True)
                if loaded.returncode == 0:
                    if root.name not in loaded.stdout:
                        raise RuntimeError('Refusing to unload a job which does not refer to this fixture')
                    subprocess.run(['launchctl', 'bootout', target], check=True, capture_output=True)
            subprocess.run([str(shim), 'kill-server'], env=env, capture_output=True)
            for name in ['stdout.log', 'stderr.log']:
                path = logs / name
                if path.is_file():
                    data = path.read_bytes()
                    assert token.encode() not in data, 'Daemon logged the synthetic credential'
                    (output / name).write_bytes(data)
        checks['fixture_launchd_and_tmux_cleaned'] = True
    (output / 'results.json').write_text(json.dumps({'checks': checks}, indent=2) + '\n')
    print(json.dumps({'passed': len(checks), 'checks': checks}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); run(args.binary, args.output)
