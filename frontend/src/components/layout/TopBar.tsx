import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { SessionData } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Icon } from '@/components/ui/Icon'
import { hasPermission, PERMISSIONS, type Permission } from '@/features/auth/permissions'
import { useMonitoringScope } from '@/features/monitoring/monitoringScopeContext'
import { useTheme } from '@/features/theme/themeContext'

interface TopBarProps {
  session: SessionData | null
  onLogout: () => Promise<void>
}

export function TopBar({ session, onLogout }: TopBarProps) {
  const [commandOpen, setCommandOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)
  const { scope, catalog, isLoading, error, setCluster, setEnvironment, setNamespace } =
    useMonitoringScope()
  const { theme, toggleTheme } = useTheme()

  useEffect(() => {
    const openCommand = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setCommandOpen(true)
      }
    }
    window.addEventListener('keydown', openCommand)
    return () => window.removeEventListener('keydown', openCommand)
  }, [])

  const displayName = session?.user?.displayName ?? session?.user?.username ?? ''

  return (
    <>
      <header className="koc-topbar">
        <div className="koc-topbar__scope" aria-label="当前全局查看范围">
          <ContextSelector
            label="集群"
            value={scope.cluster}
            emptyLabel={isLoading ? '发现中…' : error ? '范围不可用' : '暂无集群'}
            options={catalog?.clusters ?? []}
            onChange={setCluster}
          />
          <ContextSelector
            label="环境"
            value={scope.environment ?? ''}
            emptyLabel="全部环境"
            options={catalog?.environments ?? []}
            onChange={setEnvironment}
            disabled={!catalog?.capabilities.environmentFilterAvailable}
          />
          <ContextSelector
            label="Namespace"
            value={scope.namespace ?? ''}
            emptyLabel="全部 Namespace"
            options={catalog?.namespaces ?? []}
            onChange={setNamespace}
            disabled={!catalog?.capabilities.namespaceFilterAvailable}
          />
        </div>

        <div className="koc-topbar__actions">
          <button
            className="koc-command-trigger"
            type="button"
            onClick={() => setCommandOpen(true)}
            aria-label="打开快速导航"
          >
            <Icon name="search" size={17} />
            <span>快速导航</span>
            <kbd>⌘ K</kbd>
          </button>
          <button
            className="koc-icon-button"
            type="button"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'}
            title={theme === 'dark' ? '浅色主题' : '深色主题'}
          >
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
          </button>
          <div className="koc-topbar__user-menu">
            <button
              className="koc-topbar__user-trigger"
              type="button"
              onClick={() => setUserOpen((current) => !current)}
              aria-expanded={userOpen}
              aria-haspopup="menu"
            >
              <span className="koc-topbar__avatar" aria-hidden="true">
                {displayName.slice(0, 1).toUpperCase()}
              </span>
              <span>{displayName}</span>
            </button>
            {userOpen ? (
              <div className="koc-topbar__menu" role="menu">
                <div>
                  <strong>{displayName}</strong>
                  <small>{session?.user?.roles.join(' / ') || 'Authenticated user'}</small>
                </div>
                {hasPermission(session, PERMISSIONS.TOKEN_READ_OWN) ? (
                  <Link
                    className="koc-topbar__menu-link"
                    role="menuitem"
                    to="/tokens"
                    onClick={() => setUserOpen(false)}
                  >
                    <Icon name="key" size={16} />
                    API Token
                  </Link>
                ) : null}
                {hasPermission(session, PERMISSIONS.SYSTEM_MANAGE) ? (
                  <Link
                    className="koc-topbar__menu-link"
                    role="menuitem"
                    to="/migration"
                    onClick={() => setUserOpen(false)}
                  >
                    <Icon name="change" size={16} />
                    数据迁移
                  </Link>
                ) : null}
                <Button variant="ghost" size="sm" role="menuitem" onClick={() => void onLogout()}>
                  <Icon name="logout" size={16} />
                  退出登录
                </Button>
              </div>
            ) : null}
          </div>
        </div>
      </header>
      {commandOpen ? (
        <CommandDialog session={session} onClose={() => setCommandOpen(false)} />
      ) : null}
    </>
  )
}

