import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import type { SuppressionCatalog } from './api'
import { formatMatch } from './operationUtils'

interface SuppressionRulesPanelProps {
  catalog: SuppressionCatalog | undefined
  isLoading: boolean
  error: unknown
  canManage: boolean
  isReloading: boolean
  onReload: () => void
}

export function SuppressionRulesPanel({
  catalog,
  isLoading,
  error,
  canManage,
  isReloading,
  onReload,
}: SuppressionRulesPanelProps) {
  return (
    <section className="koc-card">
      <div className="koc-page__title-row">
        <h2>抑制规则</h2>
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
              当前版本：<StatusBadge tone="info">{catalog.activeVersion}</StatusBadge>
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
                {catalog.rules.map((rule) => (
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
  )
}
