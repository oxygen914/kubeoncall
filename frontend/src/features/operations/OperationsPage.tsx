import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import {
  compilePrometheusRules,
  createMaintenanceWindow,
  dryRunPolicy,
  getMaintenanceWindows,
  getPolicies,
  getSuppressionRules,
  reloadPolicies,
  reloadSuppressionRules,
  replayPolicies,
  revokeMaintenanceWindow,
  rollbackPolicy,
  type MaintenanceWindowInput,
} from './api'

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

/** Policy, maintenance-window and suppression-rule operations. */
export function OperationsPage() {
  const { session } = useSession()
  const queryClient = useQueryClient()
  const canReadPolicy = hasPermission(session, PERMISSIONS.POLICY_READ)
  const canReadMaintenance = hasPermission(session, PERMISSIONS.MAINTENANCE_READ)
  const canManagePolicy = hasPermission(session, PERMISSIONS.POLICY_MANAGE)
  const canManageMaintenance = hasPermission(session, PERMISSIONS.MAINTENANCE_MANAGE)
  const policies = useQuery({
    queryKey: ['operations', 'policies'],
    queryFn: getPolicies,
    enabled: canReadPolicy,
  })
  const windows = useQuery({
    queryKey: ['operations', 'maintenance'],
    queryFn: getMaintenanceWindows,
    enabled: canReadMaintenance,
  })
  const suppression = useQuery({
    queryKey: ['operations', 'suppression'],
    queryFn: getSuppressionRules,
    enabled: canReadMaintenance,
  })
  const [message, setMessage] = useState<string | null>(null)

  const refreshPolicies = () =>
    void queryClient.invalidateQueries({ queryKey: ['operations', 'policies'] })
  const refreshMaintenance = () =>
    void queryClient.invalidateQueries({ queryKey: ['operations', 'maintenance'] })
  const refreshSuppression = () =>
    void queryClient.invalidateQueries({ queryKey: ['operations', 'suppression'] })

  const reloadPolicyMutation = useMutation({
    mutationFn: reloadPolicies,
    onSuccess: () => {
      setMessage('告警策略已重新加载')
      refreshPolicies()
    },
    onError: (error) => setMessage(errorMessage(error)),
  })
  const rollbackMutation = useMutation({
    mutationFn: rollbackPolicy,
    onSuccess: () => {
      setMessage('策略版本已回滚')
      refreshPolicies()
    },
    onError: (error) => setMessage(errorMessage(error)),
  })
  const revokeMutation = useMutation({
    mutationFn: revokeMaintenanceWindow,
    onSuccess: () => {
      setMessage('维护窗口已撤销')
      refreshMaintenance()
    },
    onError: (error) => setMessage(errorMessage(error)),
  })
  const reloadSuppressionMutation = useMutation({
    mutationFn: reloadSuppressionRules,
    onSuccess: () => {
      setMessage('抑制规则已重新加载')
      refreshSuppression()
    },
    onError: (error) => setMessage(errorMessage(error)),
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>告警运营</h1>
          <p className="koc-page__subtitle">管理策略版本、演练告警匹配、维护窗口和告警抑制规则。</p>
        </div>
      </header>

      {message ? (
        <p className="koc-alert" role="status">
          {message}
        </p>
      ) : null}

      {canReadPolicy ? (
        <section className="koc-card">
          <div className="koc-page__title-row">
            <h2>告警策略</h2>
            {canManagePolicy ? (
              <Button
                size="sm"
                onClick={() => reloadPolicyMutation.mutate()}
                disabled={reloadPolicyMutation.isPending}
              >
                重新加载
              </Button>
            ) : null}
          </div>
          <AsyncState isLoading={policies.isLoading} error={policies.error}>
            {policies.data ? (
              <>
                <p>
                  当前版本：<StatusBadge tone="success">{policies.data.activeVersion}</StatusBadge>
                </p>
                <table className="koc-table">
                  <thead>
                    <tr>
                      <th>策略</th>
                      <th>严重级别</th>
                      <th>资源</th>
                      <th>指标</th>
                      <th>Runbook</th>
                    </tr>
                  </thead>
                  <tbody>
                    {policies.data.policies.map((policy) => (
                      <tr key={policy.id}>
                        <td>
                          <strong>{policy.name}</strong>
                          <br />
                          <span className="koc-mono">{policy.id}</span>
                        </td>
                        <td>{policy.severity ?? '—'}</td>
                        <td>{policy.resourceType ?? '—'}</td>
                        <td>{policy.metricName ?? '—'}</td>
                        <td>{policy.runbookId ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <h3>版本历史</h3>
                <table className="koc-table">
                  <thead>
                    <tr>
                      <th>版本</th>
                      <th>加载时间</th>
                      <th>策略数</th>
                      <th aria-label="操作" />
                    </tr>
                  </thead>
                  <tbody>
                    {policies.data.versions.map((version) => (
                      <tr key={version.version}>
                        <td className="koc-mono">{version.version}</td>
                        <td>{formatTime(version.loadedAt)}</td>
                        <td>{version.policyCount}</td>
                        <td>
                          {canManagePolicy && version.version !== policies.data.activeVersion ? (
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={rollbackMutation.isPending}
                              onClick={() => rollbackMutation.mutate(version.version)}
                            >
                              回滚到此版本
                            </Button>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            ) : null}
          </AsyncState>
        </section>
      ) : null}

      {canManagePolicy ? <PolicySimulationPanel /> : null}

      {canReadMaintenance ? (
        <section className="koc-card">
          <h2>维护窗口</h2>
          {canManageMaintenance ? (
            <MaintenanceWindowForm
              onCreated={() => {
                setMessage('维护窗口已创建')
                refreshMaintenance()
              }}
            />
          ) : null}
          <AsyncState
            isLoading={windows.isLoading}
            error={windows.error}
            isEmpty={!windows.isLoading && (windows.data?.length ?? 0) === 0}
            emptyMessage="暂无当前或未来维护窗口"
          >
            <table className="koc-table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>匹配范围</th>
                  <th>原因</th>
                  <th>审批</th>
                  <th>状态</th>
                  <th aria-label="操作" />
                </tr>
              </thead>
              <tbody>
                {(windows.data ?? []).map((window) => (
                  <tr key={window.id}>
                    <td>
                      {formatTime(window.startsAt)}
                      <br />至 {formatTime(window.endsAt)}
                    </td>
                    <td className="koc-mono">{formatMatchers(window.matchers)}</td>
                    <td>{window.reason}</td>
                    <td>
                      {window.approvedBy}
                      <br />
                      <span className="koc-mono">{window.approvalReference}</span>
                    </td>
                    <td>
                      <StatusBadge
                        tone={new Date(window.startsAt) > new Date() ? 'info' : 'warning'}
                      >
                        {new Date(window.startsAt) > new Date() ? '计划中' : '生效中'}
                      </StatusBadge>
                    </td>
                    <td>
                      {canManageMaintenance ? (
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={revokeMutation.isPending}
                          onClick={() => revokeMutation.mutate(window.id)}
                        >
                          撤销
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </AsyncState>
        </section>
      ) : null}

      {canReadMaintenance ? (
        <section className="koc-card">
          <div className="koc-page__title-row">
            <h2>抑制规则</h2>
            {canManageMaintenance ? (
              <Button
                size="sm"
                onClick={() => reloadSuppressionMutation.mutate()}
                disabled={reloadSuppressionMutation.isPending}
              >
                重新加载
              </Button>
            ) : null}
          </div>
          <AsyncState isLoading={suppression.isLoading} error={suppression.error}>
            {suppression.data ? (
              <>
                <p>
                  当前版本：<StatusBadge tone="info">{suppression.data.activeVersion}</StatusBadge>
                </p>
                <table className="koc-table">
                  <thead>
                    <tr>
                      <th>规则</th>
                      <th>源告警</th>
                      <th>目标告警</th>
                      <th>关联字段</th>
                      <th>有效期</th>
                      <th>原因</th>
                    </tr>
                  </thead>
                  <tbody>
                    {suppression.data.rules.map((rule) => (
                      <tr key={rule.id}>
                        <td className="koc-mono">{rule.id}</td>
                        <td>{formatMatch(rule.source)}</td>
                        <td>{formatMatch(rule.target)}</td>
                        <td>{rule.correlateBy.join(', ')}</td>
                        <td>{rule.ttlSeconds}s</td>
                        <td>{rule.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            ) : null}
          </AsyncState>
        </section>
      ) : null}
    </section>
  )
}

function PolicySimulationPanel() {
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

function MaintenanceWindowForm({ onCreated }: { onCreated: () => void }) {
  const [startsAt, setStartsAt] = useState(localTime(30))
  const [endsAt, setEndsAt] = useState(localTime(150))
  const [matcherKey, setMatcherKey] = useState('cluster')
  const [matcherValue, setMatcherValue] = useState('prod')
  const [reason, setReason] = useState('')
  const [approvedBy, setApprovedBy] = useState('')
  const [approvalReference, setApprovalReference] = useState('')
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (input: MaintenanceWindowInput) => createMaintenanceWindow(input),
    onSuccess: () => {
      setError(null)
      onCreated()
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      startsAt: new Date(startsAt).toISOString(),
      endsAt: new Date(endsAt).toISOString(),
      matchers: { [matcherKey.trim()]: matcherValue.trim() },
      reason,
      approvedBy,
      approvalReference,
    })
  }

  return (
    <form className="koc-filters" onSubmit={submit}>
      <label className="koc-filter">
        <span>开始时间</span>
        <input
          type="datetime-local"
          value={startsAt}
          onChange={(event) => setStartsAt(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>结束时间</span>
        <input
          type="datetime-local"
          value={endsAt}
          onChange={(event) => setEndsAt(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>匹配字段</span>
        <input
          value={matcherKey}
          onChange={(event) => setMatcherKey(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>匹配值</span>
        <input
          value={matcherValue}
          onChange={(event) => setMatcherValue(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>原因</span>
        <input value={reason} onChange={(event) => setReason(event.target.value)} required />
      </label>
      <label className="koc-filter">
        <span>审批人</span>
        <input
          value={approvedBy}
          onChange={(event) => setApprovedBy(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>审批单号</span>
        <input
          value={approvalReference}
          onChange={(event) => setApprovalReference(event.target.value)}
          required
        />
      </label>
      <Button type="submit" size="sm" disabled={mutation.isPending}>
        创建维护窗口
      </Button>
      {error ? <p className="koc-alert koc-alert--error">{error}</p> : null}
    </form>
  )
}

function parseObject(value: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('请输入 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

function parseArray(value: string): Record<string, unknown>[] {
  const parsed: unknown = JSON.parse(value)
  if (!Array.isArray(parsed) || parsed.some((item) => !item || typeof item !== 'object')) {
    throw new Error('请输入告警 JSON 数组')
  }
  return parsed as Record<string, unknown>[]
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '操作失败'
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString()
}

function formatMatchers(matchers: Record<string, string>): string {
  return Object.entries(matchers)
    .map(([key, value]) => `${key}=${value}`)
    .join(', ')
}

function formatMatch(match: { resourceTypes: string[]; alertNamePatterns: string[] }): string {
  return [...match.resourceTypes, ...match.alertNamePatterns].join(', ') || '全部'
}

function localTime(offsetMinutes: number): string {
  const time = new Date(Date.now() + offsetMinutes * 60_000)
  return new Date(time.getTime() - time.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
