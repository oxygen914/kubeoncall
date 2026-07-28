import { api } from '@/api/client'

export interface MonitoringDataSources {
  nodeMetricsAvailable: boolean
  kubernetesStateAvailable: boolean
}

export interface MonitoringCluster {
  name: string
  nodeMetricsAvailable: boolean
  kubernetesStateAvailable: boolean
  nodeCount: number
}

export interface MonitoringClusterList {
  clusters: MonitoringCluster[]
  collectedAt: string
}

export interface MonitoringSummary {
  cluster: string
  totalNodes: number
  readyNodes: number
  notReadyNodes: number
  unknownNodes: number
  totalPods: number | null
  podPhaseCounts: Record<string, number>
  averageCpuUsagePercent: number | null
  dataSources: MonitoringDataSources
  collectedAt: string
}

export interface MonitoringNode {
  name: string
  ready: 'READY' | 'NOT_READY' | 'UNKNOWN'
  exporterUp: boolean | null
  cpuUsagePercent: number | null
  memoryUsagePercent: number | null
  podCount: number | null
}

export interface MonitoringNodeList {
  cluster: string
  dataSources: MonitoringDataSources
  nodes: MonitoringNode[]
  collectedAt: string
}

export interface MonitoringPod {
  namespace: string
  name: string
  node: string
  phase: string
  restartCount: number
}

export interface MonitoringPodList {
  cluster: string
  kubernetesStateAvailable: boolean
  pods: MonitoringPod[]
  returned: number
  collectedAt: string
}

export interface CpuPoint {
  timestamp: string
  value: number
}

export interface CpuTrend {
  cluster: string
  node: string
  window: '15m' | '1h' | '6h'
  nodeMetricsAvailable: boolean
  points: CpuPoint[]
  collectedAt: string
}

export function getMonitoringClusters(): Promise<MonitoringClusterList> {
  return api.get<MonitoringClusterList>('/api/v1/monitoring/clusters')
}

export function getMonitoringSummary(cluster: string): Promise<MonitoringSummary> {
  return api.get<MonitoringSummary>('/api/v1/monitoring/summary', { query: { cluster } })
}

export function getMonitoringNodes(cluster: string): Promise<MonitoringNodeList> {
  return api.get<MonitoringNodeList>('/api/v1/monitoring/nodes', { query: { cluster } })
}

export function getMonitoringPods(cluster: string, phase?: string): Promise<MonitoringPodList> {
  return api.get<MonitoringPodList>('/api/v1/monitoring/pods', {
    query: { cluster, phase: phase || undefined, limit: 100 },
  })
}

export function getNodeCpuTrend(
  cluster: string,
  node: string,
  window: '15m' | '1h' | '6h',
): Promise<CpuTrend> {
  return api.get<CpuTrend>(`/api/v1/monitoring/nodes/${encodeURIComponent(node)}/cpu`, {
    query: { cluster, window },
  })
}
