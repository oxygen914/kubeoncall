import { api, type Page } from '@/api/client'

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED' | 'CONFLICT'

export interface AuditActor {
  type: string
  id: string | null
  displayName: string | null
}

export interface AuditResource {
  type: string
  id: string | null
}

export interface AuditEventListItem {
  id: string
  actor: AuditActor
  action: string
  resource: AuditResource
  result: AuditResult | string
  reason: string | null
  requestId: string
  traceId: string | null
  occurredAt: string
}

export interface AuditEventDetail extends AuditEventListItem {
  before: Record<string, unknown> | null
  after: Record<string, unknown> | null
  sourceIp: string | null
  browser: string | null
}

export interface AuditEventPage {
  data: AuditEventListItem[]
  page: Page
}

export interface AuditEventFilters {
  actor?: string
  action?: string
  resourceType?: string
  resourceId?: string
  result?: string
  requestId?: string
  from?: string
  to?: string
  page?: number
  size?: number
  [key: string]: string | number | undefined | null
}

export function listAuditEvents(filters: AuditEventFilters = {}): Promise<AuditEventPage> {
  return api.list<AuditEventListItem>('/api/v1/audit-events', {
    query: compactParams(filters),
  })
}

export function getAuditEvent(auditId: string): Promise<AuditEventDetail> {
  return api.get<AuditEventDetail>(`/api/v1/audit-events/${encodeURIComponent(auditId)}`)
}

function compactParams(
  params: Record<string, string | number | undefined | null>,
): Record<string, string | number> {
  const result: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}
