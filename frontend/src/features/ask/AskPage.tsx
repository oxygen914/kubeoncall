import { useEffect, useMemo, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/api/errors'
import { Button } from '@/components/ui/Button'
import { Icon } from '@/components/ui/Icon'
import {
  type AiConclusion,
  type EvidenceItem,
  type ExecutionDetail,
} from '@/features/executions/api'
import { useExecution } from '@/features/executions/hooks'
import { useMonitoringScope } from '@/features/monitoring/monitoringScopeContext'
import { createAskExecution, type CreateAskExecutionRequest } from './api'

interface ConversationTurn {
  id: string
  question: string
  executionId: string
  taskId: string
  submittedAt: string
}

interface Submission {
  request: CreateAskExecutionRequest
  idempotencyKey: string
}

const STORAGE_KEY = 'kubeoncall.ask.conversation.v2'
const suggestions = [
  '分析当前集群中处于 Pending 状态的 Pod',
  '最近有哪些 P1/P2 告警需要优先处理？',
  '检查失败执行并归纳主要原因',
]

/** Durable AI operations conversation backed by the unified Execution workflow. */
export function AskPage() {
  const [searchParams] = useSearchParams()
  const { scope } = useMonitoringScope()
  const restored = useMemo(loadConversation, [])
  const [question, setQuestion] = useState(() =>
    (searchParams.get('question') ?? '').slice(0, 4000),
  )
  const [sessionId, setSessionId] = useState<string | null>(restored.sessionId)
  const [turns, setTurns] = useState<ConversationTurn[]>(restored.turns)
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null)
  const [retrySubmission, setRetrySubmission] = useState<Submission | null>(null)

  const mutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: Submission) =>
      createAskExecution(request, idempotencyKey),
    onMutate: ({ request }) => {
      setPendingQuestion(request.question)
      setRetrySubmission(null)
      setQuestion('')
    },
    onSuccess: (result, submission) => {
      setTurns((current) => [
        ...current,
        {
          id: result.executionId,
          question: submission.request.question,
          executionId: result.executionId,
          taskId: result.taskId,
          submittedAt: new Date().toISOString(),
        },
      ])
      setPendingQuestion(null)
    },
    onError: (_error, submission) => {
      setQuestion(submission.request.question)
      setRetrySubmission(submission)
      setPendingQuestion(null)
    },
  })

  const latestTurn = turns.at(-1)
  const latestQuery = useExecution(latestTurn?.executionId)
  const latestResult = latestQuery.data ?? null

  useEffect(() => {
    const returnedSession = latestResult?.sessionId
    if (returnedSession && returnedSession !== sessionId) {
      setSessionId(returnedSession)
    }
  }, [latestResult?.sessionId, sessionId])

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ sessionId, turns }))
  }, [sessionId, turns])

  const error = mutation.error
  const errorMessage =
    error instanceof ApiError ? error.message : error ? '请求失败，请稍后重试。' : null

  function submitQuestion(value: string) {
    const normalized = value.trim()
    if (!normalized || mutation.isPending) return
    const request: CreateAskExecutionRequest = {
      question: normalized,
      sessionId: sessionId ?? undefined,
      cluster: scope.cluster || undefined,
      environment: scope.environment,
      namespace: scope.namespace,
    }
    mutation.mutate({ request, idempotencyKey: createIdempotencyKey() })
  }

  return (
    <section className="koc-ask-page">
      <header className="koc-ask-header">
        <div>
          <p className="koc-ask-header__eyebrow">AI OPERATIONS ASSISTANT</p>
          <h1>AI 诊断</h1>
          <p>结合监控、日志、事件与 SOP 形成可追溯结论；变更操作始终经过校验和审批。</p>
        </div>
        <div className="koc-ask-header__state" aria-label="AI 助手状态">
          <span className="koc-live-status">
            <i aria-hidden="true" />
            持久化工作流
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
                  我可以结合 Kubernetes 资源、Events、日志、指标和 SOP
                  进行诊断，并把需要变更的建议提交到受控执行流程。
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
              <ConversationItem key={turn.id} turn={turn} onSession={setSessionId} />
            ))}

            {pendingQuestion ? <PendingConversation question={pendingQuestion} /> : null}
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
                onChange={(event) => {
                  setQuestion(event.target.value)
                  setRetrySubmission(null)
                }}
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
              <span id="ask-helper">
                当前范围：{scope.cluster || '未选择集群'} / {scope.namespace || '全部 Namespace'}
              </span>
              <span>{question.length}/4000</span>
            </div>
            {errorMessage ? (
              <div className="koc-ask-error" role="alert">
                <span>{errorMessage}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={!retrySubmission || mutation.isPending}
                  onClick={() => retrySubmission && mutation.mutate(retrySubmission)}
                >
                  使用同一幂等键重试
                </Button>
              </div>
            ) : null}
          </form>
        </div>

        <ExecutionContext
          turn={latestTurn ?? null}
          result={latestResult}
          isLoading={Boolean(latestTurn) && latestQuery.isLoading}
          error={latestQuery.error}
        />
      </div>
    </section>
  )
}

