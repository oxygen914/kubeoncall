import { getRuntimeConfig } from '@/api/runtimeConfig'

export type GrafanaDashboard = 'logs' | 'overview'

const DASHBOARDS: Record<GrafanaDashboard, { slug: string; uid: string }> = {
  logs: { uid: 'kubeoncall-logs', slug: 'kubeoncall-logs' },
  overview: { uid: 'kubeoncall-overview', slug: 'kubeoncall-overview' },
}

export function getGrafanaDashboardUrl(dashboard: GrafanaDashboard): string | null {
  const baseUrl = resolveGrafanaBaseUrl()
  if (!baseUrl) return null

  const target = DASHBOARDS[dashboard]
  const url = new URL(baseUrl)
  const prefix = url.pathname.replace(/\/+$/, '')
  url.pathname = `${prefix}/d/${target.uid}/${target.slug}`
  url.search = ''
  url.hash = ''
  return url.toString()
}

function resolveGrafanaBaseUrl(): string | null {
  const configured = getRuntimeConfig().grafanaBaseUrl.trim()
  if (configured) return validHttpUrl(configured)

  if (
    typeof window !== 'undefined' &&
    ['127.0.0.1', 'localhost'].includes(window.location.hostname)
  ) {
    return `${window.location.protocol}//${window.location.hostname}:3000`
  }
  return null
}

function validHttpUrl(value: string): string | null {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.toString() : null
  } catch {
    return null
  }
}
