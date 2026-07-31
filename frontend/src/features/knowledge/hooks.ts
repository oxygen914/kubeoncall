import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createKnowledgeImport,
  deleteKnowledgeDocument,
  getKnowledgeDocument,
  listKnowledgeDocuments,
  listKnowledgeImports,
  restoreKnowledgeDocument,
  type KnowledgeDocumentListParams,
  type KnowledgeImportInput,
} from './api'

export const knowledgeKeys = {
  all: ['knowledge'] as const,
  documents: () => ['knowledge', 'documents'] as const,
  imports: () => ['knowledge', 'imports'] as const,
  list: (params: KnowledgeDocumentListParams) =>
    ['knowledge', 'documents', 'list', params] as const,
  detail: (documentId: string) => ['knowledge', 'documents', 'detail', documentId] as const,
}

export function useKnowledgeDocuments(params: KnowledgeDocumentListParams) {
  return useQuery({
    queryKey: knowledgeKeys.list(params),
    queryFn: () => listKnowledgeDocuments(params),
    staleTime: 10_000,
  })
}

export function useKnowledgeDocument(documentId: string | undefined) {
  return useQuery({
    queryKey: knowledgeKeys.detail(documentId ?? ''),
    queryFn: () => getKnowledgeDocument(documentId as string),
    enabled: Boolean(documentId),
  })
}

export function useKnowledgeImports() {
  return useQuery({
    queryKey: [...knowledgeKeys.imports(), 'recent'] as const,
    queryFn: () => listKnowledgeImports({ page: 1, size: 5 }),
    staleTime: 5_000,
  })
}

export function useCreateKnowledgeImport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: KnowledgeImportInput) => createKnowledgeImport(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: knowledgeKeys.documents() })
      void queryClient.invalidateQueries({ queryKey: knowledgeKeys.imports() })
    },
  })
}

export function useDeleteKnowledgeDocument(documentId: string) {
  return useKnowledgeLifecycleMutation(documentId, (version, reason) =>
    deleteKnowledgeDocument(documentId, version, reason ?? ''),
  )
}

export function useRestoreKnowledgeDocument(documentId: string) {
  return useKnowledgeLifecycleMutation(documentId, (version) =>
    restoreKnowledgeDocument(documentId, version),
  )
}

function useKnowledgeLifecycleMutation(
  documentId: string,
  mutation: (version: number, reason?: string) => ReturnType<typeof getKnowledgeDocument>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ version, reason }: { version: number; reason?: string }) =>
      mutation(version, reason),
    onSuccess: (document) => {
      queryClient.setQueryData(knowledgeKeys.detail(documentId), document)
      void queryClient.invalidateQueries({ queryKey: knowledgeKeys.documents() })
    },
  })
}
