import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  decideApproval,
  getApproval,
  listApprovals,
  type ApprovalDecision,
  type ApprovalListParams,
} from './api'

export const approvalKeys = {
  all: ['approvals'] as const,
  list: (params: ApprovalListParams) => ['approvals', 'list', params] as const,
  detail: (approvalId: string) => ['approvals', 'detail', approvalId] as const,
}

export function useApprovalList(params: ApprovalListParams) {
  return useQuery({
    queryKey: approvalKeys.list(params),
    queryFn: () => listApprovals(params),
    staleTime: 10_000,
  })
}

export function useApproval(approvalId: string | undefined) {
  return useQuery({
    queryKey: approvalKeys.detail(approvalId ?? ''),
    queryFn: () => getApproval(approvalId as string),
    enabled: Boolean(approvalId),
    staleTime: 5_000,
  })
}

export function useDecideApproval(approvalId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: { version: number; decision: ApprovalDecision; comment: string }) =>
      decideApproval(
        approvalId as string,
        variables.version,
        variables.decision,
        variables.comment,
        `approval_${crypto.randomUUID()}`,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: approvalKeys.detail(approvalId ?? ''),
      })
      void queryClient.invalidateQueries({ queryKey: ['approvals', 'list'] })
    },
  })
}
