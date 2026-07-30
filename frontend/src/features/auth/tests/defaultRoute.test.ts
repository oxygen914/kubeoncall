import { describe, expect, it } from 'vitest'
import type { SessionData } from '@/api/auth'
import { PERMISSIONS, type Permission } from '../permissions'
import { getDefaultConsolePath } from '../defaultRoute'

function sessionWith(permissions: Permission[]): SessionData {
  return {
    authenticated: true,
    user: {
      id: 'usr_1',
      username: 'operator',
      displayName: 'Operator',
      roles: [],
      permissions,
    },
    expiresAt: '2026-07-31T00:00:00Z',
  }
}

describe('getDefaultConsolePath', () => {
  it('prefers the operations overview when it is available', () => {
    expect(
      getDefaultConsolePath(sessionWith([PERMISSIONS.ASK_EXECUTE, PERMISSIONS.DASHBOARD_READ])),
    ).toBe('/overview')
  })

  it('falls back to the first page the account can actually access', () => {
    expect(getDefaultConsolePath(sessionWith([PERMISSIONS.ASK_EXECUTE]))).toBe('/ask')
    expect(getDefaultConsolePath(sessionWith([PERMISSIONS.SYSTEM_MANAGE]))).toBe('/users')
  })

  it('returns null when no console destination is authorized', () => {
    expect(getDefaultConsolePath(sessionWith([]))).toBeNull()
  })
})
