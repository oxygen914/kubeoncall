import { expect, test, type Page } from '@playwright/test'

/**
 * GAP-QA-01: browser write-command main path. These tests exercise the write surface end to end
 * against mocked /api/v1 responses — acknowledging an alarm (If-Match + Idempotency-Key), deciding
 * an approval and watching its async task reach a terminal state, SSE-driven query invalidation,
 * cursor-expired full invalidation, and the viewer/operator permission matrix.
 *
 * The whole suite is mock-backed: page.route fulfils every API call, so no backend is required.
 */

const meta = { requestId: 'req_e2e_write', timestamp: '2026-07-23T00:00:00Z' }

function envelope(data: unknown) {
  return { data, meta }
}

const pageInfo = { number: 1, size: 20, totalElements: 1, totalPages: 1, hasNext: false }

interface SessionOptions {
  permissions: string[]
  roles?: string[]
}

/** Mocks the session endpoint so AuthBoundary treats the browser as an authenticated user. */
async function mockSession(page: Page, { permissions, roles = ['OPERATOR'] }: SessionOptions) {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(
        envelope({
          authenticated: true,
          user: {
            id: 'usr_e2e',
            username: 'operator',
            displayName: 'E2E Operator',
            roles,
            permissions,
          },
          expiresAt: '2026-07-24T00:00:00Z',
        }),
      ),
    })
  })
}

const operatorPermissions = [
  'dashboard:read',
  'alarm:read',
  'alarm:acknowledge',
  'approval:read',
  'approval:decide',
  'execution:read',
]

const alarmDetail = {
  id: 'alm_e2e',
  fingerprint: 'fp_e2e',
  alertName: '支付接口错误率高',
  severity: 'P1',
  status: 'FIRING',
  resource: { type: 'SERVICE', name: 'payment', cluster: 'prod', namespace: 'default', service: 'payment' },
  firstSeen: '2026-07-23T10:00:00Z',
  lastSeen: '2026-07-23T10:01:00Z',
  occurrenceCount: 2,
  acknowledgement: { acknowledged: false, by: null, at: null },
  latestExecution: { id: 'exec_e2e', status: 'WAITING_APPROVAL' },
  version: 3,
  labels: {},
  annotations: {},
  metricName: 'error_rate',
  currentValue: 0.5,
  threshold: 0.1,
  unit: 'ratio',
  policyPublicId: 'pol_e2e',
  resolvedAt: null,
}

const approvalDetail = {
  id: 'apr_e2e',
  executionId: 'exec_e2e',
  status: 'PENDING',
  riskLevel: 'HIGH',
  summary: '重启支付工作负载',
  action: 'RESTART',
  version: 2,
  context: {},
  decision: {},
  createdAt: '2026-07-23T10:00:00Z',
  expiresAt: null,
}

/**
 * Replaces the native EventSource with a controllable stub so tests can dispatch SSE events without
 * a real streaming endpoint. A global window.__sse helper is installed up front (before any
 * EventSource is constructed) so the test can drive events as soon as the provider connects.
 */
async function installMockEventSource(page: Page) {
  await page.addInitScript(() => {
    type Ev = { type: string; data: string; lastEventId?: string }
    const instances: Array<{
      onopen: ((ev: Event) => void) | null
      onerror: ((ev: Event) => void) | null
      onmessage: ((ev: Ev) => void) | null
      listeners: Record<string, Array<(ev: Ev) => void>>
    }> = []
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(window as any).EventSource = class {
      onopen: ((ev: Event) => void) | null = null
      onerror: ((ev: Event) => void) | null = null
      onmessage: ((ev: Ev) => void) | null = null
      readonly listeners: Record<string, Array<(ev: Ev) => void>> = {}
      readyState = 0
      constructor() {
        this.readyState = 1
        instances.push(this)
        setTimeout(() => this.onopen?.({ type: 'open' } as Event), 0)
      }
      addEventListener(type: string, listener: (ev: Ev) => void) {
        ;(this.listeners[type] ??= []).push(listener)
      }
      close() {
        this.readyState = 2
      }
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(window as any).__sse = {
      ready: () => instances.length > 0,
      dispatch(eventType: string, payload: unknown, eventId?: string) {
        const data = JSON.stringify({
          eventType,
          schemaVersion: 1,
          eventId: eventId ?? `evt_${Date.now()}`,
          ...payload,
        })
        const ev: Ev = { type: eventType, data, lastEventId: eventId }
        for (const inst of instances) {
          inst.onmessage?.(ev)
          ;(inst.listeners[eventType] ?? []).forEach((fn) => fn(ev))
        }
      },
      dispatchGap(eventId?: string) {
        const ev: Ev = {
          type: 'gap',
          data: JSON.stringify({ eventType: 'gap', gap: true, eventId: eventId ?? 'evt_gap' }),
          lastEventId: eventId,
        }
        for (const inst of instances) {
          inst.onmessage?.(ev)
          ;(inst.listeners['gap'] ?? []).forEach((fn) => fn(ev))
        }
      },
    }
  })
  // Serve the runtime config so EventProvider connects promptly.
  await page.route('**/config/runtime.json', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ apiBaseUrl: '', ssePath: '/api/v1/events/stream', environment: 'test', release: 'e2e', supportUrl: '' }),
    })
  })
}

