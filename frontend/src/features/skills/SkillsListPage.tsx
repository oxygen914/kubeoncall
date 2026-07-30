import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { TaskStatusPanel } from '@/features/tasks/TaskStatusPanel'
import { useReloadSkills, useSkills } from './hooks'
import type { SkillLoadStatus } from './api'
import { listReturnState, mergeSearchParams, readPageParam } from '@/lib/navigation'

const PAGE_SIZE = 20
const LOAD_STATUSES: SkillLoadStatus[] = ['DISCOVERED', 'LOADING', 'LOADED', 'FAILED', 'DISABLED']

export function SkillsListPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { session } = useSession()
  const page = readPageParam(searchParams.get('page'))
  const loadStatusValue = searchParams.get('loadStatus') ?? ''
  const loadStatus = LOAD_STATUSES.includes(loadStatusValue as SkillLoadStatus)
    ? (loadStatusValue as SkillLoadStatus)
    : ''
  const queryText = (searchParams.get('q') ?? '').slice(0, 255)
  const updateFilters = (updates: Record<string, string | null>) => {
    setSearchParams(mergeSearchParams(searchParams, { ...updates, page: null }), {
      replace: true,
    })
  }
  const updatePage = (nextPage: number) => {
    setSearchParams(mergeSearchParams(searchParams, { page: nextPage === 1 ? null : nextPage }))
  }
  const openDetail = (skillId: string) => {
    navigate(`/skills/${encodeURIComponent(skillId)}`, {
      state: listReturnState(location.pathname, location.search),
    })
  }
  const query = useSkills({ page, size: PAGE_SIZE, loadStatus, query: queryText })
  const reload = useReloadSkills()
  const canManage = hasPermission(session, PERMISSIONS.SKILL_MANAGE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>Skill</h1>
          <p className="koc-page__subtitle">查看发现、加载、启停和匹配语义状态。</p>
        </div>
        {canManage ? (
          <Button size="sm" disabled={reload.isPending} onClick={() => reload.mutate()}>
            {reload.isPending ? '提交中…' : '重新加载'}
          </Button>
        ) : null}
      </header>
      {reload.data ? <TaskStatusPanel taskId={reload.data.taskId} /> : null}
      {reload.isError ? <p role="alert">Skill 重新加载任务创建失败。</p> : null}
      <form className="koc-filters" onSubmit={(event) => event.preventDefault()}>
        <label className="koc-filter">
          <span>加载状态</span>
          <select
            value={loadStatus}
            onChange={(event) => {
              updateFilters({ loadStatus: event.target.value || null })
            }}
          >
            <option value="">全部</option>
            {LOAD_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </label>
        <label className="koc-filter koc-filter--grow">
          <span>名称 / Tag</span>
          <input
            value={queryText}
            maxLength={255}
            onChange={(event) => {
              updateFilters({ q: event.target.value || null })
            }}
          />
        </label>
      </form>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && (query.data?.data.length ?? 0) === 0}
        emptyMessage="没有匹配的 Skill"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>版本</th>
              <th>启用</th>
              <th>加载状态</th>
              <th>Tags</th>
              <th>适用任务</th>
            </tr>
          </thead>
          <tbody>
            {(query.data?.data ?? []).map((skill) => (
              <tr
                key={skill.id}
                className="koc-table__row"
                tabIndex={0}
                onClick={() => openDetail(skill.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    openDetail(skill.id)
                  }
                }}
              >
                <td className="koc-table__cell--primary">{skill.name}</td>
                <td>{skill.skillVersion ?? '—'}</td>
                <td>{skill.enabled ? '是' : '否'}</td>
                <td>
                  <StatusBadge tone={skillTone(skill.loadStatus)}>{skill.loadStatus}</StatusBadge>
                </td>
                <td>{skill.tags.join(', ') || '—'}</td>
                <td>{skill.applicableTasks.join(', ') || '—'}</td>
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
                onClick={() => updatePage(page - 1)}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!query.data.page.hasNext}
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

function skillTone(status: SkillLoadStatus): StatusTone {
  if (status === 'LOADED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'LOADING') return 'info'
  return 'neutral'
}
