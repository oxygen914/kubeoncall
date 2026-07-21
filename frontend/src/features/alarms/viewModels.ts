import type { StatusTone } from '@/components/ui/StatusBadge'

/** Map a platform severity to a badge tone + short label. */
export function severityTone(severity: string): StatusTone {
  switch (severity) {
    case 'P0':
      return 'danger'
    case 'P1':
      return 'danger'
    case 'P2':
      return 'warning'
    case 'P3':
      return 'info'
    case 'INFO':
      return 'neutral'
    default:
      return 'neutral'
  }
}

export function statusTone(status: string): StatusTone {
  switch (status) {
    case 'FIRING':
      return 'danger'
    case 'ACKNOWLEDGED':
      return 'warning'
    case 'RECOVERY_PENDING':
      return 'info'
    case 'RESOLVED':
      return 'success'
    case 'SUPPRESSED':
      return 'neutral'
    default:
      return 'neutral'
  }
}

/** Format an ISO timestamp for display in the user's locale, UTC-suffixed. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      timeZoneName: 'short',
    })
  } catch {
    return iso
  }
}

/** Human-readable resource label, e.g. "NODE / worker-01 @ prod". */
export function resourceLabel(resource: {
  type: string
  name: string
  cluster: string | null
  namespace: string | null
}): string {
  const parts = [resource.type, resource.name]
  if (resource.cluster) parts.push(`@ ${resource.cluster}`)
  if (resource.namespace) parts.push(`/${resource.namespace}`)
  return parts.join(' ').trim()
}
