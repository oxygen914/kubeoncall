import type { ReactNode } from 'react'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/api/errors'

interface AsyncStateProps {
  isLoading: boolean
  error: unknown
  isEmpty?: boolean
  emptyMessage?: string
  children: ReactNode
}

/**
 * Inline loading / error / empty guard for a single data region (page or card). Keeps page
 * components free of repeated ternaries and renders the requestId on error so operators can copy it
 * to search backend logs.
 */
export function AsyncState({
  isLoading,
  error,
  isEmpty = false,
  emptyMessage = '暂无数据',
  children,
}: AsyncStateProps) {
  if (isLoading) {
    return (
      <div className="koc-center" role="status" aria-live="polite">
        <Spinner aria-label="加载中" />
      </div>
    )
  }
  if (error) {
    const requestId = error instanceof ApiError ? error.requestId : undefined
    const message = error instanceof ApiError ? error.message : '加载失败'
    return (
      <div className="koc-error-inline" role="alert">
        <p>{message}</p>
        {requestId ? <p className="koc-request-id">requestId: {requestId}</p> : null}
      </div>
    )
  }
  if (isEmpty) {
    return <p className="koc-empty">{emptyMessage}</p>
  }
  return <>{children}</>
}
