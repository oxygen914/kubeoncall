import { api, type ListEnvelope } from '@/api/client'

export interface ChangeEvent {
  changeId: string
  changeType: string
  changedBy: string
  changedAt: string
  resourceType: string
  resourceName: string
  namespace: string
  cluster: string
  diff: Record<string, unknown>
  changeSource: string
  correlationId: string | null
}

export interface ChangeEventQuery {
  from?: string
  to?: string
  cluster?: string
  namespace?: string
  page?: number
  size?: number
}

export interface ChangeCorrelation {
  changeEvent: ChangeEvent
  correlationScore: number
  correlationReason: string
  suggestions: string[]
}

export function listChangeEvents(params: ChangeEventQuery): Promise<ListEnvelope<ChangeEvent>> {
  return api.list<ChangeEvent>('/api/v1/change-events', {
    query: {
      from: params.from,
      to: params.to,
      cluster: params.cluster,
      namespace: params.namespace,
      page: params.page,
      size: params.size,
    },
  })
}

export function correlateChanges(alarm: Record<string, unknown>): Promise<ChangeCorrelation[]> {
  return api.post<ChangeCorrelation[]>('/api/v1/change-events/correlations', alarm)
}
