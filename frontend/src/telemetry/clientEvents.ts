import { getRuntimeConfig } from '@/api/runtimeConfig'
import { getLastRequestId } from '@/lib/requestId'

const ENDPOINT = '/api/v1/client-events'
const MESSAGE_MAX_LENGTH = 240
const RATE_LIMIT_MS = 60_000
const MAX_FINGERPRINTS = 100

export interface ClientEventPayload {
  release: string
  environment: string
  route: string
  errorCode: string
  requestId: string | null
  browser: string
  message: string
}

export interface ClientErrorInput {
  errorCode: string
  message?: string
  requestId?: string
}

interface ClientEventContext {
  release: string
  environment: string
  route: string
  browser: string
}

interface ClientEventReporterOptions {
  now?: () => number
  context?: () => ClientEventContext
  transport?: (payload: ClientEventPayload) => void
  rateLimitMs?: number
}

/**
 * Allowlist-only client error reporter. It cannot accept arbitrary metadata,
 * headers, request bodies or stack traces by construction.
 */
export class ClientEventReporter {
  private readonly lastSentAt = new Map<string, number>()
  private readonly now: () => number
  private readonly context: () => ClientEventContext
  private readonly transport: (payload: ClientEventPayload) => void
  private readonly rateLimitMs: number

  constructor(options: ClientEventReporterOptions = {}) {
    this.now = options.now ?? Date.now
    this.context = options.context ?? browserContext
    this.transport = options.transport ?? sendClientEvent
    this.rateLimitMs = options.rateLimitMs ?? RATE_LIMIT_MS
  }

  report(input: ClientErrorInput): boolean {
    try {
      const payload = createClientEventPayload(input, this.context())
      const fingerprint = `${payload.errorCode}|${payload.route}`
      const now = this.now()
      const lastSentAt = this.lastSentAt.get(fingerprint)
      if (lastSentAt !== undefined && now - lastSentAt < this.rateLimitMs) return false

      this.lastSentAt.set(fingerprint, now)
      this.trimFingerprints()
      this.transport(payload)
      return true
    } catch {
      // Error reporting must never replace or amplify the original failure.
      return false
    }
  }

  private trimFingerprints(): void {
    while (this.lastSentAt.size > MAX_FINGERPRINTS) {
      const oldest = this.lastSentAt.keys().next().value as string | undefined
      if (!oldest) return
      this.lastSentAt.delete(oldest)
    }
  }
}

const defaultReporter = new ClientEventReporter()

export function reportClientError(input: ClientErrorInput): void {
  defaultReporter.report(input)
}

export function reportApiError(
  error: {
    code: string
    message: string
    requestId?: string
    status: number
  },
  reporter: ClientEventReporter = defaultReporter,
): void {
  if (error.status !== 0 && error.status < 500) return
  reporter.report({
    errorCode: error.code || 'API_ERROR',
    message: error.message,
    requestId: error.requestId,
  })
}

export function reportRenderError(error: Error): void {
  reportClientError({
    errorCode: 'RENDER_ERROR',
    message: error.message,
    requestId: getLastRequestId(),
  })
}

export function installGlobalErrorReporting(
  reporter: ClientEventReporter = defaultReporter,
): () => void {
  const onError = (event: ErrorEvent) => {
    reporter.report({
      errorCode: 'WINDOW_ERROR',
      message: event.message,
      requestId: getLastRequestId(),
    })
  }
  const onUnhandledRejection = (event: PromiseRejectionEvent) => {
    const reason = event.reason as unknown
    if (isApiErrorLike(reason)) {
      reportApiError(reason, reporter)
      return
    }
    reporter.report({
      errorCode: 'UNHANDLED_REJECTION',
      message: unknownErrorMessage(reason),
      requestId: getLastRequestId(),
    })
  }

  window.addEventListener('error', onError)
  window.addEventListener('unhandledrejection', onUnhandledRejection)
  return () => {
    window.removeEventListener('error', onError)
    window.removeEventListener('unhandledrejection', onUnhandledRejection)
  }
}

