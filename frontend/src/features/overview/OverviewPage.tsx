import { lazy, Suspense, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { getOverview } from './api'
import {
  getHealthTrend,
  getMonitoringPods,
  getMonitoringSummary,
  getOperationsAdvice,
} from '@/features/monitoring/api'
import { useMonitoringScope } from '@/features/monitoring/monitoringScopeContext'
import { listAlarms } from '@/features/alarms/api'
import { listApprovals } from '@/features/approvals/api'
import { listExecutions } from '@/features/executions/api'
import { listSandboxRuns } from '@/features/sandbox/api'
import { useSession } from '@/features/auth/useSession'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { Button } from '@/components/ui/Button'
import { Icon } from '@/components/ui/Icon'
import { PageTabs, type PageTab } from '@/components/navigation/PageTabs'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import {
  AiInsightPanel,
  AlertList,
  ApprovalQueue,
  EmptyState,
  ErrorState,
  LoadingState,
  MetricCard,
  NoPermissionState,
  SandboxQueue,
  SectionPanel,
  type AiInsight,
} from './OverviewComponents'
const ExecutionTrendChart = lazy(async () => {
  const module = await import('./OverviewCharts')
  return { default: module.ExecutionTrendChart }
})
const ExecutionStatusDistribution = lazy(async () => {
  const module = await import('./OverviewCharts')
  return { default: module.ExecutionStatusDistribution }
})
const FailureReasonChart = lazy(async () => {
  const module = await import('./OverviewCharts')
  return { default: module.FailureReasonChart }
})

type RefreshMode = 'off' | '15s' | '30s' | '60s'
type OverviewWindow = '1h' | '6h' | '24h' | '7d' | '30d'
type OverviewView = 'workbench' | 'analytics'

const OVERVIEW_TABS: PageTab[] = [
  {
    id: 'workbench',
    label: '值班工作台',
    description: '风险、告警与 AI 建议',
    to: '/overview?view=workbench',
  },
  {
    id: 'analytics',
    label: '处置分析',
    description: '执行趋势、审批与 Sandbox',
    to: '/overview?view=analytics',
  },
]

export function OverviewPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const { session } = useSession()
  const { scope } = useMonitoringScope()
  const [window, setWindow] = useState<OverviewWindow>('24h')
  const cluster = scope.cluster
  const namespace = scope.namespace ?? ''
  const [refreshMode, setRefreshMode] = useState<RefreshMode>('30s')
  const refreshInterval = refreshMode === 'off' ? false : Number.parseInt(refreshMode) * 1_000
  const view: OverviewView = searchParams.get('view') === 'analytics' ? 'analytics' : 'workbench'

  const canReadAlarms = hasPermission(session, PERMISSIONS.ALARM_READ)
  const canReadApprovals = hasPermission(session, PERMISSIONS.APPROVAL_READ)
  const canReadExecutions = hasPermission(session, PERMISSIONS.EXECUTION_READ)
  const canReadSandbox = hasPermission(session, PERMISSIONS.SANDBOX_READ)
  const canReadChanges = hasPermission(session, PERMISSIONS.CHANGE_READ)
  const canAsk = hasPermission(session, PERMISSIONS.ASK_EXECUTE)

  const overviewQuery = useQuery({
    queryKey: ['overview', 'aggregate', window],
    queryFn: () => getOverview(window),
    staleTime: 15_000,
    refetchInterval: refreshInterval,
  })
  const summaryQuery = useQuery({
    queryKey: ['overview', 'monitoring-summary', scope],
    queryFn: () => getMonitoringSummary(scope),
    enabled: Boolean(cluster && view === 'workbench'),
    refetchInterval: refreshInterval,
  })
  const podsQuery = useQuery({
    queryKey: ['overview', 'monitoring-pods', scope],
    queryFn: () => getMonitoringPods(scope),
    enabled: Boolean(cluster && view === 'workbench'),
    refetchInterval: refreshInterval,
  })
  const healthQuery = useQuery({
    queryKey: ['overview', 'health-trend', scope, window],
    queryFn: () => getHealthTrend(scope, window),
    enabled: Boolean(cluster && view === 'workbench'),
    refetchInterval: refreshInterval,
  })
  const adviceQuery = useQuery({
    queryKey: ['overview', 'operations-advice', scope, window],
    queryFn: () => getOperationsAdvice(scope, window),
    enabled: Boolean(cluster && view === 'workbench'),
    refetchInterval: refreshInterval,
  })
  const alarmsQuery = useQuery({
    queryKey: ['overview', 'active-alarms', cluster, namespace],
    queryFn: () =>
      listAlarms({
        page: 1,
        size: 5,
        status: 'FIRING',
        cluster: cluster || undefined,
        namespace: namespace || undefined,
        sort: 'severity,asc',
      }),
    enabled: canReadAlarms && view === 'workbench',
    refetchInterval: refreshInterval,
  })
  const approvalsQuery = useQuery({
    queryKey: ['overview', 'pending-approvals'],
    queryFn: () => listApprovals({ page: 1, size: 5, status: 'PENDING' }),
    enabled: canReadApprovals && view === 'analytics',
    refetchInterval: refreshInterval,
  })
  const executionsQuery = useQuery({
    queryKey: ['overview', 'recent-executions'],
    queryFn: () => listExecutions({ page: 1, size: 6 }),
    enabled: canReadExecutions && view === 'analytics',
    refetchInterval: refreshInterval,
  })
  const sandboxQuery = useQuery({
    queryKey: ['overview', 'sandbox-runs'],
    queryFn: () => listSandboxRuns(),
    enabled: canReadSandbox && view === 'analytics',
    refetchInterval: refreshInterval,
  })

  const queryStates =
    view === 'workbench'
      ? [overviewQuery, summaryQuery, podsQuery, healthQuery, adviceQuery, alarmsQuery]
      : [overviewQuery, approvalsQuery, executionsQuery, sandboxQuery]
  const isRefreshing = queryStates.some((query) => query.isFetching)
  const updatedAt = Math.max(...queryStates.map((query) => query.dataUpdatedAt), 0)

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['overview'] })

  if (overviewQuery.isLoading) {
    return (
      <section className="koc-overview-page" aria-label="概览加载中">
        <LoadingState lines={8} />
      </section>
    )
  }
  if (overviewQuery.error || !overviewQuery.data) {
    return (
      <section className="koc-overview-page">
        <ErrorState title="概览聚合数据加载失败" onRetry={() => void overviewQuery.refetch()} />
      </section>
    )
  }

  const data = overviewQuery.data
  const p1 = data.severityCounts.P1 ?? 0
  const p2 = data.severityCounts.P2 ?? 0
  const abnormalWorkloads = summaryQuery.data
    ? (summaryQuery.data.podPhaseCounts.Pending ?? 0) +
      (summaryQuery.data.podPhaseCounts.Failed ?? 0) +
      (summaryQuery.data.podPhaseCounts.Unknown ?? 0)
    : null
  const executionTrend = Object.values(data.executionTrend)
  const insights = adviceQuery.data
    ? adviceQuery.data.advice.map((item) => toAiInsight(item, canAsk, canReadChanges))
    : buildInsights({
        p1,
        p2,
        failedExecutions: data.failedExecutions,
        abnormalWorkloads,
        notReadyNodes: summaryQuery.data?.notReadyNodes ?? null,
        alarm: alarmsQuery.data?.data[0],
        canReadAlarms,
        canReadExecutions,
      })

  return (
    <section className="koc-overview-page">
      <header className="koc-overview-header">
        <div>
          <p className="koc-overview-header__eyebrow">OPERATIONS / OVERVIEW</p>
          <h1>概览</h1>
          <p>
            企业级 Kubernetes 可观测性与事件处置控制台
            <span>· 监控与告警按当前范围，审批与执行保持全局聚合口径</span>
          </p>
        </div>
        <div className="koc-overview-header__meta">
          <span className="koc-live-status">
            <i aria-hidden="true" />
            {refreshMode === 'off' ? '自动刷新已暂停' : `自动刷新 ${refreshMode}`}
          </span>
          <span>最近更新 {updatedAt ? formatUpdatedAt(updatedAt) : '等待数据'}</span>
        </div>
      </header>

      <PageTabs activeId={view} label="概览视图" tabs={OVERVIEW_TABS} />

      <div className="koc-overview-toolbar" aria-label="概览时间与刷新设置">
        <label className="koc-overview-control">
          <span>时间窗口</span>
          <select
            aria-label="时间窗口"
            value={window}
            onChange={(event) => setWindow(event.target.value as OverviewWindow)}
          >
            <option value="1h">最近 1 小时</option>
            <option value="6h">最近 6 小时</option>
            <option value="24h">最近 24 小时</option>
            <option value="7d">最近 7 天</option>
            <option value="30d">最近 30 天</option>
          </select>
        </label>
        <label className="koc-overview-control">
          <span>自动刷新</span>
          <select
            value={refreshMode}
            onChange={(event) => setRefreshMode(event.target.value as RefreshMode)}
          >
            <option value="off">关闭</option>
            <option value="15s">15 秒</option>
            <option value="30s">30 秒</option>
            <option value="60s">60 秒</option>
          </select>
        </label>
        <Button variant="secondary" onClick={() => void refresh()} disabled={isRefreshing}>
          <Icon name="refresh" size={16} className={isRefreshing ? 'koc-icon--spinning' : ''} />
          {isRefreshing ? '刷新中' : '立即刷新'}
        </Button>
      </div>

      {view === 'workbench' ? (
        <>
          <section className="koc-overview-section" aria-labelledby="risk-summary-title">
            <div className="koc-overview-section__heading">
              <div>
                <p>01 / CURRENT RISK</p>
                <h2 id="risk-summary-title">当前风险摘要</h2>
              </div>
              <span>颜色仅表示状态语义，点击可进入证据详情</span>
            </div>
            <div className="koc-risk-grid">
              <MetricCard
                label="集群健康"
                value={clusterHealthValue(summaryQuery.data)}
                meta={clusterHealthMeta(summaryQuery.data, healthQuery.data?.comparison)}
                tone={clusterHealthTone(summaryQuery.data)}
                icon="cluster"
                onClick={() => navigate('/monitoring?view=overview')}
              />
              <MetricCard
                label="P1 / P2 活跃告警"
                value={`${p1} / ${p2}`}
                meta="同期基线未提供"
                tone={p1 > 0 ? 'danger' : p2 > 0 ? 'warning' : 'success'}
                icon="alarm"
                onClick={canReadAlarms ? () => navigate('/alarms') : undefined}
              />
              <MetricCard
                label="异常工作负载"
                value={abnormalWorkloads ?? '—'}
                meta={
                  abnormalWorkloads === null
                    ? 'kube-state-metrics 数据不可用'
                    : 'Pending / Failed / Unknown'
                }
                tone={
                  abnormalWorkloads === null
                    ? 'neutral'
                    : abnormalWorkloads > 0
                      ? 'warning'
                      : 'success'
                }
                icon="box"
                onClick={() => navigate('/monitoring?view=workloads')}
              />
              <MetricCard
                label="待审批"
                value={data.pendingApprovals}
                meta="同期基线未提供"
                tone={data.pendingApprovals > 0 ? 'warning' : 'success'}
                icon="approval"
                onClick={canReadApprovals ? () => navigate('/approvals') : undefined}
              />
              <MetricCard
                label="正在执行"
                value={data.runningExecutions}
                meta="当前窗口总执行量趋势"
                tone="info"
                trend={executionTrend}
                icon="execution"
                onClick={canReadExecutions ? () => navigate('/executions') : undefined}
              />
              <MetricCard
                label="失败执行"
                value={data.failedExecutions}
                meta="当前窗口总执行量趋势"
                tone={data.failedExecutions > 0 ? 'danger' : 'success'}
                trend={executionTrend}
                icon="activity"
                onClick={
                  canReadExecutions ? () => navigate('/executions?status=FAILED') : undefined
                }
              />
            </div>
          </section>

          <section className="koc-overview-section" aria-labelledby="monitoring-title">
            <div className="koc-overview-section__heading">
              <div>
                <p>02 / OBSERVABILITY</p>
                <h2 id="monitoring-title">监控态势</h2>
              </div>
              <span>实时快照与活跃风险队列</span>
            </div>
            <div className="koc-overview-grid">
              <SectionPanel
                title="集群健康与工作负载"
                description="实时快照与同口径上一周期健康基线。"
                className="koc-span-7"
                action={
                  <PanelLink
                    label="查看集群态势"
                    onClick={() => navigate('/monitoring?view=overview')}
                  />
                }
              >
                <ClusterHealthPanel
                  isLoading={summaryQuery.isLoading || podsQuery.isLoading}
                  isError={Boolean(summaryQuery.error || podsQuery.error)}
                  cluster={cluster}
                  summary={summaryQuery.data}
                  abnormalPods={
                    podsQuery.data?.pods.filter((pod) =>
                      ['Pending', 'Failed', 'Unknown'].includes(pod.phase),
                    ) ?? []
                  }
                  comparison={healthQuery.data?.comparison}
                  onRetry={() => {
                    void summaryQuery.refetch()
                    void podsQuery.refetch()
                    void healthQuery.refetch()
                  }}
                />
              </SectionPanel>
              <SectionPanel
                title="活跃告警队列"
                description="P1 优先；负责人来自现有确认信息，未确认时显示未认领。"
                className="koc-span-5"
                action={
                  canReadAlarms ? (
                    <PanelLink label="全部告警" onClick={() => navigate('/alarms')} />
                  ) : undefined
                }
              >
                {!canReadAlarms ? (
                  <NoPermissionState resource="告警" />
                ) : alarmsQuery.isLoading ? (
                  <LoadingState />
                ) : alarmsQuery.error ? (
                  <ErrorState onRetry={() => void alarmsQuery.refetch()} />
                ) : (
                  <AlertList alarms={alarmsQuery.data?.data ?? []} />
                )}
              </SectionPanel>
            </div>
          </section>
        </>
      ) : null}

      {view === 'analytics' ? (
        <section className="koc-overview-section" aria-labelledby="response-title">
          <div className="koc-overview-section__heading">
            <div>
              <p>03 / RESPONSE</p>
              <h2 id="response-title">响应处置</h2>
            </div>
            <span>执行结果、失败原因与人工决策队列</span>
          </div>
          <div className="koc-overview-grid">
            <SectionPanel
              title="执行趋势"
              description="按当前窗口展示执行状态堆叠趋势，旧数据源自动降级为总执行量。"
              className="koc-span-7"
              action={
                canReadExecutions ? (
                  <PanelLink label="执行中心" onClick={() => navigate('/executions')} />
                ) : undefined
              }
            >
              <Suspense fallback={<LoadingState lines={5} />}>
                <ExecutionTrendChart
                  values={data.executionTrend}
                  statusValues={data.executionStatusTrend}
                />
              </Suspense>
            </SectionPanel>
            <SectionPanel
              title="执行状态分布"
              description="按当前窗口汇总，状态同时使用文字与颜色。"
              className="koc-span-5"
            >
              <Suspense fallback={<LoadingState lines={4} />}>
                <ExecutionStatusDistribution values={data.executionStatusCounts} />
              </Suspense>
            </SectionPanel>
            <SectionPanel
              title="失败原因"
              description="仅展示实际返回的原因，不强制补足 Top 5。"
              className="koc-span-5"
            >
              <Suspense fallback={<LoadingState lines={4} />}>
                <FailureReasonChart values={data.failureReasons} />
              </Suspense>
            </SectionPanel>
            <SectionPanel
              title="最近执行"
              description="展示当前执行队列，详情保留原有路由和权限。"
              className="koc-span-7"
            >
              {!canReadExecutions ? (
                <NoPermissionState resource="执行" />
              ) : executionsQuery.isLoading ? (
                <LoadingState />
              ) : executionsQuery.error ? (
                <ErrorState onRetry={() => void executionsQuery.refetch()} />
              ) : (
                <ExecutionQueue executions={executionsQuery.data?.data ?? []} />
              )}
            </SectionPanel>
            <SectionPanel
              title="待审批任务"
              description="高风险操作继续从审批详情完成决策。"
              className="koc-span-6"
              action={
                canReadApprovals ? (
                  <PanelLink label="审批中心" onClick={() => navigate('/approvals')} />
                ) : undefined
              }
            >
              {!canReadApprovals ? (
                <NoPermissionState resource="审批" />
              ) : approvalsQuery.isLoading ? (
                <LoadingState />
              ) : approvalsQuery.error ? (
                <ErrorState onRetry={() => void approvalsQuery.refetch()} />
              ) : (
                <ApprovalQueue approvals={approvalsQuery.data?.data ?? []} />
              )}
            </SectionPanel>
            <SectionPanel
              title="最近 Sandbox 任务"
              description="隔离环境中的工具运行与清理状态。"
              className="koc-span-6"
              action={
                canReadSandbox ? (
                  <PanelLink label="Sandbox 运行" onClick={() => navigate('/sandbox-runs')} />
                ) : undefined
              }
            >
              {!canReadSandbox ? (
                <NoPermissionState resource="Sandbox 运行" />
              ) : sandboxQuery.isLoading ? (
                <LoadingState />
              ) : sandboxQuery.error ? (
                <ErrorState onRetry={() => void sandboxQuery.refetch()} />
              ) : (
                <SandboxQueue runs={sandboxQuery.data ?? []} />
              )}
            </SectionPanel>
          </div>
        </section>
      ) : null}

      {view === 'workbench' ? (
        <section className="koc-overview-section" aria-labelledby="ai-title">
          <div className="koc-overview-section__heading">
            <div>
              <p>04 / AI OPERATIONS</p>
              <h2 id="ai-title">今日需要关注</h2>
            </div>
            <span>
              {adviceQuery.data?.generatedBy === 'CONFIGURED_LLM'
                ? '模型增强建议，保持只读且不自动执行高风险动作'
                : '确定性规则建议，不自动执行高风险动作'}
            </span>
          </div>
          <AiInsightPanel insights={insights} />
        </section>
      ) : null}
    </section>
  )
}

