import type { StatusTone } from '@/components/ui/StatusBadge'

export function approvalTone(status: string): StatusTone {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING') return 'warning'
  return 'neutral'
}

export function riskTone(risk: string): StatusTone {
  if (['CRITICAL', 'HIGH'].includes(risk)) return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  return 'neutral'
}
