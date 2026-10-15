#!/usr/bin/env python3
"""Opt-in real-Codex regression for goal banners and truncated terminal IDs.

Uses the owned TMUX/backend/app-server fixture from test_codex_native_input.
Makes small model calls in test threads using the existing Codex login. All goal
updates, terminal input, and deletion are confined to that fixture's threads.
"""

import argparse
import asyncio
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

import websockets

from test_codex_native_input import Fixture, native_texts


async def state_until(connection, status, conversation=None):
    deadline = time.monotonic() + 12
    while True:
        value = json.loads(await asyncio.wait_for(connection.recv(), deadline - time.monotonic()))
        if value.get('type') == 'chat-binding':
            state = value['state']
            if state['status'] == status and (conversation is None or
                    (state.get('binding') or {}).get('conversationId') == conversation):
                return state


async def run(args):
    inventory_command = ['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}']
    inventory = subprocess.check_output(inventory_command, text=True)
    with tempfile.TemporaryDirectory(prefix='agentshell-goal-binding-') as temporary:
        fixture = Fixture(Path(temporary), args.evidence_dir)
        report = {}
        try:
            await fixture.launch()
            # Bind before the goal/footer transition, exactly as an existing
            # production conversation would be linked before it stops working.
            await fixture.start_backend(args.before_binary or args.binary)
            connections, leases = {}, {}
            for name in ['native-A', 'native-B']:
                connections[name], leases[name] = await fixture.watch(name)
            await fixture.seed()
            for name in ['native-A', 'native-B']:
                await fixture.rpc('thread/goal/set', {
                    'threadId': fixture.threads[name], 'objective': 'Owned footer-rendering fixture',
                    'status': 'complete', 'origin': 'user',
                })
                fixture.tmux('resize-window', '-t', name, '-x', '47', '-y', '43')
            for name in ['native-A', 'native-B']:
                deadline = time.monotonic() + 8
                while True:
                    frame = fixture.capture(name)
                    if '…' in frame['id'] and 'Goal achieved' in frame['id']:
                        break
                    assert time.monotonic() < deadline, 'No real truncated goal footer: ' + name
                    await asyncio.sleep(.1)
                if args.evidence_dir:
                    (args.evidence_dir / (name + '-footer.json')).write_text(json.dumps({
                        'header': frame['header'], 'footer': frame['id'], 'hint': frame['hint'],
                        'expectedThread': fixture.threads[name],
                    }, indent=2))
            for connection in connections.values():
                await connection.close()
            if args.before_binary:
                for name in ['native-A', 'native-B']:
                    # A fresh watch proves the current goal footer fails, rather
                    # than consuming a transient older status queued during seed.
                    async with websockets.connect(f'ws://127.0.0.1:{fixture.port}/ws?token={fixture.token}') as connection:
                        await connection.send(json.dumps({'type': 'watch-chat-log', 'sessionName': name, 'windowIndex': 0}))
                        await state_until(connection, 'required')
                report['beforeExistingChatsLostBinding'] = True
                print('BEFORE: both existing same-folder chats lost binding when the goal banner truncated their IDs.', flush=True)
            await fixture.start_backend(args.binary)
            for name in ['native-A', 'native-B']:
                connection, current = await fixture.watch(name)
                assert current['bindingId'] == leases[name]['bindingId'], 'Existing lease unexpectedly changed'
                await connection.close()
            report['automaticRecoveryWithoutTerminalInput'] = True
            print('AFTER: both exact conversations recovered automatically, with their original leases.', flush=True)

            # Recovery must also work after restarting the backend at the narrow
            # width, without relying on the process-local activity cache.
            await fixture.start_backend(args.binary)
            for name in ['native-A', 'native-B']:
                connection, binding = await fixture.watch(name)
                assert binding['bindingId'] == leases[name]['bindingId']
                prompt = f'Reply only {name}-goal-footer-ok. Do not use tools.'
                await fixture.send(connection, name, binding, prompt, True)
                await fixture.chat_reply(connection, binding, f'{name}-goal-footer-ok')
                await fixture.wait_reply(name, f'{name}-goal-footer-ok')
                assert native_texts(fixture.threads[name], 'user').count(prompt) == 1
                other = 'native-B' if name == 'native-A' else 'native-A'
                assert prompt not in native_texts(fixture.threads[other], 'user')
                await connection.close()
            report['backendRestartAndExactlyOnceIsolatedSends'] = True

            # Exercise another non-active goal state and a return to the wide
            # viewport; the same ID and lease must survive each layout.
            for status, width in [('paused', 47), ('complete', 87), ('complete', 47)]:
                await fixture.rpc('thread/goal/set', {'threadId': fixture.threads['native-A'],
                                  'status': status, 'origin': 'user'})
                fixture.tmux('resize-window', '-t', 'native-A', '-x', str(width), '-y', '43')
                await asyncio.sleep(.3)
                connection, binding = await fixture.watch('native-A')
                assert binding['bindingId'] == leases['native-A']['bindingId']
                await connection.close()
            report['goalAndResizeTransitionsKeepTheLease'] = True

            # /new changes threads in the *same* native process. Prefix recovery
            # must not turn the previous cache into authorization to send there.
            connection, previous = await fixture.watch('native-A')
            original_id = fixture.threads['native-A']
            fixture.tmux('send-keys', '-t', 'native-A', '-l', '/new')
            await asyncio.sleep(.2)  # Let Codex's paste detector finish before Enter.
            fixture.tmux('send-keys', '-t', 'native-A', 'Enter')
            deadline = time.monotonic() + 8
            while True:
                frame = fixture.capture('native-A')
                if re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', frame['id']) and frame['id'] != original_id:
                    break
                assert time.monotonic() < deadline, 'Native /new did not switch threads'
                await asyncio.sleep(.1)
            fixture.threads['previous-native-A'] = original_id
            fixture.threads['native-A'] = frame['id']
            await state_until(connection, 'bound', frame['id'])
            stale_prompt = 'DO_NOT_SUBMIT_OLD_CONVERSATION_LEASE'
            await fixture.send(connection, 'native-A', previous, stale_prompt, False)
            assert stale_prompt not in fixture.capture('native-A')['screen']
            assert stale_prompt not in native_texts(original_id, 'user')
            assert stale_prompt not in native_texts(frame['id'], 'user')
            await connection.close()
            report['sameProcessConversationSwitchRejectsStaleSend'] = True
            print(json.dumps(report), flush=True)
            if args.evidence_dir:
                (args.evidence_dir / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        except Exception:
            if args.evidence_dir:
                for name in ['native-A', 'native-B']:
                    try:
                        (args.evidence_dir / (name + '-failure.json')).write_text(json.dumps(fixture.capture(name), indent=2))
                    except subprocess.CalledProcessError:
                        pass
            raise
        finally:
            await fixture.cleanup()
        assert inventory == subprocess.check_output(inventory_command, text=True)
        print('Owned fixture conversations removed; production terminals untouched.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--before-binary', type=Path)
    parser.add_argument('--evidence-dir', type=Path)
    asyncio.run(run(parser.parse_args()))
