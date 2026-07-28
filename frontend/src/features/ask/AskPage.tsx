import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '@/api/client'
import { ApiError } from '@/api/errors'
import { Button } from '@/components/ui/Button'
import { AsyncState } from '@/components/feedback/AsyncState'

interface AskResult {
  executionId: string
  status: string
  message: string
  sessionId: string | null
  details: Record<string, unknown>
}

/** Ask page: submit a runtime question and show the execution result. */
export function AskPage() {
  const [question, setQuestion] = useState('')
  const [result, setResult] = useState<AskResult | null>(null)

  const mutation = useMutation({
    mutationFn: (q: string) => api.post<AskResult>('/api/v1/ask', { question: q }),
    onSuccess: setResult,
  })

  const error = mutation.error
  const errorMessage = error instanceof ApiError ? error.message : error ? '请求失败' : null

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>提问</h1>
        <p className="koc-page__subtitle">向 KubeOnCall 提交运维问题，查看执行状态与结论。</p>
      </header>

      <form
        className="koc-filters"
        onSubmit={(e) => {
          e.preventDefault()
          if (question.trim()) mutation.mutate(question.trim())
        }}
      >
        <label className="koc-filter koc-filter--grow">
          <span>问题</span>
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            maxLength={4000}
            rows={3}
            placeholder="例如：worker-01 的 CPU 为什么高？"
          />
        </label>
        <Button
          type="submit"
          variant="primary"
          size="sm"
          disabled={mutation.isPending || !question.trim()}
        >
          {mutation.isPending ? '执行中…' : '提交'}
        </Button>
      </form>

      {errorMessage ? (
        <p className="koc-alert koc-alert--error" role="alert">
          {errorMessage}
        </p>
      ) : null}

      <AsyncState isLoading={mutation.isPending} error={null}>
        {result ? (
          <div className="koc-detail">
            <div className="koc-detail__summary">
              <dl className="koc-fields">
                <div>
                  <dt>执行 ID</dt>
                  <dd className="koc-mono">{result.executionId}</dd>
                </div>
                <div>
                  <dt>状态</dt>
                  <dd>{result.status}</dd>
                </div>
                <div>
                  <dt>会话</dt>
                  <dd className="koc-mono">{result.sessionId ?? '—'}</dd>
                </div>
              </dl>
            </div>
            <div className="koc-detail__summary">
              <h2>结论</h2>
              <p>{result.message}</p>
              {Object.keys(result.details).length > 0 ? (
                <details>
                  <summary>详情</summary>
                  <pre className="koc-mono">{JSON.stringify(result.details, null, 2)}</pre>
                </details>
              ) : null}
            </div>
          </div>
        ) : null}
      </AsyncState>
    </section>
  )
}
