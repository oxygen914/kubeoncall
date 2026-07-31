import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import type { Permission } from './permissions'
import { hasPermission } from './permissions'
import { useSession } from './useSession'

interface PermissionBoundaryProps {
  permission?: Permission
  permissions?: Permission[]
  children: ReactNode
}

/** Prevents an authenticated user without any accepted permission from rendering a route. */
export function PermissionBoundary({
  permission,
  permissions = [],
  children,
}: PermissionBoundaryProps) {
  const { session } = useSession()
  const accepted = permission ? [permission, ...permissions] : permissions

  if (accepted.length === 0 || !accepted.some((candidate) => hasPermission(session, candidate))) {
    return <Navigate to="/forbidden" replace />
  }

  return <>{children}</>
}
