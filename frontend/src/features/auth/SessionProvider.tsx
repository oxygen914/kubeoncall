import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import * as authApi from '@/api/auth'
import type { SessionData } from '@/api/auth'
import { ApiError } from '@/api/errors'
import { SessionContext, type SessionState } from './sessionContext'

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await authApi.getSession()
      setSession(data)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        // Expected: not logged in yet. Not an error to surface.
        setSession(null)
      } else if (err instanceof ApiError) {
        setError(err)
        setSession(null)
      } else {
        throw err
      }
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const login = useCallback(async (username: string, password: string) => {
    const data = await authApi.login({ username, password })
    setSession(data)
    return data
  }, [])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      // Even if the network call fails, drop local state so the UI locks.
      setSession(null)
    }
  }, [])

  const value = useMemo<SessionState>(
    () => ({ session, loading, error, login, logout, refresh }),
    [session, loading, error, login, logout, refresh],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
