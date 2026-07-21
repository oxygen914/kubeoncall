import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getTask } from '../api'

describe('tasks API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('loads a task using an encoded identifier', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: {
        get: (name: string) =>
          name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
      },
      json: async () => ({
        data: { id: 'task/1', status: 'RUNNING' },
        meta: { requestId: 'req_task', timestamp: '2026-07-20T12:00:00Z' },
      }),
    } as Response)

    await expect(getTask('task/1')).resolves.toMatchObject({
      id: 'task/1',
      status: 'RUNNING',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/tasks/task%2F1',
      expect.objectContaining({ method: 'GET', credentials: 'include' }),
    )
  })
})
