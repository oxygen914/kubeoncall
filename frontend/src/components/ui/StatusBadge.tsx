import clsx from 'clsx'

export type StatusTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

export interface StatusBadgeProps {
  tone?: StatusTone
  children: React.ReactNode
  className?: string
}

const toneClass: Record<StatusTone, string> = {
  neutral: 'koc-badge--neutral',
  success: 'koc-badge--success',
  warning: 'koc-badge--warning',
  danger: 'koc-badge--danger',
  info: 'koc-badge--info',
}

export function StatusBadge({ tone = 'neutral', children, className }: StatusBadgeProps) {
  return <span className={clsx('koc-badge', toneClass[tone], className)}>{children}</span>
}
