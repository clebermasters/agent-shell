#!/usr/bin/env python3
"""Synthetic native Chrome display changes; never closes a personal browser."""
import asyncio
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
import websockets
from test_browser_handoff import Handoff, PROJECT
from test_android_browser import CDP,Android


class NativeTest(Handoff):
    async def move(self,destination):
        state=(await self.api('/api/browser/capabilities'))[2]['session']
        result=await asyncio.to_thread(subprocess.run,['/usr/bin/python3',str(PROJECT/'scripts/browser-native.py'),
            '--config',str(self.fixture.config_path),'--generation',state['runtimeGeneration'],destination],
            env=self.env,capture_output=True,text=True,timeout=55)
        value=json.loads(result.stdout);assert value.get('connected'),value
        return value
    async def pages(self):
        for _ in range(60):
            try:
                values=await asyncio.to_thread(lambda:json.load(urllib.request.urlopen(
                    'http://127.0.0.1:'+str(self.debug_port)+'/json',timeout=2)))
                pages=[v for v in values if v.get('type')=='page' and '/app?' in v.get('url','')]
                if len(pages)>=2:return pages
            except OSError:pass
            await asyncio.sleep(.15)
        raise AssertionError('Two original tabs were not restored')
    async def inspect(self):
        for _ in range(60):
            pages=await self.pages();target=next(p for p in pages if 'selected-secondary' in p['url'])
            async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                value=await CDP(socket).evaluate('''document.getElementById('auth') && document.getElementById('draft') ?
                    ({auth:document.getElementById('auth').textContent,memory:memoryToken,
                    draft:document.getElementById('draft').value,visible:document.visibilityState}) : null''')
                if value:return value
            await asyncio.sleep(.1)
        raise AssertionError('Restored tab document did not load')
    async def run(self):
        self.prepare()
        if getattr(self,'encrypted',False):
            runner=Path(self.fixture.config['runner']);runner.write_text(runner.read_text().replace("'--password-store=basic', ",''))
            self.fixture.config.update(privateBus=False,requireSecretService=True)
            self.fixture.config_path.write_text(json.dumps(self.fixture.config));self.fixture.stop_backend();self.fixture.start_backend()
        else:self.env.pop('DBUS_SESSION_BUS_ADDRESS',None)
        assert (await self.api('/api/browser/sessions',{}))[2]['state']=='ready'
        for _ in range(60):
            try:
                pages=json.load(urllib.request.urlopen('http://127.0.0.1:'+str(self.debug_port)+'/json',timeout=2))
                target=next(p for p in pages if '/app?' in p.get('url',''));break
            except (OSError,StopIteration):await asyncio.sleep(.15)
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            created=await CDP(socket).call('Target.createTarget',url='http://127.0.0.1:19003/app?tab=selected-secondary')
            await CDP(socket).call('Target.activateTarget',targetId=created['targetId'])
        await asyncio.sleep(.5)
        self.selected=created['targetId']
        await self.page("document.getElementById('draft').value='unsaved-before-restart'")
        before=await self.inspect()
        for index in range(2):
            old=json.loads((Path(self.fixture.config['runtimeDir'])/'runtime.json').read_text())
            moved=await self.move('desktop');assert moved['session']['state']=='native'
            assert moved['normalExit']
            record=json.loads((Path(self.fixture.config['runtimeDir'])/'native.json').read_text())
            assert record['chromePid']!=old['chromePid']
            assert not Path('/tmp/.X11-unix/X94').exists()
            for _ in range(60):
                windows=subprocess.run(['xdotool','search','--onlyvisible','--pid',str(record['chromePid']),
                    '--class','Google-chrome'],env=self.env,capture_output=True,text=True)
                if windows.stdout.strip():break
                await asyncio.sleep(.1)
            assert windows.stdout.strip(),'Native Chrome window is not visible on desktop display'
            actual=await self.inspect()
            assert actual['auth']=='Synthetic signed-in session' and actual['visible']=='visible'
            assert actual['memory']!=before['memory'] and actual['draft']=='',actual
            prefs=Path(self.fixture.config['userDataDir'])/'Default/Preferences'
            restored=await self.move('phone');assert restored['session']['state']=='ready'
            state=await self.inspect();assert state['auth']=='Synthetic signed-in session' and state['visible']=='visible'
            assert restored['normalExit']
            before=state
        self.results.update(native_chrome_on_separate_desktop_display=True,
            no_vnc_display_while_native=True,normal_sigint_exit=True,two_native_round_trips=True,
            same_profile_and_two_tabs_restored=True,synthetic_persistent_and_session_auth_restored=True,
            unsaved_input_and_memory_do_not_survive_restart=True,actual_google_authentication_tested=False)
        if self.serial:
            with (self.root/'agent.log').open('ab') as log:
                agent=subprocess.Popen(['/usr/bin/python3',str(PROJECT/'scripts/browser-desktop-agent.py'),
                    '--config',str(self.config)],env=self.env,stdout=log,stderr=log)
                self.processes.append(agent)
            for _ in range(50):
                if (self.root/'viewer/desktop-agent.sock').exists():break
                await asyncio.sleep(.1)
            android=Android(self.serial,self.root)
            assert self.serial.startswith('emulator-')
            assert (await android.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
            await android.adb('install','-r',str(PROJECT/'android-native/app/build/outputs/apk/debug/app-debug.apk'))
            await android.adb('reverse','tcp:18998','tcp:18998')
            await android.relaunch()
            for index in range(2):
                await android.click('Move browser');await android.click('Open native Chrome on computer')
                assert any('Unsaved' in n.get('text','') or 'unsaved' in n.get('text','') for n in await android.nodes())
                if index==0:
                    await android.click('Cancel');await android.ready()
                    await android.click('Move browser');await android.click('Open native Chrome on computer')
                await android.click('Open native Chrome')
                for _ in range(70):
                    if any(n.get('text')=='Native Chrome on your computer' for n in await android.nodes()):break
                    await asyncio.sleep(.2)
                else:raise AssertionError('Android native desktop action failed')
                actual=await self.inspect();assert actual['auth']=='Synthetic signed-in session' and actual['visible']=='visible'
                assert (await self.api('/api/browser/capabilities'))[2]['session']['state']=='native'
                assert not Path('/tmp/.X11-unix/X94').exists()
                if index==0:await android.screenshot('native-on-computer')
                await android.click('Use on this phone');await android.click('Return to phone');await android.ready()
                assert (await self.inspect())['auth']=='Synthetic signed-in session'
            self.results['two_android_native_display_round_trips']=True
            self.results['explicit_restart_notice_and_cancel_without_restarting']=True
            await android.adb('shell','am','force-stop','com.agentshell.debug')
            await android.adb('reverse','--remove','tcp:18998');await android.adb('forward','--remove','tcp:18995')
        # Closing the native browser manually still permits a restored return.
        await self.move('desktop')
        target=next(p for p in await self.pages() if 'selected-secondary' in p['url'])
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            try:await CDP(socket).call('Browser.close')
            except websockets.ConnectionClosed:pass
        for _ in range(60):
            if (await self.api('/api/browser/capabilities'))[2]['session']['state']=='native-closed':break
            await asyncio.sleep(.1)
        else:raise AssertionError('Manual native close was not tracked')
        await self.move('phone')
        assert (await self.inspect())['auth']=='Synthetic signed-in session'
        self.results['manual_native_close_can_restore_on_phone']=True
        # A native launch failure AFTER the clean close must restore VNC using
        # this same profile; simulate only the target launch in private code.
        wrapper=self.root/'fail-native-target.py'
        wrapper.write_text('''import importlib.util,json,sys
from pathlib import Path
s=importlib.util.spec_from_file_location('native',NATIVE_SOURCE);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
p=Path(sys.argv[1]);raw=json.loads(p.read_text());runtime=m.load_runtime(raw['runner']);value=runtime.config(p)
def fail(*_):raise runtime.RuntimeErrorCode('NATIVE_START_FAILED')
m.start_native=fail
print(json.dumps(m.move(value,p,'desktop',sys.argv[2],runtime)))
'''.replace('NATIVE_SOURCE',repr(str(PROJECT/'scripts/browser-native.py'))))
        current=(await self.api('/api/browser/capabilities'))[2]['session']
        failure=await asyncio.to_thread(subprocess.run,['/usr/bin/python3',str(wrapper),str(self.fixture.config_path),current['runtimeGeneration']],
            env=self.env,capture_output=True,text=True,timeout=55)
        outcome=json.loads(failure.stdout)
        assert not outcome['connected'] and outcome['session']['state']=='ready',outcome
        assert (await self.inspect())['auth']=='Synthetic signed-in session'
        self.results['failed_native_launch_restores_vnc_with_same_profile']=True
        # Chrome's unload confirmation must prevent a handoff without any
        # escalation or process/profile takeover. Use only this fake document.
        target=next(p for p in await self.pages() if 'selected-secondary' in p['url'])
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            cdp=CDP(socket)
            await cdp.call('Page.enable')
            await cdp.call('Runtime.evaluate',expression="window.onbeforeunload=e=>{e.preventDefault();e.returnValue='stay';return 'stay'}",userGesture=True)
            assert await cdp.evaluate('navigator.userActivation.hasBeenActive')
            state=(await self.api('/api/browser/capabilities'))[2]['session']
            record=json.loads((Path(self.fixture.config['runtimeDir'])/'runtime.json').read_text())
            result=await asyncio.to_thread(subprocess.run,['/usr/bin/python3',str(PROJECT/'scripts/browser-native.py'),
                '--config',str(self.fixture.config_path),'--generation',state['runtimeGeneration'],'desktop'],
                env=self.env,capture_output=True,text=True,timeout=55)
            blocked=json.loads(result.stdout)
            assert blocked['code']=='CHROME_CLOSE_BLOCKED' and blocked['phonePreserved'],blocked
            assert (await self.api('/api/browser/capabilities'))[2]['session']['runtimeGeneration']==state['runtimeGeneration']
            assert Path('/proc',str(record['chromePid'])).exists()
            await cdp.call('Page.handleJavaScriptDialog',accept=False)
            await cdp.evaluate('window.onbeforeunload=null')
            # The signal handler is one-shot after cancellation. Normal browser
            # closure via DevTools is restricted to synthetic cleanup here.
            try:await cdp.call('Browser.close')
            except websockets.ConnectionClosed:pass
        self.results['unload_confirmation_blocks_handoff_without_forced_kill']=True
        if getattr(self,'encrypted',False):
            cookies=list(Path(self.fixture.config['userDataDir']).rglob('Cookies'))
            assert cookies
            with sqlite3.connect(cookies[0]) as db:
                rows=db.execute("SELECT value,length(encrypted_value) FROM cookies WHERE name IN ('fixture_session','fixture_persistent')").fetchall()
            assert len(rows)==2 and all(value=='' and length>0 for value,length in rows)
            self.results['encrypted_synthetic_cookies_restored_using_existing_os_keyring']=True
        (self.root/'results.json').write_text(json.dumps(self.results,indent=2)+'\n')
        print(json.dumps(self.results,indent=2),flush=True)
    def cleanup(self):
        native=Path(self.fixture.config.get('runtimeDir',self.root/'unused'))/'native.json'
        if native.exists():
            r=json.loads(native.read_text())
            pid=r.get('chromePid')
            if pid and Path('/proc',str(pid)).exists():
                os.kill(pid,signal.SIGINT)
                for _ in range(100):
                    if not Path('/proc',str(pid)).exists():break
                    time.sleep(.1)
        super().cleanup()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--android-serial');parser.add_argument('--encrypted-profile',action='store_true');args=parser.parse_args()
    root=Path(tempfile.mkdtemp(prefix='as-native-chrome-'))
    print('Owned native test directory: '+str(root),flush=True)
    test=NativeTest(root,PROJECT/'backend-rust/target/release/agentshell-backend',args.android_serial)
    test.encrypted=args.encrypted_profile
    try:asyncio.run(test.run())
    finally:test.cleanup()


if __name__=='__main__':main()
