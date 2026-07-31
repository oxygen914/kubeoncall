import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ClientEventReporter,
  createClientEventPayload,
  installGlobalErrorReporting,
  reportApiError,
  sanitizeMessage,
  sendClientEvent,
  type ClientEventPayload,
} from '../clientEvents'

const context = {
  release: 'sha-123',
  environment: 'test',
  route: `/reset/${'a'.repeat(100)}?access_token=route-secret`,
  browser: 'Chrome 126 / MacIntel',
}

describe('client error telemetry', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('builds an exact allowlist payload and removes route/query secrets', () => {
    const payload = createClientEventPayload(
      {
        errorCode: 'API_FAILURE',
        requestId: 'req_1',
        message: 'Authorization: Bearer abc.def api_key=secret https://x.test/a?token=top-secret',
      },
      context,
    )

    expect(Object.keys(payload).sort()).toEqual(
      ['release', 'environment', 'route', 'errorCode', 'requestId', 'browser', 'message'].sort(),
    )
    expect(payload.route).toBe('/reset/:id')
    expect(JSON.stringify(payload)).not.toContain('abc.def')
    expect(JSON.stringify(payload)).not.toContain('top-secret')
    expect(JSON.stringify(payload)).not.toContain('api_key=secret')
  })

  it('redacts credentials, flattens lines and bounds the message summary', () => {
    const summary = sanitizeMessage(
      `password=hunter2\nCookie: session=abc Bearer token-value ${'x'.repeat(500)}`,
    )

    expect(summary).not.toContain('hunter2')
    expect(summary).not.toContain('session=abc')
    expect(summary).not.toContain('token-value')
    expect(summary).not.toContain('\n')
    expect(summary.length).toBeLessThanOrEqual(240)
  })

  it('rate-limits the same error class on a route while allowing another class', () => {
    const sent: ClientEventPayload[] = []
    let now = 1_000
    const reporter = new ClientEventReporter({
      now: () => now,
      context: () => context,
      transport: (payload) => sent.push(payload),
      rateLimitMs: 60_000,
    })

    expect(reporter.report({ errorCode: 'WINDOW_ERROR', message: 'boom' })).toBe(true)
    now = 2_000
    expect(reporter.report({ errorCode: 'WINDOW_ERROR', message: 'boom' })).toBe(false)
    expect(reporter.report({ errorCode: 'WINDOW_ERROR', message: 'different' })).toBe(false)
    expect(reporter.report({ errorCode: 'RENDER_ERROR', message: 'different' })).toBe(true)
    now = 62_000
    expect(reporter.report({ errorCode: 'WINDOW_ERROR', message: 'boom' })).toBe(true)
    expect(sent).toHaveLength(3)
  })

  it('reports server/network API failures but ignores expected client errors', () => {
    const sent: ClientEventPayload[] = []
    const reporter = new ClientEventReporter({
      context: () => context,
      transport: (payload) => sent.push(payload),
    })

    reportApiError({ code: 'VALIDATION_FAILED', message: 'bad input', status: 400 }, reporter)
    reportApiError(
      {
        code: 'SERVICE_UNAVAILABLE',
        message: 'upstream failed',
        requestId: 'req_5xx',
        status: 503,
      },
      reporter,
    )

    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({
      errorCode: 'SERVICE_UNAVAILABLE',
      requestId: 'req_5xx',
    })
  })

  it('does not throw when sendBeacon declines and keepalive fetch fails', () => {
    const beacon = vi.fn(() => false)
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: beacon,
    })
    const fetchMock = vi.fn(() => Promise.reject(new Error('offline')))
    vi.stubGlobal('fetch', fetchMock)

    expect(() =>
      sendClientEvent({
        release: 'dev',
        environment: 'test',
        route: '/audit',
        errorCode: 'WINDOW_ERROR',
        requestId: null,
        browser: 'Other / test',
        message: 'boom',
      }),
    ).not.toThrow()
    expect(beacon).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/client-events',
      expect.objectContaining({ method: 'POST', keepalive: true, credentials: 'include' }),
    )
  })

  it('captures window errors and unhandled rejections and removes listeners on cleanup', () => {
    const sent: ClientEventPayload[] = []
    const reporter = new ClientEventReporter({
      context: () => context,
      transport: (payload) => sent.push(payload),
    })
    const cleanup = installGlobalErrorReporting(reporter)

    window.dispatchEvent(new ErrorEvent('error', { message: 'render crashed' }))
    const rejection = new Event('unhandledrejection')
    Object.defineProperty(rejection, 'reason', {
      value: { code: 'UPSTREAM_FAILED', message: 'api failed', status: 503 },
    })
    window.dispatchEvent(rejection)

    expect(sent.map((payload) => payload.errorCode)).toEqual(['WINDOW_ERROR', 'UPSTREAM_FAILED'])

    cleanup()
    window.dispatchEvent(new ErrorEvent('error', { message: 'after cleanup' }))
    expect(sent).toHaveLength(2)
  })

  it('swallows reporter transport exceptions', () => {
    const reporter = new ClientEventReporter({
      context: () => context,
      transport: () => {
        throw new Error('telemetry unavailable')
      },
    })

    expect(() => reporter.report({ errorCode: 'RENDER_ERROR', message: 'boom' })).not.toThrow()
    expect(reporter.report({ errorCode: 'RENDER_ERROR', message: 'another' })).toBe(false)
  })
})
