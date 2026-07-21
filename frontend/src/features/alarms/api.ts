import { api, type Page } from '@/api/client'

/**
 * Alarms API module. Matches the /api/v1/alarms list/detail/timeline contract:
 *
 *   list:     { data: AlarmListItem[], page: Page, meta }
 *   detail:   { data: AlarmDetail, meta }
 *   timeline: { data: AlarmTimelineItem[], meta }
 */

export interface AlarmResource {
  type: string
  name: string
  cluster: string | null
  namespace: string | null
  service: string | null
}

export interface AlarmAcknowledgement {
  acknowledged: boolean
  by: string | null
  at: string | null
}

export interface AlarmListItem {
  id: string
  fingerprint: string
  alertName: string
  severity: string
  status: string
  resource: AlarmResource
  firstSeen: string
  lastSeen: string
  occurrenceCount: number
  acknowledgement: AlarmAcknowledgement
  latestExecution: { id: string; status: string } | null
  version: number
}

export interface AlarmDetail extends AlarmListItem {
  labels: Record<string, string>
  annotations: Record<string, string>
  metricName: string | null
  currentValue: number | null
  threshold: number | null
  unit: string | null
  policyPublicId: string | null
  resolvedAt: string | null
}

export interface AlarmTimelineItem {
  id: string
  type: string
  occurredAt: string
  actor: { type: string; id: string | null; displayName: string | null }
  summary: string | null
  details: Record<string, unknown>
  requestId: string
}

export interface AlarmListPage {
  data: AlarmListItem[]
  page: Page
}

export interface AlarmListParams {
  page?: number
  size?: number
  sort?: string
  status?: string
  severity?: string
  cluster?: string
  namespace?: string
  service?: string
  q?: string
  [key: string]: string | number | undefined | null
}

export function listAlarms(params: AlarmListParams = {}): Promise<AlarmListPage> {
  return api.list<AlarmListItem>('/api/v1/alarms', { query: filterUndefined(params) })
}

export function getAlarm(alarmId: string): Promise<AlarmDetail> {
  return api.get<AlarmDetail>(`/api/v1/alarms/${encodeURIComponent(alarmId)}`)
}

export function getAlarmTimeline(
  alarmId: string,
  params: { limit?: number; after?: string } = {},
): Promise<AlarmTimelineItem[]> {
  return api.get<AlarmTimelineItem[]>(`/api/v1/alarms/${encodeURIComponent(alarmId)}/timeline`, {
    query: filterUndefined(params),
  })
}

export interface AcknowledgeResult {
  alarmId: string
  status: string
  acknowledged: boolean
  version: number
}

export interface RecoveryConfirmationResult {
  alarmId: string
  status: string
  recovered: boolean
  version: number
}

export interface SilenceApprovalResult {
  alarmId: string
  status: string
  silenceId: string
  expiresAt: string
  version: number
}

/**
 * POST /api/v1/alarms/{alarmId}/acknowledgements. Carries the optimistic-lock version as If-Match
 * and a generated Idempotency-Key so a double-submit never produces a duplicate acknowledgement.
 * The CSRF token is injected by the shared client for this non-GET request.
 */
export function acknowledgeAlarm(
  alarmId: string,
  version: number,
  reason: string,
  idempotencyKey: string,
): Promise<AcknowledgeResult> {
  return api.post<AcknowledgeResult>(
    `/api/v1/alarms/${encodeURIComponent(alarmId)}/acknowledgements`,
    reason ? { reason } : {},
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey,
    },
  )
}

/**
 * Confirm a RECOVERY_PENDING alarm after an operator has verified its health check.
 */
export function confirmAlarmRecovery(
  alarmId: string,
  version: number,
  note: string,
  idempotencyKey: string,
): Promise<RecoveryConfirmationResult> {
  return api.post<RecoveryConfirmationResult>(
    `/api/v1/alarms/${encodeURIComponent(alarmId)}/recovery-confirmations`,
    { healthCheckPassed: true, note },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey,
    },
  )
}

/**
 * Approve a bounded silence for a firing or acknowledged alarm.
 */
export function approveAlarmSilence(
  alarmId: string,
  version: number,
  reason: string,
  expiresAt: string,
  idempotencyKey: string,
): Promise<SilenceApprovalResult> {
  return api.post<SilenceApprovalResult>(
    `/api/v1/alarms/${encodeURIComponent(alarmId)}/silence-approvals`,
    { reason, expiresAt },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey,
    },
  )
}

function filterUndefined(
  params: Record<string, string | number | undefined | null>,
): Record<string, string | number> {
  const result: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}
