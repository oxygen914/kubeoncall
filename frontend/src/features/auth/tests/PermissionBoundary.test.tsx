import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { AppRouter } from '@/app/router'
import { SessionContext, type SessionState } from '../sessionContext'
import { PERMISSIONS, type Permission } from '../permissions'
import { ThemeProvider } from '@/features/theme/ThemeProvider'

function renderRoute(route: string, permissions: Permission[]) {
  const sessionState: SessionState = {
    session: {
      authenticated: true,
      user: {
        id: 'usr_1',
        username: 'operator',
        displayName: 'Operator',
        roles: [],
        permissions,
      },
      expiresAt: '2026-07-21T00:00:00Z',
    },
    loading: false,
    error: null,
    login: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
  }
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <SessionContext.Provider value={sessionState}>
        <ThemeProvider>
          <MemoryRouter initialEntries={[route]}>
            <AppRouter />
          </MemoryRouter>
        </ThemeProvider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  )
}

describe('PermissionBoundary', () => {
  it('renders the 403 page for an authenticated user without the route permission', async () => {
    renderRoute('/ask', [PERMISSIONS.ALARM_READ])

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.getByText('当前账号没有访问该页面的权限。')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回可访问首页' })).toHaveAttribute('href', '/alarms')
  })

  it('renders a protected route when the permission is present', () => {
    renderRoute('/ask', [PERMISSIONS.ASK_EXECUTE])

    expect(screen.getByRole('heading', { name: 'AI 诊断' })).toBeInTheDocument()
  })

  it('only exposes destinations permitted to the current account', () => {
    renderRoute('/ask', [PERMISSIONS.ASK_EXECUTE])

    expect(screen.getByRole('link', { name: 'AI 诊断' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '系统' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '告警' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '技能' })).not.toBeInTheDocument()
  })
})
