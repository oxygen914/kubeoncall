import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listSkills, setSkillEnabled } from '../api'

describe('skills API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('lists Skill governance state with filters', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: [{ id: 'skill_1', loadStatus: 'LOADED', enabled: true }],
        page: {
          number: 1,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        },
        meta: { requestId: 'req_skills', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await listSkills({ page: 1, size: 20, loadStatus: 'LOADED' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/skills?page=1&size=20&loadStatus=LOADED',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('patches enabled state with If-Match and idempotency', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        data: { id: 'skill_1', enabled: false, version: 4 },
        meta: { requestId: 'req_skill_state', timestamp: '2026-07-21T00:00:00Z' },
      }),
    )

    await setSkillEnabled('skill/1', 3, false)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/skills/skill%2F1/enabled',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ enabled: false }),
        headers: expect.objectContaining({
          'If-Match': '3',
          'Idempotency-Key': expect.stringMatching(/^skill_state_/),
        }),
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
