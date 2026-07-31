import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { taskRefetchInterval, type Task } from '../api'

const { useTaskMock } = vi.hoisted(() => ({ useTaskMock: vi.fn() }))

vi.mock('../hooks', () => ({ useTask: () => useTaskMock() }))

import { TaskStatusPanel } from '../TaskStatusPanel'

const runningTask: Task = {
  id: 'task_1',
  taskType: 'APPROVAL_RESUME',
  status: 'RUNNING',
  stage: 'resuming',
  progressPercent: 60,
  resourceId: 'apr_1',
  errorCode: null,
  errorSummary: null,
  createdAt: '2026-07-20T12:00:00Z',
  finishedAt: null,
}

describe('task status', () => {
  it('polls active tasks every three seconds and stops for terminal tasks', () => {
    expect(taskRefetchInterval(undefined)).toBe(3000)
    expect(taskRefetchInterval(runningTask)).toBe(3000)
    expect(taskRefetchInterval({ ...runningTask, status: 'RETRY' })).toBe(3000)
    expect(taskRefetchInterval({ ...runningTask, status: 'CANCEL_REQUESTED' })).toBe(3000)
    expect(taskRefetchInterval({ ...runningTask, status: 'SUCCEEDED' })).toBe(false)
    expect(taskRefetchInterval({ ...runningTask, status: 'FAILED' })).toBe(false)
    expect(taskRefetchInterval({ ...runningTask, status: 'CANCELLED' })).toBe(false)
    expect(taskRefetchInterval({ ...runningTask, status: 'DEAD_LETTER' })).toBe(false)
  })

  it('renders active task stage and progress', () => {
    useTaskMock.mockReturnValue({
      data: runningTask,
      isLoading: false,
      error: null,
      isFetching: true,
    })

    render(<TaskStatusPanel taskId="task_1" />)

    expect(screen.getByText('RUNNING')).toBeInTheDocument()
    expect(screen.getByText('resuming')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '任务进度' })).toHaveValue(60)
  })

  it('renders retry state and the persisted error summary while polling continues', () => {
    useTaskMock.mockReturnValue({
      data: {
        ...runningTask,
        status: 'RETRY',
        stage: 'waiting_retry',
        errorCode: 'UPSTREAM_TIMEOUT',
        errorSummary: '上游模型调用超时，等待下一次重试',
      },
      isLoading: false,
      error: null,
      isFetching: true,
    })

    render(<TaskStatusPanel taskId="task_1" />)

    expect(screen.getByText('RETRY')).toBeInTheDocument()
    expect(screen.getByText('UPSTREAM_TIMEOUT')).toBeInTheDocument()
    expect(screen.getByText('上游模型调用超时，等待下一次重试')).toBeInTheDocument()
    expect(screen.getByText('刷新中…')).toBeInTheDocument()
  })
})
