import { api, type Page } from '@/api/client'
import type { TaskAccepted } from '@/features/tasks/api'

export type SkillLoadStatus = 'DISCOVERED' | 'LOADING' | 'LOADED' | 'FAILED' | 'DISABLED'

export interface SkillListItem {
  id: string
  name: string
  skillVersion: string | null
  enabled: boolean
  loadStatus: SkillLoadStatus
  errorSummary: string | null
  tags: string[]
  applicableTasks: string[]
  maxRisk: string | null
  version: number
  updatedAt: string
}

export interface SkillDetail extends SkillListItem {
  description: string | null
  checksum: string
  sourceLocation: string
  triggers: string[]
  services: string[]
  allowedTools: string[]
  metadata: Record<string, unknown>
  lastLoadedAt: string | null
}

export interface SkillStateUpdate {
  id: string
  enabled: boolean
  loadStatus?: SkillLoadStatus
  version: number
}

export interface SkillPage {
  data: SkillListItem[]
  page: Page
}

export interface SkillListParams {
  page?: number
  size?: number
  enabled?: boolean
  loadStatus?: SkillLoadStatus | ''
  query?: string
  [key: string]: string | number | boolean | undefined | null
}

export function listSkills(params: SkillListParams = {}): Promise<SkillPage> {
  return api.list<SkillListItem>('/api/v1/skills', { query: compactParams(params) })
}

export function getSkill(skillId: string): Promise<SkillDetail> {
  return api.get<SkillDetail>(`/api/v1/skills/${encodeURIComponent(skillId)}`)
}

export function setSkillEnabled(
  skillId: string,
  version: number,
  enabled: boolean,
): Promise<SkillStateUpdate> {
  return api.post<SkillStateUpdate>(
    `/api/v1/skills/${encodeURIComponent(skillId)}/enabled`,
    { enabled },
    {
      headers: { 'If-Match': String(version) },
      idempotencyKey: `skill_state_${crypto.randomUUID()}`,
    },
  )
}

export function reloadSkills(): Promise<TaskAccepted> {
  return api.post<TaskAccepted>(
    '/api/v1/skills/reload',
    {},
    {
      idempotencyKey: `skill_reload_${crypto.randomUUID()}`,
    },
  )
}

function compactParams(
  params: Record<string, string | number | boolean | undefined | null>,
): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    result[key] = value
  }
  return result
}
