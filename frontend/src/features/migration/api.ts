import { api } from '@/api/client'

/** Redis inventory report from /api/v1/migration/redis-inventory. */
export interface RedisInventoryReport {
  scanned: number
  byCategory: Record<string, number>
  byPrefix: Record<string, number>
  note: string
  activeBusinessFactPrefixes: string[]
}

/** Durable migration task accepted by /api/v1/migration/backfill/tasks. */
export interface MigrationTaskAccepted {
  taskId: string
  status: 'PENDING'
  domain: MigrationBackfillDomain
  dryRun: boolean
}

export type MigrationBackfillDomain =
  | 'ACTIVE_ALARM'
  | 'APPROVAL'
  | 'SKILL_STATE'
  | 'ALARM_ACKNOWLEDGEMENT'
  | 'ALARM_SILENCE'
  | 'ALARM_RECOVERY'
  | 'EXECUTION_AUDIT'

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
  resolutionStatus: 'OPEN' | 'EXPLAINED' | 'RESOLVED' | 'ACCEPTED_RISK'
  resolutionNote: string | null
  resolvedBy: string | null
  resolvedAt: string | null
  occurredAt: string | null
}

export interface MigrationItem {
  batchId: string
  sourceKey: string
  domain: string
  targetPublicId: string | null
  result: string
  reason: string | null
  occurredAt: string | null
}

export interface MigrationDiffStatistics {
  domain: string | null
  windowMinutes: number
  comparisonCount: number
  mismatchCount: number
  mismatchRatePercent: number
  thresholdPercent: number
  withinThreshold: boolean
  openDiffCount: number
  available: boolean
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

export function getMigrationDiffs(params: { page?: number; size?: number; domain?: string; resolutionStatus?: string } = {}): Promise<ListPage<MigrationDiff>> {
  return api.list<MigrationDiff>('/api/v1/migration/diffs', { query: filterUndefined(params) })
}

export function getMigrationItems(params: { page?: number; size?: number; domain?: string; result?: string; batchId?: string } = {}): Promise<ListPage<MigrationItem>> {
  return api.list<MigrationItem>('/api/v1/migration/items', { query: filterUndefined(params) })
}

export function getMigrationDiffStatistics(params: { domain?: string; windowMinutes?: number } = {}): Promise<MigrationDiffStatistics> {
  return api.get<MigrationDiffStatistics>('/api/v1/migration/diff-statistics', { query: filterUndefined(params) })
}

export function resolveMigrationDiff(publicId: string, status: Exclude<MigrationDiff['resolutionStatus'], 'OPEN'>, note?: string): Promise<{ publicId: string; resolutionStatus: string }> {
  return api.post(`/api/v1/migration/diffs/${encodeURIComponent(publicId)}/resolution`, undefined, {
    query: filterUndefined({ status, note }),
  })
}

/** Queue an ActiveAlarm backfill. Pass dryRun=true for a no-write probe, false to force apply. */
export function backfillActiveAlarm(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('ACTIVE_ALARM', dryRun)
}

/** Trigger an Approval backfill (Redis approval-request:* → MySQL koc_approval_request). */
export function backfillApproval(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('APPROVAL', dryRun)
}

/** Trigger a Skill-state backfill (Redis skill:disabled set → MySQL koc_skill_state). */
export function backfillSkillState(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('SKILL_STATE', dryRun)
}

/** Migrate legacy Redis acknowledgement records into the MySQL command history. */
export function backfillAlarmAcknowledgements(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('ALARM_ACKNOWLEDGEMENT', dryRun)
}

/** Migrate legacy Redis silence approvals into MySQL. */
export function backfillAlarmSilences(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('ALARM_SILENCE', dryRun)
}

/** Migrate confirmed legacy Redis recovery records into MySQL history. */
export function backfillAlarmRecoveries(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('ALARM_RECOVERY', dryRun)
}

/** Migrate append-only legacy execution-audit records into MySQL operation audit. */
export function backfillExecutionAudit(dryRun = true): Promise<MigrationTaskAccepted> {
  return backfill('EXECUTION_AUDIT', dryRun)
}

function backfill(domain: MigrationBackfillDomain, dryRun: boolean): Promise<MigrationTaskAccepted> {
  return api.post<MigrationTaskAccepted>('/api/v1/migration/backfill/tasks', undefined, {
    query: dryRun
      ? { domain, 'dry-run': 1 }
      : { domain, 'dry-run': 0, 'confirm-apply': 1 },
  })
}

function filterUndefined(params: Record<string, string | number | undefined | null>): Record<string, string | number> {
  const result: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}
