import { api, type Page } from '@/api/client'
import type { TaskAccepted } from '@/features/tasks/api'

export type MemoryStatus = 'ACTIVE' | 'DELETED'

export interface MemoryListItem {
  id: string
  memoryType: string
  status: MemoryStatus
  sourceSessionId: string | null
  sourceExecutionId: string | null
  sourceAlarmId: string | null
  qualityScore: number | null
  extractedAt: string | null
  expiresAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface MemoryDetail extends MemoryListItem {
  evidence: Record<string, unknown>
  contentChecksum: string
  esIndex: string | null
  esDocumentId: string | null
  deleteReason: string | null
}

export interface MemoryPage {
  data: MemoryListItem[]
  page: Page
}

export interface MemoryListParams {
  page?: number
  size?: number
  status?: MemoryStatus | ''
  memoryType?: string
  sourcePublicId?: string
  [key: string]: string | number | undefined | null
}

export interface MemoryExtractionInput {
  sourceType: 'SESSION' | 'EXECUTION' | 'ALARM' | 'MANUAL'
  sourcePublicId: string
  dedupeKey: string
  memoryType:
    | 'DEVICE_HISTORY'
    | 'SERVICE_FACT'
    | 'KNOWN_PITFALL'
    | 'INCIDENT_SUMMARY'
    | 'USER_PREFERENCE'
    | 'USER_NOTE'
  scope: 'GLOBAL' | 'SERVICE' | 'RESOURCE' | 'FINGERPRINT' | 'SESSION'
  subject: string
  content: string
  service?: string
  resource?: string
  fingerprint?: string
}

export interface MemoryExtractionAccepted extends TaskAccepted {
  extractionId?: string
}

export type MemoryExtractionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface MemoryExtraction {
  id: string
  taskId: string
  sourceType: string
  sourcePublicId: string
  status: MemoryExtractionStatus
  extractorModel: string | null
  extractorVersion: string | null
  evidenceCount: number
  memoryCount: number
  qualitySummary: Record<string, unknown>
  errorCode: string | null
  errorSummary: string | null
  startedAt: string | null
  finishedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface MemoryExtractionPage {
  data: MemoryExtraction[]
  page: Page
}

export function listMemories(params: MemoryListParams = {}): Promise<MemoryPage> {
  return api.list<MemoryListItem>('/api/v1/memories', {
    query: compactParams(params),
  })
}

export function getMemory(memoryId: string): Promise<MemoryDetail> {
  return api.get<MemoryDetail>(`/api/v1/memories/${encodeURIComponent(memoryId)}`)
}

export function createMemoryExtraction(
  input: MemoryExtractionInput,
): Promise<MemoryExtractionAccepted> {
  return api.post<MemoryExtractionAccepted>('/api/v1/memories/extractions', input, {
    idempotencyKey: `memory_extract_${crypto.randomUUID()}`,
  })
}

export function listMemoryExtractions(
  params: {
    page?: number
    size?: number
    status?: MemoryExtractionStatus | ''
  } = {},
): Promise<MemoryExtractionPage> {
  return api.list<MemoryExtraction>('/api/v1/memories/extractions', {
    query: compactParams(params),
  })
}

export function getMemoryExtraction(extractionId: string): Promise<MemoryExtraction> {
  return api.get<MemoryExtraction>(
    `/api/v1/memories/extractions/${encodeURIComponent(extractionId)}`,
  )
}

export function restoreMemory(memoryId: string, version: number): Promise<MemoryDetail> {
  return api.post<MemoryDetail>(
    `/api/v1/memories/${encodeURIComponent(memoryId)}/restore`,
    undefined,
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey: `memory_restore_${crypto.randomUUID()}`,
    },
  )
}

export function deleteMemory(
  memoryId: string,
  version: number,
  reason: string,
): Promise<MemoryDetail> {
  return api.delete<MemoryDetail>(
    `/api/v1/memories/${encodeURIComponent(memoryId)}`,
    { reason },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey: `memory_delete_${crypto.randomUUID()}`,
    },
  )
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
