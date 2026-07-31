import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useExecutionList } from './hooks'
import type { ExecutionStatus } from './api'
import { executionTone } from './viewModels'
import { listReturnState, mergeSearchParams, readPageParam } from '@/lib/navigation'

const PAGE_SIZE = 20
const STATUSES: ExecutionStatus[] = [
  'PENDING',
  'RUNNING',
  'WAITING_APPROVAL',
  'SUCCEEDED',
  'FAILED',
  'REJECTED',
  'CANCELLED',
]

export function ExecutionsListPage() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = readPageParam(searchParams.get('page'))
  const statusValue = searchParams.get('status') ?? ''
  const status = STATUSES.includes(statusValue as ExecutionStatus)
    ? (statusValue as ExecutionStatus)
    : ''
  const alarmId = (searchParams.get('alarmId') ?? '').slice(0, 128)
  const updateFilters = (updates: Record<string, string | null>) => {
    setSearchParams(mergeSearchParams(searchParams, { ...updates, page: null }), {
      replace: true,
    })
  }
  const updatePage = (nextPage: number) => {
    setSearchParams(mergeSearchParams(searchParams, { page: nextPage === 1 ? null : nextPage }))
  }
  const { data, isLoading, error, isFetching } = useExecutionList({
    page,
    size: PAGE_SIZE,
    status,
    alarmId: alarmId || undefined,
  })
  const rows = data?.data ?? []
  const pageInfo = data?.page

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>执行</h1>
        <p className="koc-page__subtitle">诊断与处置执行记录及节点状态。</p>
      </header>

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => {
              updateFilters({ status: event.target.value || null })
            }}
          >
            <option value="">全部</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>告警 ID</span>
          <input
            value={alarmId}
            onChange={(event) => {
              updateFilters({ alarmId: event.target.value || null })
            }}
            maxLength={128}
            placeholder="按告警过滤"
          />
        </label>
      </form>

      <AsyncState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && rows.length === 0}
        emptyMessage="没有匹配的执行记录"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>摘要</th>
              <th>类型</th>
              <th>状态</th>
              <th>开始时间</th>
              <th>耗时</th>
              <th>触发源</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((execution) => (
              <tr key={execution.id} className="koc-table__row">
                <td className="koc-table__cell--primary">
                  <Link
                    className="koc-table__primary-link"
                    to={`/executions/${encodeURIComponent(execution.id)}`}
                    state={listReturnState(location.pathname, location.search)}
                  >
                    {execution.summary}
                  </Link>
                </td>
                <td>{execution.type}</td>
                <td>
                  <StatusBadge tone={executionTone(execution.status)}>
                    {execution.status}
                  </StatusBadge>
                </td>
                <td>{formatTime(execution.startedAt)}</td>
                <td>{formatDuration(execution.durationMs)}</td>
                <td className="koc-mono">{execution.triggerId ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {pageInfo ? (
          <div className="koc-pagination">
            <span className="koc-pagination__info">
              第 {pageInfo.number} / {pageInfo.totalPages} 页 · 共 {pageInfo.totalElements} 条
              {isFetching ? ' （刷新中…）' : ''}
            </span>
            <div className="koc-pagination__actions">
              <Button
                variant="ghost"
                size="sm"
                disabled={page <= 1}
                onClick={() => updatePage(Math.max(1, page - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!pageInfo.hasNext}
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

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function formatDuration(durationMs: number | null): string {
  if (durationMs == null) return '—'
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(1)} s`
}
