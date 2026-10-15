#!/usr/bin/env python3
"""Real native desktop/Android handoffs using only a disposable Chrome profile.

Requires KasmVNC, GTK/WebKit, xdotool, a release backend and the existing isolated
TLS fixture. --android-serial additionally requires the owned agentshell-ui-poc
emulator and a debug APK built with -PuiPoc=true. No physical phone is touched.
"""
import argparse
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import uuid
import urllib.request
import websockets
from test_browser_streaming import Fixture, TOKEN
from test_android_browser import Android, CDP

PROJECT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


class Handoff:
    def __init__(self, root, binary, serial):
        self.root = root
        self.fixture = Fixture(root / 'fixture', binary)
        self.processes = []
        self.serial = serial
        self.results = {}
        self.debug_port = free_port()
        self.config = self.fixture.config_path.with_name('browser-desktop.json')
        self.env = dict(os.environ, DISPLAY=':95', XAUTHORITY=str(root / 'Xauthority'), GTK_USE_PORTAL='0')

    def prepare(self):
        for display in (94, 95):
            assert not Path('/tmp/.X11-unix/X' + str(display)).exists(), 'Test display is already in use'
        for port in (18998, 18999, 19002, 19003):
            with socket.socket() as connection:
                connection.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                connection.bind(('127.0.0.1', port))
        self.fixture.prepare()
        self.fixture.pages.shutdown()
        self.fixture.pages.server_close()
        class Page(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/login':
                    self.send_response(303)
                    self.send_header('Set-Cookie', 'fixture_session=synthetic; HttpOnly; SameSite=Lax')
                    self.send_header('Set-Cookie', 'fixture_persistent=synthetic; Max-Age=86400; HttpOnly; SameSite=Lax')
                    self.send_header('Location', '/app?tab=primary')
                    self.end_headers()
                    return
                authenticated = all(value in self.headers.get('Cookie', '') for value in
                                    ('fixture_session=synthetic', 'fixture_persistent=synthetic'))
                data = ('''<!doctype html><title>Synthetic shared workspace</title>
<style>body{background:#eaf4fc;font:24px sans-serif}input{position:absolute;left:50px;top:100px;width:500px;height:80px;font:24px sans-serif}</style>
<h1 id="auth">''' + ('Synthetic signed-in session' if authenticated else 'Sign-in required') + '''</h1>
<input id="draft"><script>window.memoryToken=crypto.randomUUID();window.marker='unchanged-live-memory';</script>''').encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(data)
            def log_message(self, *_):
                pass
        self.fixture.pages = ThreadingHTTPServer(('127.0.0.1', 19003), Page)
        threading.Thread(target=self.fixture.pages.serve_forever, daemon=True).start()
        # These switches apply only to this fake profile; never use them for
        # the installed personal browser or read real encrypted credentials.
        source = (PROJECT / 'scripts/browser-runtime.py').read_text()
        needle = "command = ['google-chrome', '--user-data-dir='+value['userDataDir']]"
        assert needle in source
        source = source.replace(needle, "command = ['google-chrome', '--password-store=basic', '--remote-debugging-port=" +
                                str(self.debug_port) + "', '--user-data-dir='+value['userDataDir']]")
        runner = self.root / 'synthetic-runtime.py'
        runner.write_text(source)
        self.fixture.config.update(runner=str(runner), startUrl='http://127.0.0.1:19003/login')
        self.fixture.config_path.write_text(json.dumps(self.fixture.config))
        self.fixture.stop_backend()
        self.fixture.start_backend()
        env = self.root / 'runtime.env'
        env.write_text('AUTH_TOKEN=' + json.dumps(TOKEN) + '\n')
        env.chmod(0o600)
        self.config.write_text(json.dumps({'origin': 'https://127.0.0.1:18998', 'runtimeEnv': str(env),
              'stateDir': str(self.root / 'viewer'), 'caFile': str(self.fixture.output / 'certs/ca.pem')}))
        self.config.chmod(0o600)
        backend_desktop_config = self.fixture.config_path.with_name('browser-desktop.json')
        backend_desktop_config.write_bytes(self.config.read_bytes())
        backend_desktop_config.chmod(0o600)
        for name in ['browser-desktop.py', 'browser-desktop-agent.py', 'browser-native.py']:
            (self.root / name).write_bytes((PROJECT / 'scripts' / name).read_bytes())
        authority = self.root / 'Xauthority'
        subprocess.run(['xauth', '-f', str(authority), 'add', ':95', '.', uuid.uuid4().hex], capture_output=True, check=True)
        password = self.root / 'password'
        password.write_text('fixture:' + uuid.uuid4().hex + '\n')
        password.chmod(0o600)
        with (self.root / 'display.log').open('ab') as log:
            self.processes.append(subprocess.Popen(['Xvnc', ':95', '-auth', str(authority), '-nolisten', 'tcp',
                '-geometry', '1280x900', '-depth', '24', '-interface', '127.0.0.1', '-websocketPort', str(free_port()),
                '-KasmPasswordFile', str(password), '-SecurityTypes', 'None'], env=self.env, stdout=log, stderr=log))
        for _ in range(50):
            if Path('/tmp/.X11-unix/X95').exists():
                break
            time.sleep(0.1)
        with (self.root / 'window-manager.log').open('ab') as log:
            self.processes.append(subprocess.Popen(['openbox'], env=self.env, stdout=log, stderr=log))

    async def cli(self, name, expected=0):
        result = await asyncio.to_thread(subprocess.run, ['/usr/bin/python3', str(PROJECT / 'scripts/browser-desktop.py'),
            '--config', str(self.config), '--json', name], env=self.env, capture_output=True, text=True, timeout=60)
        value = json.loads(result.stdout)
        assert result.returncode == expected, value
        assert TOKEN not in result.stdout and TOKEN not in result.stderr
        return value

    async def api(self, path, body=None, **kwargs):
        return await asyncio.to_thread(self.fixture.api, path, body, **kwargs)

    async def page(self, expression):
        def targets():
            return json.load(urllib.request.urlopen('http://127.0.0.1:' + str(self.debug_port) + '/json', timeout=3))
        pages = await asyncio.to_thread(targets)
        target = next(page for page in pages if page['id'] == self.selected)
        async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
            return await CDP(connection).evaluate(expression)

    async def state(self):
        return await self.page('''({auth:document.getElementById('auth').textContent,memory:memoryToken,
            marker,draft:document.getElementById('draft').value,url:location.href,visible:document.visibilityState})''')

    async def assert_preserved(self):
        assert await self.state() == self.expected, 'Live browser state changed during viewer transfer'
        current = json.loads((Path(self.fixture.config['runtimeDir']) / 'runtime.json').read_text())
        assert current['chromePid'] == self.record['chromePid']
        assert current['chromeGeneration'] == self.record['chromeGeneration']
        assert current['runtimeGeneration'] == self.record['runtimeGeneration']

    async def grant(self, identity, takeover=False):
        return await self.api(self.path + '/viewer-grants', {'viewerId': identity,
                'runtimeGeneration': self.generation, 'takeControl': takeover})

    async def cookie(self, grant):
        status, headers, _ = await self.api('/browser/' + self.sid + '/bootstrap', 'grant=' + grant,
                                          token=None, https=True, raw=True)
        assert status == 303
        return headers['Set-Cookie'].split(';', 1)[0]

    async def native_type(self, text):
        # Input crosses GTK/WebKit -> RFB -> X11 -> Chrome, not DevTools.
        status = await self.cli('status')
        window = subprocess.run(['xdotool', 'search', '--onlyvisible', '--pid', str(status['pid']),
            '--name', '^AgentShell Chrome$'], env=self.env, capture_output=True, text=True, check=True).stdout.splitlines()[0]
        geometry_text = subprocess.run(['xdotool', 'getwindowgeometry', '--shell', window],
            env=self.env, capture_output=True, text=True, check=True).stdout
        geometry = {key: int(value) for key, value in re.findall(r'^(X|Y|WIDTH|HEIGHT)=(\d+)$', geometry_text, re.M)}
        remote = await self.page('''(()=>{const r=document.getElementById('draft').getBoundingClientRect();return {
            x:screenX+r.x+r.width/2,y:screenY+outerHeight-innerHeight+r.y+r.height/2,width:screen.width,height:screen.height}})()''')
        width, height = status['viewport']
        scale = min(width / remote['width'], height / remote['height'])
        x = round(geometry['X'] + (width - remote['width'] * scale) / 2 + remote['x'] * scale)
        y = round(geometry['Y'] + geometry['HEIGHT'] - height + (height - remote['height'] * scale) / 2 + remote['y'] * scale)
        subprocess.run(['xdotool', 'windowactivate', '--sync', window, 'mousemove', '--sync', str(x), str(y),
            'click', '1', 'type', '--clearmodifiers', text], env=self.env, check=True, capture_output=True)
        await asyncio.sleep(0.4)

    async def run(self):
        self.prepare()
        assert (await self.cli('open'))['connected']
        self.results['native_launcher_and_https_frame'] = True
        session = (await self.api('/api/browser/capabilities'))[2]['session']
        self.sid, self.generation = session['sessionId'], session['runtimeGeneration']
        self.path = '/api/browser/sessions/' + self.sid
        for _ in range(50):
            try:
                pages = await asyncio.to_thread(lambda: json.load(urllib.request.urlopen(
                    'http://127.0.0.1:' + str(self.debug_port) + '/json', timeout=2)))
                target = next(page for page in pages if page['type'] == 'page' and '/app' in page['url'])
                break
            except (OSError, StopIteration):
                await asyncio.sleep(0.1)
        async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
            cdp = CDP(connection)
            secondary = await cdp.call('Target.createTarget', url='http://127.0.0.1:19003/app?tab=selected-secondary')
            self.selected = secondary['targetId']
            await cdp.call('Target.activateTarget', targetId=self.selected)
        await asyncio.sleep(0.5)
        await self.native_type('native-viewer-ok')
        assert await self.page("document.getElementById('draft').value") == 'native-viewer-ok'
        self.results['native_mouse_and_keyboard_input'] = True
        await self.page("document.getElementById('draft').value='Unsaved live text';window.marker='keep-across-viewers'")
        self.expected = await self.state()
        assert self.expected['auth'] == 'Synthetic signed-in session' and self.expected['visible'] == 'visible'
        self.record = json.loads((Path(self.fixture.config['runtimeDir']) / 'runtime.json').read_text())
        assert (await self.grant(str(uuid.uuid4())))[0] == 409
        assert (await self.api(self.path + '/release-viewer', {'viewerId': str(uuid.uuid4()),
                    'runtimeGeneration': self.generation}, token='wrong'))[0] == 401
        self.results['normal_grants_cannot_steal_and_release_requires_auth'] = True
        desktop_id = (self.root / 'viewer/viewer-id').read_text().strip()
        phone_id = str(uuid.uuid4())
        for _ in range(4):
            assert (await self.cli('phone'))['code'] == 'PHONE'
            # Repeated phone requests and delayed reconnect callbacks must not
            # reclaim desktop control or interfere with the phone's identity.
            await asyncio.sleep(2.2)
            assert (await self.cli('status'))['code'] == 'PHONE'
            unused = (await self.grant(str(uuid.uuid4())))[2]['grant']
            phone_grant = (await self.grant(phone_id))[2]['grant']
            cookie = await self.cookie(phone_grant)
            async with websockets.connect('wss://127.0.0.1:18998/browser/' + self.sid + '/socket',
                    ssl=self.fixture.ssl, origin='https://127.0.0.1:18998', additional_headers={'Cookie': cookie},
                    subprotocols=['binary']) as phone:
                assert await asyncio.wait_for(phone.recv(), 5) == b'RFB 003.008\n'
                await self.cli('phone')
                assert (await self.grant(desktop_id))[0] == 409
                assert (await self.cli('open', expected=1))['code'] == 'CONTROLLER_IN_USE'
                assert (await self.cli('desktop'))['connected']
                await asyncio.wait_for(phone.wait_closed(), 5)
            assert (await self.api('/browser/' + self.sid + '/bootstrap', 'grant=' + unused,
                    token=None, https=True, raw=True))[0] == 401
            assert (await self.api('/browser/' + self.sid + '/view/vnc.html',
                    token=None, cookie=cookie, https=True, raw=True))[0] == 401
            await self.assert_preserved()
        self.results['four_transfer_cycles_keep_process_tabs_cookies_memory_and_draft'] = True
        self.results['takeover_disconnects_old_socket_and_revokes_unused_grants'] = True
        self.results['repeated_phone_release_preserves_other_controller'] = True
        print('Native desktop input and four authenticated viewer-transfer cycles passed.', flush=True)
        previous_connections = (await self.cli('status'))['connectionCount']
        self.fixture.stop_backend()
        self.fixture.start_backend()
        for _ in range(100):
            current = await self.cli('status')
            if current['connected'] and current['connectionCount'] > previous_connections:
                break
            await asyncio.sleep(0.2)
        else:
            raise AssertionError('Native viewer did not recover after backend restart')
        await self.assert_preserved()
        self.results['daemon_restart_recovers_viewer_without_chrome_restart'] = True
        print('Backend restart recovered the viewer with the same Chrome process.', flush=True)
        if self.serial:
            android = Android(self.serial, self.root)
            assert self.serial.startswith('emulator-')
            assert (await android.adb('emu', 'avd', 'name')).decode().splitlines()[0] == 'agentshell-ui-poc'
            await android.adb('install', '-r', str(PROJECT / 'android-native/app/build/outputs/apk/debug/app-debug.apk'))
            await android.adb('reverse', 'tcp:18998', 'tcp:18998')
            for index in range(4):
                await self.cli('phone')
                for attempt in range(3):
                    try:
                        await android.relaunch()
                        break
                    except subprocess.CalledProcessError:
                        if attempt == 2:
                            raise
                        await asyncio.sleep(0.5)
                await self.assert_preserved()
                if index == 0:
                    await android.screenshot('synthetic-phone-viewer')
                    target = await android.target()
                    async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
                        await CDP(connection).evaluate("AgentShellUI.rfb.sendKey(65507,'ControlLeft',true)")
                # Take control while the phone stays foregrounded. Its automatic
                # reconnect must fail busy rather than steal control back.
                assert (await self.cli('desktop'))['connected']
                await asyncio.sleep(3)
                assert (await self.cli('status'))['connected']
                assert any('Another viewer is controlling' in node.get('text', '') for node in await android.nodes())
                await self.assert_preserved()
                if index == 0:
                    await self.page("document.getElementById('draft').value='';window.controlObserved=null;document.addEventListener('keydown',e=>window.controlObserved=e.ctrlKey)")
                    await self.native_type('x')
                    assert await self.page("document.getElementById('draft').value==='x' && window.controlObserved===false"), 'Phone modifier stayed held after explicit takeover'
                    await self.page("document.getElementById('draft').value='Unsaved live text'")
                    await self.assert_preserved()
                    self.results['held_phone_modifier_released_on_takeover'] = True
            self.results['four_android_native_screen_round_trips'] = True
            self.results['foreground_android_cannot_steal_control_after_return_to_computer'] = True
            # Android initiates both directions, including launching a CLOSED
            # viewer through the graphical-session broker.
            await self.cli('quit')
            with (self.root / 'desktop-agent.log').open('ab') as log:
                agent = subprocess.Popen(['/usr/bin/python3',str(PROJECT/'scripts/browser-desktop-agent.py'),
                    '--config',str(self.config)],env=self.env,stdout=log,stderr=log)
                self.processes.append(agent)
            for _ in range(50):
                if (self.root/'viewer/desktop-agent.sock').exists():break
                await asyncio.sleep(.1)
            await android.relaunch()
            for index in range(4):
                await android.click('Move browser')
                await android.click('Send to computer')
                for _ in range(50):
                    if any(node.get('text')=='Browsing on your computer' for node in await android.nodes()):break
                    await asyncio.sleep(.2)
                else:raise AssertionError('Android Send to computer did not complete')
                assert (await self.cli('status'))['connected']
                await self.assert_preserved()
                if index==0:
                    await android.screenshot('android-on-computer')
                    await android.adb('shell','input','keyevent','KEYCODE_HOME')
                    await android.adb('shell','am','start','-n','com.agentshell.debug/com.agentshell.BrowserFlowPocActivity')
                    assert any(node.get('text')=='Browsing on your computer' for node in await android.nodes())
                    assert (await self.cli('status'))['connected']
                await android.click('Use on this phone')
                await android.ready()
                await self.assert_preserved()
                if index==0:await android.screenshot('android-phone-handoff')
                if index==1:await self.cli('quit')
            self.results['four_round_trips_initiated_by_android_buttons']=True
            self.results['android_launches_closed_desktop_viewer_through_session_agent']=True
            self.results['android_background_resume_preserves_desktop_destination']=True
            await self.cli('quit')
            agent.terminate();await asyncio.to_thread(agent.wait,timeout=5)
            phone_target=await android.ready()
            await android.click('Move browser');await android.click('Send to computer')
            for _ in range(30):
                if any('Continue on this phone.' in node.get('text','') for node in await android.nodes()):break
                await asyncio.sleep(.2)
            else:raise AssertionError('Missing desktop did not keep Android control')
            assert (await android.ready())['id']==phone_target['id']
            await self.assert_preserved()
            self.results['unavailable_desktop_keeps_same_live_phone_viewer']=True
            # A disposable helper consumes the real transfer nonce and then
            # fails, exercising recovery after the old phone cookie is revoked.
            helper=self.root/'browser-desktop.py';original=helper.read_bytes()
            helper.write_text('''import argparse,importlib.util,json,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--config');p.add_argument('--handoff-id');p.add_argument('--generation');a,_=p.parse_known_args()
s=importlib.util.spec_from_file_location('desktop',RESEARCH_SOURCE);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
api=m.Api(m.Config(Path(a.config)));sid,generation=api.session();target=str(uuid.uuid4())
api.request('/api/browser/sessions/'+sid+'/viewer-grants',{'viewerId':target,'runtimeGeneration':generation,'takeControl':True,'handoffId':a.handoff_id})
(Path(a.config).parent/'failure-transfer.json').write_text(json.dumps({'id':a.handoff_id,'target':target}))
print(json.dumps({'code':'VIEWER_TIMEOUT','connected':False}))
'''.replace('RESEARCH_SOURCE',repr(str(PROJECT/'scripts/browser-desktop.py'))))
            before_failure=await android.ready()
            await android.click('Move browser');await android.click('Send to computer')
            for _ in range(35):
                if any('Reconnecting on this phone.' in node.get('text','') for node in await android.nodes()):break
                await asyncio.sleep(.2)
            else:raise AssertionError('Failed target did not request phone recovery')
            recovered=await android.ready()
            assert recovered['id']!=before_failure['id']
            failure=json.loads((self.fixture.config_path.parent/'failure-transfer.json').read_text())
            status,_,data=await self.api(self.path+'/viewer-grants',{'viewerId':failure['target'],
                'runtimeGeneration':self.generation,'takeControl':True,'handoffId':failure['id']})
            assert status==409 and data['code']=='HANDOFF_EXPIRED'
            await self.assert_preserved()
            helper.write_bytes(original)
            self.results['failed_desktop_after_claim_restores_live_phone_control']=True
            self.results['late_desktop_nonce_cannot_steal_after_recovery']=True
            await android.adb('shell', 'am', 'force-stop', 'com.agentshell.debug')
            await android.adb('reverse', '--remove', 'tcp:18998')
            await android.adb('forward', '--remove', 'tcp:18995')
        assert (await self.cli('quit'))['code'] in ('CLOSED','NOT_RUNNING')
        await asyncio.sleep(0.5)
        assert (await self.cli('status'))['code'] == 'NOT_RUNNING'
        await self.assert_preserved()
        self.results['closing_desktop_leaves_chrome_alive'] = True
        self.results['actual_personal_google_authentication_tested'] = False
        (self.root / 'results.json').write_text(json.dumps(self.results, indent=2) + '\n')
        print(json.dumps(self.results, indent=2), flush=True)

    def cleanup(self):
        if self.config.exists():
            subprocess.run(['/usr/bin/python3', str(PROJECT / 'scripts/browser-desktop.py'), '--config', str(self.config),
                           'quit'], env=self.env, capture_output=True, timeout=60)
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        self.fixture.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--binary', type=Path, default=PROJECT / 'backend-rust/target/release/agentshell-backend')
    parser.add_argument('--android-serial', help='Owned agentshell-ui-poc emulator only')
    args = parser.parse_args()
    root = args.output or Path(tempfile.mkdtemp(prefix='as-browser-handoff-'))
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    root.chmod(0o700)
    assert not (root / 'fixture/browser.json').exists(), 'Use a new test output directory'
    test = Handoff(root, args.binary.resolve(), args.android_serial)
    try:
        asyncio.run(test.run())
    finally:
        test.cleanup()


if __name__ == '__main__':
    main()
