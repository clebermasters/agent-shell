#!/usr/bin/env python3
"""Check content size in the real production screen on the owned test emulator.

Requires test_browser_streaming.py --keep-running (with matching test TLS) and
an APK built with -PuiPoc=true. Never operates a physical phone or personal Chrome.
"""
import argparse
import asyncio
import json
from pathlib import Path
import zipfile

import websockets
from test_android_browser import Android, CDP

METRICS = '''(()=>{const r=AgentShellUI.rfb,c=r._canvas.getBoundingClientRect();
return {viewport:[innerWidth,innerHeight],frame:[r._fbWidth,r._fbHeight],
canvas:[c.width,c.height],rect:[c.x,c.y,c.width,c.height],scale:r._display.scale,
density:AgentShellViewportOptions.contentDensity,keyboard:!!AgentShellViewportOptions.keyboardVisible,
connected:AgentShellUI.connected}})()'''


async def fitted(c, density):
    for _ in range(80):
        m=await c.evaluate(METRICS)
        w,h=m['viewport'];fw,fh=m['frame'];cw,ch=m['canvas']
        if (m['density']==density and not m['keyboard'] and
            abs((fw/fh)/(w/h)-1)<.005 and max(abs(cw-w),abs(ch-h))<3):
            # The old framebuffer can also be fitted for one compositor frame.
            expected=await c.evaluate(f'AgentShellBrowserGeometry.desktopSize(innerWidth,innerHeight,{density})')
            if [expected['width'],expected['height']]==m['frame']:return m
        await asyncio.sleep(.1)
    raise AssertionError(f'Content size {density} did not fit: {m}')


async def choose(a, c, label, density, fullscreen=False):
    if fullscreen:await a.click('Fullscreen controls')
    await a.click('Content size');await a.click(label);await a.click('Done')
    await asyncio.sleep(.4)
    result=await fitted(c,density)
    print(f'{label}: {result["frame"]} fitted to {result["viewport"]}',flush=True)
    return result


