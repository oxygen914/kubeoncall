import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getSkill, listSkills, reloadSkills, setSkillEnabled, type SkillListParams } from './api'

export const skillKeys = {
  all: ['skills'] as const,
  list: (params: SkillListParams) => ['skills', 'list', params] as const,
  detail: (skillId: string) => ['skills', 'detail', skillId] as const,
}

export function useSkills(params: SkillListParams) {
  return useQuery({
    queryKey: skillKeys.list(params),
    queryFn: () => listSkills(params),
    staleTime: 60_000,
  })
}

export function useSkill(skillId: string | undefined) {
  return useQuery({
    queryKey: skillKeys.detail(skillId ?? ''),
    queryFn: () => getSkill(skillId as string),
    enabled: Boolean(skillId),
    staleTime: 60_000,
  })
}

export function useSetSkillEnabled(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ version, enabled }: { version: number; enabled: boolean }) =>
      setSkillEnabled(skillId, version, enabled),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.all })
    },
  })
}

export function useReloadSkills() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: reloadSkills,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.all })
    },
  })
}
