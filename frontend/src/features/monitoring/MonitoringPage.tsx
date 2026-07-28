import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import {
  getMonitoringClusters,
  getMonitoringNodes,
  getMonitoringPods,
  getMonitoringSummary,
  getNodeCpuTrend,
  type CpuPoint,
  type MonitoringNode,
  type MonitoringPod,
} from './api'

const REFRESH_INTERVAL = 15_000

export function MonitoringPage() {
  const queryClient = useQueryClient()
  const [cluster, setCluster] = useState('')
  const [selectedNode, setSelectedNode] = useState('')
  const [cpuWindow, setCpuWindow] = useState<'15m' | '1h' | '6h'>('15m')
  const [podPhase, setPodPhase] = useState('')

  const clustersQuery = useQuery({
    queryKey: ['monitoring', 'clusters'],
    queryFn: getMonitoringClusters,
    refetchInterval: REFRESH_INTERVAL,
  })
  const clusters = useMemo(() => clustersQuery.data?.clusters ?? [], [clustersQuery.data])

  useEffect(() => {
    if (!cluster && clusters[0]) setCluster(clusters[0].name)
    if (cluster && clusters.length > 0 && !clusters.some((item) => item.name === cluster)) {
      setCluster(clusters[0]!.name)
    }
  }, [cluster, clusters])

  const summaryQuery = useQuery({
    queryKey: ['monitoring', 'summary', cluster],
    queryFn: () => getMonitoringSummary(cluster),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const nodesQuery = useQuery({
    queryKey: ['monitoring', 'nodes', cluster],
    queryFn: () => getMonitoringNodes(cluster),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const podsQuery = useQuery({
    queryKey: ['monitoring', 'pods', cluster, podPhase],
    queryFn: () => getMonitoringPods(cluster, podPhase),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const nodes = useMemo(() => nodesQuery.data?.nodes ?? [], [nodesQuery.data])

  useEffect(() => {
    if (!selectedNode && nodes[0]) setSelectedNode(nodes[0].name)
    if (selectedNode && nodes.length > 0 && !nodes.some((node) => node.name === selectedNode)) {
      setSelectedNode(nodes[0]!.name)
    }
  }, [nodes, selectedNode])

  const cpuQuery = useQuery({
    queryKey: ['monitoring', 'cpu', cluster, selectedNode, cpuWindow],
    queryFn: () => getNodeCpuTrend(cluster, selectedNode, cpuWindow),
    enabled: Boolean(cluster && selectedNode),
    refetchInterval: REFRESH_INTERVAL,
  })

  const loading =
    clustersQuery.isLoading ||
    (Boolean(cluster) && (summaryQuery.isLoading || nodesQuery.isLoading || podsQuery.isLoading))
  const error =
    clustersQuery.error ?? summaryQuery.error ?? nodesQuery.error ?? podsQuery.error ?? null

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['monitoring'] })

  return (
    <section className="koc-page">
      <header className="koc-page__header koc-monitoring__header">
        <div>
          <h1>集群态势</h1>
          <p className="koc-page__subtitle">
            查看节点健康、CPU/内存、Pod 阶段，并为告警诊断提供实时上下文。
          </p>
        </div>
        <div className="koc-page__actions">
          <Button variant="secondary" onClick={refresh}>
            立即刷新
          </Button>
          <GrafanaLink />
        </div>
      </header>

      <div className="koc-filters">
        <div className="koc-monitoring__scope" aria-label="当前监控范围">
          <span>集群</span>
          <strong>{cluster || '发现中'}</strong>
          <span>{clusters[0] ? `${clusters[0].nodeCount} 个节点` : '等待 Prometheus'}</span>
        </div>
        <label className="koc-filter">
          <span>Pod 阶段</span>
          <select value={podPhase} onChange={(event) => setPodPhase(event.target.value)}>
            <option value="">全部（异常优先）</option>
            <option value="Pending">Pending</option>
            <option value="Running">Running</option>
            <option value="Succeeded">Succeeded</option>
            <option value="Failed">Failed</option>
            <option value="Unknown">Unknown</option>
          </select>
        </label>
      </div>

      <AsyncState
        isLoading={loading}
        error={error}
        isEmpty={!loading && !error && clusters.length === 0}
        emptyMessage="Prometheus 中尚未发现带 cluster/node 标签的节点，请先接入 Node Exporter。"
      >
        {cluster && summaryQuery.data && nodesQuery.data && podsQuery.data ? (
          <div className="koc-monitoring">
            <DataSourceStatus
              nodeMetricsAvailable={summaryQuery.data.dataSources.nodeMetricsAvailable}
              kubernetesStateAvailable={summaryQuery.data.dataSources.kubernetesStateAvailable}
              collectedAt={summaryQuery.data.collectedAt}
            />
            <DataSourceNotices
              nodeMetricsAvailable={summaryQuery.data.dataSources.nodeMetricsAvailable}
              kubernetesStateAvailable={summaryQuery.data.dataSources.kubernetesStateAvailable}
            />
            <SummaryCards summary={summaryQuery.data} />
            <PodPhaseSummary
              counts={summaryQuery.data.podPhaseCounts}
              available={summaryQuery.data.dataSources.kubernetesStateAvailable}
            />
            <section className="koc-monitoring__panel">
              <div className="koc-monitoring__panel-title">
                <div>
                  <h2>节点状态</h2>
                  <p>选择节点可查看 CPU 短期趋势。</p>
                </div>
              </div>
              <NodeTable
                nodes={nodesQuery.data.nodes}
                selectedNode={selectedNode}
                onSelect={setSelectedNode}
              />
            </section>

            <section className="koc-monitoring__panel">
              <div className="koc-monitoring__panel-title">
                <div>
                  <h2>{selectedNode ? `${selectedNode} CPU 趋势` : 'CPU 趋势'}</h2>
                  <p>用于诊断和处置前后状态对比，不替代 Grafana 长期分析。</p>
                </div>
                <label className="koc-filter">
                  <span>窗口</span>
                  <select
                    value={cpuWindow}
                    onChange={(event) => setCpuWindow(event.target.value as '15m' | '1h' | '6h')}
                  >
                    <option value="15m">15 分钟</option>
                    <option value="1h">1 小时</option>
                    <option value="6h">6 小时</option>
                  </select>
                </label>
              </div>
              <AsyncState
                isLoading={cpuQuery.isLoading}
                error={cpuQuery.error}
                isEmpty={!selectedNode || !cpuQuery.data?.points.length}
                emptyMessage="当前节点没有可用 CPU 趋势数据。"
              >
                <CpuChart points={cpuQuery.data?.points ?? []} />
              </AsyncState>
            </section>

            <section className="koc-monitoring__panel">
              <div className="koc-monitoring__panel-title">
                <div>
                  <h2>Pod 状态</h2>
                  <p>最多返回 100 个 Pod，非 Running 状态优先展示。</p>
                </div>
              </div>
              {podsQuery.data.kubernetesStateAvailable ? (
                <PodTable pods={podsQuery.data.pods} />
              ) : (
                <p className="koc-monitoring__notice" data-tone="warning">
                  Pod 状态不可用：请部署 kube-state-metrics，并确保 Prometheus 写入 cluster/node
                  标签。
                </p>
              )}
            </section>
          </div>
        ) : null}
      </AsyncState>
    </section>
  )
}

function SummaryCards({ summary }: { summary: NonNullable<ReturnTypeData> }) {
  const abnormalPods =
    (summary.podPhaseCounts.Pending ?? 0) +
    (summary.podPhaseCounts.Failed ?? 0) +
    (summary.podPhaseCounts.Unknown ?? 0)
  return (
    <div className="koc-overview__cards">
      <MetricCard
        label="Ready 节点"
        value={`${summary.readyNodes}/${summary.totalNodes}`}
        tone={
          summary.notReadyNodes > 0 ? 'danger' : summary.unknownNodes > 0 ? 'warning' : 'success'
        }
      />
      <MetricCard
        label="NotReady 节点"
        value={String(summary.notReadyNodes)}
        tone={
          summary.notReadyNodes > 0 ? 'danger' : summary.unknownNodes > 0 ? 'warning' : 'success'
        }
      />
      <MetricCard
        label="Pod / 异常"
        value={summary.totalPods === null ? '—' : `${summary.totalPods} / ${abnormalPods}`}
        tone={abnormalPods > 0 ? 'warning' : 'info'}
      />
      <MetricCard
        label="平均 CPU"
        value={formatPercent(summary.averageCpuUsagePercent)}
        tone={
          summary.averageCpuUsagePercent !== null && summary.averageCpuUsagePercent >= 85
            ? 'danger'
            : 'info'
        }
      />
    </div>
  )
}

type ReturnTypeData = Awaited<ReturnType<typeof getMonitoringSummary>>

function DataSourceStatus({
  nodeMetricsAvailable,
  kubernetesStateAvailable,
  collectedAt,
}: {
  nodeMetricsAvailable: boolean
  kubernetesStateAvailable: boolean
  collectedAt: string
}) {
  return (
    <section className="koc-monitoring__sources" aria-label="监控数据源状态">
      <div>
        <span>Node Exporter</span>
        <StatusBadge tone={nodeMetricsAvailable ? 'success' : 'danger'}>
          {nodeMetricsAvailable ? '已连接' : '不可用'}
        </StatusBadge>
      </div>
      <div>
        <span>kube-state-metrics</span>
        <StatusBadge tone={kubernetesStateAvailable ? 'success' : 'warning'}>
          {kubernetesStateAvailable ? '已连接' : '未接入'}
        </StatusBadge>
      </div>
      <div>
        <span>采集时间</span>
        <strong>{formatDateTime(collectedAt)}</strong>
      </div>
    </section>
  )
}

function PodPhaseSummary({
  counts,
  available,
}: {
  counts: Record<string, number>
  available: boolean
}) {
  if (!available) return null
  const phases = [
    { name: 'Running', tone: 'success' as const },
    { name: 'Pending', tone: 'warning' as const },
    { name: 'Failed', tone: 'danger' as const },
    { name: 'Succeeded', tone: 'info' as const },
  ]
  return (
    <section className="koc-monitoring__panel">
      <div className="koc-monitoring__panel-title">
        <div>
          <h2>Pod 阶段分布</h2>
          <p>来自 kube-state-metrics，异常阶段会在下方列表中优先展示。</p>
        </div>
      </div>
      <div className="koc-monitoring__phase-grid">
        {phases.map((phase) => (
          <MetricCard
            key={phase.name}
            label={phase.name}
            value={String(counts[phase.name] ?? 0)}
            tone={phase.tone}
          />
        ))}
      </div>
    </section>
  )
}

function MetricCard({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone: 'danger' | 'warning' | 'info' | 'success'
}) {
  return (
    <div className="koc-overview__card">
      <span className="koc-overview__value" data-tone={tone}>
        {value}
      </span>
      <span className="koc-overview__label">{label}</span>
    </div>
  )
}

function DataSourceNotices({
  nodeMetricsAvailable,
  kubernetesStateAvailable,
}: {
  nodeMetricsAvailable: boolean
  kubernetesStateAvailable: boolean
}) {
  if (nodeMetricsAvailable && kubernetesStateAvailable) return null
  return (
    <div className="koc-monitoring__notices" role="status">
      {!nodeMetricsAvailable ? (
        <p className="koc-monitoring__notice" data-tone="danger">
          Node Exporter 指标不可用，CPU、内存和 Exporter 状态无法判断。
        </p>
      ) : null}
      {!kubernetesStateAvailable ? (
        <p className="koc-monitoring__notice" data-tone="warning">
          kube-state-metrics 未接入，Node Ready、Pod phase 和重启次数显示为未知。
        </p>
      ) : null}
    </div>
  )
}

function NodeTable({
  nodes,
  selectedNode,
  onSelect,
}: {
  nodes: MonitoringNode[]
  selectedNode: string
  onSelect: (node: string) => void
}) {
  if (nodes.length === 0) return <p className="koc-empty">当前集群没有可展示的节点。</p>
  return (
    <div className="koc-monitoring__table-wrap">
      <table className="koc-table">
        <thead>
          <tr>
            <th>节点</th>
            <th>Ready</th>
            <th>CPU</th>
            <th>内存</th>
            <th>Pod 数</th>
            <th>Exporter</th>
          </tr>
        </thead>
        <tbody>
          {nodes.map((node) => (
            <tr key={node.name} data-selected={node.name === selectedNode}>
              <td>
                <button
                  className="koc-monitoring__node-button"
                  type="button"
                  onClick={() => onSelect(node.name)}
                >
                  {node.name}
                </button>
              </td>
              <td>
                <StatusBadge tone={readyTone(node.ready)}>{readyLabel(node.ready)}</StatusBadge>
              </td>
              <td>{formatPercent(node.cpuUsagePercent)}</td>
              <td>{formatPercent(node.memoryUsagePercent)}</td>
              <td>{node.podCount ?? '—'}</td>
              <td>
                <StatusBadge tone={exporterTone(node.exporterUp)}>
                  {node.exporterUp === null ? '未知' : node.exporterUp ? 'UP' : 'DOWN'}
                </StatusBadge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PodTable({ pods }: { pods: MonitoringPod[] }) {
  if (pods.length === 0) return <p className="koc-empty">当前筛选条件下没有 Pod。</p>
  return (
    <div className="koc-monitoring__table-wrap">
      <table className="koc-table">
        <thead>
          <tr>
            <th>Namespace / Pod</th>
            <th>节点</th>
            <th>阶段</th>
            <th>重启次数</th>
          </tr>
        </thead>
        <tbody>
          {pods.map((pod) => (
            <tr key={`${pod.namespace}/${pod.name}`}>
              <td>
                <strong>{pod.namespace}</strong>
                <span className="koc-monitoring__pod-name">{pod.name}</span>
              </td>
              <td className="koc-mono">{pod.node || '—'}</td>
              <td>
                <StatusBadge tone={phaseTone(pod.phase)}>{pod.phase}</StatusBadge>
              </td>
              <td>{pod.restartCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CpuChart({ points }: { points: CpuPoint[] }) {
  const polyline = useMemo(() => {
    if (points.length === 0) return ''
    return points
      .map((point, index) => {
        const x = points.length === 1 ? 300 : (index / (points.length - 1)) * 580 + 10
        const y = 165 - Math.max(0, Math.min(100, point.value)) * 1.45
        return `${x},${y}`
      })
      .join(' ')
  }, [points])
  const latest = points.at(-1)

  return (
    <div className="koc-monitoring__chart">
      <div className="koc-monitoring__chart-legend">
        <span>100%</span>
        <strong>当前 {formatPercent(latest?.value ?? null)}</strong>
      </div>
      <svg viewBox="0 0 600 180" role="img" aria-label="节点 CPU 使用率趋势">
        <line x1="10" y1="20" x2="590" y2="20" />
        <line x1="10" y1="92.5" x2="590" y2="92.5" />
        <line x1="10" y1="165" x2="590" y2="165" />
        <polyline points={polyline} />
      </svg>
      <div className="koc-monitoring__chart-time">
        <span>{formatTime(points[0]?.timestamp)}</span>
        <span>{formatTime(latest?.timestamp)}</span>
      </div>
    </div>
  )
}

function GrafanaLink() {
  const href =
    typeof window !== 'undefined' && ['127.0.0.1', 'localhost'].includes(window.location.hostname)
      ? `${window.location.protocol}//${window.location.hostname}:3000`
      : null
  return href ? (
    <a
      className="koc-btn koc-btn--secondary koc-btn--md"
      href={href}
      target="_blank"
      rel="noreferrer"
    >
      Grafana 深度分析
    </a>
  ) : null
}

function formatPercent(value: number | null): string {
  return value === null ? '—' : `${value.toFixed(2)}%`
}

function formatTime(value?: string): string {
  if (!value) return '—'
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString([], {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function readyLabel(value: MonitoringNode['ready']): string {
  if (value === 'READY') return 'Ready'
  if (value === 'NOT_READY') return 'NotReady'
  return '未知'
}

function readyTone(value: MonitoringNode['ready']): StatusTone {
  if (value === 'READY') return 'success'
  if (value === 'NOT_READY') return 'danger'
  return 'neutral'
}

function exporterTone(value: boolean | null): StatusTone {
  if (value === true) return 'success'
  if (value === false) return 'danger'
  return 'neutral'
}

function phaseTone(phase: string): StatusTone {
  if (phase === 'Running' || phase === 'Succeeded') return 'success'
  if (phase === 'Pending') return 'warning'
  if (phase === 'Failed' || phase === 'Unknown') return 'danger'
  return 'neutral'
}
