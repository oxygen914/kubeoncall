import { AppRouter } from './router'

/**
 * Top-level application shell.
 *
 * Providers (QueryClient, Session) are mounted in src/main.tsx via AppProviders;
 * App just renders the router + root layout outlet.
 */
export function App() {
  return <AppRouter />
}
