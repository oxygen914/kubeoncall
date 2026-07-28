import { api, type ListEnvelope } from '@/api/client'

export interface CircuitState {
  state: string
  consecutiveFailures: number
  openedAt: string | null
}

export interface IntegrationView {
  id: string
  name: string
  configured: boolean
  endpoint: string | null
  timeoutMillis: number | null
  circuitState: string
  consecutiveFailures: number
  circuitOpenedAt: string | null
}

export interface IntegrationCatalog {
  integrations: IntegrationView[]
  circuits: Record<string, CircuitState>
}

export interface NotificationDelivery {
  id: string
  executionId: string
  status: string
  summary: string | null
  errorCode: string | null
  errorSummary: string | null
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  attempt: number
}

export const getIntegrations = () => api.get<IntegrationCatalog>('/api/v1/integrations')

export const getNotificationDeliveries = (
  page = 1,
  size = 20,
): Promise<ListEnvelope<NotificationDelivery>> =>
  api.list<NotificationDelivery>('/api/v1/integrations/notifications', {
    query: { page, size },
  })
