#!/usr/bin/env python3
"""Second-pass integration check ONLY in an isolated, already-provisioned container."""
import asyncio
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from jsonc_config import parse

runtime_python = Path('/opt/agentshell/venv/bin/python')
if runtime_python.exists() and Path(__import__('sys').prefix).resolve() != runtime_python.parent.parent.resolve():
    os.execv(str(runtime_python), [str(runtime_python), __file__])
import websockets

if os.environ.get('AGENTSHELL_INSTALL_TEST') != '1' or not Path('/.dockerenv').exists():
    raise SystemExit('Run only in the owned installer test container, with AGENTSHELL_INSTALL_TEST=1')

project = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('backend_install', project / 'scripts/install-backend.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
base = Path('/opt/agentshell')
runtime = installer.dotenv(base / 'backend/runtime.env')
original_token = runtime['AUTH_TOKEN']
original_unit_directory = Path('/etc/systemd/system').stat()
profile = Path.home() / '.profile'
profile_before = profile.read_text() if profile.exists() else ''

# Add unrelated settings before a second installation, including JSONC comments.
codex = Path.home() / '.codex/config.toml'
existing = re.sub(r'\n\[mcp_servers\.fixture-other\]\n.*?(?=\n\[|\Z)', '', codex.read_text(), flags=re.S)
codex.write_text(existing + '\n[mcp_servers.fixture-other]\ncommand = "/bin/true"\nenabled = false\n')
claude = Path.home() / '.claude.json'
value = json.loads(claude.read_text())
value['mcpServers']['fixture-other'] = {'command': '/bin/true', 'args': []}
claude.write_text(json.dumps(value))
opencode = Path.home() / '.config/opencode/opencode.json'
jsonc = opencode.with_suffix('.jsonc')
value = json.loads(opencode.read_text()) if opencode.exists() else parse(jsonc.read_text())
value['theme'] = 'opencode'
value['mcp']['fixture-other'] = {'type': 'local', 'command': ['/bin/true'], 'enabled': False}
jsonc.write_text('// Preserve this user comment.\n' + json.dumps(value, indent=2))
opencode.unlink(missing_ok=True)
database = base / 'backend/chat_events.db'
with sqlite3.connect(database) as connection:
    connection.execute('CREATE TABLE IF NOT EXISTS installer_fixture_marker(value TEXT)')
    if not connection.execute('SELECT COUNT(*) FROM installer_fixture_marker').fetchone()[0]:
        connection.execute('INSERT INTO installer_fixture_marker VALUES (?)', ('existing-history',))

second = subprocess.run([str(project / 'install.sh'), '--no-start'], text=True, capture_output=True, check=True)
assert 'Installing missing system packages' not in second.stdout
assert 'Installing missing/unusable' not in second.stdout
assert installer.dotenv(base / 'backend/runtime.env')['AUTH_TOKEN'] == original_token
assert 'fixture-other' in codex.read_text()
assert 'fixture-other' in json.loads(claude.read_text())['mcpServers']
assert '// Preserve this user comment.' in jsonc.read_text()
configured = parse(jsonc.read_text())
assert 'fixture-other' in configured['mcp'] and 'agentshell-ui' in configured['mcp']
assert configured['theme'] == 'opencode'
assert not opencode.exists(), 'JSONC must not be shadowed by a new JSON file'
assert profile.read_text().startswith(profile_before)
assert profile.read_text().count('# AgentShell managed tools') == 1
shell = subprocess.run(['bash', '-ic', 'command -v opencode'], text=True, capture_output=True, check=True)
assert shell.stdout.strip() == '/opt/agentshell/tools/bin/opencode'
for file in [base / 'backend/runtime.env', Path.home() / '.config/agentshell/interactive-ui.json',
             Path.home() / '.config/agentshell/chat-binding-hook.json', base / 'certs/key.pem']:
    assert file.stat().st_mode & 0o777 == 0o600
assert json.loads((Path.home() / '.config/agentshell/interactive-ui.json').read_text())['token'] == original_token
unit = Path('/etc/systemd/system/agentshell.service')
assert unit.stat().st_uid == 0
assert Path('/etc/systemd/system').stat().st_uid == original_unit_directory.st_uid
assert original_token not in unit.read_text()
verified = subprocess.run(['systemd-analyze', 'verify', str(unit)], check=True, capture_output=True, text=True)
assert 'ignoring:' not in verified.stderr and 'not absolute' not in verified.stderr, verified.stderr
with sqlite3.connect(database) as connection:
    assert connection.execute('SELECT value FROM installer_fixture_marker').fetchall() == [('existing-history',)]
record = json.loads((base / 'installation.json').read_text())
for name, digest in record['files'].items():
    assert hashlib.sha256((base / name).read_bytes()).hexdigest() == digest, name
versions = {}
environment = dict(os.environ, PATH=str(base / 'tools/bin') + ':' + str(base / 'node/bin') + ':' + str(Path.home() / '.cargo/bin') + ':' + os.environ['PATH'])
for command in ['rustc', 'node', 'npm', 'codex', 'claude', 'opencode', 'tmux', 'ffmpeg']:
    result = subprocess.run([command, '-version' if command == 'ffmpeg' else '-V' if command == 'tmux' else '--version'],
                            env=environment, text=True, capture_output=True, check=True)
    versions[command] = result.stdout.splitlines()[0]
requests = [{'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2024-11-05'}},
            {'id': 2, 'method': 'tools/list'}, {'id': 3, 'method': 'tools/call',
             'params': {'name': 'get_ui_design_system', 'arguments': {}}}]
