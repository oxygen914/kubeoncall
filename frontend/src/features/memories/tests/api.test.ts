import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryExtraction, restoreMemory } from '../api'

describe('memory API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('creates an asynchronous extraction task', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: { extractionId: 'mext_1', taskId: 'task_1', status: 'PENDING' },
        meta: { requestId: 'req_memory', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await createMemoryExtraction({
      sourceType: 'EXECUTION',
      sourcePublicId: 'exec_1',
      dedupeKey: 'exec_1:v3',
      memoryType: 'INCIDENT_SUMMARY',
      scope: 'GLOBAL',
      subject: 'NodeNotReady conclusion',
      content: 'The node recovered after kubelet restart.',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/memories/extractions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          sourceType: 'EXECUTION',
          sourcePublicId: 'exec_1',
          dedupeKey: 'exec_1:v3',
          memoryType: 'INCIDENT_SUMMARY',
          scope: 'GLOBAL',
          subject: 'NodeNotReady conclusion',
          content: 'The node recovered after kubelet restart.',
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': expect.stringMatching(/^memory_extract_/),
        }),
      }),
    )
  })

  it('restores a soft-deleted memory with optimistic concurrency', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: { id: 'mem_1', status: 'ACTIVE', version: 8 },
        meta: { requestId: 'req_restore', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await restoreMemory('mem/1', 7)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/memories/mem%2F1/restore',
      expect.objectContaining({
        method: 'POST',
        body: undefined,
        headers: expect.objectContaining({ 'If-Match': '7' }),
      }),
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
