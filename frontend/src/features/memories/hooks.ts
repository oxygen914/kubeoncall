import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createMemoryExtraction,
  deleteMemory,
  getMemory,
  listMemories,
  listMemoryExtractions,
  restoreMemory,
  type MemoryExtractionInput,
  type MemoryListParams,
} from './api'

export const memoryKeys = {
  all: ['memories'] as const,
  list: (params: MemoryListParams) => ['memories', 'list', params] as const,
  detail: (memoryId: string) => ['memories', 'detail', memoryId] as const,
  extractions: () => ['memories', 'extractions'] as const,
}

export function useMemories(params: MemoryListParams) {
  return useQuery({
    queryKey: memoryKeys.list(params),
    queryFn: () => listMemories(params),
    staleTime: 10_000,
  })
}

export function useMemory(memoryId: string | undefined) {
  return useQuery({
    queryKey: memoryKeys.detail(memoryId ?? ''),
    queryFn: () => getMemory(memoryId as string),
    enabled: Boolean(memoryId),
  })
}

export function useMemoryExtractions() {
  return useQuery({
    queryKey: [...memoryKeys.extractions(), 'recent'] as const,
    queryFn: () => listMemoryExtractions({ page: 1, size: 5 }),
    staleTime: 5_000,
  })
}

export function useCreateMemoryExtraction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: MemoryExtractionInput) => createMemoryExtraction(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: memoryKeys.all })
    },
  })
}

export function useRestoreMemory(memoryId: string) {
  return useMemoryLifecycleMutation(memoryId, (version) => restoreMemory(memoryId, version))
}

export function useDeleteMemory(memoryId: string) {
  return useMemoryLifecycleMutation(memoryId, (version, reason) =>
    deleteMemory(memoryId, version, reason ?? ''),
  )
}

function useMemoryLifecycleMutation(
  memoryId: string,
  mutation: (version: number, reason?: string) => ReturnType<typeof getMemory>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ version, reason }: { version: number; reason?: string }) =>
      mutation(version, reason),
    onSuccess: (memory) => {
      queryClient.setQueryData(memoryKeys.detail(memoryId), memory)
      void queryClient.invalidateQueries({ queryKey: memoryKeys.all })
    },
  })
}
