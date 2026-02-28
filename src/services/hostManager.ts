import { ref } from 'vue'

export interface Host {
  id: string
  name: string
  url: string
}

const STORAGE_KEY = 'webmux-hosts'
const SELECTED_KEY = 'webmux-selected-host'

const hosts = ref<Host[]>([])
const selectedHostId = ref<string | null>(null)

const loadHosts = (): void => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      hosts.value = JSON.parse(stored)
    } else {
      hosts.value = []
    }
    
    const selected = localStorage.getItem(SELECTED_KEY)
    selectedHostId.value = selected
  } catch (e) {
    console.error('Failed to load hosts:', e)
    hosts.value = []
  }
}

const saveHosts = (): void => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(hosts.value))
  } catch (e) {
    console.error('Failed to save hosts:', e)
  }
}

const addHost = (name: string, url: string, _isHttps: boolean = false): void => {
  const id = Date.now().toString()
  hosts.value.push({ id, name, url })
  saveHosts()
}

const removeHost = (id: string): void => {
  hosts.value = hosts.value.filter(h => h.id !== id)
  if (selectedHostId.value === id) {
    selectedHostId.value = hosts.value[0]?.id || null
    saveSelectedHost()
  }
  saveHosts()
}

const setSelectedHost = (id: string | null): void => {
  selectedHostId.value = id
  saveSelectedHost()
}

const saveSelectedHost = (): void => {
  try {
    if (selectedHostId.value) {
      localStorage.setItem(SELECTED_KEY, selectedHostId.value)
    } else {
      localStorage.removeItem(SELECTED_KEY)
    }
  } catch (e) {
    console.error('Failed to save selected host:', e)
  }
}

const getSelectedHost = (): Host | null => {
  if (!selectedHostId.value) return null
  return hosts.value.find(h => h.id === selectedHostId.value) || null
}

const getWebSocketUrl = (): string | null => {
  const host = getSelectedHost()
  if (!host) return null
  
  // Always use ws: (not wss:) for local connections
  return `ws://${host.url}/ws`
}

const isConfigured = (): boolean => {
  return hosts.value.length > 0 && selectedHostId.value !== null
}

loadHosts()

export const hostManager = {
  hosts,
  selectedHostId,
  addHost,
  removeHost,
  setSelectedHost,
  getSelectedHost,
  getWebSocketUrl,
  isConfigured,
  loadHosts
}
