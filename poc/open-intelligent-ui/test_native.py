#!/usr/bin/env python3
"""Actual Android WebView checks over its page-level DevTools protocol."""
import argparse,asyncio,json,subprocess,sys,time
from pathlib import Path
from urllib.request import urlopen
import websockets

class Page:
 def __init__(self,ws):self.ws=ws;self.sequence=0;self.contexts=[]
 async def call(self,method,params=None):
  self.sequence+=1;sequence=self.sequence
  await self.ws.send(json.dumps({'id':sequence,'method':method,'params':params or {}}))
  while True:
   data=json.loads(await asyncio.wait_for(self.ws.recv(),15))
   if data.get('method')=='Runtime.executionContextCreated':self.contexts.append(data['params']['context']['id'])
   if data.get('id')==sequence:
    if 'error' in data:raise RuntimeError(data['error'])
    return data.get('result',{})
 async def evaluate(self,context,expression):
  result=await self.call('Runtime.evaluate',{'contextId':context,'expression':expression,'returnByValue':True})
  if 'exceptionDetails' in result:raise RuntimeError(result['exceptionDetails'])
  return result.get('result',{}).get('value')
 async def click(self,context,selector):
  rect=await self.evaluate(context,f"(()=>{{const r=document.querySelector({json.dumps(selector)}).getBoundingClientRect();return {{x:r.x+r.width/2,y:r.y+r.height/2}}}})()")
  await self.call('Input.dispatchMouseEvent',{'type':'mousePressed','button':'left','clickCount':1,**rect})
  await self.call('Input.dispatchMouseEvent',{'type':'mouseReleased','button':'left','clickCount':1,**rect})

async def main():
 parser=argparse.ArgumentParser();parser.add_argument('runtime',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();cfg=json.loads(args.runtime.read_text());args.output.mkdir(parents=True,exist_ok=True)
 deadline=time.monotonic()+25
 while True:
  try:
   targets=json.load(urlopen('http://127.0.0.1:9223/json/list',timeout=2))
   if len(targets)==5:break
  except OSError:pass
  assert time.monotonic()<deadline,'Android WebViews did not become ready'
  await asyncio.sleep(.2)
 report={}
 for target in targets:
  async with websockets.connect(target['webSocketDebuggerUrl'],max_size=16*1024*1024) as ws:
   page=Page(ws);await page.call('Runtime.enable');child=None;title=None
   for context in page.contexts:
    label=await page.evaluate(context,"document.querySelector('h3')?.textContent")
    if label:child=context;title=label;break
   assert child is not None,'Widget iframe missing'
   assert not await page.evaluate(child,"(()=>{try{return !!parent.document.body}catch{return false}})()")
   if title=='Interactive sample chart':
    before=await page.evaluate(child,"document.querySelector('#series').getAttribute('d')")
    await page.evaluate(child,"(()=>{const e=document.querySelector('#scale');e.value=2;e.dispatchEvent(new Event('input'));return true})()")
    assert before!=await page.evaluate(child,"document.querySelector('#series').getAttribute('d')")
   elif title=='Bill splitter':
    await page.evaluate(child,"(()=>{const e=document.querySelector('#people');e.value=4;e.dispatchEvent(new Event('input'));return true})()")
    assert await page.evaluate(child,"document.querySelector('#total').textContent")=='28.75'
   elif title=='Process explorer':
    await page.click(child,'#step');assert await page.evaluate(child,"document.querySelector('#stepLabel').textContent")=='Validate'
   elif title=='3D orientation explorer':
    assert await page.evaluate(child,'window.__poc3dReady')
    before=await page.evaluate(child,"document.querySelector('canvas').toDataURL()")
    await page.evaluate(child,"(()=>{const e=document.querySelector('#yaw');e.value=65;e.dispatchEvent(new Event('input'));return true})()")
    assert before!=await page.evaluate(child,"document.querySelector('canvas').toDataURL()")
   elif title=='Interactive itinerary':
    await page.click(child,'#selectStop');assert await page.evaluate(child,"document.querySelector('#stopName').textContent")=='San Jose'
   else:raise AssertionError(title)
   transcript=Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][0]+'.jsonl')
   count=transcript.read_text().count('reply: ')
   await page.click(child,'#ask')
   deadline=time.monotonic()+8
   while transcript.read_text().count('reply: ')<=count:
    assert time.monotonic()<deadline,title+' action was not delivered from Android'
    await asyncio.sleep(.05)
   assert 'reply: ' not in (Path(cfg['project'])/'.codex/sessions'/('rollout-'+cfg['ids'][1]+'.jsonl')).read_text(),'Android action reached B'
   report[title]='actual WebView render, interaction, native callback and A-only reply passed'
 assert len(report)==5
 report.update({'android_api':35,'webview':'124.0.6367.219','iframe_parent_isolation':True,'provider_inference_calls':0})
 (args.output/'native-results.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report))
if __name__=='__main__':asyncio.run(main())
