import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { getRuntimeConfig, loadRuntimeConfig } from '@/api/runtimeConfig'
import { PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { invalidateAllRealtimeQueries, invalidateForRealtimeEvent } from './queryInvalidation'
import { SseClient } from './sseClient'
import { EventContext } from './eventContext'
import { REALTIME_TOPICS, type RealtimeConnectionStatus, type RealtimeTopic } from './types'

export function EventProvider({
  children,
  topics = REALTIME_TOPICS,
}: {
  children: ReactNode
  topics?: readonly RealtimeTopic[]
}) {
  const queryClient = useQueryClient()
  const { session, loading } = useSession()
  const [status, setStatus] = useState<RealtimeConnectionStatus>('idle')
  const authorizedTopics = useMemo(
    () => filterAuthorizedTopics(topics, session?.user.permissions ?? []),
    [session?.user.permissions, topics],
  )

  useEffect(() => {
    if (loading || !session?.authenticated || authorizedTopics.length === 0) {
      setStatus('idle')
      return
    }

    let disposed = false
    let client: SseClient | undefined
    void loadRuntimeConfig().then(() => {
      if (disposed) return
      const config = getRuntimeConfig()
      client = new SseClient({
        url: joinBaseAndPath(config.apiBaseUrl, config.ssePath),
        topics: authorizedTopics,
        storageKey: `koc.realtime.${session.user.id}.lastEventId`,
        onEvent: (event) => invalidateForRealtimeEvent(queryClient, event),
        onGap: () => invalidateAllRealtimeQueries(queryClient),
        onStatusChange: setStatus,
      })
      client.start()
    })

    return () => {
      disposed = true
      client?.stop()
    }
  }, [authorizedTopics, loading, queryClient, session?.authenticated, session?.user.id])

  const value = useMemo(() => ({ status }), [status])
  return <EventContext.Provider value={value}>{children}</EventContext.Provider>
}

const TOPIC_PERMISSION: Record<RealtimeTopic, string> = {
  alarms: PERMISSIONS.ALARM_READ,
  approvals: PERMISSIONS.APPROVAL_READ,
  executions: PERMISSIONS.EXECUTION_READ,
  'sandbox-runs': PERMISSIONS.SANDBOX_READ,
  tasks: PERMISSIONS.EXECUTION_READ,
}

function filterAuthorizedTopics(
  topics: readonly RealtimeTopic[],
  permissions: readonly string[],
): RealtimeTopic[] {
  const granted = new Set(permissions)
  return topics.filter((topic) => granted.has(TOPIC_PERMISSION[topic]))
}

function joinBaseAndPath(base: string, path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  if (!base) return path
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}
