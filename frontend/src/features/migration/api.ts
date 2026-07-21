import { api } from '@/api/client'

/** Redis inventory report from /api/v1/migration/redis-inventory. */
export interface RedisInventoryReport {
  scanned: number
  byCategory: Record<string, number>
  byPrefix: Record<string, number>
  note: string
  activeBusinessFactPrefixes: string[]
}

/** ActiveAlarm backfill result from /api/v1/migration/backfill/active-alarm. */
export interface BackfillResult {
  scanned: number
  migrated: number
  skipped: number
  failed: number
  checkpoint: string | null
  dryRun: boolean
  note: string
}

export interface MigrationStatus {
  alarmReadSource: string
  alarmWriteMode: string
  backfillDryRun: boolean
  legacyApiEnabled: boolean
  ledgerAvailable: boolean
}

export interface MigrationBatch {
  batchId: string
  domain: string
  mode: string
  status: string
  startedAt: string | null
  finishedAt: string | null
  scanned: number
  migrated: number
  skipped: number
  failed: number
  checkpoint: string | null
}

export interface MigrationDiff {
  publicId: string
  domain: string
  resourcePublicId: string | null
  diffType: string
  redisSummary: string | null
  mysqlSummary: string | null
  requestId: string | null
  occurredAt: string | null
}

export interface ListPage<T> {
  data: T[]
  page: { number: number; size: number; totalElements: number; totalPages: number; hasNext: boolean }
}

export function getRedisInventory(): Promise<RedisInventoryReport> {
  return api.get<RedisInventoryReport>('/api/v1/migration/redis-inventory')
}

export function getMigrationStatus(): Promise<MigrationStatus> {
  return api.get<MigrationStatus>('/api/v1/migration/status')
}

export function getMigrationBatches(params: { page?: number; size?: number; domain?: string } = {}): Promise<ListPage<MigrationBatch>> {
  return api.list<MigrationBatch>('/api/v1/migration/batches', { query: filterUndefined(params) })
}

export function getMigrationDiffs(params: { page?: number; size?: number; domain?: string } = {}): Promise<ListPage<MigrationDiff>> {
  return api.list<MigrationDiff>('/api/v1/migration/diffs', { query: filterUndefined(params) })
}

/** Trigger an ActiveAlarm backfill. Pass dryRun=true for a no-write probe, false to force apply. */
export function backfillActiveAlarm(dryRun = true): Promise<BackfillResult> {
  return api.post<BackfillResult>(
    '/api/v1/migration/backfill/active-alarm',
    undefined,
    { query: dryRun ? { 'dry-run': 1 } : { 'dry-run': 0 } },
  )
}

/** Trigger an Approval backfill (Redis approval-request:* → MySQL koc_approval_request). */
export function backfillApproval(dryRun = true): Promise<BackfillResult> {
  return api.post<BackfillResult>(
    '/api/v1/migration/backfill/approval',
    undefined,
    { query: dryRun ? { 'dry-run': 1 } : { 'dry-run': 0 } },
  )
}

/** Trigger a Skill-state backfill (Redis skill:disabled set → MySQL koc_skill_state). */
export function backfillSkillState(dryRun = true): Promise<BackfillResult> {
  return api.post<BackfillResult>(
    '/api/v1/migration/backfill/skill-state',
    undefined,
    { query: dryRun ? { 'dry-run': 1 } : { 'dry-run': 0 } },
  )
}

function filterUndefined(params: Record<string, string | number | undefined | null>): Record<string, string | number> {
  const result: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}
