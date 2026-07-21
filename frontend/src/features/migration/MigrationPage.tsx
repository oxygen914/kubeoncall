import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getRedisInventory,
  getMigrationStatus,
  getMigrationBatches,
  getMigrationDiffs,
  backfillActiveAlarm,
  backfillApproval,
  backfillSkillState,
  type BackfillResult,
} from './api'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'

/**
 * WBS-11 data-migration operations page. Shows the cutover flag state, Redis key inventory,
 * ActiveAlarm backfill controls, and paged batch/diff reports so operators can drive and sign off
 * the migration. Requires system:manage on the backend; the page is hidden from non-admins via the
 * nav guard.
 */
export function MigrationPage() {
  const queryClient = useQueryClient()
  const [lastResult, setLastResult] = useState<string | null>(null)

  const inventoryQuery = useQuery({
    queryKey: ['migration', 'redis-inventory'],
    queryFn: getRedisInventory,
    staleTime: 30_000,
  })
  const statusQuery = useQuery({
    queryKey: ['migration', 'status'],
    queryFn: getMigrationStatus,
    staleTime: 10_000,
  })
  const batchesQuery = useQuery({
    queryKey: ['migration', 'batches'],
    queryFn: () => getMigrationBatches({ size: 20 }),
    staleTime: 10_000,
  })
  const diffsQuery = useQuery({
    queryKey: ['migration', 'diffs'],
    queryFn: () => getMigrationDiffs({ size: 20 }),
    staleTime: 10_000,
  })

  const inventory = inventoryQuery.data

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>数据迁移（WBS-11）</h1>
        <p className="koc-page__subtitle">
          Redis → MySQL 回填与灰度切换运营。回填默认 dry-run，确认后再 apply。
        </p>
      </header>

      <div className="koc-detail">
        <div className="koc-detail__summary">
          <h2>Redis Key 盘点</h2>
          <AsyncState
            isLoading={inventoryQuery.isLoading}
            error={inventoryQuery.error}
            isEmpty={!inventoryQuery.isLoading && !inventory}
          >
            {inventory ? (
              <div className="koc-migration-inventory">
                <p className="koc-pagination__info">
                  扫描 {inventory.scanned} 个 Key · {inventory.note}
                </p>
                <table className="koc-table">
                  <thead>
                    <tr><th>分类</th><th>数量</th><th>处理</th></tr>
                  </thead>
                  <tbody>
                    {Object.entries(inventory.byCategory).map(([cat, count]) => (
                      <tr key={cat}>
                        <td className="koc-mono">{cat}</td>
                        <td>{count}</td>
                        <td>{categoryHint(cat)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <details>
                  <summary>按前缀明细</summary>
                  <table className="koc-table">
                    <tbody>
                      {Object.entries(inventory.byPrefix).map(([prefix, count]) => (
                        <tr key={prefix}><td className="koc-mono">{prefix}</td><td>{count}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </details>
              </div>
            ) : null}
          </AsyncState>
        </div>

        <div className="koc-detail__metric">
          <h2>回填</h2>
          <p className="koc-page__subtitle">
            把 Redis 长期事实回填到 MySQL。dry-run 不写库；Apply 前先用 dry-run 探测。
          </p>
          <BackfillPanel
            title="ActiveAlarm"
            description="alarm-active:* → koc_alarm_incident"
            onBackfill={backfillActiveAlarm}
            onDone={setLastResult}
            onInvalidate={() => {
              void queryClient.invalidateQueries({ queryKey: ['migration', 'redis-inventory'] })
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }}
          />
          <BackfillPanel
            title="Approval"
            description="approval-request:* → koc_approval_request"
            onBackfill={backfillApproval}
            onDone={setLastResult}
            onInvalidate={() => void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })}
          />
          <BackfillPanel
            title="Skill State"
            description="skill:disabled → koc_skill_state"
            onBackfill={backfillSkillState}
            onDone={setLastResult}
            onInvalidate={() => void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })}
          />
          {lastResult ? (
            <p className="koc-alert koc-alert--error" role="status">
              {lastResult}
            </p>
          ) : null}
        </div>
      </div>

      <div className="koc-detail__summary">
        <h2>切换状态</h2>
        <AsyncState isLoading={statusQuery.isLoading} error={statusQuery.error} isEmpty={!statusQuery.isLoading && !statusQuery.data}>
          {statusQuery.data ? (
            <dl className="koc-fields">
              <div><dt>读源</dt><dd><StatusBadge tone="info">{statusQuery.data.alarmReadSource}</StatusBadge></dd></div>
              <div><dt>写模式</dt><dd><StatusBadge tone="warning">{statusQuery.data.alarmWriteMode}</StatusBadge></dd></div>
              <div><dt>回填 dry-run</dt><dd>{statusQuery.data.backfillDryRun ? '是' : '否'}</dd></div>
              <div><dt>legacy API</dt><dd>{statusQuery.data.legacyApiEnabled ? '启用' : '已退役'}</dd></div>
              <div><dt>账本可用</dt><dd>{statusQuery.data.ledgerAvailable ? '是' : '否'}</dd></div>
            </dl>
          ) : null}
        </AsyncState>
      </div>

      <div className="koc-detail__summary">
        <h2>迁移批次</h2>
        <AsyncState
          isLoading={batchesQuery.isLoading}
          error={batchesQuery.error}
          isEmpty={!batchesQuery.isLoading && (batchesQuery.data?.data.length ?? 0) === 0}
          emptyMessage="暂无批次记录"
        >
          <table className="koc-table">
            <thead>
              <tr><th>批次</th><th>域</th><th>模式</th><th>状态</th><th>扫描/迁移/跳过/失败</th><th>时间</th></tr>
            </thead>
            <tbody>
              {(batchesQuery.data?.data ?? []).map((b) => (
                <tr key={b.batchId}>
                  <td className="koc-mono">{b.batchId.slice(0, 16)}</td>
                  <td>{b.domain}</td>
                  <td>{b.mode}</td>
                  <td><StatusBadge tone={b.status === 'COMPLETED' ? 'success' : 'warning'}>{b.status}</StatusBadge></td>
                  <td>{b.scanned}/{b.migrated}/{b.skipped}/{b.failed}</td>
                  <td>{b.startedAt ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </div>

      <div className="koc-detail__summary">
        <h2>差异报告</h2>
        <AsyncState
          isLoading={diffsQuery.isLoading}
          error={diffsQuery.error}
          isEmpty={!diffsQuery.isLoading && (diffsQuery.data?.data.length ?? 0) === 0}
          emptyMessage="暂无差异记录"
        >
          <table className="koc-table">
            <thead>
              <tr><th>类型</th><th>域</th><th>资源</th><th>Redis</th><th>MySQL</th><th>时间</th></tr>
            </thead>
            <tbody>
              {(diffsQuery.data?.data ?? []).map((d) => (
                <tr key={d.publicId}>
                  <td><StatusBadge tone="danger">{d.diffType}</StatusBadge></td>
                  <td>{d.domain}</td>
                  <td className="koc-mono">{d.resourcePublicId ?? '—'}</td>
                  <td className="koc-mono">{d.redisSummary ?? '—'}</td>
                  <td className="koc-mono">{d.mysqlSummary ?? '—'}</td>
                  <td>{d.occurredAt ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </div>
    </section>
  )
}

function categoryHint(cat: string): string {
  switch (cat) {
    case 'active-business-fact':
      return '迁移'
    case 'historical-audit':
      return '保留期内迁移'
    case 'skill-state':
      return '迁移'
    case 'session-graphstate':
      return '不迁移'
    case 'short-term-dedup-lock':
      return '自然过期'
    case 'rebuildable-cache':
      return '不迁移'
    default:
      return '待评估'
  }
}

function BackfillPanel({
  title,
  description,
  onBackfill,
  onDone,
  onInvalidate,
}: {
  title: string
  description: string
  onBackfill: (dryRun: boolean) => Promise<BackfillResult>
  onDone: (message: string) => void
  onInvalidate: () => void
}) {
  const mutation = useMutation({
    mutationFn: (dryRun: boolean) => onBackfill(dryRun),
    onSuccess: (result) => {
      onDone(
        `${title} ${result.dryRun ? '[dry-run]' : '[applied]'} scanned=${result.scanned} migrated=${result.migrated} skipped=${result.skipped} failed=${result.failed} — ${result.note}`,
      )
      onInvalidate()
    },
    onError: (err) => {
      onDone(err instanceof Error ? `${title} 失败: ${err.message}` : `${title} 失败`)
    },
  })
  return (
    <div className="koc-migration-inventory">
      <strong>{title}</strong> <span className="koc-mono">{description}</span>
      <div className="koc-pagination__actions">
        <Button
          variant="secondary"
          size="sm"
          disabled={mutation.isPending}
          onClick={() => mutation.mutate(true)}
        >
          {mutation.isPending ? '执行中…' : 'Dry-run'}
        </Button>
        <Button
          variant="primary"
          size="sm"
          disabled={mutation.isPending}
          onClick={() => {
            if (window.confirm(`确认对 ${title} 执行真实回填？`)) {
              mutation.mutate(false)
            }
          }}
        >
          Apply
        </Button>
      </div>
    </div>
  )
}
