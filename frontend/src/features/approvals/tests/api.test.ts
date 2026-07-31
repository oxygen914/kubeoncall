import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { decideApproval, listApprovals } from '../api'

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => body,
  } as Response
}

describe('approvals API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('preserves pagination when listing approvals', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [{ id: 'apr_1' }],
        page: { number: 1, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
        meta: { requestId: 'req_1', timestamp: '2026-07-20T12:00:00Z' },
      }),
    )

    await expect(
      listApprovals({ page: 1, size: 20, status: 'PENDING', risk: '' }),
    ).resolves.toMatchObject({
      data: [{ id: 'apr_1' }],
      page: { totalElements: 1, hasNext: false },
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/approvals?page=1&size=20&status=PENDING',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('posts a versioned and idempotent decision', async () => {
    const idempotencyKey = 'approval-test-key-0001'
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          data: { taskId: 'task_1', status: 'PENDING' },
          meta: { requestId: 'req_2', timestamp: '2026-07-20T12:00:00Z' },
        },
        202,
      ),
    )

    await expect(
      decideApproval('apr/1', 4, 'APPROVED', 'verified', idempotencyKey),
    ).resolves.toEqual({ taskId: 'task_1', status: 'PENDING' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/approvals/apr%2F1/decisions',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          'If-Match': '4',
          'Idempotency-Key': idempotencyKey,
        }),
        body: JSON.stringify({ decision: 'APPROVED', comment: 'verified' }),
      }),
    )
  })
})
