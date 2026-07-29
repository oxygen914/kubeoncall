import { useQuery } from '@tanstack/react-query'
import { getExecution, listExecutionNodes, listExecutions, type ExecutionListParams } from './api'

export const executionKeys = {
  all: ['executions'] as const,
  list: (params: ExecutionListParams) => ['executions', 'list', params] as const,
  detail: (executionId: string) => ['executions', 'detail', executionId] as const,
  nodes: (executionId: string) => ['executions', 'nodes', executionId] as const,
}

export function useExecutionList(params: ExecutionListParams) {
  return useQuery({
    queryKey: executionKeys.list(params),
    queryFn: () => listExecutions(params),
    staleTime: 10_000,
  })
}

export function useExecution(executionId: string | undefined) {
  return useQuery({
    queryKey: executionKeys.detail(executionId ?? ''),
    queryFn: () => getExecution(executionId as string),
    enabled: Boolean(executionId),
    staleTime: 5_000,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED'].includes(status)
        ? false
        : 2_000
    },
  })
}

export function useExecutionNodes(executionId: string | undefined) {
  return useQuery({
    queryKey: executionKeys.nodes(executionId ?? ''),
    queryFn: () => listExecutionNodes(executionId as string),
    enabled: Boolean(executionId),
    staleTime: 5_000,
  })
}
