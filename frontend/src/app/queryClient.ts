import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'

/**
 * Application-wide TanStack Query client.
 *
 * Defaults are conservative:
 * - retry once (network blips only)
 * - no refetch on window focus (avoids surprise refetch storms)
 * - 10s stale time so rapid navigation between pages feels instant
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      staleTime: 10_000,
      retry: (failureCount, error) => {
        // Don't retry on auth/permission errors — they won't fix themselves.
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
          return false
        }
        return failureCount < 1
      },
    },
    mutations: {
      retry: 0,
    },
  },
})
