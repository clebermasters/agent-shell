// This worker only accepts requests on Chrome's extension-initiated native port.
const catalogueEpoch = crypto.randomUUID();
const completed = new Map();
const noMembership = -1;
// Leave time under the native relay deadline (53s) to finish an in-flight
// Chrome call and return verified counts and a fresh snapshot to the phone.
const mergeBudgetMs = 45000;
const mergeFinishGraceMs = 3000;
const moveBatchSize = 32;

class WindowOperationError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function requireWindowState(condition, code = 'WINDOW_TARGET_CHANGED') {
  if (!condition) throw new WindowOperationError(code);
}

function failureCode(error) {
  if (error instanceof WindowOperationError) return error.code;
  if (String(error?.message).includes('Tabs cannot be edited right now')) return 'WINDOW_ACTION_BUSY';
  return 'WINDOW_ACTION_FAILED';
}

function pairAvailable() {
  return typeof chrome.tabs.createSplit === 'function' && typeof chrome.tabs.unsplit === 'function';
}

function displayTitle(title) {
  // Unicode mode matches isolated UTF-16 surrogates, while retaining valid
  // supplementary characters like emoji across JSON and native UTF-8 framing.
  const clean = (title || '').replace(/[\uD800-\uDFFF]/gu, '\uFFFD').replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ');
  return Array.from(clean).slice(0, 160).join('');
}

async function snapshot() {
  const all = await chrome.windows.getAll({ populate: true, windowTypes: ['normal'] });
  const windows = all.filter(w => !w.incognito && w.type === 'normal').sort((a, b) => a.id - b.id)
    .map(w => ({
      id: w.id,
      focused: w.focused,
      tabs: w.tabs.filter(t => ![t.url, t.pendingUrl].some(url =>
        url?.split('#')[0] === chrome.runtime.getURL('pair.html'))).map(t => ({
        id: t.id,
        title: displayTitle(t.title),
        active: t.active,
        pinned: t.pinned,
        groupId: t.groupId ?? noMembership,
        splitId: t.splitViewId ?? noMembership
      }))
    })).filter(w => w.tabs.length);
  const encoder = new TextEncoder();
  // Leave room for the envelope under Chrome's 1 MiB native message limit.
  requireWindowState(encoder.encode(JSON.stringify(windows)).length < 448 * 1024, 'WINDOW_CATALOG_TOO_LARGE');
  const layout = windows.map(w => [w.id, w.focused, w.tabs.map(t =>
    [t.id, t.active, t.pinned, t.groupId, t.splitId])]);
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(JSON.stringify([catalogueEpoch, layout])));
  const revision = Array.from(new Uint8Array(digest), v => v.toString(16).padStart(2, '0')).join('');
  return { revision, splitSupported: pairAvailable(), windows };
}

const requestFields = {
  list: [],
  focus: ['windowId', 'tabId?'],
  detach: ['windowId', 'tabId'],
  move: ['windowId', 'tabId', 'targetWindowId'],
  merge: ['targetWindowId'],
  pair: ['windowId', 'tabId', 'otherTabId'],
  unpair: ['windowId', 'tabId']
};

function validate(request) {
  const code = 'WINDOW_ACTION_INVALID';
  requireWindowState(request && typeof request === 'object' && !Array.isArray(request), code);
  requireWindowState(typeof request.id === 'string' &&
    /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(request.id), code);
  requireWindowState(Object.hasOwn(requestFields, request.action), code);
  const allowed = new Set(['id', 'action']);
  if (request.action !== 'list') {
    allowed.add('revision');
    requireWindowState(typeof request.revision === 'string' && /^[0-9a-f]{64}$/.test(request.revision), code);
  }
  for (const field of requestFields[request.action]) {
    const key = field.replace(/\?$/, '');
    allowed.add(key);
    if (key !== field && !(key in request)) continue;
    requireWindowState(Number.isSafeInteger(request[key]) && request[key] >= 0, code);
  }
  requireWindowState(Object.keys(request).every(key => allowed.has(key)), code);
}

function windowIn(state, id) {
  const w = state.windows.find(w => w.id === id);
  requireWindowState(w);
  return w;
}

function tabIn(w, id) {
  const tab = w.tabs.find(t => t.id === id);
  requireWindowState(tab);
  return tab;
}

async function liveTab(windowId, tabId) {
  const tab = await chrome.tabs.get(tabId);
  requireWindowState(!tab.incognito && tab.windowId === windowId);
  return tab;
}

