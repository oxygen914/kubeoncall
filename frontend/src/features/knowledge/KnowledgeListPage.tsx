import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { TaskStatusPanel } from '@/features/tasks/TaskStatusPanel'
import { useCreateKnowledgeImport, useKnowledgeDocuments, useKnowledgeImports } from './hooks'
import type { DuplicatePolicy, KnowledgeDocumentStatus, KnowledgeImportType } from './api'

const PAGE_SIZE = 20

export function KnowledgeListPage() {
  const navigate = useNavigate()
  const { session } = useSession()
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<KnowledgeDocumentStatus | ''>('ACTIVE')
  const [sourceType, setSourceType] = useState('')
  const [showImport, setShowImport] = useState(false)
  const query = useKnowledgeDocuments({ page, size: PAGE_SIZE, status, sourceType })
  const importsQuery = useKnowledgeImports()
  const canImport = hasPermission(session, PERMISSIONS.KNOWLEDGE_WRITE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>知识库</h1>
          <p className="koc-page__subtitle">治理文档版本、索引状态和异步导入。</p>
        </div>
        {canImport ? (
          <Button size="sm" onClick={() => setShowImport((value) => !value)}>
            {showImport ? '收起导入' : '导入知识'}
          </Button>
        ) : null}
      </header>

      {showImport && canImport ? <KnowledgeImportCard /> : null}

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as KnowledgeDocumentStatus | '')
              setPage(1)
            }}
          >
            <option value="">全部</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="DELETED">DELETED</option>
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>来源类型</span>
          <input
            value={sourceType}
            maxLength={64}
            placeholder="RUNBOOK / DOCUMENT"
            onChange={(event) => {
              setSourceType(event.target.value)
              setPage(1)
            }}
          />
        </label>
      </form>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && (query.data?.data.length ?? 0) === 0}
        emptyMessage="没有匹配的知识文档"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>状态</th>
              <th>来源</th>
              <th>数据集版本</th>
              <th>文档版本</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            {(query.data?.data ?? []).map((document) => (
              <tr
                key={document.id}
                className="koc-table__row"
                tabIndex={0}
                onClick={() => navigate(`/knowledge/${document.id}`)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    navigate(`/knowledge/${document.id}`)
                  }
                }}
              >
                <td className="koc-table__cell--primary">{document.title}</td>
                <td>
                  <StatusBadge tone={document.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {document.status}
                  </StatusBadge>
                </td>
                <td>{document.sourceType}</td>
                <td>{document.datasetVersion ?? '—'}</td>
                <td>{document.version}</td>
                <td>{formatTime(document.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pagination
          page={page}
          totalPages={query.data?.page.totalPages ?? 0}
          totalElements={query.data?.page.totalElements ?? 0}
          hasNext={query.data?.page.hasNext ?? false}
          isFetching={query.isFetching}
          onPage={setPage}
        />
      </AsyncState>

      <section className="koc-card" aria-labelledby="recent-knowledge-imports">
        <h2 id="recent-knowledge-imports">最近导入</h2>
        <AsyncState
          isLoading={importsQuery.isLoading}
          error={importsQuery.error}
          isEmpty={!importsQuery.isLoading && (importsQuery.data?.data.length ?? 0) === 0}
          emptyMessage="暂无导入任务"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>导入 ID</th>
                <th>类型</th>
                <th>状态</th>
                <th>进度</th>
                <th>成功 / 失败</th>
                <th>更新时间</th>
              </tr>
            </thead>
            <tbody>
              {(importsQuery.data?.data ?? []).map((item) => (
                <tr key={item.id}>
                  <td className="koc-mono">{item.id}</td>
                  <td>{item.importType}</td>
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
                  <td>
                    {item.processedCount} / {item.totalCount ?? '—'}
                  </td>
                  <td>
                    {item.succeededCount} / {item.failedCount}
                  </td>
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

function KnowledgeImportCard() {
  const mutation = useCreateKnowledgeImport()
  const [file, setFile] = useState<File | null>(null)
  const [importType, setImportType] = useState<KnowledgeImportType>('DOCUMENT')
  const [datasetVersion, setDatasetVersion] = useState('')
  const [duplicatePolicy, setDuplicatePolicy] = useState<DuplicatePolicy>('SKIP')
  const [dryRun, setDryRun] = useState(false)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!file || mutation.isPending) return
    mutation.mutate({
      file,
      importType,
      datasetVersion: datasetVersion || undefined,
      duplicatePolicy,
      dryRun,
    })
  }

  return (
    <section className="koc-card" aria-labelledby="knowledge-import-title">
      <h2 id="knowledge-import-title">创建导入任务</h2>
      <form className="koc-filters" onSubmit={submit}>
        <label className="koc-filter koc-filter--grow">
          <span>文件</span>
          <input
            type="file"
            required
            accept=".md,.txt,.jsonl,application/json,text/plain,text/markdown"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </label>
        <label className="koc-filter">
          <span>导入类型</span>
          <select
            value={importType}
            onChange={(event) => setImportType(event.target.value as KnowledgeImportType)}
          >
            <option value="DOCUMENT">DOCUMENT</option>
            <option value="JSONL">JSONL</option>
            <option value="RUNBOOK">RUNBOOK</option>
          </select>
        </label>
        <label className="koc-filter">
          <span>重复策略</span>
          <select
            value={duplicatePolicy}
            onChange={(event) => setDuplicatePolicy(event.target.value as DuplicatePolicy)}
          >
            <option value="SKIP">SKIP</option>
            <option value="REPLACE">REPLACE</option>
            <option value="FAIL">FAIL</option>
          </select>
        </label>
        <label className="koc-filter">
          <span>数据集版本</span>
          <input
            value={datasetVersion}
            maxLength={128}
            onChange={(event) => setDatasetVersion(event.target.value)}
          />
        </label>
        <label className="koc-filter">
          <span>
            <input
              type="checkbox"
              checked={dryRun}
              onChange={(event) => setDryRun(event.target.checked)}
            />{' '}
            Dry run
          </span>
        </label>
        <Button type="submit" size="sm" disabled={!file || mutation.isPending}>
          {mutation.isPending ? '提交中…' : '开始导入'}
        </Button>
      </form>
      {mutation.isError ? <p role="alert">导入任务创建失败，请根据请求 ID 排查。</p> : null}
      {mutation.data ? (
        <>
          {mutation.data.importId ? (
            <p>
              导入记录：<code>{mutation.data.importId}</code>
            </p>
          ) : null}
          <TaskStatusPanel taskId={mutation.data.taskId} />
        </>
      ) : null}
    </section>
  )
}

function Pagination({
  page,
  totalPages,
  totalElements,
  hasNext,
  isFetching,
  onPage,
}: {
  page: number
  totalPages: number
  totalElements: number
  hasNext: boolean
  isFetching: boolean
  onPage: (page: number) => void
}) {
  return (
    <div className="koc-pagination">
      <span className="koc-pagination__info">
        第 {page} / {totalPages} 页 · 共 {totalElements} 条{isFetching ? ' （刷新中…）' : ''}
      </span>
      <div className="koc-pagination__actions">
        <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          上一页
        </Button>
        <Button variant="ghost" size="sm" disabled={!hasNext} onClick={() => onPage(page + 1)}>
          下一页
        </Button>
      </div>
    </div>
  )
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
