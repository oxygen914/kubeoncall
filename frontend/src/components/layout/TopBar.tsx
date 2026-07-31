import { useCallback, useEffect, useRef, useState } from 'react'
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
  onOpenNavigation: () => void
}

export function TopBar({ session, onLogout, onOpenNavigation }: TopBarProps) {
  const [commandOpen, setCommandOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)
  const [scopeOpen, setScopeOpen] = useState(false)
  const commandTriggerRef = useRef<HTMLButtonElement>(null)
  const userTriggerRef = useRef<HTMLButtonElement>(null)
  const userMenuRef = useRef<HTMLDivElement>(null)
  const scopeTriggerRef = useRef<HTMLButtonElement>(null)
  const scopePanelRef = useRef<HTMLDivElement>(null)
  const { scope, catalog, isLoading, error, setCluster, setEnvironment, setNamespace } =
    useMonitoringScope()
  const { theme, toggleTheme } = useTheme()

  const openCommand = useCallback(() => setCommandOpen(true), [])
  const closeCommand = useCallback(() => {
    setCommandOpen(false)
    commandTriggerRef.current?.focus()
  }, [])

  useEffect(() => {
    const openCommandShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        openCommand()
      }
    }
    window.addEventListener('keydown', openCommandShortcut)
    return () => window.removeEventListener('keydown', openCommandShortcut)
  }, [openCommand])

  useEffect(() => {
    if (!userOpen) return
    const closeUserMenu = (event: PointerEvent) => {
      if (!userMenuRef.current?.contains(event.target as Node)) setUserOpen(false)
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setUserOpen(false)
      userTriggerRef.current?.focus()
    }
    window.addEventListener('pointerdown', closeUserMenu)
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      window.removeEventListener('pointerdown', closeUserMenu)
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [userOpen])

  useEffect(() => {
    if (!scopeOpen) return
    const closeScopePanel = (event: PointerEvent) => {
      const target = event.target as Node
      if (!scopePanelRef.current?.contains(target) && !scopeTriggerRef.current?.contains(target)) {
        setScopeOpen(false)
      }
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setScopeOpen(false)
      scopeTriggerRef.current?.focus()
    }
    window.addEventListener('pointerdown', closeScopePanel)
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      window.removeEventListener('pointerdown', closeScopePanel)
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [scopeOpen])

  const displayName = session?.user?.displayName ?? session?.user?.username ?? ''

  return (
    <>
      <header className="koc-topbar">
        <div className="koc-topbar__leading">
          <button
            className="koc-icon-button koc-topbar__mobile-nav"
            type="button"
            aria-label="打开主导航"
            aria-controls="primary-navigation"
            onClick={onOpenNavigation}
          >
            <Icon name="menu" />
          </button>
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
          <button
            ref={scopeTriggerRef}
            className="koc-topbar__mobile-scope"
            type="button"
            aria-label={`切换全局范围，当前集群 ${scope.cluster || '未选择'}`}
            aria-expanded={scopeOpen}
            aria-controls="mobile-scope-panel"
            onClick={() => setScopeOpen((current) => !current)}
          >
            <Icon name="cluster" size={16} />
            <span>{scope.cluster || '选择范围'}</span>
            <Icon name="chevron-right" size={14} />
          </button>
        </div>

        <div className="koc-topbar__actions">
          <button
            ref={commandTriggerRef}
            className="koc-command-trigger"
            type="button"
            onClick={openCommand}
            aria-label="打开快速导航"
          >
            <Icon name="search" size={17} />
            <span>快速导航</span>
            <kbd>⌘ K</kbd>
          </button>
          <button
            className="koc-icon-button koc-topbar__theme-toggle"
            type="button"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'}
            title={theme === 'dark' ? '浅色主题' : '深色主题'}
          >
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
          </button>
          <div ref={userMenuRef} className="koc-topbar__user-menu">
            <button
              ref={userTriggerRef}
              className="koc-topbar__user-trigger"
              type="button"
              onClick={() => setUserOpen((current) => !current)}
              aria-label={`打开用户菜单：${displayName}`}
              aria-expanded={userOpen}
              aria-controls="topbar-user-actions"
            >
              <span className="koc-topbar__avatar" aria-hidden="true">
                {displayName.slice(0, 1).toUpperCase()}
              </span>
              <span>{displayName}</span>
            </button>
            {userOpen ? (
              <div className="koc-topbar__menu" id="topbar-user-actions" aria-label="用户操作">
                <div>
                  <strong>{displayName}</strong>
                  <small>{session?.user?.roles.join(' / ') || 'Authenticated user'}</small>
                </div>
                {hasPermission(session, PERMISSIONS.TOKEN_READ_OWN) ? (
                  <Link
                    className="koc-topbar__menu-link"
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
                    to="/migration"
                    onClick={() => setUserOpen(false)}
                  >
                    <Icon name="change" size={16} />
                    数据迁移
                  </Link>
                ) : null}
                <button className="koc-topbar__menu-link" type="button" onClick={toggleTheme}>
                  <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={16} />
                  {theme === 'dark' ? '切换浅色主题' : '切换深色主题'}
                </button>
                <Button variant="ghost" size="sm" onClick={() => void onLogout()}>
                  <Icon name="logout" size={16} />
                  退出登录
                </Button>
              </div>
            ) : null}
          </div>
        </div>
        {scopeOpen ? (
          <div ref={scopePanelRef} className="koc-topbar__scope-panel" id="mobile-scope-panel">
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
        ) : null}
      </header>
      {commandOpen ? <CommandDialog session={session} onClose={closeCommand} /> : null}
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
  const panelRef = useRef<HTMLElement>(null)
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const destinations: Array<{
    label: string
    path: string
    permission: Permission
    keywords: string
  }> = [
    {
      label: '查看当前风险概览',
      path: '/overview?view=workbench',
      permission: PERMISSIONS.DASHBOARD_READ,
      keywords: '概览 风险 dashboard',
    },
    {
      label: '进入集群态势',
      path: '/monitoring?view=overview',
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
  const resolvedActiveIndex = Math.min(activeIndex, Math.max(visibleDestinations.length - 1, 0))
  const activeDestination = visibleDestinations[resolvedActiveIndex]

  useEffect(() => {
    setActiveIndex(0)
  }, [query])

  useEffect(() => {
    inputRef.current?.focus()
    const handleDialogKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const focusable = Array.from(
        panelRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), a[href], select:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      )
      if (focusable.length === 0) return
      const first = focusable[0]!
      const last = focusable[focusable.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', handleDialogKey)
    return () => window.removeEventListener('keydown', handleDialogKey)
  }, [onClose])

  const go = (path: string) => {
    navigate(path)
    onClose()
  }

  return (
    <div className="koc-command" role="presentation" onMouseDown={onClose}>
      <section
        ref={panelRef}
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
            role="combobox"
            aria-autocomplete="list"
            aria-controls="command-destinations"
            aria-expanded="true"
            aria-activedescendant={
              activeDestination ? `command-destination-${resolvedActiveIndex}` : undefined
            }
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown') {
                event.preventDefault()
                setActiveIndex((current) =>
                  visibleDestinations.length === 0 ? 0 : (current + 1) % visibleDestinations.length,
                )
              } else if (event.key === 'ArrowUp') {
                event.preventDefault()
                setActiveIndex((current) =>
                  visibleDestinations.length === 0
                    ? 0
                    : (current - 1 + visibleDestinations.length) % visibleDestinations.length,
                )
              } else if (event.key === 'Home') {
                event.preventDefault()
                setActiveIndex(0)
              } else if (event.key === 'End') {
                event.preventDefault()
                setActiveIndex(Math.max(visibleDestinations.length - 1, 0))
              } else if (event.key === 'Enter' && activeDestination) {
                event.preventDefault()
                go(activeDestination.path)
              }
            }}
          />
          <kbd>Esc</kbd>
        </div>
        <div className="koc-command__heading" id="command-title">
          快速导航
        </div>
        <ul id="command-destinations" role="listbox" aria-label="快速导航结果">
          {visibleDestinations.map((destination, index) => (
            <li
              id={`command-destination-${index}`}
              key={destination.path}
              role="option"
              aria-selected={index === resolvedActiveIndex}
            >
              <button
                type="button"
                tabIndex={-1}
                data-active={index === resolvedActiveIndex || undefined}
                onMouseMove={() => setActiveIndex(index)}
                onFocus={() => setActiveIndex(index)}
                onClick={() => go(destination.path)}
              >
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