async function focusWindow(windowId, tabId, check) {
  if (tabId !== undefined) {
    await liveTab(windowId, tabId);
    check();
    await chrome.tabs.update(tabId, { active: true });
  }
  check();
  await chrome.windows.update(windowId, { focused: true });
  for (let n = 0; n < 20; n++) {
    const win = await chrome.windows.get(windowId, { populate: true });
    if (win.focused && (tabId === undefined || win.tabs.some(t => t.id === tabId && t.active))) return;
    await new Promise(resolve => setTimeout(resolve, 50));
    check();
  }
  throw new WindowOperationError('WINDOW_FOCUS_FAILED');
}

async function ensurePin(windowId, tab, pinIndex, check) {
  const live = await liveTab(windowId, tab.id);
  if (live.pinned !== tab.pinned) {
    check();
    await chrome.tabs.update(tab.id, { pinned: tab.pinned });
  }
  if (tab.pinned && (await liveTab(windowId, tab.id)).index !== pinIndex) {
    check();
    await chrome.tabs.move(tab.id, { index: pinIndex });
  }
}

async function moveOne(source, tab, target, check) {
  requireWindowState(tab.splitId === noMembership, 'WINDOW_PAIRED_TAB');
  const live = await liveTab(source.id, tab.id);
  requireWindowState((live.splitViewId ?? noMembership) === noMembership &&
    live.groupId === tab.groupId && live.pinned === tab.pinned);
  let destId, pinIndex = 0;
  if (target) {
    requireWindowState(target.id !== source.id, 'WINDOW_ACTION_INVALID');
    destId = target.id;
    pinIndex = target.tabs.filter(t => t.pinned).length;
    check();
    await chrome.tabs.move(tab.id, { windowId: destId, index: -1 });
  } else {
    const fresh = await chrome.windows.get(source.id, { populate: true });
    requireWindowState(fresh.tabs.length > 1, 'WINDOW_ACTION_INVALID');
    check();
    destId = (await chrome.windows.create({ tabId: tab.id, focused: true, type: 'normal' })).id;
  }
  await ensurePin(destId, tab, pinIndex, check);
  const moved = await liveTab(destId, tab.id);
  if (moved.groupId !== noMembership) {
    check();
    await chrome.tabs.ungroup(tab.id);
  }
  await focusWindow(destId, tab.id, check);
  const verified = await liveTab(destId, tab.id);
  requireWindowState(verified.pinned === tab.pinned && verified.groupId === noMembership);
}

async function pairTabs(source, first, second, check) {
  requireWindowState(pairAvailable(), 'WINDOW_SPLIT_UNAVAILABLE');
  requireWindowState(first.id !== second.id && first.groupId === second.groupId &&
    first.pinned === second.pinned, 'WINDOW_ACTION_INVALID');
  const a = await liveTab(source.id, first.id);
  const b = await liveTab(source.id, second.id);
  requireWindowState(a.groupId === b.groupId && a.pinned === b.pinned);
  requireWindowState((a.splitViewId ?? noMembership) === noMembership &&
    (b.splitViewId ?? noMembership) === noMembership, 'WINDOW_PAIRED_TAB');
  if (Math.abs(a.index - b.index) !== 1) {
    check();
    await chrome.tabs.move(b.id, { index: b.index < a.index ? a.index : a.index + 1 });
  }
  const aa = await liveTab(source.id, a.id);
  const bb = await liveTab(source.id, b.id);
  requireWindowState(Math.abs(aa.index - bb.index) === 1 && aa.groupId === a.groupId && bb.groupId === a.groupId);
  check();
  const ids = aa.index < bb.index ? [a.id, b.id] : [b.id, a.id];
  const splitId = await chrome.tabs.createSplit(ids);
  await focusWindow(source.id, a.id, check);
  for (const id of ids) requireWindowState((await liveTab(source.id, id)).splitViewId === splitId);
}

async function unpair(source, tab, check) {
  requireWindowState(pairAvailable(), 'WINDOW_SPLIT_UNAVAILABLE');
  requireWindowState(tab.splitId !== noMembership, 'WINDOW_ACTION_INVALID');
  const live = await liveTab(source.id, tab.id);
  requireWindowState(live.splitViewId === tab.splitId);
  check();
  await chrome.tabs.unsplit(tab.splitId);
  for (const member of source.tabs.filter(t => t.splitId === tab.splitId)) {
    requireWindowState(((await liveTab(source.id, member.id)).splitViewId ?? noMembership) === noMembership);
  }
  await focusWindow(source.id, tab.id, check);
}

