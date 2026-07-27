import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import {
  isTerminal,
  useArtifactDownload,
  useCancelSandboxRun,
  useSandboxArtifacts,
  useSandboxRun,
} from './hooks'
import { cleanupTone, sandboxRunTone } from './viewModels'

export function SandboxRunDetailPage() {
  const { runId = '' } = useParams()
  const navigate = useNavigate()
  const { session } = useSession()
  const { data: run, isLoading, error } = useSandboxRun(runId)
  const {
    data: artifacts,
    isLoading: artifactsLoading,
    error: artifactsError,
  } = useSandboxArtifacts(runId)
  const cancel = useCancelSandboxRun(runId)
  const download = useArtifactDownload(runId)
  const [actionError, setActionError] = useState<string | null>(null)
  const canCancel = Boolean(
    run && !isTerminal(run) && hasPermission(session, PERMISSIONS.SANDBOX_CANCEL),
  )

  const cancelRun = () => {
    if (!run || !window.confirm(`确认取消 Sandbox Run ${run.id}？`)) return
    setActionError(null)
    cancel.mutate(run.version, { onError: (cause) => setActionError(errorText(cause)) })
  }

  const downloadArtifact = (artifactId: string) => {
    setActionError(null)
    download.mutate(artifactId, {
      onSuccess: (result) => window.open(result.url, '_blank', 'noopener,noreferrer'),
      onError: (cause) => setActionError(errorText(cause)),
    })
  }

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate('/sandbox-runs')}>
            ← 返回列表
          </Button>
          <h1>Sandbox Run 详情</h1>
        </div>
        {canCancel ? (
          <div className="koc-page__actions">
            <Button variant="danger" size="sm" disabled={cancel.isPending} onClick={cancelRun}>
              {cancel.isPending ? '取消中…' : '取消运行'}
            </Button>
          </div>
        ) : null}
      </header>

      {actionError ? <p className="koc-alert koc-alert--error">{actionError}</p> : null}

      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !run}>
        {run ? (
          <div className="koc-card">
            <dl className="koc-fields">
              <Field label="Run ID" value={<span className="koc-mono">{run.id}</span>} />
              <Field label="模式" value={run.mode} />
              <Field label="工具" value={`${run.toolId}@${run.toolVersion}`} />
              <Field
                label="状态"
                value={<StatusBadge tone={sandboxRunTone(run.status)}>{run.status}</StatusBadge>}
              />
              <Field label="阶段 / 进度" value={`${run.stage ?? '—'} · ${run.progress}%`} />
              <Field
                label="清理状态"
                value={
                  <StatusBadge tone={cleanupTone(run.cleanupStatus)}>
                    {run.cleanupStatus}
                  </StatusBadge>
                }
              />
              <Field label="风险" value={run.riskLevel} />
              <Field label="关联执行" value={linkExecution(run.executionId, session)} />
              <Field label="关联告警" value={linkAlarm(run.alarmId, session)} />
              <Field label="创建时间" value={formatTime(run.createdAt)} />
              <Field label="开始时间" value={formatTime(run.startedAt)} />
              <Field label="结束时间" value={formatTime(run.finishedAt)} />
              <Field label="版本" value={String(run.version)} />
              <Field label="错误码" value={run.errorCode ?? '—'} />
              <Field label="错误摘要" value={run.errorSummary ?? '—'} />
            </dl>
          </div>
        ) : null}
      </AsyncState>

      <section aria-labelledby="sandbox-artifacts-title">
        <h2 id="sandbox-artifacts-title">Artifact</h2>
        <p className="koc-page__subtitle">下载链接仅在点击时生成，页面不会保存临时 URL。</p>
        <AsyncState
          isLoading={artifactsLoading}
          error={artifactsError}
          isEmpty={!artifactsLoading && (artifacts?.length ?? 0) === 0}
          emptyMessage="暂无 Artifact"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>类型</th>
                <th>内容类型</th>
                <th>大小</th>
                <th>分类</th>
                <th>保留至</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {(artifacts ?? []).map((artifact) => (
                <tr key={artifact.id}>
                  <td>{artifact.type}</td>
                  <td>{artifact.contentType}</td>
                  <td>{formatBytes(artifact.sizeBytes)}</td>
                  <td>{artifact.classification}</td>
                  <td>{formatTime(artifact.retentionUntil)}</td>
                  <td>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={download.isPending}
                      onClick={() => downloadArtifact(artifact.id)}
                    >
                      下载
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </section>
    </section>
  )
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function linkExecution(value: string | null, session: ReturnType<typeof useSession>['session']) {
  if (!value) return '—'
  return hasPermission(session, PERMISSIONS.EXECUTION_READ) ? (
    <Link to={`/executions/${value}`}>{value}</Link>
  ) : (
    value
  )
}

function linkAlarm(value: string | null, session: ReturnType<typeof useSession>['session']) {
  if (!value) return '—'
  return hasPermission(session, PERMISSIONS.ALARM_READ) ? (
    <Link to={`/alarms/${value}`}>{value}</Link>
  ) : (
    value
  )
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function formatBytes(value: number): string {
  return value < 1024 ? `${value} B` : `${(value / 1024).toFixed(1)} KiB`
}

function errorText(cause: unknown): string {
  return cause instanceof Error ? cause.message : '操作失败，请稍后重试。'
}
