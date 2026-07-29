import { useState } from 'react'
import { Outlet, useNavigate } from 'react-router-dom'
import { useSession } from '@/features/auth/useSession'
import { MonitoringScopeProvider } from '@/features/monitoring/MonitoringScopeProvider'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

export function AppShell() {
  const { session, logout } = useSession()
  const navigate = useNavigate()
  const [collapsed, setCollapsed] = useState(false)

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="koc-app-shell">
      <a className="koc-skip-link" href="#main-content">
        跳到主要内容
      </a>
      <Sidebar
        collapsed={collapsed}
        session={session}
        onToggle={() => setCollapsed((current) => !current)}
      />

      <div className="koc-app-shell__workspace">
        <MonitoringScopeProvider>
          <TopBar session={session} onLogout={handleLogout} />
          <main className="koc-app-shell__content" id="main-content">
            <Outlet />
          </main>
        </MonitoringScopeProvider>
      </div>
    </div>
  )
}
