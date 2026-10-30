#!/usr/bin/env python3
"""Exercise the production BrowserScreen through its debug-only isolated host fixture.

Requires test_browser_streaming.py --keep-running, an APK built with -PuiPoc=true
and the owned agentshell-ui-poc emulator. Never operates a physical phone.
"""
import argparse
import asyncio
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import websockets

PROJECT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(PROJECT/'poc/remote-browser'))
from test_viewer import CDP

class Android:
    def __init__(self,serial,output):self.serial=serial;self.output=output;self.results={}
    async def adb(self,*args):
        q=await asyncio.to_thread(subprocess.run,['adb','-s',self.serial,*args],capture_output=True,check=True)
        return q.stdout
    async def nodes(self):
        await self.adb('shell','uiautomator','dump','/data/local/tmp/browser-fixture.xml')
        return ET.fromstring(await self.adb('shell','cat','/data/local/tmp/browser-fixture.xml')).iter('node')
    async def click(self,label):
        for _ in range(4):
            nodes=list(await self.nodes())
            for node in nodes:
                if label in [node.get('text'),node.get('content-desc')]:
                    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
                    if x2>x1 and y2>y1:
                        await self.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
            row=next((n for n in nodes if n.get('scrollable')=='true' and any(
                c.get('text')=='Keyboard' or c.get('content-desc')=='Zoom browser view in' for c in n.iter('node'))),None)
            if row is None:break
            x1,y1,x2,y2=map(int,re.findall(r'\d+',row.get('bounds')))
            await self.adb('shell','input','swipe',str(x2-20),str((y1+y2)//2),str(x1+20),str((y1+y2)//2),'400')
            await asyncio.sleep(.3)
        raise AssertionError('Missing control: '+label)
    async def relaunch(self):
        await self.adb('shell','am','force-stop','com.agentshell.debug')
        await self.adb('shell','am','start','-n','com.agentshell.debug/com.agentshell.BrowserFlowPocActivity')
        return await self.ready()
    async def target(self):
        pid=(await self.adb('shell','pidof','com.agentshell.debug')).decode().strip()
        await self.adb('forward','tcp:18995','localabstract:webview_devtools_remote_'+pid)
        def read():return json.load(urllib.request.urlopen('http://127.0.0.1:18995/json',timeout=2))
        targets=await asyncio.to_thread(read)
        return next(t for t in targets if t['url'].startswith('https://127.0.0.1:18998/browser/'))
    async def ready(self):
        for _ in range(80):
            try:
                target=await self.target()
                async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                    c=CDP(socket)
                    if await c.evaluate('''(()=>{const r=window.AgentShellUI?.rfb,o=window.AgentShellViewportOptions;
                        if(!window.AgentShellUI?.connected || !o?.adaptive || !r || !innerHeight) return false;
                        if(!o.keyboardVisible && Math.abs((r._fbWidth/r._fbHeight)/(innerWidth/innerHeight)-1)>.005) return false;
                        return r._canvas.width>100 && r._canvas.getBoundingClientRect().width>100 && r._canvas.getContext("2d").getImageData(100,100,1,1).data[3]>0})()'''):return target
            except (OSError,StopIteration,RuntimeError,subprocess.CalledProcessError,
                    websockets.ConnectionClosed,websockets.exceptions.InvalidHandshake):pass
            await asyncio.sleep(.25)
        raise AssertionError('No current secure browser frame')
    async def screenshot(self,name):
        (self.output/(name+'.png')).write_bytes(await self.adb('exec-out','screencap','-p'))
    async def viewer_point(self,x,y,viewport_width):
        view=next(n for n in await self.nodes() if n.get('class')=='android.webkit.WebView')
        left,top,right,_=map(int,re.findall(r'\d+',view.get('bounds')))
        scale=(right-left)/viewport_width
        return round(left+x*scale),round(top+y*scale)
    async def hide_keyboard(self):
        keyboard=(await self.adb('shell','dumpsys','input_method')).decode()
        if re.search(r'(mInputShown|inputShown)=true',keyboard):
            await self.adb('shell','input','keyevent','KEYCODE_BACK');await asyncio.sleep(.5)
    async def swipe_viewer(self,rect,viewport_width):
        x=rect['x']+rect['width']*.75
        x1,y1=await self.viewer_point(x,rect['y']+rect['height']*.8,viewport_width)
        x2,y2=await self.viewer_point(x,rect['y']+rect['height']*.3,viewport_width)
        await self.adb('shell','input','swipe',str(x1),str(y1),str(x2),str(y2),'500');await asyncio.sleep(.5)
    async def options(self,name):
        target=await self.ready()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            c=CDP(socket)
            await c.evaluate('AgentShellUI.closeControlbar()')
            await asyncio.sleep(.3);await self.hide_keyboard()
            await self.click('Browser interaction options');await asyncio.sleep(.7)
            assert await c.evaluate('document.activeElement.id!=="noVNC_keyboardinput"'),f'{name}: controls retained IME focus'
            geometry=await c.evaluate('''(()=>{const e=document.querySelector('#noVNC_control_bar .noVNC_scroll'),r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,viewportHeight:innerHeight,viewportWidth:innerWidth,open:document.getElementById('noVNC_control_bar').classList.contains('noVNC_open')}})()''')
            assert geometry['open'] and 40<geometry['height']<=geometry['viewportHeight'],f'{name}: collapsed control drawer: {geometry}'
            for _ in range(5):
                point=await c.evaluate('''(()=>{const e=document.getElementById('noVNC_settings_button'),r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,visible:document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)===e}})()''')
                if point['visible']:break
                await self.swipe_viewer(geometry,geometry['viewportWidth'])
            assert point['visible'],f'{name}: settings button is clipped or covered'
            x,y=await self.viewer_point(point['x'],point['y'],geometry['viewportWidth'])
            await self.adb('shell','input','tap',str(x),str(y))
            await asyncio.sleep(.7)
            panel=await c.evaluate('''(()=>{const e=document.getElementById('noVNC_settings'),r=e.getBoundingClientRect(),labels=[...e.querySelectorAll('.slider-label')].filter(l=>l.getClientRects().length);return {open:e.classList.contains('noVNC_open'),x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height,viewport:[innerWidth,innerHeight],labelsReadable:labels.length>=10 && labels.every(l=>parseFloat(getComputedStyle(l).fontSize)>=12 && l.getBoundingClientRect().height>0 && l.getBoundingClientRect().bottom<=l.closest('li').getBoundingClientRect().bottom+1)}})()''')
            assert panel['open'] and panel['width']>150 and panel['height']>60,f'{name}: settings did not open: {panel}'
            assert panel['x']>=0 and panel['y']>=-1 and panel['right']<=panel['viewport'][0]+1 and panel['bottom']<=panel['viewport'][1]+1,f'{name}: settings outside viewport: {panel}'
            assert panel['labelsReadable'],f'{name}: settings labels overlap or disappear'
            await self.screenshot(name+'-settings-open')
            # Native touch injection exercises WebView's Android scroll handling;
            # DevTools touch dispatch alone does not reliably trigger that path.
            await self.swipe_viewer(panel,panel['viewport'][0])
            # Larger viewports can fit the entire panel after removing duplicate
            # insets. Require scrolling only when its contents actually overflow.
            assert await c.evaluate('(()=>{const e=document.getElementById("noVNC_settings");return e.scrollHeight<=e.clientHeight+1 || e.scrollTop>0})()'),f'{name}: overflowing settings cannot scroll'
            await self.screenshot(name+'-settings')
            await self.click('Browser interaction options');await asyncio.sleep(.6)
            assert await c.evaluate('!document.getElementById("noVNC_settings").classList.contains("noVNC_open") && !document.getElementById("noVNC_control_bar").classList.contains("noVNC_open")')
            self.results[name+'_settings_visible_scrollable']=True
            await c.evaluate('AgentShellUI.hideVirtualKeyboard()')
            await self.hide_keyboard()
    async def run(self,apk):
        assert self.serial.startswith('emulator-')
        assert (await self.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
        await self.adb('shell','wm','size','reset')
        await self.adb('shell','settings','put','system','user_rotation','0')
        with zipfile.ZipFile(apk) as archive:
            assert any(b'isolated-ui-poc-only' in archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
        self.output.mkdir(parents=True,exist_ok=True)
        await self.adb('install','-r',str(apk))
        await self.adb('reverse','tcp:18998','tcp:18998')
        target=await self.relaunch();self.results['production_screen_secure_frame']=True
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            c=CDP(socket)
            assert await c.evaluate('document.cookie==="" && !Object.values(localStorage).some(x=>x.includes("isolated-ui-poc-only"))')
            self.results['no_javascript_credentials']=True
            assert await c.evaluate('document.getElementById("noVNC_fallback_errormsg").innerText===""')
            self.results['pinned_client_csp_compatible']=True
        await self.screenshot('portrait-light')
        await self.click('Switch to dark mode');await asyncio.sleep(.2)
        assert any(n.get('content-desc')=='Switch to light mode' for n in await self.nodes())
        self.results['dark_light_controls']=True;await self.screenshot('portrait-dark')
        await self.options('portrait')
        await self.click('Expand browser');await asyncio.sleep(.2)
        assert any(n.get('content-desc')=='Exit fullscreen' for n in await self.nodes())
        self.results['fullscreen']=True;await self.screenshot('fullscreen')
        await self.click('Exit fullscreen')
        await self.click('Keyboard');await asyncio.sleep(.4)
        keyboard=(await self.adb('shell','dumpsys','input_method')).decode()
        self.results['android_keyboard']=bool(re.search(r'(mInputShown|inputShown)=true',keyboard))
        assert self.results['android_keyboard'],'Android IME did not appear'
        await self.adb('shell','input','keyevent','KEYCODE_BACK')
        await self.adb('shell','settings','put','system','accelerometer_rotation','0')
        await self.adb('shell','settings','put','system','user_rotation','1')
        await asyncio.sleep(.7);await self.hide_keyboard()
        await self.ready();self.results['landscape_reflows']=True;await self.screenshot('landscape')
        await self.options('landscape')
        await self.adb('shell','settings','put','system','user_rotation','0')
        await asyncio.sleep(.7);await self.hide_keyboard()
        # Restart at each emulated physical resolution to discard the previous
        # device's IME/window insets; rotation above still tests a live reflow.
        await self.adb('shell','wm','size','1600x2560');await self.relaunch()
        self.results['tablet_layout']=True;await self.screenshot('tablet')
        await self.options('tablet')
        await self.adb('shell','wm','size','1536x1605');await self.relaunch()
        await self.options('foldable')
        await self.adb('shell','wm','size','735x1600');await self.relaunch()
        await self.options('narrow-phone')
        await self.adb('shell','wm','size','reset')
        target=await self.relaunch()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:await CDP(socket).evaluate('AgentShellUI.disconnect()')
        await asyncio.sleep(2);await self.ready();self.results['automatic_stream_reconnect']=True
        await self.adb('shell','input','keyevent','KEYCODE_HOME');await asyncio.sleep(.4)
        await self.adb('shell','am','start','-n','com.agentshell.debug/com.agentshell.BrowserFlowPocActivity')
        await self.ready();self.results['background_resume']=True
        (self.output/'results.json').write_text(json.dumps(self.results,indent=2)+'\n')
        print(json.dumps(self.results,indent=2))

async def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560')
    parser.add_argument('--apk',type=Path,default=PROJECT/'android-native/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--output',type=Path,default=Path('/tmp/agentshell-browser-implementation/android'))
    args=parser.parse_args();android=Android(args.serial,args.output)
    try:await android.run(args.apk)
    finally:
        await android.adb('shell','wm','size','reset')
        await android.adb('shell','settings','put','system','user_rotation','0')

if __name__=='__main__':asyncio.run(main())
