/* Trusted host owns identity; opaque iframe owns only the interactive document. */
const frame = document.getElementById('widget');
let current = {};
let state = null;
let ready = false;
// Also size agent-authored documents that don't include the optional canvas runtime.
function installDocumentBridge() {
  const send = data => parent.postMessage(data,'*');
  new ResizeObserver(() => send({type:'widget-resize',height:document.body.getBoundingClientRect().height})).observe(document.body);
  addEventListener('error', () => send({type:'widget-error'}));
  addEventListener('focusin', event => {
    const field = event.target;
    if (!(field instanceof Element) || !field.matches('input:not([type=range]):not([type=checkbox]):not([type=radio]),textarea,select')) return;
    setTimeout(() => {
      if (document.activeElement !== field || !navigator.userActivation.isActive) return;
      const rect = field.getBoundingClientRect();
      send({type:'widget-focus',top:rect.top,bottom:rect.bottom});
    },100);
  });
  send({type:'widget-ready'});
}
const bootstrap = `<script>(${installDocumentBridge.toString()})();<\/script>`;
window.mountWidget = function(data) {
  current = data;
  state = data.state || null;
  frame.title = data.title || 'Interactive agent widget';
  ready = false;
  const closingBody = data.html.toLowerCase().lastIndexOf('</body>');
  frame.srcdoc = closingBody >= 0 ? data.html.slice(0,closingBody)+bootstrap+data.html.slice(closingBody) : data.html+bootstrap;
};
window.setWidgetActive = active => frame.contentWindow?.postMessage({type:'widget-active',active}, '*');
window.addEventListener('message', event => {
  if (event.source !== frame.contentWindow || !event.data || typeof event.data !== 'object') return;
  const data = event.data;
  if (data.type === 'widget-ready' && !ready) {
    ready = true;
    frame.contentWindow.postMessage({type:'widget-context',theme:current.theme || (matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'),state}, '*');
    if (window.WidgetHost) WidgetHost.ready();
  }
  if (data.type === 'widget-error' && window.WidgetHost) WidgetHost.failed();
  if (data.type === 'widget-focus' && Number.isFinite(data.top) && Number.isFinite(data.bottom) && window.WidgetHost) {
    WidgetHost.focusField(data.top,data.bottom);
  }
  if (ready && data.type === 'widget-state' && data.state && typeof data.state === 'object') {
    const encoded = JSON.stringify(data.state);
    if (encoded.length <= 32768) {
      state = data.state;
      if (window.WidgetHost) WidgetHost.saveState(encoded);
      else window.dispatchEvent(new CustomEvent('widget-state',{detail:{widgetId:current.id,state}}));
    }
  }
  if (data.type === 'widget-resize' && Number.isFinite(data.height)) {
    const height = Math.max(100,Math.min(1600,Math.ceil(data.height)));
    frame.style.height = height+'px';
    if (window.WidgetHost) WidgetHost.resize(height);
  }
  if (data.type === 'send-prompt' && typeof data.text === 'string' && data.text.trim() && data.text.length <= 4000) {
    if (window.WidgetHost) WidgetHost.requestFollowUp(data.text);
    else window.dispatchEvent(new CustomEvent('widget-action',{detail:{widgetId:current.id,text:data.text}}));
  }
});
