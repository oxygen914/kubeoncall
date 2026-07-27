import type { QueryClient } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { invalidateAllRealtimeQueries, invalidateForRealtimeEvent } from '../queryInvalidation'
import type { RealtimeEvent } from '../types'

function event(overrides: Partial<RealtimeEvent>): RealtimeEvent {
  return {
    eventId: 'evt_1',
    eventType: 'alarm.updated',
    schemaVersion: 1,
    data: {},
    ...overrides,
  }
}

function queryClientSpy() {
  const invalidateQueries = vi.fn().mockResolvedValue(undefined)
  return {
    queryClient: { invalidateQueries } as unknown as QueryClient,
    invalidateQueries,
  }
}

describe('realtime query invalidation', () => {
  it('invalidates only the matching alarm list, detail and timeline keys', () => {
    const { queryClient, invalidateQueries } = queryClientSpy()

    invalidateForRealtimeEvent(
      queryClient,
      event({ eventType: 'alarm.updated', resourceId: 'alm_1' }),
    )

    expect(invalidateQueries).toHaveBeenCalledTimes(3)
    expect(invalidateQueries).toHaveBeenNthCalledWith(1, {
      queryKey: ['alarms', 'list'],
    })
    expect(invalidateQueries).toHaveBeenNthCalledWith(2, {
      queryKey: ['alarms', 'detail', 'alm_1'],
    })
    expect(invalidateQueries).toHaveBeenNthCalledWith(3, {
      queryKey: ['alarms', 'timeline', 'alm_1'],
    })
  })

  it('uses the parent execution id for node updates and leaves other families untouched', () => {
    const { queryClient, invalidateQueries } = queryClientSpy()

    invalidateForRealtimeEvent(
      queryClient,
      event({
        eventType: 'execution.node.updated',
        resourceType: 'EXECUTION_NODE',
        resourceId: 'node_1',
        data: { executionId: 'exec_1' },
      }),
    )

    expect(invalidateQueries).toHaveBeenCalledTimes(3)
    expect(invalidateQueries).toHaveBeenNthCalledWith(1, {
      queryKey: ['executions', 'list'],
    })
    expect(invalidateQueries).toHaveBeenNthCalledWith(2, {
      queryKey: ['executions', 'detail', 'exec_1'],
    })
    expect(invalidateQueries).toHaveBeenNthCalledWith(3, {
      queryKey: ['executions', 'nodes', 'exec_1'],
    })
  })

  it('invalidates a task detail precisely without disabling its polling policy', () => {
    const { queryClient, invalidateQueries } = queryClientSpy()

    invalidateForRealtimeEvent(
      queryClient,
      event({ eventType: 'task.updated', resourceId: 'task_1' }),
    )

    expect(invalidateQueries).toHaveBeenCalledOnce()
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['tasks', 'detail', 'task_1'],
    })
  })

  it('invalidates sandbox list, detail and artifacts for sandbox lifecycle events', () => {
    const { queryClient, invalidateQueries } = queryClientSpy()

    invalidateForRealtimeEvent(
      queryClient,
      event({
        eventType: 'sandbox.run.terminal',
        resourceType: 'sandbox-run',
        resourceId: 'sbx_1',
      }),
    )

    expect(invalidateQueries).toHaveBeenCalledTimes(3)
    expect(invalidateQueries).toHaveBeenNthCalledWith(1, { queryKey: ['sandbox-runs', 'list'] })
    expect(invalidateQueries).toHaveBeenNthCalledWith(2, {
      queryKey: ['sandbox-runs', 'detail', 'sbx_1'],
    })
    expect(invalidateQueries).toHaveBeenNthCalledWith(3, {
      queryKey: ['sandbox-runs', 'artifacts', 'sbx_1'],
    })
  })

  it('invalidates the complete query cache after a cursor gap', () => {
    const { queryClient, invalidateQueries } = queryClientSpy()

    invalidateAllRealtimeQueries(queryClient)

    expect(invalidateQueries).toHaveBeenCalledOnce()
    expect(invalidateQueries).toHaveBeenCalledWith()
  })
})
