import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import { ApiError } from '@/api/errors'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'

interface UserView {
  id: string
  username: string
  displayName: string
  email: string | null
  status: string
  roles: string[]
  lastLoginAt: string | null
  lockedUntil: string | null
}

/** User & role management page: list users, create, disable/enable, change password. */
export function UserManagementPage() {
  const queryClient = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const listQuery = useQuery({
    queryKey: ['users', 'list'],
    queryFn: () => api.get<UserView[]>('/api/v1/users'),
    staleTime: 10_000,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['users', 'list'] })
  }

  const disableMutation = useMutation({
    mutationFn: (userId: string) =>
      api.post(`/api/v1/users/${encodeURIComponent(userId)}/disable`, undefined),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof ApiError ? err.message : '操作失败'),
  })
  const enableMutation = useMutation({
    mutationFn: (userId: string) =>
      api.post(`/api/v1/users/${encodeURIComponent(userId)}/enable`, undefined),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof ApiError ? err.message : '操作失败'),
  })
  const revokeMutation = useMutation({
    mutationFn: (userId: string) =>
      api.post(`/api/v1/users/${encodeURIComponent(userId)}/session-revocations`, undefined),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof ApiError ? err.message : '操作失败'),
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <h1>用户与角色</h1>
          <Button variant="primary" size="sm" onClick={() => setShowCreate((s) => !s)}>
            {showCreate ? '取消' : '创建用户'}
          </Button>
        </div>
      </header>

      {showCreate ? <CreateUserForm onCreated={() => { invalidate(); setShowCreate(false) }} /> : null}
      {error ? <p className="koc-alert koc-alert--error" role="alert">{error}</p> : null}

      <AsyncState
        isLoading={listQuery.isLoading}
        error={listQuery.error}
        isEmpty={!listQuery.isLoading && (listQuery.data?.length ?? 0) === 0}
        emptyMessage="没有用户"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>用户名</th><th>显示名</th><th>角色</th><th>状态</th><th>最近登录</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            {(listQuery.data ?? []).map((user) => (
              <tr key={user.id}>
                <td className="koc-table__cell--primary">{user.username}</td>
                <td>{user.displayName}</td>
                <td>{user.roles.map((r) => <StatusBadge key={r} tone="info">{r}</StatusBadge>)}</td>
                <td><StatusBadge tone={user.status === 'ACTIVE' ? 'success' : 'neutral'}>{user.status}</StatusBadge></td>
                <td>{user.lastLoginAt ?? '—'}</td>
                <td>
                  <div className="koc-pagination__actions">
                    {user.status === 'ACTIVE' ? (
                      <Button variant="ghost" size="sm" disabled={disableMutation.isPending} onClick={() => disableMutation.mutate(user.id)}>
                        禁用
                      </Button>
                    ) : (
                      <Button variant="ghost" size="sm" disabled={enableMutation.isPending} onClick={() => enableMutation.mutate(user.id)}>
                        启用
                      </Button>
                    )}
                    <Button variant="ghost" size="sm" disabled={revokeMutation.isPending} onClick={() => revokeMutation.mutate(user.id)}>
                      撤销会话
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </AsyncState>
    </section>
  )
}

function CreateUserForm({ onCreated }: { onCreated: () => void }) {
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('VIEWER')
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () =>
      api.post('/api/v1/users', {
        username,
        displayName: displayName || username,
        email: email || undefined,
        password,
        role,
      }),
    onSuccess: onCreated,
    onError: (err) => setError(err instanceof ApiError ? err.message : '创建失败'),
  })

  return (
    <form
      className="koc-filters"
      onSubmit={(e) => {
        e.preventDefault()
        mutation.mutate()
      }}
    >
      <label className="koc-filter"><span>用户名</span>
        <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={128} required />
      </label>
      <label className="koc-filter"><span>显示名</span>
        <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={128} />
      </label>
      <label className="koc-filter"><span>邮箱</span>
        <input value={email} onChange={(e) => setEmail(e.target.value)} maxLength={320} />
      </label>
      <label className="koc-filter"><span>密码（≥12）</span>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} maxLength={1024} required />
      </label>
      <label className="koc-filter"><span>角色</span>
        <select value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="VIEWER">VIEWER</option>
          <option value="OPERATOR">OPERATOR</option>
          <option value="ADMIN">ADMIN</option>
        </select>
      </label>
      <Button type="submit" variant="primary" size="sm" disabled={mutation.isPending}>
        {mutation.isPending ? '创建中…' : '创建'}
      </Button>
      {error ? <p className="koc-alert koc-alert--error" role="alert">{error}</p> : null}
    </form>
  )
}
