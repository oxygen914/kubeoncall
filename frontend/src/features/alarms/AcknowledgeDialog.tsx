import { useState } from 'react'
import { ApiError } from '@/api/errors'
import { useDialogFocus } from '@/components/dialog/useDialogFocus'
import { Button } from '@/components/ui/Button'
import { useAcknowledgeAlarm } from './hooks'

interface AcknowledgeDialogProps {
  alarmId: string
  version: number
  onClose: () => void
}

/**
 * Acknowledge dialog. Collects an optional reason, submits with the current version as If-Match and
 * a fresh Idempotency-Key, and surfaces the stable error codes the backend returns:
 * RESOURCE_VERSION_CONFLICT (someone else changed the alarm → refresh), CONFLICT (not FIRING), and
 * IDEMPOTENCY_KEY_REUSED (should not happen with a fresh key, but handled defensively).
 */
export function AcknowledgeDialog({ alarmId, version, onClose }: AcknowledgeDialogProps) {
  const [reason, setReason] = useState('')
  const mutation = useAcknowledgeAlarm(alarmId)
  const panelRef = useDialogFocus<HTMLDivElement>(onClose, mutation.isPending)

  const handleSubmit = async () => {
    try {
      await mutation.mutateAsync({ version, reason })
      onClose()
    } catch {
      // error surfaced via mutation.error below
    }
  }

  const error = mutation.error
  const errorCode = error instanceof ApiError ? error.code : undefined
  const errorMessage = error instanceof ApiError ? error.message : undefined

  return (
    <div
      className="koc-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ack-title"
      onMouseDown={() => {
        if (!mutation.isPending) onClose()
      }}
    >
      <div
        ref={panelRef}
        className="koc-dialog__panel"
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <h2 id="ack-title">确认告警</h2>
        <p className="koc-dialog__subtitle">
          将告警 {alarmId} 标记为已确认（基于版本 {version}）。
        </p>
        <label className="koc-filter koc-filter--grow">
          <span>原因（可选）</span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={1000}
            rows={3}
            placeholder="例如：正在扩容处理"
          />
        </label>
        {errorMessage ? (
          <p className="koc-alert koc-alert--error" role="alert">
            {errorCode === 'RESOURCE_VERSION_CONFLICT'
              ? '告警已被他人更新，请刷新后重试。'
              : errorCode === 'CONFLICT'
                ? '告警当前状态不允许确认（可能已恢复或被他人确认）。'
                : errorMessage}
          </p>
        ) : null}
        <div className="koc-dialog__actions">
          <Button variant="ghost" size="sm" onClick={onClose} disabled={mutation.isPending}>
            取消
          </Button>
          <Button variant="primary" size="sm" onClick={handleSubmit} disabled={mutation.isPending}>
            {mutation.isPending ? '提交中…' : '确认'}
          </Button>
        </div>
      </div>
    </div>
  )
}
