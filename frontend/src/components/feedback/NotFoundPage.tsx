import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <main className="koc-notfound" role="main">
      <h1>404</h1>
      <p>页面不存在或已被移动。</p>
      <Link to="/" className="koc-btn koc-btn--primary koc-btn--md">
        返回首页
      </Link>
    </main>
  )
}
