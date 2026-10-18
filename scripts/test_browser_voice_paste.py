#!/usr/bin/env python3
"""Paste synthetic dictation through the real HTTPS/KasmVNC/Chrome stack.

Uses disposable profiles, localhost-only debug ports, a generated test certificate,
and a free X display. Never touches a personal Chrome profile, phone or service.
Run after cargo build: python3 scripts/test_browser_voice_paste.py

Optional --android-serial emulator-NNNN requires the owned agentshell-ui-poc
emulator and an isolated -PuiPoc=true APK whose browser_fixture_ca resource
matches --tls-directory/cert.pem. That directory must contain only test keys.
"""
import argparse
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

import websockets

import test_browser_streaming as streaming
from test_browser_streaming import Fixture, TOKEN
from test_android_browser import CDP
from test_android_browser import Android

PROJECT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


class VoiceFixture(Fixture):
    def __init__(self, output, binary, tls_directory=None):
        super().__init__(output, binary)
        self.http_port, self.https_port, self.page_port = free_port(), free_port(), free_port()
        self.remote_debug, self.viewer_debug = free_port(), free_port()
        streaming.HTTP = f'http://127.0.0.1:{self.http_port}'
        streaming.HTTPS = f'https://127.0.0.1:{self.https_port}'
        self.viewer = None
        self.tls_directory = tls_directory

    def start_backend(self):
        environment = dict(os.environ, AUTH_TOKEN=TOKEN, AGENTSHELL_BROWSER_CONFIG=str(self.config_path),
            AGENTSHELL_HTTP_PORT=str(self.http_port), AGENTSHELL_HTTPS_PORT=str(self.https_port),
            AGENTSHELL_TLS_CERT_PATH=str(self.output/'certs/cert.pem'),
            AGENTSHELL_TLS_KEY_PATH=str(self.output/'certs/key.pem'), RUST_LOG='error', HOME=str(self.output/'home'))
        with (self.output/'backend.log').open('ab') as log:
            self.backend = subprocess.Popen([str(self.binary)], cwd=self.output/'backend', env=environment,
                stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        for _ in range(100):
            try:
                if self.api('/api/clients')[0] == 200:
                    return
            except OSError:
                pass
            if self.backend.poll() is not None:
                break
            time.sleep(.1)
        raise AssertionError('Isolated backend failed; inspect private backend.log')

    def prepare(self):
        self.output.mkdir(parents=True, mode=0o700, exist_ok=True)
        assert not self.config_path.exists(), 'Use a new empty test output directory'
        self.output.chmod(0o700)
        for name in ('backend', 'home', 'certs'):
            (self.output/name).mkdir(mode=0o700)
        if self.tls_directory:
            for name in ('cert.pem', 'key.pem'):
                shutil.copyfile(self.tls_directory/name, self.output/'certs'/name)
        else:
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
                '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
                '-keyout', str(self.output/'certs/key.pem'), '-out', str(self.output/'certs/cert.pem')],
                check=True, capture_output=True)
        self.ssl = ssl.create_default_context(cafile=str(self.output/'certs/cert.pem'))
        for name in ('key.pem', 'cert.pem'):
            (self.output/'certs'/name).chmod(0o600)
        display = next(n for n in range(100, 201) if not Path(f'/tmp/.X11-unix/X{n}').exists()
                       and not Path(f'/tmp/.X{n}-lock').exists())
        source = (PROJECT/'scripts/browser-runtime.py').read_text()
        needle = "command = ['google-chrome', '--user-data-dir='+value['userDataDir']]"
        assert needle in source
        source = source.replace(needle, "command = ['google-chrome', '--password-store=basic', "
            f"'--remote-debugging-port={self.remote_debug}', '--user-data-dir='+value['userDataDir']]")
        runner = self.output/'synthetic-runtime.py'
        runner.write_text(source)
        self.config = {'sessionId': str(uuid.uuid4()), 'userDataDir': str(self.output/'remote-profile'),
            'profileDirectory': None, 'runtimeDir': str(self.output/'runtime'), 'display': display,
            'port': free_port(), 'password': uuid.uuid4().hex+uuid.uuid4().hex, 'runner': str(runner),
            'publicOrigin': streaming.HTTPS, 'additionalOrigins': ['https://127.0.0.1:18998'],
            'supervision': 'process', 'privateBus': True,
            'startUrl': f'http://127.0.0.1:{self.page_port}/voice-fixture'}
        self.config_path.write_text(json.dumps(self.config))
        self.config_path.chmod(0o600)
        class Page(BaseHTTPRequestHandler):
            def do_GET(self):
                content = '''<!doctype html><meta charset="utf-8"><title>Isolated voice paste</title>
<style>body{font:24px sans-serif;background:#eaf4fc}textarea,input{font:24px sans-serif;display:block;margin:30px;width:700px}textarea{height:250px}</style>
<h1>Synthetic remote browser dictation fixture</h1>
<form><textarea id="draft"></textarea><input id="single"><button>Submit</button></form>
<div contenteditable="true" id="rich">Rich text</div>
<script>window.events=[];window.submits=0;document.querySelector('form').onsubmit=e=>{e.preventDefault();submits++};
for(const t of ['input','beforeinput','keydown','paste']) document.addEventListener(t,e=>events.push({type:t,key:e.key,inputType:e.inputType,target:e.target.id}));</script>'''.encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(content)
            def log_message(self, *_):
                pass
        self.pages = ThreadingHTTPServer(('127.0.0.1', self.page_port), Page)
        threading.Thread(target=self.pages.serve_forever, daemon=True).start()
        self.start_backend()

    async def targets(self, port):
        return await asyncio.to_thread(lambda: json.load(urllib.request.urlopen(
            f'http://127.0.0.1:{port}/json', timeout=2)))

    async def evaluate(self, target, expression):
        async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
            return await CDP(connection).evaluate(expression)

    async def remote(self, expression):
        targets = await self.targets(self.remote_debug)
        target = next(t for t in targets if t['type'] == 'page' and '/voice-fixture' in t['url'])
        return await self.evaluate(target, expression)

    async def frame(self, expression):
        targets = await self.targets(self.viewer_debug)
        target = next(t for t in targets if t['type'] == 'page')
        return await self.evaluate(target, expression)

    async def wait_value(self, expected):
        for _ in range(80):
            actual = await self.remote("document.getElementById('draft').value")
            if actual == expected:
                return
            await asyncio.sleep(.1)
        diagnostics = await self.remote("({active:document.activeElement.id,focus:document.hasFocus(),events})")
        raise AssertionError(f'Remote textarea differs: {actual!r} != {expected!r}; {diagnostics}')

    async def paste(self, identifier, value):
        return await self.frame('AgentShellViewer.pasteText('+json.dumps(identifier)+','+json.dumps(value)+')')

    async def tests(self):
        status, _, state = await asyncio.to_thread(self.api, '/api/browser/sessions', {})
        assert status == 200 and state['state'] == 'ready', state
        sid = self.config['sessionId']
        self.viewer_id = str(uuid.uuid4())
        self.generation = state['runtimeGeneration']
        status, _, grant = await asyncio.to_thread(self.api, f'/api/browser/sessions/{sid}/viewer-grants',
            {'viewerId': self.viewer_id, 'runtimeGeneration': self.generation})
        assert status == 200
        status, headers, _ = await asyncio.to_thread(self.api, f'/browser/{sid}/bootstrap',
            'grant='+grant['grant'], token=None, https=True, raw=True)
        assert status == 303
        cookie_name, cookie_value = headers['Set-Cookie'].split(';', 1)[0].split('=', 1)
        with (self.output/'viewer.log').open('ab') as log:
            self.viewer = subprocess.Popen(['google-chrome', '--headless=new', '--disable-gpu',
                '--no-first-run', '--no-default-browser-check', '--password-store=basic',
                '--ignore-certificate-errors', f'--user-data-dir={self.output}/viewer-profile',
                f'--remote-debugging-port={self.viewer_debug}', 'about:blank'],
                stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        for _ in range(80):
            try:
                targets = await self.targets(self.viewer_debug)
                target = next(t for t in targets if t['type'] == 'page')
                break
            except (OSError, StopIteration):
                await asyncio.sleep(.1)
        else:
            raise AssertionError('Disposable viewer Chrome did not start')
        async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
            cdp = CDP(connection)
            await cdp.call('Network.setCookie', name=cookie_name, value=cookie_value, url=streaming.HTTPS,
                secure=True, httpOnly=True, sameSite='Strict')
            await cdp.call('Page.navigate', url=streaming.HTTPS+headers['Location'])
        for _ in range(100):
            if await self.frame("!!window.AgentShellViewer?.pasteText && !!window.AgentShellUI?.connected"):
                break
            await asyncio.sleep(.1)
        else:
            raise AssertionError('Production KasmVNC viewer did not connect')
        for _ in range(100):
            try:
                if await self.remote("!!document.getElementById('draft')"):
                    break
            except (OSError, StopIteration):
                pass
            await asyncio.sleep(.1)
        else:
            raise AssertionError('Synthetic remote page did not load')
        record = json.loads((Path(self.config['runtimeDir'])/'runtime.json').read_text())
        env = dict(os.environ, DISPLAY=':'+str(self.config['display']),
            XAUTHORITY=str(Path(self.config['runtimeDir'])/'Xauthority'))
        window = subprocess.run(['xdotool', 'search', '--sync', '--onlyvisible', '--pid',
            str(record['chromePid']), '--class', 'Google-chrome'], env=env, check=True,
            capture_output=True, text=True, timeout=10).stdout.splitlines()[0]
        subprocess.run(['xdotool', 'windowactivate', '--sync', window], env=env,
            check=True, capture_output=True, timeout=10)
        self.results['https_authenticated_kasmvnc_connected'] = True
        await self.remote("(()=>{const e=document.getElementById('draft');e.value='before TARGET after';e.focus();e.setSelectionRange(7,13)})()")
        text = 'ação coração — 日本語 😀\n\nSecond paragraph.'
        assert await self.paste('selection-1', text) == 'inserted'
        expected = 'before '+text+' after'
        await self.wait_value(expected)
        self.results['focused_selection_unicode_emoji_paragraphs'] = True
        assert await self.paste('selection-1', text) == 'duplicate'
        await asyncio.sleep(.3)
        await self.wait_value(expected)
        self.results['same_job_deduplicated'] = True
        # Change X11 clipboard independently, then repeat the exact transcript
        # under a new recording ID. The clipboard must be refreshed.
        subprocess.run(['xclip', '-selection', 'clipboard'], input=b'unrelated clipboard',
            env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        await self.remote("(()=>{const e=document.getElementById('draft');e.focus();e.setSelectionRange(e.value.length,e.value.length)})()")
        assert await self.paste('selection-2', text) == 'inserted'
        await self.wait_value(expected+text)
        self.results['new_job_identical_transcript_refreshes_clipboard'] = True
        assert await self.remote("submits===0 && !events.some(e=>e.key==='Enter')")
        self.results['no_enter_no_form_submit'] = True
        await self.remote("(()=>{const e=document.getElementById('single');e.value='left right';e.focus();e.setSelectionRange(5,5)})()")
        assert await self.paste('single-1', 'hello 🌍 ') == 'inserted'
        await asyncio.sleep(.5)
        assert await self.remote("document.getElementById('single').value") == 'left hello 🌍 right'
        assert await self.remote('submits') == 0
        self.results['single_line_cursor_no_submit'] = True
        await self.remote("(()=>{const e=document.getElementById('rich');e.focus();const r=document.createRange();r.selectNodeContents(e);const s=getSelection();s.removeAllRanges();s.addRange(r)})()")
        assert await self.paste('rich-1', 'Rich ação 😀') == 'inserted'
        await asyncio.sleep(.5)
        assert await self.remote("document.getElementById('rich').textContent") == 'Rich ação 😀'
        self.results['contenteditable_selection'] = True
        (self.output/'results.json').write_text(json.dumps(self.results, indent=2)+'\n')
        print(json.dumps(self.results, indent=2), flush=True)

    async def android_tests(self, serial, apk):
        """Synthetic completion crosses production VM -> WebView -> VNC -> Chrome."""
        assert serial.startswith('emulator-'), 'Only an owned emulator is accepted'
        class VoiceAndroid(Android):
            forward_port = None
            async def target(self):
                pid = (await self.adb('shell', 'pidof', 'com.agentshell.debug')).decode().strip()
                if self.forward_port is None:
                    self.forward_port = (await self.adb('forward', 'tcp:0',
                        'localabstract:webview_devtools_remote_'+pid)).decode().strip()
                targets = await asyncio.to_thread(lambda: json.load(urllib.request.urlopen(
                    'http://127.0.0.1:'+self.forward_port+'/json', timeout=2)))
                return next(t for t in targets if t['url'].startswith('https://127.0.0.1:18998/browser/'))
        android = VoiceAndroid(serial, self.output)
        assert (await android.adb('emu', 'avd', 'name')).decode().splitlines()[0] == 'agentshell-ui-poc'
        # Never allow an arbitrary APK containing personal backend credentials.
        import zipfile
        with zipfile.ZipFile(apk) as archive:
            assert any(TOKEN.encode() in archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
        await self.frame('AgentShellViewer.pause()')
        self.viewer.terminate()
        self.viewer.wait(timeout=10)
        sid = self.config['sessionId']
        status, _, _ = await asyncio.to_thread(self.api, f'/api/browser/sessions/{sid}/release-viewer',
            {'viewerId': self.viewer_id, 'runtimeGeneration': self.generation})
        assert status == 200
        await android.adb('install', '-r', str(apk))
        await android.adb('reverse', 'tcp:18998', f'tcp:{self.https_port}')
        await android.adb('shell', 'am', 'force-stop', 'com.agentshell.debug')
        await self.remote("(()=>{const e=document.getElementById('draft');e.value='native BEFORE after';e.focus();e.setSelectionRange(7,13);events=[];submits=0})()")
        text = 'Dictated ação 😀\n\nSecond native paragraph.'
        # am's argument parser requires shell quoting for transcript whitespace.
        import shlex
        command = 'am start -n com.agentshell.debug/com.agentshell.BrowserFlowPocActivity --es voiceTranscript '+shlex.quote(text)+' --el voiceDelayMs 12000'
        started = (await android.adb('shell', command)).decode()
        assert 'Error:' not in started, started
        try:
            native_frame = await android.ready()
            self.results['native_secure_frame_connected'] = True
            # Adaptive resize can change X11 focus. Model the user's field focus
            # after the phone viewer is ready, before delivering the transcript.
            env = dict(os.environ, DISPLAY=':'+str(self.config['display']),
                XAUTHORITY=str(Path(self.config['runtimeDir'])/'Xauthority'))
            record = json.loads((Path(self.config['runtimeDir'])/'runtime.json').read_text())
            window = subprocess.run(['xdotool', 'search', '--onlyvisible', '--pid', str(record['chromePid']),
                '--class', 'Google-chrome'], env=env, check=True, capture_output=True, text=True).stdout.splitlines()[0]
            subprocess.run(['xdotool', 'windowactivate', '--sync', window], env=env,
                check=True, capture_output=True, timeout=10)
            point = await self.remote("(()=>{const r=document.getElementById('draft').getBoundingClientRect();return {x:screenX+r.x+r.width/2,y:screenY+outerHeight-innerHeight+r.y+r.height/2}})()")
            async with websockets.connect(native_frame['webSocketDebuggerUrl']) as connection:
                await CDP(connection).evaluate(f"(()=>{{const r=AgentShellUI.rfb,s=r._display.scale;r._sendMouse({point['x']}*s,{point['y']}*s,1);r._sendMouse({point['x']}*s,{point['y']}*s,0)}})()")
            await asyncio.sleep(.5)
            await self.remote("(()=>{const e=document.getElementById('draft');e.value='native BEFORE after';e.focus();e.setSelectionRange(7,13);events=[];submits=0})()")
            for _ in range(250):
                if await self.remote("document.getElementById('draft').value") == 'native '+text+' after':
                    break
                await asyncio.sleep(.2)
            else:
                await android.screenshot('native-delivery-failure')
                diagnostics = await self.remote("({active:document.activeElement.id,focus:document.hasFocus(),value:document.getElementById('draft').value,events})")
                raise AssertionError('Native synthetic transcript was not pasted into the remote cursor: '+repr(diagnostics))
            assert await self.remote("submits===0 && !events.some(e=>e.key==='Enter')")
            # The native action remains available after ACK and after startup.
            nodes = list(await android.nodes())
            assert any(n.get('content-desc') == 'Dictate at browser cursor' or n.get('text') == 'Dictate' for n in nodes)
            self.results['native_production_vm_webview_cursor_delivery'] = True
            self.results['native_dictation_control_available'] = True
            self.results['native_delivery_no_enter_no_submit'] = True
            await android.screenshot('native-dictation-success')
            await android.adb('shell', 'pm', 'grant', 'com.agentshell.debug', 'android.permission.RECORD_AUDIO')
            await asyncio.sleep(1)  # Let platform permission/inset changes settle.
            async with websockets.connect(native_frame['webSocketDebuggerUrl']) as connection:
                native_cdp = CDP(connection)
                geometry = '({width:innerWidth,height:innerHeight,desktopWidth:AgentShellUI.rfb._fbWidth,desktopHeight:AgentShellUI.rfb._fbHeight})'
                before = await native_cdp.evaluate(geometry)
                await android.click('Dictate')
                await asyncio.sleep(.8)
                nodes = list(await android.nodes())
                assert any((n.get('text') or '').startswith('Recording ') for n in nodes), 'Microphone did not start'
                during = await native_cdp.evaluate(geometry)
                await android.screenshot('native-recording-overlay')
                (self.output/'native-recording-nodes.json').write_text(json.dumps([
                    {key:n.get(key) for key in ('text','content-desc','class','bounds')}
                    for n in nodes if n.get('text') or n.get('content-desc') or n.get('class')=='android.webkit.WebView'], indent=2))
                await android.click('Cancel')  # Never Stop/enqueue: no live ASR call.
                await asyncio.sleep(.3)
                assert before == during, f'Recording changed the viewer geometry: {before} -> {during}'
                self.results['recording_overlay_preserves_remote_viewport'] = True
                assert before == await native_cdp.evaluate(geometry)
                self.results['recording_cancel_without_asr'] = True
            (self.output/'results.json').write_text(json.dumps(self.results, indent=2)+'\n')
            print(json.dumps(self.results, indent=2), flush=True)
        finally:
            await android.adb('shell', 'am', 'force-stop', 'com.agentshell.debug')
            await android.adb('reverse', '--remove', 'tcp:18998')
            if android.forward_port:
                await android.adb('forward', '--remove', 'tcp:'+android.forward_port)

    def cleanup(self):
        if self.viewer and self.viewer.poll() is None:
            self.viewer.terminate()
            try:
                self.viewer.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.viewer.kill()
                self.viewer.wait(timeout=5)
        super().cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=PROJECT/'backend-rust/target/debug/agentshell-backend')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--keep-running', action='store_true', help='Keep isolated backend/Chrome for debug Android tests')
    parser.add_argument('--tls-directory', type=Path, help='Isolated cert.pem/key.pem matching the debug APK trust resource')
    parser.add_argument('--android-serial', help='Owned agentshell-ui-poc emulator only')
    parser.add_argument('--apk', type=Path, default=Path('/tmp/agentshell-browser-voice-poc.apk'))
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix='agentshell-browser-voice-'))
    fixture = VoiceFixture(output.resolve(), args.binary.resolve(), args.tls_directory)
    try:
        fixture.prepare()
        asyncio.run(fixture.tests())
        if args.android_serial:
            asyncio.run(fixture.android_tests(args.android_serial, args.apk.resolve()))
        print('Private fixture: '+str(fixture.output), flush=True)
        if args.keep_running:
            while True:
                time.sleep(1)
    finally:
        fixture.cleanup()


if __name__ == '__main__':
    main()
