import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { getLastRequestId } from '@/lib/requestId'
import { reportRenderError } from '@/telemetry/clientEvents'

interface Props {
  children: ReactNode
}
interface State {
  hasError: boolean
  error: Error | null
}

/**
 * Root error boundary. Catches render-time errors anywhere in the tree and
 * shows a recoverable error screen with a refresh button and the last seen
 * request id for support correlation.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  override componentDidCatch(error: Error, _info: ErrorInfo): void {
    reportRenderError(error)
  }

  private handleRefresh = () => {
    window.location.reload()
  }

  override render() {
    if (!this.state.hasError) return this.props.children
    const requestId = getLastRequestId()
    return (
      <div className="koc-error-screen" role="alert">
        <h1>页面出现问题</h1>
        <p>抱歉,页面发生了意外错误。您可以尝试刷新页面。</p>
        {requestId && (
          <p className="koc-error-screen__requestid">
            请求 ID:<code>{requestId}</code>
          </p>
        )}
        <Button onClick={this.handleRefresh}>刷新页面</Button>
      </div>
    )
  }
}
