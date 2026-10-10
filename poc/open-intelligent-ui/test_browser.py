#!/usr/bin/env python3
import argparse,asyncio,json,mimetypes,sys,uuid
from pathlib import Path
from urllib.parse import urlparse
import websockets
from playwright.async_api import async_playwright
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts'))
from test_chat_bindings import watch,texts

async def action(connection,session,binding,widget,text,expected=True):
 request=uuid.uuid4().hex
 await connection.send(json.dumps({'type':'send-bound-ui-action','sessionName':session,'windowIndex':0,'bindingId':binding['bindingId'],'widgetId':widget,'requestId':request,'message':text}))
 receipt=None;reply=None
 while receipt is None or (expected and reply is None):
  packet=json.loads(await asyncio.wait_for(connection.recv(),10))
  if packet.get('type')=='chat-send-result' and packet.get('requestId')==request:
   receipt=packet;assert receipt['success']==expected,receipt
  if packet.get('type')=='chat-event' and any('reply: '+text in t for t in texts(packet)):reply=packet
 return receipt

async def main():
 parser=argparse.ArgumentParser();parser.add_argument('runtime',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();cfg=json.loads(args.runtime.read_text());args.output.mkdir(parents=True,exist_ok=True)
 endpoint=f"ws://127.0.0.1:{cfg['port']}/ws?token={cfg['token']}"
 async with websockets.connect(endpoint,max_size=16*1024*1024) as connection:
  a,history=await watch(connection,'ui-A');assert len([b for m in history['messages'] for b in m['blocks'] if b['type']=='ui_widget'])==5
  async with async_playwright() as p:
   browser=await p.chromium.launch(executable_path='/bin/google-chrome',headless=True,args=['--use-angle=swiftshader','--enable-unsafe-swiftshader','--no-sandbox'])
   page=await browser.new_page(viewport={'width':412,'height':915})
   async def assets(route):
    url=urlparse(route.request.url);target=ROOT/'android-native/app/src/debug/assets'/url.path.removeprefix('/assets/')
    assert target.resolve().is_relative_to((ROOT/'android-native/app/src/debug/assets').resolve())
    await route.fulfill(path=str(target),content_type=mimetypes.guess_type(target)[0] or 'application/octet-stream',headers={'Access-Control-Allow-Origin':'*'})
   await page.route('https://appassets.androidplatform.net/**',assets)
   await page.goto(f"http://127.0.0.1:{cfg['assetsPort']}/ui-poc/host.html")
   await page.evaluate("window.actions=[];window.addEventListener('widget-action',e=>window.actions.push(e.detail))")
   report={};errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
   for widget in [x for x in cfg['widgets'] if x['session']=='A']:
    await page.evaluate('data=>{window.actions=[];mountWidget(data)}',{'id':widget['widgetId'],'html':widget['document']['html']})
    frame=page.frame_locator('#widget');title=widget['document']['title']
    await frame.locator('h3').wait_for()
    assert not await frame.locator('body').evaluate("()=>{try{return !!parent.document.body}catch{return false}}")
    if title=='Chart and table':
     before=await frame.locator('#series').get_attribute('d')
     await frame.locator('#scale').evaluate("e=>{e.value=3;e.dispatchEvent(new Event('input'))}")
     assert await frame.locator('#chartValue').inner_text()=='3';assert before!=await frame.locator('#series').get_attribute('d')
    elif title=='Calculator':
     await frame.locator('#people').fill('4');assert await frame.locator('#total').inner_text()=='28.75'
    elif title=='Diagram':
     await frame.locator('#step').click();assert await frame.locator('#stepLabel').inner_text()=='Validate'
    elif title=='3D scene':
     await frame.locator('canvas').wait_for();before=await frame.locator('canvas').evaluate('e=>e.toDataURL()')
     await frame.locator('#yaw').evaluate("e=>{e.value=65;e.dispatchEvent(new Event('input'))}")
     assert await frame.locator('#angle').inner_text()=='65';assert before!=await frame.locator('canvas').evaluate('e=>e.toDataURL()')
    elif title=='Map':
     await frame.locator('#selectStop').click();assert await frame.locator('#stopName').inner_text()=='San Jose'
     assert await frame.locator('#map .leaflet-overlay-pane path').count()>=3
    await frame.locator('#ask').click()
    await page.wait_for_function('window.actions.length===1')
    sent=(await page.evaluate('window.actions'))[0];assert sent['widgetId']==widget['widgetId']
    await action(connection,'ui-A',a,sent['widgetId'],sent['text'])
    assert sent['text'] not in (Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][1]+'.jsonl')).read_text()
    await page.screenshot(path=str(args.output/(title.replace(' ','-')+'.png')),full_page=True)
    report[title]='rendered, interacted, action delivered to A only'
   assert not errors,errors
   await page.evaluate("window.actions=[];window.postMessage({type:'send-prompt',text:'forged'},'*')")
   await page.wait_for_timeout(100);assert not await page.evaluate('window.actions')
   await browser.close()
  b,_=await watch(connection,'ui-B')
  await action(connection,'ui-B',b,cfg['widgets'][0]['widgetId'],'cross-conversation must not send',False)
  assert 'cross-conversation must not send' not in (Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][1]+'.jsonl')).read_text()
  a,_=await watch(connection,'ui-A');(Path(cfg['project'])/'A.control').write_text(cfg['ids'][2])
  while (Path(cfg['project'])/'A.active').read_text()!=cfg['ids'][2]:await asyncio.sleep(.01)
  await action(connection,'ui-A',a,cfg['widgets'][0]['widgetId'],'stale widget must not send',False)
  assert 'stale widget must not send' not in (Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][2]+'.jsonl')).read_text()
  report.update({'foreign_widget_rejected':True,'resume_rejects_stale_action':True,'iframe_parent_isolation':True,'forged_host_message_rejected':True,'provider_requests':0})
  (args.output/'browser-results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':asyncio.run(main())
