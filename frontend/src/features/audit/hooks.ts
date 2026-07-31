import { useQuery } from '@tanstack/react-query'
import { getAuditEvent, listAuditEvents, type AuditEventFilters } from './api'

export const auditKeys = {
  all: ['audit-events'] as const,
  list: (filters: AuditEventFilters) => ['audit-events', 'list', filters] as const,
  detail: (auditId: string) => ['audit-events', 'detail', auditId] as const,
}

export function useAuditEvents(filters: AuditEventFilters) {
  return useQuery({
    queryKey: auditKeys.list(filters),
    queryFn: () => listAuditEvents(filters),
    staleTime: 10_000,
  })
}

export function useAuditEvent(auditId: string | undefined) {
  return useQuery({
    queryKey: auditKeys.detail(auditId ?? ''),
    queryFn: () => getAuditEvent(auditId as string),
    enabled: Boolean(auditId),
  })
}
