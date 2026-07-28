import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MonitoringPage } from '../MonitoringPage'

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
      <MonitoringPage />
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
      if (path === '/api/v1/monitoring/clusters') {
        return Promise.resolve(
          jsonResponse({
            clusters: [
              {
                name: 'prod',
                nodeMetricsAvailable: true,
                kubernetesStateAvailable,
                nodeCount: 1,
              },
            ],
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
    expect(screen.queryByRole('combobox', { name: '集群' })).not.toBeInTheDocument()
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
