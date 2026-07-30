import { expect, test } from '@playwright/test'

const meta = { requestId: 'req_e2e_responsive', timestamp: '2026-07-30T00:00:00Z' }

test('mobile shell exposes an adaptive drawer and compact scope controls', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          authenticated: true,
          user: {
            id: 'usr_mobile',
            username: 'operator',
            displayName: 'Mobile Operator',
            roles: ['OPERATOR'],
            permissions: ['ask:execute'],
          },
          expiresAt: '2026-07-31T00:00:00Z',
        },
        meta,
      }),
    })
  })
  await page.route('**/api/v1/monitoring/scopes**', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          clusters: [{ value: 'prod', label: 'prod', resourceCount: 3 }],
          environments: [],
          namespaces: [],
          capabilities: {
            clusterFilterAvailable: true,
            environmentFilterAvailable: false,
            namespaceFilterAvailable: false,
          },
          collectedAt: '2026-07-30T00:00:00Z',
        },
        meta,
      }),
    })
  })

  await page.goto('/ask')
  await expect(page.getByRole('heading', { name: 'AI 诊断' })).toBeVisible()

  const sidebar = page.locator('#primary-navigation')
  await expect(sidebar).toBeHidden()
  await page.getByRole('button', { name: '打开主导航' }).click()
  await expect(sidebar).toBeVisible()
  await expect(sidebar.getByRole('link', { name: 'AI 诊断' })).toBeVisible()
  await expect(sidebar.getByRole('button', { name: '关闭主导航' })).toBeVisible()

  await sidebar.getByRole('button', { name: '关闭主导航' }).click()
  await expect(sidebar).toBeHidden()
  await expect(page.getByRole('button', { name: '切换到深色主题' })).toBeVisible()
  await page.getByRole('button', { name: '切换全局范围，当前集群 prod' }).click()
  await expect(page.locator('#mobile-scope-panel')).toBeVisible()
  await expect(page.locator('#mobile-scope-panel').getByLabel('全局集群')).toHaveValue('prod')
  await page.keyboard.press('Escape')
  await expect(page.locator('#mobile-scope-panel')).toBeHidden()
  await expect
    .poll(() =>
      page
        .getByLabel('向 KubeOnCall 提问')
        .evaluate((element) => Number.parseFloat(getComputedStyle(element).fontSize)),
    )
    .toBeGreaterThanOrEqual(16)
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)

  await page.setViewportSize({ width: 667, height: 375 })
  await expect(page.getByRole('button', { name: '打开主导航' })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)
})
