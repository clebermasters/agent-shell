#!/usr/bin/env python3
"""Private KasmVNC/WebView experiment; synthetic pages and a temporary profile only.

Requires aiohttp for the test harness, not for the production runtime.
Start the owned runtime first; its auth.json is supplied with --runtime.
"""
import argparse
import asyncio
import json
from pathlib import Path
from aiohttp import web, ClientSession, BasicAuth, WSMsgType

ROOT = Path(__file__).resolve().parents[2]
ORIGIN = 'http://127.0.0.1:18994'
PAGE = '''<!doctype html><meta charset="utf-8"><style>
body{font:24px sans-serif;background:#f5f7fa;padding:24px}input,button{font:24px sans-serif;padding:16px;margin:8px 0}
#marker{height:120px;background:#ee3344}label{display:block}section{height:1200px}</style>
<h1>AgentShell browser fixture</h1><div id="marker"></div><label>Portuguese text <input id="text"></label>
<button onclick="window.open('/popup','fixture','width=500,height=500')">Open popup</button><label>Upload <input type="file"></label>
<section>Scroll and drag fixture</section><h2>SCROLL_TARGET</h2>
<script>let tick=0;document.addEventListener('keydown',e=>{document.querySelector('#marker').style.background=++tick%2?'#22aa44':'#ee3344';report(e.key)});
document.querySelector('#text').addEventListener('input',()=>report('input'));
function report(key){fetch('/events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key,text:document.querySelector('#text').value,tick})})}</script>'''

async def run(runtime):
    auth = json.loads((runtime / 'auth.json').read_text())
    events = []
    async def page(request): return web.Response(text=PAGE, content_type='text/html')
    async def event(request): events.append(await request.json()); return web.json_response({'ok': True})
    async def state(request): return web.json_response(events)
    pages = web.Application(); pages.router.add_get('/', page)
    pages.router.add_get('/popup', lambda _: web.Response(text='<h1>POPUP_TARGET</h1>', content_type='text/html'))
    pages.router.add_post('/events', event); pages.router.add_get('/events', state)
    pr = web.AppRunner(pages, access_log=None); await pr.setup(); await web.TCPSite(pr, '127.0.0.1', 18993).start()
    upstream = ClientSession(auth=BasicAuth(auth['user'], auth['password']))
    async def bootstrap(request):
        body = await request.post()
        if body.get('grant') != 'poc-only': raise web.HTTPUnauthorized()
        response = web.HTTPSeeOther('/browser/poc/view/vnc.html?autoconnect=true&path=browser/poc/socket&resize=scale&enable_ime=true&idle_disconnect=0&reconnect=false&clipboard_up=false&clipboard_down=false&enable_webrtc=false')
        response.set_cookie('browser-poc', 'fixture-viewer', httponly=True, samesite='Strict', path='/browser/poc/')
        return response
    async def socket(request):
        if request.cookies.get('browser-poc') != 'fixture-viewer' or request.headers.get('Origin') != ORIGIN: raise web.HTTPUnauthorized()
        down = web.WebSocketResponse(protocols=['binary']); await down.prepare(request)
        async with upstream.ws_connect('http://127.0.0.1:18992/websockify', protocols=['binary'], origin='http://127.0.0.1:18992') as up:
            async def pump(source, target):
                async for msg in source:
                    if msg.type == WSMsgType.BINARY: await target.send_bytes(msg.data)
                    elif msg.type == WSMsgType.TEXT: await target.send_str(msg.data)
                    else: break
            jobs = [asyncio.create_task(pump(down, up)), asyncio.create_task(pump(up, down))]
            _, pending = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
            for job in pending: job.cancel()
        await down.close(); return down
    async def asset(request):
        if request.cookies.get('browser-poc') != 'fixture-viewer': raise web.HTTPUnauthorized()
        name = request.match_info['path']
        base = Path('/usr/share/kasmvnc/www').resolve(); path = (base/name).resolve()
        if not path.is_relative_to(base) or not path.is_file(): raise web.HTTPNotFound()
        data = path.read_bytes()
        if name == 'vnc.html':
            data = data.replace(b'</head>', b'<script defer src="viewer-bridge.js"></script><style>html,body,#noVNC_container{height:100vh!important;overflow:hidden}.noVNC_logo{display:none}body{background:#111827}</style></head>')
        if name.startswith('assets/ui-') and name.endswith('.js'):
            # Android WebView omits the optional browser Permissions API.
            data = data.replace(b'Nb()&&navigator.permissions.query(', b'Nb()&&navigator.permissions&&navigator.permissions.query(')
            data += b'\nwindow.AgentShellUI=o;\n'
        import mimetypes
        return web.Response(body=data, content_type=mimetypes.guess_type(name)[0] or 'application/octet-stream')
    async def bridge(request):
        if request.cookies.get('browser-poc') != 'fixture-viewer': raise web.HTTPUnauthorized()
        return web.Response(text=(ROOT/'deployment/browser/viewer-bridge.js').read_text(), content_type='text/javascript')
    app = web.Application(); app.router.add_post('/browser/poc/bootstrap', bootstrap)
    app.router.add_get('/browser/poc/socket', socket); app.router.add_get('/browser/poc/view/viewer-bridge.js', bridge)
    app.router.add_get('/browser/poc/view/{path:.*}', asset)
    runner = web.AppRunner(app, access_log=None); await runner.setup(); await web.TCPSite(runner, '127.0.0.1', 18994).start()
    print('Private synthetic browser fixture ready on loopback.', flush=True)
    try: await asyncio.Event().wait()
    finally: await runner.cleanup(); await pr.cleanup(); await upstream.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--runtime', type=Path, required=True)
    asyncio.run(run(parser.parse_args().runtime))
