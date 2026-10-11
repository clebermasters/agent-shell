#!/usr/bin/env python3
"""Opt-in native Codex send regression. Uses isolated TMUX/backend/app-server.

Makes small model calls in owned fixture threads using existing Codex login.
Never sends input to production panes. Deletes only the fixture threads.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import socket
import sqlite3
import subprocess
import tempfile
import time
import uuid

import websockets


def free_port():
    with socket.socket() as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def native_texts(identifier, role):
    database = Path.home() / '.codex/state_5.sqlite'
    with sqlite3.connect(f'file:{database}?mode=ro', uri=True) as connection:
        row = connection.execute('SELECT rollout_path FROM threads WHERE id=?', (identifier,)).fetchone()
    if not row or not Path(row[0]).exists():
        return []
    texts = []
    for line in Path(row[0]).read_text().splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        value = item.get('payload', {})
        if item.get('type') == 'response_item' and value.get('type') == 'message' and value.get('role') == role:
            texts.append(''.join(block.get('text', '') for block in value.get('content', [])
                                 if block.get('type') in ['input_text', 'output_text']))
    return texts


class Fixture:
    def __init__(self, root, evidence=None):
        self.root = root
        self.project = root / 'shared-project'
        self.project.mkdir()
        self.storage = root / 'backend-storage'
        self.storage.mkdir()
        self.socket_name = 'agentshell-native-input-' + uuid.uuid4().hex
        self.port = free_port()
        self.token = secrets.token_urlsafe(24)
        self.backend = None
        self.server = None
        self.threads = {}
        self.sequence = 0
        self.evidence = evidence
        if evidence:
            evidence.mkdir(mode=0o700, parents=True, exist_ok=True)

    def tmux(self, *args):
        return subprocess.check_output(['tmux', '-L', self.socket_name, *args], text=True).strip()

    def capture(self, name):
        # Preserve blank final rows, unlike the general tmux helper.
        text = subprocess.check_output(['tmux', '-L', self.socket_name,
                                        'display-message', '-p', '-t', name,
                                        '#{cursor_y} #{pane_width} #{pane_height}', ';',
                                        'capture-pane', '-p', '-t', name], text=True)
        header, screen = text.split('\n', 1)
        rows = screen.splitlines()
        identifier = rows[-2].strip().split(' · ')[0] if len(rows) >= 2 else ''
        return {'header': header, 'screen': screen, 'hint': rows[-1].strip(), 'id': identifier}

    async def rpc(self, method, params):
        self.sequence += 1
        identifier = self.sequence
        await self.api.send(json.dumps({'id': identifier, 'method': method, 'params': params}))
        while True:
            message = json.loads(await asyncio.wait_for(self.api.recv(), 15))
            if message.get('id') == identifier:
                assert 'error' not in message, message.get('error')
                return message['result']

    async def launch(self):
        # Explicitly disable every globally configured MCP; a bare empty map merges.
        config = (Path.home() / '.codex/config.toml').read_text()
        names = re.findall(r'^\[mcp_servers\.([^\.\]]+)\]\s*$', config, re.M)
        overrides = ['--config', 'tui.status_line=["session-id"]', '--config', 'model_reasoning_effort="low"']
        for name in names:
            overrides.extend(['--config', 'mcp_servers.' + name + '.enabled=false'])
        address = 'unix://' + str(self.root / 'server.sock')
        environment = dict(os.environ)
        environment.pop('TMUX', None)
        environment.pop('TMUX_PANE', None)
        self.server = subprocess.Popen(['codex', 'app-server', '--listen', address, *overrides],
                                       cwd=self.project, env=environment,
                                       stdout=(self.root / 'native-server.log').open('w'), stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 15
        while not (self.root / 'server.sock').exists():
            assert self.server.poll() is None and time.monotonic() < deadline, 'Native server failed to start'
            await asyncio.sleep(.05)
        self.api = await websockets.unix_connect(str(self.root / 'server.sock'), max_size=16 * 1024 * 1024)
        await self.rpc('initialize', {'clientInfo': {'name': 'agentshell-native-input-test', 'version': '1'},
                                      'capabilities': {'experimentalApi': True}})
        await self.api.send(json.dumps({'method': 'initialized'}))
        command = shlex.join(['codex', '--remote', address, '--cd', str(self.project), *overrides])
        for name in ['native-A', 'native-B']:
            self.tmux('new-session', '-d', '-s', name, '-x', '87', '-y', '37', '-c', str(self.project), command)
            deadline = time.monotonic() + 20
            while True:
                frame = self.capture(name)
                if 'Trust this folder?' in frame['screen'] and '› 1. Trust and continue' in frame['screen']:
                    # This is the empty directory we created, not a user project.
                    assert self.project.parent == self.root
                    self.tmux('send-keys', '-t', name, 'Enter')
                    await asyncio.sleep(.3)
                    continue
                if re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', frame['id']):
                    self.threads[name] = frame['id']
                    break
                if time.monotonic() >= deadline:
                    if self.evidence:
                        path = self.evidence / ('startup-' + name + '.json')
                        path.write_text(json.dumps(frame))
                        path.chmod(0o600)
                    raise AssertionError('No complete native footer: ' + name)
                await asyncio.sleep(.1)
        assert len(set(self.threads.values())) == 2

    async def wait_reply(self, name, expected):
        deadline = time.monotonic() + 90
        while True:
            if any(expected in text for text in native_texts(self.threads[name], 'assistant')):
                state = await self.rpc('thread/read', {'threadId': self.threads[name], 'includeTurns': False})
                if state['thread']['status']['type'] == 'idle':
                    return
            assert time.monotonic() < deadline, 'Native assistant did not finish: ' + expected
            await asyncio.sleep(.15)

    async def seed(self):
        await self.rpc('turn/start', {'threadId': self.threads['native-A'], 'input': [{'type': 'text', 'text':
            'Do not use tools. Reply with exactly 60 numbered lines, each containing "native-alpha-history". No other text.'}]})
        await self.wait_reply('native-A', 'native-alpha-history')
        await self.rpc('turn/start', {'threadId': self.threads['native-B'], 'input': [{'type': 'text', 'text':
            'Do not use tools. Reply only native-beta-seed-ok.'}]})
        await self.wait_reply('native-B', 'native-beta-seed-ok')
        print('Two independent native fixture histories are ready.', flush=True)

    async def start_backend(self, binary):
        self.stop_backend()
        context = self.tmux('display-message', '-p', '-t', 'native-A', '#{socket_path},#{pid},0')
        environment = dict(os.environ, TMUX=context, AUTH_TOKEN=self.token,
                           AGENTSHELL_HTTP_PORT=str(self.port), AGENTSHELL_HTTPS_PORT=str(free_port()),
                           RUST_LOG='error', TOKIO_WORKER_THREADS='2')
        self.backend = subprocess.Popen([str(binary.resolve())], cwd=self.storage, env=environment,
                                        stdout=(self.root / 'backend.log').open('a'), stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 10
        while True:
            assert self.backend.poll() is None, 'Fixture backend exited'
            try:
                with socket.create_connection(('127.0.0.1', self.port), timeout=.1):
                    return
            except OSError:
                assert time.monotonic() < deadline
                await asyncio.sleep(.05)

    def stop_backend(self):
        if self.backend is not None:
            self.backend.terminate()
            self.backend.wait(timeout=10)
            self.backend = None

    async def watch(self, name):
        connection = await websockets.connect(f'ws://127.0.0.1:{self.port}/ws?token={self.token}', max_size=16 * 1024 * 1024)
        await connection.send(json.dumps({'type': 'watch-chat-log', 'sessionName': name, 'windowIndex': 0, 'limit': 50}))
        deadline = time.monotonic() + 10
        while True:
            message = json.loads(await asyncio.wait_for(connection.recv(), deadline - time.monotonic()))
            if message.get('type') == 'chat-binding':
                assert message['state']['status'] == 'bound', message['state']
                binding = message['state']['binding']
                assert binding['conversationId'] == self.threads[name]
                return connection, binding

    async def send(self, connection, name, binding, text, success):
        request = uuid.uuid4().hex
        await connection.send(json.dumps({'type': 'send-bound-chat-message', 'sessionName': name, 'windowIndex': 0,
                                          'bindingId': binding['bindingId'], 'requestId': request, 'message': text}))
        while True:
            message = json.loads(await asyncio.wait_for(connection.recv(), 15))
            if success and message.get('type') == 'chat-binding':
                assert message['state']['status'] == 'bound', message['state']
            if message.get('type') == 'chat-send-result' and message.get('requestId') == request:
                assert message['success'] == success, message
                return message

    async def chat_reply(self, connection, binding, expected):
        deadline = time.monotonic() + 90
        while True:
            message = json.loads(await asyncio.wait_for(connection.recv(), deadline - time.monotonic()))
            if message.get('type') == 'chat-binding':
                assert message['state']['status'] == 'bound', message['state']
                assert message['state']['binding']['bindingId'] == binding['bindingId']
            entries = message.get('messages', [message.get('message', {})])
            if any(entry.get('role') == 'assistant' and any(
                    expected in block.get('text', '') for block in entry.get('blocks', [])) for entry in entries):
                assert message.get('bindingId') == binding['bindingId']
                assert message.get('conversationKey') == binding['conversationKey']
                return

    async def scroll(self):
        self.tmux('send-keys', '-t', 'native-A', 'PPage')
        deadline = time.monotonic() + 5
        while True:
            frame = self.capture('native-A')
            if frame['hint'] == 'enter/esc latest · ? shortcuts':
                assert frame['id'] == self.threads['native-A']
                return frame
            assert time.monotonic() < deadline, 'Native scrollback hint did not appear'
            await asyncio.sleep(.05)

    async def cleanup(self):
        self.stop_backend()
        try:
            identifiers = set(self.threads.values())
            if hasattr(self, 'api'):
                loaded = await self.rpc('thread/loaded/list', {})
                identifiers.update(loaded['data'])
            subprocess.run(['tmux', '-L', self.socket_name, 'kill-server'], capture_output=True)
            if hasattr(self, 'api'):
                for identifier in identifiers:
                    value = await self.rpc('thread/read', {'threadId': identifier, 'includeTurns': False})
                    assert value['thread']['cwd'] == str(self.project), 'Only delete owned fixture threads'
                    try:
                        await self.rpc('thread/delete', {'threadId': identifier})
                    except AssertionError as error:
                        if 'thread is not persisted and cannot be deleted' not in str(error):
                            raise
                await self.api.close()
        finally:
            subprocess.run(['tmux', '-L', self.socket_name, 'kill-server'], capture_output=True)
            if self.server is not None:
                self.server.terminate()
                self.server.wait(timeout=10)
            # Remove only this newly-created folder's persisted trust entry.
            config = Path.home() / '.codex/config.toml'
            text = config.read_text()
            pattern = r'^\[projects\.' + re.escape(json.dumps(str(self.project))) + r'\][^\n]*\n.*?(?=^\[|\Z)'
            updated = re.sub(pattern, '', text, flags=re.M | re.S)
            if updated != text:
                temporary = config.with_name('.agentshell-native-test-config-' + uuid.uuid4().hex)
                temporary.write_text(updated)
                temporary.chmod(config.stat().st_mode & 0o777)
                os.replace(temporary, config)


async def run(args):
    inventory = subprocess.check_output(['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}'], text=True)
    with tempfile.TemporaryDirectory(prefix='agentshell-native-input-') as temporary:
        fixture = Fixture(Path(temporary), args.evidence_dir)
        report = {}
        try:
            await fixture.launch()
            await fixture.seed()
            prompt = 'Reply only native-alpha-after-scroll-ok. No tools.'
            if args.before_binary:
                await fixture.start_backend(args.before_binary)
                await fixture.scroll()
                connection, binding = await fixture.watch('native-A')
                receipt = await fixture.send(connection, 'native-A', binding, prompt, False)
                await connection.close()
                frame = fixture.capture('native-A')
                assert frame['hint'] == 'esc latest', frame['hint']
                assert prompt not in native_texts(fixture.threads['native-A'], 'user')
                report['before'] = {'sendRejected': True, 'error': receipt.get('error'), 'draftHint': frame['hint']}
                print(json.dumps(report['before']), flush=True)
                fixture.tmux('send-keys', '-t', 'native-A', 'C-u')
                await asyncio.sleep(.2)
            await fixture.start_backend(args.binary)
            await fixture.scroll()
            connection, binding = await fixture.watch('native-A')
            await fixture.send(connection, 'native-A', binding, prompt, True)
            await fixture.chat_reply(connection, binding, 'native-alpha-after-scroll-ok')
            await fixture.wait_reply('native-A', 'native-alpha-after-scroll-ok')
            assert native_texts(fixture.threads['native-A'], 'user').count(prompt) == 1
            await connection.close()
            report['scrollbackSend'] = True
            print('Scrollback paste and Enter succeeded exactly once.', flush=True)
            # A native multiline paste exercises the expanded composer too.
            fixture.tmux('resize-window', '-t', 'native-A', '-x', '47', '-y', '22')
            await asyncio.sleep(.3)
            await fixture.scroll()
            connection, binding = await fixture.watch('native-A')
            multiline = 'Reply only native-alpha-multiline-ok. No tools.\n' + 'Revisão • ação 🧪: local fixture, do not use tools.\n' * 5
            await fixture.send(connection, 'native-A', binding, multiline, True)
            await fixture.chat_reply(connection, binding, 'native-alpha-multiline-ok')
            await fixture.wait_reply('native-A', 'native-alpha-multiline-ok')
            # Codex strips the final newline when submitting its composer.
            assert native_texts(fixture.threads['native-A'], 'user').count(multiline.rstrip('\n')) == 1
            await connection.close()
            report['smallViewportMultilineSend'] = True
            connection, binding = await fixture.watch('native-B')
            beta = 'Reply only native-beta-after-alpha-ok. No tools.'
            await fixture.send(connection, 'native-B', binding, beta, True)
            await fixture.chat_reply(connection, binding, 'native-beta-after-alpha-ok')
            await fixture.wait_reply('native-B', 'native-beta-after-alpha-ok')
            assert native_texts(fixture.threads['native-B'], 'user').count(beta) == 1
            assert prompt not in native_texts(fixture.threads['native-B'], 'user')
            assert multiline.rstrip('\n') not in native_texts(fixture.threads['native-B'], 'user')
            assert beta not in native_texts(fixture.threads['native-A'], 'user')
            await connection.close()
            report['sameFolderIsolation'] = True
            report['chatRepliesDeliveredWithStableBinding'] = True
            if args.evidence_dir:
                (args.evidence_dir / 'result.json').write_text(json.dumps(report, indent=2))
            print(json.dumps(report), flush=True)
        finally:
            await fixture.cleanup()
        assert inventory == subprocess.check_output(['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}'], text=True)
        print('Owned fixture threads deleted; production TMUX panes untouched.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--before-binary', type=Path)
    parser.add_argument('--evidence-dir', type=Path)
    asyncio.run(run(parser.parse_args()))
