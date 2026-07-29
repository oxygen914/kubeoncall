import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '@/api/client'
import { ApiError } from '@/api/errors'
import { Button } from '@/components/ui/Button'
import { Icon } from '@/components/ui/Icon'

interface AskResult {
  executionId: string
  status: string
  message: string
  sessionId: string | null
  details: Record<string, unknown>
}

interface ConversationTurn {
  id: string
  question: string
  result?: AskResult
}

const suggestions = [
  '分析当前集群中处于 Pending 状态的 Pod',
  '最近有哪些 P1/P2 告警需要优先处理？',
  '检查失败执行并归纳主要原因',
]

/** Ask page: conversational operations analysis with governed execution context. */
export function AskPage() {
  const [question, setQuestion] = useState('')
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [turns, setTurns] = useState<ConversationTurn[]>([])
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (request: { question: string; sessionId: string | null }) =>
      api.post<AskResult>('/api/v1/ask', request),
    onMutate: ({ question: submittedQuestion }) => {
      setPendingQuestion(submittedQuestion)
      setQuestion('')
    },
    onSuccess: (result, request) => {
      setTurns((current) => [
        ...current,
        {
          id: result.executionId || `${Date.now()}`,
          question: request.question,
          result,
        },
      ])
      setSessionId(result.sessionId)
      setPendingQuestion(null)
    },
    onError: (_error, request) => {
      setQuestion(request.question)
      setPendingQuestion(null)
    },
  })

  const latestResult = turns.at(-1)?.result ?? null
  const error = mutation.error
  const errorMessage = error instanceof ApiError ? error.message : error ? '请求失败，请稍后重试。' : null

  function submitQuestion(value: string) {
    const normalized = value.trim()
    if (!normalized || mutation.isPending) return
    mutation.mutate({ question: normalized, sessionId })
  }

  return (
    <section className="koc-ask-page">
      <header className="koc-ask-header">
        <div>
          <p className="koc-ask-header__eyebrow">AI OPERATIONS ASSISTANT</p>
          <h1>提问</h1>
          <p>结合监控、告警、知识与执行上下文进行分析；变更操作始终经过风险校验和审批。</p>
        </div>
        <div className="koc-ask-header__state" aria-label="AI 助手状态">
          <span className="koc-live-status">
            <i aria-hidden="true" />
            运维助手
          </span>
          <span>{sessionId ? '连续会话' : '新会话'}</span>
        </div>
      </header>

      <div className="koc-ask-layout">
        <div className="koc-ask-workspace">
          <div className="koc-ask-transcript" aria-live="polite">
            {turns.length === 0 && !pendingQuestion ? (
              <div className="koc-ask-empty">
                <span className="koc-ask-empty__icon" aria-hidden="true">
                  <Icon name="ask" size={24} />
                </span>
                <h2>从当前运维问题开始</h2>
                <p>
                  我可以协助诊断 Kubernetes 资源、解释告警和执行记录，并为需要变更的操作生成受控任务。
                </p>
                <div className="koc-ask-suggestions" aria-label="建议问题">
                  {suggestions.map((suggestion) => (
                    <button key={suggestion} type="button" onClick={() => setQuestion(suggestion)}>
                      {suggestion}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}

            {turns.map((turn) => (
              <ConversationItem key={turn.id} turn={turn} />
            ))}

            {pendingQuestion ? (
              <>
                <article className="koc-ask-message koc-ask-message--user">
                  <div className="koc-ask-message__label">你</div>
                  <div className="koc-ask-message__content">{pendingQuestion}</div>
                </article>
                <article className="koc-ask-message koc-ask-message--assistant">
                  <div className="koc-ask-message__label">
                    <Icon name="ask" size={15} />
                    KubeOnCall AI
                  </div>
                  <div className="koc-ask-thinking">
                    <span className="koc-spinner koc-spinner--sm" aria-hidden="true" />
                    正在读取上下文并生成分析…
                  </div>
                </article>
              </>
            ) : null}
          </div>

          <form
            className="koc-ask-composer"
            onSubmit={(event) => {
              event.preventDefault()
              submitQuestion(question)
            }}
          >
            <label htmlFor="ask-question">向 KubeOnCall 提问</label>
            <div className="koc-ask-composer__field">
              <textarea
                id="ask-question"
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault()
                    submitQuestion(question)
                  }
                }}
                maxLength={4000}
                rows={3}
                aria-describedby="ask-helper"
                placeholder="描述资源、现象和期望，例如：分析 payments 命名空间 Pending Pod 的原因"
              />
              <Button
                type="submit"
                variant="primary"
                size="md"
                disabled={mutation.isPending || !question.trim()}
                aria-label="发送问题"
              >
                发送
              </Button>
            </div>
            <div className="koc-ask-composer__footer">
              <span id="ask-helper">Enter 发送，Shift + Enter 换行</span>
              <span>{question.length}/4000</span>
            </div>
            {errorMessage ? (
              <div className="koc-ask-error" role="alert">
                <span>{errorMessage}</span>
                <Button type="submit" variant="ghost" size="sm" disabled={!question.trim()}>
                  重试
                </Button>
              </div>
            ) : null}
          </form>
        </div>

        <ExecutionContext result={latestResult} />
      </div>
    </section>
  )
}

