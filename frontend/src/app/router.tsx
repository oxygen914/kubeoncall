import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthBoundary } from '@/features/auth/AuthBoundary'
import { LoginPage } from '@/features/auth/LoginPage'
import { SystemStatusPage } from '@/features/system/SystemStatusPage'
import { OverviewPage } from '@/features/overview/OverviewPage'
import { AskPage } from '@/features/ask/AskPage'
import { UserManagementPage } from '@/features/users/UserManagementPage'
import { MigrationPage } from '@/features/migration/MigrationPage'
import { AlarmsListPage } from '@/features/alarms/AlarmsListPage'
import { AlarmDetailPage } from '@/features/alarms/AlarmDetailPage'
import { ApprovalsListPage } from '@/features/approvals/ApprovalsListPage'
import { ApprovalDetailPage } from '@/features/approvals/ApprovalDetailPage'
import { ExecutionsListPage } from '@/features/executions/ExecutionsListPage'
import { ExecutionDetailPage } from '@/features/executions/ExecutionDetailPage'
import { KnowledgeListPage } from '@/features/knowledge/KnowledgeListPage'
import { KnowledgeDetailPage } from '@/features/knowledge/KnowledgeDetailPage'
import { MemoryListPage } from '@/features/memories/MemoryListPage'
import { MemoryDetailPage } from '@/features/memories/MemoryDetailPage'
import { SkillsListPage } from '@/features/skills/SkillsListPage'
import { SkillDetailPage } from '@/features/skills/SkillDetailPage'
import { AuditListPage } from '@/features/audit/AuditListPage'
import { AuditDetailPage } from '@/features/audit/AuditDetailPage'
import { ApiTokensPage } from '@/features/tokens/ApiTokensPage'
import { ToolsPage } from '@/features/tools/ToolsPage'
import { OperationsPage } from '@/features/operations/OperationsPage'
import { ChangeEventsPage } from '@/features/changes/ChangeEventsPage'
import { IntegrationsPage } from '@/features/integrations/IntegrationsPage'
import { SandboxRunsListPage } from '@/features/sandbox/SandboxRunsListPage'
import { SandboxRunDetailPage } from '@/features/sandbox/SandboxRunDetailPage'
import { AppShell } from '@/components/layout/AppShell'
import { ForbiddenPage } from '@/components/feedback/ForbiddenPage'
import { NotFoundPage } from '@/components/feedback/NotFoundPage'
import { PermissionBoundary } from '@/features/auth/PermissionBoundary'
import { PERMISSIONS, type Permission } from '@/features/auth/permissions'

/**
 * Application route table.
 *
 * `/` is protected by AuthBoundary and redirects to `/overview` (the dashboard landing).
 * useful landing page for an operator). Other feature routes are placeholders
 * for now and will be filled in later iterations.
 */
