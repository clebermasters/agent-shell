#!/usr/bin/env python3
"""Measure native fullscreen coverage and live remote-desktop adaptation."""
import argparse
import asyncio
import json
from pathlib import Path
import re
import websockets
from test_android_browser import Android, CDP

async def geometry(android, fullscreen=False):
    for _ in range(80):
        target=await android.ready()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            result=await CDP(socket).evaluate('''(()=>{const r=AgentShellUI.rfb._canvas.getBoundingClientRect();return {viewport:[innerWidth,innerHeight],framebuffer:[AgentShellUI.rfb._fbWidth,AgentShellUI.rfb._fbHeight],canvas:[r.width,r.height],coverage:r.width*r.height/(innerWidth*innerHeight),options:window.AgentShellViewportOptions,connected:AgentShellUI.connected}})()''')
        aspect=result['viewport'][0]/result['viewport'][1]
        framebuffer_aspect=result['framebuffer'][0]/result['framebuffer'][1]
        if result.get('options',{}).get('fullscreen')==fullscreen and .97<result['coverage']<1.02 and abs(framebuffer_aspect/aspect-1)<.005:
            return result
        await asyncio.sleep(.15)
    raise AssertionError('Desktop did not match viewer: '+str(result))

async def interrupted_resize_recovers(android):
    """Interrupt the live resize timer with the native keyboard-state signal.

    The ordinary keyboard case below exercises the real IME. Controlling its
    signal here makes the sub-500ms cancellation race deterministic.
    """
    target=await android.ready()
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        c=CDP(socket)
        before=await c.evaluate('[AgentShellUI.rfb._fbWidth,AgentShellUI.rfb._fbHeight]')
        await android.click('Expand browser')
        for _ in range(30):
            pending=await c.evaluate('(()=>{const r=AgentShellUI.rfb;return r._resizeTimeout!==null && (r.forcedResolutionX!==r._fbWidth || r.forcedResolutionY!==r._fbHeight)})()')
            if pending:break
            await asyncio.sleep(.02)
        assert pending,'No pending fullscreen resize to interrupt'
        try:
            await c.evaluate('AgentShellViewportOptions.keyboardVisible=true')
            await asyncio.sleep(.75)
            paused=await c.evaluate('({frame:[AgentShellUI.rfb._fbWidth,AgentShellUI.rfb._fbHeight],pending:AgentShellUI.rfb._resizeTimeout})')
            assert paused['frame']==before and paused['pending'] is None,(before,paused)
        finally:
            await c.evaluate('AgentShellViewportOptions.keyboardVisible=false')
    metrics=await geometry(android,True)
    assert metrics['framebuffer']!=before,(before,metrics)
    await android.click('Exit fullscreen');await geometry(android)
    return metrics

async def run(serial,apk,output):
    a=Android(serial,output);output.mkdir(parents=True,exist_ok=True)
    assert serial.startswith('emulator-') and (await a.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
    await a.adb('install','-r',str(apk));await a.adb('reverse','tcp:18998','tcp:18998')
    results={};measurements={}
    for name,size in [('foldable','1536x1605'),('phone','1080x1920'),('tablet','1600x2560')]:
        await a.adb('shell','settings','put','system','accelerometer_rotation','0')
        await a.adb('shell','settings','put','system','user_rotation','0');await a.adb('shell','wm','size',size)
        await a.relaunch();await geometry(a)
        await a.click('Expand browser');metrics=await geometry(a,True)
        if any(n.get('text')=='Got it' for n in await a.nodes()):await a.click('Got it')
        views=[n for n in await a.nodes() if n.get('class')=='android.webkit.WebView']
        left,top,right,bottom=map(int,re.findall(r'\d+',views[0].get('bounds')))
        width,height=map(int,size.split('x'))
        assert left==0 and top==0 and right==width and bottom>=height-2,(name,views[0].attrib)
        assert any(n.get('content-desc')=='Fullscreen controls' for n in await a.nodes())
        measurements[name]=metrics;results[name+'_immersive_full_screen']=True;await a.screenshot(name+'-fullscreen')
        await a.adb('shell','settings','put','system','user_rotation','1');await asyncio.sleep(.5)
        metrics=await geometry(a,True);measurements[name+'-landscape']=metrics
        assert metrics['viewport'][0]>metrics['viewport'][1]
        results[name+'_live_rotation_fills_viewer']=True;await a.screenshot(name+'-landscape-fullscreen')
        await a.adb('shell','input','keyevent','KEYCODE_BACK');await geometry(a)
        assert any(n.get('content-desc')=='Expand browser' for n in await a.nodes())
        results[name+'_back_exits_fullscreen']=True
    await a.adb('shell','settings','put','system','user_rotation','0');await asyncio.sleep(.4)
    await a.click('Expand browser');before=await geometry(a,True)
    await a.click('Fullscreen controls');await a.click('Keyboard')
    target=await a.target()
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        c=CDP(socket)
        for _ in range(30):
            after=await c.evaluate('({options:AgentShellViewportOptions,framebuffer:[AgentShellUI.rfb._fbWidth,AgentShellUI.rfb._fbHeight]})')
            if after['options']['keyboardVisible']:break
            await asyncio.sleep(.1)
        await asyncio.sleep(.75)
        after=await c.evaluate('({options:AgentShellViewportOptions,framebuffer:[AgentShellUI.rfb._fbWidth,AgentShellUI.rfb._fbHeight]})')
    assert after['options']['keyboardVisible'] and after['framebuffer']==before['framebuffer'],(before,after)
    results['keyboard_keeps_desktop_geometry']=True;await a.hide_keyboard();await geometry(a,True)
    await a.click('Exit fullscreen');await geometry(a);results['exit_button_restores_layout']=True
    measurements['interrupted-resize']=await interrupted_resize_recovers(a)
    results['interrupted_resize_recovers_when_keyboard_closes']=True
    (output/'results.json').write_text(json.dumps({'checks':results,'measurements':measurements},indent=2)+'\n')
    print(json.dumps(results,indent=2))

async def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');p.add_argument('--apk',type=Path,default=Path('android-native/app/build/outputs/apk/debug/app-debug.apk'));p.add_argument('--output',type=Path,default=Path('/tmp/agentshell-browser-implementation/fullscreen/after'));args=p.parse_args()
    try:await run(args.serial,args.apk,args.output)
    finally:
        a=Android(args.serial,args.output);await a.adb('shell','wm','size','reset');await a.adb('shell','settings','put','system','user_rotation','0')
if __name__=='__main__':asyncio.run(main())
