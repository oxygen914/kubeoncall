import type { StatusTone } from '@/components/ui/StatusBadge'
import type { SandboxCleanupStatus, SandboxRunStatus } from './api'

export function sandboxRunTone(status: SandboxRunStatus): StatusTone {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'TIMED_OUT') return 'danger'
  if (status === 'CANCELLED') return 'neutral'
  if (status === 'COLLECTING' || status === 'DISPATCHING') return 'info'
  return 'warning'
}

export function cleanupTone(status: SandboxCleanupStatus): StatusTone {
  if (status === 'SUCCEEDED' || status === 'NOT_REQUIRED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}