function ConversationItem({
  turn,
  onSession,
}: {
  turn: ConversationTurn
  onSession: (sessionId: string) => void
}) {
  const query = useExecution(turn.executionId)
  const result = query.data

  useEffect(() => {
    if (result?.sessionId) onSession(result.sessionId)
  }, [onSession, result?.sessionId])

  const conclusion = result?.conclusions?.[0]
  const message = result?.answer || result?.resultSummary
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
          <StatusBadge status={result?.status ?? 'PENDING'} />
          {conclusion ? <PlannerModeBadge conclusion={conclusion} /> : null}
        </div>
        {query.error ? (
          <div className="koc-ask-message__content koc-ask-answer" role="alert">
            执行状态读取失败。后台任务不会因此中断，可稍后刷新或前往执行详情查看。
          </div>
        ) : message ? (
          <div className="koc-ask-message__content koc-ask-answer">{message}</div>
        ) : (
          <div className="koc-ask-thinking">
            <span className="koc-spinner koc-spinner--sm" aria-hidden="true" />
            {executionProgressText(result)}
          </div>
        )}
        <div className="koc-ask-message__meta">
          <span className="koc-mono">execution {turn.executionId}</span>
          <span className="koc-mono">task {result?.taskId ?? turn.taskId}</span>
          <Link to={`/executions/${encodeURIComponent(turn.executionId)}`}>查看执行详情</Link>
        </div>
        {conclusion ? (
          <ConclusionPanel conclusion={conclusion} evidence={result?.evidence ?? []} />
        ) : null}
        <p className="koc-ask-disclaimer">AI 结论必须结合证据时效和实际集群状态核验。</p>
      </article>
    </>
  )
}

function PendingConversation({ question }: { question: string }) {
  return (
    <>
      <article className="koc-ask-message koc-ask-message--user">
        <div className="koc-ask-message__label">你</div>
        <div className="koc-ask-message__content">{question}</div>
      </article>
      <article className="koc-ask-message koc-ask-message--assistant">
        <div className="koc-ask-message__label">
          <Icon name="ask" size={15} />
          KubeOnCall AI
        </div>
        <div className="koc-ask-thinking">
          <span className="koc-spinner koc-spinner--sm" aria-hidden="true" />
          正在创建持久化执行…
        </div>
      </article>
    </>
  )
}

function ConclusionPanel({
  conclusion,
  evidence,
}: {
  conclusion: AiConclusion
  evidence: EvidenceItem[]
}) {
  const referenced = evidence.filter((item) => conclusion.evidenceRefs.includes(item.evidenceId))
  return (
    <div className="koc-conclusion-panel">
      <div className="koc-conclusion-panel__summary">
        <div>
          <span>{conclusion.severity}</span>
          <strong>{conclusion.claim}</strong>
        </div>
        <ConfidenceBadge conclusion={conclusion} />
      </div>

      <details open={conclusion.confidence.label === 'LOW'}>
        <summary>置信度依据</summary>
        <dl className="koc-confidence-breakdown">
          {Object.entries(conclusion.confidence.basis).map(([key, value]) => (
            <div key={key}>
              <dt>{confidenceLabel(key)}</dt>
              <dd>{Math.round(value * 100)}%</dd>
            </div>
          ))}
        </dl>
      </details>

      <details>
        <summary>证据（{referenced.length}）</summary>
        <div className="koc-evidence-list">
          {referenced.length ? (
            referenced.map((item) => <EvidenceSnippet key={item.evidenceId} item={item} />)
          ) : (
            <p className="koc-evidence-empty">当前结论没有可用证据引用，不能视为确定性结论。</p>
          )}
        </div>
      </details>

      {conclusion.sopRefs.length ? (
        <details>
          <summary>SOP 来源（{conclusion.sopRefs.length}）</summary>
          <ul className="koc-sop-list">
            {conclusion.sopRefs.map((sop) => (
              <li key={`${sop.sopId}-${sop.version}`}>
                <strong className="koc-mono">{sop.sopId}</strong>
                <span>v{sop.version || '未标注'}</span>
                <small>{sop.section || sop.source}</small>
              </li>
            ))}
          </ul>
        </details>
      ) : null}

      {conclusion.recommendedAction ? (
        <div className="koc-recommended-action">
          <div>
            <span>建议动作</span>
            <strong>{conclusion.recommendedAction.type}</strong>
          </div>
          <span>{conclusion.recommendedAction.requiresApproval ? '需要人工审批' : '只读操作'}</span>
        </div>
      ) : null}
    </div>
  )
}

