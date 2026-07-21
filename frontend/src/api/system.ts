import { api } from './client'

/**
 * System & capabilities API module.
 *
 * `GET /api/v1/system/status` — lightweight service name/status.
 * `GET /api/v1/capabilities` — deployment release and capability groups.
 */

export interface SystemStatus {
  service: string
  status: string
}

export interface ReleaseInfo {
  version?: string
  commit?: string
  environment?: string
  [key: string]: unknown
}

export interface Capabilities {
  release?: ReleaseInfo
  features?: Record<string, unknown>
  limits?: Record<string, unknown>
  auth?: Record<string, unknown>
  links?: Record<string, unknown>
  [key: string]: unknown
}

export async function getSystemStatus(): Promise<SystemStatus> {
  return api.get<SystemStatus>('/api/v1/system/status')
}

export async function getCapabilities(): Promise<Capabilities> {
  return api.get<Capabilities>('/api/v1/capabilities')
}
