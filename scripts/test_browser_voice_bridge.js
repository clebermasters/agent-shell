#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture() {
  const keys = [], clipboard = [];
  const rfb = {
    _rfbConnectionState: 'connected', _viewOnly: false, agentShellObserved: true,
    _clipHash: 'previous-text',
    sendKey(...event) { keys.push(event); },
    clipboardPasteFrom(text) { clipboard.push({text, hash: this._clipHash}); },
  };
  const ui = {connected: true, rfb};
  const window = {AgentShellUI: ui, addEventListener() {}, dispatchEvent() {}};
  const styled = () => ({style: {setProperty() {}}});
  let tick;
  const context = {
    window, navigator: {userAgent: 'isolated bridge unit test'}, innerWidth: 800, innerHeight: 600,
    document: {createElement: () => ({}), head: {append() {}}, documentElement: styled(), body: styled(), getElementById: () => null},
    setInterval(fn) { tick = fn; return 1; }, clearInterval() {}, Event: class {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../deployment/browser/viewer-bridge.js'), 'utf8'), context);
  tick();
  return {rfb, ui, keys, clipboard, viewer: window.AgentShellViewer};
}

let passed = 0;
function test(name, body) { body(); passed++; process.stdout.write(`ok - ${name}\n`); }

test('literal Unicode and paragraphs use clipboard paste without an Enter key', () => {
  const f = fixture(), text = 'ação — "hello" 👋\n日本語 <script>alert(1)</script>';
  assert.equal(f.viewer.pasteText('voice-1', text), 'inserted');
  assert.equal(f.clipboard.length, 1);
  assert.equal(f.clipboard[0].text, text);
  assert.equal(f.clipboard[0].hash, null);
  assert(f.keys.some(([key, code, down]) => key === 118 && code === 'KeyV' && down === true));
  assert(!f.keys.some(([key]) => key === 65293 || key === 10 || key === 13));
  assert.deepEqual(f.keys.slice(-2).map(event => Array.from(event)), [[118, 'KeyV', false], [65507, 'ControlLeft', false]]);
});

test('duplicate callback cannot paste the same job twice', () => {
  const f = fixture();
  assert.equal(f.viewer.pasteText('voice-1', 'same transcript'), 'inserted');
  const count = f.keys.length;
  assert.equal(f.viewer.pasteText('voice-1', 'same transcript'), 'duplicate');
  assert.equal(f.clipboard.length, 1);
  assert.equal(f.keys.length, count);
});

test('a new recording with identical text refreshes the remote clipboard', () => {
  const f = fixture();
  f.viewer.pasteText('voice-1', 'repeat');
  f.rfb._clipHash = 'old hash that would suppress clipboard upload';
  assert.equal(f.viewer.pasteText('voice-2', 'repeat'), 'inserted');
  assert.equal(f.clipboard.length, 2);
  assert(f.clipboard.every(value => value.hash === null));
});

test('disconnected or read-only viewers never send clipboard or keys', () => {
  for (const update of [f => { f.ui.connected = false; }, f => { f.rfb._rfbConnectionState = 'disconnected'; }, f => { f.rfb._viewOnly = true; }]) {
    const f = fixture(); update(f);
    assert.equal(f.viewer.pasteText('voice-1', 'text'), 'unavailable');
    assert.equal(f.clipboard.length, 0); assert.equal(f.keys.length, 0);
  }
});

test('unsupported clipboard transport is reported without typing fallback', () => {
  const f = fixture(); delete f.rfb.clipboardPasteFrom;
  assert.equal(f.viewer.pasteText('voice-1', 'text'), 'unsupported');
  assert.equal(f.keys.length, 0);
});

test('invalid input is rejected before remote events are sent', () => {
  for (const [id, text] of [['voice-1', ''], ['voice-1', '  \n '], ['voice-1', 'x'.repeat(8193)], ['voice-1', 'a\0b'], ['', 'text'], ['bad id', 'text']]) {
    const f = fixture();
    assert.equal(f.viewer.pasteText(id, text), 'invalid');
    assert.equal(f.clipboard.length, 0); assert.equal(f.keys.length, 0);
  }
});

test('a failed send releases the paste keys and does not claim success', () => {
  const f = fixture(), original = f.rfb.sendKey;
  f.rfb.sendKey = function (...event) { original.apply(this, event); if (event[1] === 'KeyV' && event[2]) throw Error('transport closed'); };
  assert.equal(f.viewer.pasteText('voice-1', 'text'), 'unavailable');
  assert.deepEqual(f.keys.slice(-2).map(event => Array.from(event)), [[118, 'KeyV', false], [65507, 'ControlLeft', false]]);
});

process.stdout.write(`${passed} bridge checks passed\n`);
