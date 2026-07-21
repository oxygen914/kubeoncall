import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAuditEvent, listAuditEvents } from '../api'

describe('audit API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('passes every frozen audit filter through the list contract', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [],
        page: {
          number: 2,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        },
        meta: { requestId: 'req_audit', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await listAuditEvents({
      actor: 'alice',
      action: 'alarm.acknowledge',
      resourceType: 'ALARM',
      resourceId: 'alm_1',
      result: 'SUCCESS',
      requestId: 'req_1',
      from: '2026-07-20T00:00:00Z',
      to: '2026-07-21T00:00:00Z',
      page: 2,
      size: 20,
    })

    const url = new URL(fetchMock.mock.calls[0]![0] as string, 'http://localhost')
    expect(url.pathname).toBe('/api/v1/audit-events')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      actor: 'alice',
      action: 'alarm.acknowledge',
      resourceType: 'ALARM',
      resourceId: 'alm_1',
      result: 'SUCCESS',
      requestId: 'req_1',
      from: '2026-07-20T00:00:00Z',
      to: '2026-07-21T00:00:00Z',
      page: '2',
      size: '20',
    })
  })

  it('loads an encoded audit event identifier', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: { id: 'aud/1', action: 'skill.enable' },
        meta: { requestId: 'req_audit_detail', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await getAuditEvent('aud/1')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/audit-events/aud%2F1',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })
})

function jsonResponse(payload: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => payload,
  } as Response
}
