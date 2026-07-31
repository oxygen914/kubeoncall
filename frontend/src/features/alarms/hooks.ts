import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  acknowledgeAlarm,
  approveAlarmSilence,
  confirmAlarmRecovery,
  getAlarm,
  getAlarmTimeline,
  listAlarms,
  type AlarmListParams,
} from './api'

/** Stable query-key factory so cache invalidation can target list or detail precisely. */
export const alarmKeys = {
  all: ['alarms'] as const,
  list: (params: AlarmListParams) => ['alarms', 'list', params] as const,
  detail: (alarmId: string) => ['alarms', 'detail', alarmId] as const,
  timeline: (alarmId: string) => ['alarms', 'timeline', alarmId] as const,
}

export function useAlarmList(params: AlarmListParams) {
  return useQuery({
    queryKey: alarmKeys.list(params),
    queryFn: () => listAlarms(params),
    staleTime: 10_000,
  })
}

export function useAlarm(alarmId: string | undefined) {
  return useQuery({
    queryKey: alarmKeys.detail(alarmId ?? ''),
    queryFn: () => getAlarm(alarmId as string),
    enabled: Boolean(alarmId),
    staleTime: 10_000,
  })
}

export function useAlarmTimeline(alarmId: string | undefined) {
  return useQuery({
    queryKey: alarmKeys.timeline(alarmId ?? ''),
    queryFn: () => getAlarmTimeline(alarmId as string, { limit: 50 }),
    enabled: Boolean(alarmId),
    staleTime: 5_000,
  })
}

/**
 * Acknowledge mutation. On success it invalidates the detail + list caches so the new status/version
 * and the timeline entry appear without a manual refresh. A fresh Idempotency-Key is generated per
 * user-initiated action so retries (e.g. after a network blip) are deduped server-side, but a second
 * click of the same action gets a new key — which is correct since the user may have changed the reason.
 */
export function useAcknowledgeAlarm(alarmId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: { version: number; reason: string }) =>
      acknowledgeAlarm(
        alarmId as string,
        variables.version,
        variables.reason,
        newIdempotencyKey('ack'),
      ),
    onSuccess: () => invalidateAlarmQueries(queryClient, alarmId),
  })
}

export function useConfirmAlarmRecovery(alarmId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: { version: number; note: string }) =>
      confirmAlarmRecovery(
        alarmId as string,
        variables.version,
        variables.note,
        newIdempotencyKey('recovery'),
      ),
    onSuccess: () => invalidateAlarmQueries(queryClient, alarmId),
  })
}

export function useApproveAlarmSilence(alarmId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: { version: number; reason: string; expiresAt: string }) =>
      approveAlarmSilence(
        alarmId as string,
        variables.version,
        variables.reason,
        variables.expiresAt,
        newIdempotencyKey('silence'),
      ),
    onSuccess: () => invalidateAlarmQueries(queryClient, alarmId),
  })
}

function invalidateAlarmQueries(
  queryClient: ReturnType<typeof useQueryClient>,
  alarmId: string | undefined,
): void {
  void queryClient.invalidateQueries({ queryKey: alarmKeys.detail(alarmId ?? '') })
  void queryClient.invalidateQueries({ queryKey: alarmKeys.timeline(alarmId ?? '') })
  void queryClient.invalidateQueries({ queryKey: ['alarms', 'list'] })
}

function newIdempotencyKey(action: 'ack' | 'recovery' | 'silence'): string {
  // crypto.randomUUID is available in all supported browsers and Node 20 test env.
  return `${action}_${crypto.randomUUID()}`
}
