import { Link } from 'react-router-dom'

export function ForbiddenPage() {
  return (
    <section className="koc-notfound" aria-labelledby="forbidden-title">
      <h1 id="forbidden-title">403</h1>
      <p>当前账号没有访问该页面的权限。</p>
      <Link to="/system" className="koc-btn koc-btn--primary koc-btn--md">
        返回系统状态
      </Link>
    </section>
  )
}
