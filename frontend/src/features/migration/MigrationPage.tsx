import { useEffect, useRef, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getRedisInventory,
  getMigrationStatus,
  getMigrationBatches,
  getMigrationDiffs,
  getMigrationDiffStatistics,
  getMigrationItems,
  resolveMigrationDiff,
  backfillActiveAlarm,
  backfillAlarmAcknowledgements,
  backfillAlarmRecoveries,
  backfillAlarmSilences,
  backfillApproval,
  backfillExecutionAudit,
  backfillSkillState,
  type MigrationTaskAccepted,
  type MigrationDiff,
} from './api'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { TaskStatusPanel } from '@/features/tasks/TaskStatusPanel'
import { cancelTask, isTaskTerminal } from '@/features/tasks/api'
import { useTask } from '@/features/tasks/hooks'

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
  const itemsQuery = useQuery({
    queryKey: ['migration', 'items'],
    queryFn: () => getMigrationItems({ size: 20 }),
    staleTime: 10_000,
  })
  const diffStatisticsQuery = useQuery({
    queryKey: ['migration', 'diff-statistics'],
    queryFn: () => getMigrationDiffStatistics({ windowMinutes: 60 }),
    staleTime: 10_000,
  })
  const resolveDiffMutation = useMutation({
    mutationFn: ({
      publicId,
      status,
      note,
    }: {
      publicId: string
      status: Exclude<MigrationDiff['resolutionStatus'], 'OPEN'>
      note?: string
    }) => resolveMigrationDiff(publicId, status, note),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['migration', 'diffs'] })
      void queryClient.invalidateQueries({ queryKey: ['migration', 'diff-statistics'] })
    },
  })

  const inventory = inventoryQuery.data

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <h1>数据迁移（WBS-11）</h1>
        <p className="koc-page__subtitle">
          Redis → MySQL 回填与灰度切换运营。回填以可取消、超时受控、限速的持久化任务运行；默认
          dry-run，成功后再 apply。
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
                    <tr>
                      <th>分类</th>
                      <th>数量</th>
                      <th>处理</th>
                    </tr>
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
                        <tr key={prefix}>
                          <td className="koc-mono">{prefix}</td>
                          <td>{count}</td>
                        </tr>
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
            把 Redis 长期事实回填到 MySQL。必须先成功完成当前面板的 dry-run，再确认一次才能 Apply。
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
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
          />
          <BackfillPanel
            title="Skill State"
            description="skill:disabled → koc_skill_state"
            onBackfill={backfillSkillState}
            onDone={setLastResult}
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
          />
          <BackfillPanel
            title="Alarm Acknowledgement"
            description="alarm-ack:* → koc_alarm_acknowledgement"
            onBackfill={backfillAlarmAcknowledgements}
            onDone={setLastResult}
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
          />
          <BackfillPanel
            title="Alarm Silence"
            description="alarm-silence-approval:* → koc_alarm_silence"
            onBackfill={backfillAlarmSilences}
            onDone={setLastResult}
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
          />
          <BackfillPanel
            title="Alarm Recovery"
            description="alarm-recovery:* → koc_alarm_status_history"
            onBackfill={backfillAlarmRecoveries}
            onDone={setLastResult}
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
          />
          <BackfillPanel
            title="Execution Audit"
            description="execution-audit:* → koc_operation_audit"
            onBackfill={backfillExecutionAudit}
            onDone={setLastResult}
            onInvalidate={() =>
              void queryClient.invalidateQueries({ queryKey: ['migration', 'batches'] })
            }
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
        <AsyncState
          isLoading={statusQuery.isLoading}
          error={statusQuery.error}
          isEmpty={!statusQuery.isLoading && !statusQuery.data}
        >
          {statusQuery.data ? (
            <dl className="koc-fields">
              <div>
                <dt>读源</dt>
                <dd>
                  <StatusBadge tone="info">{statusQuery.data.alarmReadSource}</StatusBadge>
                </dd>
              </div>
              <div>
                <dt>写模式</dt>
                <dd>
                  <StatusBadge tone="warning">{statusQuery.data.alarmWriteMode}</StatusBadge>
                </dd>
              </div>
              <div>
                <dt>回填 dry-run</dt>
                <dd>{statusQuery.data.backfillDryRun ? '是' : '否'}</dd>
              </div>
              <div>
                <dt>legacy API</dt>
                <dd>{statusQuery.data.legacyApiEnabled ? '启用' : '已退役'}</dd>
              </div>
              <div>
                <dt>账本可用</dt>
                <dd>{statusQuery.data.ledgerAvailable ? '是' : '否'}</dd>
              </div>
            </dl>
          ) : null}
        </AsyncState>
      </div>

      <div className="koc-detail__summary">
        <h2>SHADOW 差异门槛（近 60 分钟）</h2>
        <AsyncState
          isLoading={diffStatisticsQuery.isLoading}
          error={diffStatisticsQuery.error}
          isEmpty={!diffStatisticsQuery.isLoading && !diffStatisticsQuery.data}
        >
          {diffStatisticsQuery.data ? (
            <dl className="koc-fields">
              <div>
                <dt>比对 / 不一致</dt>
                <dd>
                  {diffStatisticsQuery.data.comparisonCount} /{' '}
                  {diffStatisticsQuery.data.mismatchCount}
                </dd>
              </div>
              <div>
                <dt>不一致率 / 阈值</dt>
                <dd>
                  {diffStatisticsQuery.data.mismatchRatePercent.toFixed(3)}% /{' '}
                  {diffStatisticsQuery.data.thresholdPercent.toFixed(3)}%
                </dd>
              </div>
              <div>
                <dt>未处置差异</dt>
                <dd>{diffStatisticsQuery.data.openDiffCount}</dd>
              </div>
              <div>
                <dt>门槛</dt>
                <dd>
                  <StatusBadge
                    tone={diffStatisticsQuery.data.withinThreshold ? 'success' : 'danger'}
                  >
                    {diffStatisticsQuery.data.withinThreshold ? '通过' : '阻断'}
                  </StatusBadge>
                </dd>
              </div>
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
              <tr>
                <th>批次</th>
                <th>域</th>
                <th>模式</th>
                <th>状态</th>
                <th>扫描/迁移/跳过/失败</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {(batchesQuery.data?.data ?? []).map((b) => (
                <tr key={b.batchId}>
                  <td className="koc-mono">{b.batchId.slice(0, 16)}</td>
                  <td>{b.domain}</td>
                  <td>{b.mode}</td>
                  <td>
                    <StatusBadge tone={b.status === 'COMPLETED' ? 'success' : 'warning'}>
                      {b.status}
                    </StatusBadge>
                  </td>
                  <td>
                    {b.scanned}/{b.migrated}/{b.skipped}/{b.failed}
                  </td>
                  <td>{b.startedAt ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AsyncState>
      </div>

      <div className="koc-detail__summary">
        <h2>迁移 Item</h2>
        <AsyncState
          isLoading={itemsQuery.isLoading}
          error={itemsQuery.error}
          isEmpty={!itemsQuery.isLoading && (itemsQuery.data?.data.length ?? 0) === 0}
          emptyMessage="暂无 item 记录"
        >
          <table className="koc-table">
            <thead>
              <tr>
                <th>域</th>
                <th>结果</th>
                <th>源 Key</th>
                <th>目标</th>
                <th>原因</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {(itemsQuery.data?.data ?? []).map((item) => (
                <tr key={`${item.batchId}:${item.sourceKey}`}>
                  <td>{item.domain}</td>
                  <td>{item.result}</td>
                  <td className="koc-mono">{item.sourceKey}</td>
                  <td className="koc-mono">{item.targetPublicId ?? '—'}</td>
                  <td>{item.reason ?? '—'}</td>
                  <td>{item.occurredAt ?? '—'}</td>
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
              <tr>
                <th>类型</th>
                <th>域</th>
                <th>资源</th>
                <th>Redis</th>
                <th>MySQL</th>
                <th>处置</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {(diffsQuery.data?.data ?? []).map((d) => (
                <tr key={d.publicId}>
                  <td>
                    <StatusBadge tone="danger">{d.diffType}</StatusBadge>
                  </td>
                  <td>{d.domain}</td>
                  <td className="koc-mono">{d.resourcePublicId ?? '—'}</td>
                  <td className="koc-mono">{d.redisSummary ?? '—'}</td>
                  <td className="koc-mono">{d.mysqlSummary ?? '—'}</td>
                  <td>
                    {d.resolutionStatus === 'OPEN' ? (
                      <Button
                        size="sm"
                        disabled={resolveDiffMutation.isPending}
                        onClick={() => {
                          const status = window.prompt(
                            '处置状态：EXPLAINED、RESOLVED 或 ACCEPTED_RISK',
                            'RESOLVED',
                          )
                          if (
                            status !== 'EXPLAINED' &&
                            status !== 'RESOLVED' &&
                            status !== 'ACCEPTED_RISK'
                          )
                            return
                          const note = window.prompt('处置说明（可选）') ?? undefined
                          resolveDiffMutation.mutate({ publicId: d.publicId, status, note })
                        }}
                      >
                        处置
                      </Button>
                    ) : (
                      <span>
                        {d.resolutionStatus}
                        {d.resolvedBy ? ` · ${d.resolvedBy}` : ''}
                      </span>
                    )}
                  </td>
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
  onBackfill: (dryRun: boolean) => Promise<MigrationTaskAccepted>
  onDone: (message: string) => void
  onInvalidate: () => void
}) {
  const [dryRunTaskId, setDryRunTaskId] = useState<string | null>(null)
  const [latestTaskId, setLatestTaskId] = useState<string | null>(null)
  const taskQuery = useTask(latestTaskId ?? undefined)
  const onDoneRef = useRef(onDone)
  const onInvalidateRef = useRef(onInvalidate)
  onDoneRef.current = onDone
  onInvalidateRef.current = onInvalidate
  const currentTaskId = taskQuery.data?.id
  const currentTaskStatus = taskQuery.data?.status
  const currentTaskErrorSummary = taskQuery.data?.errorSummary
  const dryRunSucceeded =
    dryRunTaskId !== null &&
    taskQuery.data?.id === dryRunTaskId &&
    taskQuery.data.status === 'SUCCEEDED'
  const mutation = useMutation({
    mutationFn: (dryRun: boolean) => onBackfill(dryRun),
    onSuccess: (result) => {
      if (result.dryRun) setDryRunTaskId(result.taskId)
      setLatestTaskId(result.taskId)
      onDone(`${title} ${result.dryRun ? '[dry-run]' : '[apply]'} 任务已提交：${result.taskId}`)
    },
    onError: (err) => {
      onDone(err instanceof Error ? `${title} 失败: ${err.message}` : `${title} 失败`)
    },
  })
  const cancelMutation = useMutation({
    mutationFn: (taskId: string) => cancelTask(taskId),
    onSuccess: (task) => {
      onDone(`${title} 任务已取消：${task.id}`)
      void taskQuery.refetch()
      onInvalidate()
    },
    onError: (err) =>
      onDone(err instanceof Error ? `${title} 取消失败: ${err.message}` : `${title} 取消失败`),
  })

  useEffect(() => {
    if (!currentTaskId || !currentTaskStatus || !isTaskTerminal(currentTaskStatus)) return
    onInvalidateRef.current()
    if (currentTaskStatus !== 'SUCCEEDED') {
      onDoneRef.current(
        `${title} 任务结束：${currentTaskStatus}${currentTaskErrorSummary ? ` — ${currentTaskErrorSummary}` : ''}`,
      )
    }
  }, [currentTaskErrorSummary, currentTaskId, currentTaskStatus, title])

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
          disabled={mutation.isPending || !dryRunSucceeded}
          onClick={() => {
            if (
              window.confirm(
                `确认对 ${title} 执行真实回填？此操作会写入 MySQL，且 dry-run 已成功完成。`,
              )
            ) {
              mutation.mutate(false)
            }
          }}
        >
          {dryRunSucceeded ? 'Apply' : '等待 Dry-run 成功'}
        </Button>
        {latestTaskId && taskQuery.data && !isTaskTerminal(taskQuery.data.status) ? (
          <Button
            variant="secondary"
            size="sm"
            disabled={cancelMutation.isPending}
            onClick={() => cancelMutation.mutate(latestTaskId)}
          >
            {cancelMutation.isPending ? '取消中…' : '取消任务'}
          </Button>
        ) : null}
      </div>
      {latestTaskId ? <TaskStatusPanel taskId={latestTaskId} /> : null}
    </div>
  )
}
