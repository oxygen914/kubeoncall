import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ExecutionDetail, ExecutionNode } from '../api'

const { useExecutionMock, useExecutionNodesMock } = vi.hoisted(() => ({
  useExecutionMock: vi.fn(),
  useExecutionNodesMock: vi.fn(),
}))

vi.mock('../hooks', () => ({
  useExecution: () => useExecutionMock(),
  useExecutionNodes: () => useExecutionNodesMock(),
}))

import { ExecutionDetailPage } from '../ExecutionDetailPage'

const execution: ExecutionDetail = {
  id: 'exec_1',
  type: 'ALARM_DIAGNOSIS',
  status: 'RUNNING',
  summary: '诊断 worker-1',
  triggerId: 'alm_1',
  startedAt: '2026-07-20T12:00:00Z',
  finishedAt: null,
  durationMs: null,
  version: 2,
  currentNode: 'diagnose',
  requestId: 'req_1',
}

const node: ExecutionNode = {
  id: 'node_1',
  nodeName: 'diagnose',
  status: 'RUNNING',
  attempt: 1,
  startedAt: '2026-07-20T12:00:01Z',
  finishedAt: null,
  outputSummary: '正在收集节点指标',
  errorCode: null,
}

const closureNode: ExecutionNode = {
  id: 'node_2',
  nodeName: 'operationClosureNode',
  status: 'FAILED',
  attempt: 1,
  startedAt: '2026-07-20T12:00:02Z',
  finishedAt: '2026-07-20T12:02:02Z',
  outputSummary: 'Post-execution verification failed; the operation was rolled back',
  errorCode: 'FAILURE',
}

describe('ExecutionDetailPage', () => {
  it('renders execution state and node timeline', () => {
    useExecutionMock.mockReturnValue({ data: execution, isLoading: false, error: null })
    useExecutionNodesMock.mockReturnValue({ data: [node], isLoading: false, error: null })

    render(
      <MemoryRouter initialEntries={['/executions/exec_1']}>
        <Routes>
          <Route path="/executions/:executionId" element={<ExecutionDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('诊断 worker-1')).toBeInTheDocument()
    expect(screen.getAllByText('RUNNING')).toHaveLength(2)
    expect(screen.getAllByText('diagnose')).toHaveLength(2)
    expect(screen.getByText('正在收集节点指标')).toBeInTheDocument()
  })

  it('renders the recovery verification and rollback phase', () => {
    useExecutionMock.mockReturnValue({
      data: { ...execution, currentNode: 'operationClosureNode', errorSummary: '恢复验证超时' },
      isLoading: false,
      error: null,
    })
    useExecutionNodesMock.mockReturnValue({
      data: [closureNode],
      isLoading: false,
      error: null,
    })

    render(
      <MemoryRouter initialEntries={['/executions/exec_1']}>
        <Routes>
          <Route path="/executions/:executionId" element={<ExecutionDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getAllByText('恢复验证与回滚')).toHaveLength(2)
    expect(screen.getByText('恢复验证超时')).toBeInTheDocument()
    expect(
      screen.getByText('Post-execution verification failed; the operation was rolled back'),
    ).toBeInTheDocument()
  })

  it('returns to the exact filtered list URL supplied by the list page', () => {
    useExecutionMock.mockReturnValue({ data: execution, isLoading: false, error: null })
    useExecutionNodesMock.mockReturnValue({ data: [node], isLoading: false, error: null })

    render(
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/executions/exec_1',
            state: { returnTo: '/executions?status=FAILED&page=2' },
          },
        ]}
      >
        <Routes>
          <Route path="/executions/:executionId" element={<ExecutionDetailPage />} />
          <Route path="/executions" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '← 返回列表' }))
    expect(screen.getByText('/executions?status=FAILED&page=2')).toBeInTheDocument()
  })
})

function LocationProbe() {
  const location = useLocation()
  return <div>{`${location.pathname}${location.search}`}</div>
}
