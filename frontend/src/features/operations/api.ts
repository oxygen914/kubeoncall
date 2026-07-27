import { api } from '@/api/client'

export interface PolicyVersion {
  version: string
  previousVersion: string | null
  loadedAt: string
  policyCount: number
}

export interface AlarmPolicy {
  id: string
  name: string
  category?: string | null
  metricName?: string | null
  resourceType?: string | null
  severity?: string | null
  promql?: string | null
  window?: string | null
  runbookId?: string | null
  owner?: string | null
  version?: string | null
}

export interface PolicyCatalog {
  activeVersion: string
  policies: AlarmPolicy[]
  versions: PolicyVersion[]
}

export interface MaintenanceWindow {
  id: string
  startsAt: string
  endsAt: string
  matchers: Record<string, string>
  reason: string
  createdBy: string
  approvedBy: string
  approvalReference: string
  createdAt: string
}

export interface MaintenanceWindowInput {
  startsAt: string
  endsAt: string
  matchers: Record<string, string>
  reason: string
  approvedBy: string
  approvalReference: string
}

export interface SuppressionRule {
  id: string
  source: { resourceTypes: string[]; alertNamePatterns: string[] }
  target: { resourceTypes: string[]; alertNamePatterns: string[] }
  correlateBy: string[]
  ttlSeconds: number
  reason: string
}

export interface SuppressionCatalog {
  activeVersion: string
  rules: SuppressionRule[]
}

export interface PolicyEvaluation {
  alarmId: string
  fingerprint: string
  alertName: string
  matched: boolean
  policyId: string | null
  policyVersion: string | null
  severity: string | null
  workflowTemplate: string | null
  runbookId: string | null
  reason: string | null
}

export interface PolicyReplay {
  policyVersion: string
  total: number
  matched: number
  unmatched: number
  results: PolicyEvaluation[]
}

export const getPolicies = () => api.get<PolicyCatalog>('/api/v1/operations/policies')
export const reloadPolicies = () => api.post('/api/v1/operations/policies/reload')
export const rollbackPolicy = (version: string) =>
  api.post('/api/v1/operations/policies/rollback', { version })
export const dryRunPolicy = (alarm: Record<string, unknown>) =>
  api.post<PolicyEvaluation>('/api/v1/operations/policies/dry-run', alarm)
export const replayPolicies = (alarms: Record<string, unknown>[]) =>
  api.post<PolicyReplay>('/api/v1/operations/policies/replay', { alarms })
export const compilePrometheusRules = () =>
  api.post<Record<string, unknown>>('/api/v1/operations/policies/prometheus-rules/dry-run')

export const getMaintenanceWindows = () =>
  api.get<MaintenanceWindow[]>('/api/v1/operations/maintenance-windows')
export const createMaintenanceWindow = (input: MaintenanceWindowInput) =>
  api.post<MaintenanceWindow>('/api/v1/operations/maintenance-windows', input)
export const revokeMaintenanceWindow = (id: string) =>
  api.delete<{ id: string; revoked: boolean }>(
    `/api/v1/operations/maintenance-windows/${encodeURIComponent(id)}`,
  )

export const getSuppressionRules = () =>
  api.get<SuppressionCatalog>('/api/v1/operations/suppression-rules')
export const reloadSuppressionRules = () =>
  api.post('/api/v1/operations/suppression-rules/reload')
