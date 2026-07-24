import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { useSession } from '@/features/auth/useSession'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { type CreatedToken, type TokenView, createToken, listTokens, revokeToken } from './api'

const DEFAULT_SCOPES = ['alarm:read', 'approval:read', 'execution:read']

/** API token management page: list, create (plaintext shown once), revoke. */
export function ApiTokensPage() {
  const { session } = useSession()
  const queryClient = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [created, setCreated] = useState<CreatedToken | null>(null)
  const [error, setError] = useState<string | null>(null)
  const canManage = hasPermission(session, PERMISSIONS.TOKEN_MANAGE_OWN)

  const listQuery = useQuery({
    queryKey: ['tokens', 'list'],
    queryFn: listTokens,
    staleTime: 10_000,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['tokens', 'list'] })
  }

  const revokeMutation = useMutation({
    mutationFn: (token: TokenView) => revokeToken(token.id, token.version, commandKey()),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof ApiError ? err.message : '撤销失败'),
  })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <h1>API Token</h1>
          {canManage ? (
            <Button variant="primary" size="sm" onClick={() => setShowCreate((s) => !s)}>
              {showCreate ? '取消' : '创建 Token'}
            </Button>
          ) : null}
        </div>
      </header>

      {created ? <CreatedTokenBanner token={created} onDismiss={() => setCreated(null)} /> : null}
      {showCreate && canManage ? (
        <CreateTokenForm
          onCreated={(t) => {
            setCreated(t)
            invalidate()
            setShowCreate(false)
          }}
        />
      ) : null}
      {error ? (
        <p className="koc-alert koc-alert--error" role="alert">
          {error}
        </p>
      ) : null}

      <AsyncState
        isLoading={listQuery.isLoading}
        error={listQuery.error}
        isEmpty={!listQuery.isLoading && (listQuery.data?.length ?? 0) === 0}
        emptyMessage="没有 API Token"
      >
        <table className="koc-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>前缀</th>
              <th>范围</th>
              <th>过期时间</th>
              <th>最近使用</th>
              <th>状态</th>
              <th>所有者</th>
              <th aria-label="操作" />
            </tr>
          </thead>
          <tbody>
            {(listQuery.data ?? []).map((token) => (
              <tr key={token.id}>
                <td>{token.name}</td>
                <td className="koc-mono">{token.prefix}…</td>
                <td>{token.scopes.join(', ') || '—'}</td>
                <td>{formatTime(token.expiresAt)}</td>
                <td>{formatTime(token.lastUsedAt)}</td>
                <td>
                  <StatusBadge tone={token.revokedAt ? 'neutral' : 'success'}>
                    {token.revokedAt ? '已撤销' : '有效'}
                  </StatusBadge>
                </td>
                <td>{token.ownerUsername}</td>
                <td>
                  {canManage && !token.revokedAt ? (
                    <Button
                      variant="danger"
                      size="sm"
                      disabled={revokeMutation.isPending}
                      onClick={() => revokeMutation.mutate(token)}
                    >
                      撤销
                    </Button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </AsyncState>
    </section>
  )
}

function CreateTokenForm({ onCreated }: { onCreated: (token: CreatedToken) => void }) {
  const [name, setName] = useState('')
  const [expiresAt, setExpiresAt] = useState(defaultExpiry())
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () =>
      createToken({ name, scopes: DEFAULT_SCOPES, expiresAt: new Date(expiresAt).toISOString() }, commandKey()),
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
      <label className="koc-filter">
        <span>名称</span>
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} required />
      </label>
      <label className="koc-filter">
        <span>过期时间</span>
        <input
          type="datetime-local"
          value={expiresAt}
          onChange={(e) => setExpiresAt(e.target.value)}
          required
        />
      </label>
      <Button type="submit" variant="primary" size="sm" disabled={mutation.isPending}>
        {mutation.isPending ? '创建中…' : '创建'}
      </Button>
      {error ? (
        <p className="koc-alert koc-alert--error" role="alert">
          {error}
        </p>
      ) : null}
    </form>
  )
}

function CreatedTokenBanner({ token, onDismiss }: { token: CreatedToken; onDismiss: () => void }) {
  return (
    <div className="koc-card" role="status">
      <h2>Token 已创建</h2>
      {token.tokenReplayed ? (
        <p className="koc-alert koc-alert--warning">
          该请求为幂等重放，明文 Token 不再显示。如需新明文请重新创建。
        </p>
      ) : (
        <>
          <p>
            请立即复制保存，<strong>关闭后将不再显示</strong>。
          </p>
          <p className="koc-mono koc-break">{token.token}</p>
          <Button variant="ghost" size="sm" onClick={() => navigator.clipboard?.writeText(token.token)}>
            复制
          </Button>
        </>
      )}
      <Button variant="ghost" size="sm" onClick={onDismiss}>
        关闭
      </Button>
    </div>
  )
}

function commandKey() {
  return `tokencmd-${crypto.randomUUID()}`
}

function defaultExpiry() {
  const d = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000)
  // datetime-local value: yyyy-MM-ddTHH:mm (local), sliced to minutes.
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}
