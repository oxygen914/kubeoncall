import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import {
  getMaintenanceWindows,
  getPolicies,
  getSuppressionRules,
  reloadPolicies,
  reloadSuppressionRules,
  revokeMaintenanceWindow,
  rollbackPolicy,
} from './api'
import { MaintenanceWindowForm } from './MaintenanceWindowForm'
import { errorMessage, formatMatch, formatMatchers, formatTime } from './operationUtils'
import { PolicyCatalogPanel } from './PolicyCatalogPanel'
import { PolicySimulationPanel } from './PolicySimulationPanel'

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
        <PolicyCatalogPanel
          catalog={policies.data}
          isLoading={policies.isLoading}
          error={policies.error}
          canManage={canManagePolicy}
          isReloading={reloadPolicyMutation.isPending}
          isRollingBack={rollbackMutation.isPending}
          onReload={() => reloadPolicyMutation.mutate()}
          onRollback={(version) => rollbackMutation.mutate(version)}
        />
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
