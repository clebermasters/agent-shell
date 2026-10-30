/* Adapter for the checksum-pinned KasmVNC 1.5.0 client. No backend credentials. */
(() => {
  // Keep Default byte-for-byte compatible with the existing logical desktop.
  // Optional density changes the remote resolution, not the local fit/zoom.
  const contentDensity = value => [75,100,125,150,200].includes(value) ? value : 100;
  const desktopSize = (w, h, value) => {
    if (!Number.isFinite(w) || !Number.isFinite(h) || w < 100 || h < 100) return null;
    let factor = Math.max(1, 640/w, 360/h);
    factor *= Math.min(1, 1600/(w*factor), 2160/(h*factor));
    const density = contentDensity(value);
    if (density === 100) return {width:Math.round(w*factor/2)*2, height:Math.round(h*factor/2)*2, density, capped:false};
    factor *= density/100;
    const limit = Math.min(1, 2560/(w*factor), 3840/(h*factor), Math.sqrt(6000000/(w*h*factor*factor)));
    factor *= limit;
    return {width:Math.max(2,Math.floor((w*factor+1e-6)/2)*2), height:Math.max(2,Math.floor((h*factor+1e-6)/2)*2), density, capped:limit<1};
  };
  window.AgentShellBrowserGeometry = Object.freeze({desktopSize, contentDensity});
  const embeddedWebView = !!window.BrowserHost || /;\s*wv\)/i.test(navigator.userAgent);
  const layout = document.createElement('style');
  layout.textContent = `
    #noVNC_control_bar { width: 60px; }
    #noVNC_control_bar .noVNC_button_div { font-size: 0; text-align: center; }
    #noVNC_control_bar .noVNC_scroll {
      max-height: calc(var(--agentshell-viewer-height, 100vh) - 12px) !important;
    }
    .noVNC_panel {
      box-sizing: border-box;
      font-size: 14px; text-align: left;
      max-height: calc(var(--agentshell-viewer-height, 100vh) - 16px) !important;
      width: min(330px, calc(var(--agentshell-viewer-width, 100vw) - 91px));
    }
    .noVNC_panel label { white-space: normal; }
    .noVNC_panel input, .noVNC_panel select, .noVNC_panel textarea { max-width: 100%; }
    .noVNC_panel .switch {
      box-sizing: border-box;
      width: 100%; height: auto; min-height: 22px;
      margin: 8px 0; padding-left: 42px;
    }
    .noVNC_panel .switch input { position: absolute; }
    .noVNC_panel .switch .slider {
      width: 30px; height: 16px; top: 2px; right: auto; bottom: auto;
    }
    .noVNC_panel .slider-label { display: block; padding-left: 0; line-height: 1.35; }
  `;
  document.head.append(layout);
  const report = state => window.BrowserHost?.postMessage(JSON.stringify({type: 'connection', state}));
  const modifiers = [[65507, 'ControlLeft'], [65508, 'ControlRight'], [65513, 'AltLeft'],
    [65514, 'AltRight'], [65505, 'ShiftLeft'], [65506, 'ShiftRight'], [65515, 'MetaLeft']];
  const releaseModifiers = rfb => {
    for (const [keysym, code] of modifiers) rfb.sendKey(keysym, code, false);
  };
  const pasteReceipts = new WeakMap();
  const pasteText = (ui, id, value) => {
    if (typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id) ||
        typeof value !== 'string' || !value.trim() || value.length > 8192 || value.includes('\0')) return 'invalid';
    const rfb = ui.rfb;
    if (!ui.connected || !rfb || rfb._rfbConnectionState !== 'connected' || rfb._viewOnly) return 'unavailable';
    if (typeof rfb.clipboardPasteFrom !== 'function' || typeof rfb.sendKey !== 'function') return 'unsupported';
    let receipts = pasteReceipts.get(rfb);
    if (!receipts) { receipts = new Set(); pasteReceipts.set(rfb, receipts); }
    if (receipts.has(id)) return 'duplicate';
    try {
      mouse?.release();
      releaseModifiers(rfb);
      // Explicit dictation must replace the remote clipboard even if another
      // app changed it since an identical previous transcript. Keep automatic
      // local clipboard synchronization disabled; send only the supplied text.
      rfb._clipHash = null;
      rfb.clipboardPasteFrom(value);
      rfb.sendKey(65507, 'ControlLeft', true);
      rfb.sendKey(118, 'KeyV', true);
      rfb.sendKey(118, 'KeyV', false);
      rfb.sendKey(65507, 'ControlLeft', false);
      receipts.add(id);
      while (receipts.size > 64) receipts.delete(receipts.values().next().value);
      return 'inserted';
    } catch (_) {
      // A partial send has an uncertain outcome. Native UI retains the text
      // for an explicit retry; it must not automatically replay the input.
      return 'unavailable';
    } finally {
      try { rfb.sendKey(118, 'KeyV', false); rfb.sendKey(65507, 'ControlLeft', false); } catch (_) { /* disconnected */ }
    }
  };
  let lastDisplaySupport, lastState, lastSize, zoom = 1, pinchStart, panStart, mouse;
  let fitScale, keyboardFit, keyboardRequestedUntil = 0, keyboardWasVisible = false;
  const keyboardActive = () => {
    const visible = !!window.AgentShellViewportOptions?.keyboardVisible;
    if (visible) keyboardWasVisible = true;
    else if (keyboardWasVisible) {
      keyboardWasVisible = false;
      keyboardRequestedUntil = 0;
      keyboardFit = undefined;
    }
    return visible || Date.now() < keyboardRequestedUntil;
  };
  const scale = rfb => {
    if (!rfb?._fbWidth || !rfb?._fbHeight) return;
    const screen = rfb._screen, rect = screen.getBoundingClientRect();
    let fit = Math.min(rect.width / rfb._fbWidth, rect.height / rfb._fbHeight);
    if (!(fit > 0)) return;
    // The IME crops the viewer; it must not change the desktop's apparent size.
    // Keep the fit scale from before it opened, independently of the zoom factor.
    if (keyboardActive()) {
      keyboardFit ??= fitScale ?? fit;
      fit = keyboardFit;
    } else {
      fitScale = fit;
      keyboardFit = undefined;
    }
    const value = fit * zoom;
    if (rfb._display.scale !== value) rfb._display.scale = value;
    rfb._display.screens[0].scale = value;
    rfb._canvas.style.flexShrink = '0';
    screen.style.justifyContent = zoom > 1 ? 'flex-start' : 'center';
    screen.style.alignItems = zoom > 1 ? 'flex-start' : 'center';
    if (zoom === 1) { screen.scrollLeft = 0; screen.scrollTop = 0; }
  };
  const setZoom = (rfb, value) => { zoom = Math.max(1, Math.min(4, value)); scale(rfb); };
  const prepareKeyboard = rfb => {
    if (!rfb?._fbWidth || keyboardActive()) return;
    scale(rfb);
    keyboardFit = fitScale;
    // Protect the first resize before Compose delivers its IME-visible signal.
    // A failed keyboard request must not suspend adaptation indefinitely.
    keyboardRequestedUntil = Date.now() + 2500;
  };
  const pan = (rfb, dx, dy) => {
    if (zoom <= 1) return;
    rfb._screen.scrollLeft -= dx;
    rfb._screen.scrollTop -= dy;
  };
  const adaptDesktop = ui => {
    const options=window.AgentShellViewportOptions, rfb=ui.rfb;
    if (!options?.adaptive || !ui.connected || !rfb?._supportsSetDesktopSize) return;
    if (keyboardActive() || innerWidth<100 || innerHeight<100) {
      if (rfb._resizeTimeout!==null) {
        clearTimeout(rfb._resizeTimeout);rfb._resizeTimeout=null;
        // This size was scheduled but never applied. Clear the request and
        // forced dimensions so the client's change-detecting setters schedule
        // it again when the keyboard closes, even for the same viewport size.
        rfb.agentShellDesktopSize=null;
        rfb.forcedResolutionX=0;rfb.forcedResolutionY=0;
      }
      return;
    }
    const geometry=desktopSize(innerWidth,innerHeight,options.contentDensity);
    if(!geometry) return;
    const {width,height,density}=geometry;
    if(rfb.agentShellContentDensity!==density) {
      // A deliberate size choice returns to fit; ordinary pinch/rotation keep
      // their existing behavior. Defer this until the keyboard is closed.
      if(rfb.agentShellContentDensity!==undefined) {zoom=1;keyboardFit=undefined;}
      rfb.agentShellContentDensity=density;
    }
    const size=`${width}:${height}`;
    if (rfb.agentShellDesktopSize===size) return;
    rfb.agentShellDesktopSize=size;
    mouse?.release();
    rfb.enableHiDpi=false;rfb.antiAliasing=1;rfb.resizeSession=false;
    rfb.forcedResolutionX=width;rfb.forcedResolutionY=height;
    rfb.updateConnectionSettings();
  };
  const wait = setInterval(() => {
    const ui = window.AgentShellUI;
    if (!ui) return;
    if (embeddedWebView && !ui.agentShellKeyboardObserved) {
      ui.agentShellKeyboardObserved = true;
      const showKeyboard = ui.showVirtualKeyboard.bind(ui);
      ui.showVirtualKeyboard = () => { prepareKeyboard(ui.rfb); showKeyboard(); };
    }
    if (embeddedWebView && ui.connected && mouse?.rfb !== ui.rfb) {
      mouse?.dispose();
      ui.agentShellPointerPolicy = true;
      // The API exists in WebView but rejects requests. Clear stale preferences
      // and remove the checkbox row as well as the game's sidebar control.
      ui.forceSetting('pointer_lock', false);
      document.getElementById('noVNC_setting_pointer_lock')?.closest('li')?.classList.add('noVNC_hidden');
      mouse = new window.AgentShellTouchMouse(ui, delta => setZoom(ui.rfb, zoom * delta),
        (dx, dy) => pan(ui.rfb, dx, dy));
    }
    // WebView can resolve vh to zero when a Compose AndroidView first attaches.
    // Refresh explicit CSS pixels only when the real viewport changes.
    const size = `${innerWidth}:${innerHeight}`;
    if (innerHeight > 0 && size !== lastSize) {
      lastSize = size;
      document.documentElement.style.setProperty('--agentshell-viewer-height', `${innerHeight}px`);
      document.documentElement.style.setProperty('--agentshell-viewer-width', `${innerWidth}px`);
      for (const element of [document.documentElement, document.body, document.getElementById('noVNC_container')]) {
        element?.style.setProperty('height', `${innerHeight}px`, 'important');
      }
      window.dispatchEvent(new Event('resize'));
    }
    const state = ui.connected ? 'connected' : (ui.rfb?._rfbConnectionState || 'connecting');
    if (state !== lastState) { report(state); lastState = state; }
    if(ui.connected) {
      const supported=!!ui.rfb?._supportsSetDesktopSize;
      if(supported!==lastDisplaySupport) {
        window.BrowserHost?.postMessage(JSON.stringify({type:'display-capability',resizeSupported:supported}));
        lastDisplaySupport=supported;
      }
    }
    adaptDesktop(ui);
    if (ui.connected) scale(ui.rfb);
    mouse?.paint();
    window.AgentShellViewer = {
      keyboard() { ui.showVirtualKeyboard(); },
      key(value) { if (ui.connected) ui.rfb.sendKey(value, null); },
      controls() {
        // The native options button must cancel the client's startup auto-close.
        // Leave IME focus to the explicit keyboard control, rather than popping
        // the keyboard when a settings icon is tapped.
        ui.keepControlbar(); ui.toggleControlbar(); ui.hideVirtualKeyboard();
      },
      pause() {
        mouse?.release();
        if (ui.connected) releaseModifiers(ui.rfb);
        ui.disconnect(); report('disconnected');
      },
      fit() { setZoom(ui.rfb, 1); },
      zoom(delta) { if (ui.connected) setZoom(ui.rfb, zoom * delta); },
      mouse() { mouse?.set(!mouse.enabled); },
      mousePosition() { return mouse?.position(); },
      pasteText(id, value) { return pasteText(ui, id, value); },
      text(value) {
        if (!ui.connected || typeof value !== 'string' || value.length > 8192) return;
        for (const character of value) {
          const point = character.codePointAt(0);
          ui.rfb.sendKey(point <= 255 ? point : 0x01000000 | point, null);
        }
      },
    };
    if (ui.rfb && !ui.rfb.agentShellObserved) {
      ui.rfb.agentShellObserved = true;
      const updateScale = ui.rfb._updateScale.bind(ui.rfb);
      ui.rfb._updateScale = () => {
        const screen = ui.rfb._screen, x = screen.scrollLeft, y = screen.scrollTop;
        updateScale(); scale(ui.rfb);
        // The stock fit temporarily shrinks the canvas and clamps its scroll
        // offsets. Restore the position after applying our retained scale.
        if (zoom > 1) { screen.scrollLeft = x; screen.scrollTop = y; }
      };
      // Pinch affects this viewer only. Two-finger dragging pans a zoomed viewer.
      // Ordinary taps, one-finger dragging and scrolling retain KasmVNC handling.
      const gesture = event => {
        if (mouse?.enabled) { event.stopImmediatePropagation(); event.preventDefault(); return; }
        if (event.detail.type === 'pinch') {
          event.stopImmediatePropagation(); event.preventDefault();
          const distance = Math.hypot(event.detail.magnitudeX, event.detail.magnitudeY);
          if (event.type === 'gesturestart') pinchStart = { distance, zoom };
          else if (event.type === 'gesturemove' && pinchStart?.distance) setZoom(ui.rfb, pinchStart.zoom * distance / pinchStart.distance);
          else if (event.type === 'gestureend') pinchStart = null;
        } else if (event.detail.type === 'twodrag' && zoom > 1) {
          event.stopImmediatePropagation(); event.preventDefault();
          const screen = ui.rfb._screen;
          if (event.type === 'gesturestart') panStart = { x: screen.scrollLeft, y: screen.scrollTop };
          else if (event.type === 'gesturemove' && panStart) {
            screen.scrollLeft = panStart.x - event.detail.magnitudeX;
            screen.scrollTop = panStart.y - event.detail.magnitudeY;
          } else if (event.type === 'gestureend') panStart = null;
        }
      };
      for (const type of ['gesturestart', 'gesturemove', 'gestureend']) ui.rfb._canvas.addEventListener(type, gesture, { capture: true });
      scale(ui.rfb);
      ui.rfb.addEventListener('disconnect', () => { report('disconnected'); lastState = 'disconnected'; });
      ui.rfb.addEventListener('connect', () => { report('connected'); lastState = 'connected'; });
    }
  }, 250);
  window.addEventListener('pagehide', () => { clearInterval(wait); window.AgentShellViewer?.pause(); });
})();
