import { api } from '@/api/client'

export interface OverviewData {
  activeAlarms: number
  pendingApprovals: number
  runningExecutions: number
  failedExecutions: number
  severityCounts: Record<string, number>
  statusCounts: Record<string, number>
  executionStatusCounts: Record<string, number>
  failureReasons: Record<string, number>
  executionTrend: Record<string, number>
  executionStatusTrend?: Record<string, Record<string, number>>
  window: string
}

export function getOverview(window = '24h'): Promise<OverviewData> {
  return api.get<OverviewData>('/api/v1/overview', { query: { window } })
}
