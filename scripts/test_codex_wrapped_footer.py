#!/usr/bin/env python3
"""Opt-in real-Codex regression for wrapped native status lines in split view.

Uses isolated TMUX/backend/app-server instances and small model calls in owned
fixture threads. Never resizes or sends input to production terminals.
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

from test_codex_goal_binding import state_until
from test_codex_native_input import Fixture, native_texts


class WrappedFooterFixture(Fixture):
    def __init__(self, root, evidence):
        super().__init__(root, evidence, status_line=[
            'session-id', 'model-with-reasoning', 'current-dir', 'context-remaining',
        ])

    def capture(self, name):
        frame = super().capture(name)
        rows = frame['screen'].splitlines()
        cursor = int(frame['header'].split()[0])
        start = len(rows) - 2
        while start > cursor + 2 and rows[start - 1].strip():
            start -= 1
        frame['footerRows'] = rows[start:-1]
        tokens = rows[start].split() if start > cursor + 1 else []
        frame['id'] = tokens[0] if tokens else ''
        return frame

    async def resize(self, name, width, height=37):
        self.tmux('resize-window', '-t', name, '-x', str(width), '-y', str(height))
        deadline = time.monotonic() + 8
        while True:
            frame = self.capture(name)
            count = len(frame['footerRows'])
            if frame['id'] == self.threads[name] and (count == 1 if width == 240 else count > 1):
                return frame
            assert time.monotonic() < deadline, ('Unexpected native footer', width, frame)
            await asyncio.sleep(.1)


async def run(args):
    inventory_command = ['tmux', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_pid}']
    inventory = subprocess.check_output(inventory_command, text=True)
    with tempfile.TemporaryDirectory(prefix='agentshell-wrapped-footer-') as temporary:
        fixture = WrappedFooterFixture(Path(temporary), args.evidence_dir)
        report = {}
        try:
            await fixture.launch()
            await fixture.seed()
            await fixture.start_backend(args.before_binary or args.binary)
            leases = {}
            for name in ['native-A', 'native-B']:
                await fixture.resize(name, 240)
                connection, leases[name] = await fixture.watch(name)
                await connection.close()
                # Match the phone's narrow split view with a persisted goal.
                await fixture.rpc('thread/goal/set', {
                    'threadId': fixture.threads[name], 'objective': 'Owned wrapped footer fixture',
                    'status': 'complete', 'origin': 'user',
                })
                frame = await fixture.resize(name, 41)
                if args.evidence_dir:
                    (args.evidence_dir / (name + '-footer.json')).write_text(json.dumps({
                        'header': frame['header'], 'footerRows': frame['footerRows'],
                        'hint': frame['hint'], 'expectedThread': fixture.threads[name],
                    }, indent=2))
                if args.before_binary:
                    async with websockets.connect(f'ws://127.0.0.1:{fixture.port}/ws?token={fixture.token}') as connection:
                        await connection.send(json.dumps({'type': 'watch-chat-log', 'sessionName': name, 'windowIndex': 0}))
                        await state_until(connection, 'required')
            if args.before_binary:
                report['beforeWrappedFootersLoseBinding'] = True
                print('BEFORE: both existing chats lose their bindings at 41 columns.', flush=True)

            await fixture.start_backend(args.binary)
            for name in ['native-A', 'native-B']:
                connection, binding = await fixture.watch(name)
                assert binding['bindingId'] == leases[name]['bindingId']
                prompt = f'Reply only {name}-wrapped-footer-ok. Do not use tools.'
                await fixture.send(connection, name, binding, prompt, True)
                await fixture.chat_reply(connection, binding, f'{name}-wrapped-footer-ok')
                await fixture.wait_reply(name, f'{name}-wrapped-footer-ok')
                assert native_texts(fixture.threads[name], 'user').count(prompt) == 1
                other = 'native-B' if name == 'native-A' else 'native-A'
                assert prompt not in native_texts(fixture.threads[other], 'user')
                await connection.close()
            report['automaticRecoveryAndLiveIsolatedReplies'] = True
            print('AFTER: both chats recover their original leases and deliver live replies exactly once.', flush=True)

            await fixture.start_backend(args.binary)
            for name in ['native-A', 'native-B']:
                connection, binding = await fixture.watch(name)
                assert binding['bindingId'] == leases[name]['bindingId']
                await fixture.chat_reply(connection, binding, f'{name}-wrapped-footer-ok')
                await connection.close()
            report['restartRestoresTheSameHistoryAndLease'] = True

            for status, width in [('paused', 47), ('complete', 87), ('complete', 240), ('complete', 41)]:
                await fixture.rpc('thread/goal/set', {
                    'threadId': fixture.threads['native-A'], 'status': status, 'origin': 'user',
                })
                await fixture.resize('native-A', width)
                connection, binding = await fixture.watch('native-A')
                assert binding['bindingId'] == leases['native-A']['bindingId']
                await connection.close()
            report['wrappedAndSingleRowGoalTransitionsKeepTheLease'] = True

            # A keyboard plus terminal/chat split leaves only 16 terminal rows.
            # Expanding the composer must not hide or invalidate native identity
            # between bracketed paste and the guarded Enter.
            await fixture.resize('native-A', 47, 16)
            connection, binding = await fixture.watch('native-A')
            prompt = ('Reply only native-A-small-split-ok. Do not use tools.\n'
                      + 'Revisão • ação 🧪: native composer expansion.\n' * 6)
            await fixture.send(connection, 'native-A', binding, prompt, True)
            await fixture.chat_reply(connection, binding, 'native-A-small-split-ok')
            await fixture.wait_reply('native-A', 'native-A-small-split-ok')
            assert native_texts(fixture.threads['native-A'], 'user').count(prompt.rstrip('\n')) == 1
            frame = fixture.capture('native-A')
            assert frame['header'].split()[1:] == ['47', '16'], frame['header']
            assert frame['id'] == fixture.threads['native-A']
            assert fixture.tmux('show-options', '-wv', '-t', 'native-A', 'window-size') == 'manual'
            await connection.close()
            report['smallSplitMultilineComposerSend'] = True

            connection, previous = await fixture.watch('native-A')
            original = fixture.threads['native-A']
            fixture.tmux('send-keys', '-t', 'native-A', '-l', '/new')
            await asyncio.sleep(.2)
            fixture.tmux('send-keys', '-t', 'native-A', 'Enter')
            deadline = time.monotonic() + 8
            while True:
                frame = fixture.capture('native-A')
                if re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', frame['id']) and frame['id'] != original:
                    break
                assert time.monotonic() < deadline, 'Native /new did not change conversations'
                await asyncio.sleep(.1)
            fixture.threads['previous-native-A'] = original
            fixture.threads['native-A'] = frame['id']
            await state_until(connection, 'bound', frame['id'])
            stale = 'DO_NOT_SUBMIT_OLD_WRAPPED_FOOTER_LEASE'
            await fixture.send(connection, 'native-A', previous, stale, False)
            assert stale not in fixture.capture('native-A')['screen']
            assert stale not in native_texts(original, 'user')
            assert stale not in native_texts(frame['id'], 'user')
            await connection.close()
            report['sameProcessThreadSwitchRejectsStaleSend'] = True
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
        print('Owned fixture cleaned up; production terminal identities unchanged.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--before-binary', type=Path)
    parser.add_argument('--evidence-dir', type=Path)
    asyncio.run(run(parser.parse_args()))