export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        element={
          <AuthBoundary>
            <AppShell />
          </AuthBoundary>
        }
      >
        <Route index element={<Navigate to="/overview" replace />} />
        <Route path="/forbidden" element={<ForbiddenPage />} />
        <Route path="/system" element={<SystemStatusPage />} />
        <Route
          path="/migration"
          element={
            <Restricted permission={PERMISSIONS.SYSTEM_MANAGE}>
              <MigrationPage />
            </Restricted>
          }
        />
        <Route
          path="/alarms"
          element={
            <Restricted permission={PERMISSIONS.ALARM_READ}>
              <AlarmsListPage />
            </Restricted>
          }
        />
        <Route
          path="/alarms/:alarmId"
          element={
            <Restricted permission={PERMISSIONS.ALARM_READ}>
              <AlarmDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/approvals"
          element={
            <Restricted permission={PERMISSIONS.APPROVAL_READ}>
              <ApprovalsListPage />
            </Restricted>
          }
        />
        <Route
          path="/approvals/:approvalId"
          element={
            <Restricted permission={PERMISSIONS.APPROVAL_READ}>
              <ApprovalDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/executions"
          element={
            <Restricted permission={PERMISSIONS.EXECUTION_READ}>
              <ExecutionsListPage />
            </Restricted>
          }
        />
        <Route
          path="/executions/:executionId"
          element={
            <Restricted permission={PERMISSIONS.EXECUTION_READ}>
              <ExecutionDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/overview"
          element={
            <Restricted permission={PERMISSIONS.DASHBOARD_READ}>
              <OverviewPage />
            </Restricted>
          }
        />
        <Route
          path="/ask"
          element={
            <Restricted permission={PERMISSIONS.ASK_EXECUTE}>
              <AskPage />
            </Restricted>
          }
        />
        <Route
          path="/users"
          element={
            <Restricted permission={PERMISSIONS.SYSTEM_MANAGE}>
              <UserManagementPage />
            </Restricted>
          }
        />
        <Route
          path="/knowledge"
          element={
            <Restricted permission={PERMISSIONS.KNOWLEDGE_READ}>
              <KnowledgeListPage />
            </Restricted>
          }
        />
        <Route
          path="/knowledge/:documentId"
          element={
            <Restricted permission={PERMISSIONS.KNOWLEDGE_READ}>
              <KnowledgeDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/memory"
          element={
            <Restricted permission={PERMISSIONS.MEMORY_READ}>
              <MemoryListPage />
            </Restricted>
          }
        />
        <Route
          path="/memory/:memoryId"
          element={
            <Restricted permission={PERMISSIONS.MEMORY_READ}>
              <MemoryDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/skills"
          element={
            <Restricted permission={PERMISSIONS.SKILL_READ}>
              <SkillsListPage />
            </Restricted>
          }
        />
        <Route
          path="/skills/:skillId"
          element={
            <Restricted permission={PERMISSIONS.SKILL_READ}>
              <SkillDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/audit"
          element={
            <Restricted permission={PERMISSIONS.AUDIT_READ}>
              <AuditListPage />
            </Restricted>
          }
        />
        <Route
          path="/audit/:auditId"
          element={
            <Restricted permission={PERMISSIONS.AUDIT_READ}>
              <AuditDetailPage />
            </Restricted>
          }
        />
        <Route
          path="/tokens"
          element={
            <Restricted permission={PERMISSIONS.TOKEN_READ_OWN}>
              <ApiTokensPage />
            </Restricted>
          }
        />
        <Route
          path="/tools"
          element={
            <Restricted permission={PERMISSIONS.TOOL_READ}>
              <ToolsPage />
            </Restricted>
          }
        />
        <Route
          path="/operations"
          element={
            <RestrictedAny permissions={[PERMISSIONS.POLICY_READ, PERMISSIONS.MAINTENANCE_READ]}>
              <OperationsPage />
            </RestrictedAny>
          }
        />
        <Route
          path="/changes"
          element={
            <Restricted permission={PERMISSIONS.CHANGE_READ}>
              <ChangeEventsPage />
            </Restricted>
          }
        />
        <Route
          path="/integrations"
          element={
            <Restricted permission={PERMISSIONS.INTEGRATION_READ}>
              <IntegrationsPage />
            </Restricted>
          }
        />
        <Route
          path="/sandbox-runs"
          element={
            <Restricted permission={PERMISSIONS.SANDBOX_READ}>
              <SandboxRunsListPage />
            </Restricted>
          }
        />
        <Route
          path="/sandbox-runs/:runId"
          element={
            <Restricted permission={PERMISSIONS.SANDBOX_READ}>
              <SandboxRunDetailPage />
            </Restricted>
          }
        />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

function Restricted({
  permission,
  children,
}: {
  permission: Permission
  children: React.ReactNode
}) {
  return <PermissionBoundary permission={permission}>{children}</PermissionBoundary>
}

function RestrictedAny({
  permissions,
  children,
}: {
  permissions: Permission[]
  children: React.ReactNode
}) {
  return <PermissionBoundary permissions={permissions}>{children}</PermissionBoundary>
}
