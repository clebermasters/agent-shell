importScripts('windows.js');

const hostName = 'com.agentshell.windows';
const reconnectAlarm = 'connect-window-manager';
let connection;
let connecting = false;
let work = Promise.resolve();
let identity;
const pendingPairs = new Map();

function profilePairingId() {
  if (!identity) {
    identity = chrome.storage.local.get('pairingId').then(async ({ pairingId }) => {
      if (pairingId) return pairingId;
      const created = crypto.randomUUID();
      await chrome.storage.local.set({ pairingId: created });
      return created;
    }).catch(error => {
      identity = undefined;
      throw error;
    });
  }
  return identity;
}

async function connect() {
  if (connection || connecting) return;
  connecting = true;
  try {
    const pairingId = await profilePairingId();
    const port = chrome.runtime.connectNative(hostName);
    connection = port;
    port.onDisconnect.addListener(() => {
      // Consume Chrome's connection error without logging profile data.
      void chrome.runtime.lastError;
      if (connection === port) {
        connection = undefined;
        for (const finish of pendingPairs.values()) finish(false);
        chrome.alarms.create(reconnectAlarm, { delayInMinutes: 1 });
      }
    });
    port.onMessage.addListener(request => {
      if (Object.hasOwn(request || {}, 'paired')) {
        pendingPairs.get(request.nonce)?.(request.paired === true);
        return;
      }
      work = work.then(async () => {
        if (connection !== port) return;
        const response = await dispatchWindowRequest(request, () => connection === port);
        if (connection === port) port.postMessage(response);
      }).catch(() => {});
    });
    port.postMessage({ type: 'hello', version: 1, pairingId });
  } catch {
    chrome.alarms.create(reconnectAlarm, { delayInMinutes: 1 });
  } finally {
    connecting = false;
  }
}

chrome.runtime.onMessage.addListener((request, sender, reply) => {
  if (request?.type !== 'pair' || typeof request.nonce !== 'string' || !/^[0-9a-f]{64}$/.test(request.nonce) ||
    sender.id !== chrome.runtime.id || !sender.tab || sender.tab.incognito || sender.frameId !== 0 ||
    sender.url?.split('#')[0] !== chrome.runtime.getURL('pair.html')) return;
  // Use the same port that sent hello. Even cloned profiles can have the same
  // chrome.storage.local identity, but only the targeted instance has this nonce.
  if (!connection || pendingPairs.has(request.nonce)) {
    reply(false);
    return;
  }
  const finish = ok => {
    clearTimeout(timer);
    pendingPairs.delete(request.nonce);
    reply(ok);
  };
  const timer = setTimeout(() => finish(false), 10000);
  pendingPairs.set(request.nonce, finish);
  try { connection.postMessage({ type: 'pair', version: 1, nonce: request.nonce }); }
  catch { finish(false); }
  return true;
});

chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === reconnectAlarm) connect();
});
connect();
