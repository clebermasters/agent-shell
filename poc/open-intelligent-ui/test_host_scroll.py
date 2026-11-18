#!/usr/bin/env python3
"""Check the production bounded iframe's scroll bridge in an isolated Chromium.

Requires the same websockets dependency as the native browser harness. No daemon,
Android device, account, or published widgets are used.
"""
import argparse
import asyncio
import functools
import http.server
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import urllib.request

import websockets
from test_native import Page

ASSETS = Path(__file__).resolve().parents[2] / 'android-native/app/src/main/assets/ui'
COMMANDS = """<script>
addEventListener('message', event => {
  if (event.source !== parent || !event.data?.fixture) return;
  const {action,value} = event.data;
  if (action === 'scroll') scrollTo(0,value === 'end' ? document.documentElement.scrollHeight : value);
  if (action === 'height') document.getElementById('content').style.height = value+'px';
  if (action === 'top') document.getElementById('content').style.top = value+'px';
  if (action === 'overflow') document.documentElement.style.overflowY = value;
  if (action === 'focus') document.getElementById('field').focus({preventScroll:true});
  if (action === 'blur') document.getElementById('field').blur();
  if (action === 'send') parent.postMessage(value,'*');
  if (action === 'probe') parent.postMessage({type:'fixture-probe',scrollY,
    viewport:innerHeight,width:innerWidth,bottom:document.getElementById('content').getBoundingClientRect().bottom},'*');
});
</script>"""


def document(css='', runtime=False, field=False):
    helper = '<script>'+ (ASSETS/'runtime.js').read_text() + '</script>' if runtime else ''
    control = '<input id="field" aria-label="Synthetic input">' if field else ''
    return ('<!doctype html><html><head><style>body{padding:0;margin:0}#content{height:2600px}'
            + css + '</style></head><body><div id="content">Synthetic content'+control+'</div>'
            + helper + COMMANDS + '</body></html>')


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


