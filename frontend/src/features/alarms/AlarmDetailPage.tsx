import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAlarm, useAlarmTimeline } from './hooks'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Button } from '@/components/ui/Button'
import { AcknowledgeDialog } from './AcknowledgeDialog'
import { RecoveryConfirmationDialog } from './RecoveryConfirmationDialog'
import { SilenceApprovalDialog } from './SilenceApprovalDialog'
import { formatTime, resourceLabel, severityTone, statusTone } from './viewModels'
import { useSession } from '@/features/auth/useSession'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { resolveListReturnPath } from '@/lib/navigation'

type AlarmDialog = 'acknowledge' | 'recovery' | 'silence' | null

/** Alarm detail page: summary, timeline and state-aware alarm command actions. */
export function AlarmDetailPage() {
  const params = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { session } = useSession()
  const alarmId = params.alarmId ?? ''
  const { data: alarm, isLoading, error } = useAlarm(alarmId)
  const {
    data: timeline,
    isLoading: timelineLoading,
    error: timelineError,
  } = useAlarmTimeline(alarmId)
  const [activeDialog, setActiveDialog] = useState<AlarmDialog>(null)
  const returnTo = resolveListReturnPath(location.state, '/alarms')

  const canAcknowledge =
    hasPermission(session, PERMISSIONS.ALARM_ACKNOWLEDGE) &&
    alarm?.status === 'FIRING' &&
    !alarm.acknowledgement?.acknowledged
  const canConfirmRecovery =
    hasPermission(session, PERMISSIONS.ALARM_RECOVER) && alarm?.status === 'RECOVERY_PENDING'
  const canApproveSilence =
    hasPermission(session, PERMISSIONS.ALARM_SILENCE) &&
    (alarm?.status === 'FIRING' || alarm?.status === 'ACKNOWLEDGED')

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate(returnTo)}>
            ← 返回列表
          </Button>
          <h1>{alarm?.alertName ?? '告警详情'}</h1>
        </div>
        <div className="koc-page__actions" aria-label="告警操作">
          {canAcknowledge ? (
            <Button variant="primary" size="sm" onClick={() => setActiveDialog('acknowledge')}>
              确认告警
            </Button>
          ) : null}
          {canConfirmRecovery ? (
            <Button variant="primary" size="sm" onClick={() => setActiveDialog('recovery')}>
              确认恢复
            </Button>
          ) : null}
          {canApproveSilence ? (
            <Button variant="secondary" size="sm" onClick={() => setActiveDialog('silence')}>
              审批静默
            </Button>
          ) : null}
        </div>
      </header>

      <AsyncState isLoading={isLoading} error={error} isEmpty={!isLoading && !alarm}>
        {alarm ? (
          <div className="koc-detail">
            <div className="koc-detail__summary">
              <dl className="koc-fields">
                <div>
                  <dt>级别</dt>
                  <dd>
                    <StatusBadge tone={severityTone(alarm.severity)}>{alarm.severity}</StatusBadge>
                  </dd>
                </div>
                <div>
                  <dt>状态</dt>
                  <dd>
                    <StatusBadge tone={statusTone(alarm.status)}>{alarm.status}</StatusBadge>
                  </dd>
                </div>
                <div>
                  <dt>资源</dt>
                  <dd>{resourceLabel(alarm.resource)}</dd>
                </div>
                <div>
                  <dt>指纹</dt>
                  <dd className="koc-mono">{alarm.fingerprint}</dd>
                </div>
                <div>
                  <dt>首次发生</dt>
                  <dd>{formatTime(alarm.firstSeen)}</dd>
                </div>
                <div>
                  <dt>最近发生</dt>
                  <dd>{formatTime(alarm.lastSeen)}</dd>
                </div>
                <div>
                  <dt>恢复时间</dt>
                  <dd>{formatTime(alarm.resolvedAt)}</dd>
                </div>
                <div>
                  <dt>发生次数</dt>
                  <dd>{alarm.occurrenceCount}</dd>
                </div>
                <div>
                  <dt>策略</dt>
                  <dd className="koc-mono">{alarm.policyPublicId ?? '—'}</dd>
                </div>
                <div>
                  <dt>最近执行</dt>
                  <dd>
                    {alarm.latestExecution ? (
                      hasPermission(session, PERMISSIONS.EXECUTION_READ) ? (
                        <Link to={`/executions/${encodeURIComponent(alarm.latestExecution.id)}`}>
                          {alarm.latestExecution.id} · {alarm.latestExecution.status}
                        </Link>
                      ) : (
                        `${alarm.latestExecution.id} · ${alarm.latestExecution.status}`
                      )
                    ) : (
                      '—'
                    )}
                  </dd>
                </div>
                <div>
                  <dt>版本</dt>
                  <dd>{alarm.version}</dd>
                </div>
              </dl>
            </div>

            <div className="koc-detail__metric">
              <h2>指标</h2>
              <dl className="koc-fields">
                <div>
                  <dt>指标名</dt>
                  <dd>{alarm.metricName ?? '—'}</dd>
                </div>
                <div>
                  <dt>当前值</dt>
                  <dd>{alarm.currentValue != null ? alarm.currentValue : '—'}</dd>
                </div>
                <div>
                  <dt>阈值</dt>
                  <dd>{alarm.threshold != null ? alarm.threshold : '—'}</dd>
                </div>
                <div>
                  <dt>单位</dt>
                  <dd>{alarm.unit ?? '—'}</dd>
                </div>
              </dl>
            </div>

            <div className="koc-detail__labels">
              <h2>标签</h2>
              <table className="koc-table koc-table--kv">
                <tbody>
                  {Object.entries(alarm.labels).length === 0 ? (
                    <tr>
                      <td>无标签</td>
                    </tr>
                  ) : (
                    Object.entries(alarm.labels).map(([k, v]) => (
                      <tr key={k}>
                        <th>{k}</th>
                        <td>{v}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        ) : null}
      </AsyncState>

      <div className="koc-detail__timeline">
        <h2>时间线</h2>
        <AsyncState
          isLoading={timelineLoading}
          error={timelineError}
          isEmpty={!timelineLoading && (timeline?.length ?? 0) === 0}
          emptyMessage="暂无时间线事件"
        >
          <ol className="koc-timeline">
            {(timeline ?? []).map((item) => (
              <li key={item.id} className="koc-timeline__item">
                <div className="koc-timeline__time">{formatTime(item.occurredAt)}</div>
                <div className="koc-timeline__body">
                  <span className="koc-timeline__type">{item.type}</span>
                  {item.summary ? <p>{item.summary}</p> : null}
                  {item.requestId ? (
                    <p className="koc-request-id">requestId: {item.requestId}</p>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        </AsyncState>
      </div>

      {activeDialog === 'acknowledge' && alarm ? (
        <AcknowledgeDialog
          alarmId={alarm.id}
          version={alarm.version}
          onClose={() => setActiveDialog(null)}
        />
      ) : null}
      {activeDialog === 'recovery' && alarm ? (
        <RecoveryConfirmationDialog
          alarmId={alarm.id}
          version={alarm.version}
          onClose={() => setActiveDialog(null)}
        />
      ) : null}
      {activeDialog === 'silence' && alarm ? (
        <SilenceApprovalDialog
          alarmId={alarm.id}
          version={alarm.version}
          onClose={() => setActiveDialog(null)}
        />
      ) : null}
    </section>
  )
}
