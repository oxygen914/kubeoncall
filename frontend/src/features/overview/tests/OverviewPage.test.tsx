import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OverviewPage } from '../OverviewPage'
import { SessionContext, type SessionState } from '@/features/auth/sessionContext'
import { PERMISSIONS } from '@/features/auth/permissions'
import { MonitoringScopeProvider } from '@/features/monitoring/MonitoringScopeProvider'
import { ThemeProvider } from '@/features/theme/ThemeProvider'

vi.mock('echarts-for-react/lib/core', () => ({
  default: ({ option }: { option: unknown }) => (
    <div data-testid="echart" data-option={JSON.stringify(option)} />
  ),
}))

const meta = { requestId: 'req_overview_test', timestamp: '2026-07-29T02:00:00Z' }
const page = { number: 1, size: 5, totalElements: 1, totalPages: 1, hasNext: false }

function dataEnvelope(data: unknown): Response {
  return jsonResponse({ data, meta })
}

function listEnvelope(data: unknown[]): Response {
  return jsonResponse({ data, page, meta })
}

function jsonResponse(payload: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => payload,
  } as Response
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const sessionState: SessionState = {
    session: {
      authenticated: true,
      user: {
        id: 'usr_ops',
        username: 'operator',
        displayName: 'Operator',
        roles: ['OPERATOR'],
        permissions: [
          PERMISSIONS.DASHBOARD_READ,
          PERMISSIONS.ALARM_READ,
          PERMISSIONS.APPROVAL_READ,
          PERMISSIONS.EXECUTION_READ,
          PERMISSIONS.SANDBOX_READ,
          PERMISSIONS.ASK_EXECUTE,
        ],
      },
      expiresAt: '2026-07-30T00:00:00Z',
    },
    loading: false,
    error: null,
    login: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
  }

  return render(
    <QueryClientProvider client={queryClient}>
      <SessionContext.Provider value={sessionState}>
        <ThemeProvider>
          <MemoryRouter>
            <MonitoringScopeProvider>
              <OverviewPage />
            </MonitoringScopeProvider>
          </MemoryRouter>
        </ThemeProvider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  )
}

