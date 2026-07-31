import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AuditDiff } from '../AuditDetailPage'

describe('AuditDiff', () => {
  it('shows the union of before and after fields and marks changed values', () => {
    render(
      <AuditDiff
        before={{ status: 'FIRING', owner: null }}
        after={{ status: 'ACKNOWLEDGED', version: 2 }}
      />,
    )

    expect(screen.getByText('status')).toBeInTheDocument()
    expect(screen.getByText('owner')).toBeInTheDocument()
    expect(screen.getByText('version')).toBeInTheDocument()
    expect(screen.getByText('FIRING')).toBeInTheDocument()
    expect(screen.getByText('ACKNOWLEDGED')).toBeInTheDocument()
    expect(screen.getAllByText('（已变化）')).toHaveLength(3)
  })
})
