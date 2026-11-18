/* Trusted host: the opaque document never receives a native object or credentials. */
const frame = document.getElementById('widget');
let current = {}, state = null, ready = false, mount = 0, nativeHeight = null;
let scrollState = {canScrollUp:false,canScrollDown:false};
const native = (type, fields = {}) => window.WidgetHost?.postMessage(JSON.stringify({type,...fields}));
// Read once from the trusted local host, before mounting the opaque document.
const defaultStyles = fetch(new URL('mobile.css', location.href)).then(response => {
  if (!response.ok) throw new Error('Missing local design system');
  return response.text();
});
function installDocumentBridge(mount) {
  const send = data => parent.postMessage({...data,mount},'*');
  let layoutFrame, lastHeight, lastUp, lastDown;
  const reportLayout = () => {
    layoutFrame = null;
    const height = Math.ceil(document.body.getBoundingClientRect().height);
    if (height !== lastHeight) {
      lastHeight = height;
      send({type:'widget-resize',height});
    }
    const root = document.scrollingElement || document.documentElement;
    // Root overflow is propagated from body when html leaves it visible.
    // An intentionally clipped document must not capture the chat's gestures.
    const htmlOverflow = getComputedStyle(document.documentElement).overflowY;
    const overflow = htmlOverflow === 'visible' ? getComputedStyle(document.body).overflowY : htmlOverflow;
    const scrollable = overflow !== 'hidden' && overflow !== 'clip';
    const up = scrollable && root.scrollTop > 1;
    const down = scrollable && root.scrollHeight - root.clientHeight - root.scrollTop > 1;
    if (up !== lastUp || down !== lastDown) {
      lastUp = up; lastDown = down;
      send({type:'widget-scroll',canScrollUp:up,canScrollDown:down});
    }
  };
  const scheduleLayout = () => {
    if (layoutFrame == null) layoutFrame = requestAnimationFrame(reportLayout);
  };
  const resize = new ResizeObserver(scheduleLayout);
  resize.observe(document.body);
  resize.observe(document.documentElement);
  new MutationObserver(scheduleLayout).observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
  addEventListener('scroll',scheduleLayout,{capture:true,passive:true});
  addEventListener('resize',scheduleLayout);
  for (const event of ['load','input','change','transitionend','animationend']) addEventListener(event,scheduleLayout,true);
  document.fonts?.ready.then(scheduleLayout);
  document.fonts?.addEventListener('loadingdone',scheduleLayout);
  scheduleLayout();
  addEventListener('error', () => send({type:'widget-error'}));
  const fieldSelector = 'input:not([type=range]):not([type=checkbox]):not([type=radio]),textarea,select';
  const reportFocus = (field, type) => {
    if (!(field instanceof Element) || field !== document.activeElement || !field.matches(fieldSelector)) return;
    const rect = field.getBoundingClientRect();
    if (rect.height > 0) send({type,top:rect.top,bottom:rect.bottom});
  };
  addEventListener('message', event => {
    if (event.source !== parent) return;
    if (event.data?.type === 'widget-focus-request' && event.data.mount === mount) {
      reportFocus(document.activeElement,'widget-focus-reveal');
      return;
    }
    if (event.data?.type !== 'widget-context') return;
    document.documentElement.dataset.theme = event.data.theme === 'dark' ? 'dark' : 'light';
    scheduleLayout();
  });
  addEventListener('focusin', event => {
    const field = event.target;
    if (!(field instanceof Element) || !field.matches(fieldSelector)) return;
    setTimeout(() => {
      if (document.activeElement !== field || !navigator.userActivation.isActive) return;
      reportFocus(field,'widget-focus');
    },100);
  });
  send({type:'widget-ready'});
}
const policy = "default-src 'none';script-src 'unsafe-inline' https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/;style-src 'unsafe-inline' https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/;img-src data: blob: https://appassets.androidplatform.net/assets/ui/ https://appassets.androidplatform.net/assets/ui-poc/ https://basemap.nationalmap.gov;connect-src 'none';font-src data:;object-src 'none';frame-src 'none';worker-src 'none';base-uri 'none';form-action 'none'";
window.mountWidget = async function(data) {
  current = data; state = data.state || null; ready = false;
  const documentMount = ++mount;
  nativeHeight = null;
  scrollState = {canScrollUp:false,canScrollDown:false};
  native('scroll',scrollState);
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
  const bootstrap = `<script>(${installDocumentBridge.toString()})(${documentMount});<\/script>`;
  const closing = html.toLowerCase().lastIndexOf('</body>');
  frame.style.height = '';
  frame.srcdoc = closing >= 0 ? html.slice(0,closing)+bootstrap+html.slice(closing) : html+bootstrap;
};
window.setWidgetActive = active => frame.contentWindow?.postMessage({type:'widget-active',active}, '*');
// Native calls this after the keyboard changes layout, before revealing the field.
window.requestWidgetFocus = () => {
  if (ready) frame.contentWindow?.postMessage({type:'widget-focus-request',mount}, '*');
};
window.addEventListener('message', event => {
  if (event.source !== frame.contentWindow || !event.data || typeof event.data !== 'object') return;
  const data = event.data;
  // WindowProxy survives srcdoc navigation; ignore queued layout from the old document.
  if (['widget-ready','widget-error','widget-focus','widget-focus-reveal','widget-resize','widget-scroll'].includes(data.type) && data.mount !== mount) return;
  if (data.type === 'widget-ready' && !ready) {
    ready = true;
    frame.contentWindow.postMessage({type:'widget-context',theme:current.theme || (matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'),state}, '*');
    native('ready');
  }
  if (data.type === 'widget-error') native('failed');
  if ((data.type === 'widget-focus' || data.type === 'widget-focus-reveal') && Number.isFinite(data.top) && Number.isFinite(data.bottom)) {
    const offset = frame.getBoundingClientRect().top;
    native(data.type === 'widget-focus' ? 'focus' : 'focus-reveal',{top:data.top+offset,bottom:data.bottom+offset});
  }
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
    frame.style.height = height+'px';
    if (height !== nativeHeight) { nativeHeight = height; native('resize',{value:height}); }
  }
  if (data.type === 'widget-scroll' && typeof data.canScrollUp === 'boolean' && typeof data.canScrollDown === 'boolean') {
    if (data.canScrollUp !== scrollState.canScrollUp || data.canScrollDown !== scrollState.canScrollDown) {
      scrollState = {canScrollUp:data.canScrollUp,canScrollDown:data.canScrollDown};
      native('scroll',scrollState);
    }
  }
  if (data.type === 'send-prompt' && typeof data.text === 'string' && data.text.trim() && new TextEncoder().encode(data.text).length <= 4000 &&
      navigator.userActivation.isActive && document.visibilityState === 'visible') {
    if (window.WidgetHost) native('action',{text:data.text});
    else window.dispatchEvent(new CustomEvent('widget-action',{detail:{widgetId:current.id,text:data.text}}));
  }
});