export function createClientEventPayload(
  input: ClientErrorInput,
  context: ClientEventContext,
): ClientEventPayload {
  return {
    release: boundedSummary(context.release, 128, 'unknown'),
    environment: boundedSummary(context.environment, 64, 'unknown'),
    route: sanitizeRoute(context.route),
    errorCode: boundedCode(input.errorCode),
    requestId: input.requestId ? boundedCode(input.requestId) : null,
    browser: boundedSummary(context.browser, 120, 'unknown'),
    message: sanitizeMessage(input.message),
  }
}

export function sanitizeMessage(message: string | undefined): string {
  if (!message) return 'Client error'
  let value = message
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
    .replace(/\b(?:authorization|cookie|set-cookie)\s*[:=]\s*[^\s,;]+/gi, '$1=[REDACTED]')
    .replace(
      /\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\s*[:=]\s*[^\s,;]+/gi,
      '$1=[REDACTED]',
    )
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[JWT_REDACTED]')
    .replace(/([?&][^=\s]+)=([^&\s]+)/g, '$1=[REDACTED]')
    .replace(/[\r\n\t]+/g, ' ')
    .trim()
  if (!value) value = 'Client error'
  return value.slice(0, MESSAGE_MAX_LENGTH)
}

function browserContext(): ClientEventContext {
  const config = getRuntimeConfig()
  return {
    release: config.release,
    environment: config.environment,
    route: typeof window === 'undefined' ? '/' : window.location.pathname,
    browser: browserSummary(),
  }
}

function browserSummary(): string {
  if (typeof navigator === 'undefined') return 'unknown'
  const userAgent = navigator.userAgent
  const match =
    userAgent.match(/Edg\/([\d.]+)/) ??
    userAgent.match(/Chrome\/([\d.]+)/) ??
    userAgent.match(/Firefox\/([\d.]+)/) ??
    userAgent.match(/Version\/([\d.]+).*Safari\//)
  const family = match?.[0]?.split('/')[0] ?? 'Other'
  const version = match?.[1] ?? ''
  const platform = navigator.platform || 'unknown'
  return `${family}${version ? ` ${version}` : ''} / ${platform}`
}

function sanitizeRoute(route: string): string {
  const pathname = route.split(/[?#]/, 1)[0] || '/'
  const segments = pathname.split('/').map((segment) => {
    if (
      segment.length > 64 ||
      /^[a-f0-9]{32,}$/i.test(segment) ||
      /^[A-Za-z0-9_-]{80,}$/.test(segment)
    ) {
      return ':id'
    }
    return segment
  })
  return boundedSummary(segments.join('/'), 256, '/')
}

function boundedCode(value: string): string {
  const normalized = value.replace(/[^A-Za-z0-9_.:-]/g, '_')
  return normalized.slice(0, 128) || 'CLIENT_ERROR'
}

function boundedSummary(value: string, maxLength: number, fallback: string): string {
  const normalized = sanitizeMessage(value)
  return (normalized || fallback).slice(0, maxLength)
}

export function sendClientEvent(payload: ClientEventPayload): void {
  const config = getRuntimeConfig()
  const url = joinBaseAndPath(config.apiBaseUrl, ENDPOINT)
  const serialized = JSON.stringify(payload)

  try {
    if (
      typeof navigator !== 'undefined' &&
      typeof navigator.sendBeacon === 'function' &&
      navigator.sendBeacon(url, new Blob([serialized], { type: 'application/json' }))
    ) {
      return
    }
  } catch {
    // Fall through to a keepalive request.
  }

  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    const csrf = readCookie('KOC_CSRF')
    if (csrf) headers['X-CSRF-Token'] = csrf
    void fetch(url, {
      method: 'POST',
      headers,
      body: serialized,
      credentials: 'include',
      keepalive: true,
    }).catch(() => undefined)
  } catch {
    // Unsupported keepalive, CSP failures and test environments are non-fatal.
  }
}

function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]!) : undefined
}

function joinBaseAndPath(base: string, path: string): string {
  if (!base) return path
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

function isApiErrorLike(
  value: unknown,
): value is { code: string; message: string; requestId?: string; status: number } {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' &&
    typeof candidate.message === 'string' &&
    typeof candidate.status === 'number'
  )
}

function unknownErrorMessage(value: unknown): string {
  if (value instanceof Error) return value.message
  if (typeof value === 'string') return value
  return 'Unhandled promise rejection'
}