function ClusterHealthPanel({
  isLoading,
  isError,
  cluster,
  summary,
  abnormalPods,
  comparison,
  onRetry,
}: {
  isLoading: boolean
  isError: boolean
  cluster: string
  summary: Awaited<ReturnType<typeof getMonitoringSummary>> | undefined
  abnormalPods: Awaited<ReturnType<typeof getMonitoringPods>>['pods']
  comparison: Awaited<ReturnType<typeof getHealthTrend>>['comparison'] | undefined
  onRetry: () => void
}) {
  if (isLoading) return <LoadingState lines={5} />
  if (isError) return <ErrorState title="集群监控数据加载失败" onRetry={onRetry} />
  if (!cluster || !summary) {
    return (
      <EmptyState title="尚未发现集群" description="请先确认 Prometheus 的 cluster 标签配置。" />
    )
  }
  const readyPercent =
    summary.totalNodes === 0 ? 0 : Math.round((summary.readyNodes / summary.totalNodes) * 100)
  return (
    <div className="koc-cluster-health">
      <div className="koc-cluster-health__headline">
        <div>
          <span>当前集群</span>
          <strong className="koc-mono">{cluster}</strong>
        </div>
        <StatusBadge tone={clusterHealthTone(summary)}>
          {summary.notReadyNodes > 0
            ? '存在故障'
            : summary.unknownNodes > 0
              ? '状态不完整'
              : '健康'}
        </StatusBadge>
      </div>
      <div className="koc-cluster-health__availability">
        <div>
          <strong>{readyPercent}%</strong>
          <span>节点 Ready</span>
        </div>
        <div className="koc-cluster-health__bar" aria-label={`节点 Ready 比例 ${readyPercent}%`}>
          <span style={{ width: `${readyPercent}%` }} />
        </div>
      </div>
      <dl className="koc-cluster-health__stats">
        <div>
          <dt>可用节点</dt>
          <dd>{summary.readyNodes}</dd>
        </div>
        <div>
          <dt>异常节点</dt>
          <dd data-tone={summary.notReadyNodes > 0 ? 'danger' : 'success'}>
            {summary.notReadyNodes}
          </dd>
        </div>
        <div>
          <dt>未知节点</dt>
          <dd data-tone={summary.unknownNodes > 0 ? 'warning' : 'success'}>
            {summary.unknownNodes}
          </dd>
        </div>
        <div>
          <dt>Pod 总数</dt>
          <dd>{summary.totalPods ?? '—'}</dd>
        </div>
        <div>
          <dt>异常 Pod</dt>
          <dd data-tone={abnormalPods.length > 0 ? 'warning' : 'success'}>{abnormalPods.length}</dd>
        </div>
      </dl>
      <div className="koc-cluster-health__footer">
        <span>
          <Icon name="activity" size={15} />
          {comparison?.baselineAvailable
            ? `较上一周期 ${formatSigned(comparison.delta)} 分`
            : '上一周期健康基线不足'}
        </span>
        <span>采集于 {formatDateTime(summary.collectedAt)}</span>
      </div>
    </div>
  )
}

