#!/usr/bin/env python3
"""End-to-end identity regression using isolated TMUX, fake agents and a temporary backend.

No model calls, real terminal input, production databases, or user settings are touched.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import tempfile
import time
import uuid
import websockets

FAKE_AGENT = r'''import ctypes,json,os,select,sys,tty
from pathlib import Path
from datetime import datetime,timezone
ctypes.CDLL(None).prctl(15,b'codex',0,0,0)
directory=Path(sys.argv[1]); label=sys.argv[2]; identifier=sys.argv[3]
control=directory/(label+'.control'); ready=directory/(label+'.ready')
file=None
def event(kind,text):
    value={'timestamp':datetime.now(timezone.utc).isoformat(),'type':'event_msg','payload':{'type':kind,'message':text}}
    file.write(json.dumps(value)+'\n');file.flush()
def resume(identifier):
    global file
    if file:file.close()
    path=directory/'.codex/sessions'/('rollout-'+identifier+'.jsonl')
    path.parent.mkdir(parents=True,exist_ok=True)
    new=not path.exists()
    file=path.open('a+')
    if new:
        file.write(json.dumps({'type':'session_meta','payload':{'id':identifier,'cwd':str(directory),'source':'cli'}})+'\n')
        file.flush()
        event('agent_message',label+' history '+identifier)
    (directory/(label+'.active')).write_text(identifier)
resume(identifier)
if '--subagent' in sys.argv[4:]:
    child_id='subagent-'+label+'-fixture'
    child_path=directory/'.codex/sessions'/('rollout-'+child_id+'.jsonl')
    child_log=child_path.open('a+')
    child_log.write(json.dumps({'type':'session_meta','payload':{'id':child_id,'cwd':str(directory),'source':{'subagent':{'thread_spawn':{'parent_thread_id':identifier}}}}})+'\n');child_log.flush()
tty.setraw(0)
sys.stdout.write('\x1b[?2004h');sys.stdout.flush();ready.write_text('ready')
draft=b'';escape=b'';paste=False
while True:
    if control.exists():
        next_id=control.read_text().strip();control.unlink();resume(next_id)
    if not select.select([0],[],[],.05)[0]:continue
    char=os.read(0,1)
    if escape or char==b'\x1b':
        escape+=char
        if escape==b'\x1b[200~':paste=True;escape=b'';continue
        if escape==b'\x1b[201~':paste=False;escape=b'';continue
        if len(escape)<6:continue
        draft+=escape;escape=b'';continue
    if char==b'\r' and not paste:
        text=draft.decode();draft=b''
        event('user_message',text);event('agent_message',label+' reply: '+text)
    else:draft+=char
'''


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


async def receive(connection, predicate, timeout=10):
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        message = json.loads(await asyncio.wait_for(connection.recv(), deadline - asyncio.get_running_loop().time()))
        if predicate(message):
            return message


def texts(message):
    return [block.get('text', '') for entry in message.get('messages', [message.get('message', {})]) for block in entry.get('blocks', []) if block.get('type') == 'text']


async def watch(connection, name):
    await connection.send(json.dumps({'type': 'watch-chat-log', 'sessionName': name, 'windowIndex': 0, 'limit': 30}))
    packet = await receive(connection, lambda m: m.get('type') == 'chat-binding' and m.get('sessionName') == name and m['state'].get('status') == 'bound')
    history = await receive(connection, lambda m: m.get('type') == 'chat-history' and m.get('sessionName') == name and m.get('bindingId') == packet['state']['binding']['bindingId'])
    return packet['state']['binding'], history


async def send(connection, name, binding, text, success=True, observed=None):
    request = uuid.uuid4().hex
    await connection.send(json.dumps({'type': 'send-bound-chat-message', 'sessionName': name, 'windowIndex': 0,
        'bindingId': binding['bindingId'], 'requestId': request, 'message': text}))
    # The live reply can arrive before the send receipt. Accumulate both without dropping a packet.
    receipt = None
    reply = None
    deadline = asyncio.get_running_loop().time() + 10
    while receipt is None or (success and reply is None):
        message = json.loads(await asyncio.wait_for(connection.recv(), deadline - asyncio.get_running_loop().time()))
        if observed is not None:
            observed.append(message)
        if message.get('type') == 'chat-send-result' and message.get('requestId') == request:
            receipt = message
            assert receipt['success'] == success, receipt.get('error')
        if message.get('type') == 'chat-event' and any('reply: '+text in t for t in texts(message)):
            reply = message
            assert message['bindingId'] == binding['bindingId']
            assert message['conversationKey'] == binding['conversationKey']
    return reply


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--stress-rounds', type=int, default=12)
    args = parser.parse_args()
    inventory = subprocess.check_output(['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}'], text=True)
    token = secrets.token_urlsafe(32)
    socket_name = 'agentshell-binding-test-' + uuid.uuid4().hex
    identifiers = [str(uuid.uuid4()) for _ in range(3)]
    backend = None
    with tempfile.TemporaryDirectory(prefix='agentshell-binding-e2e-') as temporary:
        root = Path(temporary)
        project = root/'shared-project'; project.mkdir()
        script = root/'fake-agent.py'; script.write_text(FAKE_AGENT)
        def tmux(*arguments):
            return subprocess.check_output(['tmux', '-L', socket_name, *arguments], text=True).strip()
        try:
            for label, identifier in zip(['A', 'B'], identifiers):
                command = 'python3 ' + shlex.quote(str(script)) + ' ' + shlex.quote(str(project)) + ' ' + label + ' ' + identifier
                tmux('new-session', '-d', '-s', 'terminal-'+label, '-c', str(project), command)
            deadline = time.monotonic()+5
            while not all((project/(label+'.ready')).exists() for label in ['A', 'B']):
                assert time.monotonic() < deadline, 'fake agents failed to start'
                time.sleep(.01)
            http_port = free_port()
            instance = root/'backend'; instance.mkdir()
            context = tmux('display-message', '-p', '-t', 'terminal-A', '#{socket_path},#{pid},0')
            env = dict(os.environ, AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(http_port), AGENTSHELL_HTTPS_PORT=str(free_port()), RUST_LOG='error', TMUX=context, TOKIO_WORKER_THREADS='2')
            log = (root/'backend.log').open('w')
            def start():
                process = subprocess.Popen([str(args.binary.resolve())], cwd=instance, env=env, stdout=log, stderr=log)
                deadline = time.monotonic()+10
                while True:
                    assert process.poll() is None, 'temporary backend exited'
                    try:
                        with socket.create_connection(('127.0.0.1', http_port), timeout=.2):break
                    except OSError:
                        assert time.monotonic() < deadline, 'temporary backend did not start'
                        time.sleep(.05)
                return process
            backend = start()
            async def verify():
                async with websockets.connect(f'ws://127.0.0.1:{http_port}/ws?token={token}', max_size=16*1024*1024) as a, websockets.connect(f'ws://127.0.0.1:{http_port}/ws?token={token}', max_size=16*1024*1024) as b:
                    first, ahistory = await watch(a, 'terminal-A')
                    second, bhistory = await watch(b, 'terminal-B')
                    assert first['conversationId'] == identifiers[0]
                    assert second['conversationId'] == identifiers[1]
                    assert all(not t.startswith('B ') for t in texts(ahistory))
                    assert all(not t.startswith('A ') for t in texts(bhistory))
                    ar, br = await asyncio.gather(send(a, 'terminal-A', first, 'Olá from A'), send(b, 'terminal-B', second, 'Different message from B'))
                    assert any(t == 'A reply: Olá from A' for t in texts(ar))
                    assert any(t == 'B reply: Different message from B' for t in texts(br))
                    # Resume another independent conversation in the same process/pane/directory.
                    (project/'A.control').write_text(identifiers[2])
                    changed = await receive(a, lambda m: m.get('type') == 'chat-binding' and (m['state'].get('binding') or {}).get('conversationId') == identifiers[2])
                    resumed = changed['state']['binding']
                    history = await receive(a, lambda m: m.get('type') == 'chat-history' and m.get('bindingId') == resumed['bindingId'])
                    assert all(not t.startswith('B ') for t in texts(history))
                    await send(a, 'terminal-A', first, 'must-not-send', success=False)
                    assert 'must-not-send' not in (project/'.codex/sessions'/('rollout-'+identifiers[2]+'.jsonl')).read_text()
                    # A renamed terminal keeps its link; input is directed to the exact pane, not an alias fallback.
                    tmux('rename-session', '-t', 'terminal-A', 'renamed-A')
                    await send(a, 'terminal-A', resumed, 'after rename')
                    # Clearing A must leave B's history and messages intact.
                    await a.send(json.dumps({'type': 'clear-bound-chat-log', 'sessionName': 'terminal-A', 'windowIndex': 0, 'bindingId': resumed['bindingId']}))
                    cleared = await receive(a, lambda m: m.get('type') == 'chat-log-cleared')
                    assert cleared['success'] is True
                    _, intact = await watch(b, 'terminal-B')
                    assert any(t == 'B reply: Different message from B' for t in texts(intact))
                    return resumed['bindingId'], second['bindingId']
            ids = asyncio.run(verify())
            backend.terminate(); backend.wait(timeout=5)
            backend = start()
            async def reconnect():
                async with websockets.connect(f'ws://127.0.0.1:{http_port}/ws?token={token}', max_size=16*1024*1024) as a, websockets.connect(f'ws://127.0.0.1:{http_port}/ws?token={token}', max_size=16*1024*1024) as b:
                    first, _ = await watch(a, 'renamed-A')
                    second, history = await watch(b, 'terminal-B')
                    assert (first['bindingId'], second['bindingId']) == ids
                    assert any(t == 'B reply: Different message from B' for t in texts(history))
                    await send(a, 'renamed-A', first, 'after backend restart')
            asyncio.run(reconnect())
            async def connection_churn():
                # Reconnecting Android clients open stores while transcript
                # watchers close SQLite connections. This exposed the Unix
                # mutex deadlock in the previously bundled SQLite 3.51.1.
                endpoint = f'ws://127.0.0.1:{http_port}/ws?token={token}'
                done = asyncio.Event()
                latencies = []
                async def probe():
                    async with websockets.connect(endpoint, open_timeout=3, close_timeout=2) as connection:
                        while not done.is_set():
                            started = time.monotonic()
                            await connection.send(json.dumps({'type': 'ping'}))
                            await receive(connection, lambda message: message.get('type') == 'pong', timeout=2)
                            latencies.append(time.monotonic()-started)
                            await asyncio.sleep(.05)
                async def client(index):
                    name = 'renamed-A' if index % 2 == 0 else 'terminal-B'
                    expected = ids[0] if index % 2 == 0 else ids[1]
                    for _ in range(args.stress_rounds):
                        async with websockets.connect(endpoint, open_timeout=3, close_timeout=2, max_size=16*1024*1024) as connection:
                            binding, _ = await watch(connection, name)
                            assert binding['bindingId'] == expected
                            for request, response in [('get-favorites', 'favorites-list'), ('get-tags', 'tags-list')]:
                                await connection.send(json.dumps({'type': request}))
                                await receive(connection, lambda message: message.get('type') == response, timeout=3)
                            await asyncio.sleep(.1)
                monitor = asyncio.create_task(probe())
                try:
                    await asyncio.wait_for(asyncio.gather(*(client(index) for index in range(12))), 60)
                finally:
                    done.set()
                    await monitor
                assert latencies, 'responsiveness probe never ran'
                return {'reconnections': 12*args.stress_rounds, 'max_ping_seconds': round(max(latencies), 3)}
            stress = asyncio.run(connection_churn())
            print(json.dumps({'same_directory_isolation': True, 'simultaneous_input_and_replies': True, 'resume_isolation': True,
                'stale_submission_rejected': True, 'rename_preserves_target': True, 'clear_isolation': True, 'backend_restart_preserves_link': True,
                'connection_stress': stress}))
        finally:
            if backend is not None:
                backend.terminate()
                try:backend.wait(timeout=5)
                except subprocess.TimeoutExpired:backend.kill();backend.wait(timeout=5)
            subprocess.run(['tmux', '-L', socket_name, 'kill-server'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    assert subprocess.check_output(['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}'], text=True) == inventory
    print('Production TMUX panes untouched; isolated test processes stopped.')


if __name__ == '__main__':
    main()