function ConversationItem({ turn }: { turn: ConversationTurn }) {
  const result = turn.result
  if (!result) return null

  return (
    <>
      <article className="koc-ask-message koc-ask-message--user">
        <div className="koc-ask-message__label">你</div>
        <div className="koc-ask-message__content">{turn.question}</div>
      </article>
      <article className="koc-ask-message koc-ask-message--assistant">
        <div className="koc-ask-message__label">
          <Icon name="ask" size={15} />
          KubeOnCall AI
          <StatusBadge status={result.status} />
        </div>
        <div className="koc-ask-message__content koc-ask-answer">{result.message}</div>
        <p className="koc-ask-disclaimer">AI 生成内容，请结合下方执行依据与实际集群状态核验。</p>
      </article>
    </>
  )
}

function ExecutionContext({ result }: { result: AskResult | null }) {
  const plan = asRecord(result?.details.plan)
  const tasks = Array.isArray(plan?.tasks) ? plan.tasks.map(asRecord).filter(Boolean) : []
  const approval = asRecord(result?.details.approval)
  const pause = asRecord(approval?.pause)
  const riskReasons = Array.isArray(pause?.riskReasons)
    ? pause.riskReasons.map(String).filter(Boolean)
    : []
  const waitingApproval =
    result?.status === 'WAITING' || result?.status === 'WAITING_APPROVAL' || Boolean(pause)

  return (
    <aside className="koc-ask-context" aria-label="执行上下文">
      <div className="koc-ask-context__header">
        <div>
          <span>执行上下文</span>
          <h2>分析与操作边界</h2>
        </div>
        <Icon name="shield" size={18} />
      </div>

      {!result ? (
        <div className="koc-ask-context__empty">
          <p>提交问题后，这里会显示执行状态、任务计划、风险判断和审批入口。</p>
        </div>
      ) : (
        <>
          <dl className="koc-ask-context__facts">
            <div>
              <dt>状态</dt>
              <dd>
                <StatusBadge status={result.status} />
              </dd>
            </div>
            <div>
              <dt>执行 ID</dt>
              <dd className="koc-mono" title={result.executionId}>
                {result.executionId}
              </dd>
            </div>
            <div>
              <dt>会话 ID</dt>
              <dd className="koc-mono" title={result.sessionId ?? undefined}>
                {result.sessionId ?? '—'}
              </dd>
            </div>
          </dl>

          <section className="koc-ask-context__section">
            <h3>任务计划</h3>
            {tasks.length > 0 ? (
              <ol className="koc-ask-task-list">
                {tasks.map((task, index) => (
                  <li key={String(task?.taskId ?? index)}>
                    <span>{index + 1}</span>
                    <div>
                      <strong>{String(task?.description ?? task?.taskType ?? '运维任务')}</strong>
                      <small>
                        {String(task?.target ?? '未指定目标')} · {String(task?.riskLevel ?? '未知风险')}
                      </small>
                    </div>
                  </li>
                ))}
              </ol>
            ) : (
              <p>本次响应未返回结构化任务计划。</p>
            )}
          </section>

          <section className="koc-ask-context__section">
            <h3>安全策略</h3>
            <div className={waitingApproval ? 'koc-governance koc-governance--warning' : 'koc-governance'}>
              <Icon name={waitingApproval ? 'approval' : 'shield'} size={17} />
              <div>
                <strong>{waitingApproval ? '等待人工审批' : '已执行风险校验'}</strong>
                <p>
                  {waitingApproval
                    ? '外部状态变更不会由 AI 直接执行，审批通过后才会恢复任务。'
                    : '只读分析可自动运行；重启、扩缩容和配置变更需要人工确认。'}
                </p>
              </div>
            </div>
            {riskReasons.length > 0 ? (
              <ul className="koc-ask-risk-list">
                {riskReasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            ) : null}
          </section>

          <p className="koc-ask-context__boundary">
            {waitingApproval
              ? '审批状态以当前响应为准；接入持久化执行入口后才会出现在统一审批与执行列表中。'
              : '当前页面展示同步分析结果；统一执行记录由持久化工作流入口维护。'}
          </p>

          <details className="koc-ask-evidence">
            <summary>查看原始执行依据</summary>
            <pre className="koc-mono">{JSON.stringify(result.details, null, 2)}</pre>
          </details>
        </>
      )}
    </aside>
  )
}

function StatusBadge({ status }: { status: string }) {
  const normalized = status.toUpperCase()
  const tone =
    normalized === 'SUCCESS' || normalized === 'SUCCEEDED'
      ? 'success'
      : normalized === 'FAILED' || normalized === 'REJECTED'
        ? 'danger'
        : normalized.includes('WAIT') || normalized === 'PENDING'
          ? 'warning'
          : 'info'

  return <span className={`koc-badge koc-badge--${tone}`}>{status}</span>
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}
