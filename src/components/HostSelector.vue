<template>
  <div class="host-selector">
    <!-- Desktop: Dropdown + Add button -->
    <div class="hidden xs:flex items-center space-x-2">
      <select
        v-model="selectedId"
        @change="onSelect"
        class="text-xs px-2 py-1 rounded border max-w-24 xs:max-w-32"
        style="background: var(--bg-primary); border-color: var(--border-secondary); color: var(--text-primary)"
      >
        <option value="">Select host...</option>
        <option v-for="host in hosts" :key="host.id" :value="host.id">
          {{ host.name }}
        </option>
      </select>
      
      <button
        @click="showAddModal = true"
        class="p-1 rounded hover-bg"
        style="color: var(--text-tertiary)"
        title="Add host"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
      </button>
    </div>

    <!-- Mobile (xs): Icon button that opens modal -->
    <button
      @click="showHostModal = true"
      class="xs:hidden p-1.5 rounded hover-bg"
      style="color: var(--text-tertiary)"
      title="Select host"
    >
      <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
      </svg>
    </button>

    <!-- Host Selection Modal (Mobile) -->
    <div v-if="showHostModal" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" @click.self="showHostModal = false">
      <div class="rounded-lg p-4 w-80 max-w-[90vw]" style="background: var(--bg-secondary); border: 1px solid var(--border-primary)">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-sm font-medium" style="color: var(--text-primary)">Select Host</h3>
          <button @click="showHostModal = false" class="p-1" style="color: var(--text-tertiary)">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        
        <!-- Current host display -->
        <div v-if="currentHost" class="mb-3 p-2 rounded" style="background: var(--bg-primary)">
          <div class="text-xs" style="color: var(--text-secondary)">Connected to:</div>
          <div class="text-sm font-medium" style="color: var(--text-primary)">{{ currentHost.name }}</div>
          <div class="text-xs" style="color: var(--text-tertiary)">{{ currentHost.url }}</div>
        </div>
        
        <!-- Host list -->
        <div class="space-y-2 max-h-48 overflow-y-auto mb-3">
          <div
            v-for="host in hosts"
            :key="host.id"
            @click="selectHost(host.id)"
            class="p-2 rounded cursor-pointer flex items-center justify-between"
            :style="host.id === selectedId ? 'background: var(--accent-primary); color: var(--bg-primary)' : 'background: var(--bg-tertiary); color: var(--text-primary)'"
          >
            <div>
              <div class="text-sm font-medium">{{ host.name }}</div>
              <div class="text-xs" :style="host.id === selectedId ? 'color: var(--bg-primary); opacity: 0.8' : 'color: var(--text-tertiary)'">{{ host.url }}</div>
            </div>
            <button
              @click.stop="promptDelete(host)"
              class="p-1 rounded"
              :style="host.id === selectedId ? 'color: var(--bg-primary)' : 'color: var(--text-tertiary)'"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
          </div>
          
          <div v-if="hosts.length === 0" class="text-center py-4 text-xs" style="color: var(--text-tertiary)">
            No hosts added yet
          </div>
        </div>
        
        <button
          @click="showHostModal = false; showAddModal = true"
          class="w-full py-2 text-sm rounded font-medium"
          style="background: var(--accent-primary); color: var(--bg-primary)"
        >
          + Add New Host
        </button>
      </div>
    </div>

    <!-- Add Host Modal -->
    <div v-if="showAddModal" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" @click.self="showAddModal = false">
      <div class="rounded-lg p-4 w-80" style="background: var(--bg-secondary); border: 1px solid var(--border-primary)">
        <h3 class="text-sm font-medium mb-3" style="color: var(--text-primary)">Add New Host</h3>
        
        <div class="space-y-3">
          <div>
            <label class="text-xs" style="color: var(--text-secondary)">Name</label>
            <input
              v-model="newHost.name"
              type="text"
              placeholder="e.g., Home Server"
              class="w-full text-xs px-2 py-1 rounded border"
              style="background: var(--bg-primary); border-color: var(--border-secondary); color: var(--text-primary)"
            />
          </div>
          
          <div>
            <label class="text-xs" style="color: var(--text-secondary)">Host URL (with port)</label>
            <input
              v-model="newHost.url"
              type="text"
              placeholder="e.g., 192.168.1.100:4010"
              class="w-full text-xs px-2 py-1 rounded border"
              style="background: var(--bg-primary); border-color: var(--border-secondary); color: var(--text-primary)"
            />
          </div>
        </div>
        
        <div class="flex justify-end space-x-2 mt-4">
          <button
            @click="showAddModal = false"
            class="px-3 py-1 text-xs rounded"
            style="background: var(--bg-tertiary); color: var(--text-secondary)"
          >
            Cancel
          </button>
          <button
            @click="addNewHost"
            class="px-3 py-1 text-xs rounded"
            style="background: var(--accent-primary); color: var(--bg-primary)"
          >
            Add
          </button>
        </div>
      </div>
    </div>

    <!-- Delete confirmation -->
    <div v-if="showDeleteConfirm" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" @click.self="showDeleteConfirm = false">
      <div class="rounded-lg p-4 w-80" style="background: var(--bg-secondary); border: 1px solid var(--border-primary)">
        <h3 class="text-sm font-medium mb-2" style="color: var(--text-primary)">Delete Host?</h3>
        <p class="text-xs mb-4" style="color: var(--text-secondary)">Are you sure you want to delete "{{ hostToDelete?.name }}"?</p>
        <div class="flex justify-end space-x-2">
          <button
            @click="showDeleteConfirm = false"
            class="px-3 py-1 text-xs rounded"
            style="background: var(--bg-tertiary); color: var(--text-secondary)"
          >
            Cancel
          </button>
          <button
            @click="confirmDelete"
            class="px-3 py-1 text-xs rounded"
            style="background: #dc2626; color: white"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { hostManager, type Host } from '@/services/hostManager'

const hosts = hostManager.hosts
const selectedId = ref<string>(hostManager.selectedHostId.value || '')

const showAddModal = ref(false)
const showDeleteConfirm = ref(false)
const showHostModal = ref(false)
const hostToDelete = ref<Host | null>(null)

const newHost = ref({
  name: '',
  url: ''
})

const currentHost = computed(() => {
  if (!selectedId.value) return null
  return hosts.value.find(h => h.id === selectedId.value) || null
})

watch(() => hostManager.selectedHostId.value, (newVal) => {
  selectedId.value = newVal || ''
})

const onSelect = () => {
  hostManager.setSelectedHost(selectedId.value || null)
}

// ...

const addNewHost = () => {
  if (newHost.value.name && newHost.value.url) {
    hostManager.addHost(newHost.value.name, newHost.value.url, false)
    const hostsList = hostManager.hosts.value
    if (hostsList.length > 0) {
      const added = hostsList[hostsList.length - 1]
      if (added) {
        hostManager.setSelectedHost(added.id)
        selectedId.value = added.id
      }
    }
    
    newHost.value = { name: '', url: '' }
    showAddModal.value = false
    showHostModal.value = false
  }
}

const selectHost = (hostId: string) => {
  selectedId.value = hostId
  hostManager.setSelectedHost(hostId || null)
}

const promptDelete = (host: Host) => {
  hostToDelete.value = host
  showDeleteConfirm.value = true
}

const confirmDelete = () => {
  if (hostToDelete.value) {
    hostManager.removeHost(hostToDelete.value.id)
    selectedId.value = hostManager.selectedHostId.value || ''
    hostToDelete.value = null
  }
  showDeleteConfirm.value = false
}
</script>
