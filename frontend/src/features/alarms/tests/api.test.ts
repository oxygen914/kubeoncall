import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { approveAlarmSilence, confirmAlarmRecovery } from '../api'

function jsonResponse(data: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => ({
      data,
      meta: {
        requestId: 'req_alarm_command',
        timestamp: '2026-07-20T12:00:00Z',
      },
    }),
  } as Response
}

describe('alarm command API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts a versioned and idempotent recovery confirmation', async () => {
    const idempotencyKey = 'recovery-test-key-0001'
    fetchMock.mockResolvedValue(
      jsonResponse({ alarmId: 'alm/1', status: 'RESOLVED', recovered: true, version: 8 }),
    )

    await confirmAlarmRecovery('alm/1', 7, 'stable for ten minutes', idempotencyKey)

    expect(idempotencyKey.length).toBeGreaterThanOrEqual(16)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/alarms/alm%2F1/recovery-confirmations',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          'If-Match': '7',
          'Idempotency-Key': idempotencyKey,
        }),
        body: JSON.stringify({
          healthCheckPassed: true,
          note: 'stable for ten minutes',
        }),
      }),
    )
  })

  it('posts a versioned and idempotent silence approval', async () => {
    const idempotencyKey = 'silence-test-key-00001'
    fetchMock.mockResolvedValue(
      jsonResponse({
        alarmId: 'alm_1',
        status: 'SUPPRESSED',
        silenceId: 'asil_1',
        expiresAt: '2026-07-21T00:00:00Z',
        version: 9,
      }),
    )

    await approveAlarmSilence(
      'alm_1',
      8,
      'planned maintenance',
      '2026-07-21T00:00:00Z',
      idempotencyKey,
    )

    expect(idempotencyKey.length).toBeGreaterThanOrEqual(16)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/alarms/alm_1/silence-approvals',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          'If-Match': '8',
          'Idempotency-Key': idempotencyKey,
        }),
        body: JSON.stringify({
          reason: 'planned maintenance',
          expiresAt: '2026-07-21T00:00:00Z',
        }),
      }),
    )
  })
})
