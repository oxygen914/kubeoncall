import { createContext, useContext } from 'react'
import type { MonitoringScope, MonitoringScopeCatalog } from './api'

export interface MonitoringScopeContextValue {
  scope: MonitoringScope
  catalog: MonitoringScopeCatalog | undefined
  isLoading: boolean
  error: Error | null
  setCluster: (value: string) => void
  setEnvironment: (value: string) => void
  setNamespace: (value: string) => void
}

export const MonitoringScopeContext = createContext<MonitoringScopeContextValue | null>(null)

export function useMonitoringScope(): MonitoringScopeContextValue {
  const value = useContext(MonitoringScopeContext)
  if (!value) {
    throw new Error('useMonitoringScope must be used inside MonitoringScopeProvider')
  }
  return value
}
