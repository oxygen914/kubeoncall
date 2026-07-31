import type { paths } from './schema'

/**
 * Shared generated-contract surface. Feature clients continue to use the hardened `api` transport
 * (request id, CSRF and error-envelope handling), while this alias gives them a single generated
 * source for endpoint and payload types.
 */
export type OpenApiPaths = paths
