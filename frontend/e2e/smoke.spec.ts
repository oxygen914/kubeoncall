import { expect, test } from '@playwright/test'

test('login page renders the form', async ({ page }) => {
  await page.goto('/login')

  await expect(page).toHaveTitle(/KubeOnCall Console/)
  await expect(page.getByRole('heading', { name: 'KubeOnCall Console' })).toBeVisible()
  await expect(page.getByLabel('用户名')).toBeVisible()
  await expect(page.getByLabel('密码')).toBeVisible()
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
})
