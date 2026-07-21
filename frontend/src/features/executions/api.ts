import { api, type Page } from '@/api/client'

export type ExecutionStatus =
  'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'REJECTED' | 'CANCELLED'

export interface ExecutionListItem {
  id: string
  type: string
  status: ExecutionStatus
  summary: string
  triggerId: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  version: number
}

export interface ExecutionDetail extends ExecutionListItem {
  riskLevel?: string
  currentNode?: string | null
  resultSummary?: string | null
  errorCode?: string | null
  requestId?: string
  traceId?: string | null
}

export interface ExecutionNode {
  id: string
  nodeName: string
  status: string
  attempt: number
  startedAt: string | null
  finishedAt: string | null
  outputSummary: string | null
  errorCode: string | null
}

export interface ExecutionListPage {
  data: ExecutionListItem[]
  page: Page
}

export interface ExecutionListParams {
  page?: number
  size?: number
  status?: ExecutionStatus | ''
  alarmId?: string
  [key: string]: string | number | undefined | null
}

export function listExecutions(params: ExecutionListParams = {}): Promise<ExecutionListPage> {
  return api.list<ExecutionListItem>('/api/v1/executions', {
    query: compactParams(params),
  })
}

export function getExecution(executionId: string): Promise<ExecutionDetail> {
  return api.get<ExecutionDetail>(`/api/v1/executions/${encodeURIComponent(executionId)}`)
}

export function listExecutionNodes(executionId: string): Promise<ExecutionNode[]> {
  return api.get<ExecutionNode[]>(`/api/v1/executions/${encodeURIComponent(executionId)}/nodes`)
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
