import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { PageTabs } from '../PageTabs'

describe('PageTabs', () => {
  it('keeps operational subviews deep-linkable and exposes the active view', () => {
    render(
      <MemoryRouter>
        <PageTabs
          activeId="nodes"
          label="监控视图"
          tabs={[
            { id: 'overview', label: '健康总览', to: '/monitoring?view=overview' },
            {
              id: 'nodes',
              label: '节点',
              description: '状态与 CPU 趋势',
              to: '/monitoring?view=nodes',
            },
          ]}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('navigation', { name: '监控视图' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '节点 状态与 CPU 趋势' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('link', { name: '健康总览' })).toHaveAttribute(
      'href',
      '/monitoring?view=overview',
    )
  })
})
