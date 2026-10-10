#!/usr/bin/env python3
"""Live tiles and network failure fallback, separate from offline layout checks."""
import argparse
import asyncio
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import urlopen
from playwright.async_api import async_playwright
from fixtures import widgets

ROOT=Path(__file__).resolve().parents[2]

async def main():
 parser=argparse.ArgumentParser();parser.add_argument('runtime',type=Path);parser.add_argument('--output',type=Path,required=True)
 args=parser.parse_args();cfg=json.loads(args.runtime.read_text());args.output.mkdir(parents=True,exist_ok=True)
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path='/bin/google-chrome',headless=True,args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':360,'height':900});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  page.on('console',lambda msg:print('Browser: '+msg.text,flush=True) if msg.type=='error' else None)
  async def asset(route):
   target=ROOT/'android-native/app/src/debug/assets'/urlparse(route.request.url).path.removeprefix('/assets/')
   await route.fulfill(path=str(target),content_type=mimetypes.guess_type(target)[0] or 'application/octet-stream',headers={'Access-Control-Allow-Origin':'*'})
  # Context routing catches the very first request of a new sandboxed OOPIF.
  await page.context.route('https://appassets.androidplatform.net/**',asset)
  tile_count=0
  async def live_tile(route):
   nonlocal tile_count
   def fetch():
    with urlopen(route.request.url,timeout=20) as response:return response.read(),response.headers.get_content_type()
   body,kind=await asyncio.to_thread(fetch);tile_count+=1
   await route.fulfill(body=body,content_type=kind)
  await page.context.route('https://basemap.nationalmap.gov/**',live_tile)
  await page.goto(f"http://127.0.0.1:{cfg['assetsPort']}/ui-poc/host.html")
  # Desktop browsers have no Android WebViewAssetLoader. Serve the identical
  # bundled libraries through the harness's actual local asset server instead.
  document=widgets(f"http://127.0.0.1:{cfg['assetsPort']}/ui-poc")[4]
  await page.evaluate("d=>{window.mapState=null;addEventListener('widget-state',e=>window.mapState=e.detail.state);mountWidget(d)}",{'id':'map-modes','html':document['html']})
  frame=page.frame_locator('#widget');await frame.locator('#liveMap').click()
  try:
   await frame.get_by_text('USGS basemap · online',exact=True).wait_for(timeout=10000)
  except Exception:
   print({'status':await frame.locator('#mapStatus').inner_text(),'errors':errors,'tiles':await frame.locator('.leaflet-tile').count()},flush=True)
   await page.screenshot(path=str(args.output/'map-live-failure.png'),full_page=True)
   raise
  assert await frame.locator('.leaflet-image-layer').count()==0,'Offline SVG must not cover live tiles'
  assert await frame.locator('.leaflet-tile-loaded').count()>0
  assert await frame.locator('.leaflet-tile-loaded').first.evaluate('e=>e.naturalWidth')==256
  await page.wait_for_function('window.mapState?.custom.live===true')
  state=await page.evaluate('window.mapState');await page.screenshot(path=str(args.output/'map-live.png'),full_page=True)
  await page.evaluate('d=>mountWidget(d)',{'id':'map-modes','html':document['html'],'state':state})
  await frame.get_by_text('USGS basemap · online',exact=True).wait_for()
  assert await frame.locator('#liveMap').get_attribute('aria-pressed')=='true'
  await frame.locator('#offlineMap').click();assert await frame.locator('.leaflet-image-layer').count()==1
  assert await frame.locator('.leaflet-overlay-pane path').count()>=4
  await page.context.unroute('https://basemap.nationalmap.gov/**')
  await page.context.route('https://basemap.nationalmap.gov/**',lambda route:route.abort())
  # Use fresh image URLs so already-decoded successful tiles cannot mask an outage.
  failure_html=document['html'].replace('/tile/{z}/{y}/{x}', '/tile/{z}/{y}/{x}?test-outage=1')
  await page.evaluate('d=>mountWidget(d)',{'id':'map-failure','html':failure_html})
  await frame.locator('#liveMap').click()
  await frame.get_by_text('Basemap unavailable. Offline map is ready.',exact=True).wait_for()
  assert await frame.locator('#offlineMap').get_attribute('aria-pressed')=='true'
  assert await frame.locator('.leaflet-image-layer').count()==1
  assert not errors,errors
  report={'live_tiles_loaded':True,'real_tiles_fetched':tile_count,'schematic_does_not_cover_tiles':True,'mode_restored':True,'network_failure_restores_offline_map':True,'route_and_stops_retained':True,'source':'USGS tile service, actual per-coordinate tile requests; simulated outage in a fresh document'}
  (args.output/'map-mode-results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
  await browser.close()

if __name__=='__main__':asyncio.run(main())
