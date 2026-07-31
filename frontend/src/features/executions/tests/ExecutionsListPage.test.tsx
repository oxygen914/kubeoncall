import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

const { useExecutionListMock } = vi.hoisted(() => ({
  useExecutionListMock: vi.fn(),
}))

vi.mock('../hooks', () => ({
  useExecutionList: (params: unknown) => {
    useExecutionListMock(params)
    return {
      data: {
        data: [
          {
            id: 'exec_1',
            type: 'ALARM_DIAGNOSIS',
            status: 'FAILED',
            summary: '诊断 payment-api',
            triggerId: 'alm_1',
            startedAt: '2026-07-30T04:00:00Z',
            finishedAt: '2026-07-30T04:01:00Z',
            durationMs: 60_000,
            version: 1,
          },
        ],
        page: {
          number: 2,
          size: 20,
          totalElements: 21,
          totalPages: 2,
          hasNext: false,
        },
      },
      isLoading: false,
      error: null,
      isFetching: false,
    }
  },
}))

import { ExecutionsListPage } from '../ExecutionsListPage'

describe('ExecutionsListPage URL state', () => {
  it('reads filters from the deep link and carries the exact URL into detail navigation', () => {
    render(
      <MemoryRouter initialEntries={['/executions?status=FAILED&page=2&alarmId=alm_1']}>
        <Routes>
          <Route path="/executions" element={<ExecutionsListPage />} />
          <Route path="/executions/:executionId" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(useExecutionListMock).toHaveBeenLastCalledWith({
      page: 2,
      size: 20,
      status: 'FAILED',
      alarmId: 'alm_1',
    })

    fireEvent.click(screen.getByText('诊断 payment-api'))
    expect(screen.getByText('/executions/exec_1')).toBeInTheDocument()
    expect(screen.getByText('/executions?status=FAILED&page=2&alarmId=alm_1')).toBeInTheDocument()
  })
})

function LocationProbe() {
  const location = useLocation()
  const returnTo =
    location.state && typeof location.state === 'object'
      ? (location.state as { returnTo?: string }).returnTo
      : undefined
  return (
    <>
      <div>{location.pathname}</div>
      <div>{returnTo}</div>
    </>
  )
}
