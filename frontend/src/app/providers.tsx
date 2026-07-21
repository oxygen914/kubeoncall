import type { ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from './queryClient'
import { SessionProvider } from '@/features/auth/SessionProvider'
import { EventProvider } from '@/events/EventProvider'

/**
 * Composes all top-level application providers in the correct order:
 *
 * QueryClientProvider  -> data fetching / caching
 * SessionProvider      -> current authenticated session (consumes QueryClient)
 * EventProvider        -> SSE invalidation hints (consumes Session + QueryClient)
 *
 * Router is mounted in src/main.tsx so providers are router-agnostic.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <EventProvider>{children}</EventProvider>
      </SessionProvider>
    </QueryClientProvider>
  )
}
