/* Narrow iframe bridge; no account, file, network or terminal capabilities. */
const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const money = value => new Intl.NumberFormat('en-US', {style:'currency',currency:'USD'}).format(value);
const tellHost = data => parent.postMessage(data, '*');
window.sendPrompt = text => tellHost({type:'send-prompt',text});
window.widgetState = {};
let restoring = false;
function saveState() {
  if (restoring) return;
  const inputs = {};
  $$('input[id],select[id]').forEach(e => inputs[e.id] = e.type === 'checkbox' ? e.checked : e.value);
  tellHost({type:'widget-state',state:{inputs,custom:window.widgetState}});
}
document.addEventListener('input', saveState, true);
document.addEventListener('change', saveState, true);
window.addEventListener('message', event => {
  if (event.source !== parent || !event.data || typeof event.data !== 'object') return;
  const data = event.data;
  if (data.type === 'widget-context') {
    document.documentElement.dataset.theme = data.theme === 'dark' ? 'dark' : 'light';
    if (data.state && typeof data.state === 'object') {
      restoring = true;
      for (const [id,value] of Object.entries(data.state.inputs || {})) {
        const element = document.getElementById(id);
        if (!(element instanceof HTMLInputElement || element instanceof HTMLSelectElement)) continue;
        if (element.type === 'checkbox') element.checked = value === true;
        else if (typeof value === 'string') element.value = value;
        element.dispatchEvent(new Event('input'));
      }
      window.widgetState = data.state.custom || {};
      window.dispatchEvent(new CustomEvent('widget-restore'));
      restoring = false;
    }
    window.dispatchEvent(new CustomEvent('widget-theme'));
  }
  if (data.type === 'widget-active') window.dispatchEvent(new CustomEvent('widget-visibility',{detail:data.active === true}));
});
window.addEventListener('error', () => tellHost({type:'widget-error',message:'This interactive view could not load. Try reopening it.'}));
let resizeFrame;
new ResizeObserver(() => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => tellHost({type:'widget-resize',height:Math.ceil(document.body.getBoundingClientRect().height)}));
}).observe(document.body);
// Defer the handshake until each widget has registered its state restoration handlers.
queueMicrotask(() => tellHost({type:'widget-ready'}));
