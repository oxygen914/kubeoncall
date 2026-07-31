import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlarmDetail } from '../api'
import { SessionContext, type SessionState } from '@/features/auth/sessionContext'
import { PERMISSIONS, type Permission } from '@/features/auth/permissions'

const { useAlarmMock, useAlarmTimelineMock } = vi.hoisted(() => ({
  useAlarmMock: vi.fn(),
  useAlarmTimelineMock: vi.fn(),
}))

vi.mock('../hooks', () => ({
  useAlarm: (alarmId: string) => useAlarmMock(alarmId),
  useAlarmTimeline: (alarmId: string) => useAlarmTimelineMock(alarmId),
  useAcknowledgeAlarm: vi.fn(),
  useConfirmAlarmRecovery: vi.fn(),
  useApproveAlarmSilence: vi.fn(),
}))

import { AlarmDetailPage } from '../AlarmDetailPage'

const baseAlarm: AlarmDetail = {
  id: 'alm_1',
  fingerprint: 'fingerprint-1',
  alertName: 'NodeDown',
  severity: 'P1',
  status: 'FIRING',
  resource: {
    type: 'NODE',
    name: 'worker-1',
    cluster: 'prod',
    namespace: null,
    service: null,
  },
  firstSeen: '2026-07-20T10:00:00Z',
  lastSeen: '2026-07-20T11:00:00Z',
  occurrenceCount: 2,
  acknowledgement: { acknowledged: false, by: null, at: null },
  latestExecution: null,
  version: 7,
  labels: {},
  annotations: {},
  metricName: null,
  currentValue: null,
  threshold: null,
  unit: null,
  policyPublicId: null,
  resolvedAt: null,
}

const COMMAND_PERMISSIONS: Permission[] = [
  PERMISSIONS.ALARM_ACKNOWLEDGE,
  PERMISSIONS.ALARM_RECOVER,
  PERMISSIONS.ALARM_SILENCE,
]

function renderDetail(alarm: AlarmDetail, permissions: Permission[] = COMMAND_PERMISSIONS) {
  useAlarmMock.mockReturnValue({ data: alarm, isLoading: false, error: null })
  useAlarmTimelineMock.mockReturnValue({ data: [], isLoading: false, error: null })
  const sessionState: SessionState = {
    session: {
      authenticated: true,
      user: {
        id: 'usr_viewer',
        username: 'viewer',
        displayName: 'Viewer',
        roles: ['VIEWER'],
        permissions,
      },
      expiresAt: '2026-07-21T00:00:00Z',
    },
    loading: false,
    error: null,
    login: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
  }

  return render(
    <SessionContext.Provider value={sessionState}>
      <MemoryRouter initialEntries={[`/alarms/${alarm.id}`]}>
        <Routes>
          <Route path="/alarms/:alarmId" element={<AlarmDetailPage />} />
        </Routes>
      </MemoryRouter>
    </SessionContext.Provider>,
  )
}

describe('AlarmDetailPage command visibility', () => {
  beforeEach(() => {
    useAlarmMock.mockReset()
    useAlarmTimelineMock.mockReset()
  })

  it('keeps acknowledgement and silence actions available for a firing alarm', () => {
    renderDetail(baseAlarm)

    expect(screen.getByRole('button', { name: '确认告警' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '审批静默' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认恢复' })).not.toBeInTheDocument()
  })

  it('only offers recovery confirmation while recovery is pending', () => {
    renderDetail({ ...baseAlarm, status: 'RECOVERY_PENDING' })

    expect(screen.getByRole('button', { name: '确认恢复' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认告警' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '审批静默' })).not.toBeInTheDocument()
  })

  it('offers silence without acknowledgement after the alarm is acknowledged', () => {
    renderDetail({
      ...baseAlarm,
      status: 'ACKNOWLEDGED',
      acknowledgement: {
        acknowledged: true,
        by: 'Alice',
        at: '2026-07-20T11:30:00Z',
      },
    })

    expect(screen.getByRole('button', { name: '审批静默' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认告警' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认恢复' })).not.toBeInTheDocument()
  })

  it('hides acknowledgement and silence actions from a read-only viewer', () => {
    renderDetail(baseAlarm, [PERMISSIONS.ALARM_READ])

    expect(screen.queryByRole('button', { name: '确认告警' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '审批静默' })).not.toBeInTheDocument()
  })

  it('hides recovery confirmation from a read-only viewer', () => {
    renderDetail({ ...baseAlarm, status: 'RECOVERY_PENDING' }, [PERMISSIONS.ALARM_READ])

    expect(screen.queryByRole('button', { name: '确认恢复' })).not.toBeInTheDocument()
  })

  it('links the latest execution for users allowed to read executions', () => {
    renderDetail(
      {
        ...baseAlarm,
        latestExecution: { id: 'exec_1', status: 'RUNNING' },
      },
      [...COMMAND_PERMISSIONS, PERMISSIONS.EXECUTION_READ],
    )

    expect(screen.getByRole('link', { name: 'exec_1 · RUNNING' })).toHaveAttribute(
      'href',
      '/executions/exec_1',
    )
  })
})
