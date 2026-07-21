/**
 * Runtime configuration loader.
 *
 * Build-once, run-anywhere: the same static bundle can be deployed to any
 * environment. Environment-specific values (API base URL, SSE path, release
 * SHA, support URL) are injected at deploy time via `/config/runtime.json`,
 * which Nginx (or a startup script) serves from the same origin.
 *
 * If the config file is missing or malformed we fall back to safe same-origin
 * defaults so the app never gets stuck on a blank loading screen.
 */

export interface RuntimeConfig {
  /** Base URL for API requests. Empty string means same-origin. */
  apiBaseUrl: string
  /** Path to the SSE event stream. */
  ssePath: string
  /** Deployment environment label, e.g. "local" | "staging" | "production". */
  environment: string
  /** Release identifier (git SHA / version) for diagnostics. */
  release: string
  /** Optional support / docs URL shown in error UI. */
  supportUrl: string
}

const SAFE_DEFAULTS: RuntimeConfig = {
  apiBaseUrl: '',
  ssePath: '/api/v1/events/stream',
  environment: 'local',
  release: 'dev',
  supportUrl: '',
}

let cached: RuntimeConfig | undefined
let loadPromise: Promise<RuntimeConfig> | undefined

function mergeWithDefaults(partial: unknown): RuntimeConfig {
  if (typeof partial !== 'object' || partial === null) return { ...SAFE_DEFAULTS }
  const p = partial as Record<string, unknown>
  return {
    apiBaseUrl: typeof p.apiBaseUrl === 'string' ? p.apiBaseUrl : SAFE_DEFAULTS.apiBaseUrl,
    ssePath: typeof p.ssePath === 'string' ? p.ssePath : SAFE_DEFAULTS.ssePath,
    environment: typeof p.environment === 'string' ? p.environment : SAFE_DEFAULTS.environment,
    release: typeof p.release === 'string' ? p.release : SAFE_DEFAULTS.release,
    supportUrl: typeof p.supportUrl === 'string' ? p.supportUrl : SAFE_DEFAULTS.supportUrl,
  }
}

/**
 * Load runtime config from `/config/runtime.json`. Safe to call multiple
 * times — the result is cached and the in-flight promise is shared.
 */
export function loadRuntimeConfig(): Promise<RuntimeConfig> {
  if (cached) return Promise.resolve(cached)
  if (loadPromise) return loadPromise

  loadPromise = fetch('/config/runtime.json', { headers: { Accept: 'application/json' } })
    .then(async (res) => {
      if (!res.ok) return { ...SAFE_DEFAULTS }
      try {
        const json = (await res.json()) as unknown
        cached = mergeWithDefaults(json)
      } catch {
        cached = { ...SAFE_DEFAULTS }
      }
      return cached
    })
    .catch(() => {
      cached = { ...SAFE_DEFAULTS }
      return cached
    })

  return loadPromise
}

/**
 * Synchronous accessor. Returns the cached config if loaded, otherwise the
 * safe defaults. Prefer `await loadRuntimeConfig()` at startup.
 */
export function getRuntimeConfig(): RuntimeConfig {
  return cached ?? SAFE_DEFAULTS
}