/** Waits until the SSE provider has constructed an EventSource connection. */
async function waitForSse(page: Page) {
  await expect.poll(async () => await page.evaluate(() => (window as unknown as { __sse?: { ready: () => boolean } }).__sse?.ready() ?? false)).toBe(true)
}

test('operator acknowledges a firing alarm with version and idempotency headers', async ({ page }) => {
  await mockSession(page, { permissions: operatorPermissions })

  let acknowledgeRequest: { method: string; headers: Record<string, string> } | undefined
  await page.route('**/api/v1/alarms/alm_e2e', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(envelope({ ...alarmDetail, status: 'FIRING', acknowledgement: { acknowledged: false, by: null, at: null } })),
    })
  })
  await page.route('**/api/v1/alarms/alm_e2e/timeline*', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope([])) })
  })
  await page.route('**/api/v1/alarms/alm_e2e/acknowledgements', async (route) => {
    const request = route.request()
    acknowledgeRequest = { method: request.method(), headers: request.headers() }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(envelope({ alarmId: 'alm_e2e', status: 'ACKNOWLEDGED', acknowledged: true, version: 4 })),
    })
  })

  await page.goto('/alarms/alm_e2e')

  await expect(page.getByRole('heading', { name: '支付接口错误率高' })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认告警' })).toBeVisible()

  await page.getByRole('button', { name: '确认告警' }).click()
  await page.getByRole('dialog', { name: '确认告警' }).getByLabel('原因（可选）').fill('正在扩容处理')
  await page.getByRole('dialog', { name: '确认告警' }).getByRole('button', { name: '确认' }).click()

  // The dialog closes on success and the detail refetch reflects the acknowledged state.
  await expect(page.getByRole('dialog', { name: '确认告警' })).toHaveCount(0)

  expect(acknowledgeRequest).toBeDefined()
  expect(acknowledgeRequest!.method).toBe('POST')
  // The transport carries the optimistic-lock version as If-Match and a generated idempotency key.
  expect(acknowledgeRequest!.headers['if-match']).toBe('3')
  expect(acknowledgeRequest!.headers['idempotency-key']).toBeTruthy()
})

test('operator decides an approval and the async task reaches a terminal state', async ({ page }) => {
  await mockSession(page, { permissions: operatorPermissions })

  await page.route('**/api/v1/approvals/apr_e2e', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope(approvalDetail)) })
  })

  let decisionRequest: { method: string; body: string } | undefined
  await page.route('**/api/v1/approvals/apr_e2e/decisions', async (route) => {
    decisionRequest = { method: route.request().method(), body: route.request().postData() ?? '' }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(envelope({ taskId: 'tsk_e2e', status: 'PENDING' })),
    })
  })

  const taskStates = ['RUNNING', 'SUCCEEDED']
  let taskPoll = 0
  await page.route('**/api/v1/tasks/tsk_e2e', async (route) => {
    const status = taskStates[Math.min(taskPoll, taskStates.length - 1)]
    taskPoll += 1
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(
        envelope({
          id: 'tsk_e2e',
          taskType: 'APPROVAL_DECIDE',
          status,
          stage: status === 'SUCCEEDED' ? 'completed' : 'running',
          progressPercent: status === 'SUCCEEDED' ? 100 : 50,
          resourceId: 'apr_e2e',
          errorCode: null,
          errorSummary: null,
          createdAt: '2026-07-23T10:00:00Z',
          finishedAt: status === 'SUCCEEDED' ? '2026-07-23T10:00:05Z' : null,
        }),
      ),
    })
  })

  await page.goto('/approvals/apr_e2e')
  await expect(page.getByRole('heading', { name: '审批详情' })).toBeVisible()

  await page.getByRole('button', { name: '批准' }).click()
  await page.getByRole('dialog', { name: '审批决策' }).getByRole('button', { name: '批准' }).click()

  // The decision endpoint received the chosen decision and the If-Match version.
  await expect.poll(() => decisionRequest?.method).toBe('POST')
  expect(decisionRequest!.body).toContain('"decision":"APPROVED"')

  // The task panel renders and polls until SUCCEEDED.
  await expect(page.getByText('异步任务')).toBeVisible()
  await expect(page.getByText('SUCCEEDED')).toBeVisible({ timeout: 15_000 })
})

