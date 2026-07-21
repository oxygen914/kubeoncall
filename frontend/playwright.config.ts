import { defineConfig, devices } from '@playwright/test'

const serverUrl = 'http://127.0.0.1:5173'

/**
 * Playwright E2E config. Spins up the Vite dev server on port 5173 and runs
 * a smoke suite against it.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'html',
  use: {
    baseURL: serverUrl,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173 --strictPort',
    url: serverUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
