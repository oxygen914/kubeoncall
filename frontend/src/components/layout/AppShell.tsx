import { Suspense, useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import clsx from 'clsx'
import { useSession } from '@/features/auth/useSession'
import { MonitoringScopeProvider } from '@/features/monitoring/MonitoringScopeProvider'
import { Spinner } from '@/components/ui/Spinner'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

export function AppShell() {
  const { session, logout } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const [navigationOpen, setNavigationOpen] = useState(false)
  const mainRef = useRef<HTMLElement>(null)
  const previousLocationRef = useRef(`${location.pathname}${location.search}`)

  useEffect(() => {
    const currentLocation = `${location.pathname}${location.search}`
    if (previousLocationRef.current === currentLocation) return
    previousLocationRef.current = currentLocation
    setNavigationOpen(false)
    mainRef.current?.focus()
  }, [location.pathname, location.search])

  useEffect(() => {
    if (!navigationOpen) return
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const navigation = document.getElementById('primary-navigation')
    const focusableSelector =
      'button:not([disabled]), a[href], select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    const getFocusable = () =>
      Array.from(navigation?.querySelectorAll<HTMLElement>(focusableSelector) ?? []).filter(
        (element) => element.offsetParent !== null,
      )
    getFocusable()[0]?.focus()

    const handleNavigationKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavigationOpen(false)
        return
      }
      if (event.key !== 'Tab') return
      const focusable = getFocusable()
      if (focusable.length === 0) return
      const first = focusable[0]!
      const last = focusable[focusable.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', handleNavigationKey)
    return () => {
      window.removeEventListener('keydown', handleNavigationKey)
      previouslyFocused?.focus()
    }
  }, [navigationOpen])

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className={clsx('koc-app-shell', navigationOpen && 'koc-app-shell--nav-open')}>
      <a className="koc-skip-link" href="#main-content">
        跳到主要内容
      </a>
      <Sidebar
        collapsed={collapsed}
        mobileOpen={navigationOpen}
        session={session}
        onToggle={() => setCollapsed((current) => !current)}
        onNavigate={() => setNavigationOpen(false)}
      />
      <button
        className="koc-app-shell__backdrop"
        type="button"
        aria-label="关闭主导航"
        onClick={() => setNavigationOpen(false)}
      />

      <div className="koc-app-shell__workspace">
        <MonitoringScopeProvider>
          <TopBar
            session={session}
            onLogout={handleLogout}
            onOpenNavigation={() => setNavigationOpen(true)}
          />
          <main ref={mainRef} className="koc-app-shell__content" id="main-content" tabIndex={-1}>
            <Suspense
              fallback={
                <div className="koc-route-loading" role="status" aria-live="polite">
                  <Spinner size="md" />
                  <span>正在加载页面…</span>
                </div>
              }
            >
              <Outlet />
            </Suspense>
          </main>
        </MonitoringScopeProvider>
      </div>
    </div>
  )
}
