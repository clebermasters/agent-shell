#!/usr/bin/env python3
"""Page-level Android WebView DevTools helper and compatible test entry point."""
import asyncio
import json

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

if __name__ == '__main__':
 from test_native_mobile import main
 asyncio.run(main())
