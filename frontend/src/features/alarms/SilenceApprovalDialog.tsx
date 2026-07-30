import { useId, useState, type FormEvent } from 'react'
import { ApiError } from '@/api/errors'
import { useDialogFocus } from '@/components/dialog/useDialogFocus'
import { Button } from '@/components/ui/Button'
import { useApproveAlarmSilence } from './hooks'

interface SilenceApprovalDialogProps {
  alarmId: string
  version: number
  onClose: () => void
}

export function SilenceApprovalDialog({ alarmId, version, onClose }: SilenceApprovalDialogProps) {
  const titleId = useId()
  const descriptionId = useId()
  const [reason, setReason] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [validationError, setValidationError] = useState('')
  const mutation = useApproveAlarmSilence(alarmId)
  const panelRef = useDialogFocus<HTMLFormElement>(onClose, mutation.isPending)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const expiresAtMillis = Date.parse(expiresAt)
    if (!Number.isFinite(expiresAtMillis) || expiresAtMillis <= Date.now()) {
      setValidationError('静默截止时间必须晚于当前时间。')
      return
    }

    setValidationError('')
    try {
      await mutation.mutateAsync({
        version,
        reason: reason.trim(),
        expiresAt: new Date(expiresAtMillis).toISOString(),
      })
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
        <h2 id={titleId}>审批静默</h2>
        <p id={descriptionId} className="koc-dialog__subtitle">
          在指定时间前静默告警 {alarmId}（基于版本 {version}）。
        </p>

        <label className="koc-filter koc-filter--grow">
          <span>静默原因</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={1000}
            rows={3}
            placeholder="例如：计划维护窗口"
            autoFocus
            required
          />
        </label>

        <label className="koc-filter koc-filter--grow">
          <span>静默截止时间</span>
          <input
            type="datetime-local"
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
            required
          />
        </label>

        {validationError ? (
          <p className="koc-alert koc-alert--error" role="alert">
            {validationError}
          </p>
        ) : null}
        <CommandError error={mutation.error} />

        <div className="koc-dialog__actions">
          <Button variant="ghost" size="sm" onClick={onClose} disabled={mutation.isPending}>
            取消
          </Button>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            disabled={mutation.isPending || !reason.trim() || !expiresAt}
          >
            {mutation.isPending ? '提交中…' : '批准静默'}
          </Button>
        </div>
      </form>
    </div>
  )
}

function CommandError({ error }: { error: unknown }) {
  if (!(error instanceof ApiError)) return null
  let message = error.message
  if (error.code === 'RESOURCE_VERSION_CONFLICT') {
    message = '告警已被他人更新，请刷新后重试。'
  } else if (error.code === 'CONFLICT') {
    message = '告警当前状态不允许静默。'
  }
  return (
    <p className="koc-alert koc-alert--error" role="alert">
      {message}
    </p>
  )
}
