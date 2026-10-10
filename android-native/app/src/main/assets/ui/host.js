/* Trusted host: the opaque document never receives a native object or credentials. */
const frame = document.getElementById('widget');
let current = {}, state = null, ready = false;
const native = (type, fields = {}) => window.WidgetHost?.postMessage(JSON.stringify({type,...fields}));
// Read once from the trusted local host, before mounting the opaque document.
const defaultStyles = fetch(new URL('mobile.css', location.href)).then(response => {
  if (!response.ok) throw new Error('Missing local design system');
  return response.text();
});
function installDocumentBridge() {
  const send = data => parent.postMessage(data,'*');
  new ResizeObserver(() => send({type:'widget-resize',height:document.body.getBoundingClientRect().height})).observe(document.body);
  addEventListener('error', () => send({type:'widget-error'}));
  addEventListener('message', event => {
    if (event.source !== parent || event.data?.type !== 'widget-context') return;
    document.documentElement.dataset.theme = event.data.theme === 'dark' ? 'dark' : 'light';
  });
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
const policy = "default-src 'none';script-src 'unsafe-inline' https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/;style-src 'unsafe-inline' https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/;img-src data: blob: https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/ https://basemap.nationalmap.gov;connect-src 'none';font-src data:;object-src 'none';frame-src 'none';worker-src 'none';base-uri 'none';form-action 'none'";
window.mountWidget = async function(data) {
  current = data; state = data.state || null; ready = false;
  let css;
  try { css = await defaultStyles; }
  catch { native('failed'); return; }
  if (current !== data) return;
  frame.title = data.title || 'Interactive agent widget';
  let html = data.html;
  // Policy precedes every generated script; document policies can only tighten it.
  const head = `<meta http-equiv="Content-Security-Policy" content="${policy}"><style>${css}</style>`;
  // Prepend to the document so even content before an explicit head is constrained.
  html = '<!doctype html>'+head+html;
  const closing = html.toLowerCase().lastIndexOf('</body>');
  frame.srcdoc = closing >= 0 ? html.slice(0,closing)+bootstrap+html.slice(closing) : html+bootstrap;
};
window.setWidgetActive = active => frame.contentWindow?.postMessage({type:'widget-active',active}, '*');
window.addEventListener('message', event => {
  if (event.source !== frame.contentWindow || !event.data || typeof event.data !== 'object') return;
  const data = event.data;
  if (data.type === 'widget-ready' && !ready) {
    ready = true;
    frame.contentWindow.postMessage({type:'widget-context',theme:current.theme || (matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'),state}, '*');
    native('ready');
  }
  if (data.type === 'widget-error') native('failed');
  if (data.type === 'widget-focus' && Number.isFinite(data.top) && Number.isFinite(data.bottom)) native('focus',{top:data.top,bottom:data.bottom});
  if (ready && data.type === 'widget-state' && data.state && typeof data.state === 'object') {
    const encoded = JSON.stringify(data.state);
    if (new TextEncoder().encode(encoded).length <= 32768) {
      state = data.state;
      if (window.WidgetHost) native('state',{value:encoded});
      else window.dispatchEvent(new CustomEvent('widget-state',{detail:{widgetId:current.id,state}}));
    }
  }
  if (data.type === 'widget-resize' && Number.isFinite(data.height)) {
    const height = Math.max(100,Math.min(1600,Math.ceil(data.height)));
    frame.style.height = height+'px'; native('resize',{value:height});
  }
  if (data.type === 'send-prompt' && typeof data.text === 'string' && data.text.trim() && new TextEncoder().encode(data.text).length <= 4000 &&
      navigator.userActivation.isActive && document.visibilityState === 'visible') {
    if (window.WidgetHost) native('action',{text:data.text});
    else window.dispatchEvent(new CustomEvent('widget-action',{detail:{widgetId:current.id,text:data.text}}));
  }
});
