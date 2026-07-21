import { createContext } from 'react'
import type { RealtimeConnectionStatus } from './types'

export interface EventContextValue {
  status: RealtimeConnectionStatus
}

export const EventContext = createContext<EventContextValue>({ status: 'idle' })
