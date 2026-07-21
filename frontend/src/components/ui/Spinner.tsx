import clsx from 'clsx'

export interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  className?: string
  'aria-label'?: string
}

const sizeClass = {
  sm: 'koc-spinner--sm',
  md: 'koc-spinner--md',
  lg: 'koc-spinner--lg',
} as const

export function Spinner({ size = 'md', className, ...rest }: SpinnerProps) {
  return (
    <span
      role="status"
      className={clsx('koc-spinner', sizeClass[size], className)}
      aria-label={rest['aria-label']}
    />
  )
}