// Keep entire groups intact. Ungrouped paired tabs travel together; Chrome can
// separate them during a cross-window move and they are re-paired below.
function units(tabs) {
  const result = [];
  for (const tab of tabs) {
    const last = result.at(-1);
    const first = last?.[0];
    const sameGroup = first && tab.groupId !== noMembership && tab.groupId === first.groupId;
    const samePair = first && tab.splitId !== noMembership && tab.splitId === first.splitId;
    const plainRun = first && last.length < moveBatchSize && first.pinned === tab.pinned &&
      first.groupId === noMembership && tab.groupId === noMembership &&
      first.splitId === noMembership && tab.splitId === noMembership;
    if (sameGroup || samePair || plainRun) {
      last.push(tab);
    } else {
      result.push([tab]);
    }
  }
  return result;
}

function sameIds(a, b) {
  return a.length === b.length && a.every((id, n) => id === b[n]);
}

async function transferUnit(sourceId, targetId, unit, check) {
  const first = unit[0];
  const ids = unit.map(t => t.id);
  const source = await chrome.windows.get(sourceId, { populate: true });
  const positions = source.tabs.map(t => t.id);
  const start = positions.indexOf(first.id);
  requireWindowState(start >= 0 && sameIds(positions.slice(start, start + ids.length), ids));
  for (let n = 0; n < unit.length; n++) {
    const t = source.tabs[start + n], wanted = unit[n];
    requireWindowState(t.groupId === wanted.groupId && t.pinned === wanted.pinned &&
      (t.splitViewId ?? noMembership) === wanted.splitId);
  }
  if (first.groupId !== noMembership) {
    requireWindowState(sameIds(source.tabs.filter(t => t.groupId === first.groupId).map(t => t.id), ids));
    check();
    await chrome.tabGroups.move(first.groupId, { windowId: targetId, index: -1 });
  } else {
    const pinIndex = first.pinned ? (await chrome.tabs.query({ windowId: targetId, pinned: true })).length : 0;
    check();
    await chrome.tabs.move(ids, { windowId: targetId, index: -1 });
    if (first.pinned) {
      for (let n = 0; n < unit.length; n++) await ensurePin(targetId, unit[n], pinIndex + n, check);
    }
  }
  // Chrome may allocate a new group ID across windows. Verify its membership.
  if (first.groupId !== noMembership) {
    const currentGroupId = (await liveTab(targetId, first.id)).groupId;
    requireWindowState(currentGroupId !== noMembership && sameIds(
      (await chrome.tabs.query({ windowId: targetId, groupId: currentGroupId })).map(t => t.id), ids));
  }
  const pairs = new Map();
  for (const t of unit) {
    if (t.splitId === noMembership) continue;
    if (!pairs.has(t.splitId)) pairs.set(t.splitId, []);
    pairs.get(t.splitId).push(t.id);
  }
  for (const members of pairs.values()) {
    const code = 'WINDOW_PAIR_RESTORE_FAILED';
    requireWindowState(members.length === 2, code);
    const [a, b] = await Promise.all(members.map(id => liveTab(targetId, id)));
    if (a.splitViewId !== noMembership && a.splitViewId != null && a.splitViewId === b.splitViewId) continue;
    requireWindowState(b.index === a.index + 1 && a.pinned === b.pinned && a.groupId === b.groupId, code);
    check();
    try {
      const newId = await chrome.tabs.createSplit(members);
      const after = await Promise.all(members.map(id => liveTab(targetId, id)));
      requireWindowState(after.every(t => t.splitViewId === newId), code);
    } catch {
      throw new WindowOperationError(code);
    }
  }
}

