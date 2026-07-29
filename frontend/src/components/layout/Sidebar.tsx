import { NavLink } from 'react-router-dom'
import clsx from 'clsx'
import { hasPermission, PERMISSIONS, type Permission } from '@/features/auth/permissions'
import type { SessionData } from '@/api/auth'
import { Icon, type IconName } from '@/components/ui/Icon'

interface NavItem {
  to: string
  label: string
  icon: IconName
  permission?: Permission
  permissions?: Permission[]
}

interface NavSection {
  label: string
  items: NavItem[]
}

const NAV_SECTIONS: NavSection[] = [
  {
    label: '监控中心',
    items: [
      { to: '/overview', label: '概览', icon: 'grid', permission: PERMISSIONS.DASHBOARD_READ },
      {
        to: '/monitoring',
        label: '集群态势',
        icon: 'cluster',
        permission: PERMISSIONS.DASHBOARD_READ,
      },
      { to: '/alarms', label: '告警', icon: 'alarm', permission: PERMISSIONS.ALARM_READ },
      {
        to: '/changes',
        label: '变更事件',
        icon: 'change',
        permission: PERMISSIONS.CHANGE_READ,
      },
    ],
  },
  {
    label: '响应处置',
    items: [
      {
        to: '/operations',
        label: '告警运营',
        icon: 'activity',
        permissions: [PERMISSIONS.POLICY_READ, PERMISSIONS.MAINTENANCE_READ],
      },
      {
        to: '/approvals',
        label: '审批',
        icon: 'approval',
        permission: PERMISSIONS.APPROVAL_READ,
      },
      {
        to: '/executions',
        label: '执行',
        icon: 'execution',
        permission: PERMISSIONS.EXECUTION_READ,
      },
      {
        to: '/sandbox-runs',
        label: 'Sandbox 运行',
        icon: 'box',
        permission: PERMISSIONS.SANDBOX_READ,
      },
    ],
  },
  {
    label: 'AI 助手',
    items: [
      { to: '/ask', label: '提问', icon: 'ask', permission: PERMISSIONS.ASK_EXECUTE },
      {
        to: '/knowledge',
        label: '知识库',
        icon: 'book',
        permission: PERMISSIONS.KNOWLEDGE_READ,
      },
      {
        to: '/memory',
        label: '记忆',
        icon: 'memory',
        permission: PERMISSIONS.MEMORY_READ,
      },
      {
        to: '/skills',
        label: '技能',
        icon: 'skill',
        permission: PERMISSIONS.SKILL_READ,
      },
      { to: '/tools', label: '工具', icon: 'tools', permission: PERMISSIONS.TOOL_READ },
    ],
  },
  {
    label: '平台治理',
    items: [
      {
        to: '/integrations',
        label: '集成通知',
        icon: 'integration',
        permission: PERMISSIONS.INTEGRATION_READ,
      },
      { to: '/audit', label: '审计', icon: 'shield', permission: PERMISSIONS.AUDIT_READ },
      { to: '/users', label: '用户', icon: 'users', permission: PERMISSIONS.SYSTEM_MANAGE },
      {
        to: '/tokens',
        label: 'API Token',
        icon: 'key',
        permission: PERMISSIONS.TOKEN_READ_OWN,
      },
      { to: '/system', label: '系统', icon: 'settings' },
      {
        to: '/migration',
        label: '数据迁移',
        icon: 'change',
        permission: PERMISSIONS.SYSTEM_MANAGE,
      },
    ],
  },
]

interface SidebarProps {
  collapsed: boolean
  session: SessionData | null
  onToggle: () => void
}

export function Sidebar({ collapsed, session, onToggle }: SidebarProps) {
  const displayName = session?.user?.displayName ?? session?.user?.username ?? '当前用户'

  return (
    <aside
      className={clsx('koc-sidebar', collapsed && 'koc-sidebar--collapsed')}
      aria-label="主导航"
    >
      <div className="koc-sidebar__brand">
        <span className="koc-sidebar__brand-mark" aria-hidden="true">
          K
        </span>
        {collapsed ? null : (
          <span className="koc-sidebar__brand-copy">
            <strong>KubeOnCall</strong>
            <small>Operations Console</small>
          </span>
        )}
        <button
          className="koc-icon-button koc-sidebar__toggle"
          type="button"
          onClick={onToggle}
          aria-label={collapsed ? '展开导航' : '折叠导航'}
          title={collapsed ? '展开导航' : '折叠导航'}
        >
          <Icon name={collapsed ? 'chevron-right' : 'chevron-left'} size={16} />
        </button>
      </div>

      <nav className="koc-sidebar__nav" aria-label="功能导航">
        {NAV_SECTIONS.map((section) => (
          <SidebarSection
            key={section.label}
            section={section}
            collapsed={collapsed}
            session={session}
          />
        ))}
      </nav>

      <div className="koc-sidebar__user">
        <span className="koc-sidebar__avatar" aria-hidden="true">
          {displayName.slice(0, 1).toUpperCase()}
        </span>
        {collapsed ? null : (
          <span className="koc-sidebar__user-copy">
            <strong title={displayName}>{displayName}</strong>
            <small>已连接 · 企业工作区</small>
          </span>
        )}
      </div>
    </aside>
  )
}

function SidebarSection({
  section,
  collapsed,
  session,
}: {
  section: NavSection
  collapsed: boolean
  session: SessionData | null
}) {
  const visibleItems = section.items.filter(
    (item) =>
      (!item.permission && !item.permissions) ||
      (item.permission ? hasPermission(session, item.permission) : false) ||
      (item.permissions?.some((permission) => hasPermission(session, permission)) ?? false),
  )

  if (visibleItems.length === 0) return null

  return (
    <section className="koc-sidebar__section" aria-label={section.label}>
      {collapsed ? (
        <span className="koc-sidebar__section-divider" aria-hidden="true" />
      ) : (
        <h2>{section.label}</h2>
      )}
      <ul>
        {visibleItems.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              className={({ isActive }) =>
                clsx('koc-sidebar__link', isActive && 'koc-sidebar__link--active')
              }
              title={collapsed ? item.label : undefined}
            >
              <Icon name={item.icon} />
              {collapsed ? <span className="koc-visually-hidden">{item.label}</span> : item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </section>
  )
}
