import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import clsx from 'clsx'
import type { AlarmListItem } from '@/features/alarms/api'
import type { ApprovalListItem } from '@/features/approvals/api'
import type { SandboxRun } from '@/features/sandbox/api'
import { Button } from '@/components/ui/Button'
import { Icon, type IconName } from '@/components/ui/Icon'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'

export function SectionPanel({
  title,
  description,
  action,
  className,
  children,
}: {
  title: string
  description?: string
  action?: ReactNode
  className?: string
  children: ReactNode
}) {
  return (
    <section className={clsx('koc-section-panel', className)}>
      <header className="koc-section-panel__header">
        <div>
          <h2>{title}</h2>
          {description ? <p>{description}</p> : null}
        </div>
        {action ? <div className="koc-section-panel__action">{action}</div> : null}
      </header>
      <div className="koc-section-panel__body">{children}</div>
    </section>
  )
}

export function MetricCard({
  label,
  value,
  meta,
  tone,
  icon,
  trend,
  onClick,
}: {
  label: string
  value: string | number
  meta: string
  tone: StatusTone
  icon: IconName
  trend?: number[]
  onClick?: () => void
}) {
  const content = (
    <>
      <span className="koc-metric-card__header">
        <span>{label}</span>
        <span className="koc-metric-card__icon" data-tone={tone}>
          <Icon name={icon} size={16} />
        </span>
      </span>
      <span className="koc-metric-card__body">
        <strong data-tone={tone}>{value}</strong>
        <MiniTrend values={trend} tone={tone} />
      </span>
      <span className="koc-metric-card__meta">{meta}</span>
    </>
  )

  return onClick ? (
    <button className="koc-metric-card" type="button" onClick={onClick}>
      {content}
    </button>
  ) : (
    <div className="koc-metric-card">{content}</div>
  )
}

export function SeverityBadge({ severity }: { severity: string }) {
  return <StatusBadge tone={severityTone(severity)}>{severity}</StatusBadge>
}

export function AlertList({ alarms }: { alarms: AlarmListItem[] }) {
  if (alarms.length === 0) {
    return <EmptyState title="当前没有活跃告警" description="所选范围内未返回 FIRING 告警。" />
  }

  return (
    <div className="koc-alert-list">
      <div className="koc-alert-list__header" aria-hidden="true">
        <span>等级 / 告警</span>
        <span>资源与范围</span>
        <span>持续 / 负责人</span>
        <span>状态</span>
      </div>
      {alarms.map((alarm) => (
        <Link className="koc-alert-list__row" to={`/alarms/${alarm.id}`} key={alarm.id}>
          <span className="koc-alert-list__primary">
            <SeverityBadge severity={alarm.severity} />
            <strong title={alarm.alertName}>{alarm.alertName}</strong>
          </span>
          <span className="koc-alert-list__resource">
            <code title={alarm.resource.name}>{alarm.resource.name}</code>
            <small>
              {alarm.resource.cluster || '未知集群'} ·{' '}
              {alarm.resource.namespace || '全部 Namespace'}
            </small>
          </span>
          <span>
            <strong>{formatDuration(alarm.firstSeen)}</strong>
            <small>{alarm.acknowledgement.by || '未认领'}</small>
          </span>
          <StatusBadge tone={alarm.status === 'FIRING' ? 'danger' : 'warning'}>
            {alarm.status}
          </StatusBadge>
        </Link>
      ))}
    </div>
  )
}

