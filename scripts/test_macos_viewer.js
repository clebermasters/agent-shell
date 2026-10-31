#!/usr/bin/env node
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {randomUUID}=require('node:crypto');
const source=fs.readFileSync(path.join(__dirname,'../deployment/browser/macos-viewer.js'),'utf8');

function fixture({deferImages=false}={}){
  const elements={},images=[],draws=[],blobs=[],revoked=[],host=[],timers=[],intervals=[];let context;
  const element=id=>elements[id]??=(
    {id,style:{},listeners:{},hidden:false,value:'',width:0,height:0,scrollLeft:0,scrollTop:0,open:false,
     get clientWidth(){return context.innerWidth;},get clientHeight(){return context.innerHeight;},
     getBoundingClientRect(){return {left:0,top:0,width:parseFloat(this.style.width)||context.innerWidth,height:parseFloat(this.style.height)||context.innerHeight};},
     addEventListener(type,fn){this.listeners[type]=fn;},focus(){},blur(){this.listeners.blur?.({});},select(){},
     showModal(){this.open=true;},close(){this.open=false;},setPointerCapture(){},
     getContext(){return {drawImage(...args){draws.push(args);},clearRect(){}};}}
  );
  const sockets=[];
  class Socket{
    static OPEN=1;
    constructor(url,protocols){this.url=url;this.protocols=protocols;this.readyState=1;this.bufferedAmount=0;this.sent=[];sockets.push(this);}
    send(raw){this.sent.push(JSON.parse(raw));}
    close(){this.readyState=3;this.onclose?.();}
  }
  class FrameImage{
    constructor(){images.push(this);}
    decode(){return deferImages?new Promise(resolve=>{this.finish=resolve;}):Promise.resolve();}
  }
  class BlobValue{constructor(parts,options){this.parts=parts;this.type=options.type;}}
  class Url extends URL{}
  Url.createObjectURL=blob=>{assert.equal(blob.type,'image/jpeg');blobs.push(blob);return 'blob:owned-'+blobs.length;};
  Url.revokeObjectURL=url=>revoked.push(url);
  const document={hidden:false,getElementById:element,addEventListener(type,fn){this[type]=fn;},execCommand(){return true;}};
  const window={AgentShellViewportOptions:{adaptive:true,keyboardVisible:false,contentDensity:100},BrowserHost:{postMessage:value=>host.push(JSON.parse(value))},
    addEventListener(type,fn){this[type]=fn;}};
  context={window,document,WebSocket:Socket,Image:FrameImage,Blob:BlobValue,URL:Url,location:new URL('https://viewer.example/browser/owned/view/vnc.html'),
    innerWidth:411,innerHeight:800,Uint8Array,Date,Math,JSON,Number,Map,Promise,TextEncoder,crypto:{randomUUID},navigator:{},
    atob:value=>Buffer.from(value,'base64').toString('binary'),
    setInterval(fn){intervals.push(fn);return intervals.length;},
    setTimeout(fn){timers.push(fn);return timers.length;},clearTimeout(){}};
  vm.runInNewContext(source,context);
  const f={context,window,document,elements,images,draws,blobs,revoked,host,sockets,timers,
    tick(){intervals.forEach(fn=>fn());},
    message(value){sockets.at(-1).onmessage({data:JSON.stringify(value)});},
    frame(sequence=1,width=640,height=800){this.message({type:'frame',sequence,width,height,data:Buffer.from('jpeg fixture').toString('base64')});},
    sent(){return sockets.at(-1).sent;},
    event(id,type,values={}){const event={preventDefault(){this.prevented=true;},...values,type};element(id).listeners[type]?.(event);return event;}
  };
  return f;
}
const settle=async()=>{for(let i=0;i<8;i++)await Promise.resolve();};
let passed=0;
async function test(name,fn){await fn();passed++;process.stdout.write('ok - '+name+'\n');}

