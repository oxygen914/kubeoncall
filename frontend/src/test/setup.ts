import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

// Auto-cleanup RTL between tests.
afterEach(() => {
  cleanup()
})

// jsdom doesn't implement matchMedia; some libraries query it at import time.
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

// Provide crypto.randomUUID if missing in the test environment.
if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function') {
  Object.defineProperty(crypto, 'randomUUID', {
    value: () =>
      '00000000-0000-4000-8000-000000000000'.replace(/0/g, () =>
        Math.floor(Math.random() * 16).toString(16),
      ),
  })
}

// Silence unhandled rejection noise from intentionally-failed queries in tests.
void vi