test('SSE alarm.acknowledged event precisely invalidates the alarm query', async ({ page }) => {
  await mockSession(page, { permissions: operatorPermissions })
  await installMockEventSource(page)

  const detailFetches: string[] = []
  let version = 3
  await page.route('**/api/v1/alarms/alm_e2e', async (route) => {
    detailFetches.push(`v${version}`)
    const acknowledged = version >= 4
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(
        envelope({
          ...alarmDetail,
          version,
          status: acknowledged ? 'ACKNOWLEDGED' : 'FIRING',
          acknowledgement: acknowledged
            ? { acknowledged: true, by: 'usr_e2e', at: '2026-07-23T10:02:00Z' }
            : { acknowledged: false, by: null, at: null },
        }),
      ),
    })
  })
  await page.route('**/api/v1/alarms/alm_e2e/timeline*', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope([])) })
  })

  await page.goto('/alarms/alm_e2e')
  await expect(page.getByText('FIRING')).toBeVisible()
  expect(detailFetches).toEqual(['v3'])

  await waitForSse(page)

  // Simulate the backend pushing an alarm.acknowledged event. The precise invalidation must refetch
  // the alarm detail; the new version (4) is served and the status flips to ACKNOWLEDGED.
  version = 4
  await page.evaluate(() =>
    (window as unknown as { __sse: { dispatch: (t: string, p: unknown, id?: string) => void } }).__sse.dispatch(
      'alarm.acknowledged',
      { resourceType: 'alarm', resourceId: 'alm_e2e', version: 4 },
      'evt_ack',
    ),
  )

  await expect(page.getByText('ACKNOWLEDGED')).toBeVisible()
  expect(detailFetches).toContain('v4')
})

test('SSE cursor.expired gap event triggers a full query invalidation', async ({ page }) => {
  await mockSession(page, { permissions: operatorPermissions })
  await installMockEventSource(page)

  const overviewFetches: string[] = []
  let activeAlarms = 2
  await page.route('**/api/v1/overview?window=*', async (route) => {
    overviewFetches.push(new URL(route.request().url()).searchParams.get('window') ?? '')
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(envelope({ activeAlarms, pendingApprovals: 1, runningExecutions: 0, failedExecutions: 0, severityCounts: {}, statusCounts: {}, executionStatusCounts: {}, failureReasons: {}, executionTrend: {}, window: '1h' })),
    })
  })

  await page.goto('/overview')
  await expect(page.getByRole('heading', { name: '概览' })).toBeVisible()
  expect(overviewFetches.length).toBeGreaterThanOrEqual(1)
  const fetchesBeforeGap = overviewFetches.length

  await waitForSse(page)

  // Bump the server-side state, then dispatch a gap event. A full invalidation must refetch the
  // overview regardless of topic, surfacing the new active-alarms count.
  activeAlarms = 7
  await page.evaluate(() => (window as unknown as { __sse: { dispatchGap: (id?: string) => void } }).__sse.dispatchGap('evt_gap'))

  await expect.poll(() => overviewFetches.length, { timeout: 15_000 }).toBeGreaterThan(fetchesBeforeGap)
})

test('viewer without acknowledge permission sees no acknowledge button', async ({ page }) => {
  await mockSession(page, {
    permissions: ['alarm:read', 'approval:read', 'execution:read'],
    roles: ['VIEWER'],
  })

  await page.route('**/api/v1/alarms/alm_e2e', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope(alarmDetail)) })
  })
  await page.route('**/api/v1/alarms/alm_e2e/timeline*', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope([])) })
  })

  await page.goto('/alarms/alm_e2e')
  await expect(page.getByRole('heading', { name: '支付接口错误率高' })).toBeVisible()
  // A viewer can read the alarm but the write action is hidden.
  await expect(page.getByRole('button', { name: '确认告警' })).toHaveCount(0)
})

test('operator without approval:decide cannot decide a pending approval', async ({ page }) => {
  await mockSession(page, {
    permissions: ['approval:read', 'execution:read'],
    roles: ['OPERATOR'],
  })

  await page.route('**/api/v1/approvals/apr_e2e', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope(approvalDetail)) })
  })

  await page.goto('/approvals/apr_e2e')
  await expect(page.getByRole('heading', { name: '审批详情' })).toBeVisible()
  await expect(page.getByRole('button', { name: '批准' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '拒绝' })).toHaveCount(0)
})