function ExecutionQueue({
  executions,
}: {
  executions: Awaited<ReturnType<typeof listExecutions>>['data']
}) {
  if (executions.length === 0) {
    return <EmptyState title="暂无执行记录" description="当前窗口未返回执行任务。" />
  }
  return (
    <div className="koc-execution-queue">
      <div className="koc-execution-queue__header" aria-hidden="true">
        <span>执行摘要</span>
        <span>状态</span>
        <span>开始时间</span>
        <span>耗时</span>
      </div>
      {executions.map((execution) => (
        <Link to={`/executions/${encodeURIComponent(execution.id)}`} key={execution.id}>
          <span>
            <strong title={execution.summary}>{execution.summary}</strong>
            <code>{execution.id}</code>
          </span>
          <StatusBadge tone={executionTone(execution.status)}>{execution.status}</StatusBadge>
          <time>{formatDateTime(execution.startedAt)}</time>
          <span>{formatDuration(execution.durationMs)}</span>
        </Link>
      ))}
    </div>
  )
}

function PanelLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button className="koc-panel-link" type="button" onClick={onClick}>
      {label}
      <Icon name="chevron-right" size={14} />
    </button>
  )
}

function buildInsights({
  p1,
  p2,
  failedExecutions,
  abnormalWorkloads,
  notReadyNodes,
  alarm,
  canReadAlarms,
  canReadExecutions,
}: {
  p1: number
  p2: number
  failedExecutions: number
  abnormalWorkloads: number | null
  notReadyNodes: number | null
  alarm: Awaited<ReturnType<typeof listAlarms>>['data'][number] | undefined
  canReadAlarms: boolean
  canReadExecutions: boolean
}): AiInsight[] {
  const insights: AiInsight[] = []
  if (p1 > 0 || p2 > 0) {
    insights.push({
      id: 'active-severity',
      title: '高优先级告警仍在持续',
      risk: p1 > 0 ? 'P1' : 'P2',
      summary: `当前存在 ${p1} 个 P1、${p2} 个 P2 活跃告警，需要先确认影响面与负责人。`,
      cause: '可能与工作负载异常、依赖不可用或近期变更相关，需进入告警证据链确认。',
      relatedAlarm: alarm?.alertName ?? `${p1 + p2} 个高优先级告警`,
      relatedChange: '概览接口未提供变更关联数据',
      evidence: `severityCounts: P1=${p1}, P2=${p2}`,
      action: '先查看告警详情与时间线，再从原有处置入口创建任务并保留审批。',
      analysisPath: canReadAlarms
        ? alarm
          ? `/alarms/${encodeURIComponent(alarm.id)}`
          : '/alarms'
        : undefined,
      analysisLabel: '查看告警证据',
    })
  }
  if (failedExecutions > 0) {
    insights.push({
      id: 'failed-executions',
      title: '处置执行存在失败记录',
      risk: failedExecutions >= 3 ? 'P2' : 'P3',
      summary: `当前窗口记录到 ${failedExecutions} 次失败执行，建议优先核查集中失败原因。`,
      cause: '可能为工具超时、目标状态变化、权限或 Sandbox 环境异常。',
      relatedAlarm: alarm?.alertName ?? '暂无可直接关联的告警条目',
      relatedChange: '当前聚合数据未提供变更关联',
      evidence: `failedExecutions=${failedExecutions}`,
      action: '对照失败原因与执行节点日志，必要时重新规划，不直接重复高风险动作。',
      analysisPath: canReadExecutions ? '/executions?status=FAILED' : undefined,
      analysisLabel: '查看失败执行',
    })
  }
  if ((abnormalWorkloads ?? 0) > 0 || (notReadyNodes ?? 0) > 0) {
    insights.push({
      id: 'cluster-degradation',
      title: '集群工作负载健康度下降',
      risk: (notReadyNodes ?? 0) > 0 ? 'P2' : 'P3',
      summary: `发现 ${notReadyNodes ?? 0} 个 NotReady 节点、${abnormalWorkloads ?? 0} 个异常工作负载。`,
      cause: '可能为节点资源压力、调度失败、镜像拉取或容器反复重启。',
      relatedAlarm: alarm?.alertName ?? '当前队列暂无直接关联告警',
      relatedChange: '需在变更事件页按时间窗口核对',
      evidence: `notReadyNodes=${notReadyNodes ?? 0}, abnormalWorkloads=${abnormalWorkloads ?? 0}`,
      action: '进入集群态势查看节点与 Pod 证据，再决定是否需要发起处置。',
      analysisPath: '/monitoring',
      analysisLabel: '查看集群证据',
      handlingPath: alarm ? `/alarms/${encodeURIComponent(alarm.id)}` : undefined,
      handlingLabel: '查看告警并处置',
    })
  }
  if (insights.length === 0) {
    insights.push({
      id: 'stable',
      title: '当前未发现需要升级处置的风险',
      risk: 'INFO',
      summary: '高优先级告警、失败执行和已知集群异常均为零或暂无数据。',
      cause: '当前仅能基于已接入的数据源判断，仍需关注数据源完整性。',
      relatedAlarm: '无',
      relatedChange: '未接入概览聚合',
      evidence: 'P1/P2=0, failedExecutions=0',
      action: '保持自动刷新，并按值班节奏检查告警与变更事件。',
    })
  }
  return insights.slice(0, 3)
}

