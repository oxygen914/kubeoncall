import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getMonitoringScopes } from './api'
import { MonitoringScopeContext, type MonitoringScopeContextValue } from './monitoringScopeContext'

export const MONITORING_SCOPE_STORAGE_KEY = 'kubeoncall.monitoring.scope.v1'

export function MonitoringScopeProvider({ children }: { children: ReactNode }) {
  const restored = useMemo(loadMonitoringScope, [])
  const [cluster, setClusterState] = useState(restored.cluster)
  const [environment, setEnvironmentState] = useState(restored.environment)
  const [namespace, setNamespaceState] = useState(restored.namespace)
  const scopesQuery = useQuery({
    queryKey: ['monitoring', 'scopes', cluster, environment],
    queryFn: () => getMonitoringScopes({ cluster, environment }),
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
  const catalog = scopesQuery.data

  useEffect(() => {
    const first = catalog?.clusters[0]?.value
    if (!cluster && first) setClusterState(first)
    if (cluster && catalog && !catalog.clusters.some((item) => item.value === cluster)) {
      setClusterState(first ?? '')
    }
  }, [catalog, cluster])

  useEffect(() => {
    if (
      environment &&
      catalog &&
      !catalog.environments.some((item) => item.value === environment)
    ) {
      setEnvironmentState('')
    }
  }, [catalog, environment])

  useEffect(() => {
    if (namespace && catalog && !catalog.namespaces.some((item) => item.value === namespace)) {
      setNamespaceState('')
    }
  }, [catalog, namespace])

  useEffect(() => {
    try {
      window.localStorage.setItem(
        MONITORING_SCOPE_STORAGE_KEY,
        JSON.stringify({ cluster, environment, namespace }),
      )
    } catch {
      // Storage can be disabled by the browser; the current in-memory scope remains authoritative.
    }
  }, [cluster, environment, namespace])

  const value = useMemo<MonitoringScopeContextValue>(
    () => ({
      scope: {
        cluster,
        environment: environment || undefined,
        namespace: namespace || undefined,
      },
      catalog,
      isLoading: scopesQuery.isLoading,
      error: scopesQuery.error,
      setCluster: (value) => {
        setClusterState(value)
        setEnvironmentState('')
        setNamespaceState('')
      },
      setEnvironment: (value) => {
        setEnvironmentState(value)
        setNamespaceState('')
      },
      setNamespace: setNamespaceState,
    }),
    [catalog, cluster, environment, namespace, scopesQuery.error, scopesQuery.isLoading],
  )

  return <MonitoringScopeContext.Provider value={value}>{children}</MonitoringScopeContext.Provider>
}

function loadMonitoringScope(): { cluster: string; environment: string; namespace: string } {
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(MONITORING_SCOPE_STORAGE_KEY) ?? '{}',
    ) as Record<string, unknown>
    return {
      cluster: boundedText(parsed.cluster, 128),
      environment: boundedText(parsed.environment, 128),
      namespace: boundedText(parsed.namespace, 253),
    }
  } catch {
    return { cluster: '', environment: '', namespace: '' }
  }
}

function boundedText(value: unknown, maxLength: number): string {
  if (typeof value !== 'string') return ''
  const normalized = value.trim()
  return normalized.length <= maxLength ? normalized : ''
}
