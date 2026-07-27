import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelSandboxRun, listSandboxRuns, requestArtifactDownload } from '../api'

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: { get: () => 'application/json' },
    json: async () => body,
  } as unknown as Response
}

describe('sandbox runs API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('lists runs with only selected filters', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ data: [], meta: { requestId: 'req_1', timestamp: 'now' } }),
    )

    await listSandboxRuns({ status: 'RUNNING', executionId: 'exe_1' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sandbox-runs?status=RUNNING&executionId=exe_1',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('cancels with an optimistic version and requests download only on demand', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({ data: { id: 'sbx_1' }, meta: { requestId: 'req_2', timestamp: 'now' } }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          data: { url: 'https://minio.example/one-time', expiresAt: '2026-07-27T12:01:00Z' },
          meta: { requestId: 'req_3', timestamp: 'now' },
        }),
      )

    await cancelSandboxRun('sbx/1', 4)
    await requestArtifactDownload('sbx/1', 'sba/1')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/sandbox-runs/sbx%2F1/cancel',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'If-Match': '4' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sandbox-runs/sbx%2F1/artifacts/sba%2F1/download',
      expect.objectContaining({ method: 'GET' }),
    )
  })
})
