export const REALTIME_TOPICS = [
  'alarms',
  'approvals',
  'executions',
  'sandbox-runs',
  'tasks',
] as const

export type RealtimeTopic = (typeof REALTIME_TOPICS)[number]

export type RealtimeConnectionStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface RealtimeEvent {
  eventId?: string
  eventType: string
  schemaVersion: number
  resourceType?: string
  resourceId?: string
  occurredAt?: string
  version?: number
  data: Record<string, unknown>
}
