import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useApprovalList } from './hooks'
import type { ApprovalRiskLevel, ApprovalStatus } from './api'
import { approvalTone, riskTone } from './viewModels'

const PAGE_SIZE = 20
const STATUSES: ApprovalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED']
const RISKS: ApprovalRiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

export function ApprovalsListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<ApprovalStatus | ''>('PENDING')
  const [risk, setRisk] = useState<ApprovalRiskLevel | ''>('')
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
              setStatus(event.target.value as ApprovalStatus | '')
              setPage(1)
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
              setRisk(event.target.value as ApprovalRiskLevel | '')
              setPage(1)
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
              <tr
                key={approval.id}
                className="koc-table__row"
                tabIndex={0}
                onClick={() => navigate(`/approvals/${approval.id}`)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    navigate(`/approvals/${approval.id}`)
                  }
                }}
              >
                <td className="koc-table__cell--primary">{approval.summary}</td>
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
                onClick={() => setPage((value) => Math.max(1, value - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!pageInfo.hasNext}
                onClick={() => setPage((value) => value + 1)}
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
