import type { SVGProps } from 'react'

export type IconName =
  | 'activity'
  | 'alarm'
  | 'approval'
  | 'ask'
  | 'bell'
  | 'book'
  | 'box'
  | 'change'
  | 'chevron-left'
  | 'chevron-right'
  | 'close'
  | 'cluster'
  | 'execution'
  | 'external-link'
  | 'grid'
  | 'integration'
  | 'key'
  | 'logout'
  | 'logs'
  | 'memory'
  | 'menu'
  | 'moon'
  | 'refresh'
  | 'search'
  | 'settings'
  | 'shield'
  | 'skill'
  | 'sun'
  | 'tools'
  | 'user'
  | 'users'

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  name: IconName
  size?: number
}

export function Icon({ name, size = 18, ...props }: IconProps) {
  return (
    <svg aria-hidden="true" fill="none" height={size} viewBox="0 0 24 24" width={size} {...props}>
      <g stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8">
        {iconPath(name)}
      </g>
    </svg>
  )
}

function iconPath(name: IconName) {
  switch (name) {
    case 'grid':
      return (
        <>
          <rect height="7" rx="1.5" width="7" x="3" y="3" />
          <rect height="7" rx="1.5" width="7" x="14" y="3" />
          <rect height="7" rx="1.5" width="7" x="3" y="14" />
          <rect height="7" rx="1.5" width="7" x="14" y="14" />
        </>
      )
    case 'activity':
      return <path d="M3 12h4l2.2-6 4.1 12 2.2-6H21" />
    case 'logs':
      return (
        <>
          <path d="M8 6h12M8 12h12M8 18h12" />
          <circle cx="4" cy="6" r="1" />
          <circle cx="4" cy="12" r="1" />
          <circle cx="4" cy="18" r="1" />
        </>
      )
    case 'external-link':
      return (
        <>
          <path d="M14 4h6v6M20 4l-9 9" />
          <path d="M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5" />
        </>
      )
    case 'cluster':
      return (
        <>
          <rect height="5" rx="1.5" width="8" x="8" y="3" />
          <rect height="5" rx="1.5" width="7" x="3" y="16" />
          <rect height="5" rx="1.5" width="7" x="14" y="16" />
          <path d="M12 8v4m-5.5 4v-2h11v2" />
        </>
      )
    case 'alarm':
      return (
        <>
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 8.5h18C21 15 18 15 18 8Z" />
          <path d="M10 20h4" />
        </>
      )
    case 'change':
      return (
        <>
          <path d="M7 7h11m0 0-3-3m3 3-3 3M17 17H6m0 0 3 3m-3-3 3-3" />
        </>
      )
    case 'approval':
      return (
        <>
          <rect height="18" rx="2" width="14" x="5" y="3" />
          <path d="m8.5 12 2.2 2.2 4.8-5M9 7h6" />
        </>
      )
    case 'execution':
      return (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="m10 8 6 4-6 4Z" />
        </>
      )
    case 'box':
      return (
        <>
          <path d="m4 7 8-4 8 4-8 4-8-4Z" />
          <path d="m4 7 8 4 8-4v10l-8 4-8-4V7Zm8 4v10" />
        </>
      )
    case 'ask':
      return (
        <>
          <path d="M20 15a4 4 0 0 1-4 4H8l-5 2 1.5-4A7 7 0 0 1 3 12a8 8 0 0 1 8-8h2a8 8 0 0 1 7 11Z" />
          <path d="M9.5 9.2a2.5 2.5 0 1 1 3.3 2.4c-.8.3-.8.8-.8 1.4m0 2.5h.01" />
        </>
      )
    case 'book':
      return (
        <>
          <path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H11v17H6.5A2.5 2.5 0 0 0 4 21.5v-17Z" />
          <path d="M20 4.5A2.5 2.5 0 0 0 17.5 2H13v17h4.5a2.5 2.5 0 0 1 2.5 2.5v-17Z" />
        </>
      )
    case 'memory':
      return (
        <>
          <rect height="12" rx="2" width="14" x="5" y="6" />
          <path d="M9 10h6v4H9zm3-7v3m0 12v3M2 10h3m14 0h3M2 14h3m14 0h3" />
        </>
      )
    case 'skill':
      return (
        <>
          <path d="m12 3 2.1 4.3L19 8l-3.5 3.4.8 4.8-4.3-2.3-4.3 2.3.8-4.8L5 8l4.9-.7L12 3Z" />
          <path d="m9.5 18.5-1 2.5 3.5-1 3.5 1-1-2.5" />
        </>
      )
    case 'tools':
      return (
        <>
          <path d="M14 6.5a4 4 0 0 0-5.2 5.2L3 17.5 6.5 21l5.8-5.8a4 4 0 0 0 5.2-5.2l-2.4 2.4-3.5-3.5L14 6.5Z" />
        </>
      )
    case 'integration':
      return (
        <>
          <path d="M8 3v5H3m13 13v-5h5M5 16a7 7 0 0 0 11.5 2M19 8A7 7 0 0 0 7.5 6" />
        </>
      )
    case 'shield':
      return (
        <>
          <path d="M12 3 5 6v5c0 4.5 2.8 8 7 10 4.2-2 7-5.5 7-10V6l-7-3Z" />
          <path d="m9 12 2 2 4-4" />
        </>
      )
    case 'users':
      return (
        <>
          <circle cx="9" cy="8" r="3" />
          <path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 5.5a3 3 0 0 1 0 5.5m1 3a5 5 0 0 1 4 5" />
        </>
      )
    case 'user':
      return (
        <>
          <circle cx="12" cy="8" r="3.5" />
          <path d="M5 21a7 7 0 0 1 14 0" />
        </>
      )
    case 'key':
      return (
        <>
          <circle cx="8" cy="12" r="4" />
          <path d="M12 12h9m-3 0v3m-3-3v2" />
        </>
      )
    case 'settings':
      return (
        <>
          <circle cx="12" cy="12" r="3" />
          <path d="M19 13.5v-3l-2-.7-.7-1.7.9-1.9-2.1-2.1-1.9.9-1.7-.7L10.5 2h-3l-.7 2-1.7.7-1.9-.9-2.1 2.1.9 1.9-.7 1.7-2 .7v3l2 .7.7 1.7-.9 1.9 2.1 2.1 1.9-.9 1.7.7.7 2h3l.7-2 1.7-.7 1.9.9 2.1-2.1-.9-1.9.7-1.7 2-.4Z" />
        </>
      )
    case 'search':
      return (
        <>
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 5 5" />
        </>
      )
    case 'bell':
      return (
        <>
          <path d="M18 9a6 6 0 0 0-12 0c0 6-2.5 6-2.5 7.5h17C20.5 15 18 15 18 9Z" />
          <path d="M10 20h4" />
        </>
      )
    case 'moon':
      return <path d="M20.5 15.2A8.5 8.5 0 0 1 8.8 3.5 9 9 0 1 0 20.5 15.2Z" />
    case 'sun':
      return (
        <>
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
        </>
      )
    case 'refresh':
      return (
        <>
          <path d="M20 7v5h-5M4 17v-5h5" />
          <path d="M18.2 12A7 7 0 0 0 6.5 6.8L4 9m2 3a7 7 0 0 0 11.5 5.2L20 15" />
        </>
      )
    case 'logout':
      return (
        <>
          <path d="M14 8V4H4v16h10v-4m-3-4h10m-3-3 3 3-3 3" />
        </>
      )
    case 'menu':
      return <path d="M4 7h16M4 12h16M4 17h16" />
    case 'close':
      return <path d="m6 6 12 12M18 6 6 18" />
    case 'chevron-left':
      return <path d="m15 18-6-6 6-6" />
    case 'chevron-right':
      return <path d="m9 18 6-6-6-6" />
  }
}
