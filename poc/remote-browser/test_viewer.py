#!/usr/bin/env python3
"""Assert real KasmVNC frames and input in the loopback-only Android fixture."""
import asyncio
import json
import subprocess
import urllib.request
from pathlib import Path
import websockets

OUTPUT = Path('/tmp/agentshell-browser-implementation')

class CDP:
    def __init__(self, socket): self.socket = socket; self.sequence = 0
    async def call(self, method, **params):
        self.sequence += 1; identifier = self.sequence
        await self.socket.send(json.dumps({'id': identifier, 'method': method, 'params': params}))
        while True:
            response = json.loads(await asyncio.wait_for(self.socket.recv(), 30))
            if response.get('id') == identifier:
                if 'error' in response: raise RuntimeError(response['error'])
                return response.get('result', {})
    async def evaluate(self, expression):
        response = await self.call('Runtime.evaluate', expression=expression, returnByValue=True, awaitPromise=True)
        if 'exceptionDetails' in response: raise RuntimeError(response['exceptionDetails'])
        return response['result'].get('value')
    async def tap(self, remote_x, remote_y):
        point = await self.evaluate(f'''(()=>{{const c=[...document.querySelectorAll('canvas')].find(c=>c.width===960),r=c.getBoundingClientRect();return [r.x+{remote_x}/c.width*r.width,r.y+{remote_y}/c.height*r.height]}})()''')
        await self.call('Input.dispatchTouchEvent', type='touchStart', touchPoints=[{'x': point[0], 'y': point[1]}])
        await self.call('Input.dispatchTouchEvent', type='touchEnd', touchPoints=[])

async def main():
    targets = json.load(urllib.request.urlopen('http://127.0.0.1:18995/json'))
    target = next(t for t in targets if t['url'].startswith('http://127.0.0.1:18994/browser/poc/'))
    results = {}
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        cdp = CDP(socket); await cdp.call('Page.reload', ignoreCache=True)
        for _ in range(40):
            if await cdp.evaluate('!!window.AgentShellUI?.connected && [...document.querySelectorAll("canvas")].some(c=>c.width===960 && c.getBoundingClientRect().width>100)'): break
            await asyncio.sleep(.25)
        else: raise AssertionError('No visible remote frame')
        results['visible_remote_frame'] = True
        await cdp.tap(430, 425)
        await cdp.evaluate('window.AgentShellUI.rfb.sendKey(65507,"ControlLeft",true);window.AgentShellViewer.key(97);window.AgentShellUI.rfb.sendKey(65507,"ControlLeft",false);window.AgentShellViewer.key(65288)')
        await cdp.evaluate('window.AgentShellUI.showVirtualKeyboard()')
        await cdp.call('Input.insertText', text='ação coração São Paulo')
        await asyncio.sleep(.5)
        events = json.load(urllib.request.urlopen('http://127.0.0.1:18993/events'))
        results['portuguese_ime_commit'] = bool(events and events[-1]['text'] == 'ação coração São Paulo')
        assert results['portuguese_ime_commit'], events[-2:]
        await cdp.evaluate('window.AgentShellViewer.key(65288);window.AgentShellViewer.key(65293)')
        await asyncio.sleep(.3)
        events = json.load(urllib.request.urlopen('http://127.0.0.1:18993/events'))
        results['backspace_enter'] = events[-1]['key'] == 'Enter' and events[-1]['text'] == 'ação coração São Paul'
        assert results['backspace_enter'], events[-2:]
        # Both timestamps and decoded-pixel observation are in the viewer clock.
        latency = await cdp.evaluate('''(async()=>{const c=[...document.querySelectorAll('canvas')].find(c=>c.width===960),ctx=c.getContext('2d'),values=[];
          for(let i=0;i<100;i++){const before=[...ctx.getImageData(100,300,1,1).data].join(),start=performance.now();window.AgentShellViewer.key(97);
            await new Promise((resolve,reject)=>{const tick=()=>{if([...ctx.getImageData(100,300,1,1).data].join()!==before)resolve();else if(performance.now()-start>3000)reject(Error('Frame response missing'));else requestAnimationFrame(tick)};requestAnimationFrame(tick)});
            values.push(performance.now()-start)}return values})()''')
        latency.sort(); results['latency'] = {'samples':len(latency),'p50_ms':round(latency[49],1),'p95_ms':round(latency[94],1),'network':'owned emulator loopback; not physical Wi-Fi'}
        width=await cdp.evaluate('window.AgentShellUI.rfb._canvas.getBoundingClientRect().width')
        await cdp.evaluate('window.AgentShellViewer.zoom(1.5)')
        await asyncio.sleep(.2)
        results['viewer_zoom']=await cdp.evaluate(f'window.AgentShellUI.rfb._canvas.getBoundingClientRect().width>{width}*1.4')
        assert results['viewer_zoom']
        await cdp.tap(430,425);await cdp.evaluate('window.AgentShellViewer.key(122)');await asyncio.sleep(.2)
        events=json.load(urllib.request.urlopen('http://127.0.0.1:18993/events'))
        results['zoomed_pointer_coordinates']=events[-1]['text'].count('z')==1;assert results['zoomed_pointer_coordinates']
        count=len(events)
        await cdp.evaluate('''(()=>{const c=AgentShellUI.rfb._canvas;for(const [type,magnitude] of [['gesturestart',100],['gesturemove',150],['gestureend',150]]) c.dispatchEvent(new CustomEvent(type,{bubbles:true,cancelable:true,detail:{type:'pinch',magnitudeX:magnitude,magnitudeY:0,clientX:200,clientY:200}}))})()''')
        await asyncio.sleep(.2)
        results['pinch_does_not_send_remote_keys']=len(json.load(urllib.request.urlopen('http://127.0.0.1:18993/events')))==count
        assert results['pinch_does_not_send_remote_keys']
        await cdp.evaluate('''(()=>{const c=AgentShellUI.rfb._canvas;for(const [type,magnitude] of [['gesturestart',0],['gesturemove',-150],['gestureend',-150]]) c.dispatchEvent(new CustomEvent(type,{bubbles:true,cancelable:true,detail:{type:'twodrag',magnitudeX:magnitude,magnitudeY:magnitude,clientX:200,clientY:200}}))})()''')
        results['zoomed_viewer_pan']=await cdp.evaluate('AgentShellUI.rfb._screen.scrollLeft>0 && AgentShellUI.rfb._screen.scrollTop>0')
        assert results['zoomed_viewer_pan']
        await cdp.evaluate('AgentShellViewer.fit()')
        results['fit_restores_viewport']=await cdp.evaluate(f'Math.abs(AgentShellUI.rfb._canvas.getBoundingClientRect().width-{width})<2 && AgentShellUI.rfb._screen.scrollTop===0')
        assert results['fit_restores_viewport']
        screenshot = await cdp.call('Page.captureScreenshot')
        import base64
        (OUTPUT/'poc-viewer.png').write_bytes(base64.b64decode(screenshot['data']))
    (OUTPUT/'poc-results.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))

if __name__ == '__main__': asyncio.run(main())
