import { api } from '@/api/client'

export type SandboxRunStatus =
  | 'PENDING'
  | 'DISPATCHING'
  | 'RUNNING'
  | 'COLLECTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED'

export type SandboxCleanupStatus = 'NOT_REQUIRED' | 'PENDING' | 'SUCCEEDED' | 'FAILED'

export interface SandboxRun {
  id: string
  executionId: string | null
  alarmId: string | null
  mode: string
  toolId: string
  toolVersion: string
  status: SandboxRunStatus
  cleanupStatus: SandboxCleanupStatus
  stage: string | null
  progress: number
  riskLevel: string
  approvalRequired: boolean
  errorCode: string | null
  errorSummary: string | null
  version: number
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface SandboxArtifact {
  id: string
  type: 'INPUT' | 'OUTPUT' | 'LOG' | 'REPORT'
  contentType: string
  sizeBytes: number
  sha256: string
  classification: string
  retentionUntil: string
  createdAt: string
}

export interface SandboxArtifactDownload {
  url: string
  expiresAt: string
}

export interface SandboxRunListParams {
  mode?: string
  status?: SandboxRunStatus | ''
  executionId?: string
  alarmId?: string
}

export function listSandboxRuns(params: SandboxRunListParams = {}): Promise<SandboxRun[]> {
  return api.get<SandboxRun[]>('/api/v1/sandbox-runs', { query: compact(params) })
}

export function getSandboxRun(runId: string): Promise<SandboxRun> {
  return api.get<SandboxRun>(`/api/v1/sandbox-runs/${encodeURIComponent(runId)}`)
}

export function listSandboxArtifacts(runId: string): Promise<SandboxArtifact[]> {
  return api.get<SandboxArtifact[]>(`/api/v1/sandbox-runs/${encodeURIComponent(runId)}/artifacts`)
}

export function cancelSandboxRun(runId: string, version: number): Promise<SandboxRun> {
  return api.post<SandboxRun>(
    `/api/v1/sandbox-runs/${encodeURIComponent(runId)}/cancel`,
    undefined,
    {
      headers: { 'If-Match': String(version) },
    },
  )
}

export function requestArtifactDownload(
  runId: string,
  artifactId: string,
): Promise<SandboxArtifactDownload> {
  return api.get<SandboxArtifactDownload>(
    `/api/v1/sandbox-runs/${encodeURIComponent(runId)}/artifacts/${encodeURIComponent(artifactId)}/download`,
  )
}

function compact(params: SandboxRunListParams): Record<string, string> {
  return Object.fromEntries(
    Object.entries(params).filter((entry): entry is [string, string] => Boolean(entry[1])),
  )
}
