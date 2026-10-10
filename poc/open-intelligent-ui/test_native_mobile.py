#!/usr/bin/env python3
"""Real emulator UI + actual WebView content. No production device or daemon."""
import argparse
import asyncio
import io
from contextlib import asynccontextmanager
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import urlopen

import websockets
from PIL import Image
from test_native import Page


async def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('runtime',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--serial',default='emulator-5560')
    args=parser.parse_args();cfg=json.loads(args.runtime.read_text());args.output.mkdir(parents=True,exist_ok=True)
    def adb(*arguments):
        return subprocess.check_output(['adb','-s',args.serial,*arguments],timeout=20)
    def hierarchy():
        for attempt in range(3):
            try:
                adb('shell','uiautomator','dump','/sdcard/canvas-ui.xml')
                return ET.fromstring(adb('shell','cat','/sdcard/canvas-ui.xml'))
            except subprocess.CalledProcessError as error:
                if error.returncode!=137 or attempt==2:raise
                time.sleep(.5)
    def tap(label):
        tree=hierarchy()
        nodes=[n for n in tree.iter('node') if label in [n.get('text'),n.get('content-desc')]]
        assert nodes,'Native control not visible: '+label
        x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
        assert x2>x1 and y2>y1,(label,nodes[0].attrib)
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    def screenshot(name):
        # DOM readiness doesn't prove Android's compositor has painted the new view.
        # Wait for actual visible pixels in the widget, including after dialog/resize.
        tree=hierarchy()
        node=next(n for n in tree.iter('node') if n.get('class')=='android.webkit.WebView')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        deadline=time.monotonic()+10
        while True:
            encoded=adb('exec-out','screencap','-p')
            with Image.open(io.BytesIO(encoded)) as image:
                # Exclude rounded borders and any overlaid native header.
                crop=image.crop((x1+40,max(y1+40,520 if image.width==1080 else y1+40),x2-40,min(y2-30,image.height-160))).convert('L')
                if crop.width>0 and crop.height>0 and crop.entropy()>.2:break
            assert time.monotonic()<deadline,'Native widget remained visually blank: '+name
            time.sleep(.2)
        (args.output/name).write_bytes(encoded)
    async def web_tap(page,child,selector):
        rect=await page.evaluate(child,f"(()=>{{const r=document.querySelector({json.dumps(selector)}).getBoundingClientRect();return {{x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}}}})()")
        tree=hierarchy()
        node=next(n for n in tree.iter('node') if n.get('class')=='android.webkit.WebView')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        scale=(x2-x1)/rect['w']
        # At the bottom of the native container the WebView's bottom edge is visible.
        x=x1+rect['x']*scale;y=y2-rect['h']*scale+rect['y']*scale
        assert 510<y<1800,(selector,rect,(x,y))
        adb('shell','input','tap',str(round(x)),str(round(y)))
    def launch(index=0,dark=False,message=False):
        endpoint=f"ws://10.0.2.2:{cfg['port']}/ws?token={cfg['token']}"
        adb('shell','am','start','-S','-n','com.agentshell.debug/com.agentshell.UiPocActivity',
            '--es','endpoint',endpoint,'--ei','sample',str(index),'--ez','dark',str(dark).lower(),
            '--ez','message',str(message).lower())
    @asynccontextmanager
    async def content():
        deadline=time.monotonic()+20
        while True:
            try:
                pid=adb('shell','pidof','com.agentshell.debug').decode().strip()
                adb('forward','tcp:9223','localabstract:webview_devtools_remote_'+pid)
                targets=json.load(urlopen('http://127.0.0.1:9223/json/list',timeout=2))
                if len(targets)==1:break
            except (OSError,subprocess.CalledProcessError):pass
            assert time.monotonic()<deadline,'A single active WebView did not become ready'
            await asyncio.sleep(.1)
        async with websockets.connect(targets[0]['webSocketDebuggerUrl'],max_size=16*1024*1024) as ws:
            page=Page(ws);await page.call('Runtime.enable')
            child=None
            for _ in range(40):
                for context in page.contexts:
                    try:
                        if await page.evaluate(context,"document.querySelector('h3')?.textContent"):
                            child=context;break
                    except RuntimeError:pass
                if child is not None:break
                await page.call('Runtime.evaluate',{'expression':'1'})
                await asyncio.sleep(.1)
            assert child is not None,'Widget content missing'
            yield page,child

    report={}
    transcript=Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][0]+'.jsonl')
    for index,title in enumerate(['Revenue overview','Split the bill','Release pipeline','Spatial preview','Weekend itinerary']):
        launch(index)
        async with content() as (page,child):
            assert await page.evaluate(child,"document.querySelector('h3').textContent")==title
            assert not await page.evaluate(child,"(()=>{try{return !!parent.document.body}catch{return false}})()")
            if index==0:
                await page.evaluate(child,"(()=>{const e=document.querySelector('#scale');e.value=3;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
                assert await page.evaluate(child,"document.querySelector('#revenue').textContent")=='$75,016'
            elif index==1:
                await page.evaluate(child,"(()=>{const e=document.querySelector('#people');e.value=4;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
                assert await page.evaluate(child,"document.querySelector('#total').textContent")=='28.75'
            elif index==2:
                await page.evaluate(child,"document.querySelector('#step').click()");assert await page.evaluate(child,"document.querySelector('#stepLabel').textContent")=='Validate'
            elif index==3:
                assert await page.evaluate(child,'window.__poc3dReady')
                before=await page.evaluate(child,"document.querySelector('canvas').toDataURL()")
                await page.evaluate(child,"(()=>{const e=document.querySelector('#yaw');e.value=65;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
                assert before!=await page.evaluate(child,"document.querySelector('canvas').toDataURL()")
            elif index==4:
                await page.evaluate(child,"document.querySelector('#selectStop').click()");assert await page.evaluate(child,"document.querySelector('#stopName').textContent")=='San Jose'
            count=transcript.read_text().count('reply: ')
            # Scroll the native chat container so the action is visible on the phone.
            for _ in range(5):adb('shell','input','swipe','1055','1550','1055','700','250')
            await web_tap(page,child,'#ask')
            deadline=time.monotonic()+10
            while transcript.read_text().count('reply: ')<=count:
                assert time.monotonic()<deadline,title+' action was not delivered from Android'
                await asyncio.sleep(.05)
            assert 'reply: ' not in (Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][1]+'.jsonl')).read_text()
            report[title]='native render, controls and scoped A-only follow-up passed'
            print('Passed native interaction: '+title,flush=True)
        # Capture the card from its beginning, without the response covering controls.
        for _ in range(5):adb('shell','input','swipe','1055','700','1055','1550','250')
        screenshot(f'native-{index}-light.png')

    launch(0)
    async with content() as (page,child):
        await page.evaluate(child,"(()=>{const e=document.querySelector('#scale');e.value=3;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
        await asyncio.sleep(.15)
    tap('Expand interactive view')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.querySelector('#scale').value")=='3'
        screenshot('native-fullscreen.png')
    tap('Close expanded view')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.querySelector('#scale').value")=='3'
    tap('Toggle canvas theme')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.documentElement.dataset.theme")=='dark'
        assert await page.evaluate(child,"document.querySelector('#scale').value")=='3'
        screenshot('native-dark.png')
    tap('Split')
    async with content() as (page,child):
        await page.evaluate(child,"(()=>{const e=document.querySelector('#people');e.value=5;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
        await asyncio.sleep(.15)
    tap('Insights')
    async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#scale').value")=='3'
    tap('Split')
    async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#people').value")=='5'
    report['native_fullscreen_theme_and_tabs']='actual UI taps retain each card controls and theme'

    try:
        adb('shell','settings','put','system','accelerometer_rotation','0')
        adb('shell','settings','put','system','user_rotation','1')
        await asyncio.sleep(1)
        async with content() as (page,child):
            assert await page.evaluate(child,"document.querySelector('#people').value")=='5'
            screenshot('native-landscape.png')
        report['activity_recreation']='landscape rotation preserves selected card, theme and controls'
    finally:
        adb('shell','settings','put','system','user_rotation','0')
    await asyncio.sleep(1)
    async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#people').value")=='5'
    tap('Share widget image')
    files=adb('shell','run-as','com.agentshell.debug','ls','cache/file_previews/widget-images').decode().splitlines()
    assert files,'Share did not generate a PNG'
    exported=args.output/'native-export.png'
    exported.write_bytes(adb('exec-out','run-as','com.agentshell.debug','cat','cache/file_previews/widget-images/'+sorted(files)[-1]))
    with Image.open(exported) as image:
        assert image.width>=300 and image.width*image.height<=4_000_000
        assert len(image.resize((50,50)).getcolors(2500) or [])>20,'Shared image is blank'
        report['share_png']={'width':image.width,'height':image.height,'verified_nonblank':True}
    adb('shell','input','keyevent','4')
    async with content() as (page,child):
        await page.evaluate(child,"window.dispatchEvent(new ErrorEvent('error'))")
    await asyncio.sleep(.3)
    tap('Reopen view')
    async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#people').value")=='5'
    report['recoverable_failure']='script failure displays native recovery and reopening restores controls'
    tap('ui-B')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.querySelector('h3').textContent")=='Split the bill'
    # B contains only its own calculator; its tab must describe that widget.
    tap('Split')
    tap('ui-A')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.querySelector('h3').textContent")=='Revenue overview'
    report['native_conversation_switch']='B shows only its calculator with the correct label; A restores its own gallery'
    launch(0,message=True)
    async with content() as (page,child):
        phone_width=await page.evaluate(child,'innerWidth')
        await page.evaluate(child,"(()=>{const e=document.querySelector('#scale');e.value=2;e.dispatchEvent(new Event('input',{bubbles:true}))})()")
        await asyncio.sleep(.2)
    tap('Maximize message')
    async with content() as (page,child):
        assert await page.evaluate(child,"document.querySelector('#scale').value")=='2'
        screenshot('native-message-maximized-phone.png')
    tap('Close expanded message')
    async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#scale').value")=='2'
    try:
        adb('shell','wm','size','1600x2560');adb('shell','wm','density','240')
        await asyncio.sleep(1)
        tap('Maximize message')
        async with content() as (page,child):
            tablet_width=await page.evaluate(child,'innerWidth')
            assert tablet_width>phone_width*1.7,(phone_width,tablet_width)
            assert await page.evaluate(child,"document.querySelector('#scale').value")=='2'
            screenshot('native-message-maximized-tablet.png')
        tap('Close expanded message')
        async with content() as (page,child):assert await page.evaluate(child,"document.querySelector('#scale').value")=='2'
        report['whole_message_maximize']={'phone_css_width':phone_width,'tablet_css_width':tablet_width,
            'controls_survive_close_and_display_resize':True}
    finally:
        adb('shell','wm','size','reset');adb('shell','wm','density','reset')
    report['android_api']=adb('shell','getprop','ro.build.version.sdk').decode().strip()
    report['single_active_webview']=True
    (args.output/'native-mobile-results.json').write_text(json.dumps(report,indent=2)+'\n')
    (args.output/'native-results.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))

if __name__=='__main__':asyncio.run(main())
