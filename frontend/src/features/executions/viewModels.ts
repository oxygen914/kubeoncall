import type { StatusTone } from '@/components/ui/StatusBadge'

export function executionTone(status: string): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (['FAILED', 'REJECTED'].includes(status)) return 'danger'
  if (status === 'WAITING_APPROVAL') return 'warning'
  if (['PENDING', 'RUNNING'].includes(status)) return 'info'
  return 'neutral'
}
