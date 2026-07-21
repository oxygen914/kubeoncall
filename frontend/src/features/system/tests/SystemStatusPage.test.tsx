import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <SystemStatusPage />
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
})
