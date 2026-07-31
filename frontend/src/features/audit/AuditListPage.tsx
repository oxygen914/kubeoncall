import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { useAuditEvents } from './hooks'
import type { AuditEventFilters } from './api'
import { listReturnState, mergeSearchParams, readPageParam } from '@/lib/navigation'

const PAGE_SIZE = 20

export function AuditListPage() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = readPageParam(searchParams.get('page'))
  const filters: AuditEventFilters = {
    actor: readFilter(searchParams, 'actor'),
    action: readFilter(searchParams, 'action'),
    resourceType: readFilter(searchParams, 'resourceType'),
    resourceId: readFilter(searchParams, 'resourceId'),
    result: readAuditResult(searchParams.get('result')),
    requestId: readFilter(searchParams, 'requestId'),
    from: readDateFilter(searchParams.get('from')),
    to: readDateFilter(searchParams.get('to')),
  }
  const query = useAuditEvents({ ...filters, page, size: PAGE_SIZE })

  const update = (key: keyof AuditEventFilters, value: string) => {
    setSearchParams(
      mergeSearchParams(searchParams, {
        [key]: value || null,
        page: null,
      }),
      { replace: true },
    )
  }
  const updatePage = (nextPage: number) => {
    setSearchParams(mergeSearchParams(searchParams, { page: nextPage === 1 ? null : nextPage }))
  }

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>操作审计</h1>
          <p className="koc-page__subtitle">按操作者、资源和 requestId 追溯不可编辑的操作事实。</p>
        </div>
      </header>

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <Filter label="操作者" value={filters.actor} onChange={(value) => update('actor', value)} />
        <Filter label="动作" value={filters.action} onChange={(value) => update('action', value)} />
        <Filter
          label="资源类型"
          value={filters.resourceType}
          onChange={(value) => update('resourceType', value)}
        />
        <Filter
          label="资源 ID"
          value={filters.resourceId}
          onChange={(value) => update('resourceId', value)}
        />
        <label className="koc-filter">
          <span>结果</span>
          <select
            value={filters.result ?? ''}
            onChange={(event) => update('result', event.target.value)}
          >
            <option value="">全部</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="FAILURE">FAILURE</option>
            <option value="DENIED">DENIED</option>
            <option value="CONFLICT">CONFLICT</option>
          </select>
        </label>
        <Filter
          label="Request ID"
          value={filters.requestId}
          onChange={(value) => update('requestId', value)}
        />
        <Filter
          label="开始时间"
          type="datetime-local"
          value={filters.from}
          onChange={(value) => update('from', toIso(value))}
        />
        <Filter
          label="结束时间"
          type="datetime-local"
          value={filters.to}
          onChange={(value) => update('to', toIso(value))}
        />
      </form>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && (query.data?.data.length ?? 0) === 0}
        emptyMessage="没有匹配的审计事件"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作者</th>
              <th>动作</th>
              <th>资源</th>
              <th>结果</th>
              <th>Request ID</th>
            </tr>
          </thead>
          <tbody>
            {(query.data?.data ?? []).map((item) => (
              <tr key={item.id} className="koc-table__row">
                <td>
                  <Link
                    className="koc-table__primary-link"
                    to={`/audit/${encodeURIComponent(item.id)}`}
                    state={listReturnState(location.pathname, location.search)}
                  >
                    {formatTime(item.occurredAt)}
                  </Link>
                </td>
                <td>{item.actor.displayName ?? item.actor.id ?? item.actor.type}</td>
                <td>{item.action}</td>
                <td>
                  {item.resource.type} / <span className="koc-mono">{item.resource.id ?? '—'}</span>
                </td>
                <td>
                  <StatusBadge tone={resultTone(item.result)}>{item.result}</StatusBadge>
                </td>
                <td className="koc-mono">{item.requestId}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.data?.page ? (
          <div className="koc-pagination">
            <span className="koc-pagination__info">
              第 {query.data.page.number} / {query.data.page.totalPages} 页 · 共{' '}
              {query.data.page.totalElements} 条{query.isFetching ? ' （刷新中…）' : ''}
            </span>
            <div className="koc-pagination__actions">
              <Button
                variant="ghost"
                size="sm"
                disabled={page <= 1}
                onClick={() => updatePage(page - 1)}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!query.data.page.hasNext}
                onClick={() => updatePage(page + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : null}
      </AsyncState>
    </section>
  )
}

function Filter({
  label,
  value,
  type = 'text',
  onChange,
}: {
  label: string
  value?: string
  type?: string
  onChange: (value: string) => void
}) {
  return (
    <label className="koc-filter">
      <span>{label}</span>
      <input
        type={type}
        value={type === 'datetime-local' ? toLocalInput(value) : (value ?? '')}
        maxLength={type === 'text' ? 255 : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function toIso(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? '' : date.toISOString()
}

function readFilter(searchParams: URLSearchParams, key: string): string | undefined {
  return (searchParams.get(key) ?? '').slice(0, 255) || undefined
}

function readAuditResult(value: string | null): string | undefined {
  return value && ['SUCCESS', 'FAILURE', 'DENIED', 'CONFLICT'].includes(value) ? value : undefined
}

function readDateFilter(value: string | null): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? undefined : date.toISOString()
}

function toLocalInput(value: string | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return ''
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.valueOf() - offset).toISOString().slice(0, 16)
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString()
}

function resultTone(result: string): StatusTone {
  if (result === 'SUCCESS') return 'success'
  if (result === 'FAILURE' || result === 'DENIED') return 'danger'
  if (result === 'CONFLICT') return 'warning'
  return 'neutral'
}