function toAiInsight(
  item: Awaited<ReturnType<typeof getOperationsAdvice>>['advice'][number],
  canAsk: boolean,
  canReadChanges: boolean,
): AiInsight {
  const destination =
    item.analysisPath === '/ask' && canAsk
      ? {
          path: createAskPath(item.title, item.summary, item.evidence),
          label: '发起 AI 诊断',
        }
      : item.analysisPath === '/changes' && canReadChanges
        ? { path: '/changes', label: '查看相关变更' }
        : item.analysisPath === '/monitoring'
          ? { path: '/monitoring', label: '查看监控证据' }
          : null
  return {
    id: item.id,
    title: item.title,
    risk: ['P1', 'P2', 'P3'].includes(item.risk) ? (item.risk as 'P1' | 'P2' | 'P3') : 'INFO',
    summary: item.summary,
    cause:
      item.source === 'MODEL' ? '由已配置模型基于当前监控证据生成。' : '由后端确定性运营规则生成。',
    relatedAlarm: '详见告警与变更关联面板',
    relatedChange: '仅在具备对应权限时纳入研判',
    evidence: item.evidence,
    action: item.recommendation,
    analysisPath: destination?.path,
    analysisLabel: destination?.label,
    source: item.source,
  }
}

function createAskPath(title: string, summary: string, evidence: string): string {
  const question = `请基于当前监控范围分析“${title}”。现象：${summary}；已有证据：${evidence}`
  return `/ask?question=${encodeURIComponent(question.slice(0, 4000))}`
}

