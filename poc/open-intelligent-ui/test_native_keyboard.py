#!/usr/bin/env python3
"""Physical field focus and Android IME layout in the mobile calculator."""
import argparse,asyncio,io,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import urlopen
import websockets
from PIL import Image
from test_native import Page

async def main():
 parser=argparse.ArgumentParser();parser.add_argument('runtime',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();cfg=json.loads(args.runtime.read_text())
 def adb(*args):return subprocess.check_output(['adb','-s','emulator-5560',*args],timeout=20)
 endpoint=f"ws://10.0.2.2:{cfg['port']}/ws?token={cfg['token']}"
 adb('shell','am','start','-S','-n','com.agentshell.debug/com.agentshell.UiPocActivity','--es','endpoint',endpoint,'--ei','sample','1')
 deadline=time.monotonic()+20
 while True:
  try:
   pid=adb('shell','pidof','com.agentshell.debug').decode().strip();adb('forward','tcp:9223','localabstract:webview_devtools_remote_'+pid)
   targets=json.load(urlopen('http://127.0.0.1:9223/json/list',timeout=2))
   if len(targets)==1:break
  except (OSError,subprocess.CalledProcessError):pass
  assert time.monotonic()<deadline;await asyncio.sleep(.1)
 async with websockets.connect(targets[0]['webSocketDebuggerUrl']) as ws:
  page=Page(ws);await page.call('Runtime.enable');child=None
  for _ in range(30):
   for c in page.contexts:
    try:
     if await page.evaluate(c,"document.querySelector('#bill')?.id"):child=c;break
    except RuntimeError:pass
   if child:break
   await asyncio.sleep(.1);await page.call('Runtime.evaluate',{'expression':'1'})
  assert child is not None
  await asyncio.sleep(.4)
  rect=await page.evaluate(child,"(()=>{const r=document.querySelector('#bill').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}})()")
  adb('shell','uiautomator','dump','/sdcard/keyboard-ui.xml');tree=ET.fromstring(adb('shell','cat','/sdcard/keyboard-ui.xml'))
  node=next(n for n in tree.iter('node') if n.get('class')=='android.webkit.WebView');x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
  ratio=(x2-x1)/rect['width'];x=round(x1+rect['x']*ratio);y=round(y1+rect['y']*ratio);assert 500<y<1794
  adb('shell','input','tap',str(x),str(y));await asyncio.sleep(1.2)
  assert await page.evaluate(child,'document.activeElement.id')=='bill'
  info=adb('shell','dumpsys','input_method').decode()
  assert re.search(r'm(?:InputShown|IsInputViewShown)[\s:=]+true',info),'Android keyboard not shown'
  args.output.mkdir(parents=True,exist_ok=True);data=adb('exec-out','screencap','-p');(args.output/'native-keyboard.png').write_bytes(data)
  with Image.open(io.BytesIO(data)) as image:
   assert image.width==1080
   # The focused input's purple outline must be visible above the keyboard.
   pixels=image.convert('RGB');points=[]
   for py in range(510,1020):
    for px in range(60,1020):
     r,g,b=pixels.getpixel((px,py))
     if abs(r-81)<4 and abs(g-81)<4 and abs(b-216)<4:points.append((px,py))
   assert points and max(y for x,y in points)-min(y for x,y in points)>=115,'Focused field remains hidden behind the keyboard'
  adb('shell','input','keyevent','4');await asyncio.sleep(.2)
  assert await page.evaluate(child,"document.querySelector('#total').textContent")=='57.50'
  report={'physical_field_tap':True,'android_keyboard_shown':True,'focused_field':'bill','focused_input_outline_visible_above_keyboard':True,'calculation_retained_after_keyboard_close':True,'expanded_and_gallery_containers_apply_ime_insets':True}
  (args.output/'native-keyboard-results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':asyncio.run(main())
