import { api, type Page } from '@/api/client'
import type { TaskAccepted } from '@/features/tasks/api'

export type KnowledgeDocumentStatus = 'ACTIVE' | 'DELETED'
export type KnowledgeImportType = 'DOCUMENT' | 'JSONL' | 'RUNBOOK'
export type DuplicatePolicy = 'SKIP' | 'REPLACE' | 'FAIL'

export interface KnowledgeDocumentListItem {
  id: string
  externalDocumentId: string | null
  title: string
  sourceType: string
  sourceUri: string | null
  datasetVersion: string | null
  status: KnowledgeDocumentStatus
  currentVersionId: string | null
  version: number
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface KnowledgeDocumentDetail extends KnowledgeDocumentListItem {
  metadata: Record<string, unknown>
  deleteReason: string | null
}

export interface KnowledgeDocumentPage {
  data: KnowledgeDocumentListItem[]
  page: Page
}

export interface KnowledgeDocumentListParams {
  page?: number
  size?: number
  status?: KnowledgeDocumentStatus | ''
  sourceType?: string
  query?: string
  [key: string]: string | number | undefined | null
}

export interface KnowledgeImportInput {
  file: File
  importType: KnowledgeImportType
  datasetVersion?: string
  duplicatePolicy: DuplicatePolicy
  dryRun: boolean
  metadata?: Record<string, unknown>
}

export interface KnowledgeImportAccepted extends TaskAccepted {
  importId?: string
}

export type KnowledgeImportStatus =
  'PENDING' | 'RUNNING' | 'PARTIAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface KnowledgeImport {
  id: string
  taskId: string
  importType: KnowledgeImportType
  duplicatePolicy: DuplicatePolicy
  dryRun: boolean
  status: KnowledgeImportStatus
  sourceSizeBytes: number
  datasetVersion: string | null
  totalCount: number | null
  processedCount: number
  succeededCount: number
  failedCount: number
  skippedCount: number
  errorCode: string | null
  errorSummary: string | null
  startedAt: string | null
  finishedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeImportPage {
  data: KnowledgeImport[]
  page: Page
}

export function listKnowledgeDocuments(
  params: KnowledgeDocumentListParams = {},
): Promise<KnowledgeDocumentPage> {
  return api.list<KnowledgeDocumentListItem>('/api/v1/knowledge/documents', {
    query: compactParams(params),
  })
}

export function getKnowledgeDocument(documentId: string): Promise<KnowledgeDocumentDetail> {
  return api.get<KnowledgeDocumentDetail>(
    `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}`,
  )
}

export function createKnowledgeImport(
  input: KnowledgeImportInput,
): Promise<KnowledgeImportAccepted> {
  const form = new FormData()
  form.append('file', input.file)
  form.append('importType', input.importType)
  form.append('duplicatePolicy', input.duplicatePolicy)
  form.append('dryRun', String(input.dryRun))
  if (input.datasetVersion) form.append('datasetVersion', input.datasetVersion)
  if (input.metadata) form.append('metadata', JSON.stringify(input.metadata))
  return api.post<KnowledgeImportAccepted>('/api/v1/knowledge/imports', form, {
    idempotencyKey: `knowledge_import_${crypto.randomUUID()}`,
  })
}

export function listKnowledgeImports(
  params: {
    page?: number
    size?: number
    status?: KnowledgeImportStatus | ''
  } = {},
): Promise<KnowledgeImportPage> {
  return api.list<KnowledgeImport>('/api/v1/knowledge/imports', {
    query: compactParams(params),
  })
}

export function getKnowledgeImport(importId: string): Promise<KnowledgeImport> {
  return api.get<KnowledgeImport>(`/api/v1/knowledge/imports/${encodeURIComponent(importId)}`)
}

export function deleteKnowledgeDocument(
  documentId: string,
  version: number,
  reason: string,
): Promise<KnowledgeDocumentDetail> {
  return api.delete<KnowledgeDocumentDetail>(
    `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}`,
    { reason },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey: `knowledge_delete_${crypto.randomUUID()}`,
    },
  )
}

export function restoreKnowledgeDocument(
  documentId: string,
  version: number,
): Promise<KnowledgeDocumentDetail> {
  return api.post<KnowledgeDocumentDetail>(
    `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/restore`,
    undefined,
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey: `knowledge_restore_${crypto.randomUUID()}`,
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
