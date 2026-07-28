import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import type { PolicyCatalog } from './api'
import { formatTime } from './operationUtils'

interface PolicyCatalogPanelProps {
  catalog: PolicyCatalog | undefined
  isLoading: boolean
  error: unknown
  canManage: boolean
  isReloading: boolean
  isRollingBack: boolean
  onReload: () => void
  onRollback: (version: string) => void
}

export function PolicyCatalogPanel({
  catalog,
  isLoading,
  error,
  canManage,
  isReloading,
  isRollingBack,
  onReload,
  onRollback,
}: PolicyCatalogPanelProps) {
  return (
    <section className="koc-card">
      <div className="koc-page__title-row">
        <h2>告警策略</h2>
        {canManage ? (
          <Button size="sm" onClick={onReload} disabled={isReloading}>
            重新加载
          </Button>
        ) : null}
      </div>
      <AsyncState isLoading={isLoading} error={error}>
        {catalog ? (
          <>
            <p>
              当前版本：<StatusBadge tone="success">{catalog.activeVersion}</StatusBadge>
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
                {catalog.policies.map((policy) => (
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
                {catalog.versions.map((version) => (
                  <tr key={version.version}>
                    <td className="koc-mono">{version.version}</td>
                    <td>{formatTime(version.loadedAt)}</td>
                    <td>{version.policyCount}</td>
                    <td>
                      {canManage && version.version !== catalog.activeVersion ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={isRollingBack}
                          onClick={() => onRollback(version.version)}
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
  )
}
