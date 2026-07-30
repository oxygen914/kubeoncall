import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useApprovalList } from './hooks'
import type { ApprovalRiskLevel, ApprovalStatus } from './api'
import { approvalTone, riskTone } from './viewModels'
import { listReturnState, mergeSearchParams, readPageParam } from '@/lib/navigation'

const PAGE_SIZE = 20
const STATUSES: ApprovalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED']
const RISKS: ApprovalRiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

export function ApprovalsListPage() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = readPageParam(searchParams.get('page'))
  const statusValue = searchParams.get('status')
  const status =
    statusValue === 'ALL'
      ? ''
      : STATUSES.includes(statusValue as ApprovalStatus)
        ? (statusValue as ApprovalStatus)
        : 'PENDING'
  const riskValue = searchParams.get('risk') ?? ''
  const risk = RISKS.includes(riskValue as ApprovalRiskLevel)
    ? (riskValue as ApprovalRiskLevel)
    : ''
  const updateFilters = (updates: Record<string, string | null>) => {
    setSearchParams(mergeSearchParams(searchParams, { ...updates, page: null }), {
      replace: true,
    })
  }
  const updatePage = (nextPage: number) => {
    setSearchParams(mergeSearchParams(searchParams, { page: nextPage === 1 ? null : nextPage }))
  }
  const { data, isLoading, error, isFetching } = useApprovalList({
    page,
    size: PAGE_SIZE,
    status,
    risk,
  })
  const rows = data?.data ?? []
  const pageInfo = data?.page

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>审批</h1>
        <p className="koc-page__subtitle">查看待审批操作并跟踪已完成的决策。</p>
      </header>

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => {
              updateFilters({ status: event.target.value || 'ALL' })
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
        <label className="koc-filter">
          <span>风险</span>
          <select
            value={risk}
            onChange={(event) => {
              updateFilters({ risk: event.target.value || null })
            }}
          >
            <option value="">全部</option>
            {RISKS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
      </form>

      <AsyncState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && rows.length === 0}
        emptyMessage="没有匹配的审批"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>摘要</th>
              <th>状态</th>
              <th>风险</th>
              <th>执行 ID</th>
              <th>创建时间</th>
              <th>过期时间</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((approval) => (
              <tr key={approval.id} className="koc-table__row">
                <td className="koc-table__cell--primary">
                  <Link
                    className="koc-table__primary-link"
                    to={`/approvals/${encodeURIComponent(approval.id)}`}
                    state={listReturnState(location.pathname, location.search)}
                  >
                    {approval.summary}
                  </Link>
                </td>
                <td>
                  <StatusBadge tone={approvalTone(approval.status)}>{approval.status}</StatusBadge>
                </td>
                <td>
                  <StatusBadge tone={riskTone(approval.riskLevel)}>
                    {approval.riskLevel}
                  </StatusBadge>
                </td>
                <td className="koc-mono">{approval.executionId}</td>
                <td>{formatTime(approval.createdAt)}</td>
                <td>{formatTime(approval.expiresAt)}</td>
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
