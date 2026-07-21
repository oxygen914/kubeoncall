import { ApiError, type FieldError } from './errors'
import { getRuntimeConfig } from './runtimeConfig'
import { newRequestId, trackRequestId } from '@/lib/requestId'
import { reportApiError } from '@/telemetry/clientEvents'

/**
 * Unified response envelopes (from openapi.yaml):
 *
 *   success: { data: T, meta: { requestId, timestamp } }
 *   error:   { error: { code, message, retryable, fieldErrors?, details? }, meta }
 */

export interface Meta {
  requestId: string
  timestamp: string
}

interface SuccessEnvelope<T> {
  data: T
  meta: Meta
}

interface ErrorEnvelope {
  error: {
    code: string
    message: string
    retryable: boolean
    fieldErrors?: FieldError[]
    details?: Record<string, unknown>
  }
  meta?: Meta
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  /** JSON-serializable request body. */
  body?: unknown
  /** URL query params (object → URLSearchParams). */
  query?: Record<string, string | number | boolean | undefined | null>
  /** Extra headers. */
  headers?: Record<string, string>
  /** Abort signal for cancellation. */
  signal?: AbortSignal
  /**
   * Idempotency key for unsafe requests. When set, also allows automatic retry
   * of retryable failures.
   */
  idempotencyKey?: string
}

/** Name of the readable cookie that carries the CSRF token. */
export const CSRF_COOKIE_NAME = 'KOC_CSRF'

/** Read a cookie value by name (no dependency on a cookie lib). */
export function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]!) : undefined
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const base = getRuntimeConfig().apiBaseUrl
  const url = `${base}${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue
    params.append(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

function isRetryableMethod(method: HttpMethod): boolean {
  return method === 'GET'
}

type SuccessDecoder<T> = (payload: unknown, response: Response, requestId: string) => T

/**
 * Core fetch wrapper. All feature API modules go through this. Components
 * and pages should never call `fetch` directly.
 *
 * Guarantees:
 * - `credentials: "include"` so the `KOC_SESSION` HttpOnly cookie is sent.
 * - Auto `X-Request-Id` (`req_<uuid>`).
 * - CSRF token (`X-CSRF-Token`) injected on all non-GET requests from the
 *   `KOC_CSRF` readable cookie.
 * - Parses the `{data, meta}` / `{error, meta}` envelope.
 * - Throws a typed `ApiError` on non-2xx responses.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return executeRequest(path, options, decodeDataEnvelope<T>)
}

/**
 * Request a paged list without losing the top-level `page` sibling.
 *
 * List endpoints intentionally use `{ data: T[], page, meta }`, whereas normal
 * success endpoints use `{ data: T, meta }`. Keeping this decoder separate
 * prevents a normal data-envelope unwrap from discarding pagination.
 */
export async function apiListRequest<T>(
  path: string,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<ListEnvelope<T>> {
  return executeRequest(path, { ...options, method: 'GET' }, decodeListEnvelope<T>)
}

async function executeRequest<T>(
  path: string,
  options: RequestOptions,
  decodeSuccess: SuccessDecoder<T>,
): Promise<T> {
  const { method = 'GET', body, query, headers = {}, signal, idempotencyKey } = options

  const requestId = headers['X-Request-Id'] ?? newRequestId()
  trackRequestId(requestId)
  const isFormDataBody = typeof FormData !== 'undefined' && body instanceof FormData

  const finalHeaders: Record<string, string> = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
    ...headers,
  }

  if (body !== undefined && !isFormDataBody) {
    finalHeaders['Content-Type'] = 'application/json'
  }

  if (method !== 'GET') {
    const csrf = readCookie(CSRF_COOKIE_NAME)
    if (csrf) {
      finalHeaders['X-CSRF-Token'] = csrf
    }
  }

  if (idempotencyKey) {
    finalHeaders['Idempotency-Key'] = idempotencyKey
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : isFormDataBody ? body : JSON.stringify(body),
      credentials: 'include',
      signal,
    })
  } catch (err) {
    // Network failure / aborted. Treat aborted separately.
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw err
    }
    const apiError = new ApiError({
      code: 'NETWORK_ERROR',
      message: '网络请求失败,请检查连接后重试。',
      status: 0,
      retryable: isRetryableMethod(method),
      requestId,
    })
    reportApiError(apiError)
    throw apiError
  }

  return parseResponse(response, requestId, decodeSuccess)
}

async function parseResponse<T>(
  response: Response,
  requestId: string,
  decodeSuccess: SuccessDecoder<T>,
): Promise<T> {
  // 204 No Content (e.g. logout)
  if (response.status === 204) {
    return undefined as T
  }

  const contentType = response.headers.get('content-type') ?? ''
  const isJson = contentType.includes('application/json')
  const payload: unknown = isJson ? await response.json().catch(() => null) : null

  if (response.ok) {
    const meta = readMeta(payload)
    const responseRequestId = meta?.requestId ?? requestId
    if (meta?.requestId) trackRequestId(meta.requestId)
    return decodeSuccess(payload, response, responseRequestId)
  }

  // Error envelope
  const errEnv = payload as ErrorEnvelope | null
  const errObj = errEnv?.error
  const metaRequestId = errEnv?.meta?.requestId ?? requestId

  const apiError = new ApiError({
    code: errObj?.code ?? 'UNKNOWN_ERROR',
    message: errObj?.message ?? response.statusText ?? '请求失败',
    status: response.status,
    retryable: errObj?.retryable ?? false,
    fieldErrors: errObj?.fieldErrors,
    details: errObj?.details,
    requestId: metaRequestId,
  })
  reportApiError(apiError)
  throw apiError
}

function decodeDataEnvelope<T>(payload: unknown): T {
  if (isRecord(payload) && Object.prototype.hasOwnProperty.call(payload, 'data')) {
    return (payload as unknown as SuccessEnvelope<T>).data
  }
  // Keep compatibility with endpoints that deliberately return a raw value.
  return payload as T
}

function decodeListEnvelope<T>(
  payload: unknown,
  response: Response,
  requestId: string,
): ListEnvelope<T> {
  if (!isRecord(payload) || !Array.isArray(payload.data) || !isPage(payload.page)) {
    throw new ApiError({
      code: 'INVALID_RESPONSE',
      message: '服务端返回了无效的分页响应。',
      status: response.status,
      retryable: false,
      requestId,
    })
  }

  return {
    data: payload.data as T[],
    page: payload.page,
  }
}

function readMeta(payload: unknown): Meta | undefined {
  if (!isRecord(payload) || !isRecord(payload.meta)) return undefined
  const { requestId, timestamp } = payload.meta
  if (typeof requestId !== 'string' || typeof timestamp !== 'string') return undefined
  return { requestId, timestamp }
}

function isPage(value: unknown): value is Page {
  if (!isRecord(value)) return false
  return (
    typeof value.number === 'number' &&
    typeof value.size === 'number' &&
    typeof value.totalElements === 'number' &&
    typeof value.totalPages === 'number' &&
    typeof value.hasNext === 'boolean'
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** Convenience helpers. */
export const api = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiRequest<T>(path, { ...options, method: 'DELETE', body }),
  /**
   * List helper for paged endpoints that return `{ data, page, meta }`.
   * Unlike `get`, it validates and preserves the top-level `page` block.
   */
  list: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiListRequest<T>(path, options),
}

/** Shape of a paged list response: `{ data, page }` (meta is consumed by the client). */
export interface ListEnvelope<T> {
  data: T[]
  page: Page
}

export interface Page {
  number: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}
