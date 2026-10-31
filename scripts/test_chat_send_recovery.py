#!/usr/bin/env python3
"""Exercise delivery receipts through real WebSockets and an isolated fake-agent PTY.

Never sends to a user agent, model, phone, or production backend. All mutations
use a fresh named TMUX socket, temporary HOME and temporary backend databases.
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
import sqlite3
import subprocess
import tempfile
import time
import uuid

import websockets
from test_chat_bindings import FAKE_AGENT, free_port, receive, texts, watch


def production_panes():
    result = subprocess.run(['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}'],
                            capture_output=True, text=True)
    return set(result.stdout.splitlines()) if result.returncode == 0 else set()


async def result_for(connection, request, complete=True):
    deadline = asyncio.get_running_loop().time() + 12
    while True:
        await connection.send(json.dumps({'type': 'get-chat-send-result',
            'bindingId': request['bindingId'], 'requestId': request['requestId']}))
        packet = await receive(connection, lambda value: value.get('type') == 'chat-send-result'
            and value.get('requestId') == request['requestId'], timeout=5)
        if not complete or packet.get('status') != 'pending':
            return packet
        assert asyncio.get_running_loop().time() < deadline, 'Receipt remained pending'
        await asyncio.sleep(.05)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    binary = args.binary.resolve()
    assert binary.is_file()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True); output.chmod(0o700)
    initial_panes = production_panes()
    socket_name = 'agentshell-send-recovery-' + uuid.uuid4().hex
    token = secrets.token_urlsafe(32)
    backend = None
    checks = {}
    with tempfile.TemporaryDirectory(prefix='owned-fixture-', dir=output) as temporary:
        root = Path(temporary)
        project = root/'project'; project.mkdir()
        instance = root/'backend'; instance.mkdir()
        script = root/'fake-agent.py'; script.write_text(FAKE_AGENT)
        identifier = str(uuid.uuid4())
        transcript = project/'.codex/sessions'/('rollout-' + identifier + '.jsonl')
        log = (root/'backend.log').open('wb')

        def tmux(*arguments):
            return subprocess.check_output(['tmux', '-L', socket_name, *arguments], text=True).strip()

        def submissions(text):
            messages = []
            for line in transcript.read_text().splitlines():
                try: event = json.loads(line)
                except ValueError: continue
                payload = event.get('payload', {})
                if payload.get('type') == 'user_message': messages.append(payload.get('message'))
            return messages.count(text)

        def persisted(binding, text):
            with sqlite3.connect((instance/'chat_events.db').as_uri()+'?mode=ro', uri=True) as database:
                rows = database.execute('SELECT message_json FROM chat_events WHERE session_name=? AND source=?',
                                        (binding['conversationKey'], 'webhook'))
                return sum(1 for row in rows if json.loads(row[0]).get('role') == 'user'
                    and any(block.get('type') == 'text' and block.get('text') == text
                            for block in json.loads(row[0]).get('blocks', [])))

        async def exactly_once(binding, text):
            deadline = asyncio.get_running_loop().time() + 5
            while submissions(text) < 1:
                assert asyncio.get_running_loop().time() < deadline, 'Fake PTY did not receive input'
                await asyncio.sleep(.05)
            await asyncio.sleep(.4)
            assert submissions(text) == 1, 'Duplicate terminal submission'
            assert persisted(binding, text) == 1, 'Duplicate persisted user event'

        try:
            command = 'python3 ' + shlex.quote(str(script)) + ' ' + shlex.quote(str(project)) + ' A ' + identifier
            tmux('new-session', '-d', '-s', 'receipt-fixture', '-c', str(project), command)
            deadline = time.monotonic() + 5
            while not (project/'A.ready').exists():
                assert time.monotonic() < deadline, 'Fake PTY failed to start'
                time.sleep(.01)
            pane_pid = tmux('display-message', '-p', '-t', 'receipt-fixture', '#{pane_pid}')
            port, tls_port = free_port(), free_port()
            while tls_port == port: tls_port = free_port()
            context = tmux('display-message', '-p', '-t', 'receipt-fixture', '#{socket_path},#{pid},0')
            env = dict(os.environ, AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(port),
                AGENTSHELL_HTTPS_PORT=str(tls_port), RUST_LOG='warn', TMUX=context,
                TOKIO_WORKER_THREADS='2', HOME=str(project), XDG_CONFIG_HOME=str(root/'config'),
                AGENTSHELL_BROWSER_CONFIG=str(root/'unconfigured-browser.json'),
                AGENTSHELL_TLS_CERT_PATH=str(root/'no-fixture-tls.pem'),
                AGENTSHELL_TLS_KEY_PATH=str(root/'no-fixture-tls.key'))

            def start():
                process = subprocess.Popen([str(binary)], cwd=instance, env=env,
                    stdout=log, stderr=log, stdin=subprocess.DEVNULL)
                deadline = time.monotonic() + 10
                while True:
                    assert process.poll() is None, 'Owned backend exited; inspect backend.log'
                    try:
                        with socket.create_connection(('127.0.0.1', port), timeout=.2): return process
                    except OSError:
                        assert time.monotonic() < deadline, 'Owned backend failed to start'
                        time.sleep(.05)

            def connection():
                return websockets.connect(f'ws://127.0.0.1:{port}/ws',
                    additional_headers={'X-Auth-Token': token}, max_size=16*1024*1024,
                    open_timeout=5, close_timeout=2)

            backend = start()
            async def verify():
                async with connection() as original:
                    binding, _ = await watch(original, 'receipt-fixture')
                    request = {'type': 'send-bound-chat-message', 'bindingId': binding['bindingId'],
                        'requestId': uuid.uuid4().hex, 'sessionName': 'receipt-fixture',
                        'windowIndex': 0, 'message': 'Synthetic message with an unread acknowledgement'}
                    await original.send(json.dumps(request))
                    await receive(original, lambda value: value.get('type') == 'chat-event'
                        and value.get('source') == 'webhook' and request['message'] in texts(value))
                    # Close without consuming chat-send-result. The next client
                    # must recover authoritative state, never repeat PTY input.
                async with connection() as reconnected:
                    receipt = await result_for(reconnected, request)
                    assert receipt.get('status') == 'complete' and receipt.get('success') is True, receipt
                    checks['unread_ack_recovered_without_reopening_chat'] = True
                    await exactly_once(binding, request['message'])

                async def replay():
                    async with connection() as retry:
                        await retry.send(json.dumps(request))
                        receipt = await receive(retry, lambda value: value.get('type') == 'chat-send-result'
                            and value.get('requestId') == request['requestId'])
                        assert receipt.get('success') is True, receipt
                await asyncio.gather(*(replay() for _ in range(6)))
                await exactly_once(binding, request['message'])
                checks['parallel_replays_never_repeat_terminal_input'] = True

                race = dict(request, requestId=uuid.uuid4().hex, message='Synthetic concurrent reservation race')
                barrier = asyncio.Event()
                ready = 0
                async def racer():
                    nonlocal ready
                    async with connection() as contender:
                        current, _ = await watch(contender, 'receipt-fixture')
                        assert current['bindingId'] == binding['bindingId']
                        ready += 1
                        if ready == 6: barrier.set()
                        await asyncio.wait_for(barrier.wait(), 10)
                        await contender.send(json.dumps(race))
                        receipt = await receive(contender, lambda value: value.get('type') == 'chat-send-result'
                            and value.get('requestId') == race['requestId'])
                        if receipt.get('status') == 'pending': receipt = await result_for(contender, race)
                        assert receipt.get('success') is True, receipt
                await asyncio.gather(*(racer() for _ in range(6)))
                await exactly_once(binding, race['message'])
                checks['concurrent_first_requests_only_submit_once'] = True

                async with connection() as client:
                    await client.send(json.dumps(dict(request, message='Altered payload must never enter the PTY')))
                    rejected = await receive(client, lambda value: value.get('type') == 'chat-send-result'
                        and value.get('requestId') == request['requestId'])
                    assert rejected.get('status') == 'complete' and rejected.get('success') is False
                    assert (await result_for(client, request)).get('success') is True
                    unknown = await result_for(client, dict(request, requestId=uuid.uuid4().hex), complete=False)
                    assert unknown.get('status') == 'unknown' and 'success' not in unknown, unknown
                assert submissions('Altered payload must never enter the PTY') == 0
                checks['altered_payload_rejected_without_overwriting_original_receipt'] = True
                checks['missing_receipt_is_uncertain_not_false_rejection'] = True

                async with connection() as client:
                    await watch(client, 'receipt-fixture')
                    bad_file = {'type':'send-bound-file-to-chat', 'bindingId':binding['bindingId'],
                        'requestId':uuid.uuid4().hex, 'sessionName':'receipt-fixture','windowIndex':0,
                        'file':{'filename':'synthetic-invalid.txt','mimeType':'text/plain','data':'not!base64'},
                        'prompt':'Synthetic invalid attachment must not submit'}
                    await client.send(json.dumps(bad_file))
                    rejected = await receive(client, lambda value: value.get('type') == 'chat-send-result'
                        and value.get('requestId') == bad_file['requestId'])
                    assert rejected.get('status') == 'complete' and rejected.get('success') is False, rejected
                assert submissions(bad_file['prompt']) == 0
                checks['attachment_save_failure_is_not_acknowledged_as_delivered'] = True
                return binding, request

            binding, request = asyncio.run(verify())
            backend.terminate(); backend.wait(timeout=8); backend = start()
            async def after_restart():
                async with connection() as client:
                    assert (await result_for(client, request)).get('success') is True
                    await client.send(json.dumps(request))
                    receipt = await receive(client, lambda value: value.get('type') == 'chat-send-result'
                        and value.get('requestId') == request['requestId'])
                    assert receipt.get('success') is True, receipt
                await exactly_once(binding, request['message'])
            asyncio.run(after_restart())
            checks['backend_restart_preserves_receipt_and_prevents_replay'] = True
            assert tmux('display-message', '-p', '-t', 'receipt-fixture', '#{pane_pid}') == pane_pid
            checks['same_owned_fake_agent_survives_reconnect_and_restart'] = True
        finally:
            if backend is not None:
                backend.terminate()
                try: backend.wait(timeout=8)
                except subprocess.TimeoutExpired: backend.kill(); backend.wait(timeout=5)
            log.close()
            shutil.copyfile(root/'backend.log', output/'backend.log')
            subprocess.run(['tmux', '-L', socket_name, 'kill-server'], capture_output=True)
    assert initial_panes.issubset(production_panes()), 'A pre-existing production pane changed'
    checks['production_panes_untouched_owned_fixture_stopped'] = True
    (output/'results.json').write_text(json.dumps(checks, indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'checks':checks,'output':str(output)}, indent=2))


if __name__ == '__main__': main()