result = subprocess.run(['python3', str(base / 'ui/agent-ui-mcp.py')],
                        input=''.join(json.dumps(value) + '\n' for value in requests), text=True,
                        capture_output=True, check=True)
messages = [json.loads(line) for line in result.stdout.splitlines()]
assert len(messages) == 3 and all('result' in value for value in messages)
assert {tool['name'] for tool in messages[1]['result']['tools']} == {'get_ui_design_system', 'render_interactive_ui'}
assert '--accent' in messages[2]['result']['content'][0]['text']

subprocess.run(['tmux', 'new-session', '-d', '-s', 'installer-fixture', '/bin/sleep 600'], check=True)
server = subprocess.Popen([str(base / 'backend/agentshell-backend')], cwd=base / 'backend', env=dict(environment, **runtime),
                          stdout=(Path('/tmp') / 'agentshell-installer-backend.log').open('w'), stderr=subprocess.STDOUT)
try:
    deadline = time.monotonic() + 10
    url = 'http://127.0.0.1:' + runtime['AGENTSHELL_HTTP_PORT']
    while True:
        assert server.poll() is None, 'Installed backend exited'
        try:
            request = urllib.request.Request(url + '/api/clients', headers={'X-Auth-Token': original_token})
            with urllib.request.urlopen(request, timeout=2) as response:
                assert isinstance(json.load(response)['clients'], int)
            break
        except OSError:
            assert time.monotonic() < deadline
            time.sleep(.1)
    try:
        urllib.request.urlopen(url + '/api/clients', timeout=2)
        raise AssertionError('Unauthenticated request was accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    payload = {'codexThreadId': str(uuid.uuid4()), 'agentPid': os.getpid(), 'agentStart': 0,
               'title': 'Install probe', 'html': '<p>Do not publish</p>'}
    request = urllib.request.Request(url + '/api/chat/ui', data=json.dumps(payload).encode(),
                                    headers={'Content-Type': 'application/json', 'X-Auth-Token': original_token})
    try:
        urllib.request.urlopen(request, timeout=2)
        raise AssertionError('Unowned publication was accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 409 and 'publisher has changed' in error.read().decode()

    async def websocket_check():
        async with websockets.connect(url.replace('http://', 'ws://') + '/ws?token=' + original_token) as connection:
            await connection.send(json.dumps({'type': 'list-sessions'}))
            while True:
                value = json.loads(await asyncio.wait_for(connection.recv(), 10))
                if value.get('type') == 'sessions-list':
                    assert 'installer-fixture' in json.dumps(value)
                    return
    asyncio.run(websocket_check())
finally:
    server.terminate()
    server.wait(timeout=10)
    subprocess.run(['tmux', 'kill-session', '-t', 'installer-fixture'], check=True)

print(json.dumps({'freshInstall': True, 'repeatInstallPreservesTokenAndHistory': True,
                  'unrelatedMcpAndJsoncCommentsPreserved': True, 'privateFileModesCorrect': True,
                  'systemDirectoryOwnershipPreserved': True, 'systemdUnitValid': True,
                  'installedFileHashesVerified': len(record['files']), 'mcpToolsAndDesignSystemReady': True,
                  'authenticatedBackendAndWebsocketReady': True, 'websocketsVersion': websockets.__version__, 'versions': versions}, indent=2))
