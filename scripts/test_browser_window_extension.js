// Loaded only into the disposable copy of the extension by the Python test.
async function verifyWindows(base) {
  function assert(condition, description) {
    if (!condition) throw new Error(description);
  }
  assert(displayTitle('\uD800🧭\uDC00\t') === '\uFFFD🧭\uFFFD ', 'well-formed Unicode tab titles');
  const call = async fields => dispatchWindowRequest({ id: crypto.randomUUID(), ...fields });
  const list = async () => (await call({ action: 'list' })).snapshot;
  const change = async fields => {
    const response = await call({ revision: (await list()).revision, ...fields });
    assert(response.ok, fields.action + ' failed ' + JSON.stringify(response));
    return response;
  };
  const setupTab = async (windowId, name, pinned = false) => {
    const tab = await chrome.tabs.create({ windowId, active: false, url: base + '/' + name, pinned });
    return tab.id;
  };
  const measure = async (id, newDraft) => {
    for (let n = 0; n < 50; n++) {
      if ((await chrome.tabs.get(id)).status === 'complete') break;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    const [value] = await chrome.scripting.executeScript({
      target: { tabId: id },
      func: draft => {
        const field = document.querySelector('textarea');
        if (draft) field.value = draft;
        return [field.value, performance.timeOrigin];
      }, args: [newDraft || '']
    });
    return JSON.stringify(value.result);
  };
  const start = await chrome.windows.create({ url: base + '/destination' });
  const dest = start.id, selected = start.tabs[0].id;
  for (const w of await chrome.windows.getAll()) {
    if (w.id !== dest) await chrome.windows.remove(w.id);
  }
  const originalPin = await setupTab(dest, 'pin-a', true);
  const source = await chrome.windows.create({ url: base + '/first' });
  const src = source.id, first = source.tabs[0].id;
  const middle = await setupTab(src, 'group-a');
  const grouped = await setupTab(src, 'group-b');
  const groupId = await chrome.tabs.group({ tabIds: [middle, grouped], createProperties: { windowId: src } });
  await chrome.tabGroups.update(groupId, { title: 'Test group', color: 'green' });
  const last = await setupTab(src, 'second');
  const pin = await setupTab(src, 'pin-b', true);
  const original = await measure(last, 'unsaved draft');

  const focused = await change({ action: 'focus', windowId: dest, tabId: selected });
  assert(focused.snapshot.windows.some(w => w.id === dest && w.focused), 'destination focus');
  let activeWindow = '';
  for (let n = 0; n < 10; n++) {
    activeWindow = await (await fetch(base + '/active-window', { cache: 'no-store' })).text();
    if (activeWindow.includes('Fixture destination')) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert(activeWindow.includes('Fixture destination'), 'Openbox foregrounds the chosen real window');
  const detached = await change({ action: 'detach', windowId: src, tabId: pin });
  const own = detached.snapshot.windows.find(w => w.tabs.some(t => t.id === pin));
  assert(own.id !== src && own.tabs[0].pinned && own.tabs[0].active, 'detach pin and focus');
  const single = await call({ action: 'detach', windowId: own.id, tabId: pin, revision: detached.snapshot.revision });
  assert(single.code === 'WINDOW_ACTION_INVALID', 'single tab detach guard');
  const returned = await change({ action: 'move', windowId: own.id, tabId: pin, targetWindowId: src });
  assert(!returned.snapshot.windows.some(w => w.id === own.id), 'Chrome closed the empty window');
  assert(returned.snapshot.windows.find(w => w.id === src).tabs.some(t => t.id === pin && t.pinned && t.active),
    'single move followed the live pinned tab');

  const split = await change({ action: 'pair', windowId: src, tabId: first, otherTabId: last });
  const splitTabs = split.snapshot.windows.find(w => w.id === src).tabs;
  assert(splitTabs.find(t => t.id === first).splitId === splitTabs.find(t => t.id === last).splitId &&
    splitTabs.find(t => t.id === first).splitId !== -1, 'created native split after making tabs adjacent');
  const refused = await call({ action: 'move', revision: split.snapshot.revision, windowId: src, tabId: last, targetWindowId: dest });
  assert(refused.code === 'WINDOW_PAIRED_TAB', 'paired member move rejected');
  const stale = await call({ action: 'merge', revision: detached.snapshot.revision, targetWindowId: dest });
  assert(stale.code === 'WINDOW_CATALOG_CHANGED', 'stale preview rejected');
  const command = { id: crypto.randomUUID(), action: 'merge', revision: (await list()).revision, targetWindowId: dest };
  const merged = await dispatchWindowRequest(command);
  assert(merged.ok && merged.moved === 5 && merged.total === 5, 'verified merge count ' + JSON.stringify(merged));
  const tabs = merged.snapshot.windows[0].tabs;
  assert(merged.snapshot.windows.length === 1 && tabs.find(t => t.active).id === selected, 'destination selection');
  assert(tabs.map(t => t.id).join() === [originalPin, pin, selected, first, last, middle, grouped].join(), 'tab order');
  assert(tabs.slice(0, 2).every(t => t.pinned), 'pin preservation');
  assert(tabs[3].splitId !== -1 && tabs[3].splitId === tabs[4].splitId, 'split recreated');
  assert(tabs[5].groupId !== -1 && tabs[5].groupId === tabs[6].groupId, 'group retained');
  const group = await chrome.tabGroups.get(tabs[5].groupId);
  assert(group.title === 'Test group' && group.color === 'green', 'group metadata retained');
  const replay = await dispatchWindowRequest(command);
  assert(replay.ok && replay.moved === merged.moved, 'duplicate command receipt');
  assert((await dispatchWindowRequest({ ...command, targetWindowId: src })).code === 'WINDOW_REQUEST_REUSED', 'ID reuse rejected');
  await change({ action: 'unpair', windowId: dest, tabId: last });
  assert((await chrome.tabs.get(last)).splitViewId === -1, 'split removed');
  assert((await measure(last)) === original, 'live draft and page identity retained');

  const extra = await chrome.windows.create({ url: base + '/extra' });
  const remaining = await setupTab(extra.id, 'remaining');
  await chrome.tabs.group({ tabIds: [remaining], createProperties: { windowId: extra.id } });
  const interrupted = { id: crypto.randomUUID(), action: 'merge', revision: (await list()).revision, targetWindowId: dest };
  let checks = 0;
  // Simulate a controller disconnect after Chrome has moved the first tab.
  const partial = await dispatchWindowRequest(interrupted, () => ++checks <= 2);
  assert(!partial.ok && partial.code === 'WINDOW_OPERATION_PARTIAL' &&
    partial.moved === 1 && partial.total === 2, 'partial receipt ' + JSON.stringify(partial));
  const unrelated = await chrome.windows.create({ url: base + '/new-window' });
  const retry = await dispatchWindowRequest(interrupted);
  assert(retry.code === partial.code && retry.moved === partial.moved, 'partial replay receipt');
  assert((await chrome.tabs.get(remaining)).windowId === extra.id, 'partial replay left remaining tab alone');
  assert(retry.snapshot.windows.some(w => w.id === unrelated.id), 'partial replay left new window alone');

  // Advance a controlled monotonic clock when Chrome attaches the first group
  // to the destination. The next unit must stop and return a usable receipt.
  let elapsed = 0;
  const tick = (tabId, info) => {
    if (tabId === remaining && info.newWindowId === dest) elapsed = mergeBudgetMs + 1;
  };
  chrome.tabs.onAttached.addListener(tick);
  try {
    const slow = { id: crypto.randomUUID(), action: 'merge', revision: (await list()).revision, targetWindowId: dest };
    const timedOut = await dispatchWindowRequest(slow, () => true, () => elapsed);
    assert(timedOut.code === 'WINDOW_OPERATION_PARTIAL' && timedOut.reason === 'WINDOW_COMMAND_TIMEOUT' &&
      timedOut.moved === 1 && timedOut.total === 2, 'deadline progress receipt ' + JSON.stringify(timedOut));
    assert(timedOut.snapshot.windows.some(w => w.id === unrelated.id), 'deadline left remaining window intact');
    const receipt = await dispatchWindowRequest(slow);
    assert(receipt.reason === 'WINDOW_COMMAND_TIMEOUT' && receipt.moved === 1, 'deadline receipt remains idempotent');
  } finally {
    chrome.tabs.onAttached.removeListener(tick);
  }

  const pairs = await chrome.windows.create({ url: base + '/pairs' });
  const pins = [await setupTab(pairs.id, 'left-pin', true), await setupTab(pairs.id, 'right-pin', true)];
  const members = [await setupTab(pairs.id, 'left-group'), await setupTab(pairs.id, 'right-group')];
  await chrome.tabs.group({ tabIds: members, createProperties: { windowId: pairs.id } });
  for (const [tabId, otherTabId] of [pins, members]) {
    await change({ action: 'pair', windowId: pairs.id, tabId, otherTabId });
  }
  const combined = await change({ action: 'merge', targetWindowId: dest });
  const finalTabs = combined.snapshot.windows[0].tabs;
  for (const ids of [pins, members]) {
    const [left, right] = ids.map(id => finalTabs.find(t => t.id === id));
    assert(left.splitId !== -1 && left.splitId === right.splitId, 'kept pinned or grouped split');
  }
  const bulk = await chrome.windows.create({ url: 'about:blank' });
  for (let n = 1; n < moveBatchSize + 4; n++) {
    const t = await chrome.tabs.create({ windowId: bulk.id, active: false, url: 'about:blank' });
    await chrome.tabs.discard(t.id);
  }
  const bulkIds = (await chrome.tabs.query({ windowId: bulk.id })).map(t => t.id);
  const started = performance.now();
  const batchMove = await change({ action: 'merge', targetWindowId: dest });
  const bulkMs = Math.round(performance.now() - started);
  assert(batchMove.moved === bulkIds.length &&
    batchMove.snapshot.windows[0].tabs.slice(-bulkIds.length).every((t, n) => t.id === bulkIds[n]),
    'batched ordinary tabs retain order across batch boundary');
  return { passed: true, bulkTabs: bulkIds.length, bulkMs, scenarios: ['focus', 'detach', 'single move', 'pair', 'merge', 'unpair', 'stale preview', 'replay',
    'pin', 'group', 'pair reconstruction', 'unsaved draft and navigation identity', 'partial progress and replay',
    'pinned and grouped split pairs during multiwindow merge', 'bounded merge deadline and replay', 'batched bulk move'] };
}
