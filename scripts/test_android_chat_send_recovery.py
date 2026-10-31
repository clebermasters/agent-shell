#!/usr/bin/env python3
"""Fault-inject chat acknowledgements through the real Android MainActivity.

Only accepts the owned agentshell-ui-poc emulator and a verified -PuiPoc=true
debug APK. A synthetic agent, named TMUX socket, backend, HOME and WebSocket
proxy are isolated. No phone, model, production backend or real agent is used.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

import websockets
from test_chat_bindings import FAKE_AGENT, free_port
from test_chat_send_recovery import production_panes

TOKEN = 'isolated-ui-poc-only'
PROXY_PORT = 40199
SESSION = 'chat-send-fixture'


def verify_apk(apk):
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home()/'android-sdk'))
    analyzer = sdk/'cmdline-tools/latest/bin/apkanalyzer'
    package = subprocess.run([str(analyzer), 'manifest', 'application-id', str(apk)],
        capture_output=True,text=True,check=True,
        env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64'))
    assert package.stdout.strip() == 'com.agentshell.debug'
    result = subprocess.run([str(analyzer), 'dex', 'code', '--class',
        'com.agentshell.core.config.BuildConfig', str(apk)], capture_output=True, text=True,
        check=True, env=dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64'))
    # Inspect the actual configuration fields, rather than incidental fixture
    # strings which also occur in ordinary debug APKs. Never print credentials.
    assert re.search(r'\.field[^\n]* AUTH_TOKEN:[^\n]*= "isolated-ui-poc-only"', result.stdout)
    assert re.search(r'\.field[^\n]* DEFAULT_SERVER_LIST:[^\n]*= "127\.0\.0\.1:40199,Isolated UI POC"', result.stdout)


class AndroidUi:
    def __init__(self, serial, output): self.serial, self.output = serial, output
    async def adb(self, *args, check=True):
        result = await asyncio.to_thread(subprocess.run, ['adb', '-s', self.serial, *args],
            capture_output=True, check=check, timeout=40)
        return result.stdout
    async def nodes(self):
        remote = '/data/local/tmp/agentshell-chat-send-fixture.xml'
        await self.adb('shell', 'uiautomator', 'dump', remote)
        return ET.fromstring(await self.adb('shell', 'cat', remote))
    async def tap(self, node):
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
        await self.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    async def click(self, label):
        root = await self.nodes()
        choices = [node for node in root.iter('node') if node.get('content-desc') == label]
        if not choices:
            choices = [node for node in root.iter('node') if node.get('clickable') == 'true'
                and any(child.get('text') == label for child in node.iter('node'))]
        assert choices, 'Missing UI control: ' + label
        def area(node):
            x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
            return (x2-x1)*(y2-y1)
        await self.tap(min(choices,key=area))
    async def field(self):
        root = await self.nodes()
        assert any(node.get('text') == SESSION for node in root.iter('node')), 'Left the fixture chat'
        fields = [node for node in root.iter('node') if node.get('class') == 'android.widget.EditText']
        assert len(fields) == 1, 'Expected the single chat composer'
        return fields[0]
    async def linked(self, identifier):
        deadline = asyncio.get_running_loop().time()+30
        while True:
            root = await self.nodes()
            if any(node.get('text') == 'Codex · '+identifier[:8] for node in root.iter('node')): return
            assert asyncio.get_running_loop().time() < deadline, 'Fixture conversation never linked'
            await asyncio.sleep(.3)
    async def type_draft(self, text):
        assert re.fullmatch('[A-Za-z0-9]+', text), 'Only synthetic plain ASCII fixture text is accepted'
        field = await self.field(); assert field.get('enabled') == 'true', 'Composer is not editable'
        await self.tap(field)
        await self.adb('shell','input','keycombination','KEYCODE_CTRL_LEFT','KEYCODE_A')
        await self.adb('shell','input','keyevent','KEYCODE_DEL')
        await self.adb('shell','input','text',text)
        field = await self.field(); assert field.get('text') == text, field.get('text')
    async def edit_draft(self, suffix):
        assert re.fullmatch('[A-Za-z0-9]+', suffix)
        field = await self.field(); before = field.get('text')
        assert field.get('enabled') == 'true' and before
        await self.tap(field)
        # Editing is the behavior under test; do not depend on an IME's
        # hardware Ctrl+A shortcut to replace the entire existing draft.
        await self.adb('shell','input','text',suffix)
        after = (await self.field()).get('text')
        assert after != before and suffix in after
        return after
    async def wait_cleared(self, timeout=40):
        deadline = asyncio.get_running_loop().time()+timeout
        while True:
            field = await self.field()
            if not field.get('text'): return
            assert asyncio.get_running_loop().time() < deadline, 'Delivered draft did not clear in the same chat'
            await asyncio.sleep(.3)
    async def snapshot(self, name):
        root = await self.nodes(); ET.ElementTree(root).write(self.output/(name+'.xml'))
        remote = '/data/local/tmp/agentshell-chat-send-fixture.png'
        await self.adb('shell','screencap','-p',remote)
        await self.adb('pull',remote,str(self.output/(name+'.png')))
        await self.adb('shell','rm','-f',remote)


class ReceiptProxy:
    def __init__(self, port):
        self.port = port; self.mode = 'pass'; self.request = None
        self.send_count = 0; self.lookup_count = 0; self.discarded = 0
        self.connections = 0; self.held = []; self.forwarded = asyncio.Event(); self.errors = []
        self.events = []
    def arm(self, mode):
        assert mode in ['drop', 'disconnect', 'hold']
        self.mode = mode; self.request = None; self.send_count = 0
        self.lookup_count = 0; self.discarded = 0; self.held = []; self.forwarded = asyncio.Event()
    async def release(self):
        self.mode = 'pass'
        held, self.held = self.held, []
        assert held, 'No held acknowledgement to release'
        for client, packet in held:
            try:
                await client.send(packet); self.forwarded.set()
            except websockets.exceptions.ConnectionClosed: pass
    async def handle(self, client):
        self.connections += 1
        path = client.request.path
        try:
            async with websockets.connect(f'ws://127.0.0.1:{self.port}'+path,
                additional_headers={'X-Auth-Token':TOKEN}, max_size=32*1024*1024) as backend:
                async def upstream():
                    async for packet in client:
                        if isinstance(packet, str):
                            message = json.loads(packet)
                            kind = message.get('type')
                            if kind in ['watch-chat-log','unwatch-chat-log','send-bound-chat-message','get-chat-send-result']:
                                self.events.append({'direction':'client','type':kind,'requestId':message.get('requestId'),
                                    'at':round(time.monotonic(),3)})
                            if kind in ['send-bound-chat-message','send-bound-file-to-chat','send-bound-ui-action']:
                                assert message.get('sessionName') == SESSION, 'Refusing a mutation outside the owned fixture'
                                self.send_count += 1
                                if self.request is None: self.request = message
                                assert self.request['requestId'] == message.get('requestId'), 'Unexpected second submission'
                            if kind == 'get-chat-send-result' and self.request and message.get('requestId') == self.request['requestId']:
                                self.lookup_count += 1
                        await backend.send(packet)
                async def downstream():
                    async for packet in backend:
                        message = json.loads(packet) if isinstance(packet,str) else {}
                        if message.get('type') in ['chat-binding','chat-send-result']:
                            self.events.append({'direction':'server','type':message['type'],'requestId':message.get('requestId'),
                                'sendReceipts':message.get('sendReceipts'),'status':message.get('status'),
                                'success':message.get('success'),'mode':self.mode,'at':round(time.monotonic(),3)})
                        if message.get('type') == 'chat-send-result' and self.request and message.get('requestId') == self.request['requestId']:
                            if self.mode == 'drop':
                                self.discarded += 1; self.mode = 'pass'; continue
                            if self.mode == 'disconnect':
                                self.discarded += 1; self.mode = 'pass'
                                await client.close(code=1012,reason='Owned fixture dropped acknowledgement')
                                return
                            if self.mode == 'hold':
                                self.discarded += 1; self.held.append((client,packet)); continue
                            self.forwarded.set()
                        await client.send(packet)
                tasks = [asyncio.create_task(upstream()),asyncio.create_task(downstream())]
                try:
                    done,_ = await asyncio.wait(tasks,return_when=asyncio.FIRST_COMPLETED)
                    for task in done: task.result()
                finally:
                    for task in tasks: task.cancel()
                    await asyncio.gather(*tasks,return_exceptions=True)
        except websockets.exceptions.ConnectionClosed: pass
        except Exception as error:
            self.errors.append(str(error)); await client.close(code=1011)


async def run(args):
    verify_apk(args.apk)
    output = args.output.resolve(); output.mkdir(parents=True,exist_ok=True); output.chmod(0o700)
    ui = AndroidUi(args.serial,output)
    assert args.serial.startswith('emulator-')
    assert (await ui.adb('emu','avd','name')).decode().splitlines()[0] == 'agentshell-ui-poc'
    before = production_panes(); checks = {}; traces = {}
    socket_name = 'agentshell-android-send-' + uuid.uuid4().hex
    backend = None
    proxy = None
    with tempfile.TemporaryDirectory(prefix='owned-android-fixture-',dir=output) as temporary:
        root = Path(temporary); project = root/'project'; project.mkdir(); instance = root/'backend'; instance.mkdir()
        script = root/'fake-agent.py'; script.write_text(FAKE_AGENT)
        identifier = str(uuid.uuid4()); log = (root/'backend.log').open('wb')
        def tmux(*arguments):
            return subprocess.check_output(['tmux','-L',socket_name,*arguments],text=True).strip()
        def submitted(text):
            path = project/'.codex/sessions'/('rollout-'+identifier+'.jsonl')
            count = 0
            for line in path.read_text().splitlines():
                try: payload = json.loads(line).get('payload',{})
                except ValueError: continue
                if payload.get('type') == 'user_message' and payload.get('message') == text: count += 1
            return count
        try:
            command = 'python3 '+shlex.quote(str(script))+' '+shlex.quote(str(project))+' A '+identifier
            tmux('new-session','-d','-s',SESSION,'-c',str(project),command)
            deadline = time.monotonic()+5
            while not (project/'A.ready').exists():
                assert time.monotonic() < deadline; await asyncio.sleep(.02)
            port = free_port(); context = tmux('display-message','-p','-t',SESSION,'#{socket_path},#{pid},0')
            env = dict(os.environ,AUTH_TOKEN=TOKEN,AGENTSHELL_HTTP_PORT=str(port),AGENTSHELL_HTTPS_PORT=str(free_port()),
                TMUX=context,RUST_LOG='warn',TOKIO_WORKER_THREADS='2',HOME=str(project),XDG_CONFIG_HOME=str(root/'config'),
                AGENTSHELL_BROWSER_CONFIG=str(root/'unconfigured-browser.json'),
                AGENTSHELL_TLS_CERT_PATH=str(root/'no-tls.pem'),AGENTSHELL_TLS_KEY_PATH=str(root/'no-tls.key'))
            backend = subprocess.Popen([str(args.binary.resolve())],cwd=instance,env=env,
                stdout=log,stderr=log,stdin=subprocess.DEVNULL)
            deadline = time.monotonic()+10
            while True:
                assert backend.poll() is None, 'Owned backend exited'
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.2): break
                except OSError:
                    assert time.monotonic() < deadline; await asyncio.sleep(.05)
            proxy = ReceiptProxy(port)
            async with websockets.serve(proxy.handle,'127.0.0.1',PROXY_PORT,max_size=32*1024*1024,ping_interval=None):
                await ui.adb('install','-r',str(args.apk.resolve()))
                # Only this disposable emulator's verified synthetic debug app.
                await ui.adb('shell','pm','clear','com.agentshell.debug')
                await ui.adb('shell','pm','grant','com.agentshell.debug','android.permission.POST_NOTIFICATIONS',check=False)
                await ui.adb('reverse','tcp:40199','tcp:40199')
                await ui.adb('shell','input','keyevent','KEYCODE_WAKEUP'); await ui.adb('shell','wm','dismiss-keyguard')
                await ui.adb('shell','am','start','-n','com.agentshell.debug/com.agentshell.MainActivity')
                deadline = asyncio.get_running_loop().time()+40
                while True:
                    tree = await ui.nodes()
                    if any(node.get('text') == SESSION for node in tree.iter('node')): break
                    assert asyncio.get_running_loop().time() < deadline, 'Fixture session never appeared in MainActivity'
                    await asyncio.sleep(.3)
                await ui.click('Chat'); await ui.linked(identifier)
                assert (await ui.field()).get('enabled') == 'true'
                checks['production_main_activity_opens_owned_chat'] = True

                cases = [('drop','dropped-ack')] if args.case == 'drop' else [('drop','dropped-ack'),('disconnect','reconnect')]
                for mode,label in cases:
                    proxy.arm(mode); connections = proxy.connections
                    text = 'fixture'+uuid.uuid4().hex[:12]
                    await ui.linked(identifier)
                    await ui.type_draft(text); await ui.click('Send')
                    await ui.wait_cleared()
                    assert proxy.request and proxy.send_count == 1 and proxy.lookup_count >= 1
                    assert proxy.discarded == 1 and submitted(text) == 1
                    if mode == 'disconnect': assert proxy.connections > connections
                    assert not proxy.errors, proxy.errors
                    await ui.snapshot(label)
                    checks[label+'_recovers_in_same_chat_without_duplicate'] = True
                    traces[label] = {'terminal_submissions':submitted(text),'mutating_packets':proxy.send_count,
                        'receipt_queries':proxy.lookup_count,'discarded_acknowledgements':proxy.discarded,
                        'new_connections':proxy.connections-connections}
                    print(label+' passed',flush=True)

                if args.case == 'all':
                    proxy.arm('hold'); text = 'held'+uuid.uuid4().hex[:12]; newer = 'newer'+uuid.uuid4().hex[:12]
                    await ui.linked(identifier)
                    await ui.type_draft(text); await ui.click('Send'); await asyncio.sleep(31)
                    field = await ui.field()
                    assert field.get('enabled') == 'true' and field.get('text') == text
                    newer = await ui.edit_draft(newer); await ui.snapshot('receipt-blackout-editable')
                    assert proxy.lookup_count >= 1 and submitted(text) == 1 and proxy.send_count == 1
                    await proxy.release(); await asyncio.sleep(2)
                    field = await ui.field(); assert field.get('text') == newer, 'Late receipt erased a newer draft'
                    assert any(node.get('content-desc') == 'Send' and node.get('enabled') == 'true'
                        for node in (await ui.nodes()).iter('node')), 'Composer stayed blocked after authoritative delivery receipt'
                    await ui.snapshot('late-ack-keeps-newer-draft')
                    checks['thirty_second_receipt_blackout_keeps_composer_editable'] = True
                    checks['late_ack_preserves_newer_draft_and_unblocks_send'] = True
                    assert submitted(newer) == 0 and submitted(text) == 1 and proxy.send_count == 1
                    checks['editing_unconfirmed_draft_never_replays_input'] = True
                    traces['blackout'] = {'terminal_submissions':submitted(text),'mutating_packets':proxy.send_count,
                        'receipt_queries':proxy.lookup_count,'held_acknowledgements':proxy.discarded}
                    assert not proxy.errors, proxy.errors
        finally:
            if proxy is not None:
                (output/'wire-events.json').write_text(json.dumps({'events':proxy.events,'errors':proxy.errors,
                    'mutating_packets':proxy.send_count,'receipt_queries':proxy.lookup_count,
                    'discarded_acknowledgements':proxy.discarded},indent=2)+'\n')
            try: await ui.snapshot('final-state')
            except Exception: pass
            (output/'logcat.txt').write_bytes(await ui.adb('logcat','-d','-v','threadtime',
                'ChatViewModel:D','AndroidRuntime:E','*:S',check=False))
            await ui.adb('shell','am','force-stop','com.agentshell.debug',check=False)
            await ui.adb('reverse','--remove','tcp:40199',check=False)
            await ui.adb('shell','rm','-f','/data/local/tmp/agentshell-chat-send-fixture.xml',check=False)
            if backend is not None:
                backend.terminate()
                try: await asyncio.to_thread(backend.wait,timeout=8)
                except subprocess.TimeoutExpired: backend.kill(); await asyncio.to_thread(backend.wait,timeout=5)
            log.close(); shutil.copyfile(root/'backend.log',output/'backend.log')
            subprocess.run(['tmux','-L',socket_name,'kill-server'],capture_output=True)
    assert before.issubset(production_panes()), 'A pre-existing production pane changed'
    checks['production_panes_untouched_owned_fixture_stopped'] = True
    (output/'results.json').write_text(json.dumps({'checks':checks,'traces':traces},indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'checks':checks,'traces':traces,'output':str(output)},indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True); parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--serial',default='emulator-5560'); parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--case',choices=['all','drop'],default='all')
    asyncio.run(run(parser.parse_args()))

if __name__ == '__main__': main()
