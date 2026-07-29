import { render, screen } from '@testing-library/react'
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

    const link = screen.getByRole('link', { name: '日志分析 （在新标签页打开）' })
    expect(link).toHaveAttribute('href', 'http://localhost:3000/d/kubeoncall-logs/kubeoncall-logs')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })
})