function ContextSelector({
  label,
  value,
  emptyLabel,
  options,
  onChange,
  disabled = false,
}: {
  label: string
  value: string
  emptyLabel: string
  options: Array<{ value: string; label: string; resourceCount: number }>
  onChange: (value: string) => void
  disabled?: boolean
}) {
  return (
    <label
      className="koc-context-selector"
      title={disabled ? `当前指标未提供可用的${label}标签` : `切换全局${label}范围`}
    >
      <span>{label}</span>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-label={`全局${label}`}
        disabled={disabled}
      >
        {label !== '集群' || options.length === 0 ? <option value="">{emptyLabel}</option> : null}
        {options.map((option) => (
          <option value={option.value} key={option.value}>
            {option.label}
            {option.resourceCount > 0 ? ` · ${option.resourceCount}` : ''}
          </option>
        ))}
      </select>
    </label>
  )
}

function CommandDialog({ session, onClose }: { session: SessionData | null; onClose: () => void }) {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const destinations: Array<{
    label: string
    path: string
    permission: Permission
    keywords: string
  }> = [
    {
      label: '查看当前风险概览',
      path: '/overview',
      permission: PERMISSIONS.DASHBOARD_READ,
      keywords: '概览 风险 dashboard',
    },
    {
      label: '进入集群态势',
      path: '/monitoring',
      permission: PERMISSIONS.DASHBOARD_READ,
      keywords: '集群 监控 节点 pod',
    },
    {
      label: '查看活跃告警',
      path: '/alarms',
      permission: PERMISSIONS.ALARM_READ,
      keywords: '告警 alarm',
    },
    {
      label: '向 AI 助手提问',
      path: '/ask',
      permission: PERMISSIONS.ASK_EXECUTE,
      keywords: 'AI 诊断 提问',
    },
    {
      label: '查看待审批任务',
      path: '/approvals',
      permission: PERMISSIONS.APPROVAL_READ,
      keywords: '审批 approval',
    },
    {
      label: '查看执行记录',
      path: '/executions',
      permission: PERMISSIONS.EXECUTION_READ,
      keywords: '执行 execution 失败',
    },
    {
      label: '查看变更事件',
      path: '/changes',
      permission: PERMISSIONS.CHANGE_READ,
      keywords: '变更 change',
    },
    {
      label: '查看 Sandbox 运行',
      path: '/sandbox-runs',
      permission: PERMISSIONS.SANDBOX_READ,
      keywords: 'sandbox 隔离 运行',
    },
  ]
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const visibleDestinations = destinations
    .filter((destination) => hasPermission(session, destination.permission))
    .filter((destination) =>
      `${destination.label} ${destination.keywords}`.toLocaleLowerCase().includes(normalizedQuery),
    )

  useEffect(() => {
    inputRef.current?.focus()
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  const go = (path: string) => {
    navigate(path)
    onClose()
  }

  return (
    <div className="koc-command" role="presentation" onMouseDown={onClose}>
      <section
        className="koc-command__panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="command-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="koc-command__input">
          <Icon name="search" />
          <input
            ref={inputRef}
            value={query}
            placeholder="搜索可访问页面…"
            aria-label="搜索可访问页面"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && visibleDestinations[0]) {
                event.preventDefault()
                go(visibleDestinations[0].path)
              }
            }}
          />
          <kbd>Esc</kbd>
        </div>
        <div className="koc-command__heading" id="command-title">
          快速导航
        </div>
        <ul>
          {visibleDestinations.map((destination) => (
            <li key={destination.path}>
              <button type="button" onClick={() => go(destination.path)}>
                <span>{destination.label}</span>
                <Icon name="chevron-right" size={16} />
              </button>
            </li>
          ))}
        </ul>
        {visibleDestinations.length === 0 ? (
          <p className="koc-command__empty" role="status">
            没有匹配的可访问页面。
          </p>
        ) : null}
        <p>仅搜索当前账号有权限访问的 Console 页面。</p>
      </section>
    </div>
  )
}