function clusterHealthValue(
  summary: Awaited<ReturnType<typeof getMonitoringSummary>> | undefined,
): string {
  if (!summary) return '—'
  return `${summary.readyNodes}/${summary.totalNodes}`
}

function clusterHealthMeta(
  summary: Awaited<ReturnType<typeof getMonitoringSummary>> | undefined,
  comparison?: Awaited<ReturnType<typeof getHealthTrend>>['comparison'],
): string {
  if (!summary) return '监控快照不可用'
  if (comparison?.baselineAvailable) return `较上一周期 ${formatSigned(comparison.delta)} 分`
  if (summary.notReadyNodes > 0) return `${summary.notReadyNodes} 个 NotReady`
  if (summary.unknownNodes > 0) return `${summary.unknownNodes} 个状态未知`
  return '节点全部 Ready'
}

function clusterHealthTone(
  summary: Awaited<ReturnType<typeof getMonitoringSummary>> | undefined,
): StatusTone {
  if (!summary) return 'neutral'
  if (summary.notReadyNodes > 0) return 'danger'
  if (summary.unknownNodes > 0) return 'warning'
  return 'success'
}

function executionTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'REJECTED' || status === 'CANCELLED') return 'danger'
  if (status === 'RUNNING') return 'info'
  return 'warning'
}

function formatUpdatedAt(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString([], {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatDuration(milliseconds: number | null): string {
  if (milliseconds === null) return '进行中'
  if (milliseconds < 1_000) return `${milliseconds}ms`
  if (milliseconds < 60_000) return `${(milliseconds / 1_000).toFixed(1)}s`
  return `${Math.round(milliseconds / 60_000)}m`
}

function formatSigned(value: number | null): string {
  if (value === null) return '—'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}`
}