(async()=>{
 await test('viewer uses the existing authenticated socket route and waits for a decoded frame',async()=>{
   const f=fixture();assert.equal(f.sockets[0].url,'wss://viewer.example/browser/owned/socket');
   assert.deepEqual(Array.from(f.sockets[0].protocols),['binary']);assert(!f.host.some(m=>m.state==='connected'));
   f.frame();await settle();assert(f.host.some(m=>m.state==='connected'));
   assert(f.host.some(m=>m.type==='display-capability'&&m.resizeSupported));assert.equal(f.draws.length,1);
   assert.equal(f.blobs.length,1);assert.equal(f.revoked.length,1);
 });
 await test('default density keeps the existing geometry and optional resolutions stay bounded',async()=>{
   const g=fixture().window.AgentShellBrowserGeometry;
   for(const [w,h] of [[411,800],[590,620],[1600,2500],[5000,5000]]){
     let factor=Math.max(1,640/w,360/h);factor*=Math.min(1,1600/(w*factor),2160/(h*factor));
     const d=g.desktopSize(w,h,100);assert.deepEqual([d.width,d.height],[Math.round(w*factor/2)*2,Math.round(h*factor/2)*2]);
     for(const n of [75,125,150,200]){const x=g.desktopSize(w,h,n);assert(x.width*x.height<=6000000);assert(x.width<=2560&&x.height<=3840);}
   }
 });
 await test('only the latest pending frame is decoded and stale frames cannot cross reconnects',async()=>{
   const f=fixture({deferImages:true});f.frame(1,640,800);f.frame(2,700,1000);f.frame(3,800,1200);f.images[0].finish();await settle();
   assert.equal(f.images.length,2);assert.equal(f.draws.length,1);f.images[1].finish();await settle();
   assert.equal(f.draws.length,2);assert.equal(f.elements.frame.width,800);assert.equal(f.revoked.length,2);
   f.frame(4,900,1200);f.sockets[0].close();f.images[2].finish();await settle();assert.equal(f.draws.length,2);
 });
 await test('dictation remains pending until its actual input acknowledgement and never replays an uncertain job',async()=>{
   const f=fixture();f.frame();await settle();const api=f.window.AgentShellViewer;
   assert.equal(api.pasteText('job-1','Café 😀\nnext line'),'pending');assert.equal(api.pasteStatus('job-1'),'pending');
   assert.equal(api.pasteText('job-1','Café 😀\nnext line'),'pending');assert.equal(f.sent().filter(m=>m.type==='text').length,1);
   f.message({type:'input-result',id:'job-1',ok:true});assert.equal(api.pasteStatus('job-1'),'inserted');
   assert.equal(api.pasteText('job-2','Keep this transcript'),'pending');f.sockets[0].close();
   assert.equal(api.pasteStatus('job-2'),'unavailable');assert.equal(api.pasteText('job-2','Keep this transcript'),'unavailable');
   assert.equal(f.elements['retained-text'].value,'Keep this transcript');
 });
 await test('invalid text, disconnected input and excessive socket buffering are rejected',async()=>{
   const f=fixture(),api=f.window.AgentShellViewer;
   assert.equal(api.pasteText('job','text'),'unavailable');f.frame();await settle();
   assert.equal(api.pasteText('bad id','text'),'invalid');assert.equal(api.pasteText('job','x'.repeat(8193)),'invalid');
   f.sockets[0].bufferedAmount=200000;assert.equal(api.pasteText('job','text'),'unavailable');
   assert.equal(f.sent().filter(m=>m.type==='text').length,0);
 });
 await test('content size fits the captured frame and keyboard crops without resizing the desktop',async()=>{
   const f=fixture();f.frame();await settle();f.tick();
   f.window.AgentShellViewportOptions.contentDensity=150;f.tick();const chosen=f.sent().filter(m=>m.type==='resize').at(-1);
   assert.equal(chosen.contentDensity,150);f.window.AgentShellViewer.zoom(2);f.window.AgentShellViewer.fit();
   const before=[f.elements.frame.style.width,f.elements.frame.style.height];const count=f.sent().filter(m=>m.type==='resize').length;
   f.window.AgentShellViewportOptions.keyboardVisible=true;f.context.innerHeight=400;f.tick();
   assert.deepEqual([f.elements.frame.style.width,f.elements.frame.style.height],before);
   assert.equal(f.sent().filter(m=>m.type==='resize').length,count);
   f.window.AgentShellViewportOptions.keyboardVisible=false;f.context.innerHeight=800;f.tick();
   assert.equal(f.sent().filter(m=>m.type==='resize').at(-1).contentDensity,150);
 });
 await test('native Unicode composition, toolbar keys and key release retain separate input semantics',async()=>{
   const f=fixture();f.frame();await settle();
   f.event('keyboard','beforeinput',{inputType:'insertText',data:'Olá 😀',isComposing:false});
   assert.equal(f.sent().filter(m=>m.type==='text').at(-1).text,'Olá 😀');
   assert.equal(f.sent().filter(m=>m.type==='text').at(-1).method,'unicode');
   f.window.AgentShellViewer.key(65293);const enter=f.sent().filter(m=>m.type==='key'&&m.keysym===65293);
   assert.deepEqual(enter.map(m=>m.down),[true,false]);
   f.event('keyboard','keydown',{key:'Control',code:'ControlLeft'});f.event('keyboard','blur');
   assert(f.sent().some(m=>m.type==='key'&&m.keysym===65507&&m.down===false));assert(f.sent().some(m=>m.type==='release'));
 });
 await test('pointer coordinates scale to frame pixels while out-of-frame and two-finger gestures do not click remotely',async()=>{
   const f=fixture();f.frame();await settle();const g=f.window.AgentShellBrowserGeometry;
   const p=g.framePoint(225,330,{left:25,top:30,width:400,height:600},800,1200);assert.equal(p.x,400);assert.equal(p.y,600);
   f.event('viewport','pointerdown',{pointerType:'touch',pointerId:1,clientX:-5,clientY:20});
   f.event('viewport','pointerup',{pointerType:'touch',pointerId:1,clientX:-5,clientY:20});assert.equal(f.sent().filter(m=>m.type==='pointer').length,0);
   f.event('viewport','pointerdown',{pointerType:'touch',pointerId:1,clientX:100,clientY:100});
   f.event('viewport','pointerdown',{pointerType:'touch',pointerId:2,clientX:200,clientY:100});
   f.event('viewport','pointerup',{pointerType:'touch',pointerId:1,clientX:100,clientY:100});
   f.event('viewport','pointerup',{pointerType:'touch',pointerId:2,clientX:200,clientY:100});assert(!f.sent().some(m=>m.type==='pointer'&&m.kind==='down'));
 });
 await test('fresh viewer pages cannot reuse native text receipt IDs',async()=>{
   const first=fixture(),second=fixture();first.frame();second.frame();await settle();
   for(const f of [first,second])f.event('keyboard','beforeinput',{inputType:'insertText',data:'same text',isComposing:false});
   assert.notEqual(first.sent().find(m=>m.type==='text').id,second.sent().find(m=>m.type==='text').id);
 });
 await test('pointer mapping carries the painted frame dimensions while a newer frame is still decoding',async()=>{
   const f=fixture({deferImages:true});f.frame(1,640,800);f.images[0].finish();await settle();
   f.frame(2,800,1200);
   f.event('viewport','pointerdown',{pointerType:'mouse',pointerId:1,clientX:100,clientY:100,button:0});
   const pointer=f.sent().find(m=>m.type==='pointer'&&m.kind==='down');
   assert.equal(pointer.frameWidth,640);assert.equal(pointer.frameHeight,800);
   f.images[1].finish();await settle();
   f.event('viewport','pointerup',{pointerType:'mouse',pointerId:1,clientX:100,clientY:100,button:0});
   const next=f.sent().filter(m=>m.type==='pointer').at(-1);assert.equal(next.frameWidth,800);assert.equal(next.frameHeight,1200);
 });
 await test('ordinary typing is retained until acknowledgement and uncertain text is never replayed',async()=>{
   const f=fixture();f.frame();await settle();f.event('keyboard','beforeinput',{inputType:'insertText',data:'Unconfirmed typing',isComposing:false});
   assert.equal(f.elements.keyboard.value,'');const sent=f.sent().find(m=>m.type==='text');
   f.sockets[0].close();assert.equal(f.host.at(-1).state,'input-recovery');assert.equal(f.elements['retained-text'].value,'Unconfirmed typing');
   f.timers.at(-1)();f.frame();await settle();assert.equal(f.sent().filter(m=>m.type==='text').length,0);
   f.document.execCommand=()=>false;await f.elements['copy-retained'].onclick();
   assert.equal(f.elements['retained-text'].value,'Unconfirmed typing');assert(!f.elements.retained.hidden);
   f.document.execCommand=()=>true;await f.elements['copy-retained'].onclick();assert.equal(f.elements['retained-text'].value,'');
   assert.equal(f.sockets.flatMap(s=>s.sent).filter(m=>m.type==='text'&&m.id===sent.id).length,1);
 });
 await test('successful typing acknowledgements release the bounded recovery budget',async()=>{
   const f=fixture();f.frame();await settle();
   for(let i=0;i<4;i++)f.event('keyboard','beforeinput',{inputType:'insertText',data:'x'.repeat(8192),isComposing:false});
   const before=f.sent().filter(m=>m.type==='text');assert.equal(before.length,4);assert(f.elements.keyboard.readOnly);
   f.event('keyboard','beforeinput',{inputType:'insertText',data:'rejected while full',isComposing:false});assert.equal(f.sent().filter(m=>m.type==='text').length,4);
   for(const message of before)f.message({type:'input-result',id:message.id,ok:true});
   assert(!f.elements.keyboard.readOnly);assert(f.elements.retained.hidden);
   f.sockets[0].close();assert.equal(f.host.at(-1).state,'disconnected');
 });
 await test('mixed typing and failed dictation preserve every recovery segment in send order',async()=>{
   const f=fixture();f.frame();await settle();
   f.event('keyboard','beforeinput',{inputType:'insertText',data:'A',isComposing:false});
   assert.equal(f.window.AgentShellViewer.pasteText('dictation','B'),'pending');
   f.message({type:'input-result',id:'dictation',ok:false});
   f.event('keyboard','beforeinput',{inputType:'insertText',data:'C',isComposing:false});
   assert.equal(f.elements['retained-text'].value,'ABC');
   f.sockets[0].close();assert.equal(f.elements['retained-text'].value,'ABC');
 });
 process.stdout.write(passed+' native viewer checks passed\n');
})().catch(error=>{process.stderr.write(String(error.stack||error)+'\n');process.exitCode=1;});
