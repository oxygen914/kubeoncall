import { useQuery } from '@tanstack/react-query'
import { AsyncState } from '@/components/feedback/AsyncState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { getToolCatalog, type ToolView, type VerifierCapability } from './api'

/** Tool catalog page: lists planner, executor and verifier tools the agent runtime can invoke. */
export function ToolsPage() {
  const query = useQuery({ queryKey: ['tools', 'catalog'], queryFn: getToolCatalog, staleTime: 60_000 })

  return (
    <section className="koc-page">
      <header className="koc-page__header">
        <div>
          <h1>工具目录</h1>
          <p className="koc-page__subtitle">
            查看 Agent 运行时可调用的规划、执行与校验工具及其权限、参数和依赖。
          </p>
        </div>
      </header>

      <AsyncState
        isLoading={query.isLoading}
        error={query.error}
        isEmpty={!query.isLoading && !query.data}
        emptyMessage="没有可用工具"
      >
        <section className="koc-card">
          <h2>规划工具（Planner）</h2>
          <p className="koc-page__subtitle">只读工具，用于告警发生时收集上下文。</p>
          <ToolTable tools={query.data?.planner ?? []} />
        </section>

        <section className="koc-card">
          <h2>执行工具（Executor）</h2>
          <p className="koc-page__subtitle">可变更状态的工具，高风险操作需审批。</p>
          <ToolTable tools={query.data?.executor ?? []} />
        </section>

        <section className="koc-card">
          <h2>校验能力（Verifier）</h2>
          <VerifierTable capabilities={query.data?.verifier ?? []} />
        </section>
      </AsyncState>
    </section>
  )
}

function ToolTable({ tools }: { tools: ToolView[] }) {
  if (tools.length === 0) {
    return <p>无</p>
  }
  return (
    <table className="koc-table">
      <thead>
        <tr>
          <th>名称</th>
          <th>用途</th>
          <th>风险</th>
          <th>所需参数</th>
          <th>依赖系统</th>
          <th>适用任务</th>
        </tr>
      </thead>
      <tbody>
        {tools.map((tool) => (
          <tr key={tool.name}>
            <td className="koc-mono">{tool.name}</td>
            <td>{tool.description}</td>
            <td>
              {tool.readOnly ? (
                <StatusBadge tone="success">只读</StatusBadge>
              ) : (
                <StatusBadge tone="warning">变更</StatusBadge>
              )}
              {tool.requiresApproval ? (
                <>
                  {' '}
                  <StatusBadge tone="danger">需审批</StatusBadge>
                </>
              ) : null}
            </td>
            <td>{tool.requiredParameters.join(', ') || '—'}</td>
            <td>{tool.targetSystems.join(', ') || '—'}</td>
            <td>{tool.supportedTaskTypes.join(', ') || '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function VerifierTable({ capabilities }: { capabilities: VerifierCapability[] }) {
  if (capabilities.length === 0) {
    return <p>无</p>
  }
  return (
    <table className="koc-table">
      <thead>
        <tr>
          <th>节点</th>
          <th>类型</th>
          <th>说明</th>
        </tr>
      </thead>
      <tbody>
        {capabilities.map((cap) => (
          <tr key={cap.node}>
            <td className="koc-mono">{cap.node}</td>
            <td>{cap.type}</td>
            <td>{cap.description}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
