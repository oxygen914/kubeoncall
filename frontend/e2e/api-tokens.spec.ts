import { expect, test, type Page } from '@playwright/test'

const meta = { requestId: 'req_e2e_tokens', timestamp: '2026-07-23T00:00:00Z' }

function envelope(data: unknown) {
  return { data, meta }
}

async function mockSession(page: Page, permissions: string[]) {
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
            permissions,
          },
          expiresAt: '2026-07-24T00:00:00Z',
        }),
      ),
    })
  })
}

const operatorPermissions = ['token:read-own', 'token:manage-own']

/**
 * GAP-04-01: API token management main path — create (plaintext shown once), list, revoke. Mock-backed
 * so no backend is required.
 */
test('operator creates a token, sees the plaintext once, then revokes it', async ({ page }) => {
  await mockSession(page, operatorPermissions)

  const tokens = [
    {
      id: 'tok_existing',
      name: '旧 Token',
      prefix: 'koc_old1234',
      scopes: ['alarm:read'],
      expiresAt: '2026-12-31T00:00:00Z',
      lastUsedAt: null,
      revokedAt: null,
      ownerId: 'usr_e2e',
      ownerUsername: 'operator',
      version: 1,
      createdAt: '2026-07-20T00:00:00Z',
    },
  ]

  await page.route('**/api/v1/api-tokens', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope(tokens)) })
    } else {
      // POST create — returns the plaintext exactly once.
      const created = {
        id: 'tok_new',
        name: 'CI Token',
        prefix: 'koc_newabcd',
        scopes: ['alarm:read', 'approval:read', 'execution:read'],
        expiresAt: '2026-10-21T00:00:00Z',
        ownerId: 'usr_e2e',
        version: 1,
        token: 'koc_newabcd_secret_plaintext_value',
      }
      tokens.push({ ...created, token: undefined, lastUsedAt: null, revokedAt: null, ownerUsername: 'operator', createdAt: '2026-07-23T00:00:00Z' })
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope(created)) })
    }
  })

  await page.route('**/api/v1/api-tokens/tok_new', async (route) => {
    // DELETE revoke.
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(envelope({ id: 'tok_new', revokedAt: '2026-07-23T00:00:00Z', version: 2 })),
    })
  })

  await page.goto('/tokens')
  await expect(page.getByRole('heading', { name: 'API Token' })).toBeVisible()
  await expect(page.getByText('旧 Token')).toBeVisible()

  // Create: fill the form and submit.
  await page.getByRole('button', { name: '创建 Token' }).click()
  await page.getByLabel('名称').fill('CI Token')
  await page.getByLabel('过期时间').fill('2026-10-21T00:00')
  await page.getByRole('button', { name: '创建', exact: true }).click()

  // The plaintext appears exactly once in a banner.
  await expect(page.getByText('koc_newabcd_secret_plaintext_value')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Token 已创建' })).toBeVisible()

  // Dismiss the banner; the plaintext is gone.
  await page.getByRole('button', { name: '关闭' }).click()
  await expect(page.getByText('koc_newabcd_secret_plaintext_value')).toHaveCount(0)

  // The new token now appears in the list and can be revoked.
  await expect(page.getByText('CI Token')).toBeVisible()
  await page.getByRole('row', { name: /CI Token/ }).getByRole('button', { name: '撤销' }).click()
})