export function ApprovalQueue({ approvals }: { approvals: ApprovalListItem[] }) {
  if (approvals.length === 0) {
    return <EmptyState title="暂无待审批任务" description="当前没有需要人工决策的处置动作。" />
  }
  return (
    <ul className="koc-work-queue">
      {approvals.map((approval) => (
        <li key={approval.id}>
          <Link to={`/approvals/${approval.id}`}>
            <span>
              <strong title={approval.summary}>{approval.summary}</strong>
              <code>{approval.executionId}</code>
            </span>
            <span>
              <StatusBadge tone={riskTone(approval.riskLevel)}>{approval.riskLevel}</StatusBadge>
              <small>{formatDateTime(approval.createdAt)}</small>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}

export function SandboxQueue({ runs }: { runs: SandboxRun[] }) {
  const recentRuns = [...runs]
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 5)
  if (recentRuns.length === 0) {
    return <EmptyState title="暂无 Sandbox 任务" description="当前没有可展示的隔离执行记录。" />
  }
  return (
    <ul className="koc-work-queue">
      {recentRuns.map((run) => (
        <li key={run.id}>
          <Link to={`/sandbox-runs/${run.id}`}>
            <span>
              <strong title={run.toolId}>{run.toolId}</strong>
              <code>{run.id}</code>
            </span>
            <span>
              <StatusBadge tone={runStatusTone(run.status)}>{run.status}</StatusBadge>
              <small>{formatDateTime(run.createdAt)}</small>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}

export interface AiInsight {
  id: string
  title: string
  risk: 'P1' | 'P2' | 'P3' | 'INFO'
  summary: string
  cause: string
  relatedAlarm: string
  relatedChange: string
  evidence: string
  action: string
  analysisPath: string
  handlingPath?: string
  source?: 'MODEL' | 'RULE_ENGINE_FALLBACK'
}

export function AiInsightPanel({ insights }: { insights: AiInsight[] }) {
  return (
    <div className="koc-ai-insights">
      {insights.map((insight) => (
        <article className="koc-ai-insight" key={insight.id}>
          <header>
            <span className="koc-ai-insight__mark" aria-hidden="true">
              {insight.source === 'RULE_ENGINE_FALLBACK' ? '规则' : 'AI'}
            </span>
            <div>
              <h3>{insight.title}</h3>
              <p>{insight.summary}</p>
            </div>
            <SeverityBadge severity={insight.risk} />
          </header>
          <dl>
            <div>
              <dt>可能原因</dt>
              <dd>{insight.cause}</dd>
            </div>
            <div>
              <dt>关联告警</dt>
              <dd>{insight.relatedAlarm}</dd>
            </div>
            <div>
              <dt>关联变更</dt>
              <dd>{insight.relatedChange}</dd>
            </div>
            <div>
              <dt>数据依据</dt>
              <dd>{insight.evidence}</dd>
            </div>
          </dl>
          <div className="koc-ai-insight__recommendation">
            <strong>推荐动作</strong>
            <span>{insight.action}</span>
          </div>
          <footer>
            <Link className="koc-btn koc-btn--secondary koc-btn--sm" to={insight.analysisPath}>
              查看分析
            </Link>
            {insight.handlingPath ? (
              <Link className="koc-btn koc-btn--primary koc-btn--sm" to={insight.handlingPath}>
                创建处置任务
              </Link>
            ) : (
              <Button
                size="sm"
                disabled
                title="当前没有可复用的告警处置入口，不能在概览页伪造执行请求"
              >
                创建处置任务
              </Button>
            )}
          </footer>
        </article>
      ))}
    </div>
  )
}

export function LoadingState({ lines = 4 }: { lines?: number }) {
  return (
    <div className="koc-skeleton" role="status" aria-label="加载中">
      {Array.from({ length: lines }, (_, index) => (
        <span key={index} />
      ))}
    </div>
  )
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="koc-overview-state">
      <Icon name="grid" />
      <strong>{title}</strong>
      <p>{description}</p>
    </div>
  )
}

export function ErrorState({
  title = '数据加载失败',
  onRetry,
}: {
  title?: string
  onRetry?: () => void
}) {
  return (
    <div className="koc-overview-state koc-overview-state--error" role="alert">
      <Icon name="alarm" />
      <strong>{title}</strong>
      <p>请检查连接后重试；其他区域仍可继续使用。</p>
      {onRetry ? (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          <Icon name="refresh" size={15} />
          重试
        </Button>
      ) : null}
    </div>
  )
}

export function NoPermissionState({ resource }: { resource: string }) {
  return (
    <div className="koc-overview-state">
      <Icon name="shield" />
      <strong>无权查看{resource}</strong>
      <p>页面已保留结构，但不会发起越权请求。</p>
    </div>
  )
}

function MiniTrend({ values, tone }: { values?: number[]; tone: StatusTone }) {
  if (!values || values.length < 2) {
    return (
      <span className="koc-mini-trend koc-mini-trend--empty" aria-label="暂无同期趋势">
        <i />
      </span>
    )
  }
  const max = Math.max(...values, 1)
  const min = Math.min(...values, 0)
  const range = Math.max(1, max - min)
  const points = values
    .map((value, index) => {
      const x = values.length === 1 ? 0 : (index / (values.length - 1)) * 64
      const y = 22 - ((value - min) / range) * 18
      return `${x},${y}`
    })
    .join(' ')
  return (
    <svg
      className="koc-mini-trend"
      data-tone={tone}
      viewBox="0 0 64 24"
      role="img"
      aria-label={`当前窗口趋势：${values.join('、')}`}
    >
      <polyline points={points} />
    </svg>
  )
}

function severityTone(severity: string): StatusTone {
  if (severity === 'P0' || severity === 'P1') return 'danger'
  if (severity === 'P2') return 'warning'
  if (severity === 'P3') return 'info'
  return severity === 'INFO' ? 'success' : 'neutral'
}

function riskTone(risk: string): StatusTone {
  if (risk === 'CRITICAL' || risk === 'HIGH') return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  return 'info'
}

function runStatusTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'TIMED_OUT') return 'danger'
  if (status === 'RUNNING' || status === 'COLLECTING') return 'info'
  return 'neutral'
}

function formatDuration(firstSeen: string): string {
  const milliseconds = Math.max(0, Date.now() - Date.parse(firstSeen))
  const minutes = Math.floor(milliseconds / 60_000)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时`
  return `${Math.floor(hours / 24)} 天`
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString([], {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
