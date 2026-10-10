/* POC host: generated documents never receive credentials or a terminal bridge. */
const frame=document.getElementById('widget');
let widgetId='';
window.mountWidget=function(data){widgetId=data.id;frame.srcdoc=data.html;};
window.addEventListener('message',event=>{
 if(event.source!==frame.contentWindow || !event.data || typeof event.data!=='object')return;
 const data=event.data;
 if(data.type==='widget-resize' && Number.isFinite(data.height)){
  const height=Math.max(100,Math.min(1200,Math.ceil(data.height)));
  frame.style.height=height+'px';
  if(window.WidgetHost)WidgetHost.resize(height);
 }
 if(data.type==='send-prompt' && typeof data.text==='string' && data.text.trim() && data.text.length<=4000){
  if(window.WidgetHost)WidgetHost.requestFollowUp(data.text);
  else window.dispatchEvent(new CustomEvent('widget-action',{detail:{widgetId,text:data.text}}));
 }
});
