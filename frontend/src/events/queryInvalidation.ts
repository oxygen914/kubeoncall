import type { QueryClient } from '@tanstack/react-query'
import type { RealtimeEvent } from './types'

/**
 * SSE is only an invalidation hint. REST remains the source of truth.
 * Resource keys deliberately match the feature query-key factories.
 */
export function invalidateForRealtimeEvent(queryClient: QueryClient, event: RealtimeEvent): void {
  const family = eventFamily(event)
  const resourceId = effectiveResourceId(event, family)

  switch (family) {
    case 'alarm':
      void queryClient.invalidateQueries({ queryKey: ['alarms', 'list'] })
      if (resourceId) {
        void queryClient.invalidateQueries({ queryKey: ['alarms', 'detail', resourceId] })
        void queryClient.invalidateQueries({ queryKey: ['alarms', 'timeline', resourceId] })
      }
      break
    case 'approval':
      void queryClient.invalidateQueries({ queryKey: ['approvals', 'list'] })
      if (resourceId) {
        void queryClient.invalidateQueries({
          queryKey: ['approvals', 'detail', resourceId],
        })
      }
      break
    case 'execution':
      void queryClient.invalidateQueries({ queryKey: ['executions', 'list'] })
      if (resourceId) {
        void queryClient.invalidateQueries({
          queryKey: ['executions', 'detail', resourceId],
        })
        void queryClient.invalidateQueries({
          queryKey: ['executions', 'nodes', resourceId],
        })
      }
      break
    case 'task':
      if (resourceId) {
        void queryClient.invalidateQueries({ queryKey: ['tasks', 'detail', resourceId] })
      }
      break
    case 'sandbox':
      void queryClient.invalidateQueries({ queryKey: ['sandbox-runs', 'list'] })
      if (resourceId) {
        void queryClient.invalidateQueries({ queryKey: ['sandbox-runs', 'detail', resourceId] })
        void queryClient.invalidateQueries({ queryKey: ['sandbox-runs', 'artifacts', resourceId] })
      }
      break
    case 'system':
      void queryClient.invalidateQueries({ queryKey: ['system'] })
      void queryClient.invalidateQueries({ queryKey: ['capabilities'] })
      break
  }
}

export function invalidateAllRealtimeQueries(queryClient: QueryClient): void {
  void queryClient.invalidateQueries()
}

type EventFamily = 'alarm' | 'approval' | 'execution' | 'sandbox' | 'task' | 'system' | undefined

function eventFamily(event: RealtimeEvent): EventFamily {
  const prefix = event.eventType.toLowerCase().split('.')[0]
  if (
    prefix === 'alarm' ||
    prefix === 'approval' ||
    prefix === 'execution' ||
    prefix === 'sandbox' ||
    prefix === 'task' ||
    prefix === 'system'
  ) {
    return prefix
  }

  const resourceType = event.resourceType?.toLowerCase() ?? ''
  if (resourceType.includes('alarm')) return 'alarm'
  if (resourceType.includes('approval')) return 'approval'
  if (resourceType.includes('execution')) return 'execution'
  if (resourceType.includes('sandbox')) return 'sandbox'
  if (resourceType.includes('task')) return 'task'
  if (resourceType.includes('system')) return 'system'
  return undefined
}

function effectiveResourceId(event: RealtimeEvent, family: EventFamily): string | undefined {
  if (family === 'execution' && event.eventType === 'execution.node.updated') {
    const executionId = event.data.executionId
    if (typeof executionId === 'string' && executionId) return executionId
  }
  return event.resourceId
}
