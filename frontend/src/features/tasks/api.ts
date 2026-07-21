import { api } from '@/api/client'

export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'RETRY'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'DEAD_LETTER'

export interface Task {
  id: string
  taskType: string
  status: TaskStatus
  stage: string | null
  progressPercent: number | null
  resourceId: string | null
  errorCode: string | null
  errorSummary: string | null
  createdAt: string
  finishedAt: string | null
}

export interface TaskAccepted {
  taskId: string
  status: 'PENDING'
}

export function getTask(taskId: string): Promise<Task> {
  return api.get<Task>(`/api/v1/tasks/${encodeURIComponent(taskId)}`)
}

export function isTaskTerminal(status: TaskStatus): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'DEAD_LETTER'].includes(status)
}

export function taskRefetchInterval(task: Task | undefined): number | false {
  return task && isTaskTerminal(task.status) ? false : 3_000
}
