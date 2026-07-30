import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { SessionData } from '@/api/auth'
import { PERMISSIONS } from '@/features/auth/permissions'
import { TopBar } from '../TopBar'

vi.mock('@/features/monitoring/monitoringScopeContext', () => ({
  useMonitoringScope: () => ({
    scope: { cluster: 'prod', environment: '', namespace: '' },
    catalog: {
      clusters: [{ value: 'prod', label: 'prod', resourceCount: 1 }],
      environments: [],
      namespaces: [],
      capabilities: {
        environmentFilterAvailable: false,
        namespaceFilterAvailable: false,
      },
    },
    isLoading: false,
    error: null,
    setCluster: vi.fn(),
    setEnvironment: vi.fn(),
    setNamespace: vi.fn(),
  }),
}))

vi.mock('@/features/theme/themeContext', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }),
}))

const session: SessionData = {
  authenticated: true,
  user: {
    id: 'usr_1',
    username: 'operator',
    displayName: 'Operator',
    roles: ['OPERATOR'],
    permissions: [
      PERMISSIONS.DASHBOARD_READ,
      PERMISSIONS.ALARM_READ,
      PERMISSIONS.TOKEN_READ_OWN,
      PERMISSIONS.SYSTEM_MANAGE,
    ],
  },
  expiresAt: '2026-07-31T00:00:00Z',
}

describe('TopBar navigation', () => {
  it('searches only accessible pages and keeps API Token in the user menu', () => {
    render(
      <MemoryRouter>
        <TopBar session={session} onLogout={vi.fn()} onOpenNavigation={vi.fn()} />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '打开快速导航' }))
    expect(screen.getByRole('button', { name: '查看活跃告警' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '向 AI 助手提问' })).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('搜索可访问页面'), {
      target: { value: '告警' },
    })
    expect(screen.getByRole('button', { name: '查看活跃告警' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看当前风险概览' })).not.toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: /Operator/ }))
    expect(screen.getByRole('menuitem', { name: 'API Token' })).toHaveAttribute('href', '/tokens')
    expect(screen.getByRole('menuitem', { name: '数据迁移' })).toHaveAttribute('href', '/migration')
    expect(screen.queryByLabelText(/通知/)).not.toBeInTheDocument()
  })

  it('exposes mobile navigation and scope controls without duplicating desktop behavior', () => {
    const onOpenNavigation = vi.fn()
    render(
      <MemoryRouter>
        <TopBar session={session} onLogout={vi.fn()} onOpenNavigation={onOpenNavigation} />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '打开主导航' }))
    expect(onOpenNavigation).toHaveBeenCalledOnce()

    const scopeButton = screen.getByRole('button', {
      name: '切换全局范围，当前集群 prod',
    })
    fireEvent.click(scopeButton)
    expect(scopeButton).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getAllByLabelText('全局集群')).toHaveLength(2)
  })
})
