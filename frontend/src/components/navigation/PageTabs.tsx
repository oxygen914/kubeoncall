import { Link } from 'react-router-dom'
import clsx from 'clsx'

export interface PageTab {
  id: string
  label: string
  description?: string
  to: string
}

interface PageTabsProps {
  activeId: string
  label: string
  tabs: PageTab[]
}

/**
 * Secondary navigation for closely related views inside one operational workspace.
 *
 * Tabs use real links so every view remains deep-linkable and browser back/forward keeps working.
 */
export function PageTabs({ activeId, label, tabs }: PageTabsProps) {
  return (
    <nav className="koc-page-tabs" aria-label={label}>
      <ul>
        {tabs.map((tab) => {
          const active = tab.id === activeId
          return (
            <li key={tab.id}>
              <Link
                className={clsx('koc-page-tabs__link', active && 'koc-page-tabs__link--active')}
                to={tab.to}
                aria-current={active ? 'page' : undefined}
              >
                <strong>{tab.label}</strong>
                {tab.description ? <span>{tab.description}</span> : null}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
