import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelSandboxRun,
  getSandboxRun,
  listSandboxArtifacts,
  listSandboxRuns,
  requestArtifactDownload,
  type SandboxRun,
  type SandboxRunListParams,
} from './api'

export const sandboxKeys = {
  all: ['sandbox-runs'] as const,
  list: (params: SandboxRunListParams) => ['sandbox-runs', 'list', params] as const,
  detail: (runId: string) => ['sandbox-runs', 'detail', runId] as const,
  artifacts: (runId: string) => ['sandbox-runs', 'artifacts', runId] as const,
}

export function useSandboxRunList(params: SandboxRunListParams) {
  return useQuery({
    queryKey: sandboxKeys.list(params),
    queryFn: () => listSandboxRuns(params),
    staleTime: 5_000,
    refetchInterval: (query) => pollingInterval(query.state.data),
  })
}

export function useSandboxRun(runId: string | undefined) {
  return useQuery({
    queryKey: sandboxKeys.detail(runId ?? ''),
    queryFn: () => getSandboxRun(runId as string),
    enabled: Boolean(runId),
    staleTime: 3_000,
    refetchInterval: (query) => (query.state.data && isTerminal(query.state.data) ? false : 3_000),
  })
}

export function useSandboxArtifacts(runId: string | undefined) {
  return useQuery({
    queryKey: sandboxKeys.artifacts(runId ?? ''),
    queryFn: () => listSandboxArtifacts(runId as string),
    enabled: Boolean(runId),
    staleTime: 5_000,
  })
}

export function useCancelSandboxRun(runId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (version: number) => cancelSandboxRun(runId as string, version),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: sandboxKeys.detail(runId ?? '') })
      void queryClient.invalidateQueries({ queryKey: ['sandbox-runs', 'list'] })
    },
  })
}

export function useArtifactDownload(runId: string | undefined) {
  return useMutation({
    mutationFn: (artifactId: string) => requestArtifactDownload(runId as string, artifactId),
  })
}

export function isTerminal(run: SandboxRun): boolean {
  return ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(run.status)
}

function pollingInterval(runs: SandboxRun[] | undefined): number | false {
  return runs?.some((run) => !isTerminal(run)) ? 5_000 : false
}
