import type { StatusTone } from '@/components/ui/StatusBadge'

export function executionTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (['FAILED', 'REJECTED'].includes(status)) return 'danger'
  if (status === 'WAITING_APPROVAL') return 'warning'
  if (['PENDING', 'RUNNING'].includes(status)) return 'info'
  return 'neutral'
}

export function operationClosureTone(phase: string): StatusTone {
  if (phase === 'VERIFIED') return 'success'
  if (['PREPARED', 'VERIFYING'].includes(phase)) return 'info'
  if (['STABILIZING', 'ROLLING_BACK', 'ROLLED_BACK'].includes(phase)) return 'warning'
  if (['ESCALATED', 'BLOCKED', 'PERSISTENCE_FAILED', 'DISPATCH_REJECTED'].includes(phase)) {
    return 'danger'
  }
  return 'neutral'
}

export function operationEscalationTone(status: string): StatusTone {
  if (status === 'DISPATCHED') return 'info'
  if (status === 'PENDING_MANUAL') return 'warning'
  if (status === 'DISPATCH_FAILED') return 'danger'
  return 'neutral'
}
