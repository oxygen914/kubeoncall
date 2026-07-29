import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getMonitoringClusters,
  getHealthTrend,
  getMonitoringNodes,
  getMonitoringPods,
  getNodeCpuTrend,
} from '../api'

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

describe('monitoring API', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockResolvedValue(jsonResponse({}))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses only controlled monitoring endpoints and encoded parameters', async () => {
    await getMonitoringClusters()
    await getMonitoringNodes('prod:cn')
    await getMonitoringPods('prod:cn', 'Pending')
    await getNodeCpuTrend('prod:cn', 'worker/1', '1h')
    await getHealthTrend({ cluster: 'prod:cn', namespace: 'payments' }, '30d')

    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/v1/monitoring/clusters',
      '/api/v1/monitoring/nodes?cluster=prod%3Acn',
      '/api/v1/monitoring/pods?cluster=prod%3Acn&phase=Pending&limit=100',
      '/api/v1/monitoring/nodes/worker%2F1/cpu?cluster=prod%3Acn&window=1h',
      '/api/v1/monitoring/health/trend?cluster=prod%3Acn&namespace=payments&window=30d',
    ])
  })
})
