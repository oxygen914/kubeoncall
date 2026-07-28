import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { createMaintenanceWindow, type MaintenanceWindowInput } from './api'
import { errorMessage, localTime } from './operationUtils'

export function MaintenanceWindowForm({ onCreated }: { onCreated: () => void }) {
  const [startsAt, setStartsAt] = useState(localTime(30))
  const [endsAt, setEndsAt] = useState(localTime(150))
  const [matcherKey, setMatcherKey] = useState('cluster')
  const [matcherValue, setMatcherValue] = useState('prod')
  const [reason, setReason] = useState('')
  const [approvedBy, setApprovedBy] = useState('')
  const [approvalReference, setApprovalReference] = useState('')
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (input: MaintenanceWindowInput) => createMaintenanceWindow(input),
    onSuccess: () => {
      setError(null)
      onCreated()
    },
    onError: (reason) => setError(errorMessage(reason)),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      startsAt: new Date(startsAt).toISOString(),
      endsAt: new Date(endsAt).toISOString(),
      matchers: { [matcherKey.trim()]: matcherValue.trim() },
      reason,
      approvedBy,
      approvalReference,
    })
  }

  return (
    <form className="koc-filters" onSubmit={submit}>
      <label className="koc-filter">
        <span>开始时间</span>
        <input
          type="datetime-local"
          value={startsAt}
          onChange={(event) => setStartsAt(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>结束时间</span>
        <input
          type="datetime-local"
          value={endsAt}
          onChange={(event) => setEndsAt(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>匹配字段</span>
        <input
          value={matcherKey}
          onChange={(event) => setMatcherKey(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>匹配值</span>
        <input
          value={matcherValue}
          onChange={(event) => setMatcherValue(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>原因</span>
        <input value={reason} onChange={(event) => setReason(event.target.value)} required />
      </label>
      <label className="koc-filter">
        <span>审批人</span>
        <input
          value={approvedBy}
          onChange={(event) => setApprovedBy(event.target.value)}
          required
        />
      </label>
      <label className="koc-filter">
        <span>审批单号</span>
        <input
          value={approvalReference}
          onChange={(event) => setApprovalReference(event.target.value)}
          required
        />
      </label>
      <Button type="submit" size="sm" disabled={mutation.isPending}>
        创建维护窗口
      </Button>
      {error ? <p className="koc-alert koc-alert--error">{error}</p> : null}
    </form>
  )
}
