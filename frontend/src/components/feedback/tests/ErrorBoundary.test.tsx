import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const reportRenderError = vi.hoisted(() => vi.fn())
vi.mock('@/telemetry/clientEvents', () => ({ reportRenderError }))

import { ErrorBoundary } from '../ErrorBoundary'

function BrokenView(): never {
  throw new Error('Bearer private-token should not be rendered')
}

describe('ErrorBoundary telemetry', () => {
  beforeEach(() => {
    reportRenderError.mockReset()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  it('reports render failures and keeps raw error text out of the fallback UI', () => {
    render(
      <ErrorBoundary>
        <BrokenView />
      </ErrorBoundary>,
    )

    expect(reportRenderError).toHaveBeenCalledOnce()
    expect(screen.getByRole('alert')).toHaveTextContent('页面出现问题')
    expect(screen.queryByText(/private-token/)).not.toBeInTheDocument()
  })
})
