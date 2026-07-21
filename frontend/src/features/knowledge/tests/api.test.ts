import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createKnowledgeImport, listKnowledgeDocuments } from '../api'

describe('knowledge API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('preserves the paged document response', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [{ id: 'kdoc_1', title: 'NodeNotReady', status: 'ACTIVE' }],
        page: {
          number: 1,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        },
        meta: { requestId: 'req_knowledge', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    const result = await listKnowledgeDocuments({ page: 1, size: 20, status: 'ACTIVE' })

    expect(result.page.totalElements).toBe(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/knowledge/documents?page=1&size=20&status=ACTIVE',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })

  it('uploads knowledge as multipart with task idempotency and no manual content-type', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: { taskId: 'task_1', status: 'PENDING' },
        meta: { requestId: 'req_import', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )
    const file = new File(['{"title":"NodeNotReady"}\n'], 'knowledge.jsonl', {
      type: 'application/jsonl',
    })

    await expect(
      createKnowledgeImport({
        file,
        importType: 'JSONL',
        datasetVersion: '2026.07',
        duplicatePolicy: 'SKIP',
        dryRun: true,
      }),
    ).resolves.toEqual({ taskId: 'task_1', status: 'PENDING' })

    const options = fetchMock.mock.calls[0]![1] as RequestInit
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    const body = options.body as FormData
    expect(body.get('file')).toBe(file)
    expect(body.get('importType')).toBe('JSONL')
    expect(body.get('datasetVersion')).toBe('2026.07')
    expect(body.get('duplicatePolicy')).toBe('SKIP')
    expect(body.get('dryRun')).toBe('true')
    expect(options.headers).toEqual(
      expect.objectContaining({ 'Idempotency-Key': expect.stringMatching(/^knowledge_import_/) }),
    )
    expect(options.headers).not.toEqual(
      expect.objectContaining({ 'Content-Type': expect.anything() }),
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
