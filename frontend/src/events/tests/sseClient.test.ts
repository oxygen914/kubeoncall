import { describe, expect, it, vi } from 'vitest'
import { SseClient, parseRealtimeEvent } from '../sseClient'
import type { RealtimeEvent } from '../types'

class FakeEventSource {
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>()
  closed = false

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, listener)
  }

  close(): void {
    this.closed = true
  }

  emit(type: string, data: Record<string, unknown>, lastEventId = ''): void {
    const message = new MessageEvent<string>(type, {
      data: JSON.stringify(data),
      lastEventId,
    })
    if (type === 'message') this.onmessage?.(message)
    else this.listeners.get(type)?.(message)
  }
}

class MemoryStorage {
  readonly values = new Map<string, string>()

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }
}

describe('parseRealtimeEvent', () => {
  it('uses the SSE event name and Last-Event-ID when the data is a compact hint', () => {
    const result = parseRealtimeEvent(
      new MessageEvent<string>('execution.updated', {
        data: JSON.stringify({
          schemaVersion: 1,
          resourceType: 'EXECUTION',
          resourceId: 'exec_1',
          data: { status: 'RUNNING' },
        }),
        lastEventId: 'evt_1',
      }),
    )

    expect(result).toMatchObject({
      eventId: 'evt_1',
      eventType: 'execution.updated',
      schemaVersion: 1,
      resourceId: 'exec_1',
      data: { status: 'RUNNING' },
    })
  })

  it('parses the backend EventEnvelope payload field', () => {
    const result = parseRealtimeEvent(
      new MessageEvent<string>('approval.decided', {
        data: JSON.stringify({
          eventId: 'evt_2',
          topic: 'approval',
          type: 'approval.decided',
          resourceId: 'apr_1',
          occurredAt: '2026-07-21T00:00:00Z',
          payload: { status: 'APPROVED' },
          schemaVersion: 1,
        }),
        lastEventId: 'evt_2',
      }),
    )

    expect(result).toMatchObject({
      eventId: 'evt_2',
      eventType: 'approval.decided',
      resourceId: 'apr_1',
      data: { status: 'APPROVED' },
    })
  })

  it('rejects malformed JSON and unsupported schema versions', () => {
    expect(parseRealtimeEvent(new MessageEvent('message', { data: '{' }))).toBeNull()
    expect(
      parseRealtimeEvent(
        new MessageEvent('alarm.updated', {
          data: JSON.stringify({ schemaVersion: 2, resourceId: 'alm_1' }),
        }),
      ),
    ).toBeNull()
  })
})

describe('SseClient', () => {
  it('restores the cursor, subscribes to topics and deduplicates replayed events', () => {
    const storage = new MemoryStorage()
    storage.setItem('koc.realtime.lastEventId', 'evt_previous')
    const sources: FakeEventSource[] = []
    const urls: string[] = []
    const received: RealtimeEvent[] = []
    const client = new SseClient({
      url: '/api/v1/events/stream',
      topics: ['alarms', 'tasks'],
      storage,
      eventSourceFactory: (url) => {
        urls.push(url)
        const source = new FakeEventSource()
        sources.push(source)
        return source
      },
      onEvent: (event) => received.push(event),
      onGap: vi.fn(),
    })

    client.start()
    sources[0]!.emit('alarm.updated', { schemaVersion: 1, resourceId: 'alm_1' }, 'evt_2')
    sources[0]!.emit('alarm.updated', { schemaVersion: 1, resourceId: 'alm_1' }, 'evt_2')
    sources[0]!.emit('approval.requested', { schemaVersion: 1, resourceId: 'apr_1' }, 'evt_3')

    const connectedUrl = new URL(urls[0]!)
    expect(connectedUrl.searchParams.get('topics')).toBe('alarms,tasks')
    expect(connectedUrl.searchParams.get('lastEventId')).toBe('evt_previous')
    expect(received).toHaveLength(2)
    expect(received[1]).toMatchObject({
      eventType: 'approval.requested',
      resourceId: 'apr_1',
    })
    expect(storage.getItem('koc.realtime.lastEventId')).toBe('evt_3')
  })

  it('invalidates all data and clears the cursor on cursor expiration or a gap', () => {
    const storage = new MemoryStorage()
    storage.setItem('koc.realtime.lastEventId', 'evt_previous')
    const source = new FakeEventSource()
    const onGap = vi.fn()
    const onEvent = vi.fn()
    const client = new SseClient({
      url: '/api/v1/events/stream',
      topics: ['alarms'],
      storage,
      eventSourceFactory: () => source,
      onEvent,
      onGap,
    })

    client.start()
    source.emit('gap', { cursorExpired: true, requestedEventId: 'evt_old' })

    expect(onGap).toHaveBeenCalledOnce()
    expect(onEvent).not.toHaveBeenCalled()
    expect(storage.getItem('koc.realtime.lastEventId')).toBeNull()
  })

  it('recognizes a top-level cursor-expiration control payload', () => {
    const storage = new MemoryStorage()
    const source = new FakeEventSource()
    const onGap = vi.fn()
    const client = new SseClient({
      url: '/api/v1/events/stream',
      topics: ['alarms'],
      storage,
      eventSourceFactory: () => source,
      onEvent: vi.fn(),
      onGap,
    })

    client.start()
    source.emit('message', {
      eventType: 'stream.control',
      schemaVersion: 1,
      code: 'EVENT_CURSOR_EXPIRED',
    })

    expect(onGap).toHaveBeenCalledOnce()
  })

  it('uses bounded exponential reconnect delays and cancels pending work on stop', () => {
    const sources: FakeEventSource[] = []
    const callbacks: Array<() => void> = []
    const delays: number[] = []
    const clearTimer = vi.fn()
    const statuses: string[] = []
    const client = new SseClient({
      url: '/api/v1/events/stream',
      topics: ['executions'],
      eventSourceFactory: () => {
        const source = new FakeEventSource()
        sources.push(source)
        return source
      },
      setTimer: (callback, delay) => {
        callbacks.push(callback)
        delays.push(delay)
        return callbacks.length as unknown as ReturnType<typeof setTimeout>
      },
      clearTimer,
      reconnectDelayMs: 1_000,
      maxReconnectDelayMs: 2_000,
      onEvent: vi.fn(),
      onGap: vi.fn(),
      onStatusChange: (status) => statuses.push(status),
    })

    client.start()
    sources[0]!.onerror?.(new Event('error'))
    expect(delays).toEqual([1_000])
    expect(sources[0]!.closed).toBe(true)

    callbacks[0]!()
    sources[1]!.onerror?.(new Event('error'))
    expect(delays).toEqual([1_000, 2_000])

    client.stop()
    expect(clearTimer).toHaveBeenCalledWith(2)
    expect(statuses.at(-1)).toBe('closed')
  })
})
