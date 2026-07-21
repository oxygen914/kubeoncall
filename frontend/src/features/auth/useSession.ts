import { useContext } from 'react'
import { SessionContext, type SessionState } from './sessionContext'

export function useSession(): SessionState {
  const ctx = useContext(SessionContext)
  if (!ctx) {
    throw new Error('useSession must be used within a SessionProvider')
  }
  return ctx
}
