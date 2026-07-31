import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { PageTabs, type PageTab } from '@/components/navigation/PageTabs'
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
import { MaintenanceWindowsPanel } from './MaintenanceWindowsPanel'
import { errorMessage } from './operationUtils'
import { PolicyCatalogPanel } from './PolicyCatalogPanel'
import { PolicySimulationPanel } from './PolicySimulationPanel'
import { SuppressionRulesPanel } from './SuppressionRulesPanel'

type OperationsView = 'policies' | 'maintenance' | 'suppression'

/** Policy, maintenance-window and suppression-rule operations. */
export function OperationsPage() {
  const { session } = useSession()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const canReadPolicy = hasPermission(session, PERMISSIONS.POLICY_READ)
  const canReadMaintenance = hasPermission(session, PERMISSIONS.MAINTENANCE_READ)
  const canManagePolicy = hasPermission(session, PERMISSIONS.POLICY_MANAGE)
  const canManageMaintenance = hasPermission(session, PERMISSIONS.MAINTENANCE_MANAGE)
  const requestedView = searchParams.get('view')
  const view: OperationsView =
    requestedView === 'policies' && canReadPolicy
      ? 'policies'
      : requestedView === 'suppression' && canReadMaintenance
        ? 'suppression'
        : requestedView === 'maintenance' && canReadMaintenance
          ? 'maintenance'
          : canReadPolicy
            ? 'policies'
            : 'maintenance'
  const tabs: PageTab[] = [
    ...(canReadPolicy
      ? [
          {
            id: 'policies',
            label: '策略版本',
            description: '目录、演练与回滚',
            to: '/operations?view=policies',
          },
        ]
      : []),
    ...(canReadMaintenance
      ? [
          {
            id: 'maintenance',
            label: '维护窗口',
            description: '计划与撤销',
            to: '/operations?view=maintenance',
          },
          {
            id: 'suppression',
            label: '抑制规则',
            description: '版本与关联条件',
            to: '/operations?view=suppression',
          },
        ]
      : []),
  ]
  const policies = useQuery({
    queryKey: ['operations', 'policies'],
    queryFn: getPolicies,
    enabled: canReadPolicy && view === 'policies',
  })
  const windows = useQuery({
    queryKey: ['operations', 'maintenance'],
    queryFn: getMaintenanceWindows,
    enabled: canReadMaintenance && view === 'maintenance',
  })
  const suppression = useQuery({
    queryKey: ['operations', 'suppression'],
    queryFn: getSuppressionRules,
    enabled: canReadMaintenance && view === 'suppression',
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
          <h1>策略与维护</h1>
          <p className="koc-page__subtitle">管理策略版本、演练告警匹配、维护窗口和告警抑制规则。</p>
        </div>
      </header>

      <PageTabs activeId={view} label="告警运营视图" tabs={tabs} />

      {message ? (
        <p className="koc-alert" role="status">
          {message}
        </p>
      ) : null}

      {view === 'policies' && canReadPolicy ? (
        <>
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
          {canManagePolicy ? <PolicySimulationPanel /> : null}
        </>
      ) : null}

      {view === 'maintenance' && canReadMaintenance ? (
        <MaintenanceWindowsPanel
          windows={windows.data}
          isLoading={windows.isLoading}
          error={windows.error}
          canManage={canManageMaintenance}
          isRevoking={revokeMutation.isPending}
          onCreated={() => {
            setMessage('维护窗口已创建')
            refreshMaintenance()
          }}
          onRevoke={(windowId) => revokeMutation.mutate(windowId)}
        />
      ) : null}

      {view === 'suppression' && canReadMaintenance ? (
        <SuppressionRulesPanel
          catalog={suppression.data}
          isLoading={suppression.isLoading}
          error={suppression.error}
          canManage={canManageMaintenance}
          isReloading={reloadSuppressionMutation.isPending}
          onReload={() => reloadSuppressionMutation.mutate()}
        />
      ) : null}
    </section>
  )
}
