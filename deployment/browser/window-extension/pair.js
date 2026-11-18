async function pairProfile() {
  const nonce = location.hash.slice(1);
  history.replaceState(null, '', location.pathname);
  if (!/^[0-9a-f]{64}$/.test(nonce)) throw new Error();
  const tab = await chrome.tabs.getCurrent();
  if (!tab || tab.incognito) throw new Error();
  const verified = await chrome.runtime.sendMessage({ type: 'pair', nonce });
  if (verified !== true) throw new Error();
  await chrome.tabs.remove(tab.id);
}

pairProfile().catch(() => {
  document.getElementById('status').textContent = 'AgentShell could not verify this profile. Close this tab and reopen Windows in AgentShell.';
});
