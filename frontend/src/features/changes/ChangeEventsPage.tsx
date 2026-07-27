import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { correlateChanges, listChangeEvents } from './api'

const SAMPLE_ALARM = JSON.stringify(
  {
    alarmId: 'alarm-correlation',
    alertName: 'DeploymentUnavailable',
    resourceType: 'DEPLOYMENT',
    resourceName: 'checkout',
    cluster: 'prod',
    namespace: 'default',
    service: 'checkout',
    severity: 'P1',
    occurredAt: new Date().toISOString(),
    status: 'FIRING',
  },
  null,
  2,
)

/** Change-event timeline and explainable alarm correlation. */
export function ChangeEventsPage() {
  const [cluster, setCluster] = useState('')
  const [namespace, setNamespace] = useState('')
  const [from, setFrom] = useState(localTime(-24 * 60))
  const [to, setTo] = useState(localTime(0))
  const [query, setQuery] = useState({
    cluster: '',
    namespace: '',
    from: new Date(Date.now() - 24 * 60 * 60_000).toISOString(),
    to: new Date().toISOString(),
    page: 1,
    size: 50,
  })
  const [alarmJson, setAlarmJson] = useState(SAMPLE_ALARM)
  const [correlationError, setCorrelationError] = useState<string | null>(null)

  const events = useQuery({
    queryKey: ['change-events', query],
    queryFn: () => listChangeEvents(query),
  })
  const correlations = useMutation({
    mutationFn: () => correlateChanges(parseObject(alarmJson)),
    onSuccess: () => setCorrelationError(null),
    onError: (error) => setCorrelationError(errorMessage(error)),
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>变更事件</h1>
          <p className="koc-page__subtitle">
            查询部署与基础设施变更，并通过时间、资源和命名空间给出告警关联解释。
          </p>
        </div>
      </header>

      <section className="koc-card">
        <h2>变更时间线</h2>
        <form
          className="koc-filters"
          onSubmit={(event) => {
            event.preventDefault()
            setQuery({
              cluster: cluster.trim(),
              namespace: namespace.trim(),
              from: new Date(from).toISOString(),
              to: new Date(to).toISOString(),
              page: 1,
              size: 50,
            })
          }}
        >
          <label className="koc-filter">
            <span>开始</span>
            <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <label className="koc-filter">
            <span>结束</span>
            <input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />
          </label>
          <label className="koc-filter">
            <span>集群</span>
            <input value={cluster} onChange={(event) => setCluster(event.target.value)} />
          </label>
          <label className="koc-filter">
            <span>命名空间</span>
            <input value={namespace} onChange={(event) => setNamespace(event.target.value)} />
          </label>
          <Button type="submit" size="sm">
            查询
          </Button>
        </form>

        <AsyncState
          isLoading={events.isLoading}
          error={events.error}
          isEmpty={!events.isLoading && (events.data?.data.length ?? 0) === 0}
          emptyMessage="当前窗口没有变更事件"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>类型</th>
                <th>资源</th>
                <th>范围</th>
                <th>操作者/来源</th>
                <th>差异</th>
              </tr>
            </thead>
            <tbody>
              {(events.data?.data ?? []).map((change) => (
                <tr key={change.changeId}>
                  <td>{formatTime(change.changedAt)}</td>
                  <td>
                    <StatusBadge tone="info">{change.changeType}</StatusBadge>
                  </td>
                  <td>
                    {change.resourceType}/{change.resourceName}
                  </td>
                  <td>
                    {change.cluster}/{change.namespace}
                  </td>
                  <td>
                    {change.changedBy}
                    <br />
                    <span className="koc-mono">{change.changeSource}</span>
                  </td>
                  <td>
                    <details>
                      <summary>查看</summary>
                      <pre className="koc-mono koc-break">{JSON.stringify(change.diff, null, 2)}</pre>
                    </details>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </section>

      <section className="koc-card">
        <h2>告警关联解释</h2>
        <label className="koc-field">
          <span>告警 JSON</span>
          <textarea
            className="koc-input"
            rows={12}
            value={alarmJson}
            onChange={(event) => setAlarmJson(event.target.value)}
          />
        </label>
        <Button
          size="sm"
          onClick={() => correlations.mutate()}
          disabled={correlations.isPending}
        >
          分析关联变更
        </Button>
        {correlationError ? (
          <p className="koc-alert koc-alert--error">{correlationError}</p>
        ) : null}
        {correlations.data ? (
          correlations.data.length > 0 ? (
            <table className="koc-table">
              <thead>
                <tr>
                  <th>分数</th>
                  <th>变更</th>
                  <th>解释</th>
                  <th>建议</th>
                </tr>
              </thead>
              <tbody>
                {correlations.data.map((correlation) => (
                  <tr key={correlation.changeEvent.changeId}>
                    <td>
                      <StatusBadge
                        tone={correlation.correlationScore >= 0.7 ? 'danger' : 'warning'}
                      >
                        {(correlation.correlationScore * 100).toFixed(0)}%
                      </StatusBadge>
                    </td>
                    <td>
                      {correlation.changeEvent.changeType}
                      <br />
                      <span className="koc-mono">
                        {correlation.changeEvent.resourceName}
                      </span>
                    </td>
                    <td>{correlation.correlationReason}</td>
                    <td>{correlation.suggestions.join('；') || '复核当前状态'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="koc-empty">未发现达到关联阈值的变更</p>
          )
        ) : null}
      </section>
    </section>
  )
}

function parseObject(value: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('请输入告警 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '关联分析失败'
}

function localTime(offsetMinutes: number): string {
  const value = new Date(Date.now() + offsetMinutes * 60_000)
  return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString()
}
