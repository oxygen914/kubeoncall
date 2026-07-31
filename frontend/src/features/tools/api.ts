import { api } from '@/api/client'

/**
 * Tools API module. Matches the /api/v1/tools read-only catalog contract:
 *
 *   all:      { data: ToolCatalogView, meta }
 *   planner:  { data: ToolView[], meta }
 *   executor: { data: ToolView[], meta }
 *   verifier: { data: VerifierCapability[], meta }
 */

export interface ToolView {
  name: string
  executorKind: string
  description: string
  readOnly: boolean
  requiresApproval: boolean
  supportedTaskTypes: string[]
  requiredParameters: string[]
  targetSystems: string[]
  inputSchema: Record<string, unknown> | null
}

export interface VerifierCapability {
  node: string
  type: string
  description: string
}

export interface ToolCatalogView {
  planner: ToolView[]
  executor: ToolView[]
  verifier: VerifierCapability[]
}

export function getToolCatalog(): Promise<ToolCatalogView> {
  return api.get<ToolCatalogView>('/api/v1/tools')
}
