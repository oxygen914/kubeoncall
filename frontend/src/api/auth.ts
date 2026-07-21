import { api } from './client'
import { readCookie, CSRF_COOKIE_NAME } from './client'

/**
 * Auth API module. Matches the OpenAPI SessionResponse shape:
 *
 *   data: {
 *     authenticated: boolean,
 *     user: { id, username, displayName, roles[], permissions[] },
 *     expiresAt: string,
 *     idleExpiresAt?: string
 *   }
 */

export interface UserSession {
  id: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export interface SessionData {
  authenticated: boolean
  user: UserSession
  expiresAt: string
  idleExpiresAt?: string
}

export interface LoginInput {
  username: string
  password: string
}

/** POST /api/v1/auth/login — sets the KOC_SESSION cookie and KOC_CSRF cookie. */
export async function login(input: LoginInput): Promise<SessionData> {
  const data = await api.post<SessionData>('/api/v1/auth/login', input)
  // After a successful login the server sets a readable KOC_CSRF cookie.
  // Touch it here so callers/tests can assert it was read at least once.
  void readCookie(CSRF_COOKIE_NAME)
  return data
}

/** POST /api/v1/auth/logout — clears the session. Returns void (204). */
export async function logout(): Promise<void> {
  await api.post<void>('/api/v1/auth/logout')
}

/** GET /api/v1/auth/session — current session, or 401 if unauthenticated. */
export async function getSession(): Promise<SessionData> {
  return api.get<SessionData>('/api/v1/auth/session')
}
