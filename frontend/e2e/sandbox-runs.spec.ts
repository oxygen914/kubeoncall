import { expect, test } from '@playwright/test'

const meta = { requestId: 'req_e2e_sandbox', timestamp: '2026-07-27T00:00:00Z' }
const run = {
  id: 'sbx_e2e',
  executionId: 'exe_e2e',
  alarmId: 'alm_e2e',
  mode: 'FIXED_DIAGNOSTIC',
  toolId: 'log-pattern-analysis',
  toolVersion: 'v1',
  status: 'RUNNING',
  cleanupStatus: 'PENDING',
  stage: 'collecting',
  progress: 60,
  riskLevel: 'LOW',
  approvalRequired: false,
  errorCode: null,
  errorSummary: null,
  version: 4,
  createdAt: '2026-07-27T00:00:00Z',
  startedAt: '2026-07-27T00:00:10Z',
  finishedAt: null,
}

test('authorized operator inspects and cancels a sandbox run through versioned Console API', async ({
  page,
}) => {
  await page.route('**/api/v1/auth/session', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          authenticated: true,
          user: {
            id: 'usr_e2e',
            username: 'operator',
            displayName: 'E2E Operator',
            roles: ['OPERATOR'],
            permissions: ['sandbox:read', 'sandbox:cancel', 'execution:read', 'alarm:read'],
          },
          expiresAt: '2026-07-28T00:00:00Z',
        },
        meta,
      }),
    }),
  )
  await page.route('**/api/v1/sandbox-runs', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data: [run], meta }),
    }),
  )
  await page.route('**/api/v1/sandbox-runs/sbx_e2e', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data: run, meta }),
    }),
  )
  await page.route('**/api/v1/sandbox-runs/sbx_e2e/artifacts', async (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'sba_e2e',
            type: 'REPORT',
            contentType: 'application/json',
            sizeBytes: 42,
            sha256: 'a'.repeat(64),
            classification: 'UNTRUSTED',
            retentionUntil: '2026-07-28T00:00:00Z',
            createdAt: '2026-07-27T00:00:00Z',
          },
        ],
        meta,
      }),
    }),
  )
  await page.route('**/api/v1/sandbox-runs/sbx_e2e/cancel', async (route) => {
    expect(route.request().headers()['if-match']).toBe('4')
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data: { ...run, status: 'CANCELLED', version: 5 }, meta }),
    })
  })

  await page.goto('/sandbox-runs')
  await expect(page.getByRole('heading', { name: 'Sandbox 运行' })).toBeVisible()
  await page.getByText('sbx_e2e').click()
  await expect(page.getByRole('heading', { name: 'Sandbox Run 详情' })).toBeVisible()
  await expect(page.getByText('sba_e2e')).not.toBeVisible()
  await expect(page.getByText('REPORT')).toBeVisible()

  page.once('dialog', (dialog) => dialog.accept())
  const cancelRequest = page.waitForRequest('**/api/v1/sandbox-runs/sbx_e2e/cancel')
  await page.getByRole('button', { name: '取消运行' }).click()
  await cancelRequest
})
