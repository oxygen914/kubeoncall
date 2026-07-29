import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getMonitoringScopes } from './api'
import { MonitoringScopeContext, type MonitoringScopeContextValue } from './monitoringScopeContext'

export function MonitoringScopeProvider({ children }: { children: ReactNode }) {
  const [cluster, setClusterState] = useState('')
  const [environment, setEnvironmentState] = useState('')
  const [namespace, setNamespaceState] = useState('')
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
