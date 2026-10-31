/* Native macOS window stream. Public access stays inside the authenticated viewer route. */
(() => {
  'use strict';
  const byId=id=>document.getElementById(id);
  const viewport=byId('viewport'),canvas=byId('frame'),context=canvas.getContext('2d',{alpha:false});
  const keyboard=byId('keyboard'),status=byId('status'),cursor=byId('cursor'),controls=byId('controls');
  const densities=[75,100,125,150,200],MAX_FRAME=8*1024*1024,MAX_PIXELS=6000000;
  const pageNonce=typeof crypto.randomUUID==='function'?crypto.randomUUID():Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join(''),MAX_TYPING_BYTES=32768;
  const density=value=>densities.includes(value)?value:100;
  const desktopSize=(w,h,value)=>{
    if(!Number.isFinite(w)||!Number.isFinite(h)||w<100||h<100)return null;
    let factor=Math.max(1,640/w,360/h);factor*=Math.min(1,1600/(w*factor),2160/(h*factor));
    const d=density(value);
    if(d===100)return {width:Math.round(w*factor/2)*2,height:Math.round(h*factor/2)*2,contentDensity:d};
    factor*=d/100;factor*=Math.min(1,2560/(w*factor),3840/(h*factor),Math.sqrt(MAX_PIXELS/(w*h*factor*factor)));
    return {width:Math.max(100,Math.floor((w*factor+1e-6)/2)*2),height:Math.max(100,Math.floor((h*factor+1e-6)/2)*2),contentDensity:d};
  };
  const framePoint=(x,y,rect,w,h)=>({x:Math.max(0,Math.min(w-1,(x-rect.left)*w/rect.width)),y:Math.max(0,Math.min(h-1,(y-rect.top)*h/rect.height))});
  window.AgentShellBrowserGeometry=Object.freeze({desktopSize,contentDensity:density,framePoint});
  let socket,connected=false,paused=false,reconnect,attempt=0,serial=0,frameWidth=0,frameHeight=0;
  let latestFrame=null,decoding=false,lastSequence=0,zoom=1,fitScale,keyboardFit,keyboardUntil=0,wasKeyboard=false,lastResolution,lastDensity;
  let connectionEpoch=0;
  let mouseMode=false,pointer={x:0,y:0},composing=false,lastConnection;
  const keys=new Map(),pastes=new Map(),typing=new Map(),touches=new Map();let gesture=null,longPress,typingBytes=0,recoverySerial=0;
  const report=state=>{
    if(state===lastConnection)return;lastConnection=state;
    window.BrowserHost?.postMessage(JSON.stringify({type:'connection',state}));
  };
  const notice=text=>{status.textContent=text||'';status.hidden=!text;};
  const keyboardActive=()=>{
    const visible=!!window.AgentShellViewportOptions?.keyboardVisible;
    if(visible)wasKeyboard=true;
    else if(wasKeyboard){wasKeyboard=false;keyboardUntil=0;keyboardFit=undefined;}
    return visible||Date.now()<keyboardUntil;
  };
  const send=(command,id)=>{
    if(!connected||!socket||socket.readyState!==WebSocket.OPEN||socket.bufferedAmount>131072)return null;
    const identifier=id||('viewer-'+pageNonce+'-'+(++serial));
    socket.send(JSON.stringify({...command,id:identifier}));return identifier;
  };
  const paintPointer=()=>{
    cursor.hidden=!mouseMode||!connected||!frameWidth;
    if(cursor.hidden)return;
    const r=canvas.getBoundingClientRect();
    cursor.style.left=(r.left+pointer.x*r.width/frameWidth)+'px';cursor.style.top=(r.top+pointer.y*r.height/frameHeight)+'px';
  };
  const scale=()=>{
    if(!frameWidth||!frameHeight)return;
    let fit=Math.min(viewport.clientWidth/frameWidth,viewport.clientHeight/frameHeight);
    if(!(fit>0))return;
    if(keyboardActive()){keyboardFit??=fitScale??fit;fit=keyboardFit;}
    else{fitScale=fit;keyboardFit=undefined;}
    canvas.style.width=(frameWidth*fit*zoom)+'px';canvas.style.height=(frameHeight*fit*zoom)+'px';
    viewport.style.justifyContent=zoom>1||keyboardActive()?'flex-start':'center';
    viewport.style.alignItems=zoom>1||keyboardActive()?'flex-start':'center';
    if(zoom===1&&!keyboardActive()){viewport.scrollLeft=0;viewport.scrollTop=0;}
    paintPointer();
  };
  const setZoom=value=>{zoom=Math.max(1,Math.min(4,value));scale();};
  const release=()=>{
    clearTimeout(longPress);
    if(socket?.readyState===WebSocket.OPEN&&socket.bufferedAmount>131072){socket.close(1000,'Input released');}
    else{
      for(const symbol of keys.values())send({type:'key',keysym:symbol,down:false});
      send({type:'release'});
    }
    keys.clear();touches.clear();gesture=null;
  };
  const failedText=text=>{
    recovery();
    notice('Text insertion could not be confirmed. Open browser controls to copy it.');
  };
  const typingLimit=()=>{keyboard.maxLength=Math.min(8192,Math.floor((MAX_TYPING_BYTES-typingBytes)/4));keyboard.readOnly=!connected||keyboard.maxLength===0;};
  const recovery=()=>{
    const snippets=[...typing.values()];
    for(const item of pastes.values())if(item.state==='unavailable'&&item.text)snippets.push(item);
    snippets.sort((a,b)=>a.order-b.order);
    byId('retained').hidden=!snippets.length;byId('retained-text').value=snippets.map(item=>item.text).join('');
  };
  const recoveryNotice=()=>{recovery();notice('Some text could not be confirmed. Open browser controls to review it before sending more.');};
  const lost=()=>{
    connected=false;keys.clear();touches.clear();gesture=null;
    connectionEpoch+=1;latestFrame=null;context.clearRect(0,0,canvas.width,canvas.height);
    for(const item of pastes.values())if(item.state==='pending'){item.state='unavailable';failedText(item.text);}
    for(const item of typing.values())item.state='unavailable';typingLimit();recovery();
    paintPointer();report(typing.size?'input-recovery':'disconnected');
  };
  const decodeFrames=async()=>{
    if(decoding)return;decoding=true;
    try{
      while(latestFrame&&!paused){
        const frame=latestFrame;latestFrame=null;let url;
        try{
          const binary=atob(frame.data),bytes=new Uint8Array(binary.length);
          for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
          url=URL.createObjectURL(new Blob([bytes],{type:'image/jpeg'}));
          const image=new Image();image.src=url;await image.decode();
          // Paint the completed decode even if a newer frame arrived meanwhile.
          // Only one pending frame is retained; dropping every active decode
          // would starve a slower phone when capture runs faster than decoding.
          if(paused||frame.viewerEpoch!==connectionEpoch||socket?.readyState!==WebSocket.OPEN)continue;
          frameWidth=frame.width;frameHeight=frame.height;
          if(canvas.width!==frameWidth)canvas.width=frameWidth;if(canvas.height!==frameHeight)canvas.height=frameHeight;
          context.drawImage(image,0,0,frameWidth,frameHeight);scale();
          if(!connected){connected=true;attempt=0;pointer={x:frameWidth/2,y:frameHeight/2};typingLimit();
            if(typing.size||[...pastes.values()].some(item=>item.state==='unavailable'))recoveryNotice();else notice(null);report('connected');
            window.BrowserHost?.postMessage(JSON.stringify({type:'display-capability',resizeSupported:true}));}
        }catch(_){notice('Waiting for a readable browser frame…');}
        finally{if(url)URL.revokeObjectURL(url);}
      }
    }finally{decoding=false;}
  };
  const receive=event=>{
    if(typeof event.data!=='string'||event.data.length>MAX_FRAME)return;
    let message;try{message=JSON.parse(event.data);}catch(_){return;}
    if(message.type==='frame'){
      const {width,height,sequence,data}=message;
      if(!Number.isInteger(width)||!Number.isInteger(height)||width<1||height<1||width*height>MAX_PIXELS||
          !Number.isInteger(sequence)||sequence<=lastSequence||typeof data!=='string'||data.length>MAX_FRAME-1024)return;
      lastSequence=sequence;latestFrame={...message,viewerEpoch:connectionEpoch};decodeFrames();
    }else if(message.type==='input-result'){
      const typed=typing.get(message.id);
      if(typed){
        if(message.ok===true){typing.delete(message.id);typingBytes-=typed.bytes;typingLimit();recovery();}
        else{typed.state='unavailable';recoveryNotice();}
        return;
      }
      const paste=pastes.get(message.id);
      if(paste&&paste.state==='pending'){
        paste.state=message.ok===true?'inserted':'unavailable';
        if(paste.state==='inserted')paste.text='';else failedText(paste.text);
      }else if(message.ok===false)notice('Browser input was not accepted. Reconnect and try again.');
    }
  };
  const connect=()=>{
    if(paused)return;clearTimeout(reconnect);report('connecting');notice('Connecting to Chrome…');
    const url=new URL('../socket',location.href);url.protocol=location.protocol==='https:'?'wss:':'ws:';
    connectionEpoch+=1;latestFrame=null;
    const current=new WebSocket(url.href,['binary']);socket=current;lastSequence=0;lastResolution=undefined;
    current.onmessage=event=>{if(socket===current)receive(event);};
    current.onclose=()=>{if(socket!==current)return;lost();if(!paused){if(typing.size)recoveryNotice();else notice('Browser disconnected. Reconnecting…');reconnect=setTimeout(connect,Math.min(10000,1000*2**Math.min(attempt++,4)));}};
    current.onerror=()=>{if(socket===current)notice('Unable to connect to the browser.');};
  };
  const resize=()=>{
    scale();
    if(!connected||!window.AgentShellViewportOptions?.adaptive||keyboardActive())return;
    const size=desktopSize(innerWidth,innerHeight,window.AgentShellViewportOptions.contentDensity);if(!size)return;
    if(lastDensity!==size.contentDensity){if(lastDensity!==undefined)setZoom(1);lastDensity=size.contentDensity;}
    const signature=JSON.stringify(size);
    if(signature!==lastResolution&&send({type:'resize',...size}))lastResolution=signature;
  };
  const point=(x,y)=>framePoint(x,y,canvas.getBoundingClientRect(),frameWidth,frameHeight);
  const move=(p,kind='move',button=0,extra={})=>{pointer=p;paintPointer();return send({type:'pointer',kind,x:p.x,y:p.y,button,frameWidth,frameHeight,...extra});};
  const click=(p,button=0)=>{move(p,'down',button);move(p,'up',button);};
  const scroll=(p,dx,dy)=>move(p,'scroll',0,{deltaX:Math.max(-4096,Math.min(4096,dx)),deltaY:Math.max(-4096,Math.min(4096,dy))});
  const showKeyboard=()=>{scale();keyboardFit=fitScale;keyboardUntil=Date.now()+2500;keyboard.value='';keyboard.focus({preventScroll:true});};
  const key=code=>{if(Number.isInteger(code)&&code>0){send({type:'key',keysym:code,down:true});send({type:'key',keysym:code,down:false});}};
  const pasteText=(id,text)=>{
    if(typeof id!=='string'||!/^[-A-Za-z0-9_.:]{1,128}$/.test(id)||typeof text!=='string'||!text.trim()||text.length>8192||text.includes('\0'))return 'invalid';
    if(pastes.has(id))return pastes.get(id).state;
    if(pastes.size>=64){const done=[...pastes].find(([,item])=>item.state!=='pending');if(done)pastes.delete(done[0]);else return 'unavailable';}
    if(!send({type:'text',text,method:'paste'},id))return 'unavailable';
    pastes.set(id,{state:'pending',text,order:++recoverySerial});return 'pending';
  };
  window.AgentShellViewer={
    pause(){release();paused=true;clearTimeout(reconnect);keyboard.blur();socket?.close(1000,'Viewer paused');lost();},
    fit(){setZoom(1);viewport.scrollLeft=0;viewport.scrollTop=0;},
    zoom(factor){if(Number.isFinite(factor))setZoom(zoom*factor);},key,keyboard:showKeyboard,pasteText,
    pasteStatus(id){return pastes.get(id)?.state||'unavailable';},
    mouse(){mouseMode=!mouseMode;byId('mouse').textContent=mouseMode?'Use direct touch':'Use mouse pointer';paintPointer();},
    controls(){keyboard.blur();if(controls.open)controls.close();else controls.showModal();},
  };
  const symbols={Escape:65307,Tab:65289,Enter:65293,Backspace:65288,Delete:65535,ArrowLeft:65361,ArrowUp:65362,ArrowRight:65363,ArrowDown:65364,Home:65360,End:65367,PageUp:65365,PageDown:65366,Shift:65505,Control:65507,Alt:65513,Meta:65511};
  keyboard.addEventListener('keydown',event=>{
    let symbol=symbols[event.key];
    if(!symbol&&(event.ctrlKey||event.metaKey||event.altKey)&&[...event.key].length===1)symbol=event.key.codePointAt(0);
    if(symbol){event.preventDefault();if(!keys.has(event.code)){keys.set(event.code,symbol);send({type:'key',keysym:symbol,down:true});}}
  });
  keyboard.addEventListener('keyup',event=>{const symbol=keys.get(event.code);if(symbol){event.preventDefault();send({type:'key',keysym:symbol,down:false});keys.delete(event.code);}});
  keyboard.addEventListener('compositionstart',()=>{composing=true;});
  const inputText=text=>{
    const bytes=new TextEncoder().encode(text).length;
    if(!text||text.length>8192||typingBytes+bytes>MAX_TYPING_BYTES){recovery();notice('Typing is paused while earlier text is checked. Open browser controls to review it.');return false;}
    const id=send({type:'text',text,method:'unicode'});if(!id){notice('Typing was not sent. Reconnect before typing more.');return false;}
    typing.set(id,{text,bytes,state:'pending',order:++recoverySerial});typingBytes+=bytes;typingLimit();recovery();return true;
  };
  const flush=()=>{if(!composing&&keyboard.value&&inputText(keyboard.value))keyboard.value='';};
  keyboard.addEventListener('compositionend',()=>{composing=false;setTimeout(flush,0);});
  keyboard.addEventListener('beforeinput',event=>{
    if(composing||event.isComposing)return;
    if(event.inputType==='deleteContentBackward'){event.preventDefault();key(65288);keyboard.value='';}
    else if(event.inputType==='deleteContentForward'){event.preventDefault();key(65535);keyboard.value='';}
    else if(event.inputType==='insertLineBreak'||event.inputType==='insertParagraph'){event.preventDefault();key(65293);keyboard.value='';}
    else if(typeof event.data==='string'&&event.data){event.preventDefault();if(inputText(event.data))keyboard.value='';}
  });
  keyboard.addEventListener('input',flush);
  keyboard.addEventListener('blur',release);
  viewport.addEventListener('pointerdown',event=>{
    if(!connected||!frameWidth)return;
    const bounds=canvas.getBoundingClientRect();if(event.clientX<bounds.left||event.clientY<bounds.top||event.clientX>=bounds.left+bounds.width||event.clientY>=bounds.top+bounds.height)return;
    event.preventDefault();viewport.setPointerCapture?.(event.pointerId);
    if(event.pointerType==='mouse'){move(point(event.clientX,event.clientY),'down',event.button);return;}
    touches.set(event.pointerId,{x:event.clientX,y:event.clientY});
    if(touches.size===1){gesture={x:event.clientX,y:event.clientY,lastX:event.clientX,lastY:event.clientY,moved:false,multi:false,drag:false};
      longPress=setTimeout(()=>{if(gesture&&!gesture.moved&&!gesture.multi){gesture.drag=true;move(mouseMode?pointer:point(gesture.x,gesture.y),'down');}},600);
    }else{clearTimeout(longPress);if(gesture?.drag)move(pointer,'up');if(gesture)gesture.multi=true;}
  });
  viewport.addEventListener('pointermove',event=>{
    if(!connected||!frameWidth)return;
    if(event.pointerType==='mouse'){move(point(event.clientX,event.clientY));return;}
    if(!touches.has(event.pointerId)||!gesture)return;event.preventDefault();touches.set(event.pointerId,{x:event.clientX,y:event.clientY});
    if(touches.size>=2){
      const points=[...touches.values()],a=points[0],b=points[1],distance=Math.hypot(a.x-b.x,a.y-b.y),center={x:(a.x+b.x)/2,y:(a.y+b.y)/2};
      if(!gesture.distance){gesture.distance=distance;gesture.baseZoom=zoom;gesture.center=center;return;}
      if(Math.abs(distance-gesture.distance)>6)setZoom(gesture.baseZoom*distance/gesture.distance);
      if(zoom>1){viewport.scrollLeft-=center.x-gesture.center.x;viewport.scrollTop-=center.y-gesture.center.y;paintPointer();}
      else if(Math.abs(distance-gesture.distance)<=6)scroll(pointer,(gesture.center.x-center.x)/Math.max(fitScale,0.01),(gesture.center.y-center.y)/Math.max(fitScale,0.01));
      gesture.center=center;return;
    }
    if(gesture.multi)return;
    const dx=event.clientX-gesture.lastX,dy=event.clientY-gesture.lastY;
    if(Math.hypot(event.clientX-gesture.x,event.clientY-gesture.y)>8){gesture.moved=true;clearTimeout(longPress);}
    if(mouseMode){const r=canvas.getBoundingClientRect();move({x:Math.max(0,Math.min(frameWidth-1,pointer.x+dx*frameWidth/r.width)),y:Math.max(0,Math.min(frameHeight-1,pointer.y+dy*frameHeight/r.height))});}
    else if(gesture.drag)move(point(event.clientX,event.clientY));
    else if(gesture.moved)scroll(point(gesture.x,gesture.y),-dx/Math.max(fitScale,0.01),-dy/Math.max(fitScale,0.01));
    gesture.lastX=event.clientX;gesture.lastY=event.clientY;
  });
  const endTouch=event=>{
    clearTimeout(longPress);
    if(event.pointerType==='mouse'){move(point(event.clientX,event.clientY),'up',event.button);return;}
    if(!touches.has(event.pointerId))return;touches.delete(event.pointerId);
    if(gesture?.drag)move(pointer,'up');
    else if(gesture&&!gesture.multi&&!gesture.moved&&event.type!=='pointercancel')click(mouseMode?pointer:point(event.clientX,event.clientY));
    if(!touches.size)gesture=null;
  };
  viewport.addEventListener('pointerup',endTouch);viewport.addEventListener('pointercancel',endTouch);
  viewport.addEventListener('wheel',event=>{event.preventDefault();if(connected)scroll(point(event.clientX,event.clientY),event.deltaX,event.deltaY);},{passive:false});
  viewport.addEventListener('contextmenu',event=>event.preventDefault());viewport.addEventListener('scroll',paintPointer);
  byId('fit').onclick=()=>window.AgentShellViewer.fit();byId('mouse').onclick=()=>window.AgentShellViewer.mouse();
  byId('right-click').onclick=()=>click(pointer,2);byId('show-keyboard').onclick=()=>{controls.close();showKeyboard();};
  const discardAndReconnect=()=>{
    typing.clear();typingBytes=0;for(const item of pastes.values())if(item.state==='unavailable')item.text='';
    recovery();typingLimit();clearTimeout(reconnect);socket?.close(1000,'Reconnect requested');report('disconnected');
  };
  byId('close-controls').onclick=()=>controls.close();byId('clear-retained').onclick=discardAndReconnect;
  byId('copy-retained').onclick=async()=>{
    const field=byId('retained-text'),text=field.value,known=new Set(typing.keys());
    const knownPastes=new Map([...pastes].filter(([,item])=>item.state==='unavailable').map(([id,item])=>[id,item.text]));let copied=false;
    try{if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(text);copied=true;}}catch(_){}
    if(!copied){field.focus();field.select();try{copied=document.execCommand('copy')===true;}catch(_){}}
    if(copied){
      const noNewText=[...typing.keys()].every(id=>known.has(id))&&[...pastes].every(([id,item])=>item.state!=='unavailable'||!item.text||knownPastes.get(id)===item.text);
      if(noNewText)discardAndReconnect();else notice('Earlier text was copied. Review the newer text before reconnecting.');
    }else notice('Copy was not confirmed. Select and copy the visible text before reconnecting.');
  };
  window.addEventListener('blur',release);window.addEventListener('pagehide',()=>window.AgentShellViewer.pause());
  document.addEventListener('visibilitychange',()=>{if(document.hidden)window.AgentShellViewer.pause();else if(paused){paused=false;connect();}});
  window.addEventListener('resize',resize);setInterval(resize,250);connect();
})();
