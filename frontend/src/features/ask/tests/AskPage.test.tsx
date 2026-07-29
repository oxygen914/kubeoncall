import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AskPage } from '../AskPage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AskPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function successResponse(executionId: string, sessionId: string): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => ({
      data: {
        executionId,
        status: 'WAITING_APPROVAL',
        message: '发现 payment-api Pod 持续 Pending，调度事件显示 CPU 不足。',
        sessionId,
        details: {
          plan: {
            approvalRequired: true,
            tasks: [
              {
                taskId: 'task-1',
                description: 'Prepare restart for payment-api',
                taskType: 'RESTART_SERVICE',
                riskLevel: 'HIGH',
                target: 'payment-api',
              },
            ],
          },
          approval: {
            pause: {
              riskReasons: ['Selected executor tool mutates external state and requires approval'],
            },
          },
        },
      },
      meta: { requestId: 'req_ask_test', timestamp: '2026-07-29T06:00:00Z' },
    }),
  } as Response
}

describe('AskPage', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock
      .mockResolvedValueOnce(successResponse('exe_1', 'session_1'))
      .mockResolvedValueOnce(successResponse('exe_2', 'session_1'))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders the AI answer, execution evidence, and approval boundary', async () => {
    renderPage()

    expect(screen.getByRole('heading', { name: '从当前运维问题开始' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '分析当前集群中处于 Pending 状态的 Pod' }))
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))

    expect(await screen.findByText(/发现 payment-api Pod 持续 Pending/)).toBeInTheDocument()
    expect(screen.getByText('KubeOnCall AI')).toBeInTheDocument()
    expect(screen.getByText('等待人工审批')).toBeInTheDocument()
    expect(screen.getByText('Prepare restart for payment-api')).toBeInTheDocument()
    expect(screen.getByText(/接入持久化执行入口后/)).toBeInTheDocument()
  })

  it('reuses the returned session for a follow-up question', async () => {
    renderPage()
    const input = screen.getByLabelText('向 KubeOnCall 提问')

    fireEvent.change(input, { target: { value: '分析 Pending Pod' } })
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
    await screen.findByText(/发现 payment-api Pod 持续 Pending/)

    fireEvent.change(input, { target: { value: '继续说明推荐动作' } })
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const secondRequest = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(JSON.parse(String(secondRequest.body))).toMatchObject({
      question: '继续说明推荐动作',
      sessionId: 'session_1',
    })
  })
})