function EvidenceSnippet({ item }: { item: EvidenceItem }) {
  return (
    <article
      className={`koc-evidence-snippet koc-evidence-snippet--${item.collectionStatus.toLowerCase()}`}
    >
      <header>
        <div>
          <StatusBadge status={item.collectionStatus} />
          <strong>{item.type}</strong>
          <span>{item.source}</span>
        </div>
        <time dateTime={item.observedAt}>{formatTime(item.observedAt)}</time>
      </header>
      <p>{item.summary}</p>
      {item.snippet ? <pre className="koc-mono">{item.snippet}</pre> : null}
      <footer>
        <span className="koc-mono">
          {item.resource.kind || 'Resource'}/{item.resource.name || 'current-scope'}
        </span>
        <span>{item.freshnessSeconds}s 前</span>
        {item.truncated ? <span>已截断</span> : null}
        {item.errorType ? <span>{item.errorType}</span> : null}
      </footer>
    </article>
  )
}

function ExecutionContext({
  turn,
  result,
  isLoading,
  error,
}: {
  turn: ConversationTurn | null
  result: ExecutionDetail | null
  isLoading: boolean
  error: Error | null
}) {
  const details = result?.details ?? {}
  const plan = asRecord(details.plan)
  const tasks = Array.isArray(plan?.tasks) ? plan.tasks.map(asRecord).filter(Boolean) : []
  const approval = asRecord(details.approval)
  const pause = asRecord(approval?.pause)
  const riskReasons = Array.isArray(pause?.riskReasons)
    ? pause.riskReasons.map(String).filter(Boolean)
    : []
  const waitingApproval = result?.status === 'WAITING_APPROVAL' || Boolean(pause)
  const conclusion = result?.conclusions?.[0]

  return (
    <aside className="koc-ask-context" aria-label="执行上下文">
      <div className="koc-ask-context__header">
        <div>
          <span>执行上下文</span>
          <h2>证据与操作边界</h2>
        </div>
        <Icon name="shield" size={18} />
      </div>

      {!turn ? (
        <div className="koc-ask-context__empty">
          <p>提交问题后，这里会显示持久化执行、Planner 来源、置信度和审批状态。</p>
        </div>
      ) : error ? (
        <div className="koc-ask-context__empty" role="alert">
          <p>暂时无法读取执行详情，后台执行仍会继续。</p>
        </div>
      ) : (
        <>
          <dl className="koc-ask-context__facts">
            <div>
              <dt>状态</dt>
              <dd>
                <StatusBadge status={result?.status ?? (isLoading ? 'LOADING' : 'PENDING')} />
              </dd>
            </div>
            <div>
              <dt>执行 ID</dt>
              <dd className="koc-mono" title={turn.executionId}>
                {turn.executionId}
              </dd>
            </div>
            <div>
              <dt>任务 ID</dt>
              <dd className="koc-mono" title={result?.taskId ?? turn.taskId}>
                {result?.taskId ?? turn.taskId}
              </dd>
            </div>
            <div>
              <dt>Planner</dt>
              <dd>{conclusion ? <PlannerModeBadge conclusion={conclusion} /> : '等待规划'}</dd>
            </div>
            <div>
              <dt>置信度</dt>
              <dd>{conclusion ? <ConfidenceBadge conclusion={conclusion} /> : '—'}</dd>
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
                        {String(task?.target ?? '未指定目标')} ·{' '}
                        {String(task?.riskLevel ?? '未知风险')}
                      </small>
                    </div>
                  </li>
                ))}
              </ol>
            ) : (
              <p>{executionProgressText(result)}</p>
            )}
          </section>

          <section className="koc-ask-context__section">
            <h3>安全策略</h3>
            <div
              className={
                waitingApproval ? 'koc-governance koc-governance--warning' : 'koc-governance'
              }
            >
              <Icon name={waitingApproval ? 'approval' : 'shield'} size={17} />
              <div>
                <strong>{waitingApproval ? '等待人工审批' : '持久化执行已启用'}</strong>
                <p>
                  {waitingApproval
                    ? '外部状态变更不会由 AI 直接执行，审批通过后由后台任务恢复。'
                    : '规则降级和模拟结果仅允许只读诊断；变更必须经过 Verifier 与审批。'}
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

          <div className="koc-ask-context__actions">
            <Link to={`/executions/${encodeURIComponent(turn.executionId)}`}>打开执行详情</Link>
            {waitingApproval ? <Link to="/approvals">前往审批中心</Link> : null}
          </div>

          <p className="koc-ask-context__boundary">
            浏览器关闭不会中断后台任务；刷新页面后会从本地 executionId 恢复进度。
          </p>

          <details className="koc-ask-evidence">
            <summary>查看原始执行依据</summary>
            <pre className="koc-mono">
              {JSON.stringify(result ?? { executionId: turn.executionId }, null, 2)}
            </pre>
          </details>
        </>
      )}
    </aside>
  )
}

