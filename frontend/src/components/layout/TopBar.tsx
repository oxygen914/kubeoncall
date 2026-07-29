import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { SessionData } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Icon } from '@/components/ui/Icon'
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
            aria-label="打开全局搜索或命令面板"
          >
            <Icon name="search" size={17} />
            <span>搜索资源或运行命令</span>
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
          <button
            className="koc-icon-button"
            type="button"
            aria-label="查看通知"
            title="通知中心（入口预留）"
          >
            <Icon name="bell" />
            <span
              className="koc-topbar__notification-dot"
              role="status"
              aria-label="存在未读通知"
            />
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
                <Button variant="ghost" size="sm" role="menuitem" onClick={() => void onLogout()}>
                  <Icon name="logout" size={16} />
                  退出登录
                </Button>
              </div>
            ) : null}
          </div>
        </div>
      </header>
      {commandOpen ? <CommandDialog onClose={() => setCommandOpen(false)} /> : null}
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

function CommandDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const destinations = [
    { label: '查看当前风险概览', path: '/overview' },
    { label: '进入集群态势', path: '/monitoring' },
    { label: '查看活跃告警', path: '/alarms' },
    { label: '查看待审批任务', path: '/approvals' },
    { label: '向 AI 助手提问', path: '/ask' },
  ]

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
          <input ref={inputRef} placeholder="搜索页面、资源或命令…" aria-label="搜索命令" />
          <kbd>Esc</kbd>
        </div>
        <div className="koc-command__heading" id="command-title">
          快速导航
        </div>
        <ul>
          {destinations.map((destination) => (
            <li key={destination.path}>
              <button type="button" onClick={() => go(destination.path)}>
                <span>{destination.label}</span>
                <Icon name="chevron-right" size={16} />
              </button>
            </li>
          ))}
        </ul>
        <p>资源全文检索将在统一搜索接口接入后启用；当前仅提供安全的页面导航。</p>
      </section>
    </div>
  )
}
