import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { compilePrometheusRules, dryRunPolicy, replayPolicies } from './api'
import { errorMessage, parseArray, parseObject } from './operationUtils'

const SAMPLE_ALARM = JSON.stringify(
  {
    alarmId: 'alarm-dry-run',
    alertName: 'NodeNotReady',
    resourceType: 'NODE',
    resourceName: 'worker-01',
    cluster: 'prod',
    severity: 'P1',
    occurredAt: new Date().toISOString(),
    labels: { team: 'platform' },
    status: 'FIRING',
  },
  null,
  2,
)

export function PolicySimulationPanel() {
  const [alarmJson, setAlarmJson] = useState(SAMPLE_ALARM)
  const [replayJson, setReplayJson] = useState(`[${SAMPLE_ALARM}]`)
  const [result, setResult] = useState<unknown>(null)
  const [error, setError] = useState<string | null>(null)
  const dryRun = useMutation({
    mutationFn: () => dryRunPolicy(parseObject(alarmJson)),
    onSuccess: (data) => {
      setError(null)
      setResult(data)
    },
    onError: (reason) => setError(errorMessage(reason)),
  })
  const replay = useMutation({
    mutationFn: () => replayPolicies(parseArray(replayJson)),
    onSuccess: (data) => {
      setError(null)
      setResult(data)
    },
    onError: (reason) => setError(errorMessage(reason)),
  })
  const compile = useMutation({
    mutationFn: compilePrometheusRules,
    onSuccess: (data) => {
      setError(null)
      setResult(data)
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  return (
    <section className="koc-card">
      <h2>策略演练</h2>
      <label className="koc-field">
        <span>单条告警 JSON</span>
        <textarea
          className="koc-input"
          rows={10}
          value={alarmJson}
          onChange={(event) => setAlarmJson(event.target.value)}
        />
      </label>
      <div className="koc-page__title-row">
        <Button size="sm" onClick={() => dryRun.mutate()} disabled={dryRun.isPending}>
          Dry-run
        </Button>
        <Button size="sm" variant="secondary" onClick={() => compile.mutate()}>
          生成 Prometheus 规则
        </Button>
      </div>
      <label className="koc-field">
        <span>批量回放 JSON 数组（最多 1000 条）</span>
        <textarea
          className="koc-input"
          rows={8}
          value={replayJson}
          onChange={(event) => setReplayJson(event.target.value)}
        />
      </label>
      <Button size="sm" onClick={() => replay.mutate()} disabled={replay.isPending}>
        执行 Replay
      </Button>
      {error ? <p className="koc-alert koc-alert--error">{error}</p> : null}
      {result ? (
        <pre className="koc-card koc-mono koc-break">{JSON.stringify(result, null, 2)}</pre>
      ) : null}
    </section>
  )
}
