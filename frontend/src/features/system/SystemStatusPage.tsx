import { useQuery } from '@tanstack/react-query'
import { getCapabilities, getSystemStatus } from '@/api/system'
import { Spinner } from '@/components/ui/Spinner'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { isApiError } from '@/api/errors'

function statusTone(status: string): StatusTone {
  const s = status.toLowerCase()
  if (s.includes('up') || s === 'ok' || s === 'healthy' || s === 'running') return 'success'
  if (s.includes('degrad')) return 'warning'
  if (s.includes('down') || s.includes('error') || s.includes('unavailable')) return 'danger'
  return 'neutral'
}

export function SystemStatusPage() {
  const statusQuery = useQuery({
    queryKey: ['system', 'status'],
    queryFn: getSystemStatus,
  })
  const capabilitiesQuery = useQuery({
    queryKey: ['capabilities'],
    queryFn: getCapabilities,
  })

  return (
    <section className="koc-page" aria-labelledby="system-status-title">
      <h1 id="system-status-title">系统状态</h1>

      <h2>服务</h2>
      {statusQuery.isLoading ? (
        <div role="status">
          <Spinner aria-label="加载系统状态" />
        </div>
      ) : statusQuery.isError ? (
        <ErrorState error={statusQuery.error} />
      ) : statusQuery.data ? (
        <SystemStatusCard
          service={statusQuery.data.service}
          version={capabilitiesQuery.data?.release?.version}
          status={statusQuery.data.status}
        />
      ) : (
        <EmptyState />
      )}

      <h2>部署能力</h2>
      {capabilitiesQuery.isLoading ? (
        <div role="status">
          <Spinner aria-label="加载能力列表" />
        </div>
      ) : capabilitiesQuery.isError ? (
        <ErrorState error={capabilitiesQuery.error} />
      ) : capabilitiesQuery.data ? (
        <CapabilitiesList capabilities={capabilitiesQuery.data} />
      ) : (
        <EmptyState />
      )}
    </section>
  )
}

function SystemStatusCard({
  service,
  version,
  status,
}: {
  service: string
  version?: string
  status: string
}) {
  return (
    <div className="koc-card">
      <dl className="koc-deflist">
        <div className="koc-deflist__row">
          <dt>服务</dt>
          <dd>{service}</dd>
        </div>
        <div className="koc-deflist__row">
          <dt>版本</dt>
          <dd>
            <code>{version ?? '未知'}</code>
          </dd>
        </div>
        <div className="koc-deflist__row">
          <dt>状态</dt>
          <dd>
            <StatusBadge tone={statusTone(status)}>{status}</StatusBadge>
          </dd>
        </div>
      </dl>
    </div>
  )
}

function CapabilitiesList({ capabilities }: { capabilities: Record<string, unknown> }) {
  const entries = Object.entries(capabilities)
  if (entries.length === 0) return <EmptyState />
  return (
    <div className="koc-card">
      <ul className="koc-caplist">
        {entries.map(([key, value]) => (
          <li key={key} className="koc-caplist__item">
            <span className="koc-caplist__key">{key}</span>
            <span className="koc-caplist__value">{formatCapabilityValue(value)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function formatCapabilityValue(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return JSON.stringify(value)
}

function ErrorState({ error }: { error: unknown }) {
  const message = isApiError(error)
    ? `${error.message}${error.requestId ? ` (请求 ID: ${error.requestId})` : ''}`
    : '加载失败,请稍后重试。'
  return (
    <div role="alert" className="koc-alert koc-alert--error">
      {message}
    </div>
  )
}

function EmptyState() {
  return <p className="koc-empty">暂无数据</p>
}
