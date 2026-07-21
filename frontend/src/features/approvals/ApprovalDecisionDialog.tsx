import { useState, type FormEvent } from 'react'
import { ApiError } from '@/api/errors'
import { Button } from '@/components/ui/Button'
import { TaskStatusPanel } from '@/features/tasks/TaskStatusPanel'
import { useDecideApproval } from './hooks'
import type { ApprovalDecision } from './api'

interface ApprovalDecisionDialogProps {
  approvalId: string
  version: number
  initialDecision: ApprovalDecision
  onClose: () => void
}

export function ApprovalDecisionDialog({
  approvalId,
  version,
  initialDecision,
  onClose,
}: ApprovalDecisionDialogProps) {
  const [decision, setDecision] = useState(initialDecision)
  const [comment, setComment] = useState('')
  const mutation = useDecideApproval(approvalId)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try {
      await mutation.mutateAsync({ version, decision, comment: comment.trim() })
    } catch {
      // The typed mutation error is rendered below.
    }
  }

  const error =
    mutation.error instanceof ApiError
      ? mutation.error.code === 'RESOURCE_VERSION_CONFLICT'
        ? '审批已被他人更新，请刷新后重试。'
        : mutation.error.code === 'CONFLICT'
          ? '审批当前状态不允许再次决策。'
          : mutation.error.message
      : null

  return (
    <div className="koc-dialog" role="dialog" aria-modal="true" aria-labelledby="decision-title">
      <form className="koc-dialog__panel" onSubmit={submit}>
        <h2 id="decision-title">审批决策</h2>
        <p className="koc-dialog__subtitle">
          审批 {approvalId}，基于版本 {version}。
        </p>

        {!mutation.data ? (
          <>
            <label className="koc-filter">
              <span>决策</span>
              <select
                value={decision}
                onChange={(event) => setDecision(event.target.value as ApprovalDecision)}
              >
                <option value="APPROVED">批准</option>
                <option value="REJECTED">拒绝</option>
              </select>
            </label>
            <label className="koc-filter koc-filter--grow">
              <span>说明（可选）</span>
              <textarea
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                maxLength={2000}
                rows={4}
                autoFocus
              />
            </label>
          </>
        ) : (
          <TaskStatusPanel taskId={mutation.data.taskId} />
        )}

        {error ? (
          <p className="koc-alert koc-alert--error" role="alert">
            {error}
          </p>
        ) : null}

        <div className="koc-dialog__actions">
          <Button variant="ghost" size="sm" onClick={onClose} disabled={mutation.isPending}>
            {mutation.data ? '关闭' : '取消'}
          </Button>
          {!mutation.data ? (
            <Button type="submit" size="sm" disabled={mutation.isPending}>
              {mutation.isPending ? '提交中…' : decision === 'APPROVED' ? '批准' : '拒绝'}
            </Button>
          ) : null}
        </div>
      </form>
    </div>
  )
}
