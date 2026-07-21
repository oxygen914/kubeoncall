import { useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useExecution, useExecutionNodes } from './hooks'
import { executionTone } from './viewModels'

export function ExecutionDetailPage() {
  const { executionId = '' } = useParams()
  const navigate = useNavigate()
  const { data: execution, isLoading, error } = useExecution(executionId)
  const { data: nodes, isLoading: nodesLoading, error: nodesError } = useExecutionNodes(executionId)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate('/executions')}>
            ← 返回列表
          </Button>
          <h1>执行详情</h1>
        </div>
      </header>

      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !execution}>
        {execution ? (
          <div className="koc-card">
            <dl className="koc-fields">
              <div>
                <dt>执行 ID</dt>
                <dd className="koc-mono">{execution.id}</dd>
              </div>
              <div>
                <dt>摘要</dt>
                <dd>{execution.summary}</dd>
              </div>
              <div>
                <dt>类型</dt>
                <dd>{execution.type}</dd>
              </div>
              <div>
                <dt>状态</dt>
                <dd>
                  <StatusBadge tone={executionTone(execution.status)}>
                    {execution.status}
                  </StatusBadge>
                </dd>
              </div>
              <div>
                <dt>风险</dt>
                <dd>{execution.riskLevel ?? '—'}</dd>
              </div>
              <div>
                <dt>当前节点</dt>
                <dd>{execution.currentNode ?? '—'}</dd>
              </div>
              <div>
                <dt>结果</dt>
                <dd>{execution.resultSummary ?? '—'}</dd>
              </div>
              <div>
                <dt>错误码</dt>
                <dd>{execution.errorCode ?? '—'}</dd>
              </div>
              <div>
                <dt>requestId</dt>
                <dd className="koc-mono">{execution.requestId ?? '—'}</dd>
              </div>
              <div>
                <dt>traceId</dt>
                <dd className="koc-mono">{execution.traceId ?? '—'}</dd>
              </div>
              <div>
                <dt>版本</dt>
                <dd>{execution.version}</dd>
              </div>
            </dl>
          </div>
        ) : null}
      </AsyncState>

      <section className="koc-detail__timeline" aria-labelledby="execution-nodes-title">
        <h2 id="execution-nodes-title">节点时间线</h2>
        <AsyncState
          isLoading={nodesLoading}
          error={nodesError}
          isEmpty={!nodesLoading && (nodes?.length ?? 0) === 0}
          emptyMessage="暂无执行节点"
        >
          <ol className="koc-timeline">
            {(nodes ?? []).map((node) => (
              <li key={node.id} className="koc-timeline__item">
                <div className="koc-timeline__time">
                  {formatTime(node.startedAt)}
                  <br />第 {node.attempt} 次
                </div>
                <div className="koc-timeline__body">
                  <StatusBadge tone={executionTone(node.status)}>{node.status}</StatusBadge>
                  <strong className="koc-execution-node__name">{node.nodeName}</strong>
                  {node.outputSummary ? <p>{node.outputSummary}</p> : null}
                  {node.errorCode ? (
                    <p className="koc-alert koc-alert--error">错误码：{node.errorCode}</p>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        </AsyncState>
      </section>
    </section>
  )
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
