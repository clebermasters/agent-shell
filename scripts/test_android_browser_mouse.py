#!/usr/bin/env python3
"""Verify touch mouse gestures against real remote Chrome mouse events."""
import argparse
import asyncio
import json
from pathlib import Path
import urllib.request
import websockets
from test_android_browser import Android, CDP

URL='http://127.0.0.1:19003/mouse-events'
async def events():
    return await asyncio.to_thread(lambda:json.load(urllib.request.urlopen(URL,timeout=3)))
async def reset():
    await asyncio.to_thread(lambda:urllib.request.urlopen(urllib.request.Request(URL+'/reset',data=b''),timeout=3).read())
async def wait_events(predicate, timeout=3):
    deadline=asyncio.get_running_loop().time()+timeout
    while True:
        trace=await events()
        if predicate(trace) or asyncio.get_running_loop().time()>=deadline:return trace
        await asyncio.sleep(.1)

async def run(serial, output):
    a=Android(serial,output);output.mkdir(parents=True,exist_ok=True)
    assert serial.startswith('emulator-') and (await a.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
    await a.adb('reverse','tcp:18998','tcp:18998');await a.adb('shell','wm','size','1536x1605');target=await a.relaunch()
    results={}
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        c=CDP(socket)
        for _ in range(30):
            if await c.evaluate('!!AgentShellViewer.mousePosition()'):break
            await asyncio.sleep(.1)
        await c.evaluate('if(AgentShellViewer.mousePosition().enabled) AgentShellViewer.mouse()')
        await c.evaluate('AgentShellUI.rfb._canvas.requestPointerLock=()=>{throw Error("Pointer lock must never be requested")};AgentShellUI.closeControlbar()')
        await a.hide_keyboard();await a.click('Browser interaction options');await asyncio.sleep(.5)
        point=await c.evaluate('''(()=>{const r=document.getElementById('noVNC_game_mode_button').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()''')
        x,y=await a.viewer_point(*point);await a.adb('shell','input','tap',str(x),str(y));await asyncio.sleep(.3)
        assert await c.evaluate('AgentShellViewer.mousePosition().enabled && !AgentShellUI.rfb.pointerRelative')
        results['native_icon_enables_absolute_touch_mouse']=True
        await c.evaluate('AgentShellUI.closeControlbar();AgentShellUI.hideVirtualKeyboard();AgentShellUI.rfb.focusOnClick=false')
        await a.hide_keyboard();await c.evaluate('AgentShellViewer.key(65307)');await reset()
        before=await c.evaluate('AgentShellViewer.mousePosition()')
        rect=await c.evaluate('''(()=>{const r=AgentShellUI.rfb._canvas.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,viewport:innerWidth}})()''')
        x1,y1=await a.viewer_point(rect['x']+rect['w']*.8,rect['y']+rect['h']*.7,rect['viewport'])
        x2,y2=await a.viewer_point(rect['x']+rect['w']*.8-40,rect['y']+rect['h']*.7-30,rect['viewport'])
        await a.adb('shell','input','swipe',str(x1),str(y1),str(x2),str(y2),'350');await asyncio.sleep(.5)
        after=await c.evaluate('AgentShellViewer.mousePosition()');trace=await events()
        assert after['x']<before['x']-30 and after['y']<before['y']-30 and not any(e['type']=='mousedown' for e in trace)
        moves=[e for e in trace if e['type']=='mousemove'];assert moves and any(abs(e['x']-after['x'])<2 and abs(e['y']-after['y'])<2 for e in moves), (after,trace[-5:])
        results['native_drag_moves_remote_cursor_without_click']=True
        async def touch(kind,points):
            await c.call('Input.dispatchTouchEvent',type=kind,touchPoints=[dict(point,id=i+1) for i,point in enumerate(points)])
            # Allow WebView to deliver movement before a subsequent touch-end;
            # back-to-back DevTools events can coalesce the only move away.
            await asyncio.sleep(.08)
        p={'x':rect['x']+rect['w']*.8,'y':rect['y']+rect['h']*.7}
        q={'x':p['x']-40,'y':p['y']}
        await reset();await touch('touchStart',[p]);await touch('touchEnd',[]);await asyncio.sleep(.3)
        trace=await events();assert any(e['type']=='mousedown' and e['button']==0 and abs(e['x']-after['x'])<2 for e in trace)
        results['tap_clicks_at_cursor_not_finger']=True
        await c.evaluate('''window.touchTrace=[];window.mouseWire=[];const oldTouch=AgentShellTouchMouse.prototype.handle;AgentShellTouchMouse.prototype.handle=function(event){oldTouch.call(this,event);touchTrace.push({type:event.type,points:[...event.touches].map(p=>[p.identifier,p.clientX,p.clientY]),gesture:this.gesture?{...this.gesture}:null})};const oldSend=AgentShellUI.rfb._sendMouse;AgentShellUI.rfb._sendMouse=function(...args){mouseWire.push(args);return oldSend.apply(this,args)}''')
        await reset();await touch('touchStart',[p,q])
        # Exercise a continuous drag across multiple frames, as on a device.
        # This verifies wheel delivery without depending on one synthetic tick.
        for distance in [10,20,40,60,80,100]:
            await touch('touchMove',[dict(p,y=p['y']-distance),dict(q,y=q['y']-distance)])
        await touch('touchEnd',[]);await asyncio.sleep(.3)
        # Remote Chrome can deliver a wheel event after its next compositor
        # frame. Wait for that event without repeating the input gesture.
        trace=await wait_events(lambda trace:any(e['type']=='wheel' and e.get('deltaY',0)>0 for e in trace))
        assert any(e['type']=='wheel' and e.get('deltaY',0)>0 for e in trace),(trace,await c.evaluate('({touchTrace,mouseWire,position:AgentShellViewer.mousePosition(),scale:AgentShellUI.rfb._display.scale})'))
        results['two_finger_scroll']=True
        await reset();await touch('touchStart',[p,q]);await touch('touchEnd',[]);await asyncio.sleep(.3)
        assert any(e['type']=='mousedown' and e['button']==2 for e in await events());results['two_finger_tap_right_click']=True
        # Chrome opens its native context menu asynchronously after the page's
        # mousedown event. Wait for that popup before dismissing it with Escape.
        await asyncio.sleep(.8)
        await c.evaluate('AgentShellViewer.key(65307)')
        await asyncio.sleep(.4)
        await reset();await touch('touchStart',[p]);await asyncio.sleep(.6);await touch('touchMove',[dict(p,x=p['x']-10)]);await touch('touchCancel',[]);await asyncio.sleep(.3)
        trace=await events();assert any(e['type']=='mousemove' and e['buttons']==1 for e in trace) and any(e['type']=='mouseup' and e['button']==0 for e in trace)
        assert await c.evaluate('AgentShellViewer.mousePosition().button===0');results['hold_drag_and_cancel_release_button']=True
        await c.evaluate('AgentShellViewer.zoom(1.5)');await reset();await touch('touchStart',[p]);await touch('touchMove',[dict(p,x=p['x']-20)]);await touch('touchEnd',[]);await asyncio.sleep(.3)
        position=await c.evaluate('AgentShellViewer.mousePosition()');trace=await events()
        assert any(e['type']=='mousemove' and abs(e['x']-position['x'])<2 and abs(e['y']-position['y'])<2 for e in trace), (position,trace[-4:])
        results['zoomed_pointer_coordinates']=True
        assert await c.evaluate('document.getElementById("noVNC_fallback_errormsg").innerText==="" && AgentShellUI.connected')
        await a.screenshot('touch-mouse')
        await c.evaluate('AgentShellViewer.mouse()');assert await c.evaluate('!AgentShellViewer.mousePosition().enabled');results['mode_switch_restores_direct_touch']=True
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2))

async def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');p.add_argument('--output',type=Path,default=Path('/tmp/agentshell-browser-implementation/touch-mouse'))
    args=p.parse_args()
    try:await run(args.serial,args.output)
    finally:await Android(args.serial,args.output).adb('shell','wm','size','reset')
if __name__=='__main__':asyncio.run(main())
