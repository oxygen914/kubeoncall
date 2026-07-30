import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IntegrationsPage } from '../IntegrationsPage'

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
      <MemoryRouter>
        <IntegrationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('IntegrationsPage notification delivery view', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/v1/integrations') {
        return Promise.resolve(
          jsonResponse({
            data: {
              integrations: [
                {
                  id: 'feishu',
                  name: '飞书群机器人',
                  configured: true,
                  endpoint: null,
                  timeoutMillis: null,
                  circuitState: 'NOT_OBSERVED',
                  consecutiveFailures: 0,
                  circuitOpenedAt: null,
                  targetCount: 1,
                  capabilities: ['GROUP_WEBHOOK'],
                },
              ],
              circuits: {},
            },
            meta: { requestId: 'req_integrations', timestamp: '2026-07-30T01:00:00Z' },
          }),
        )
      }
      if (path === '/api/v1/integrations/notifications?page=1&size=50') {
        return Promise.resolve(
          jsonResponse({
            data: [
              {
                id: 'ndlv_1',
                executionId: null,
                status: 'DELIVERED',
                summary: 'NodeDown',
                errorCode: null,
                errorSummary: null,
                startedAt: '2026-07-30T01:00:00Z',
                finishedAt: '2026-07-30T01:00:01Z',
                durationMs: 1000,
                attempt: 1,
                providerKey: 'feishu',
                destinationId: 'feishu-primary',
                eventType: 'alarm.firing.initial',
                operation: 'SEND',
                externalMessageId: 'om_external_1',
                retryable: false,
                replayCount: 2,
              },
            ],
            page: {
              number: 1,
              size: 50,
              totalElements: 1,
              totalPages: 1,
              hasNext: false,
            },
            meta: { requestId: 'req_deliveries', timestamp: '2026-07-30T01:00:01Z' },
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

  it('keeps webhook endpoints protected and renders durable provider facts', async () => {
    renderPage()

    expect(await screen.findByText('飞书群机器人')).toBeInTheDocument()
    expect(screen.getByText('受保护配置')).toBeInTheDocument()
    expect(await screen.findByText('om_external_1')).toBeInTheDocument()
    expect(screen.getByText('alarm.firing.initial')).toBeInTheDocument()
    expect(screen.getByText(/已重放 2 次/)).toBeInTheDocument()
  })
})
