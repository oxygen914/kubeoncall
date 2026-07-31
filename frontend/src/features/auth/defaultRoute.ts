import type { SessionData } from '@/api/auth'
import { hasPermission, PERMISSIONS, type Permission } from './permissions'

const DEFAULT_DESTINATIONS: Array<{ path: string; permission: Permission }> = [
  { path: '/overview', permission: PERMISSIONS.DASHBOARD_READ },
  { path: '/alarms', permission: PERMISSIONS.ALARM_READ },
  { path: '/ask', permission: PERMISSIONS.ASK_EXECUTE },
  { path: '/approvals', permission: PERMISSIONS.APPROVAL_READ },
  { path: '/executions', permission: PERMISSIONS.EXECUTION_READ },
  { path: '/sandbox-runs', permission: PERMISSIONS.SANDBOX_READ },
  { path: '/changes', permission: PERMISSIONS.CHANGE_READ },
  { path: '/operations', permission: PERMISSIONS.POLICY_READ },
  { path: '/operations', permission: PERMISSIONS.MAINTENANCE_READ },
  { path: '/knowledge', permission: PERMISSIONS.KNOWLEDGE_READ },
  { path: '/memory', permission: PERMISSIONS.MEMORY_READ },
  { path: '/skills', permission: PERMISSIONS.SKILL_READ },
  { path: '/tools', permission: PERMISSIONS.TOOL_READ },
  { path: '/integrations', permission: PERMISSIONS.INTEGRATION_READ },
  { path: '/audit', permission: PERMISSIONS.AUDIT_READ },
  { path: '/system', permission: PERMISSIONS.SYSTEM_READ },
  { path: '/users', permission: PERMISSIONS.SYSTEM_MANAGE },
  { path: '/tokens', permission: PERMISSIONS.TOKEN_READ_OWN },
]

/** Select a real page the authenticated account may access instead of assuming dashboard access. */
export function getDefaultConsolePath(session: SessionData | null | undefined): string | null {
  return (
    DEFAULT_DESTINATIONS.find(({ permission }) => hasPermission(session, permission))?.path ?? null
  )
}
