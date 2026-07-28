import { expect, test } from '@playwright/test'

const meta = { requestId: 'req_e2e_console', timestamp: '2026-07-23T00:00:00Z' }

function envelope(data: unknown) {
  return { data, meta }
}

test('authenticated operator can inspect the overview window and drill into executions', async ({
  page,
}) => {
  const overviewWindows: string[] = []

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
            roles: ['OPERATOR'],
            permissions: ['dashboard:read', 'alarm:read', 'approval:read', 'execution:read'],
          },
          expiresAt: '2026-07-24T00:00:00Z',
        }),
      ),
    })
  })

  await page.route('**/api/v1/overview?window=*', async (route) => {
    overviewWindows.push(new URL(route.request().url()).searchParams.get('window') ?? '')
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(
        envelope({
          activeAlarms: 2,
          pendingApprovals: 1,
          runningExecutions: 3,
          failedExecutions: 4,
          severityCounts: { P1: 1, P2: 1 },
          statusCounts: { FIRING: 2 },
          executionStatusCounts: { RUNNING: 3, FAILED: 4 },
          failureReasons: { TOOL_TIMEOUT: 3, UNCLASSIFIED: 1 },
          executionTrend: { '2026-07-23 09:00': 2, '2026-07-23 10:00': 5 },
          window: new URL(route.request().url()).searchParams.get('window'),
        }),
      ),
    })
  })

  await page.route('**/api/v1/executions?*', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'exec_e2e',
            type: 'REMEDIATION',
            status: 'FAILED',
            summary: '支付接口故障恢复',
            triggerId: 'alarm_e2e',
            startedAt: '2026-07-23T10:00:00Z',
            finishedAt: '2026-07-23T10:00:04Z',
            durationMs: 4000,
            version: 1,
          },
        ],
        page: { number: 1, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
        meta,
      }),
    })
  })

  await page.goto('/overview')

  await expect(page.getByRole('heading', { name: '概览' })).toBeVisible()
  await expect(page.getByRole('button', { name: /失败执行/ })).toContainText('4')
  await expect(page.getByRole('heading', { name: '失败原因 Top 5' })).toBeVisible()
  await expect(page.getByText('TOOL_TIMEOUT')).toBeVisible()

  await page.getByLabel('时间窗口').selectOption('6h')
  await expect.poll(() => overviewWindows).toContain('6h')

  await page.getByRole('button', { name: /失败执行/ }).click()
  await expect(page).toHaveURL(/\/executions$/)
  await expect(page.getByRole('heading', { name: '执行' })).toBeVisible()
  await expect(page.getByText('支付接口故障恢复')).toBeVisible()
})

test('authenticated operator can traverse alarm, approval and execution worklists', async ({
  page,
}) => {
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
            roles: ['OPERATOR'],
            permissions: ['alarm:read', 'approval:read', 'execution:read'],
          },
          expiresAt: '2026-07-24T00:00:00Z',
        }),
      ),
    })
  })
  const pageInfo = { number: 1, size: 20, totalElements: 1, totalPages: 1, hasNext: false }
  await page.route('**/api/v1/alarms?*', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'alm_e2e',
            fingerprint: 'fp',
            alertName: '支付接口错误率高',
            severity: 'P1',
            status: 'FIRING',
            resource: {
              type: 'SERVICE',
              name: 'payment',
              cluster: 'prod',
              namespace: 'default',
              service: 'payment',
            },
            firstSeen: '2026-07-23T10:00:00Z',
            lastSeen: '2026-07-23T10:01:00Z',
            occurrenceCount: 2,
            acknowledgement: { acknowledged: false, by: null, at: null },
            latestExecution: { id: 'exec_e2e', status: 'WAITING_APPROVAL' },
            version: 1,
          },
        ],
        page: pageInfo,
        meta,
      }),
    }),
  )
  await page.route('**/api/v1/approvals?*', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'apr_e2e',
            executionId: 'exec_e2e',
            status: 'PENDING',
            riskLevel: 'HIGH',
            summary: '重启支付工作负载',
            createdAt: '2026-07-23T10:00:00Z',
            expiresAt: null,
            version: 1,
          },
        ],
        page: pageInfo,
        meta,
      }),
    }),
  )
  await page.route('**/api/v1/executions?*', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'exec_e2e',
            type: 'REMEDIATION',
            status: 'WAITING_APPROVAL',
            summary: '支付故障处置',
            triggerId: 'alm_e2e',
            startedAt: '2026-07-23T10:00:00Z',
            finishedAt: null,
            durationMs: null,
            version: 1,
          },
        ],
        page: pageInfo,
        meta,
      }),
    }),
  )

  await page.goto('/alarms')
  await expect(page.getByText('支付接口错误率高')).toBeVisible()
  await page.goto('/approvals')
  await expect(page.getByText('重启支付工作负载')).toBeVisible()
  await page.goto('/executions')
  await expect(page.getByText('支付故障处置')).toBeVisible()
})
