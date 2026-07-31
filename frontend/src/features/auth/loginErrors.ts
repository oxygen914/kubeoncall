import { isApiError } from '@/api/errors'

/**
 * Maps a backend error code to a user-facing login message. Per the error-code
 * doc, login failures show a unified "用户名或密码错误" message even though the
 * backend retains the real classification for audit.
 */
export function mapLoginError(err: unknown): string {
  if (isApiError(err)) {
    switch (err.code) {
      case 'AUTH_INVALID_CREDENTIALS':
      case 'AUTH_ACCOUNT_DISABLED':
      case 'AUTH_ACCOUNT_LOCKED':
      case 'AUTH_PASSWORD_CHANGE_REQUIRED':
        return '用户名或密码错误'
      case 'RATE_LIMITED':
        return '尝试过于频繁,请稍后再试'
      case 'AUTH_CSRF_INVALID':
        return '会话校验失败,请刷新页面后重试'
      default:
        return err.message || '登录失败,请稍后重试'
    }
  }
  return '网络异常,请稍后重试'
}
