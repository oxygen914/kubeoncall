import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { getGrafanaDashboardUrl } from '@/lib/grafana'
import {
  getHealthTrend,
  getMonitoringCorrelations,
  getMonitoringNodes,
  getOperationsAdvice,
  getMonitoringPods,
  getMonitoringSummary,
  getNodeCpuTrend,
  type AlarmChangeCorrelation,
  type CpuPoint,
  type HealthPoint,
  type MonitoringNode,
  type OperationsAdvice,
  type MonitoringPod,
} from './api'
import { useMonitoringScope } from './monitoringScopeContext'

const REFRESH_INTERVAL = 15_000

export function MonitoringPage() {
  const queryClient = useQueryClient()
  const { session } = useSession()
  const { scope, catalog, isLoading: scopeLoading, error: scopeError } = useMonitoringScope()
  const cluster = scope.cluster
  const [selectedNode, setSelectedNode] = useState('')
  const [cpuWindow, setCpuWindow] = useState<'15m' | '1h' | '6h'>('15m')
  const [healthWindow, setHealthWindow] = useState<'1h' | '6h' | '24h' | '7d' | '30d'>('6h')
  const [podPhase, setPodPhase] = useState('')
  const canReadAlarms = hasPermission(session, PERMISSIONS.ALARM_READ)
  const canReadChanges = hasPermission(session, PERMISSIONS.CHANGE_READ)
  const canAsk = hasPermission(session, PERMISSIONS.ASK_EXECUTE)
  const canReadCorrelations = canReadAlarms && canReadChanges

  const summaryQuery = useQuery({
    queryKey: ['monitoring', 'summary', scope],
    queryFn: () => getMonitoringSummary(scope),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const nodesQuery = useQuery({
    queryKey: ['monitoring', 'nodes', scope.cluster, scope.environment],
    queryFn: () => getMonitoringNodes(scope),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const podsQuery = useQuery({
    queryKey: ['monitoring', 'pods', scope, podPhase],
    queryFn: () => getMonitoringPods(scope, podPhase),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const healthQuery = useQuery({
    queryKey: ['monitoring', 'health', scope, healthWindow],
    queryFn: () => getHealthTrend(scope, healthWindow),
    enabled: Boolean(cluster),
    refetchInterval: REFRESH_INTERVAL,
  })
  const correlationsQuery = useQuery({
    queryKey: ['monitoring', 'correlations', scope, healthWindow],
    queryFn: () => getMonitoringCorrelations(scope, healthWindow),
    enabled: Boolean(cluster && canReadCorrelations),
    refetchInterval: REFRESH_INTERVAL,
  })
  const adviceQuery = useQuery({
    queryKey: ['monitoring', 'advice', scope, healthWindow],
    queryFn: () => getOperationsAdvice(scope, healthWindow),
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
    queryKey: ['monitoring', 'cpu', scope.cluster, scope.environment, selectedNode, cpuWindow],
    queryFn: () => getNodeCpuTrend(scope, selectedNode, cpuWindow),
    enabled: Boolean(cluster && selectedNode),
    refetchInterval: REFRESH_INTERVAL,
  })

  const loading =
    scopeLoading ||
    (Boolean(cluster) && (summaryQuery.isLoading || nodesQuery.isLoading || podsQuery.isLoading))
  const error = scopeError ?? summaryQuery.error ?? nodesQuery.error ?? podsQuery.error ?? null

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
          <span>
            {scope.environment || '全部环境'} / {scope.namespace || '全部 Namespace'}
          </span>
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
        isEmpty={!loading && !error && (catalog?.clusters.length ?? 0) === 0}
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
                  <h2>集群健康趋势</h2>
                  <p>当前窗口与紧邻上一周期使用相同口径，可直接查看环比基线。</p>
                </div>
                <label className="koc-filter">
                  <span>窗口</span>
                  <select
                    value={healthWindow}
                    onChange={(event) =>
                      setHealthWindow(event.target.value as '1h' | '6h' | '24h' | '7d' | '30d')
                    }
                  >
                    <option value="1h">1 小时</option>
                    <option value="6h">6 小时</option>
                    <option value="24h">24 小时</option>
                    <option value="7d">7 天</option>
                    <option value="30d">30 天</option>
                  </select>
                </label>
              </div>
              <AsyncState
                isLoading={healthQuery.isLoading}
                error={healthQuery.error}
                isEmpty={!healthQuery.data?.current.length}
                emptyMessage="当前范围尚无可用的集群健康历史指标。"
              >
                {healthQuery.data ? (
                  <HealthTrendPanel
                    current={healthQuery.data.current}
                    previous={healthQuery.data.previous}
                    comparison={healthQuery.data.comparison}
                  />
                ) : null}
              </AsyncState>
            </section>
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

            <div className="koc-monitoring__operations-grid">
              <section className="koc-monitoring__panel">
                <div className="koc-monitoring__panel-title">
                  <div>
                    <h2>告警与变更关联</h2>
                    <p>按时间、资源与 Namespace 评分，仅展示有证据的关联。</p>
                  </div>
                </div>
                {!canReadCorrelations ? (
                  <p className="koc-monitoring__notice">
                    需要告警读取和变更读取权限才能查看关联证据。
                  </p>
                ) : (
                  <AsyncState
                    isLoading={correlationsQuery.isLoading}
                    error={correlationsQuery.error}
                    isEmpty={!correlationsQuery.data?.correlations.length}
                    emptyMessage="当前窗口未发现可解释的告警与变更关联。"
                  >
                    <CorrelationList correlations={correlationsQuery.data?.correlations ?? []} />
                  </AsyncState>
                )}
              </section>

              <section className="koc-monitoring__panel">
                <div className="koc-monitoring__panel-title">
                  <div>
                    <h2>AI 运营建议</h2>
                    <p>独立只读接口输出研判与建议，不会直接触发处置。</p>
                  </div>
                  {adviceQuery.data ? (
                    <StatusBadge tone={adviceQuery.data.modelAvailable ? 'info' : 'neutral'}>
                      {adviceQuery.data.modelAvailable ? '模型增强' : '规则降级'}
                    </StatusBadge>
                  ) : null}
                </div>
                <AsyncState
                  isLoading={adviceQuery.isLoading}
                  error={adviceQuery.error}
                  isEmpty={!adviceQuery.data?.advice.length}
                  emptyMessage="当前没有运营建议。"
                >
                  <AdviceList
                    advice={adviceQuery.data?.advice ?? []}
                    canAsk={canAsk}
                    canReadChanges={canReadChanges}
                  />
                </AsyncState>
              </section>
            </div>
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

function HealthTrendPanel({
  current,
  previous,
  comparison,
}: {
  current: HealthPoint[]
  previous: HealthPoint[]
  comparison: {
    currentAverage: number | null
    previousAverage: number | null
    delta: number | null
    direction: 'IMPROVING' | 'DEGRADING' | 'STABLE' | 'UNAVAILABLE'
    baselineAvailable: boolean
  }
}) {
  const currentLine = healthPolyline(current)
  const previousLine = healthPolyline(previous)
  const latest = current.at(-1)
  const comparisonLabel = comparison.baselineAvailable
    ? `${comparison.delta !== null && comparison.delta > 0 ? '+' : ''}${comparison.delta?.toFixed(2)} 分`
    : '基线不足'

  return (
    <div className="koc-monitoring__health">
      <div className="koc-monitoring__health-summary">
        <div>
          <span>当前健康分</span>
          <strong>{latest ? latest.healthScore.toFixed(2) : '—'}</strong>
        </div>
        <div>
          <span>当前周期均值</span>
          <strong>{comparison.currentAverage?.toFixed(2) ?? '—'}</strong>
        </div>
        <div>
          <span>上一周期均值</span>
          <strong>{comparison.previousAverage?.toFixed(2) ?? '—'}</strong>
        </div>
        <div>
          <span>周期环比</span>
          <StatusBadge tone={comparisonTone(comparison.direction)}>{comparisonLabel}</StatusBadge>
        </div>
      </div>
      <div className="koc-monitoring__chart">
        <div className="koc-monitoring__chart-legend" aria-hidden="true">
          <span className="koc-monitoring__legend-current">当前周期</span>
          <span className="koc-monitoring__legend-previous">上一周期</span>
        </div>
        <svg viewBox="0 0 600 180" role="img" aria-label="集群健康分当前周期与上一周期趋势">
          <line x1="10" y1="20" x2="590" y2="20" />
          <line x1="10" y1="92.5" x2="590" y2="92.5" />
          <line x1="10" y1="165" x2="590" y2="165" />
          {previousLine ? (
            <polyline className="koc-health-line--previous" points={previousLine} />
          ) : null}
          <polyline className="koc-health-line--current" points={currentLine} />
        </svg>
        <p className="koc-monitoring__chart-fallback">
          当前周期均值 {comparison.currentAverage?.toFixed(2) ?? '不可用'}；上一周期均值{' '}
          {comparison.previousAverage?.toFixed(2) ?? '不可用'}；趋势{' '}
          {comparisonDirectionLabel(comparison.direction)}。
        </p>
      </div>
    </div>
  )
}

function CorrelationList({ correlations }: { correlations: AlarmChangeCorrelation[] }) {
  return (
    <ul className="koc-monitoring__correlations">
      {correlations.map((correlation) => (
        <li key={correlation.alarmId}>
          <div>
            <StatusBadge tone={severityTone(correlation.severity)}>
              {correlation.severity}
            </StatusBadge>
            <Link to={`/alarms/${encodeURIComponent(correlation.alarmId)}`}>
              {correlation.alertName}
            </Link>
            <span className="koc-mono">{correlation.resourceName}</span>
          </div>
          {correlation.changes.map((change) => (
            <div className="koc-monitoring__change-evidence" key={change.changeId}>
              <strong>{change.changeType}</strong>
              <span>{change.reason}</span>
              <span>相关度 {Math.round(change.score * 100)}%</span>
              <time>{formatDateTime(change.changedAt)}</time>
            </div>
          ))}
        </li>
      ))}
    </ul>
  )
}

function AdviceList({
  advice,
  canAsk,
  canReadChanges,
}: {
  advice: OperationsAdvice[]
  canAsk: boolean
  canReadChanges: boolean
}) {
  return (
    <ul className="koc-monitoring__advice">
      {advice.map((item) => {
        const destination = adviceDestination(item, canAsk, canReadChanges)
        return (
          <li key={item.id}>
            <div>
              <StatusBadge tone={severityTone(item.risk)}>{item.risk}</StatusBadge>
              <strong>{item.title}</strong>
              <span>{item.source === 'MODEL' ? 'AI 生成' : '规则建议'}</span>
            </div>
            <p>{item.summary}</p>
            <dl>
              <div>
                <dt>证据</dt>
                <dd>{item.evidence}</dd>
              </div>
              <div>
                <dt>建议</dt>
                <dd>{item.recommendation}</dd>
              </div>
            </dl>
            {destination ? <Link to={destination.path}>{destination.label}</Link> : null}
          </li>
        )
      })}
    </ul>
  )
}

function adviceDestination(
  item: OperationsAdvice,
  canAsk: boolean,
  canReadChanges: boolean,
): { path: string; label: string } | null {
  if (item.analysisPath === '/ask' && canAsk) {
    const question =
      `请基于当前监控范围分析“${item.title}”。现象：${item.summary}；` +
      `已有证据：${item.evidence}`
    return {
      path: `/ask?question=${encodeURIComponent(question.slice(0, 4000))}`,
      label: '发起 AI 诊断',
    }
  }
  if (item.analysisPath === '/changes' && canReadChanges) {
    return { path: '/changes', label: '查看相关变更' }
  }
  // `/monitoring` is the current page. Rendering it as a link creates a no-op interaction.
  return null
}

function healthPolyline(points: HealthPoint[]): string {
  return points
    .map((point, index) => {
      const x = points.length === 1 ? 300 : (index / (points.length - 1)) * 580 + 10
      const y = 165 - Math.max(0, Math.min(100, point.healthScore)) * 1.45
      return `${x},${y}`
    })
    .join(' ')
}

function GrafanaLink() {
  const href = getGrafanaDashboardUrl('overview')
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

function comparisonTone(
  direction: 'IMPROVING' | 'DEGRADING' | 'STABLE' | 'UNAVAILABLE',
): StatusTone {
  if (direction === 'IMPROVING') return 'success'
  if (direction === 'DEGRADING') return 'danger'
  if (direction === 'STABLE') return 'info'
  return 'neutral'
}

function comparisonDirectionLabel(
  direction: 'IMPROVING' | 'DEGRADING' | 'STABLE' | 'UNAVAILABLE',
): string {
  if (direction === 'IMPROVING') return '改善'
  if (direction === 'DEGRADING') return '下降'
  if (direction === 'STABLE') return '稳定'
  return '不可用'
}

function severityTone(severity: string): StatusTone {
  const normalized = severity.toUpperCase()
  if (normalized === 'P1' || normalized === 'CRITICAL') return 'danger'
  if (normalized === 'P2' || normalized === 'HIGH') return 'warning'
  if (normalized === 'P3' || normalized === 'MEDIUM') return 'info'
  return 'neutral'
}
