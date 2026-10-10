#!/usr/bin/env python3
"""Exercise the production host policy with HTML that precedes its explicit head."""
import argparse
import asyncio
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlparse
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[2]

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runtime', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.runtime.read_text())
    requests = []
    async with async_playwright() as p:
        browser = await p.chromium.launch(executable_path='/bin/google-chrome', headless=True, args=['--no-sandbox'])
        page = await browser.new_page()
        async def assets(route):
            root = ROOT/'android-native/app/src/main/assets'
            target = root/urlparse(route.request.url).path.removeprefix('/assets/')
            assert target.resolve().is_relative_to(root.resolve())
            await route.fulfill(path=str(target), content_type=mimetypes.guess_type(target)[0] or 'application/octet-stream', headers={'Access-Control-Allow-Origin':'*'})
        async def blocked(route):
            requests.append(route.request.url)
            await route.abort()
        await page.context.route('https://appassets.androidplatform.net/**', assets)
        await page.context.route('https://blocked.example/**', blocked)
        await page.goto(f"http://127.0.0.1:{config['assetsPort']}/ui/host.html")
        cdp = await page.context.new_cdp_session(page)
        await cdp.send('Runtime.enable')
        async def evaluate(expression, context=None):
            params = dict(expression=expression, returnByValue=True, userGesture=False)
            if context is not None: params['contextId'] = context
            result = await cdp.send('Runtime.evaluate', params)
            assert 'exceptionDetails' not in result, result
            return result['result'].get('value')
        await evaluate("window.nativeCalls=[];window.WidgetHost={postMessage:x=>nativeCalls.push(JSON.parse(x))}")
        html = '''<script src="https://blocked.example/pre-head.js"></script>
        <!doctype html><html><head></head><body>
        <h3>Policy probe</h3><img src="https://blocked.example/tracker.png">
        <button id="ask">Ask agent</button><script>
        window.fetchBlocked=false;
        fetch('https://blocked.example/data').catch(()=>window.fetchBlocked=true);
        document.querySelector('#ask').onclick=()=>parent.postMessage({type:'send-prompt',text:'Explicit user action'},'*');
        </script></body></html>'''
        await evaluate('mountWidget({id:"security-probe",title:"Probe",html:'+json.dumps(html)+',theme:"dark"})')
        for _ in range(100):
            if await evaluate('ready === true'): break
            await asyncio.sleep(.05)
        assert await evaluate('ready === true')
        frame = page.frame_locator('#widget')
        child_cdp = await page.context.new_cdp_session(page.frames[1])
        await child_cdp.send('Runtime.evaluate', {'expression':"document.querySelector('#ask').click()", 'userGesture':False})
        await evaluate("window.postMessage({type:'send-prompt',text:'Forged host'},'*')")
        await page.wait_for_timeout(200)
        assert not await evaluate('nativeCalls.some(x=>x.type==="action")')
        assert await frame.locator('body').evaluate('()=>fetchBlocked')
        assert not await frame.locator('body').evaluate('()=>{try{return !!parent.document.body}catch{return false}}')
        assert await frame.locator('body').evaluate('()=>typeof WidgetHost') == 'undefined'
        assert await frame.locator('html').get_attribute('data-theme') == 'dark'
        for _ in range(100):
            style = await child_cdp.send('Runtime.evaluate', {'expression':'getComputedStyle(document.body).backgroundColor', 'returnByValue':True, 'userGesture':False})
            if style['result'].get('value') == 'rgb(23, 28, 39)': break
            await asyncio.sleep(.05)
        if style['result'].get('value') != 'rgb(23, 28, 39)':
            info = await child_cdp.send('Runtime.evaluate', {'expression':"JSON.stringify({links:[...document.querySelectorAll('link')].map(e=>({href:e.href,sheet:!!e.sheet})),styleSheets:[...document.styleSheets].map(e=>e.href),head:document.head.outerHTML,vars:getComputedStyle(document.documentElement).getPropertyValue('--bg'),rules:[...document.styleSheets].map(s=>{try{return [...s.cssRules].slice(0,3).map(r=>r.cssText)}catch{return 'crossorigin'}})})",'returnByValue':True})
            print(info)
        assert style['result'].get('value') == 'rgb(23, 28, 39)', style
        await frame.locator('#ask').click()
        await page.wait_for_function('nativeCalls.some(x=>x.type==="action")')
        assert await page.evaluate('nativeCalls.filter(x=>x.type==="action").length') == 1
        assert not requests, requests
        await browser.close()
    report = dict(policy_before_generated_html=True, external_scripts_images_and_fetch_blocked=True,
                  opaque_frame_has_no_native_bridge=True, forged_and_synthetic_actions_rejected=True,
                  explicit_click_accepted=True, default_stylesheet_and_theme_applied=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report))

if __name__ == '__main__':
    asyncio.run(main())
