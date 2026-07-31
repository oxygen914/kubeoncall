import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { useApproval } from './hooks'
import { approvalTone, riskTone } from './viewModels'
import { ApprovalDecisionDialog } from './ApprovalDecisionDialog'
import type { ApprovalDecision } from './api'
import { resolveListReturnPath } from '@/lib/navigation'

export function ApprovalDetailPage() {
  const { approvalId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { session } = useSession()
  const { data: approval, isLoading, error } = useApproval(approvalId)
  const [decision, setDecision] = useState<ApprovalDecision | null>(null)
  const returnTo = resolveListReturnPath(location.state, '/approvals')
  const canDecide =
    approval?.status === 'PENDING' && hasPermission(session, PERMISSIONS.APPROVAL_DECIDE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate(returnTo)}>
            ← 返回列表
          </Button>
          <h1>审批详情</h1>
        </div>
        {canDecide ? (
          <div className="koc-page__actions" aria-label="审批操作">
            <Button size="sm" onClick={() => setDecision('APPROVED')}>
              批准
            </Button>
            <Button variant="danger" size="sm" onClick={() => setDecision('REJECTED')}>
              拒绝
            </Button>
          </div>
        ) : null}
      </header>

      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !approval}>
        {approval ? (
          <>
            <div className="koc-card">
              <dl className="koc-fields">
                <div>
                  <dt>审批 ID</dt>
                  <dd className="koc-mono">{approval.id}</dd>
                </div>
                <div>
                  <dt>摘要</dt>
                  <dd>{approval.summary}</dd>
                </div>
                <div>
                  <dt>状态</dt>
                  <dd>
                    <StatusBadge tone={approvalTone(approval.status)}>
                      {approval.status}
                    </StatusBadge>
                  </dd>
                </div>
                <div>
                  <dt>风险</dt>
                  <dd>
                    <StatusBadge tone={riskTone(approval.riskLevel)}>
                      {approval.riskLevel}
                    </StatusBadge>
                  </dd>
                </div>
                <div>
                  <dt>执行</dt>
                  <dd>
                    {hasPermission(session, PERMISSIONS.EXECUTION_READ) ? (
                      <Link to={`/executions/${encodeURIComponent(approval.executionId)}`}>
                        {approval.executionId}
                      </Link>
                    ) : (
                      approval.executionId
                    )}
                  </dd>
                </div>
                <div>
                  <dt>动作</dt>
                  <dd>{approval.action ?? '—'}</dd>
                </div>
                <div>
                  <dt>版本</dt>
                  <dd>{approval.version}</dd>
                </div>
              </dl>
            </div>
            <JsonPanel title="审批上下文" value={approval.context} />
            <JsonPanel title="决策信息" value={approval.decision} />
          </>
        ) : null}
      </AsyncState>

      {decision && approval ? (
        <ApprovalDecisionDialog
          approvalId={approval.id}
          version={approval.version}
          initialDecision={decision}
          onClose={() => setDecision(null)}
        />
      ) : null}
    </section>
  )
}

function JsonPanel({ title, value }: { title: string; value?: Record<string, unknown> }) {
  if (!value || Object.keys(value).length === 0) return null
  return (
    <section className="koc-card">
      <h2>{title}</h2>
      <pre className="koc-json">{JSON.stringify(value, null, 2)}</pre>
    </section>
  )
}
