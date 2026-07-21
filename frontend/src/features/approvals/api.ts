import { api, type Page } from '@/api/client'
import type { TaskAccepted } from '@/features/tasks/api'

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED'
export type ApprovalRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type ApprovalDecision = 'APPROVED' | 'REJECTED'

export interface ApprovalListItem {
  id: string
  executionId: string
  status: ApprovalStatus
  riskLevel: ApprovalRiskLevel
  summary: string
  createdAt: string
  expiresAt: string | null
  version: number
}

export interface ApprovalDetail extends ApprovalListItem {
  action?: string
  context?: Record<string, unknown>
  decision?: Record<string, unknown>
}

export interface ApprovalListPage {
  data: ApprovalListItem[]
  page: Page
}

export interface ApprovalListParams {
  page?: number
  size?: number
  status?: ApprovalStatus | ''
  risk?: ApprovalRiskLevel | ''
  [key: string]: string | number | undefined | null
}

export function listApprovals(params: ApprovalListParams = {}): Promise<ApprovalListPage> {
  return api.list<ApprovalListItem>('/api/v1/approvals', {
    query: compactParams(params),
  })
}

export function getApproval(approvalId: string): Promise<ApprovalDetail> {
  return api.get<ApprovalDetail>(`/api/v1/approvals/${encodeURIComponent(approvalId)}`)
}

export function decideApproval(
  approvalId: string,
  version: number,
  decision: ApprovalDecision,
  comment: string,
  idempotencyKey: string,
): Promise<TaskAccepted> {
  return api.post<TaskAccepted>(
    `/api/v1/approvals/${encodeURIComponent(approvalId)}/decisions`,
    { decision, comment },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey,
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
