import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthBoundary } from '@/features/auth/AuthBoundary'
import { LoginPage } from '@/features/auth/LoginPage'
import { AppShell } from '@/components/layout/AppShell'
import { ForbiddenPage } from '@/components/feedback/ForbiddenPage'
import { NotFoundPage } from '@/components/feedback/NotFoundPage'
import { PermissionBoundary } from '@/features/auth/PermissionBoundary'
import { PERMISSIONS, type Permission } from '@/features/auth/permissions'
import { getDefaultConsolePath } from '@/features/auth/defaultRoute'
import { useSession } from '@/features/auth/useSession'

const SystemStatusPage = lazy(() =>
  import('@/features/system/SystemStatusPage').then((module) => ({
    default: module.SystemStatusPage,
  })),
)
const OverviewPage = lazy(() =>
  import('@/features/overview/OverviewPage').then((module) => ({
    default: module.OverviewPage,
  })),
)
const MonitoringPage = lazy(() =>
  import('@/features/monitoring/MonitoringPage').then((module) => ({
    default: module.MonitoringPage,
  })),
)
const AskPage = lazy(() =>
  import('@/features/ask/AskPage').then((module) => ({ default: module.AskPage })),
)
const UserManagementPage = lazy(() =>
  import('@/features/users/UserManagementPage').then((module) => ({
    default: module.UserManagementPage,
  })),
)
const MigrationPage = lazy(() =>
  import('@/features/migration/MigrationPage').then((module) => ({
    default: module.MigrationPage,
  })),
)
const AlarmsListPage = lazy(() =>
  import('@/features/alarms/AlarmsListPage').then((module) => ({
    default: module.AlarmsListPage,
  })),
)
const AlarmDetailPage = lazy(() =>
  import('@/features/alarms/AlarmDetailPage').then((module) => ({
    default: module.AlarmDetailPage,
  })),
)
const ApprovalsListPage = lazy(() =>
  import('@/features/approvals/ApprovalsListPage').then((module) => ({
    default: module.ApprovalsListPage,
  })),
)
const ApprovalDetailPage = lazy(() =>
  import('@/features/approvals/ApprovalDetailPage').then((module) => ({
    default: module.ApprovalDetailPage,
  })),
)
const ExecutionsListPage = lazy(() =>
  import('@/features/executions/ExecutionsListPage').then((module) => ({
    default: module.ExecutionsListPage,
  })),
)
const ExecutionDetailPage = lazy(() =>
  import('@/features/executions/ExecutionDetailPage').then((module) => ({
    default: module.ExecutionDetailPage,
  })),
)
const KnowledgeListPage = lazy(() =>
  import('@/features/knowledge/KnowledgeListPage').then((module) => ({
    default: module.KnowledgeListPage,
  })),
)
const KnowledgeDetailPage = lazy(() =>
  import('@/features/knowledge/KnowledgeDetailPage').then((module) => ({
    default: module.KnowledgeDetailPage,
  })),
)
const MemoryListPage = lazy(() =>
  import('@/features/memories/MemoryListPage').then((module) => ({
    default: module.MemoryListPage,
  })),
)
const MemoryDetailPage = lazy(() =>
  import('@/features/memories/MemoryDetailPage').then((module) => ({
    default: module.MemoryDetailPage,
  })),
)
const SkillsListPage = lazy(() =>
  import('@/features/skills/SkillsListPage').then((module) => ({
    default: module.SkillsListPage,
  })),
)
const SkillDetailPage = lazy(() =>
  import('@/features/skills/SkillDetailPage').then((module) => ({
    default: module.SkillDetailPage,
  })),
)
const AuditListPage = lazy(() =>
  import('@/features/audit/AuditListPage').then((module) => ({
    default: module.AuditListPage,
  })),
)
const AuditDetailPage = lazy(() =>
  import('@/features/audit/AuditDetailPage').then((module) => ({
    default: module.AuditDetailPage,
  })),
)
const ApiTokensPage = lazy(() =>
  import('@/features/tokens/ApiTokensPage').then((module) => ({
    default: module.ApiTokensPage,
  })),
)
const ToolsPage = lazy(() =>
  import('@/features/tools/ToolsPage').then((module) => ({ default: module.ToolsPage })),
)
const OperationsPage = lazy(() =>
  import('@/features/operations/OperationsPage').then((module) => ({
    default: module.OperationsPage,
  })),
)
const ChangeEventsPage = lazy(() =>
  import('@/features/changes/ChangeEventsPage').then((module) => ({
    default: module.ChangeEventsPage,
  })),
)
const IntegrationsPage = lazy(() =>
  import('@/features/integrations/IntegrationsPage').then((module) => ({
    default: module.IntegrationsPage,
  })),
)
const SandboxRunsListPage = lazy(() =>
  import('@/features/sandbox/SandboxRunsListPage').then((module) => ({
    default: module.SandboxRunsListPage,
  })),
)
const SandboxRunDetailPage = lazy(() =>
  import('@/features/sandbox/SandboxRunDetailPage').then((module) => ({
    default: module.SandboxRunDetailPage,
  })),
)

/**
 * Application route table.
 *
 * `/` is protected by AuthBoundary and resolves to the first page the current account may access.
 * Every feature route repeats its backend permission boundary for predictable deep-link behavior.
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
        <Route index element={<DefaultConsoleRoute />} />
        <Route path="/forbidden" element={<ForbiddenPage />} />
        <Route
          path="/system"
          element={
            <Restricted permission={PERMISSIONS.SYSTEM_READ}>
              <SystemStatusPage />
            </Restricted>
          }
        />
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
          path="/monitoring"
          element={
            <Restricted permission={PERMISSIONS.DASHBOARD_READ}>
              <MonitoringPage />
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

function DefaultConsoleRoute() {
  const { session } = useSession()
  return <Navigate to={getDefaultConsolePath(session) ?? '/forbidden'} replace />
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
