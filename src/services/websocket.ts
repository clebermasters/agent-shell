import { ref, watch } from 'vue'
import type { WsMessage } from '@/types'
import { hostManager } from './hostManager'

// HARDCODED BACKEND FOR TESTING - change this to your server IP:port
const HARDCODED_BACKEND = '192.168.0.76:4010' // TODO: remove this after testing

type MessageHandler<T extends WsMessage = WsMessage> = (data: T) => void
type DisconnectHandler = () => void
type ErrorHandler = (error: string) => void

// Singleton WebSocket manager to ensure single connection
class WebSocketManager {
  private ws: WebSocket | null = null
  public isConnected = ref(false)
  public connectionError = ref<string | null>(null)
  public lastError = ref<string | null>(null)
  private messageHandlers: Map<string, MessageHandler[]> = new Map()
  private disconnectHandlers: DisconnectHandler[] = []
  private errorHandlers: ErrorHandler[] = []
  private connectionPromise: Promise<void> | null = null
  private pingInterval: number | null = null
  private reconnectAttempts: number = 0
  private readonly maxReconnectAttempts: number = 5

  constructor() {
    // Watch for host changes and reconnect
    watch(() => hostManager.selectedHostId.value, () => {
      console.log('Host changed, reconnecting...')
      this.reconnect()
    })
  }

  reconnect(): void {
    this.close()
    this.connect()
  }

  private setError(message: string): void {
    this.connectionError.value = message
    this.lastError.value = message
    console.error('Connection error:', message)
    // Notify all error handlers
    this.errorHandlers.forEach(handler => handler(message))
  }

  private clearError(): void {
    this.connectionError.value = null
  }

  onError(handler: ErrorHandler): void {
    this.errorHandlers.push(handler)
  }

  offError(handler: ErrorHandler): void {
    const index = this.errorHandlers.indexOf(handler)
    if (index > -1) {
      this.errorHandlers.splice(index, 1)
    }
  }

  connect(): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      console.log('WebSocket already connected')
      this.clearError()
      return Promise.resolve()
    }

    if (this.connectionPromise) {
      console.log('WebSocket connection in progress...')
      return this.connectionPromise
    }

    this.clearError()
    this.connectionPromise = new Promise((resolve, reject) => {
      // Always use hardcoded backend for testing
      const wsUrl = `ws://${HARDCODED_BACKEND}/ws`
      console.log('Using HARDCODED backend:', wsUrl)
      
      console.log('Creating WebSocket to:', wsUrl)
      
      // Test if we can reach the server first
      fetch(`http://${HARDCODED_BACKEND}/api/clients`, { mode: 'no-cors' })
        .then(() => console.log('HTTP connectivity test: OK'))
        .catch((err) => console.log('HTTP connectivity test failed:', err.message))
      
      const ws = new WebSocket(wsUrl)
      this.ws = ws
      
      const connectionTimeout = setTimeout(() => {
        if (ws.readyState !== WebSocket.OPEN) {
          console.error('Connection timeout')
          ws.close()
          this.setError(`Connection timeout to ${HARDCODED_BACKEND}. Is the server running?`)
          reject(new Error('Connection timeout'))
        }
      }, 10000) // 10 second timeout
      
      ws.onopen = () => {
        clearTimeout(connectionTimeout)
        this.isConnected.value = true
        this.connectionPromise = null
        this.reconnectAttempts = 0
        this.clearError()
        console.log('WebSocket connected')
        
        // Start ping to keep connection alive
        this.startPing()
        
        resolve()
      }
      
      this.ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data) as WsMessage
          // Don't log output messages as they can be very frequent
          if (data.type !== 'output') {
            console.log('WebSocket message received:', data.type, data.type === 'audio-stream' ? '(audio data)' : data)
          }
          const handlers = this.messageHandlers.get(data.type) || []
          // Only log handler count for non-output messages
          if (data.type !== 'output' && handlers.length === 0) {
            console.warn(`No handlers for message type: ${data.type}`)
          }
          handlers.forEach(handler => handler(data))
        } catch (error) {
          console.error('Error parsing WebSocket message:', error)
        }
      }
      
      ws.onerror = (error) => {
        console.error('WebSocket error:', error)
        this.setError(`Failed to connect to ${HARDCODED_BACKEND}. Check if the server is running.`)
      }
      
      ws.onclose = (event) => {
        console.log('WebSocket disconnected:', event.code, event.reason)
        this.isConnected.value = false
        this.ws = null
        this.connectionPromise = null
        this.stopPing()
        
        // Notify disconnect handlers
        this.disconnectHandlers.forEach(handler => handler())
        
        // Set error message
        if (event.code !== 1000) {
          this.setError(`Disconnected (code: ${event.code}). ${event.reason || 'Connection failed'}`)
        }
        
        // Only reconnect if we haven't exceeded max attempts
        if (this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++
          const delay = event.code === 1000 ? 3000 : 5000 // 5s for errors
          console.log(`Reconnect attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts} in ${delay}ms`)
          setTimeout(() => this.connect(), delay)
        } else {
          const errorMsg = `Failed to connect after ${this.maxReconnectAttempts} attempts. Server may be offline.`
          this.setError(errorMsg)
          console.error(errorMsg)
        }
      }
    })

    return this.connectionPromise
  }

  send(data: WsMessage): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify(data))
      } catch (err) {
        console.error('WebSocket send failed:', err)
        // Force reconnect on send failure
        this.connect()
      }
    } else {
      console.warn('WebSocket not connected, message not sent:', data)
      // Try to reconnect
      this.connect()
    }
  }

  onMessage<T extends WsMessage = WsMessage>(type: string, handler: MessageHandler<T>): void {
    if (!this.messageHandlers.has(type)) {
      this.messageHandlers.set(type, [])
    }
    this.messageHandlers.get(type)!.push(handler as MessageHandler)
  }

  offMessage<T extends WsMessage = WsMessage>(type: string, handler?: MessageHandler<T>): void {
    if (!handler) {
      // Remove all handlers for this type
      this.messageHandlers.delete(type)
      return
    }
    
    if (this.messageHandlers.has(type)) {
      const handlers = this.messageHandlers.get(type)!
      const index = handlers.indexOf(handler as MessageHandler)
      if (index > -1) {
        handlers.splice(index, 1)
      }
    }
  }

  private startPing(): void {
    this.stopPing()
    this.pingInterval = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        try {
          this.ws.send(JSON.stringify({ type: 'ping' }))
        } catch (err) {
          console.warn('Ping failed:', err)
          this.connect() // Try to reconnect
        }
      }
    }, 30000) // Ping every 30 seconds
  }
  
  private stopPing(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval)
      this.pingInterval = null
    }
  }
  
  close(): void {
    this.stopPing()
    if (this.ws) {
      this.ws.close()
    }
  }
  
  ensureConnected(): Promise<void> {
    if (this.isConnected.value) {
      return Promise.resolve()
    }
    return this.connect()
  }
  
  onDisconnect(handler: DisconnectHandler): void {
    this.disconnectHandlers.push(handler)
  }
  
  offDisconnect(handler: DisconnectHandler): void {
    const index = this.disconnectHandlers.indexOf(handler)
    if (index > -1) {
      this.disconnectHandlers.splice(index, 1)
    }
  }
}

// Export singleton instance
export const wsManager = new WebSocketManager()