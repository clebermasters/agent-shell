#!/usr/bin/env python3
"""Real WebView gestures against the owned browser fixture, never a phone.

Requires test_browser_streaming.py --keep-running and the debug-only POC APK.
--probe records failures on the old implementation to establish the baseline.
"""
import argparse
import asyncio
import json
from pathlib import Path
import websockets
from test_android_browser import Android, CDP

METRICS = '''(()=>{const r=AgentShellUI.rfb,s=r._screen,c=r._canvas.getBoundingClientRect();
return {viewport:[innerWidth,innerHeight],frame:[r._fbWidth,r._fbHeight],
canvas:[c.width,c.height],scale:r._display.scale,offset:[s.scrollLeft,s.scrollTop],
keyboard:!!AgentShellViewportOptions.keyboardVisible,connected:AgentShellUI.connected}})()'''

async def touch(c, kind, points):
    await c.call('Input.dispatchTouchEvent', type=kind, touchPoints=points)

def points(width, height, count=3):
    return [{'id':i, 'x':width*(.55+.12*(i%2)), 'y':height*(.45+.12*(i//2))}
            for i in range(count)]

async def drag(c, width, height, dx, dy, count=3, cancel=False):
    start=points(width,height,count)
    await touch(c,'touchStart',start)
    for step in range(1,5):
        await touch(c,'touchMove',[dict(p,x=p['x']+dx*step/4,y=p['y']+dy*step/4) for p in start])
    await touch(c,'touchCancel' if cancel else 'touchEnd',[])

async def run(serial, apk, output, probe):
    a=Android(serial,output);output.mkdir(parents=True,exist_ok=True)
    assert serial.startswith('emulator-') and (await a.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
    await a.adb('install','-r',str(apk));await a.adb('reverse','tcp:18998','tcp:18998')
    checks={};measurements={}
    devices=[('foldable','1536x1605')] if probe else [('phone','1080x1920'),('foldable','1536x1605'),('tablet','1600x2560')]
    for device,size in devices:
        await a.adb('shell','settings','put','system','accelerometer_rotation','0')
        await a.adb('shell','settings','put','system','user_rotation','0')
        await a.adb('shell','wm','size',size);await a.relaunch()
        for fullscreen in [False,True]:
            label=device+('-fullscreen' if fullscreen else '-normal')
            if fullscreen:
                await a.click('Expand browser');await asyncio.sleep(1)
                if any(n.get('text')=='Got it' for n in await a.nodes()):await a.click('Got it')
            target=await a.ready()
            async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                c=CDP(socket)
                await c.evaluate('''AgentShellUI.closeControlbar();AgentShellViewer.fit();
                    if(!AgentShellViewer.mousePosition().enabled) AgentShellViewer.mouse();
                    if(!AgentShellUI.rfb.viewportTestWire){const r=AgentShellUI.rfb,send=r._sendMouse;
                      r.viewportTestWire=[];r._sendMouse=function(...args){this.viewportTestWire.push(args);return send.apply(this,args)}}''')
                await asyncio.sleep(.4)
                initial=await c.evaluate(METRICS);width,height=initial['viewport']
                start=[{'id':0,'x':width*.35,'y':height*.6},{'id':1,'x':width*.65,'y':height*.6}]
                await c.evaluate('AgentShellUI.rfb.viewportTestWire=[]')
                await touch(c,'touchStart',start)
                await touch(c,'touchMove',[dict(start[0],x=width*.2),dict(start[1],x=width*.8)])
                await touch(c,'touchEnd',[])
                pinched=await c.evaluate(METRICS)
                measurements[label+'_pinch']={'before':initial,'after':pinched}
                checks[label+'_two_finger_zoom']=pinched['scale']>initial['scale']*1.5
                checks[label+'_pinch_is_local']=await c.evaluate('AgentShellUI.rfb.viewportTestWire.length===0')

                await c.evaluate('AgentShellViewer.fit();AgentShellViewer.zoom(3)');await asyncio.sleep(.4)
                before=await c.evaluate(METRICS)
                await c.evaluate('AgentShellUI.rfb.viewportTestWire=[]')
                await drag(c,width,height,-width*.2,-height*.2)
                panned=await c.evaluate(METRICS);await asyncio.sleep(.8);settled=await c.evaluate(METRICS)
                checks[label+'_three_finger_pan']=all(panned['offset'][i]>before['offset'][i]+15 for i in [0,1])
                checks[label+'_pan_stays_in_place']=all(abs(settled['offset'][i]-panned['offset'][i])<2 for i in [0,1])
                checks[label+'_pan_is_local']=await c.evaluate('AgentShellUI.rfb.viewportTestWire.length===0')
                measurements[label+'_pan']={'before':before,'after':panned,'settled':settled}
                await a.screenshot(label+'-panned')

                # Real devices add/remove fingers one at a time. Losing a finger
                # must finish the local gesture without a stray remote click.
                start=points(width,height)
                await c.evaluate('AgentShellUI.rfb.viewportTestWire=[]')
                for count in [1,2,3]:await touch(c,'touchStart',start[:count])
                await touch(c,'touchMove',[dict(p,x=p['x']-20,y=p['y']-20) for p in start])
                await touch(c,'touchEnd',start[1:]);await touch(c,'touchEnd',[])
                checks[label+'_partial_lift_is_local']=await c.evaluate('AgentShellUI.rfb.viewportTestWire.length===0 && AgentShellViewer.mousePosition().button===0')

                # Moving the pointer after a local pan must not reveal its old,
                # off-screen location by dragging the viewport back there.
                pointer_before=await c.evaluate(METRICS)
                single=points(width,height,1)
                await touch(c,'touchStart',single)
                await touch(c,'touchMove',[dict(single[0],x=single[0]['x']-8,y=single[0]['y']-8)])
                await touch(c,'touchEnd',[])
                pointer_after=await c.evaluate(METRICS)
                checks[label+'_pointer_move_keeps_panned_view']=all(abs(pointer_after['offset'][i]-pointer_before['offset'][i])<2 for i in [0,1])

                before_keyboard=await c.evaluate(METRICS)
                if fullscreen:
                    await a.click('Fullscreen controls');await a.click('Keyboard')
                else:await a.click('Keyboard')
                for _ in range(40):
                    after_keyboard=await c.evaluate(METRICS)
                    if after_keyboard['keyboard'] and after_keyboard['viewport'][1]<before_keyboard['viewport'][1]-50:break
                    await asyncio.sleep(.1)
                await asyncio.sleep(.8);after_keyboard=await c.evaluate(METRICS)
                checks[label+'_native_keyboard_open']=after_keyboard['keyboard'] and after_keyboard['viewport'][1]<before_keyboard['viewport'][1]-50
                checks[label+'_keyboard_keeps_framebuffer']=after_keyboard['frame']==before_keyboard['frame']
                checks[label+'_keyboard_keeps_zoom']=all(abs(after_keyboard['canvas'][i]-before_keyboard['canvas'][i])<2 for i in [0,1])
                checks[label+'_keyboard_keeps_pan']=all(abs(after_keyboard['offset'][i]-before_keyboard['offset'][i])<2 for i in [0,1])
                measurements[label+'_keyboard']={'before':before_keyboard,'after':after_keyboard}
                await a.screenshot(label+'-keyboard')
                await a.hide_keyboard();await asyncio.sleep(.8)
                closed=await c.evaluate(METRICS)
                checks[label+'_keyboard_close_keeps_zoom']=all(abs(closed['canvas'][i]-before_keyboard['canvas'][i])<2 for i in [0,1])

                await c.evaluate('AgentShellViewer.fit()');await asyncio.sleep(.2)
                fit=await c.evaluate(METRICS)
                checks[label+'_fit_resets_view']=all(v==0 for v in fit['offset']) and abs(fit['canvas'][0]-width)<2
                await c.evaluate('AgentShellUI.rfb.viewportTestWire=[]')
                await drag(c,width,height,-30,-30)
                checks[label+'_three_fingers_at_fit_are_local']=await c.evaluate('AgentShellUI.rfb._screen.scrollLeft===0 && AgentShellUI.rfb._screen.scrollTop===0 && AgentShellUI.rfb.viewportTestWire.length===0')
                await drag(c,width,height,0,-80,count=2)
                checks[label+'_two_finger_page_scroll']=await c.evaluate('AgentShellUI.rfb.viewportTestWire.some(a=>(a[2]&24)!==0)')
                await c.evaluate('AgentShellViewer.zoom(2);AgentShellUI.rfb.viewportTestWire=[]')
                await drag(c,width,height,-30,-30,cancel=True)
                single=points(width,height,1)
                await touch(c,'touchStart',single);await touch(c,'touchEnd',[])
                checks[label+'_pan_cancel_releases_input']=await c.evaluate('AgentShellViewer.mousePosition().button===0 && AgentShellUI.rfb.viewportTestWire.some(a=>a[2]===1) && AgentShellUI.rfb.viewportTestWire.at(-1)[2]===0')
                await c.evaluate('AgentShellViewer.fit()')
                checks[label+'_connection_healthy']=await c.evaluate('AgentShellUI.connected && document.getElementById("noVNC_fallback_errormsg").innerText===""')
            if fullscreen:await a.click('Exit fullscreen')
    (output/'results.json').write_text(json.dumps({'checks':checks,'measurements':measurements},indent=2)+'\n')
    failed=[key for key,value in checks.items() if not value]
    print(json.dumps({'passed':len(checks)-len(failed),'failed':failed,'output':str(output)},indent=2))
    if not probe:assert not failed,failed

async def direct_touch(serial, apk, output):
    """Ensure the shared scale changes retain the existing direct-touch controls."""
    a=Android(serial,output);output.mkdir(parents=True,exist_ok=True)
    assert serial.startswith('emulator-') and (await a.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
    await a.adb('install','-r',str(apk));await a.adb('reverse','tcp:18998','tcp:18998')
    await a.adb('shell','wm','size','1536x1605');await a.relaunch()
    await a.click('Expand browser');await asyncio.sleep(1)
    if any(n.get('text')=='Got it' for n in await a.nodes()):await a.click('Got it')
    target=await a.ready();checks={}
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        c=CDP(socket)
        await c.evaluate('''AgentShellUI.closeControlbar();AgentShellViewer.fit();
            if(AgentShellViewer.mousePosition().enabled) AgentShellViewer.mouse();
            AgentShellViewer.zoom(3)''')
        await asyncio.sleep(.3);before=await c.evaluate(METRICS);width,height=before['viewport']
        await drag(c,width,height,-width*.25,-height*.25,count=2)
        after=await c.evaluate(METRICS)
        checks['direct_two_finger_pan']=all(after['offset'][i]>before['offset'][i]+15 for i in [0,1])
        await a.click('Fullscreen controls');await a.click('Keyboard')
        for _ in range(40):
            keyboard=await c.evaluate(METRICS)
            if keyboard['keyboard']:break
            await asyncio.sleep(.1)
        await asyncio.sleep(.8);keyboard=await c.evaluate(METRICS)
        checks['direct_keyboard_shown']=keyboard['keyboard']
        checks['direct_keyboard_preserves_zoom']=all(abs(after['canvas'][i]-keyboard['canvas'][i])<2 for i in [0,1])
        checks['direct_keyboard_preserves_pan']=all(abs(after['offset'][i]-keyboard['offset'][i])<2 for i in [0,1])
        await a.screenshot('direct-touch-keyboard');await a.hide_keyboard();await asyncio.sleep(.7)
        await c.evaluate('AgentShellViewer.fit();AgentShellViewer.mouse()')
        fit=await c.evaluate(METRICS)
        checks['direct_fit_restored']=fit['offset']==[0,0] and abs(fit['canvas'][0]-width)<2
    (output/'results.json').write_text(json.dumps(checks,indent=2)+'\n')
    print(json.dumps(checks,indent=2));assert all(checks.values()),checks

async def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560')
    parser.add_argument('--apk',type=Path,default=Path('android-native/app/build/outputs/apk/debug/app-debug.apk'))
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--probe',action='store_true')
    parser.add_argument('--direct-only',action='store_true');args=parser.parse_args()
    try:
        if args.direct_only:await direct_touch(args.serial,args.apk,args.output)
        else:await run(args.serial,args.apk,args.output,args.probe)
    finally:
        a=Android(args.serial,args.output);await a.hide_keyboard()
        await a.adb('shell','wm','size','reset');await a.adb('shell','settings','put','system','user_rotation','0')

if __name__=='__main__':asyncio.run(main())
