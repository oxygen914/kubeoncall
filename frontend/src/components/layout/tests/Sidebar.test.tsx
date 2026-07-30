import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { SessionData } from '@/api/auth'
import { PERMISSIONS } from '@/features/auth/permissions'
import { Sidebar } from '../Sidebar'

const session: SessionData = {
  authenticated: true,
  user: {
    id: 'usr_1',
    username: 'operator',
    displayName: 'Operator',
    roles: [],
    permissions: [PERMISSIONS.DASHBOARD_READ],
  },
  expiresAt: '2026-07-30T00:00:00Z',
}

describe('Sidebar', () => {
  it('exposes Grafana Logs as an explicit external navigation item', () => {
    render(
      <MemoryRouter>
        <Sidebar collapsed={false} session={session} onToggle={vi.fn()} />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '可观测性' }))
    const link = screen.getByRole('link', { name: '日志分析 （在新标签页打开）' })
    expect(link).toHaveAttribute('href', 'http://localhost:3000/d/kubeoncall-logs/kubeoncall-logs')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })

  it('keeps low-frequency token and migration tools out of the primary sidebar', () => {
    const adminSession: SessionData = {
      ...session,
      user: {
        ...session.user,
        permissions: [
          PERMISSIONS.DASHBOARD_READ,
          PERMISSIONS.SYSTEM_READ,
          PERMISSIONS.SYSTEM_MANAGE,
          PERMISSIONS.TOKEN_READ_OWN,
        ],
      },
    }

    render(
      <MemoryRouter>
        <Sidebar collapsed={false} session={adminSession} onToggle={vi.fn()} />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '管理中心' }))
    expect(screen.getByRole('link', { name: '系统状态' })).toHaveAttribute('href', '/system')
    expect(screen.queryByRole('link', { name: /Token/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /迁移/ })).not.toBeInTheDocument()
  })
})
