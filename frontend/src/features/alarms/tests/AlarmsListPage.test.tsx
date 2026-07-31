import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlarmListParams } from '../api'
import { useAlarmList } from '../hooks'
import { AlarmsListPage } from '../AlarmsListPage'

vi.mock('../hooks', () => ({
  useAlarmList: vi.fn(),
}))

describe('AlarmsListPage', () => {
  const params: AlarmListParams[] = []

  beforeEach(() => {
    vi.useFakeTimers()
    params.length = 0
    vi.mocked(useAlarmList).mockImplementation((nextParams) => {
      params.push(nextParams)
      return {
        data: {
          data: [],
          page: { number: 1, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
        },
        isLoading: false,
        error: null,
        isFetching: false,
      } as unknown as ReturnType<typeof useAlarmList>
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('debounces keyword filtering while keeping the input responsive', () => {
    render(
      <MemoryRouter initialEntries={['/alarms']}>
        <AlarmsListPage />
      </MemoryRouter>,
    )

    const search = screen.getByRole('searchbox', { name: '搜索' })
    fireEvent.change(search, { target: { value: 'payment-api' } })
    expect(search).toHaveValue('payment-api')
    expect(params.at(-1)?.q).toBeUndefined()

    act(() => {
      vi.advanceTimersByTime(299)
    })
    expect(params.at(-1)?.q).toBeUndefined()

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(params.at(-1)?.q).toBe('payment-api')
  })
})
