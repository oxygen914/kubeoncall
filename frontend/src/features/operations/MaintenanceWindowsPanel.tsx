import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import type { MaintenanceWindow } from './api'
import { MaintenanceWindowForm } from './MaintenanceWindowForm'
import { formatMatchers, formatTime } from './operationUtils'

interface MaintenanceWindowsPanelProps {
  windows: MaintenanceWindow[] | undefined
  isLoading: boolean
  error: unknown
  canManage: boolean
  isRevoking: boolean
  onCreated: () => void
  onRevoke: (windowId: string) => void
}

export function MaintenanceWindowsPanel({
  windows,
  isLoading,
  error,
  canManage,
  isRevoking,
  onCreated,
  onRevoke,
}: MaintenanceWindowsPanelProps) {
  return (
    <section className="koc-card">
      <h2>维护窗口</h2>
      {canManage ? <MaintenanceWindowForm onCreated={onCreated} /> : null}
      <AsyncState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && (windows?.length ?? 0) === 0}
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
            {(windows ?? []).map((window) => {
              const planned = new Date(window.startsAt) > new Date()
              return (
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
                    <StatusBadge tone={planned ? 'info' : 'warning'}>
                      {planned ? '计划中' : '生效中'}
                    </StatusBadge>
                  </td>
                  <td>
                    {canManage ? (
                      <Button
                        size="sm"
                        variant="danger"
                        disabled={isRevoking}
                        onClick={() => onRevoke(window.id)}
                      >
                        撤销
                      </Button>
                    ) : null}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </AsyncState>
    </section>
  )
}
