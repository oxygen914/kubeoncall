import { createContext } from 'react'
import type { SessionData } from '@/api/auth'
import type { ApiError } from '@/api/errors'

export interface SessionState {
  /** Null when not authenticated; the initial probe is reflected by `loading`. */
  session: SessionData | null
  loading: boolean
  error: ApiError | null
  /** Authenticate with username/password. Throws on failure. */
  login: (username: string, password: string) => Promise<SessionData>
  /** Clear the current session. */
  logout: () => Promise<void>
  /** Force a re-probe of /auth/session. */
  refresh: () => Promise<void>
}

export const SessionContext = createContext<SessionState | null>(null)
