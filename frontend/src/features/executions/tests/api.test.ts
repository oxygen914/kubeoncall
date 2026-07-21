import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getExecution, listExecutionNodes, listExecutions } from '../api'

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => body,
  } as Response
}

describe('executions API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('lists execution records with filters', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [],
        page: { number: 2, size: 20, totalElements: 21, totalPages: 2, hasNext: false },
        meta: { requestId: 'req_exec', timestamp: '2026-07-20T12:00:00Z' },
      }),
    )

    await listExecutions({ page: 2, size: 20, status: 'RUNNING', alarmId: 'alm_1' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/executions?page=2&size=20&status=RUNNING&alarmId=alm_1',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('loads encoded execution detail and node paths', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({
          data: { id: 'exec/1' },
          meta: { requestId: 'req_detail', timestamp: '2026-07-20T12:00:00Z' },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          data: [{ id: 'node_1' }],
          meta: { requestId: 'req_nodes', timestamp: '2026-07-20T12:00:00Z' },
        }),
      )

    await getExecution('exec/1')
    await listExecutionNodes('exec/1')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/executions/exec%2F1',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/executions/exec%2F1/nodes',
      expect.objectContaining({ method: 'GET' }),
    )
  })
})