async function mergeWindows(before, target, check, now) {
  const deadline = now() + mergeBudgetMs;
  const finishUnit = () => {
    check();
    requireWindowState(now() < deadline + mergeFinishGraceMs, 'WINDOW_COMMAND_TIMEOUT');
  };
  const sources = before.windows.filter(w => w.id !== target.id);
  requireWindowState(sources.length > 0, 'WINDOW_ACTION_INVALID');
  const incoming = sources.flatMap(w => w.tabs);
  requireWindowState(before.splitSupported || incoming.every(t => t.splitId === noMembership),
    'WINDOW_MERGE_NEEDS_SPLIT_SUPPORT');
  const active = target.tabs.find(t => t.active);
  requireWindowState(active);
  for (const w of sources) {
    for (const unit of units(w.tabs)) {
      requireWindowState(now() < deadline, 'WINDOW_COMMAND_TIMEOUT');
      await transferUnit(w.id, target.id, unit, finishUnit);
    }
  }
  await focusWindow(target.id, active.id, finishUnit);
  const verified = await chrome.windows.get(target.id, { populate: true });
  const all = [...target.tabs, ...incoming];
  const expected = [...all.filter(t => t.pinned), ...all.filter(t => !t.pinned)];
  requireWindowState(sameIds(verified.tabs.map(t => t.id), expected.map(t => t.id)) &&
    verified.tabs.every((t, n) => t.pinned === expected[n].pinned));
  // Group and split IDs can change. Compare the partitions of the ordered tab
  // strip, including singletons, instead of comparing provider-assigned IDs.
  function membership(tabs, key) {
    const firstMembers = new Map();
    return tabs.map(t => {
      const id = t[key] ?? noMembership;
      if (id === noMembership) return noMembership;
      if (!firstMembers.has(id)) firstMembers.set(id, t.id);
      return firstMembers.get(id);
    });
  }
  requireWindowState(sameIds(membership(expected, 'groupId'), membership(verified.tabs, 'groupId')));
  requireWindowState(sameIds(membership(expected, 'splitId'), membership(verified.tabs, 'splitViewId')),
    'WINDOW_PAIR_RESTORE_FAILED');
}

async function applyAction(request, before, check, now) {
  const { action, windowId, targetWindowId, tabId, otherTabId } = request;
  if (action === 'merge') return mergeWindows(before, windowIn(before, targetWindowId), check, now);
  const source = windowIn(before, windowId);
  if (action === 'focus') {
    if (tabId !== undefined) tabIn(source, tabId);
    return focusWindow(source.id, tabId, check);
  }
  const tab = tabIn(source, tabId);
  switch (action) {
    case 'move': return moveOne(source, tab, windowIn(before, targetWindowId), check);
    case 'detach': return moveOne(source, tab, null, check);
    case 'pair': return pairTabs(source, tab, tabIn(source, otherTabId), check);
    case 'unpair': return unpair(source, tab, check);
  }
}

async function dispatchWindowRequest(request, isCurrent = () => true, now = () => performance.now()) {
  let before, after, signature, result;
  const check = () => requireWindowState(isCurrent(), 'WINDOW_TARGET_CHANGED');
  try {
    validate(request);
    check();
    before = await snapshot();
    if (request.action === 'list') return { id: request.id, ok: true, snapshot: before };
    signature = JSON.stringify(request, Object.keys(request).sort());
    const cached = completed.get(request.id);
    if (cached) {
      requireWindowState(cached.signature === signature, 'WINDOW_REQUEST_REUSED');
      return { ...cached.result, snapshot: before };
    }
    requireWindowState(before.revision === request.revision, 'WINDOW_CATALOG_CHANGED');
    await applyAction(request, before, check, now);
    result = { id: request.id, ok: true };
  } catch (error) {
    result = { id: request?.id, ok: false, code: failureCode(error) };
  }
  try {
    after = await snapshot();
  } catch (error) {
    if (result.ok) result = { id: request.id, ok: false, code: failureCode(error) };
  }
  if (result.code === 'WINDOW_REQUEST_REUSED') return { ...result, snapshot: after };
  if (!result.ok && before && after && request.action !== 'list' &&
    before.revision === request.revision && after.revision !== before.revision) {
    result.reason = result.code;
    result.code = 'WINDOW_OPERATION_PARTIAL';
  }
  if (request?.action === 'merge' && before && before.revision === request.revision) {
    const ids = new Set(before.windows.filter(w => w.id !== request.targetWindowId).flatMap(w => w.tabs.map(t => t.id)));
    result.total = ids.size;
    if (after) result.moved = after.windows.find(w => w.id === request.targetWindowId)?.tabs.filter(t => ids.has(t.id)).length ?? 0;
  }
  // Receipts hold no tab titles or other browsing metadata. A worker restart
  // invalidates the catalogue epoch, so an old command cannot execute again.
  if (signature) {
    if (completed.size >= 256) completed.delete(completed.keys().next().value);
    completed.set(request.id, { signature, result });
  }
  return { ...result, ...(after && { snapshot: after }) };
}
