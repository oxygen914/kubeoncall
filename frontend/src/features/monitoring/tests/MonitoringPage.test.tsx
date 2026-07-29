import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MonitoringPage } from '../MonitoringPage'
import { MonitoringScopeProvider } from '../MonitoringScopeProvider'

vi.mock('@/features/auth/useSession', () => ({
  useSession: () => ({
    session: {
      authenticated: true,
      user: { permissions: ['dashboard:read', 'alarm:read', 'change:read'] },
    },
  }),
}))

function jsonResponse(data: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => ({
      data,
      meta: { requestId: 'req_monitoring', timestamp: '2026-07-28T10:00:00Z' },
    }),
  } as Response
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MonitoringScopeProvider>
          <MonitoringPage />
        </MonitoringScopeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MonitoringPage', () => {
  const fetchMock = vi.fn()
  let kubernetesStateAvailable = false

  beforeEach(() => {
    kubernetesStateAvailable = false
    fetchMock.mockReset()
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.startsWith('/api/v1/monitoring/scopes')) {
        return Promise.resolve(
          jsonResponse({
            clusters: [{ value: 'prod', label: 'prod', resourceCount: 1 }],
            environments: [],
            namespaces: [{ value: 'default', label: 'default', resourceCount: 1 }],
            capabilities: {
              clusterFilterAvailable: true,
              environmentFilterAvailable: false,
              namespaceFilterAvailable: true,
            },
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/summary')) {
        return Promise.resolve(
          jsonResponse({
            cluster: 'prod',
            totalNodes: 1,
            readyNodes: kubernetesStateAvailable ? 1 : 0,
            notReadyNodes: 0,
            unknownNodes: kubernetesStateAvailable ? 0 : 1,
            totalPods: kubernetesStateAvailable ? 1 : null,
            podPhaseCounts: kubernetesStateAvailable ? { Running: 1 } : {},
            averageCpuUsagePercent: 12.5,
            dataSources: {
              nodeMetricsAvailable: true,
              kubernetesStateAvailable,
            },
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/nodes?')) {
        return Promise.resolve(
          jsonResponse({
            cluster: 'prod',
            dataSources: {
              nodeMetricsAvailable: true,
              kubernetesStateAvailable,
            },
            nodes: [
              {
                name: 'worker-1',
                ready: kubernetesStateAvailable ? 'READY' : 'UNKNOWN',
                exporterUp: true,
                cpuUsagePercent: 12.5,
                memoryUsagePercent: 40,
                podCount: kubernetesStateAvailable ? 1 : null,
              },
            ],
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/pods?')) {
        return Promise.resolve(
          jsonResponse({
            cluster: 'prod',
            kubernetesStateAvailable,
            pods: kubernetesStateAvailable
              ? [
                  {
                    namespace: 'default',
                    name: 'api-1',
                    node: 'worker-1',
                    phase: 'Running',
                    restartCount: 0,
                  },
                ]
              : [],
            returned: kubernetesStateAvailable ? 1 : 0,
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.includes('/cpu?')) {
        return Promise.resolve(
          jsonResponse({
            cluster: 'prod',
            node: 'worker-1',
            window: '15m',
            nodeMetricsAvailable: true,
            points: [],
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/health/trend')) {
        return Promise.resolve(
          jsonResponse({
            scope: { cluster: 'prod' },
            window: '6h',
            stepSeconds: 300,
            current: [
              {
                timestamp: '2026-07-28T10:00:00Z',
                readyPercent: 100,
                abnormalPods: 0,
                healthScore: 100,
              },
            ],
            previous: [
              {
                timestamp: '2026-07-28T04:00:00Z',
                readyPercent: 90,
                abnormalPods: 0,
                healthScore: 90,
              },
            ],
            comparison: {
              currentAverage: 100,
              previousAverage: 90,
              delta: 10,
              direction: 'IMPROVING',
              baselineAvailable: true,
            },
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/correlations')) {
        return Promise.resolve(
          jsonResponse({
            scope: { cluster: 'prod' },
            alarmDataAvailable: true,
            changeDataAvailable: true,
            correlations: [],
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      if (path.startsWith('/api/v1/monitoring/advice')) {
        return Promise.resolve(
          jsonResponse({
            scope: { cluster: 'prod' },
            generatedBy: 'RULE_ENGINE_FALLBACK',
            modelAvailable: false,
            safetyMode: 'READ_ONLY',
            advice: [
              {
                id: 'stable',
                title: '当前范围未发现明显异常',
                risk: 'INFO',
                summary: '健康',
                evidence: 'Ready 1/1',
                recommendation: '保持观察',
                source: 'RULE_ENGINE_FALLBACK',
                analysisPath: '/monitoring',
              },
            ],
            collectedAt: '2026-07-28T10:00:00Z',
          }),
        )
      }
      return Promise.reject(new Error(`Unexpected request: ${path}`))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders node metrics while making missing Kubernetes state explicit', async () => {
    renderPage()

    expect(await screen.findByText('worker-1')).toBeInTheDocument()
    expect(screen.getAllByText('12.50%')).toHaveLength(2)
    expect(screen.getByText(/kube-state-metrics 未接入/)).toBeInTheDocument()
    expect(screen.getByText(/Pod 状态不可用/)).toBeInTheDocument()
    expect(screen.queryByText('Ready', { selector: '.koc-badge' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('当前监控范围')).toHaveTextContent('prod')
    expect(screen.getByRole('heading', { name: '集群健康趋势' })).toBeInTheDocument()
    expect(screen.getByText('规则降级')).toBeInTheDocument()
    expect(screen.getByText('0/1')).toHaveAttribute('data-tone', 'warning')
    expect(screen.getByText('0', { selector: '.koc-overview__value' })).toHaveAttribute(
      'data-tone',
      'warning',
    )
  })

  it('shows connected Kubernetes state and Pod phase data for one cluster', async () => {
    kubernetesStateAvailable = true
    renderPage()

    expect(await screen.findByText('api-1')).toBeInTheDocument()
    const sources = screen.getByLabelText('监控数据源状态')
    expect(within(sources).getAllByText('已连接')).toHaveLength(2)
    expect(screen.getByRole('heading', { name: 'Pod 阶段分布' })).toBeInTheDocument()
    expect(screen.getByText('Ready', { selector: '.koc-badge' })).toBeInTheDocument()
    expect(screen.queryByText(/kube-state-metrics 未接入/)).not.toBeInTheDocument()
  })
})
