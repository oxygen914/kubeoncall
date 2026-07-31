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
  errorSummary?: string | null
  requestId?: string
  traceId?: string | null
  sessionId?: string | null
  taskId?: string | null
  taskStatus?: string | null
  taskStage?: string | null
  taskProgress?: number | null
  answer?: string | null
  details?: Record<string, unknown>
  evidence?: EvidenceItem[]
  conclusions?: AiConclusion[]
  operationClosure?: OperationClosure | null
  nodes?: ExecutionNode[]
}

export interface OperationClosure {
  id: string
  operationId: string
  phase: string
  executorKind: string
  action: string
  target: string | null
  details: Record<string, unknown>
  errorSummary: string | null
  startedAt: string
  finishedAt: string | null
  escalation: {
    id: string
    status: string
    severity: string
    summary: string
    details: Record<string, unknown>
    errorSummary: string | null
    updatedAt: string
  } | null
}

export interface EvidenceItem {
  evidenceId: string
  type: string
  source: string
  cluster: string
  namespace: string
  resource: {
    kind: string
    name: string
    uid: string
  }
  observedAt: string
  window?: {
    start?: string | null
    end?: string | null
  }
  summary: string
  snippet: string
  freshnessSeconds: number
  redacted: boolean
  truncated: boolean
  collectionStatus: string
  errorType?: string | null
}

export interface ConfidenceAssessment {
  score: number
  label: 'HIGH' | 'MEDIUM' | 'LOW' | string
  basis: Record<string, number>
}

export interface SopEvidenceReference {
  sopId: string
  version: string
  source: string
  section: string
}

export interface AiConclusion {
  conclusionId: string
  claim: string
  severity: string
  status: string
  evidenceRefs: string[]
  sopRefs: SopEvidenceReference[]
  confidence: ConfidenceAssessment
  planner: {
    mode?: string
    model?: string
    provider?: string
    degraded?: boolean
    degradedReason?: string
  }
  recommendedAction?: {
    type: string
    requiresApproval: boolean
    parameters: Record<string, unknown>
  }
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