async def run(serial, apk, output, fixture):
    a=Android(serial,output);output.mkdir(parents=True,exist_ok=True)
    assert serial.startswith('emulator-') and (await a.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
    with zipfile.ZipFile(apk) as archive:
        assert any(b'isolated-ui-poc-only' in archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
    config=json.loads((fixture/'browser.json').read_text())
    assert Path(config['userDataDir']).is_relative_to(fixture.resolve()),'Not an owned test profile'
    record=Path(config['runtimeDir'])/'runtime.json'
    before_pid=json.loads(record.read_text())['chromePid']
    await a.adb('install','-r',str(apk));await a.adb('reverse','tcp:18998','tcp:18998')
    checks={};measurements={}
    for name,physical in [('phone','1080x1920'),('foldable','1536x1605')]:
        await a.adb('shell','settings','put','system','accelerometer_rotation','0')
        await a.adb('shell','settings','put','system','user_rotation','0')
        await a.adb('shell','wm','size',physical);target=await a.relaunch()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            c=CDP(socket)
            await c.evaluate('AgentShellUI.closeControlbar()')
            default=await fitted(c,100)
            # Compare against the original default algorithm independently of
            # the new geometry helper, then exercise native controls live.
            w,h=default['viewport'];factor=max(1,640/w,360/h)
            factor*=min(1,1600/(w*factor),2160/(h*factor))
            expected=[int(w*factor/2+.5)*2,int(h*factor/2+.5)*2]
            assert default['frame']==expected,(default,expected)
            checks[name+'_default_unchanged']=True
            sizes={100:default}
            for label,density in [('Larger text',75),('More content',125),('Even more content',150),('Most content',200)]:
                m=await choose(a,c,label,density);sizes[density]=m
                assert m['frame'][0]*m['frame'][1]<=6000000
                assert m['frame'][0]<=2560 and m['frame'][1]<=3840
                await a.screenshot(name+'-'+str(density))
            assert all(sizes[x]['frame'][0]<sizes[y]['frame'][0] for x,y in [(75,100),(100,125),(125,150),(150,200)])
            checks[name+'_all_presets_resize_and_fit']=True
            measurements[name]=sizes
            # A native tap still reaches the matching remote pixel after a
            # resolution change; the local pinch remains independent.
            await c.evaluate('''if(AgentShellViewer.mousePosition().enabled) AgentShellViewer.mouse();
                const r=AgentShellUI.rfb,send=r._sendMouse;r.contentSizeWire=[];
                r._sendMouse=function(x,y,buttons){this.contentSizeWire.push([
                    this._display.absX(x),this._display.absY(y),buttons]);return send.call(this,x,y,buttons)};
                r.focusOnClick=false;''')
            m=sizes[200];x,y,cw,ch=m['rect'];px=x+cw*.6;py=y+ch*.65
            tx,ty=await a.viewer_point(px,py,m['viewport'][0])
            await a.adb('shell','input','tap',str(tx),str(ty));await asyncio.sleep(.5)
            wire=await c.evaluate('AgentShellUI.rfb.contentSizeWire')
            assert any(v[2]&1 and abs(v[0]-m['frame'][0]*.6)<4 and abs(v[1]-m['frame'][1]*.65)<4 for v in wire),wire
            checks[name+'_tap_coordinates_follow_resolution']=True
            await c.evaluate('AgentShellViewer.zoom(2)');await asyncio.sleep(.3)
            zoomed=await c.evaluate(METRICS)
            assert zoomed['frame']==m['frame'] and zoomed['canvas'][0]>m['canvas'][0]*1.9
            await c.evaluate('AgentShellViewer.fit()');await fitted(c,200)
            checks[name+'_pinch_fit_keep_content_size']=True
            await a.click('Expand browser');await asyncio.sleep(.8)
            if any(n.get('text')=='Got it' for n in await a.nodes()):await a.click('Got it')
            m=await choose(a,c,'Even more content',150,fullscreen=True)
            checks[name+'_fullscreen_size_control']=True
            await a.click('Fullscreen controls');await a.click('Keyboard')
            for _ in range(40):
                keyboard=await c.evaluate(METRICS)
                if keyboard['keyboard']:break
                await asyncio.sleep(.1)
            await asyncio.sleep(.8);keyboard=await c.evaluate(METRICS)
            assert keyboard['keyboard'] and keyboard['frame']==m['frame']
            assert all(abs(x-y)<3 for x,y in zip(keyboard['canvas'],m['canvas']))
            checks[name+'_keyboard_keeps_size']=True
            await a.hide_keyboard();await fitted(c,150)
            await a.adb('shell','settings','put','system','user_rotation','1')
            await asyncio.sleep(.8);await a.hide_keyboard();await fitted(c,150)
            checks[name+'_rotation_keeps_choice_and_fit']=True
            await a.adb('shell','settings','put','system','user_rotation','0')
            await asyncio.sleep(.8);await a.hide_keyboard();await fitted(c,150)
            await a.click('Exit fullscreen');await fitted(c,150)
        await a.click('Reconnect browser');target=await a.ready()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            c=CDP(socket);await fitted(c,150)
            checks[name+'_reconnect_remembers_choice']=True
            await choose(a,c,'Default',100)
        print(name+' passed',flush=True)
    assert json.loads(record.read_text())['chromePid']==before_pid
    checks['same_chrome_process_and_profile']=True
    (output/'results.json').write_text(json.dumps({'checks':checks,'measurements':measurements},indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'checks':checks,'output':str(output)},indent=2))


async def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560')
    p.add_argument('--apk',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--fixture',type=Path,required=True);args=p.parse_args()
    try:await run(args.serial,args.apk,args.output,args.fixture.resolve())
    finally:
        a=Android(args.serial,args.output);await a.hide_keyboard()
        await a.adb('shell','wm','size','reset');await a.adb('shell','settings','put','system','user_rotation','0')

if __name__=='__main__':asyncio.run(main())
