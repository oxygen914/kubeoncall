import { api } from '@/api/client'

export interface CreateAskExecutionRequest {
  question: string
  sessionId?: string
  alarmId?: string
  cluster?: string
  environment?: string
  namespace?: string
  resourceKind?: string
  resourceName?: string
  resourceUid?: string
}

export interface CreateAskExecutionResult {
  executionId: string
  taskId: string
  status: string
}

export function createAskExecution(
  request: CreateAskExecutionRequest,
  idempotencyKey: string,
): Promise<CreateAskExecutionResult> {
  return api.post<CreateAskExecutionResult>('/api/v1/executions', request, {
    idempotencyKey,
  })
}
