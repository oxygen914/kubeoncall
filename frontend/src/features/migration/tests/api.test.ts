import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { backfillActiveAlarm, backfillExecutionAudit, resolveMigrationDiff } from '../api'

function jsonResponse(data: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: { get: () => 'application/json' },
    json: async () => ({
      data,
      meta: { requestId: 'req_migration', timestamp: '2026-07-22T00:00:00Z' },
    }),
  } as unknown as Response
}

describe('migration backfill API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockResolvedValue(
      jsonResponse({
        taskId: 'tsk_migration',
        status: 'PENDING',
        domain: 'ACTIVE_ALARM',
        dryRun: false,
      }),
    )
  })

  afterEach(() => vi.unstubAllGlobals())

  it('sends the server-side confirmation flag only for Apply', async () => {
    await backfillActiveAlarm(false)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/migration/backfill/tasks?domain=ACTIVE_ALARM&dry-run=0&confirm-apply=1',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('keeps Dry-run write-free and uses the durable execution-audit task domain', async () => {
    await backfillExecutionAudit(true)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/migration/backfill/tasks?domain=EXECUTION_AUDIT&dry-run=1',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('submits an explicit terminal disposition for an open diff', async () => {
    await resolveMigrationDiff('mdiff_1', 'RESOLVED', 'Redis key expired as designed')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/migration/diffs/mdiff_1/resolution?status=RESOLVED&note=Redis+key+expired+as+designed',
      expect.objectContaining({ method: 'POST' }),
    )
  })
})
