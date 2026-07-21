import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge, type StatusTone } from '@/components/ui/StatusBadge'
import { useTask } from './hooks'
import { isTaskTerminal } from './api'

export function TaskStatusPanel({ taskId }: { taskId: string }) {
  const { data: task, isLoading, error, isFetching } = useTask(taskId)

  return (
    <section className="koc-task-panel" aria-labelledby={`task-${taskId}-title`}>
      <h3 id={`task-${taskId}-title`}>异步任务</h3>
      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !task}>
        {task ? (
          <>
            <dl className="koc-fields">
              <div>
                <dt>任务 ID</dt>
                <dd className="koc-mono">{task.id}</dd>
              </div>
              <div>
                <dt>类型</dt>
                <dd>{task.taskType}</dd>
              </div>
              <div>
                <dt>状态</dt>
                <dd>
                  <StatusBadge tone={taskTone(task.status)}>{task.status}</StatusBadge>
                  {!isTaskTerminal(task.status) && isFetching ? ' 刷新中…' : ''}
                </dd>
              </div>
              <div>
                <dt>阶段</dt>
                <dd>{task.stage ?? '—'}</dd>
              </div>
              <div>
                <dt>错误码</dt>
                <dd>{task.errorCode ?? '—'}</dd>
              </div>
              <div>
                <dt>错误摘要</dt>
                <dd>{task.errorSummary ?? '—'}</dd>
              </div>
            </dl>
            {task.progressPercent != null ? (
              <div className="koc-task-panel__progress">
                <progress aria-label="任务进度" value={task.progressPercent} max={100} />
                <span>{task.progressPercent}%</span>
              </div>
            ) : null}
          </>
        ) : null}
      </AsyncState>
    </section>
  )
}

function taskTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (['FAILED', 'DEAD_LETTER'].includes(status)) return 'danger'
  if (['PENDING', 'RUNNING', 'RETRY', 'CANCEL_REQUESTED'].includes(status)) return 'info'
  return 'neutral'
}
