/* Touch trackpad for the pinned KasmVNC client. Uses ordinary absolute mouse
 * events on the wire; Android WebView pointer lock is never requested. */
(() => {
  const arrow = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="26" viewBox="0 0 24 26"><path d="M3 2L3 22L8 17L12 25L16 23L12 15L20 15Z" fill="white" stroke="#101828" stroke-width="1.6" stroke-linejoin="round"/></svg>';
  window.AgentShellTouchMouse = class {
    constructor(ui, zoom, pan) {
      this.ui = ui; this.rfb = ui.rfb; this.zoom = zoom; this.pan = pan;
      this.x = this.rfb._fbWidth / 2; this.y = this.rfb._fbHeight / 2;
      this.enabled = false; this.button = 0; this.gesture = null;
      this.stockCursor = this.rfb._cursor?._canvas;
      this.stockCursorDisplay = this.stockCursor?.style.display || '';
      this.storageKey = 'agentshell.touch-mouse.' + location.pathname.split('/')[2];
      this.cursor = document.createElement('div'); this.cursor.innerHTML = arrow;
      this.cursor.style.cssText = 'position:fixed;left:0;top:0;pointer-events:none;z-index:5;display:none;width:24px;height:26px;will-change:transform;filter:drop-shadow(0 1px 2px #0008)';
      this.cursor.setAttribute('aria-hidden', 'true'); document.body.append(this.cursor);
      const game = document.getElementById('noVNC_game_mode_button');
      game.disabled = false; game.alt = 'Touch mouse'; game.title = 'Touch mouse pointer mode';
      game.setAttribute('aria-label','Touch mouse pointer mode');
      game.src = 'data:image/svg+xml,' + encodeURIComponent(arrow);
      game.parentElement.style.removeProperty('display');
      this.game = game;
      this.toggleHandler = event => {
        event.preventDefault(); event.stopImmediatePropagation(); this.set(!this.enabled);
        ui.keepControlbar(); ui.hideVirtualKeyboard();
        this.rfb.focusOnClick = !this.enabled;
        ui.showStatus(this.enabled ? 'Mouse mode: drag to move, tap to click. Two fingers scroll or pinch to zoom. Three fingers move the zoomed view.' : 'Touch mode', 'info', 4500);
      };
      game.parentElement.addEventListener('click', this.toggleHandler, true);
      ui.toggleRelativePointer = () => this.set(!this.enabled);
      ui.updatePointerLockButton = () => {
        if (ui.connected) ui.showControlInput('noVNC_game_mode_button');
        else ui.hideControlInput('noVNC_game_mode_button');
        document.getElementById('noVNC_setting_pointer_lock').closest('li').classList.add('noVNC_hidden');
      };
      ui.updatePointerLockButton();
      this.handlers = [];
      for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
        const handler = event => {
          if (!this.enabled) return;
          event.preventDefault(); event.stopImmediatePropagation();
          this.rfb.focusOnClick = false;
          if (type === 'touchstart') ui.hideVirtualKeyboard();
          this.rfb.focusOnClick = false;
          this.handle(event);
        };
        this.handlers.push([type,handler]);
        this.rfb._canvas.addEventListener(type, handler, {capture:true, passive:false});
      }
      try { if (localStorage.getItem(this.storageKey) === 'true') this.set(true); } catch {}
      this.blurHandler = () => this.release();
      addEventListener('blur', this.blurHandler);
    }
    position() { return {x:this.x, y:this.y, enabled:this.enabled, button:this.button}; }
    coordinates() {
      const scale = this.rfb._display.scale || 1;
      return [this.x * scale, this.y * scale];
    }
    send(mask = this.button) {
      if (!this.ui.connected) return;
      this.rfb._sendMouse(...this.coordinates(), mask);
    }
    release() {
      clearTimeout(this.longPress);
      if (this.button) { this.button = 0; this.send(0); }
      this.gesture = null;
    }
    set(enabled) {
      this.release();
      if (enabled !== this.enabled) {
        if (enabled) this.rfb._gestures.detach();
        else this.rfb._gestures.attach(this.rfb._canvas);
      }
      this.enabled = enabled;
      if (this.stockCursor) this.stockCursor.style.display = enabled ? 'none' : this.stockCursorDisplay;
      this.rfb.focusOnClick = !enabled;
      document.getElementById('noVNC_game_mode_button').classList.toggle('noVNC_selected', enabled);
      document.getElementById('noVNC_game_mode_button').setAttribute('aria-pressed',String(enabled));
      try { localStorage.setItem(this.storageKey, String(enabled)); } catch {}
      if (enabled) { this.ui.hideVirtualKeyboard(); this.rfb.focusOnClick = false; this.send(0); }
      this.paint();
    }
    click(mask) { this.send(mask); this.send(0); }
    move(dx, dy) {
      const rect = this.rfb._canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      // After panning, bring an off-screen local pointer into the visible area
      // on its next move instead of dragging the viewport back to its old place.
      const bounds = this.rfb._screen.getBoundingClientRect();
      const minX = Math.max(0, (bounds.left + 24 - rect.left) * this.rfb._fbWidth / rect.width);
      const maxX = Math.min(this.rfb._fbWidth - 1, (bounds.right - 24 - rect.left) * this.rfb._fbWidth / rect.width);
      const minY = Math.max(0, (bounds.top + 24 - rect.top) * this.rfb._fbHeight / rect.height);
      const maxY = Math.min(this.rfb._fbHeight - 1, (bounds.bottom - 24 - rect.top) * this.rfb._fbHeight / rect.height);
      this.x = Math.max(minX, Math.min(maxX, this.x));
      this.y = Math.max(minY, Math.min(maxY, this.y));
      this.x = Math.max(0, Math.min(this.rfb._fbWidth - 1, this.x + dx * this.rfb._fbWidth / rect.width));
      this.y = Math.max(0, Math.min(this.rfb._fbHeight - 1, this.y + dy * this.rfb._fbHeight / rect.height));
      this.send(); this.paint(true);
    }
    paint(followPointer = false) {
      if (!this.enabled || !this.ui.connected) { this.cursor.style.display = 'none'; return; }
      this.x = Math.max(0, Math.min(this.rfb._fbWidth-1, this.x));
      this.y = Math.max(0, Math.min(this.rfb._fbHeight-1, this.y));
      const screen = this.rfb._screen, bounds = screen.getBoundingClientRect();
      if (!bounds.width || !bounds.height) { this.cursor.style.display = 'none'; return; }
      let rect = this.rfb._canvas.getBoundingClientRect();
      let x = rect.left + this.x / this.rfb._fbWidth * rect.width;
      let y = rect.top + this.y / this.rfb._fbHeight * rect.height;
      if (followPointer) {
        if (x < bounds.left + 24) screen.scrollLeft -= bounds.left + 24 - x;
        if (x > bounds.right - 24) screen.scrollLeft += x - bounds.right + 24;
        if (y < bounds.top + 24) screen.scrollTop -= bounds.top + 24 - y;
        if (y > bounds.bottom - 24) screen.scrollTop += y - bounds.bottom + 24;
      }
      rect = this.rfb._canvas.getBoundingClientRect();
      x = rect.left + this.x / this.rfb._fbWidth * rect.width;
      y = rect.top + this.y / this.rfb._fbHeight * rect.height;
      if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) {
        this.cursor.style.display = 'none'; return;
      }
      this.cursor.style.display = 'block';
      this.cursor.style.transform = `translate3d(${x-3}px,${y-2}px,0)`;
    }
    pair(touches) {
      return {x:(touches[0].clientX+touches[1].clientX)/2, y:(touches[0].clientY+touches[1].clientY)/2,
        distance:Math.hypot(touches[0].clientX-touches[1].clientX,touches[0].clientY-touches[1].clientY)};
    }
    center(touches) {
      return {x:[...touches].reduce((sum,p)=>sum+p.clientX,0)/touches.length,
        y:[...touches].reduce((sum,p)=>sum+p.clientY,0)/touches.length};
    }
    handle(event) {
      const touches = event.touches;
      if (event.type === 'touchcancel' || touches.length > 3) { this.release(); return; }
      if (event.type === 'touchstart') {
        this.release();
        if (touches.length === 1) {
          const point = touches[0];
          this.gesture = {count:1, x:point.clientX, y:point.clientY, startX:point.clientX, startY:point.clientY, moved:false};
          this.longPress = setTimeout(() => {
            if (this.gesture?.count === 1 && !this.gesture.moved) { this.button = 1; this.send(); }
          }, 500);
        } else if (touches.length === 2) {
          const pair = this.pair(touches);
          this.gesture = {count:2, ...pair, startX:pair.x, startY:pair.y, startDistance:pair.distance, moved:false, kind:null};
        } else if (touches.length === 3) {
          this.gesture = {count:3, ...this.center(touches)};
        }
        return;
      }
      const gesture = this.gesture;
      if (!gesture) return;
      if (event.type === 'touchmove') {
        if (gesture.count === 1 && touches.length === 1) {
          const point = touches[0];
          if (Math.hypot(point.clientX-gesture.startX,point.clientY-gesture.startY)>3) { gesture.moved = true; clearTimeout(this.longPress); }
          if (gesture.moved) this.move(point.clientX-gesture.x, point.clientY-gesture.y);
          gesture.x = point.clientX; gesture.y = point.clientY;
        } else if (gesture.count === 2 && touches.length === 2) {
          const pair = this.pair(touches);
          if (!gesture.kind) {
            if (Math.abs(pair.distance-gesture.startDistance)>8) gesture.kind = 'pinch';
            else if (Math.hypot(pair.x-gesture.startX,pair.y-gesture.startY)>4) gesture.kind = 'scroll';
          }
          if (gesture.kind) gesture.moved = true;
          if (gesture.kind === 'pinch' && gesture.distance) { this.zoom(pair.distance/gesture.distance); this.paint(); }
          else if (gesture.kind === 'scroll') {
            gesture.scrollX = (gesture.scrollX || 0) + gesture.x-pair.x;
            gesture.scrollY = (gesture.scrollY || 0) + gesture.y-pair.y;
            // Use the standard wheel-button protocol, also used by KasmVNC's
            // touch gestures, rather than depending on pixel-scroll extensions.
            for (const [axis,positive,negative] of [['scrollY',16,8],['scrollX',64,32]]) {
              while (Math.abs(gesture[axis]) >= 24) {
                const direction = Math.sign(gesture[axis]);
                this.click(direction > 0 ? positive : negative);
                gesture[axis] -= direction * 24;
              }
            }
          }
          gesture.x = pair.x; gesture.y = pair.y; gesture.distance = pair.distance;
        } else if (gesture.count === 3 && touches.length === 3) {
          const point = this.center(touches);
          this.pan(point.x-gesture.x, point.y-gesture.y);
          gesture.x = point.x; gesture.y = point.y;
          this.paint();
        }
      } else if (event.type === 'touchend' && gesture.count === 3) {
        // Lifting any finger ends panning. Remaining fingers must not turn it
        // into a click, remote wheel event or an accidental drag.
        this.release();
      } else if (event.type === 'touchend' && touches.length === 0) {
        clearTimeout(this.longPress);
        if (this.button) { this.button = 0; this.send(0); }
        else if (!gesture.moved) this.click(gesture.count === 2 ? 4 : 1);
        this.gesture = null;
      }
    }
    dispose() {
      this.release();
      for (const [type,handler] of this.handlers) this.rfb._canvas.removeEventListener(type,handler,true);
      this.game.parentElement.removeEventListener('click',this.toggleHandler,true);
      removeEventListener('blur',this.blurHandler); this.cursor.remove();
    }
  };
})();
