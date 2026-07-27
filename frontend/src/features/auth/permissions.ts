import type { SessionData } from '@/api/auth'

export const PERMISSIONS = {
  DASHBOARD_READ: 'dashboard:read',
  ALARM_READ: 'alarm:read',
  ALARM_ACKNOWLEDGE: 'alarm:acknowledge',
  ALARM_RECOVER: 'alarm:recover',
  ALARM_SILENCE: 'alarm:silence',
  APPROVAL_READ: 'approval:read',
  APPROVAL_DECIDE: 'approval:decide',
  EXECUTION_READ: 'execution:read',
  ASK_EXECUTE: 'ask:execute',
  KNOWLEDGE_READ: 'knowledge:read',
  KNOWLEDGE_WRITE: 'knowledge:write',
  KNOWLEDGE_DELETE: 'knowledge:delete',
  MEMORY_READ: 'memory:read',
  MEMORY_WRITE: 'memory:write',
  MEMORY_MAINTAIN: 'memory:maintain',
  SKILL_READ: 'skill:read',
  SKILL_MANAGE: 'skill:manage',
  TOOL_READ: 'tool:read',
  POLICY_READ: 'policy:read',
  POLICY_MANAGE: 'policy:manage',
  MAINTENANCE_READ: 'maintenance:read',
  MAINTENANCE_MANAGE: 'maintenance:manage',
  CHANGE_READ: 'change:read',
  INTEGRATION_READ: 'integration:read',
  AUDIT_READ: 'audit:read',
  SYSTEM_READ: 'system:read',
  SYSTEM_MANAGE: 'system:manage',
  TOKEN_READ_OWN: 'token:read-own',
  TOKEN_MANAGE_OWN: 'token:manage-own',
  TOKEN_MANAGE_ALL: 'token:manage-all',
  SANDBOX_READ: 'sandbox:read',
  SANDBOX_EXECUTE: 'sandbox:execute',
  SANDBOX_CANCEL: 'sandbox:cancel',
  SANDBOX_MANAGE: 'sandbox:manage',
} as const

export type Permission = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

/** The frontend is only a visibility guard; backend authorization remains authoritative. */
export function hasPermission(
  session: SessionData | null | undefined,
  permission: Permission,
): boolean {
  return session?.authenticated === true && session.user.permissions.includes(permission)
}
