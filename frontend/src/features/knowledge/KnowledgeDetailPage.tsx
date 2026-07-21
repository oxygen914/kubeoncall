import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import {
  useDeleteKnowledgeDocument,
  useKnowledgeDocument,
  useRestoreKnowledgeDocument,
} from './hooks'

export function KnowledgeDetailPage() {
  const { documentId = '' } = useParams()
  const navigate = useNavigate()
  const { session } = useSession()
  const query = useKnowledgeDocument(documentId)
  const deletion = useDeleteKnowledgeDocument(documentId)
  const restoration = useRestoreKnowledgeDocument(documentId)
  const [reason, setReason] = useState('')
  const document = query.data
  const canManage = hasPermission(session, PERMISSIONS.KNOWLEDGE_DELETE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate('/knowledge')}>
            ← 返回列表
          </Button>
          <h1>知识文档详情</h1>
        </div>
        {document && canManage ? (
          <div className="koc-page__actions">
            {document.status === 'ACTIVE' ? (
              <>
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
                  onClick={() => deletion.mutate({ version: document.version, reason })}
                >
                  软删除
                </Button>
              </>
            ) : (
              <Button
                size="sm"
                disabled={restoration.isPending}
                onClick={() => restoration.mutate({ version: document.version })}
              >
                恢复
              </Button>
            )}
          </div>
        ) : null}
      </header>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error ?? deletion.error ?? restoration.error}
        isEmpty={!query.isLoading && !document}
      >
        {document ? (
          <>
            <div className="koc-card">
              <dl className="koc-fields">
                <Field label="文档 ID" value={document.id} mono />
                <Field label="标题" value={document.title} />
                <Field label="状态">
                  <StatusBadge tone={document.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {document.status}
                  </StatusBadge>
                </Field>
                <Field label="来源" value={document.sourceType} />
                <Field label="来源 URI" value={document.sourceUri} />
                <Field label="数据集版本" value={document.datasetVersion} />
                <Field label="当前版本 ID" value={document.currentVersionId} mono />
                <Field label="并发版本" value={String(document.version)} />
                <Field label="删除原因" value={document.deleteReason} />
              </dl>
            </div>
            <section className="koc-card">
              <h2>元数据</h2>
              <pre className="koc-json">{JSON.stringify(document.metadata, null, 2)}</pre>
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
