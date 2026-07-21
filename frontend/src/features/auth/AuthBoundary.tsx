import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from './useSession'
import { sanitizeReturnTo } from './sessionUtils'
import { Spinner } from '@/components/ui/Spinner'

/**
 * Guards protected routes. Redirects unauthenticated users to /login with a
 * sanitized `returnTo` so we never produce an open-redirect.
 */
export function AuthBoundary({ children }: { children: ReactNode }) {
  const { session, loading } = useSession()
  const location = useLocation()

  if (loading) {
    return (
      <div className="koc-center-screen" role="status" aria-live="polite">
        <Spinner aria-label="加载会话" />
      </div>
    )
  }

  if (!session || !session.authenticated) {
    const returnTo = sanitizeReturnTo(location.pathname + location.search)
    return <Navigate to={`/login?returnTo=${encodeURIComponent(returnTo)}`} replace />
  }

  return <>{children}</>
}