describe('OverviewPage', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/v1/overview?')) {
        return Promise.resolve(
          dataEnvelope({
            activeAlarms: 2,
            pendingApprovals: 1,
            runningExecutions: 1,
            failedExecutions: 2,
            severityCounts: { P1: 1, P2: 1 },
            statusCounts: { FIRING: 2 },
            executionStatusCounts: { RUNNING: 1, SUCCEEDED: 4, FAILED: 2 },
            failureReasons: { TOOL_TIMEOUT: 2 },
            executionTrend: { '2026-07-29 09:00': 2, '2026-07-29 10:00': 5 },
            window: '24h',
          }),
        )
      }
      if (url.startsWith('/api/v1/monitoring/scopes')) {
        return Promise.resolve(
          dataEnvelope({
            clusters: [{ value: 'prod', label: 'prod', resourceCount: 3 }],
            environments: [],
            namespaces: [{ value: 'payments', label: 'payments', resourceCount: 8 }],
            capabilities: {
              clusterFilterAvailable: true,
              environmentFilterAvailable: false,
              namespaceFilterAvailable: true,
            },
            collectedAt: '2026-07-29T02:00:00Z',
          }),
        )
      }
      if (url.startsWith('/api/v1/monitoring/summary?')) {
        return Promise.resolve(
          dataEnvelope({
            cluster: 'prod',
            totalNodes: 3,
            readyNodes: 2,
            notReadyNodes: 1,
            unknownNodes: 0,
            totalPods: 30,
            podPhaseCounts: { Running: 29, Pending: 1 },
            averageCpuUsagePercent: 48,
            dataSources: { nodeMetricsAvailable: true, kubernetesStateAvailable: true },
            collectedAt: '2026-07-29T02:00:00Z',
          }),
        )
      }
      if (url.startsWith('/api/v1/monitoring/pods?')) {
        return Promise.resolve(
          dataEnvelope({
            cluster: 'prod',
            kubernetesStateAvailable: true,
            pods: [
              {
                namespace: 'payments',
                name: 'payment-api-7d9',
                node: 'worker-1',
                phase: 'Pending',
                restartCount: 3,
              },
            ],
            returned: 1,
            collectedAt: '2026-07-29T02:00:00Z',
          }),
        )
      }
      if (url.startsWith('/api/v1/monitoring/health/trend?')) {
        return Promise.resolve(
          dataEnvelope({
            scope: { cluster: 'prod' },
            window: '24h',
            stepSeconds: 900,
            current: [
              {
                timestamp: '2026-07-29T02:00:00Z',
                readyPercent: 66.67,
                abnormalPods: 1,
                healthScore: 61.67,
              },
            ],
            previous: [
              {
                timestamp: '2026-07-28T02:00:00Z',
                readyPercent: 100,
                abnormalPods: 0,
                healthScore: 100,
              },
            ],
            comparison: {
              currentAverage: 61.67,
              previousAverage: 100,
              delta: -38.33,
              direction: 'DEGRADING',
              baselineAvailable: true,
            },
            collectedAt: '2026-07-29T02:00:00Z',
          }),
        )
      }
      if (url.startsWith('/api/v1/monitoring/advice?')) {
        return Promise.resolve(
          dataEnvelope({
            scope: { cluster: 'prod' },
            generatedBy: 'RULE_ENGINE_FALLBACK',
            modelAvailable: false,
            safetyMode: 'READ_ONLY',
            advice: [
              {
                id: 'node-health',
                title: '优先核查节点健康',
                risk: 'P1',
                summary: '当前范围存在 NotReady 节点。',
                evidence: '1 NotReady',
                recommendation: '先检查 kubelet 与网络状态。',
                source: 'RULE_ENGINE_FALLBACK',
                analysisPath: '/monitoring',
              },
            ],
            collectedAt: '2026-07-29T02:00:00Z',
          }),
        )
      }
      if (url.startsWith('/api/v1/alarms?')) {
        return Promise.resolve(
          listEnvelope([
            {
              id: 'alm_1',
              fingerprint: 'fp_1',
              alertName: 'API 服务不可用',
              severity: 'P1',
              status: 'FIRING',
              resource: {
                type: 'POD',
                name: 'payment-api-7d9',
                cluster: 'prod',
                namespace: 'payments',
                service: 'payment-api',
              },
              firstSeen: '2026-07-29T01:00:00Z',
              lastSeen: '2026-07-29T02:00:00Z',
              occurrenceCount: 3,
              acknowledgement: { acknowledged: false, by: null, at: null },
              latestExecution: null,
              version: 1,
            },
          ]),
        )
      }
      if (url.startsWith('/api/v1/approvals?')) {
        return Promise.resolve(
          listEnvelope([
            {
              id: 'apr_1',
              executionId: 'exec_1',
              status: 'PENDING',
              riskLevel: 'HIGH',
              summary: '重启 payment-api 工作负载',
              createdAt: '2026-07-29T02:00:00Z',
              expiresAt: null,
              version: 1,
            },
          ]),
        )
      }
      if (url.startsWith('/api/v1/executions?')) {
        return Promise.resolve(
          listEnvelope([
            {
              id: 'exec_1',
              type: 'REMEDIATION',
              status: 'FAILED',
              summary: 'payment-api 故障恢复',
              triggerId: 'alm_1',
              startedAt: '2026-07-29T02:00:00Z',
              finishedAt: '2026-07-29T02:00:04Z',
              durationMs: 4000,
              version: 1,
            },
          ]),
        )
      }
      if (url === '/api/v1/sandbox-runs') {
        return Promise.resolve(dataEnvelope([]))
      }
      return Promise.reject(new Error(`Unexpected request: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders an evidence-backed operations command center', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: '当前风险摘要' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /P1 \/ P2 活跃告警/ })).toHaveTextContent('1 / 1')
    expect((await screen.findAllByText('API 服务不可用')).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: '执行趋势' })).toBeInTheDocument()
    expect(await screen.findByText('TOOL_TIMEOUT')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '今日需要关注' })).toBeInTheDocument()
    expect(screen.getByText('优先核查节点健康')).toBeInTheDocument()
    expect(screen.getAllByTestId('echart')).toHaveLength(2)
  })
})