function PlannerModeBadge({ conclusion }: { conclusion: AiConclusion }) {
  const mode = conclusion.planner.mode ?? 'UNAVAILABLE'
  const label =
    mode === 'REAL_MODEL'
      ? '真实模型'
      : mode === 'RULE_ASSISTED'
        ? '模型 + 规则'
        : mode === 'RULE_FALLBACK'
          ? '规则降级'
          : mode === 'SIMULATION'
            ? '模拟'
            : '不可用'
  return (
    <span
      className={`koc-planner-mode koc-planner-mode--${mode.toLowerCase()}`}
      title={conclusion.planner.degradedReason}
    >
      {label}
      {conclusion.planner.model ? ` · ${conclusion.planner.model}` : ''}
    </span>
  )
}

function ConfidenceBadge({ conclusion }: { conclusion: AiConclusion }) {
  const { score, label } = conclusion.confidence
  return (
    <span className={`koc-confidence koc-confidence--${label.toLowerCase()}`}>
      {label} · {Math.round(score * 100)}%
    </span>
  )
}

function StatusBadge({ status }: { status: string }) {
  const normalized = status.toUpperCase()
  const tone =
    normalized === 'SUCCESS' || normalized === 'SUCCEEDED'
      ? 'success'
      : normalized === 'FAILED' || normalized === 'REJECTED'
        ? 'danger'
        : normalized.includes('WAIT') || normalized === 'PENDING' || normalized === 'LOADING'
          ? 'warning'
          : 'info'

  return <span className={`koc-badge koc-badge--${tone}`}>{status}</span>
}

function executionProgressText(result: ExecutionDetail | null | undefined) {
  if (!result) return '执行已提交，等待后台 Worker 接管…'
  if (result.status === 'WAITING_APPROVAL') return '计划已通过校验，正在等待人工审批。'
  if (result.status === 'RUNNING') {
    return result.taskStage
      ? `正在执行 ${result.taskStage}（${result.taskProgress ?? 0}%）`
      : '正在采集证据并生成计划…'
  }
  return '正在同步执行结果…'
}

function confidenceLabel(key: string) {
  const labels: Record<string, string> = {
    directEvidence: '直接证据',
    sourceAgreement: '多源一致',
    freshness: '时效性',
    sopSupport: 'SOP 支持',
    targetCertainty: '目标确定',
    missingSignalPenalty: '缺失扣分',
    conflictPenalty: '冲突扣分',
  }
  return labels[key] ?? key
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function createIdempotencyKey() {
  const random =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().replaceAll('-', '')
      : `${Date.now()}${Math.random().toString(16).slice(2)}`
  return `ask_${random}`.slice(0, 128)
}

function loadConversation(): { sessionId: string | null; turns: ConversationTurn[] } {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return { sessionId: null, turns: [] }
    const parsed = JSON.parse(raw) as { sessionId?: unknown; turns?: unknown }
    const turns = Array.isArray(parsed.turns)
      ? parsed.turns
          .map(asRecord)
          .filter(Boolean)
          .filter(
            (item) =>
              typeof item?.executionId === 'string' &&
              typeof item?.taskId === 'string' &&
              typeof item?.question === 'string',
          )
          .slice(-20)
          .map((item): ConversationTurn => ({
            id: String(item?.id ?? item?.executionId),
            question: String(item?.question),
            executionId: String(item?.executionId),
            taskId: String(item?.taskId),
            submittedAt: String(item?.submittedAt ?? ''),
          }))
      : []
    return {
      sessionId: typeof parsed.sessionId === 'string' ? parsed.sessionId : null,
      turns,
    }
  } catch {
    return { sessionId: null, turns: [] }
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}
