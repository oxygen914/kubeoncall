import { forwardRef } from 'react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import clsx from 'clsx'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
  children?: ReactNode
}

const variantClass: Record<ButtonVariant, string> = {
  primary: 'koc-btn--primary',
  secondary: 'koc-btn--secondary',
  ghost: 'koc-btn--ghost',
  danger: 'koc-btn--danger',
}

const sizeClass: Record<ButtonSize, string> = {
  sm: 'koc-btn--sm',
  md: 'koc-btn--md',
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', fullWidth = false, className, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={clsx(
        'koc-btn',
        variantClass[variant],
        sizeClass[size],
        fullWidth && 'koc-btn--full',
        className,
      )}
      {...rest}
    />
  )
})
