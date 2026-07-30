import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useAlarmList } from './hooks'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Button } from '@/components/ui/Button'
import { listReturnState, mergeSearchParams, readPageParam } from '@/lib/navigation'
import { formatTime, resourceLabel, severityTone, statusTone } from './viewModels'

const PAGE_SIZE = 20

const SEVERITIES = ['P0', 'P1', 'P2', 'P3', 'INFO']
const STATUSES = ['FIRING', 'ACKNOWLEDGED', 'RECOVERY_PENDING', 'RESOLVED', 'SUPPRESSED']

/**
 * Alarm list page. Single list query with severity/status filters and pagination. Selecting a row
 * navigates to the detail page. The backend is the authority on what the viewer may see; this page
 * only renders the returned rows.
 */
export function AlarmsListPage() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = readPageParam(searchParams.get('page'))
  const severityValue = searchParams.get('severity') ?? ''
  const severity = SEVERITIES.includes(severityValue) ? severityValue : ''
  const statusValue = searchParams.get('status') ?? ''
  const status = STATUSES.includes(statusValue) ? statusValue : ''
  const q = (searchParams.get('q') ?? '').slice(0, 200)
  const [searchDraft, setSearchDraft] = useState(q)

  const updateFilters = (updates: Record<string, string | null>) => {
    setSearchParams(mergeSearchParams(searchParams, { ...updates, page: null }), {
      replace: true,
    })
  }
  const updatePage = (nextPage: number) => {
    setSearchParams(mergeSearchParams(searchParams, { page: nextPage === 1 ? null : nextPage }))
  }
  useEffect(() => {
    setSearchDraft(q)
  }, [q])

  useEffect(() => {
    if (searchDraft === q) return
    const timeoutId = window.setTimeout(() => {
      setSearchParams(
        (current) =>
          mergeSearchParams(current, {
            q: searchDraft || null,
            page: null,
          }),
        { replace: true },
      )
    }, 300)
    return () => window.clearTimeout(timeoutId)
  }, [q, searchDraft, setSearchParams])

  const { data, isLoading, error, isFetching } = useAlarmList({
    page,
    size: PAGE_SIZE,
    severity: severity || undefined,
    status: status || undefined,
    q: q || undefined,
    sort: '-lastSeen',
  })

  const rows = data?.data ?? []
  const pageInfo = data?.page

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>告警</h1>
        <p className="koc-page__subtitle">当前与历史告警，数据来自 MySQL 读模型。</p>
      </header>

      <form className="koc-filters" onSubmit={(e) => e.preventDefault()}>
        <label className="koc-filter">
          <span>严重级别</span>
          <select
            value={severity}
            onChange={(e) => {
              updateFilters({ severity: e.target.value || null })
            }}
          >
            <option value="">全部</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(e) => {
              updateFilters({ status: e.target.value || null })
            }}
          >
            <option value="">全部</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>搜索</span>
          <input
            type="search"
            value={searchDraft}
            onChange={(e) => {
              setSearchDraft(e.target.value)
            }}
            placeholder="告警名或资源名"
            maxLength={200}
          />
        </label>
      </form>

      <AsyncState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && rows.length === 0}
        emptyMessage="没有匹配的告警"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>告警</th>
              <th>级别</th>
              <th>状态</th>
              <th>资源</th>
              <th>最近发生</th>
              <th>次数</th>
              <th>确认</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((alarm) => (
              <tr key={alarm.id} className="koc-table__row">
                <td className="koc-table__cell--primary">
                  <Link
                    className="koc-table__primary-link"
                    to={`/alarms/${encodeURIComponent(alarm.id)}`}
                    state={listReturnState(location.pathname, location.search)}
                  >
                    {alarm.alertName}
                  </Link>
                </td>
                <td>
                  <StatusBadge tone={severityTone(alarm.severity)}>{alarm.severity}</StatusBadge>
                </td>
                <td>
                  <StatusBadge tone={statusTone(alarm.status)}>{alarm.status}</StatusBadge>
                </td>
                <td>{resourceLabel(alarm.resource)}</td>
                <td>{formatTime(alarm.lastSeen)}</td>
                <td>{alarm.occurrenceCount}</td>
                <td>{alarm.acknowledgement?.acknowledged ? '已确认' : '—'}</td>
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
