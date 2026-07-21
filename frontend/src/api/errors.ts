/**
 * Typed API error matching the OpenAPI ErrorResponse envelope.
 *
 * Error response shape (from openapi.yaml):
 * {
 *   error: {
 *     code: string,
 *     message: string,
 *     retryable: boolean,
 *     fieldErrors?: FieldError[],
 *     details?: Record<string, unknown>
 *   },
 *   meta: { requestId: string, timestamp: string }
 * }
 */
export interface FieldError {
  field: string
  code: string
  message: string
}

export interface ApiErrorInit {
  code: string
  message: string
  requestId?: string
  status: number
  details?: Record<string, unknown>
  fieldErrors?: FieldError[]
  retryable: boolean
}

export class ApiError extends Error {
  readonly code: string
  readonly requestId: string | undefined
  readonly status: number
  readonly details: Record<string, unknown> | undefined
  readonly fieldErrors: FieldError[] | undefined
  readonly retryable: boolean

  constructor(init: ApiErrorInit) {
    super(init.message)
    this.name = 'ApiError'
    this.code = init.code
    this.requestId = init.requestId
    this.status = init.status
    this.details = init.details
    this.fieldErrors = init.fieldErrors
    this.retryable = init.retryable
    // Restore prototype chain after calling super (compiles down to ES2022)
    Object.setPrototypeOf(this, ApiError.prototype)
  }

  /** True when the error is an authentication failure (401). */
  isAuthError(): boolean {
    return this.status === 401
  }

  /** True when the error is a permission failure (403). */
  isForbidden(): boolean {
    return this.status === 403
  }

  /** Field-level error for a specific form field, if any. */
  fieldErrorFor(field: string): FieldError | undefined {
    return this.fieldErrors?.find((fe) => fe.field === field)
  }
}

/** Narrow an unknown value to an ApiError. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}
