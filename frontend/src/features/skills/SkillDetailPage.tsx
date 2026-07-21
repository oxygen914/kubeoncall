import { useNavigate, useParams } from 'react-router-dom'
import { AsyncState } from '@/components/feedback/AsyncState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { hasPermission, PERMISSIONS } from '@/features/auth/permissions'
import { useSession } from '@/features/auth/useSession'
import { useSetSkillEnabled, useSkill } from './hooks'

export function SkillDetailPage() {
  const { skillId = '' } = useParams()
  const navigate = useNavigate()
  const { session } = useSession()
  const query = useSkill(skillId)
  const stateMutation = useSetSkillEnabled(skillId)
  const skill = query.data
  const canManage = hasPermission(session, PERMISSIONS.SKILL_MANAGE)

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div className="koc-page__title-row">
          <Button variant="ghost" size="sm" onClick={() => navigate('/skills')}>
            ← 返回列表
          </Button>
          <h1>Skill 详情</h1>
        </div>
        {skill && canManage ? (
          <Button
            size="sm"
            variant={skill.enabled ? 'danger' : 'primary'}
            disabled={stateMutation.isPending}
            onClick={() =>
              stateMutation.mutate({ version: skill.version, enabled: !skill.enabled })
            }
          >
            {skill.enabled ? '停用' : '启用'}
          </Button>
        ) : null}
      </header>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error ?? stateMutation.error}
        isEmpty={!query.isLoading && !skill}
      >
        {skill ? (
          <>
            <div className="koc-card">
              <dl className="koc-fields">
                <Field label="Skill ID" value={skill.id} mono />
                <Field label="名称" value={skill.name} />
                <Field label="版本" value={skill.skillVersion} />
                <Field label="启用状态" value={skill.enabled ? '已启用' : '已停用'} />
                <Field label="加载状态">
                  <StatusBadge tone={skill.loadStatus === 'FAILED' ? 'danger' : 'success'}>
                    {skill.loadStatus}
                  </StatusBadge>
                </Field>
                <Field label="最大风险" value={skill.maxRisk} />
                <Field label="来源" value={skill.sourceLocation} mono />
                <Field label="加载错误" value={skill.errorSummary} />
                <Field label="并发版本" value={String(skill.version)} />
              </dl>
            </div>
            <section className="koc-card">
              <h2>匹配与工具边界</h2>
              <dl className="koc-fields">
                <Field label="Tags" value={skill.tags.join(', ')} />
                <Field label="适用任务" value={skill.applicableTasks.join(', ')} />
                <Field label="触发词" value={skill.triggers.join(', ')} />
                <Field label="服务" value={skill.services.join(', ')} />
                <Field label="允许工具" value={skill.allowedTools.join(', ')} />
              </dl>
            </section>
            <section className="koc-card">
              <h2>元数据</h2>
              <pre className="koc-json">{JSON.stringify(skill.metadata, null, 2)}</pre>
            </section>
          </>
        ) : null}
      </AsyncState>
    </section>
  )
}

function Field({
  label,
  value,
  mono = false,
  children,
}: {
  label: string
  value?: string | null
  mono?: boolean
  children?: React.ReactNode
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? 'koc-mono' : undefined}>{children ?? value ?? '—'}</dd>
    </div>
  )
}