async def verify(endpoint, url):
    async with websockets.connect(endpoint, max_size=16*1024*1024) as connection:
        page = Page(connection)
        await page.call('Page.enable')
        await page.call('Runtime.enable')
        await page.call('Emulation.setEmulatedMedia',{'features':[{'name':'prefers-reduced-motion','value':'reduce'}]})

        async def evaluate(expression):
            result = await page.call('Runtime.evaluate', {'expression':expression,'returnByValue':True,'awaitPromise':True})
            assert 'exceptionDetails' not in result, result
            return result.get('result',{}).get('value')

        async def until(expression):
            for _ in range(200):
                result = await evaluate(expression)
                if result:
                    return result
                await asyncio.sleep(.025)
            raise AssertionError('Timed out: '+expression)

        await page.call('Page.navigate', {'url':url})
        await until('typeof mountWidget === "function"')
        await evaluate("""window.nativeCalls=[];window.childMessages=[];
          window.WidgetHost={postMessage:value=>nativeCalls.push(JSON.parse(value))};
          addEventListener('message',event=>childMessages.push(event.data));
          window.lastScroll=()=>nativeCalls.filter(value=>value.type==='scroll').at(-1);
          window.lastHeight=()=>nativeCalls.filter(value=>value.type==='resize').at(-1)?.value;
        """)

        async def mount_widget(html):
            await evaluate('nativeCalls=[];childMessages=[];mountWidget('+json.dumps({'id':'scroll-fixture','html':html})+')')
            await until('nativeCalls.some(value=>value.type==="ready") && lastHeight() !== undefined')
            assert await evaluate('nativeCalls[0]') == {'type':'scroll','canScrollUp':False,'canScrollDown':False}

        async def command(action, value=None):
            await evaluate('frame.contentWindow.postMessage('+json.dumps({'fixture':True,'action':action,'value':value})+',"*")')

        async def probe():
            await evaluate('childMessages=childMessages.filter(value=>value.type!=="fixture-probe")')
            await command('probe')
            return await until('childMessages.find(value=>value.type==="fixture-probe")')

        await mount_widget(document(runtime=True))
        await until('lastHeight()===1600 && lastScroll()?.canScrollDown===true && lastScroll()?.canScrollUp===false')
        await command('scroll','end')
        await until('lastScroll()?.canScrollUp===true && lastScroll()?.canScrollDown===false')
        bottom = await probe()
        assert bottom['scrollY'] > 0 and bottom['bottom'] <= bottom['viewport']+1, bottom
        assert await evaluate('childMessages.filter(value=>value.type==="widget-ready").length') == 1
        assert await evaluate('childMessages.filter(value=>value.type==="widget-resize").every(value=>Number.isInteger(value.mount))')
        assert await evaluate('nativeCalls.filter(value=>value.type==="resize").length') == 1

        await command('height',300)
        await until('lastHeight()===300 && !lastScroll()?.canScrollUp && !lastScroll()?.canScrollDown')
        await command('height',2700)
        await until('lastHeight()===1600 && lastScroll()?.canScrollDown===true')
        await page.call('Emulation.setDeviceMetricsOverride',{'width':280,'height':800,'deviceScaleFactor':1,'mobile':True})
        assert (await probe())['width'] == 280
        await command('scroll','end')
        await until('lastScroll()?.canScrollUp===true && lastScroll()?.canScrollDown===false')

        # Changes in overflow alone do not resize the body's border box.
        await command('overflow','hidden')
        await until('lastScroll()?.canScrollUp===false && lastScroll()?.canScrollDown===false')
        await command('overflow','auto')
        await until('lastScroll()?.canScrollUp===true')

        # Out-of-flow content can change document scrollability without body resize.
        await mount_widget(document('body{height:200px}#content{position:absolute;top:600px;height:100px}'))
        await until('lastHeight()===200 && lastScroll()?.canScrollDown===true')
        await command('top',100)
        await until('!lastScroll()?.canScrollUp && !lastScroll()?.canScrollDown')
        old_mount = await evaluate('mount')
        await mount_widget(document('#content{height:200px}'))
        assert await evaluate('lastHeight()') == 200
        calls = await evaluate('nativeCalls.length')
        # A WindowProxy survives srcdoc replacement. Reject stale queued bridge data.
        await command('send',{'type':'widget-scroll','mount':old_mount,'canScrollUp':True,'canScrollDown':True})
        await command('send',{'type':'widget-resize','mount':old_mount,'height':1500})
        await command('send',{'type':'widget-focus-reveal','mount':old_mount,'top':100,'bottom':144})
        await command('send',{'type':'widget-scroll','mount':old_mount+1,'canScrollUp':'yes','canScrollDown':True})
        await evaluate('window.postMessage({type:"widget-scroll",mount,canScrollUp:true,canScrollDown:true},"*")')
        await asyncio.sleep(.1)
        assert await evaluate('nativeCalls.length') == calls
        assert await evaluate('lastHeight()') == 200

        # IME layout can move an already focused field after the original focusin.
        await mount_widget(document('#content{height:900px;position:relative}#field{position:absolute;bottom:12px;height:44px}',field=True))
        await asyncio.sleep(.25)  # Finish the canvas reveal before comparing geometry.
        await command('focus')
        await evaluate('requestWidgetFocus()')
        first = await until('nativeCalls.find(value=>value.type==="focus-reveal")')
        await command('height',600)
        await until('lastHeight()===600')
        await evaluate('requestWidgetFocus()')
        fresh = await until('nativeCalls.filter(value=>value.type==="focus-reveal").length===2 && nativeCalls.filter(value=>value.type==="focus-reveal").at(-1)')
        assert abs(first['top']-fresh['top']-300) < 1, (first,fresh)
        assert abs(first['bottom']-fresh['bottom']-300) < 1, (first,fresh)
        await command('blur')
        await evaluate('requestWidgetFocus()')
        await asyncio.sleep(.05)
        assert await evaluate('nativeCalls.filter(value=>value.type==="focus-reveal").length') == 2

        # Viewport-sized content keeps the safety cap and exposes its overflow.
        await mount_widget(document('body{padding:20px}#content{height:100vh}'))
        await until('lastHeight()===1600 && lastScroll()?.canScrollDown===true')
        await command('scroll','end')
        await until('lastScroll()?.canScrollUp===true && lastScroll()?.canScrollDown===false')
        viewport_bottom = await probe()
        assert viewport_bottom['bottom'] <= viewport_bottom['viewport']+1, viewport_bottom
        resize_count = await evaluate('nativeCalls.filter(value=>value.type==="resize").length')
        await asyncio.sleep(.1)
        assert await evaluate('nativeCalls.filter(value=>value.type==="resize").length') == resize_count
        return {'bounded_tall_document_bottom_reachable':True,'scroll_boundaries_reported':True,
                'dynamic_grow_shrink_and_absolute_overflow':True,'viewport_resize':True,
                'root_overflow_changes':True,'single_runtime_layout_owner':True,
                'mount_reset_and_stale_or_invalid_messages_rejected':True,
                'focused_field_remeasured_after_layout':True,'viewport_units_bounded_and_scrollable':True}


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-path', default=shutil.which('chromium') or shutil.which('google-chrome'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not args.browser_path:
        parser.error('Chromium is required; pass --browser-path')
    handler = functools.partial(QuietHandler,directory=str(ASSETS))
    server = http.server.ThreadingHTTPServer(('127.0.0.1',0),handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix='agentshell-host-scroll-',ignore_cleanup_errors=True) as profile:
            process = subprocess.Popen([args.browser_path,'--headless','--no-sandbox','--disable-gpu',
                '--disable-extensions','--no-first-run','--no-default-browser-check',
                '--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                active_port = Path(profile)/'DevToolsActivePort'
                for _ in range(200):
                    if active_port.exists():
                        break
                    if process.poll() is not None:
                        raise RuntimeError('Chromium exited before opening its debugging endpoint')
                    await asyncio.sleep(.025)
                port = active_port.read_text().splitlines()[0]
                with urllib.request.urlopen('http://127.0.0.1:'+port+'/json',timeout=5) as response:
                    targets = json.load(response)
                endpoint = next(target['webSocketDebuggerUrl'] for target in targets if target['type']=='page')
                report = await verify(endpoint,f'http://127.0.0.1:{server.server_port}/host.html')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill();process.wait(timeout=5)
    finally:
        server.shutdown();server.server_close()
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__ == '__main__':
    asyncio.run(main())
