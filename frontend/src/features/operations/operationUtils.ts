import { ApiError } from '@/api/errors'

export function parseObject(value: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('请输入 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

export function parseArray(value: string): Record<string, unknown>[] {
  const parsed: unknown = JSON.parse(value)
  if (!Array.isArray(parsed) || parsed.some((item) => !item || typeof item !== 'object')) {
    throw new Error('请输入告警 JSON 数组')
  }
  return parsed as Record<string, unknown>[]
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '操作失败'
}

export function formatTime(value: string): string {
  return new Date(value).toLocaleString()
}

export function formatMatchers(matchers: Record<string, string>): string {
  return Object.entries(matchers)
    .map(([key, value]) => `${key}=${value}`)
    .join(', ')
}

export function formatMatch(match: {
  resourceTypes: string[]
  alertNamePatterns: string[]
}): string {
  return [...match.resourceTypes, ...match.alertNamePatterns].join(', ') || '全部'
}

export function localTime(offsetMinutes: number): string {
  const time = new Date(Date.now() + offsetMinutes * 60_000)
  return new Date(time.getTime() - time.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
