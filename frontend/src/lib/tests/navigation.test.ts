import { describe, expect, it } from 'vitest'
import {
  listReturnState,
  mergeSearchParams,
  readPageParam,
  resolveListReturnPath,
} from '../navigation'

describe('list navigation helpers', () => {
  it('normalizes invalid page numbers', () => {
    expect(readPageParam(null)).toBe(1)
    expect(readPageParam('0')).toBe(1)
    expect(readPageParam('-2')).toBe(1)
    expect(readPageParam('3')).toBe(3)
  })

  it('merges filters without mutating the current URL search params', () => {
    const current = new URLSearchParams('status=FAILED&page=3')
    const next = mergeSearchParams(current, { status: 'RUNNING', page: null, q: 'pod' })

    expect(current.toString()).toBe('status=FAILED&page=3')
    expect(next.toString()).toBe('status=RUNNING&q=pod')
  })

  it('preserves safe list URLs and rejects external return targets', () => {
    expect(listReturnState('/alarms', '?severity=P1&page=2')).toEqual({
      returnTo: '/alarms?severity=P1&page=2',
    })
    expect(resolveListReturnPath({ returnTo: '/alarms?severity=P1&page=2' }, '/alarms')).toBe(
      '/alarms?severity=P1&page=2',
    )
    expect(resolveListReturnPath({ returnTo: '//example.com' }, '/alarms')).toBe('/alarms')
    expect(resolveListReturnPath({ returnTo: 'https://example.com' }, '/alarms')).toBe('/alarms')
  })
})
