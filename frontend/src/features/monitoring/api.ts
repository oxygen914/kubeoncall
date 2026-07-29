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

export interface MonitoringScope {
  cluster: string
  environment?: string
  namespace?: string
}

export interface MonitoringScopeValue {
  value: string
  label: string
  cluster: string | null
  environment: string | null
  resourceCount: number
}

export interface MonitoringScopeCatalog {
  clusters: MonitoringScopeValue[]
  environments: MonitoringScopeValue[]
  namespaces: MonitoringScopeValue[]
  capabilities: {
    clusterFilterAvailable: boolean
    environmentFilterAvailable: boolean
    namespaceFilterAvailable: boolean
  }
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

export interface HealthPoint {
  timestamp: string
  readyPercent: number
  abnormalPods: number
  healthScore: number
}

export interface HealthTrend {
  scope: MonitoringScope
  window: '1h' | '6h' | '24h' | '7d' | '30d'
  stepSeconds: number
  current: HealthPoint[]
  previous: HealthPoint[]
  comparison: {
    currentAverage: number | null
    previousAverage: number | null
    delta: number | null
    direction: 'IMPROVING' | 'DEGRADING' | 'STABLE' | 'UNAVAILABLE'
    baselineAvailable: boolean
  }
  collectedAt: string
}

export interface AlarmChangeCorrelation {
  alarmId: string
  alertName: string
  severity: string
  status: string
  resourceName: string
  firstSeen: string
  changes: Array<{
    changeId: string
    changeType: string
    resourceName: string
    namespace: string | null
    changedAt: string
    score: number
    reason: string
    suggestions: string[]
  }>
}

export interface CorrelationFeed {
  scope: MonitoringScope
  alarmDataAvailable: boolean
  changeDataAvailable: boolean
  correlations: AlarmChangeCorrelation[]
  collectedAt: string
}

export interface OperationsAdvice {
  id: string
  title: string
  risk: string
  summary: string
  evidence: string
  recommendation: string
  source: 'MODEL' | 'RULE_ENGINE_FALLBACK'
  analysisPath: string
}

export interface AdviceFeed {
  scope: MonitoringScope
  generatedBy: 'CONFIGURED_LLM' | 'RULE_ENGINE_FALLBACK'
  modelAvailable: boolean
  safetyMode: 'READ_ONLY'
  advice: OperationsAdvice[]
  collectedAt: string
}

export function getMonitoringClusters(): Promise<MonitoringClusterList> {
  return api.get<MonitoringClusterList>('/api/v1/monitoring/clusters')
}

export function getMonitoringScopes(
  selection: Partial<MonitoringScope> = {},
): Promise<MonitoringScopeCatalog> {
  return api.get<MonitoringScopeCatalog>('/api/v1/monitoring/scopes', {
    query: { cluster: selection.cluster, environment: selection.environment },
  })
}

export function getMonitoringSummary(scope: string | MonitoringScope): Promise<MonitoringSummary> {
  return api.get<MonitoringSummary>('/api/v1/monitoring/summary', {
    query: scopeQuery(scope),
  })
}

export function getMonitoringNodes(scope: string | MonitoringScope): Promise<MonitoringNodeList> {
  return api.get<MonitoringNodeList>('/api/v1/monitoring/nodes', {
    query: scopeQuery(scope, false),
  })
}

export function getMonitoringPods(
  scope: string | MonitoringScope,
  phase?: string,
): Promise<MonitoringPodList> {
  return api.get<MonitoringPodList>('/api/v1/monitoring/pods', {
    query: { ...scopeQuery(scope), phase: phase || undefined, limit: 100 },
  })
}

export function getNodeCpuTrend(
  scope: string | MonitoringScope,
  node: string,
  window: '15m' | '1h' | '6h',
): Promise<CpuTrend> {
  return api.get<CpuTrend>(`/api/v1/monitoring/nodes/${encodeURIComponent(node)}/cpu`, {
    query: { ...scopeQuery(scope, false), window },
  })
}

export function getHealthTrend(
  scope: MonitoringScope,
  window: HealthTrend['window'],
): Promise<HealthTrend> {
  return api.get<HealthTrend>('/api/v1/monitoring/health/trend', {
    query: { ...scopeQuery(scope), window },
  })
}

export function getMonitoringCorrelations(
  scope: MonitoringScope,
  window: HealthTrend['window'],
): Promise<CorrelationFeed> {
  return api.get<CorrelationFeed>('/api/v1/monitoring/correlations', {
    query: { ...scopeQuery(scope), window, limit: 10 },
  })
}

export function getOperationsAdvice(
  scope: MonitoringScope,
  window: HealthTrend['window'],
): Promise<AdviceFeed> {
  return api.get<AdviceFeed>('/api/v1/monitoring/advice', {
    query: { ...scopeQuery(scope), window },
  })
}

function scopeQuery(scope: string | MonitoringScope, includeNamespace = true) {
  if (typeof scope === 'string') return { cluster: scope }
  return {
    cluster: scope.cluster,
    environment: scope.environment || undefined,
    namespace: includeNamespace ? scope.namespace || undefined : undefined,
  }
}
