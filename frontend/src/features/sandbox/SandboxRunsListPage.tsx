import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useSandboxRunList } from './hooks'
import type { SandboxRunStatus } from './api'
import { sandboxRunTone } from './viewModels'
import { listReturnState, mergeSearchParams } from '@/lib/navigation'

const STATUSES: SandboxRunStatus[] = [
  'PENDING',
  'DISPATCHING',
  'RUNNING',
  'COLLECTING',
  'SUCCEEDED',
  'FAILED',
  'TIMED_OUT',
  'CANCELLED',
]

export function SandboxRunsListPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const statusValue = searchParams.get('status') ?? ''
  const status = STATUSES.includes(statusValue as SandboxRunStatus)
    ? (statusValue as SandboxRunStatus)
    : ''
  const executionId = (searchParams.get('executionId') ?? '').slice(0, 40)
  const alarmId = (searchParams.get('alarmId') ?? '').slice(0, 40)
  const updateFilters = (updates: Record<string, string | null>) => {
    setSearchParams(mergeSearchParams(searchParams, updates), { replace: true })
  }
  const openDetail = (runId: string) => {
    navigate(`/sandbox-runs/${encodeURIComponent(runId)}`, {
      state: listReturnState(location.pathname, location.search),
    })
  }
  const {
    data: runs,
    isLoading,
    error,
    isFetching,
  } = useSandboxRunList({
    status,
    executionId: executionId || undefined,
    alarmId: alarmId || undefined,
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>Sandbox 运行</h1>
        <p className="koc-page__subtitle">隔离诊断、校验与仿真的运行状态；生产动作仍需独立审批。</p>
      </header>

      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => updateFilters({ status: event.target.value || null })}
          >
            <option value="">全部</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>执行 ID</span>
          <input
            value={executionId}
            onChange={(event) => updateFilters({ executionId: event.target.value || null })}
            maxLength={40}
            placeholder="关联 Workflow Execution"
          />
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>告警 ID</span>
          <input
            value={alarmId}
            onChange={(event) => updateFilters({ alarmId: event.target.value || null })}
            maxLength={40}
            placeholder="关联告警"
          />
        </label>
      </form>

      <AsyncState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && (runs?.length ?? 0) === 0}
        emptyMessage="没有匹配的 Sandbox Run"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>Run ID</th>
              <th>模式 / 工具</th>
              <th>状态</th>
              <th>进度</th>
              <th>清理</th>
              <th>关联执行</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            {(runs ?? []).map((run) => (
              <tr
                key={run.id}
                className="koc-table__row"
                tabIndex={0}
                onClick={() => openDetail(run.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    openDetail(run.id)
                  }
                }}
              >
                <td className="koc-mono">{run.id}</td>
                <td>
                  <strong>{run.mode}</strong>
                  <br />
                  <span className="koc-text-muted">
                    {run.toolId}@{run.toolVersion}
                  </span>
                </td>
                <td>
                  <StatusBadge tone={sandboxRunTone(run.status)}>{run.status}</StatusBadge>
                </td>
                <td>
                  {run.progress}%{run.stage ? ` · ${run.stage}` : ''}
                </td>
                <td>{run.cleanupStatus}</td>
                <td className="koc-mono">{run.executionId ?? '—'}</td>
                <td>
                  {formatTime(run.createdAt)}
                  {isFetching ? ' · 刷新中…' : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </AsyncState>
    </section>
  )
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString()
}
