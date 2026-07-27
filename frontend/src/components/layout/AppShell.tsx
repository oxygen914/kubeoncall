import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import clsx from 'clsx'
import { useSession } from '@/features/auth/useSession'
import { hasPermission, PERMISSIONS, type Permission } from '@/features/auth/permissions'
import { Button } from '@/components/ui/Button'

interface NavItem {
  to: string
  label: string
  /** Omitted for destinations available to every authenticated user. */
  permission?: Permission
  /** Destination is visible when the user has at least one listed permission. */
  permissions?: Permission[]
  /** End match so `/` doesn't stay active everywhere. */
  end?: boolean
}

const NAV_ITEMS: NavItem[] = [
  { to: '/overview', label: '概览', permission: PERMISSIONS.DASHBOARD_READ },
  { to: '/alarms', label: '告警', permission: PERMISSIONS.ALARM_READ },
  { to: '/approvals', label: '审批', permission: PERMISSIONS.APPROVAL_READ },
  { to: '/executions', label: '执行', permission: PERMISSIONS.EXECUTION_READ },
  { to: '/ask', label: '提问', permission: PERMISSIONS.ASK_EXECUTE },
  { to: '/knowledge', label: '知识库', permission: PERMISSIONS.KNOWLEDGE_READ },
  { to: '/memory', label: '记忆', permission: PERMISSIONS.MEMORY_READ },
  { to: '/skills', label: '技能', permission: PERMISSIONS.SKILL_READ },
  { to: '/tools', label: '工具', permission: PERMISSIONS.TOOL_READ },
  {
    to: '/operations',
    label: '告警运营',
    permissions: [PERMISSIONS.POLICY_READ, PERMISSIONS.MAINTENANCE_READ],
  },
  { to: '/changes', label: '变更事件', permission: PERMISSIONS.CHANGE_READ },
  { to: '/integrations', label: '集成通知', permission: PERMISSIONS.INTEGRATION_READ },
  { to: '/audit', label: '审计', permission: PERMISSIONS.AUDIT_READ },
  { to: '/users', label: '用户', permission: PERMISSIONS.SYSTEM_MANAGE },
  { to: '/tokens', label: 'API Token', permission: PERMISSIONS.TOKEN_READ_OWN },
  { to: '/system', label: '系统' },
  { to: '/migration', label: '数据迁移', permission: PERMISSIONS.SYSTEM_MANAGE },
]

export function AppShell() {
  const { session, logout } = useSession()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="koc-shell">
      <aside className="koc-shell__sidebar" aria-label="主导航">
        <div className="koc-shell__brand">KubeOnCall</div>
        <nav className="koc-shell__nav">
          <ul>
            {NAV_ITEMS.filter(
              (item) =>
                (!item.permission && !item.permissions) ||
                (item.permission ? hasPermission(session, item.permission) : false) ||
                (item.permissions?.some((permission) => hasPermission(session, permission)) ??
                  false),
            ).map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    clsx('koc-shell__navlink', isActive && 'koc-shell__navlink--active')
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </aside>

      <div className="koc-shell__main">
        <header className="koc-shell__topbar">
          <div className="koc-shell__user" data-testid="current-user">
            {session?.user?.displayName ?? session?.user?.username ?? ''}
          </div>
          <Button variant="ghost" size="sm" onClick={handleLogout}>
            退出登录
          </Button>
        </header>
        <main className="koc-shell__content" id="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
