import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PERMISSIONS, type Permission } from '@/features/auth/permissions'
import { SessionContext, type SessionState } from '@/features/auth/sessionContext'
import { SystemStatusPage } from '../SystemStatusPage'

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null,
    },
    json: async () => body,
  } as Response
}

function renderPage(permissions: Permission[] = [PERMISSIONS.SYSTEM_READ]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const sessionState: SessionState = {
    session: {
      authenticated: true,
      user: {
        id: 'usr_system',
        username: 'operator',
        displayName: 'Operator',
        roles: [],
        permissions,
      },
      expiresAt: '2026-07-31T00:00:00Z',
    },
    loading: false,
    error: null,
    login: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
  }
  return render(
    <QueryClientProvider client={queryClient}>
      <SessionContext.Provider value={sessionState}>
        <MemoryRouter>
          <SystemStatusPage />
        </MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  )
}

describe('SystemStatusPage backend contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/api/v1/system/status')) {
        return Promise.resolve(
          jsonResponse({
            data: { service: 'KubeOnCall', status: 'UP' },
            meta: {
              requestId: 'req_status',
              timestamp: '2026-07-20T12:00:00Z',
            },
          }),
        )
      }
      if (path.endsWith('/api/v1/capabilities')) {
        return Promise.resolve(
          jsonResponse({
            data: {
              release: {
                version: '1.2.3',
                commit: 'abc1234',
                environment: 'test',
              },
              features: { rag: true },
              limits: { maxPageSize: 100 },
              auth: { passwordLogin: true },
              links: { documentation: '/docs' },
            },
            meta: {
              requestId: 'req_capabilities',
              timestamp: '2026-07-20T12:00:00Z',
            },
          }),
        )
      }
      return Promise.reject(new Error(`Unexpected request: ${path}`))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders service status and reads release version from capabilities', async () => {
    renderPage()

    expect(await screen.findByText('KubeOnCall')).toBeInTheDocument()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(await screen.findByText('1.2.3')).toBeInTheDocument()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual(
      expect.arrayContaining(['/api/v1/system/status', '/api/v1/capabilities']),
    )
  })

  it('keeps migration as an explicit admin-only system action', async () => {
    renderPage([PERMISSIONS.SYSTEM_READ, PERMISSIONS.SYSTEM_MANAGE])

    expect(await screen.findByText('KubeOnCall')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开数据迁移工具' })).toHaveAttribute(
      'href',
      '/migration',
    )
  })
})
