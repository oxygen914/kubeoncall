import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ApprovalDetail } from '../api'
import { SessionContext, type SessionState } from '@/features/auth/sessionContext'
import { PERMISSIONS, type Permission } from '@/features/auth/permissions'

const { useApprovalMock } = vi.hoisted(() => ({ useApprovalMock: vi.fn() }))

vi.mock('../hooks', () => ({
  useApproval: () => useApprovalMock(),
  useDecideApproval: vi.fn(),
}))

import { ApprovalDetailPage } from '../ApprovalDetailPage'

const approval: ApprovalDetail = {
  id: 'apr_1',
  executionId: 'exec_1',
  status: 'PENDING',
  riskLevel: 'HIGH',
  summary: '重启异常节点',
  createdAt: '2026-07-20T12:00:00Z',
  expiresAt: null,
  version: 3,
  action: 'restart-node',
  context: { node: 'worker-1' },
}

function renderPage(permissions: Permission[]) {
  useApprovalMock.mockReturnValue({
    data: approval,
    isLoading: false,
    error: null,
  })
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

  return render(
    <SessionContext.Provider value={sessionState}>
      <MemoryRouter initialEntries={['/approvals/apr_1']}>
        <Routes>
          <Route path="/approvals/:approvalId" element={<ApprovalDetailPage />} />
        </Routes>
      </MemoryRouter>
    </SessionContext.Provider>,
  )
}

describe('ApprovalDetailPage', () => {
  it('hides decision actions from a read-only user', () => {
    renderPage([PERMISSIONS.APPROVAL_READ])

    expect(screen.getByText('重启异常节点')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '批准' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()
  })

  it('shows decision actions and an execution link with the corresponding permissions', () => {
    renderPage([PERMISSIONS.APPROVAL_READ, PERMISSIONS.APPROVAL_DECIDE, PERMISSIONS.EXECUTION_READ])

    expect(screen.getByRole('button', { name: '批准' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'exec_1' })).toHaveAttribute(
      'href',
      '/executions/exec_1',
    )
  })
})
