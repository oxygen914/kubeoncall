import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import type { Permission } from './permissions'
import { hasPermission } from './permissions'
import { useSession } from './useSession'

interface PermissionBoundaryProps {
  permission: Permission
  children: ReactNode
}

/** Prevents an authenticated user without the required permission from rendering a route. */
export function PermissionBoundary({ permission, children }: PermissionBoundaryProps) {
  const { session } = useSession()

  if (!hasPermission(session, permission)) {
    return <Navigate to="/forbidden" replace />
  }

  return <>{children}</>
}
