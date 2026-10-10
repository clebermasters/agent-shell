#!/usr/bin/env python3
"""Layout, accessibility, persistence and useful behavior across mobile sizes."""
import argparse
import asyncio
import json
import mimetypes
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright
from fixtures import widgets

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android-native/app/src/debug/assets"

LAYOUT = """() => {
 const visible=e=>e.getClientRects().length && !e.closest('[hidden]');
 const buttons=[...document.querySelectorAll('button,input,select,.leaflet-control-zoom a')].filter(visible);
 const labels=buttons.filter(e=>!e.disabled && !(e.getAttribute('aria-label') || e.labels?.length || e.textContent.trim()));
 const small=buttons.filter(e=>{const r=e.getBoundingClientRect();return r.height<43.5 || r.width<43.5}).map(e=>({id:e.id,text:e.textContent,w:e.clientWidth,h:e.clientHeight}));
 function rgb(value){return value.match(/[\\d.]+/g)?.slice(0,3).map(Number)}
 function luminance(c){return c.map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0)}
 const root=getComputedStyle(document.documentElement);
 const ink=rgb(root.getPropertyValue('--ink').trim().startsWith('#')?getComputedStyle(document.body).color:root.getPropertyValue('--ink'));
 const background=getComputedStyle(document.body).backgroundColor;
 const ratio=(a,b)=>(Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);
 const probe=document.createElement('span');probe.style.color='var(--muted)';document.body.append(probe);const muted=rgb(getComputedStyle(probe).color);probe.remove();
 return {overflow:document.documentElement.scrollWidth>innerWidth,small,unlabelled:labels.map(e=>e.id),contrast:ratio(ink,rgb(background)),mutedContrast:ratio(muted,rgb(background)),height:document.documentElement.scrollHeight};
}"""


