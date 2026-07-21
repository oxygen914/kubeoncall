import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { TaskStatusPanel } from '@/features/tasks/TaskStatusPanel'
import { useCreateMemoryExtraction, useMemories, useMemoryExtractions } from './hooks'
import type { MemoryExtractionInput, MemoryStatus } from './api'

const PAGE_SIZE = 20

export function MemoryListPage() {
  const navigate = useNavigate()
  const { session } = useSession()
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<MemoryStatus | ''>('ACTIVE')
  const [memoryType, setMemoryType] = useState('')
  const [showExtraction, setShowExtraction] = useState(false)
  const query = useMemories({ page, size: PAGE_SIZE, status, memoryType })
  const extractionsQuery = useMemoryExtractions()
  const canExtract = hasPermission(session, PERMISSIONS.MEMORY_WRITE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>记忆治理</h1>
          <p className="koc-page__subtitle">查看证据、质量评分和结构化提取任务。</p>
        </div>
        {canExtract ? (
          <Button size="sm" onClick={() => setShowExtraction((value) => !value)}>
            {showExtraction ? '收起提取' : '创建提取任务'}
          </Button>
        ) : null}
      </header>

      {showExtraction && canExtract ? <MemoryExtractionCard /> : null}

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as MemoryStatus | '')
              setPage(1)
            }}
          >
            <option value="">全部</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="DELETED">DELETED</option>
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>记忆类型</span>
          <input
            value={memoryType}
            maxLength={64}
            placeholder="SERVICE_FACT / INCIDENT_SUMMARY"
            onChange={(event) => {
              setMemoryType(event.target.value)
              setPage(1)
            }}
          />
        </label>
      </form>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && (query.data?.data.length ?? 0) === 0}
        emptyMessage="没有匹配的记忆条目"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>记忆 ID</th>
              <th>类型</th>
              <th>状态</th>
              <th>质量评分</th>
              <th>关联执行</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            {(query.data?.data ?? []).map((memory) => (
              <tr
                key={memory.id}
                className="koc-table__row"
                tabIndex={0}
                onClick={() => navigate(`/memory/${memory.id}`)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    navigate(`/memory/${memory.id}`)
                  }
                }}
              >
                <td className="koc-mono">{memory.id}</td>
                <td>{memory.memoryType}</td>
                <td>
                  <StatusBadge tone={memory.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {memory.status}
                  </StatusBadge>
                </td>
                <td>{formatScore(memory.qualityScore)}</td>
                <td className="koc-mono">{memory.sourceExecutionId ?? '—'}</td>
                <td>{formatTime(memory.updatedAt)}</td>
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
                onClick={() => setPage(page - 1)}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!query.data.page.hasNext}
                onClick={() => setPage(page + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : null}
      </AsyncState>

      <section className="koc-card" aria-labelledby="recent-memory-extractions">
        <h2 id="recent-memory-extractions">最近提取任务</h2>
        <AsyncState
          isLoading={extractionsQuery.isLoading}
          error={extractionsQuery.error}
          isEmpty={!extractionsQuery.isLoading && (extractionsQuery.data?.data.length ?? 0) === 0}
          emptyMessage="暂无提取任务"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>提取 ID</th>
                <th>来源</th>
                <th>状态</th>
                <th>证据数</th>
                <th>记忆数</th>
                <th>更新时间</th>
              </tr>
            </thead>
            <tbody>
              {(extractionsQuery.data?.data ?? []).map((item) => (
                <tr key={item.id}>
                  <td className="koc-mono">{item.id}</td>
                  <td>
                    {item.sourceType} / <span className="koc-mono">{item.sourcePublicId}</span>
                  </td>
                  <td>
                    <StatusBadge
                      tone={
                        item.status === 'SUCCEEDED'
                          ? 'success'
                          : item.status === 'FAILED'
                            ? 'danger'
                            : 'info'
                      }
                    >
                      {item.status}
                    </StatusBadge>
                  </td>
                  <td>{item.evidenceCount}</td>
                  <td>{item.memoryCount}</td>
                  <td>{formatTime(item.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </section>
    </section>
  )
}

function MemoryExtractionCard() {
  const mutation = useCreateMemoryExtraction()
  const [sourceType, setSourceType] = useState<MemoryExtractionInput['sourceType']>('EXECUTION')
  const [sourcePublicId, setSourcePublicId] = useState('')
  const [dedupeKey, setDedupeKey] = useState('')
  const [memoryType, setMemoryType] =
    useState<MemoryExtractionInput['memoryType']>('INCIDENT_SUMMARY')
  const [scope, setScope] = useState<MemoryExtractionInput['scope']>('GLOBAL')
  const [subject, setSubject] = useState('')
  const [content, setContent] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!sourcePublicId.trim() || !dedupeKey.trim() || !subject.trim() || !content.trim()) return
    mutation.mutate({
      sourceType,
      sourcePublicId,
      dedupeKey,
      memoryType,
      scope,
      subject,
      content,
    })
  }

  return (
    <section className="koc-card" aria-labelledby="memory-extraction-title">
      <h2 id="memory-extraction-title">结构化提取</h2>
      <form className="koc-filters" onSubmit={submit}>
        <label className="koc-filter">
          <span>来源类型</span>
          <select
            value={sourceType}
            onChange={(event) =>
              setSourceType(event.target.value as MemoryExtractionInput['sourceType'])
            }
          >
            <option value="SESSION">SESSION</option>
            <option value="EXECUTION">EXECUTION</option>
            <option value="ALARM">ALARM</option>
            <option value="MANUAL">MANUAL</option>
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>来源 ID</span>
          <input
            required
            value={sourcePublicId}
            maxLength={128}
            onChange={(event) => setSourcePublicId(event.target.value)}
          />
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>幂等业务键</span>
          <input
            required
            value={dedupeKey}
            maxLength={255}
            onChange={(event) => setDedupeKey(event.target.value)}
          />
        </label>
        <label className="koc-filter">
          <span>记忆类型</span>
          <select
            value={memoryType}
            onChange={(event) =>
              setMemoryType(event.target.value as MemoryExtractionInput['memoryType'])
            }
          >
            <option value="INCIDENT_SUMMARY">INCIDENT_SUMMARY</option>
            <option value="SERVICE_FACT">SERVICE_FACT</option>
            <option value="DEVICE_HISTORY">DEVICE_HISTORY</option>
            <option value="KNOWN_PITFALL">KNOWN_PITFALL</option>
            <option value="USER_PREFERENCE">USER_PREFERENCE</option>
            <option value="USER_NOTE">USER_NOTE</option>
          </select>
        </label>
        <label className="koc-filter">
          <span>记忆范围</span>
          <select
            value={scope}
            onChange={(event) => setScope(event.target.value as MemoryExtractionInput['scope'])}
          >
            <option value="GLOBAL">GLOBAL</option>
            <option value="SERVICE">SERVICE</option>
            <option value="RESOURCE">RESOURCE</option>
            <option value="FINGERPRINT">FINGERPRINT</option>
            <option value="SESSION">SESSION</option>
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>主题</span>
          <input
            required
            value={subject}
            maxLength={2000}
            placeholder="例如：节点 NotReady 处置结论"
            onChange={(event) => setSubject(event.target.value)}
          />
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>待提取证据</span>
          <textarea
            required
            value={content}
            maxLength={100000}
            rows={4}
            placeholder="粘贴执行结论、告警上下文或会话证据"
            onChange={(event) => setContent(event.target.value)}
          />
        </label>
        <Button type="submit" size="sm" disabled={mutation.isPending}>
          {mutation.isPending ? '提交中…' : '开始提取'}
        </Button>
      </form>
      {mutation.isError ? <p role="alert">提取任务创建失败。</p> : null}
      {mutation.data ? (
        <>
          {mutation.data.extractionId ? (
            <p>
              提取记录：<code>{mutation.data.extractionId}</code>
            </p>
          ) : null}
          <TaskStatusPanel taskId={mutation.data.taskId} />
        </>
      ) : null}
    </section>
  )
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function formatScore(value: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}
