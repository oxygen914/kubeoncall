import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { getIntegrations, getNotificationDeliveries } from './api'

/** Configured integrations, dependency circuit state and durable notification deliveries. */
export function IntegrationsPage() {
  const integrations = useQuery({
    queryKey: ['integrations', 'catalog'],
    queryFn: getIntegrations,
    refetchInterval: 30_000,
  })
  const notifications = useQuery({
    queryKey: ['integrations', 'notifications'],
    queryFn: () => getNotificationDeliveries(1, 50),
    refetchInterval: 15_000,
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>集成与通知</h1>
          <p className="koc-page__subtitle">
            查看外部系统配置、进程内断路器状态和持久化通知节点执行记录。
          </p>
        </div>
      </header>

      <section className="koc-card">
        <h2>集成状态</h2>
        <AsyncState isLoading={integrations.isLoading} error={integrations.error}>
          <table className="koc-table">
            <thead>
              <tr>
                <th>依赖</th>
                <th>配置</th>
                <th>端点</th>
                <th>超时</th>
                <th>断路器</th>
                <th>连续失败</th>
              </tr>
            </thead>
            <tbody>
              {(integrations.data?.integrations ?? []).map((integration) => (
                <tr key={integration.id}>
                  <td>
                    <strong>{integration.name}</strong>
                    <br />
                    <span className="koc-mono">{integration.id}</span>
                  </td>
                  <td>
                    <StatusBadge tone={integration.configured ? 'success' : 'neutral'}>
                      {integration.configured ? '已配置' : '未配置'}
                    </StatusBadge>
                  </td>
                  <td className="koc-mono">{integration.endpoint ?? '内部依赖'}</td>
                  <td>
                    {integration.timeoutMillis == null ? '—' : `${integration.timeoutMillis}ms`}
                  </td>
                  <td>
                    <StatusBadge tone={circuitTone(integration.circuitState)}>
                      {integration.circuitState}
                    </StatusBadge>
                  </td>
                  <td>{integration.consecutiveFailures}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </section>

      <section className="koc-card">
        <h2>通知记录</h2>
        <AsyncState
          isLoading={notifications.isLoading}
          error={notifications.error}
          isEmpty={!notifications.isLoading && (notifications.data?.data.length ?? 0) === 0}
          emptyMessage="暂无持久化通知执行记录"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>执行</th>
                <th>状态</th>
                <th>摘要</th>
                <th>耗时</th>
                <th>错误</th>
              </tr>
            </thead>
            <tbody>
              {(notifications.data?.data ?? []).map((delivery) => (
                <tr key={delivery.id}>
                  <td>{formatTime(delivery.finishedAt ?? delivery.startedAt)}</td>
                  <td>
                    <Link to={`/executions/${encodeURIComponent(delivery.executionId)}`}>
                      {delivery.executionId}
                    </Link>
                  </td>
                  <td>
                    <StatusBadge tone={delivery.status === 'SUCCEEDED' ? 'success' : 'danger'}>
                      {delivery.status}
                    </StatusBadge>
                  </td>
                  <td>{delivery.summary ?? '—'}</td>
                  <td>{delivery.durationMs == null ? '—' : `${delivery.durationMs}ms`}</td>
                  <td>{delivery.errorSummary ?? delivery.errorCode ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </section>
    </section>
  )
}

function circuitTone(state: string): StatusTone {
  if (state === 'OPEN') return 'danger'
  if (state === 'HALF_OPEN') return 'warning'
  if (state === 'CLOSED') return 'success'
  return 'neutral'
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
