export interface ListReturnState {
  returnTo: string
}

type SearchParamValue = string | number | null | undefined

/** Parse a one-based page number from the URL without allowing invalid or negative values. */
export function readPageParam(value: string | null): number {
  if (!value) return 1
  const page = Number.parseInt(value, 10)
  return Number.isSafeInteger(page) && page > 0 ? page : 1
}

/** Merge list filters into a new URLSearchParams instance. Nullish values remove a parameter. */
export function mergeSearchParams(
  current: URLSearchParams,
  updates: Record<string, SearchParamValue>,
): URLSearchParams {
  const next = new URLSearchParams(current)
  Object.entries(updates).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') {
      next.delete(key)
      return
    }
    next.set(key, String(value))
  })
  return next
}

/** Preserve the exact list URL so a detail page can return without dropping filters or pagination. */
export function listReturnState(pathname: string, search: string): ListReturnState {
  return { returnTo: `${pathname}${search}` }
}

/** Resolve an internal return target while rejecting protocol-relative or malformed paths. */
export function resolveListReturnPath(state: unknown, fallback: string): string {
  if (!isSafeInternalPath(fallback)) {
    throw new Error(`List fallback must be an internal path: ${fallback}`)
  }
  if (!state || typeof state !== 'object') return fallback
  const returnTo = (state as { returnTo?: unknown }).returnTo
  return typeof returnTo === 'string' && isSafeInternalPath(returnTo) ? returnTo : fallback
}

function isSafeInternalPath(value: string): boolean {
  return value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')
}
