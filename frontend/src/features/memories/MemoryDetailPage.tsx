import { useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { useDeleteMemory, useMemory, useRestoreMemory } from './hooks'
import { resolveListReturnPath } from '@/lib/navigation'

export function MemoryDetailPage() {
  const { memoryId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { session } = useSession()
  const query = useMemory(memoryId)
  const deletion = useDeleteMemory(memoryId)
  const restoration = useRestoreMemory(memoryId)
  const [reason, setReason] = useState('')
  const returnTo = resolveListReturnPath(location.state, '/memory')
  const memory = query.data
  const canMaintain = hasPermission(session, PERMISSIONS.MEMORY_MAINTAIN)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate(returnTo)}>
            ← 返回列表
          </Button>
          <h1>记忆详情</h1>
        </div>
        {canMaintain && memory ? (
          memory.status === 'DELETED' ? (
            <Button
              size="sm"
              disabled={restoration.isPending}
              onClick={() => restoration.mutate({ version: memory.version })}
            >
              恢复记忆
            </Button>
          ) : (
            <div className="koc-page__actions">
              <input
                aria-label="删除原因"
                value={reason}
                maxLength={1000}
                placeholder="删除原因"
                onChange={(event) => setReason(event.target.value)}
              />
              <Button
                variant="danger"
                size="sm"
                disabled={!reason.trim() || deletion.isPending}
                onClick={() => deletion.mutate({ version: memory.version, reason })}
              >
                软删除
              </Button>
            </div>
          )
        ) : null}
      </header>
      <AsyncState
        isLoading={query.isLoading}
        error={query.error ?? deletion.error ?? restoration.error}
        isEmpty={!query.isLoading && !memory}
      >
        {memory ? (
          <>
            <div className="koc-card">
              <dl className="koc-fields">
                <Field label="记忆 ID" value={memory.id} mono />
                <Field label="类型" value={memory.memoryType} />
                <Field label="状态">
                  <StatusBadge tone={memory.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {memory.status}
                  </StatusBadge>
                </Field>
                <Field label="质量评分" value={formatScore(memory.qualityScore)} />
                <Field label="来源 Session" value={memory.sourceSessionId} mono />
                <Field label="来源执行" value={memory.sourceExecutionId} mono />
                <Field label="来源告警" value={memory.sourceAlarmId} mono />
                <Field label="过期时间" value={formatTime(memory.expiresAt)} />
                <Field label="删除原因" value={memory.deleteReason} />
                <Field label="版本" value={String(memory.version)} />
              </dl>
            </div>
            <section className="koc-card">
              <h2>证据归因</h2>
              <pre className="koc-json">{JSON.stringify(memory.evidence, null, 2)}</pre>
            </section>
          </>
        ) : null}
      </AsyncState>
    </section>
  )
}

function Field({
  label,
  value,
  mono = false,
  children,
}: {
  label: string
  value?: string | null
  mono?: boolean
  children?: React.ReactNode
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? 'koc-mono' : undefined}>{children ?? value ?? '—'}</dd>
    </div>
  )
}

function formatScore(value: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
