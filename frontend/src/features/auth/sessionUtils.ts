/**
 * Open-redirect guard. Allows only relative same-app paths.
 *
 * Reject anything that:
 * - doesn't start with `/`
 * - starts with `//` (protocol-relative URL → open redirect)
 * - starts with `/\` (some browsers normalize to //)
 *
 * Mirrors the rule in the architecture doc (section 7).
 */
export function sanitizeReturnTo(candidate: string): string {
  if (!candidate.startsWith('/')) return '/'
  if (candidate.startsWith('//')) return '/'
  if (candidate.startsWith('/\\')) return '/'
  return candidate
}
