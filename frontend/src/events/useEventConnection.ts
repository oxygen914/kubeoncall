import { useContext } from 'react'
import { EventContext } from './eventContext'

export function useEventConnection() {
  return useContext(EventContext)
}
