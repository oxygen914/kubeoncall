import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../client'
import { ApiError } from '../errors'

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => body,
  } as Response
}

describe('api list envelope', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('preserves data and page from a paged response', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [{ id: 'alm_1' }],
        page: {
          number: 2,
          size: 20,
          totalElements: 41,
          totalPages: 3,
          hasNext: true,
        },
        meta: {
          requestId: 'req_server',
          timestamp: '2026-07-20T12:00:00Z',
        },
      }),
    )

    await expect(
      api.list<{ id: string }>('/api/v1/alarms', { query: { page: 2, size: 20 } }),
    ).resolves.toEqual({
      data: [{ id: 'alm_1' }],
      page: {
        number: 2,
        size: 20,
        totalElements: 41,
        totalPages: 3,
        hasNext: true,
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/alarms?page=2&size=20',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('rejects a successful response that omits pagination metadata', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [{ id: 'alm_1' }],
        meta: {
          requestId: 'req_invalid_list',
          timestamp: '2026-07-20T12:00:00Z',
        },
      }),
    )

    const request = api.list<{ id: string }>('/api/v1/alarms')

    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      status: 200,
      requestId: 'req_invalid_list',
      retryable: false,
    })
  })
})
