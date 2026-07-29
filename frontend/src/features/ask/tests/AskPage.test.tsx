import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MonitoringScopeContext } from '@/features/monitoring/monitoringScopeContext'
import { AskPage } from '../AskPage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MonitoringScopeContext.Provider
        value={{
          scope: { cluster: 'test-01', environment: 'test', namespace: 'payments' },
          catalog: undefined,
          isLoading: false,
          error: null,
          setCluster: vi.fn(),
          setEnvironment: vi.fn(),
          setNamespace: vi.fn(),
        }}
      >
        <MemoryRouter>
          <AskPage />
        </MemoryRouter>
      </MonitoringScopeContext.Provider>
    </QueryClientProvider>,
  )
}

function response(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 202 ? 'Accepted' : 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => ({
      data,
      meta: { requestId: 'req_ask_test', timestamp: '2026-07-29T06:00:00Z' },
    }),
  } as Response
}

function executionDetail(executionId: string, taskId: string) {
  return {
    id: executionId,
    type: 'ASK',
    status: 'WAITING_APPROVAL',
    summary: '分析 Pending Pod',
    triggerId: null,
    startedAt: '2026-07-29T06:00:00Z',
    finishedAt: null,
    durationMs: null,
    version: 2,
    sessionId: 'session_1',
    taskId,
    taskStatus: 'SUCCEEDED',
    answer: '发现 payment-api Pod 持续 Pending，调度事件显示 CPU 不足。',
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
    evidence: [
      {
        evidenceId: 'evd_event_1',
        type: 'K8S_EVENT',
        source: 'kubernetes-api',
        cluster: 'test-01',
        namespace: 'payments',
        resource: { kind: 'Pod', name: 'payment-api-1', uid: 'pod-uid-1' },
        observedAt: '2026-07-29T06:00:00Z',
        window: {
          start: '2026-07-29T05:45:00Z',
          end: '2026-07-29T06:00:00Z',
        },
        summary: 'Pod scheduling failed',
        snippet: '0/3 nodes are available: Insufficient cpu',
        freshnessSeconds: 8,
        redacted: true,
        truncated: false,
        collectionStatus: 'SUCCEEDED',
      },
    ],
    conclusions: [
      {
        conclusionId: 'con_1',
        claim: 'Pod Pending 的直接原因是可调度 CPU 不足',
        severity: 'P2',
        status: 'SUPPORTED',
        evidenceRefs: ['evd_event_1'],
        sopRefs: [
          {
            sopId: 'pod-pending-triage',
            version: '1.3.0',
            source: 'runbook',
            section: 'Insufficient resources',
          },
        ],
        confidence: {
          score: 0.91,
          label: 'HIGH',
          basis: { directEvidence: 1, freshness: 1 },
        },
        planner: {
          mode: 'REAL_MODEL',
          model: 'qwen-plus',
          degraded: false,
        },
        recommendedAction: {
          type: 'RESTART_SERVICE',
          requiresApproval: true,
          parameters: {},
        },
      },
    ],
  }
}

describe('AskPage', () => {
  const fetchMock = vi.fn()
  let submissionCount = 0

  beforeEach(() => {
    submissionCount = 0
    window.localStorage.clear()
    fetchMock.mockReset()
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if ((init?.method ?? 'GET') === 'POST' && url.endsWith('/api/v1/executions')) {
        submissionCount += 1
        const executionId = `exe_${submissionCount}`
        return response({ executionId, taskId: `tsk_${submissionCount}`, status: 'PENDING' }, 202)
      }
      const match = url.match(/\/api\/v1\/executions\/(exe_\d+)$/)
      if (match) {
        return response(executionDetail(match[1]!, `tsk_${match[1]!.slice(4)}`))
      }
      throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('renders durable answer, planner source, evidence, confidence and approval boundary', async () => {
    renderPage()

    expect(screen.getByRole('heading', { name: '从当前运维问题开始' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '分析当前集群中处于 Pending 状态的 Pod' }))
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))

    expect(
      (await screen.findAllByText(/发现 payment-api Pod 持续 Pending/)).length,
    ).toBeGreaterThan(0)
    expect(screen.getAllByText(/真实模型/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/HIGH · 91%/).length).toBeGreaterThan(0)
    expect(screen.getByText('等待人工审批')).toBeInTheDocument()
    expect(screen.getByText('Prepare restart for payment-api')).toBeInTheDocument()
    expect(screen.getByText('Pod Pending 的直接原因是可调度 CPU 不足')).toBeInTheDocument()
    expect(screen.getByText(/浏览器关闭不会中断后台任务/)).toBeInTheDocument()
  })

  it('reuses session and sends scope with an idempotency key for a follow-up', async () => {
    renderPage()
    const input = screen.getByLabelText('向 KubeOnCall 提问')

    fireEvent.change(input, { target: { value: '分析 Pending Pod' } })
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))
    await screen.findAllByText(/发现 payment-api Pod 持续 Pending/)

    fireEvent.change(input, { target: { value: '继续说明推荐动作' } })
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }))

    await waitFor(() => {
      const posts = fetchMock.mock.calls.filter(
        ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
      )
      expect(posts).toHaveLength(2)
    })
    const posts = fetchMock.mock.calls.filter(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    )
    const secondRequest = posts[1]?.[1] as RequestInit
    expect(JSON.parse(String(secondRequest.body))).toMatchObject({
      question: '继续说明推荐动作',
      sessionId: 'session_1',
      cluster: 'test-01',
      environment: 'test',
      namespace: 'payments',
    })
    const headers = secondRequest.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toMatch(/^ask_.{16,}$/)
  })
})
