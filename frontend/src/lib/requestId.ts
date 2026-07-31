/**
 * Request ID generation and tracking.
 *
 * Every outbound API request gets a client-generated `X-Request-Id` so that
 * a request can be correlated across browser -> gateway -> backend -> logs,
 * even before the server assigns its own trace id.
 *
 * Format: `req_<uuid>` to make client-generated ids visually distinct from
 * server ids in log greps.
 */

const PREFIX = 'req_'

/** Generate a fresh request id (`req_<uuid>`). */
export function newRequestId(): string {
  // crypto.randomUUID is available in all modern browsers and in jsdom (Node 20+).
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${PREFIX}${crypto.randomUUID()}`
  }
  // Fallback for very old runtimes — should not be hit in practice.
  return `${PREFIX}${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`
}

/** Best-effort last-seen request id, for display in error boundaries. */
let lastRequestId: string | undefined

export function trackRequestId(id: string): void {
  lastRequestId = id
}

export function getLastRequestId(): string | undefined {
  return lastRequestId
}
