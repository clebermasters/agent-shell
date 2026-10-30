#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../deployment/browser/viewer-bridge.js'), 'utf8');

function fixture(width = 411, height = 800) {
  const styled = () => ({style:{setProperty(){}}});
  let tick, updates = 0;
  const screen = {getBoundingClientRect:()=>({width:context.innerWidth,height:context.innerHeight}),style:{},scrollLeft:0,scrollTop:0};
  const rfb = {_supportsSetDesktopSize:true,_fbWidth:640,_fbHeight:1246,_resizeTimeout:null,agentShellObserved:true,
    _screen:screen,_canvas:{style:{}},_display:{scale:1,screens:[{}]},
    updateConnectionSettings(){updates++;this._fbWidth=this.forcedResolutionX;this._fbHeight=this.forcedResolutionY;}};
  const ui = {connected:true,rfb};
  const window = {AgentShellUI:ui,AgentShellViewportOptions:{adaptive:true,keyboardVisible:false},addEventListener(){},dispatchEvent(){}};
  const context = {window,navigator:{userAgent:'content size fixture'},innerWidth:width,innerHeight:height,
    document:{createElement:()=>({}),head:{append(){}},documentElement:styled(),body:styled(),getElementById:()=>null},
    setInterval(fn){tick=fn;return 1;},clearInterval(){},Event:class{}};
  vm.runInNewContext(source,context);tick();
  return {context,window,rfb,ui,tick,updates:()=>updates,geometry:window.AgentShellBrowserGeometry};
}

let passed=0;
function test(name,fn){fn();passed++;process.stdout.write(`ok - ${name}\n`);}

test('default geometry preserves the previous fit on phones, folds, tablets and desktops',()=>{
  const f=fixture();
  for(const [w,h] of [[411,800],[590,620],[800,1200],[1280,2048],[1920,1080],[3840,2160],[10000,9000]]){
    let factor=Math.max(1,640/w,360/h);factor*=Math.min(1,1600/(w*factor),2160/(h*factor));
    const expected=[Math.round(w*factor/2)*2,Math.round(h*factor/2)*2];
    const result=f.geometry.desktopSize(w,h,100);
    assert.deepEqual([result.width,result.height],expected);
    assert.deepEqual([f.geometry.desktopSize(w,h,undefined).width,f.geometry.desktopSize(w,h,999).height],expected);
  }
});
test('larger text uses fewer remote pixels and more content uses more pixels with the same aspect',()=>{
  const f=fixture();
  for(const [w,h] of [[411,800],[800,400],[590,620],[800,1200]]){
    let previous=0;
    for(const density of [75,100,125,150,200]){
      const r=f.geometry.desktopSize(w,h,density);
      assert(r.width*r.height>previous);previous=r.width*r.height;
      assert(Math.abs((r.width/r.height)/(w/h)-1)<.005);
      assert.equal(r.width%2,0);assert.equal(r.height%2,0);
    }
  }
});
test('high resolution is bounded by dimensions and a six megapixel budget',()=>{
  const f=fixture();
  for(const [w,h] of [[1920,3500],[5000,2000],[6000,6000],[1000,9000]]){
    const r=f.geometry.desktopSize(w,h,200);
    assert(r.width<=2560);assert(r.height<=3840);assert(r.width*r.height<=6000000);
  }
  assert.equal(f.geometry.desktopSize(Infinity,800,150),null);
  assert.equal(f.geometry.desktopSize(411,50,150),null);
});
test('density changes resize the current framebuffer once, fit the view, and leave fit independent',()=>{
  const f=fixture();const initial=f.rfb._fbWidth;const firstUpdates=f.updates();
  f.window.AgentShellViewer.zoom(2);assert(f.rfb._display.scale>411/initial);
  f.window.AgentShellViewportOptions.contentDensity=150;f.tick();
  assert(f.rfb._fbWidth>initial);assert.equal(f.updates(),firstUpdates+1);
  assert(Math.abs(f.rfb._display.scale-411/f.rfb._fbWidth)<.002);
  f.tick();assert.equal(f.updates(),firstUpdates+1);
  const size=[f.rfb._fbWidth,f.rfb._fbHeight];f.window.AgentShellViewer.zoom(2);f.window.AgentShellViewer.fit();
  assert.deepEqual([f.rfb._fbWidth,f.rfb._fbHeight],size);
});
test('keyboard changes postpone density and retain the existing apparent browser size',()=>{
  const f=fixture();const initial=[f.rfb._fbWidth,f.rfb._fbHeight];const scale=f.rfb._display.scale;
  f.window.AgentShellViewportOptions.keyboardVisible=true;f.context.innerHeight=400;
  f.window.AgentShellViewportOptions.contentDensity=150;f.tick();
  assert.deepEqual([f.rfb._fbWidth,f.rfb._fbHeight],initial);assert.equal(f.rfb._display.scale,scale);
  f.window.AgentShellViewportOptions.keyboardVisible=false;f.context.innerHeight=800;f.tick();
  assert(f.rfb._fbWidth>initial[0]);
});
test('rotation keeps the selected density, while unsupported servers receive no resize',()=>{
  const f=fixture();f.window.AgentShellViewportOptions.contentDensity=150;f.tick();
  f.context.innerWidth=800;f.context.innerHeight=411;f.tick();
  const r=f.geometry.desktopSize(800,411,150);assert.deepEqual([f.rfb._fbWidth,f.rfb._fbHeight],[r.width,r.height]);
  const before=f.updates();f.rfb._supportsSetDesktopSize=false;f.window.AgentShellViewportOptions.contentDensity=200;f.tick();assert.equal(f.updates(),before);
});
process.stdout.write(`${passed} content-size checks passed\n`);
