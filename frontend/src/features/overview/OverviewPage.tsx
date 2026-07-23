import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getOverview } from './api'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useNavigate } from 'react-router-dom'

/** Dashboard overview page: single aggregate query for the home view. */
export function OverviewPage() {
  const navigate = useNavigate()
  const [window, setWindow] = useState('24h')
  const { data, isLoading, error } = useQuery({
    queryKey: ['overview', window],
    queryFn: () => getOverview(window),
    staleTime: 15_000,
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>概览</h1>
        <p className="koc-page__subtitle">告警、审批与执行汇总（按所选时间窗口统计）。</p>
        <label className="koc-filter">
          <span>时间窗口</span>
          <select value={window} onChange={(event) => setWindow(event.target.value)}>
            <option value="1h">最近 1 小时</option>
            <option value="6h">最近 6 小时</option>
            <option value="24h">最近 24 小时</option>
            <option value="7d">最近 7 天</option>
            <option value="30d">最近 30 天</option>
          </select>
        </label>
      </header>

      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !data}>
        {data ? (
          <div className="koc-overview">
            <div className="koc-overview__cards">
              <OverviewCard label="活跃告警" value={data.activeAlarms} tone="danger" onClick={() => navigate('/alarms')} />
              <OverviewCard label="待审批" value={data.pendingApprovals} tone="warning" onClick={() => navigate('/approvals')} />
              <OverviewCard label="运行中执行" value={data.runningExecutions} tone="info" onClick={() => navigate('/executions')} />
              <OverviewCard label="失败执行" value={data.failedExecutions} tone="danger" onClick={() => navigate('/executions')} />
            </div>

            <div className="koc-detail">
              <div className="koc-detail__summary">
                <h2>活跃告警按级别</h2>
                <table className="koc-table">
                  <tbody>
                    {Object.entries(data.severityCounts).length === 0 ? (
                      <tr><td>无活跃告警</td></tr>
                    ) : (
                      Object.entries(data.severityCounts).map(([sev, count]) => (
                        <tr key={sev}>
                          <td><StatusBadge tone={severityTone(sev)}>{sev}</StatusBadge></td>
                          <td>{count}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
              <div className="koc-detail__summary">
                <h2>告警按状态</h2>
                <table className="koc-table">
                  <tbody>
                    {Object.entries(data.statusCounts).map(([status, count]) => (
                      <tr key={status}>
                        <td className="koc-mono">{status}</td>
                        <td>{count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        ) : null}
      </AsyncState>
    </section>
  )
}

function OverviewCard({
  label,
  value,
  tone,
  onClick,
}: {
  label: string
  value: number
  tone: 'danger' | 'warning' | 'info' | 'success'
  onClick: () => void
}) {
  return (
    <button className="koc-overview__card" onClick={onClick} type="button">
      <span className="koc-overview__value" data-tone={tone}>{value}</span>
      <span className="koc-overview__label">{label}</span>
    </button>
  )
}

function severityTone(severity: string): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (severity) {
    case 'P0':
    case 'P1':
      return 'danger'
    case 'P2':
      return 'warning'
    case 'P3':
      return 'info'
    default:
      return 'neutral'
  }
}
