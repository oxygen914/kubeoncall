import { useQuery } from '@tanstack/react-query'
import { getTask, taskRefetchInterval } from './api'

export const taskKeys = {
  detail: (taskId: string) => ['tasks', 'detail', taskId] as const,
}

export function useTask(taskId: string | undefined) {
  return useQuery({
    queryKey: taskKeys.detail(taskId ?? ''),
    queryFn: () => getTask(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) => taskRefetchInterval(query.state.data),
  })
}
