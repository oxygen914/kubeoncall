import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'

// Mock the auth API module so the test never hits the network.
// `vi.mock` is hoisted, so we use vi.hoisted to share the spy.
const { loginMock, getSessionMock } = vi.hoisted(() => ({
  loginMock: vi.fn(),
  getSessionMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({
  login: (input: { username: string; password: string }) => loginMock(input),
  logout: vi.fn(),
  getSession: () => getSessionMock(),
}))

import { SessionProvider } from '../SessionProvider'
import { LoginPage } from '../LoginPage'

function renderLogin() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/system" element={<div>system page</div>} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    loginMock.mockReset()
    // Default: not authenticated. Use a real ApiError so SessionProvider's
    // 401 handling kicks in cleanly.
    getSessionMock.mockRejectedValue(
      new ApiError({
        code: 'AUTH_REQUIRED',
        message: 'unauthenticated',
        status: 401,
        retryable: false,
      }),
    )
  })

  it('renders the form and submits credentials', async () => {
    const user = userEvent.setup()
    loginMock.mockResolvedValue({
      authenticated: true,
      user: { id: 'u1', username: 'alice', displayName: 'Alice', roles: [], permissions: [] },
      expiresAt: '2026-12-31T00:00:00Z',
    })

    renderLogin()

    const username = await screen.findByLabelText('用户名')
    const password = screen.getByLabelText('密码')
    const submit = screen.getByRole('button', { name: '登录' })

    await user.type(username, 'alice')
    await user.type(password, 'secret')
    await user.click(submit)

    await waitFor(() => expect(loginMock).toHaveBeenCalledTimes(1))
    expect(loginMock).toHaveBeenCalledWith({ username: 'alice', password: 'secret' })
  })

  it('shows a unified error on invalid credentials', async () => {
    const user = userEvent.setup()
    loginMock.mockRejectedValue(
      new ApiError({
        code: 'AUTH_INVALID_CREDENTIALS',
        message: 'bad creds',
        status: 401,
        retryable: false,
      }),
    )

    renderLogin()

    await user.type(await screen.findByLabelText('用户名'), 'bob')
    await user.type(screen.getByLabelText('密码'), 'wrong')
    await user.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('用户名或密码错误')
  })
})
