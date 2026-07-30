import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useAuditEvent } from './hooks'
import { resolveListReturnPath } from '@/lib/navigation'

export function AuditDetailPage() {
  const { auditId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const query = useAuditEvent(auditId)
  const audit = query.data
  const returnTo = resolveListReturnPath(location.state, '/audit')

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate(returnTo)}>
            ← 返回列表
          </Button>
          <h1>审计详情</h1>
        </div>
      </header>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && !audit}
      >
        {audit ? (
          <>
            <div className="koc-card">
              <dl className="koc-fields">
                <Field label="审计 ID" value={audit.id} mono />
                <Field
                  label="操作者"
                  value={audit.actor.displayName ?? audit.actor.id ?? audit.actor.type}
                />
                <Field label="Actor 类型" value={audit.actor.type} />
                <Field label="动作" value={audit.action} />
                <Field
                  label="资源"
                  value={`${audit.resource.type} / ${audit.resource.id ?? '—'}`}
                  mono
                />
                <Field label="结果">
                  <StatusBadge tone={audit.result === 'SUCCESS' ? 'success' : 'danger'}>
                    {audit.result}
                  </StatusBadge>
                </Field>
                <Field label="原因" value={audit.reason} />
                <Field label="Request ID" value={audit.requestId} mono />
                <Field label="Trace ID" value={audit.traceId} mono />
                <Field label="来源 IP" value={audit.sourceIp} mono />
                <Field label="浏览器摘要" value={audit.browser} />
                <Field label="发生时间" value={new Date(audit.occurredAt).toLocaleString()} />
              </dl>
            </div>
            <AuditDiff before={audit.before} after={audit.after} />
          </>
        ) : null}
      </AsyncState>
    </section>
  )
}

export function AuditDiff({
  before,
  after,
}: {
  before: Record<string, unknown> | null
  after: Record<string, unknown> | null
}) {
  const keys = Array.from(
    new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]),
  ).sort()
  if (keys.length === 0) return <p className="koc-empty">该操作没有状态差异快照。</p>

  return (
    <section className="koc-card" aria-labelledby="audit-diff-title">
      <h2 id="audit-diff-title">Before / After</h2>
      <table className="koc-table">
        <thead>
          <tr>
            <th>字段</th>
            <th>Before</th>
            <th>After</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => {
            const previous = before?.[key]
            const current = after?.[key]
            const changed = canonical(previous) !== canonical(current)
            return (
              <tr key={key}>
                <td className="koc-mono">{key}</td>
                <td>
                  <code>{formatValue(previous)}</code>
                </td>
                <td>
                  <code>{formatValue(current)}</code>
                  {changed ? <span>（已变化）</span> : null}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </section>
  )
}

function canonical(value: unknown): string {
  return JSON.stringify(value)
}

function formatValue(value: unknown): string {
  if (value === undefined) return '—'
  if (typeof value === 'string') return value
  return JSON.stringify(value)
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
