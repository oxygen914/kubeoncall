import type { RealtimeConnectionStatus, RealtimeEvent, RealtimeTopic } from './types'

const LAST_EVENT_ID_STORAGE_KEY = 'koc.realtime.lastEventId'
const MAX_SEEN_EVENT_IDS = 1_000
const DEFAULT_RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 30_000

const NAMED_EVENT_TYPES = [
  'alarm.created',
  'alarm.updated',
  'alarm.resolved',
  'alarm.acknowledged',
  'alarm.recovery.confirmed',
  'alarm.silence.approved',
  'approval.created',
  'approval.requested',
  'approval.decided',
  'execution.created',
  'execution.updated',
  'execution.node.updated',
  'task.created',
  'task.updated',
  'system.dependency.updated',
  'cursor.expired',
  'stream.gap',
  'gap',
] as const

interface EventSourceLike {
  onopen: ((event: Event) => void) | null
  onerror: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent<string>) => void) | null
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void
  close(): void
}

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface SseClientOptions {
  url: string
  topics: readonly RealtimeTopic[]
  onEvent: (event: RealtimeEvent) => void
  onGap: () => void
  onStatusChange?: (status: RealtimeConnectionStatus) => void
  eventSourceFactory?: (url: string) => EventSourceLike
  storage?: StorageLike
  storageKey?: string
  setTimer?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void
  reconnectDelayMs?: number
  maxReconnectDelayMs?: number
}

/**
 * Small EventSource lifecycle wrapper.
 *
 * Native EventSource reconnects with a fixed server-provided delay. We close it
 * after errors and own reconnect scheduling instead so repeated failures back
 * off to a bounded 30 seconds and cleanup is deterministic on logout/unmount.
 */
export class SseClient {
  private readonly options: SseClientOptions
  private readonly seenEventIds = new Set<string>()
  private readonly seenEventIdOrder: string[] = []
  private source: EventSourceLike | undefined
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined
  private reconnectAttempt = 0
  private stopped = true

  constructor(options: SseClientOptions) {
    this.options = options
  }

  start(): void {
    if (!this.stopped) return
    this.stopped = false
    this.reconnectAttempt = 0
    this.open('connecting')
  }

  stop(): void {
    this.stopped = true
    if (this.reconnectTimer !== undefined) {
      this.clearTimer(this.reconnectTimer)
      this.reconnectTimer = undefined
    }
    this.closeSource()
    this.changeStatus('closed')
  }

  private open(status: RealtimeConnectionStatus): void {
    if (this.stopped) return
    this.changeStatus(status)

    const source = this.eventSourceFactory(this.buildUrl())
    this.source = source
    source.onopen = () => {
      if (source !== this.source || this.stopped) return
      this.reconnectAttempt = 0
      this.changeStatus('open')
    }
    source.onerror = () => {
      if (source !== this.source || this.stopped) return
      this.closeSource()
      this.scheduleReconnect()
    }
    source.onmessage = this.handleMessage
    for (const eventType of NAMED_EVENT_TYPES) {
      source.addEventListener(eventType, this.handleMessage)
    }
  }

  private readonly handleMessage = (message: MessageEvent<string>): void => {
    const event = parseRealtimeEvent(message)
    if (!event) return

    if (event.eventId && this.seenEventIds.has(event.eventId)) return
    if (event.eventId) this.rememberEventId(event.eventId)

    if (isGapEvent(event)) {
      this.storage?.removeItem(this.storageKey)
      this.options.onGap()
      return
    }

    if (event.eventId) {
      this.storage?.setItem(this.storageKey, event.eventId)
    }
    this.options.onEvent(event)
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer !== undefined) return
    this.changeStatus('reconnecting')
    const baseDelay = this.options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS
    const maximumDelay = this.options.maxReconnectDelayMs ?? MAX_RECONNECT_DELAY_MS
    const delay = Math.min(baseDelay * 2 ** this.reconnectAttempt, maximumDelay)
    this.reconnectAttempt += 1
    this.reconnectTimer = this.setTimer(() => {
      this.reconnectTimer = undefined
      this.open('reconnecting')
    }, delay)
  }

  private buildUrl(): string {
    const base = typeof window === 'undefined' ? 'http://localhost' : window.location.origin
    const url = new URL(this.options.url, base)
    url.searchParams.set('topics', this.options.topics.join(','))
    const lastEventId = this.storage?.getItem(this.storageKey)
    if (lastEventId) url.searchParams.set('lastEventId', lastEventId)
    return url.toString()
  }

  private rememberEventId(eventId: string): void {
    this.seenEventIds.add(eventId)
    this.seenEventIdOrder.push(eventId)
    if (this.seenEventIdOrder.length <= MAX_SEEN_EVENT_IDS) return
    const oldest = this.seenEventIdOrder.shift()
    if (oldest) this.seenEventIds.delete(oldest)
  }

  private closeSource(): void {
    const source = this.source
    this.source = undefined
    source?.close()
  }

  private changeStatus(status: RealtimeConnectionStatus): void {
    this.options.onStatusChange?.(status)
  }

  private eventSourceFactory(url: string): EventSourceLike {
    if (this.options.eventSourceFactory) return this.options.eventSourceFactory(url)
    return new EventSource(url, { withCredentials: true })
  }

  private get storage(): StorageLike | undefined {
    if (this.options.storage) return this.options.storage
    return typeof window === 'undefined' ? undefined : window.sessionStorage
  }

  private get storageKey(): string {
    return this.options.storageKey ?? LAST_EVENT_ID_STORAGE_KEY
  }

  private setTimer(callback: () => void, delayMs: number): ReturnType<typeof setTimeout> {
    return this.options.setTimer
      ? this.options.setTimer(callback, delayMs)
      : setTimeout(callback, delayMs)
  }

  private clearTimer(timer: ReturnType<typeof setTimeout>): void {
    if (this.options.clearTimer) {
      this.options.clearTimer(timer)
      return
    }
    clearTimeout(timer)
  }
}

export function parseRealtimeEvent(message: MessageEvent<string>): RealtimeEvent | null {
  let payload: unknown
  try {
    payload = JSON.parse(message.data) as unknown
  } catch {
    return null
  }
  if (!isRecord(payload)) return null

  const eventType =
    readString(payload.eventType) ??
    (message.type !== 'message' ? readString(message.type) : undefined)
  const schemaVersion = readNumber(payload.schemaVersion)
  const isGap = message.type === 'gap'
  if (!eventType || (!isGap && schemaVersion !== 1)) return null

  return {
    eventId: readString(payload.eventId) ?? readString(message.lastEventId),
    eventType,
    schemaVersion: schemaVersion ?? 1,
    resourceType: readString(payload.resourceType),
    resourceId: readString(payload.resourceId),
    occurredAt: readString(payload.occurredAt),
    version: readNumber(payload.version),
    // Some control events put `code`, `gap` or `cursorExpired` at the top
    // level instead of inside `data`; retain those fields for gap detection.
    data: isRecord(payload.data)
      ? payload.data
      : isRecord(payload.payload)
        ? payload.payload
        : payload,
  }
}

export function isGapEvent(event: RealtimeEvent): boolean {
  const normalizedType = event.eventType.toLowerCase()
  return (
    normalizedType.includes('gap') ||
    (normalizedType.includes('cursor') && normalizedType.includes('expired')) ||
    event.data.cursorExpired === true ||
    event.data.gap === true ||
    event.data.code === 'EVENT_CURSOR_EXPIRED'
  )
}

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function readNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
