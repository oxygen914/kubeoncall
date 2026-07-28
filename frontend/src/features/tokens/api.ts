import { api } from '@/api/client'

/**
 * API tokens API module. Matches the /api/v1/api-tokens contract:
 *
 *   list:    { data: TokenView[], meta }
 *   create:  { data: TokenView & { token }, meta }   // plaintext returned once
 *   revoke:  { data: { id, revokedAt, version }, meta }
 */

export interface TokenView {
  id: string
  name: string
  prefix: string
  scopes: string[]
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  ownerId: string
  ownerUsername: string
  version: number
  createdAt: string | null
}

export interface CreatedToken extends TokenView {
  /** Plaintext token, returned exactly once on create. */
  token: string
  /** True when this create response is an idempotency replay (no plaintext). */
  tokenReplayed?: boolean
}

export interface CreateTokenRequest {
  name: string
  scopes: string[]
  expiresAt: string
}

export function listTokens(): Promise<TokenView[]> {
  return api.get<TokenView[]>('/api/v1/api-tokens')
}

export function createToken(
  request: CreateTokenRequest,
  idempotencyKey: string,
): Promise<CreatedToken> {
  return api.post<CreatedToken>('/api/v1/api-tokens', request, { idempotencyKey })
}

export function revokeToken(
  tokenId: string,
  version: number,
  idempotencyKey: string,
): Promise<{ id: string; revokedAt: string; version: number }> {
  return api.delete(`/api/v1/api-tokens/${encodeURIComponent(tokenId)}`, undefined, {
    headers: { 'If-Match': `"${version}"` },
    idempotencyKey,
  })
}