async def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("runtime", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    cfg = json.loads(args.runtime.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    documents = widgets("https://appassets.androidplatform.net/assets/ui-poc")
    report = {"layouts": [], "interactions": {}, "external_requests": []}
    async with async_playwright() as p:
        browser = await p.chromium.launch(executable_path="/bin/google-chrome", headless=True,
            args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--no-sandbox"])
        page = await browser.new_page(viewport={"width":360,"height":800}, reduced_motion="reduce")
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: report["external_requests"].append(request.url)
            if urlparse(request.url).hostname not in ["127.0.0.1", "appassets.androidplatform.net", None] else None)
        async def asset(route):
            target = ASSETS / urlparse(route.request.url).path.removeprefix("/assets/")
            assert target.resolve().is_relative_to(ASSETS.resolve())
            await route.fulfill(path=str(target), content_type=mimetypes.guess_type(target)[0] or "application/octet-stream",
                headers={"Access-Control-Allow-Origin":"*"})
        await page.route("https://appassets.androidplatform.net/**", asset)
        await page.goto(f"http://127.0.0.1:{cfg['assetsPort']}/ui-poc/host.html")
        await page.evaluate("window.states={};addEventListener('widget-state',e=>window.states[e.detail.widgetId]=e.detail.state)")

        async def mount(index, theme="light", state=None):
            await page.evaluate("data=>mountWidget(data)", {"id":f"sample-{index}","html":documents[index]["html"],"theme":theme,"state":state})
            frame = page.frame_locator("#widget")
            await frame.locator("h3").wait_for()
            await frame.locator("html").evaluate("(e,theme)=>new Promise(resolve=>{const wait=()=>e.dataset.theme===theme?resolve():requestAnimationFrame(wait);wait()})",theme)
            await page.wait_for_timeout(50)
            return frame

        for width in [280,320,360,412,740]:
            await page.set_viewport_size({"width":width,"height":800})
            for theme in ["light","dark"]:
                for index, document in enumerate(documents):
                    started = time.monotonic()
                    frame = await mount(index,theme)
                    layout = await frame.locator("body").evaluate(LAYOUT)
                    assert not layout["overflow"], (width,theme,document["title"],layout)
                    assert not layout["small"], (width,theme,document["title"],layout["small"])
                    assert not layout["unlabelled"], (document["title"],layout)
                    assert layout["contrast"]>=4.5 and layout["mutedContrast"]>=4.5, (theme,layout)
                    report["layouts"].append({"width":width,"theme":theme,"widget":document["title"],
                        "load_ms":round((time.monotonic()-started)*1000),**layout})
                    if width==360:
                        await page.screenshot(path=str(args.output/f"mobile-{index}-{theme}.png"),full_page=True)

        await page.set_viewport_size({"width":360,"height":800})
        frame=await mount(0)
        assert await frame.locator("#revenue").inner_text()=="$51,400"
        await frame.get_by_role("button",name="Select Feb").click()
        assert await frame.locator("#selectedValue").inner_text()=="$7,400.00"
        await frame.get_by_role("button",name="Data table").click()
        assert await frame.locator("#rows tr").count()==6
        await page.wait_for_function("window.states['sample-0']?.custom.table===true")
        state=await page.evaluate("window.states['sample-0']")
        frame=await mount(0,"dark",state)
        assert await frame.locator("#tableView").is_visible()
        await frame.get_by_role("button",name="Trend",exact=True).click()
        assert await frame.locator("#selectedValue").inner_text()=="$7,400.00"
        report["interactions"]["chart"]="arithmetic, month selection, six rows, selected month and table state survive remount"

        frame=await mount(1)
        for field, invalid in [("bill","-1"),("tip","101"),("people","0"),("people","2.5")]:
            await frame.locator("#"+field).fill(invalid)
            assert await frame.locator("#ask").is_disabled()
            assert await frame.locator("#"+field).get_attribute("aria-invalid")=="true"
            await frame.locator("#"+field).fill({"bill":"100","tip":"15","people":"2"}[field])
        await frame.locator("#people").fill("4")
        assert await frame.locator("#total").inner_text()=="28.75"
        await frame.get_by_role("button",name="20%",exact=True).click()
        assert await frame.locator("#total").inner_text()=="30.00"
        await frame.get_by_role("button",name="One more person").click()
        assert await frame.locator("#total").inner_text()=="24.00"
        await page.wait_for_function("window.states['sample-1']?.inputs.people==='5'")
        frame=await mount(1,"dark",await page.evaluate("window.states['sample-1']"))
        assert await frame.locator("#total").inner_text()=="24.00"
        report["interactions"]["calculator"]="invalid inputs block actions; tip presets, group stepper and restored calculation pass"

        frame=await mount(2)
        assert await frame.locator("#previous").is_disabled()
        for _ in range(3):await frame.locator("#step").click()
        assert await frame.locator("#stepLabel").inner_text()=="Review"
        assert await frame.locator("#step").is_disabled()
        await frame.get_by_role("button",name="2 Validate").click()
        assert await frame.locator("#stepLabel").inner_text()=="Validate"
        await page.wait_for_function("window.states['sample-2']?.custom.step===1")
        frame=await mount(2,"dark",await page.evaluate("window.states['sample-2']"))
        assert await frame.locator("#stepLabel").inner_text()=="Validate"
        report["interactions"]["diagram"]="direct stage selection, boundary navigation and restore pass"

        frame=await mount(3)
        canvas=frame.locator("canvas");before=await canvas.evaluate("e=>e.toDataURL()")
        bounds=await canvas.bounding_box();await page.mouse.move(bounds['x']+80,bounds['y']+80);await page.mouse.down()
        await page.mouse.move(bounds['x']+160,bounds['y']+80,steps=5);await page.mouse.up()
        assert await frame.locator("#angle").inner_text()!="0"
        assert before!=await canvas.evaluate("e=>e.toDataURL()")
        await frame.get_by_role("button",name="Top",exact=True).click()
        assert await frame.locator("#elevation").inner_text()=="85"
        await frame.get_by_role("button",name="Zoom in",exact=True).click()
        await page.wait_for_function("window.states['sample-3']?.custom.zoom>1")
        frame=await mount(3,"dark",await page.evaluate("window.states['sample-3']"))
        assert await frame.locator("#elevation").inner_text()=="85"
        await frame.get_by_role("button",name="Reset view",exact=True).click()
        assert await frame.locator("#angle").inner_text()=="0" and await frame.locator("#elevation").inner_text()=="28"
        report["interactions"]["3d"]="actual pointer drag changes rendered pixels; camera presets, zoom, reset and restore pass"

        frame=await mount(4)
        await frame.locator("#selectStop").click()
        assert await frame.locator("#stopName").inner_text()=="San Jose"
        await frame.get_by_role("button",name="Fit route",exact=True).click()
        await page.wait_for_function("window.states['sample-4']?.custom.selectedStop===2")
        frame=await mount(4,"dark",await page.evaluate("window.states['sample-4']"))
        assert await frame.locator("#stopName").inner_text()=="San Jose"
        assert await frame.locator("#map svg").count()>=2
        report["interactions"]["map"]="offline schematic, stop selection, fit bounds and restored stop pass"
        assert not errors,errors
        assert not report["external_requests"],report["external_requests"]
        await browser.close()

        fallback=await p.chromium.launch(executable_path="/bin/google-chrome",headless=True,args=["--disable-webgl","--no-sandbox"])
        page=await fallback.new_page();await page.route("https://appassets.androidplatform.net/**",asset)
        await page.goto(f"http://127.0.0.1:{cfg['assetsPort']}/ui-poc/host.html")
        await page.evaluate("data=>mountWidget(data)",{"id":"fallback","html":documents[3]["html"]})
        frame=page.frame_locator("#widget");await frame.locator("#sceneError:not([hidden])").wait_for()
        await frame.get_by_role("button",name="Front",exact=True).click()
        assert await frame.locator("#elevation").inner_text()=="0"
        report["interactions"]["webgl_fallback"]="static preview and useful controls remain available"
        await fallback.close()
    (args.output/"mobile-results.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({"layout_cases":len(report['layouts']),"interactions":report['interactions'],"external_requests":0}))

if __name__=="__main__":asyncio.run(run())
