import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useExecution, useExecutionNodes } from './hooks'
import { executionTone, operationClosureTone, operationEscalationTone } from './viewModels'
import { resolveListReturnPath } from '@/lib/navigation'

export function ExecutionDetailPage() {
  const { executionId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { data: execution, isLoading, error } = useExecution(executionId)
  const { data: nodes, isLoading: nodesLoading, error: nodesError } = useExecutionNodes(executionId)
  const returnTo = resolveListReturnPath(location.state, '/executions')

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate(returnTo)}>
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
                <dd>{execution.currentNode ? nodeLabel(execution.currentNode) : '—'}</dd>
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
                <dt>错误摘要</dt>
                <dd>{execution.errorSummary ?? '—'}</dd>
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

      {execution?.operationClosure ? (
        <section className="koc-card" aria-labelledby="operation-closure-title">
          <h2 id="operation-closure-title">操作闭环</h2>
          <dl className="koc-fields">
            <div>
              <dt>闭环阶段</dt>
              <dd>
                <StatusBadge tone={operationClosureTone(execution.operationClosure.phase)}>
                  {execution.operationClosure.phase}
                </StatusBadge>
              </dd>
            </div>
            <div>
              <dt>operationId</dt>
              <dd className="koc-mono">{execution.operationClosure.operationId}</dd>
            </div>
            <div>
              <dt>动作</dt>
              <dd>
                {execution.operationClosure.executorKind}.{execution.operationClosure.action}
              </dd>
            </div>
            <div>
              <dt>目标</dt>
              <dd>{execution.operationClosure.target || '—'}</dd>
            </div>
            <div>
              <dt>开始</dt>
              <dd>{formatTime(execution.operationClosure.startedAt)}</dd>
            </div>
            <div>
              <dt>完成</dt>
              <dd>{formatTime(execution.operationClosure.finishedAt)}</dd>
            </div>
            <div>
              <dt>错误</dt>
              <dd>{execution.operationClosure.errorSummary || '—'}</dd>
            </div>
          </dl>
          {execution.operationClosure.escalation ? (
            <div className="koc-alert koc-alert--warning">
              <strong>人工升级：</strong>{' '}
              <StatusBadge
                tone={operationEscalationTone(execution.operationClosure.escalation.status)}
              >
                {execution.operationClosure.escalation.status}
              </StatusBadge>{' '}
              · {execution.operationClosure.escalation.severity}
              <p>{execution.operationClosure.escalation.summary}</p>
              {execution.operationClosure.escalation.errorSummary ? (
                <p>{execution.operationClosure.escalation.errorSummary}</p>
              ) : null}
            </div>
          ) : null}
          <details>
            <summary>查看脱敏闭环事实</summary>
            <pre className="koc-json">
              {JSON.stringify(execution.operationClosure.details, null, 2)}
            </pre>
          </details>
        </section>
      ) : null}

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
                  <strong className="koc-execution-node__name">{nodeLabel(node.nodeName)}</strong>
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

function nodeLabel(value: string): string {
  const labels: Record<string, string> = {
    executorThinkNode: '生成执行计划',
    verifierThinkNode: '安全校验',
    verifierApprovalNode: '人工审批',
    executorExecuteNode: '执行操作',
    operationClosureNode: '恢复验证与回滚',
  }
  return labels[value] ?? value
}
