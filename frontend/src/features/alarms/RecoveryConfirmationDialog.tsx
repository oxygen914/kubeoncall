import { useId, useState, type FormEvent } from 'react'
import { ApiError } from '@/api/errors'
import { useDialogFocus } from '@/components/dialog/useDialogFocus'
import { Button } from '@/components/ui/Button'
import { useConfirmAlarmRecovery } from './hooks'

interface RecoveryConfirmationDialogProps {
  alarmId: string
  version: number
  onClose: () => void
}

export function RecoveryConfirmationDialog({
  alarmId,
  version,
  onClose,
}: RecoveryConfirmationDialogProps) {
  const titleId = useId()
  const descriptionId = useId()
  const [healthCheckPassed, setHealthCheckPassed] = useState(false)
  const [note, setNote] = useState('')
  const mutation = useConfirmAlarmRecovery(alarmId)
  const panelRef = useDialogFocus<HTMLFormElement>(onClose, mutation.isPending)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!healthCheckPassed) return
    try {
      await mutation.mutateAsync({ version, note: note.trim() })
      onClose()
    } catch {
      // The mutation error is rendered below.
    }
  }

  return (
    <div
      className="koc-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onMouseDown={() => {
        if (!mutation.isPending) onClose()
      }}
    >
      <form
        ref={panelRef}
        className="koc-dialog__panel"
        tabIndex={-1}
        onSubmit={handleSubmit}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <h2 id={titleId}>确认恢复</h2>
        <p id={descriptionId} className="koc-dialog__subtitle">
          确认告警 {alarmId} 的健康检查已经通过（基于版本 {version}）。
        </p>

        <label className="koc-dialog__check">
          <input
            type="checkbox"
            checked={healthCheckPassed}
            onChange={(event) => setHealthCheckPassed(event.target.checked)}
            autoFocus
            required
          />
          <span>我已验证服务或节点恢复健康</span>
        </label>

        <label className="koc-filter koc-filter--grow">
          <span>恢复说明（可选）</span>
          <textarea
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={1000}
            rows={3}
            placeholder="例如：连续 10 分钟健康检查通过"
          />
        </label>

        <CommandError error={mutation.error} action="恢复确认" />

        <div className="koc-dialog__actions">
          <Button variant="ghost" size="sm" onClick={onClose} disabled={mutation.isPending}>
            取消
          </Button>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            disabled={mutation.isPending || !healthCheckPassed}
          >
            {mutation.isPending ? '提交中…' : '确认恢复'}
          </Button>
        </div>
      </form>
    </div>
  )
}

function CommandError({ error, action }: { error: unknown; action: string }) {
  if (!(error instanceof ApiError)) return null
  let message = error.message
  if (error.code === 'RESOURCE_VERSION_CONFLICT') {
    message = '告警已被他人更新，请刷新后重试。'
  } else if (error.code === 'CONFLICT') {
    message = `告警当前状态不允许${action}。`
  }
  return (
    <p className="koc-alert koc-alert--error" role="alert">
      {message}
    </p>
  )
}
