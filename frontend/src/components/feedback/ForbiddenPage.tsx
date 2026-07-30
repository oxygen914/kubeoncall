import { Link } from 'react-router-dom'
import { getDefaultConsolePath } from '@/features/auth/defaultRoute'
import { useSession } from '@/features/auth/useSession'

export function ForbiddenPage() {
  const { session } = useSession()
  const defaultPath = getDefaultConsolePath(session)

  return (
    <section className="koc-notfound" aria-labelledby="forbidden-title">
      <h1 id="forbidden-title">403</h1>
      <p>当前账号没有访问该页面的权限。</p>
      {defaultPath ? (
        <Link to={defaultPath} className="koc-btn koc-btn--primary koc-btn--md">
          返回可访问首页
        </Link>
      ) : (
        <p>当前账号尚未分配任何 Console 页面权限，请联系管理员。</p>
      )}
    </section>
  )
}
