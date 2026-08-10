import { describe, expect, it } from 'vitest'
import { formatAskAnswer } from '../answerFormatter'

describe('formatAskAnswer', () => {
  it('normalizes a legacy internal execution report into a Chinese conversation', () => {
    const raw = [
      'executionId=exe_1',
      'status=SUCCESS',
      "requestSummary=question='CPU', intent=diagnose-cpu-usage, confidence=LOW",
      'planSummary=METRIC@prometheus=FORBIDDEN: NAMESPACE_NOT_ALLOWED',
      'toolSummary={executorResultStatus=success}',
      'auditSummary=observations=[]',
    ].join('\n')

    const answer = formatAskAnswer(raw, '已有证据：3/3 Ready，平均 CPU 16.59%', {
      status: 'SUCCEEDED',
    } as never)

    expect(answer).toContain('研判已完成')
    expect(answer).toContain('3/3 Ready，平均 CPU 16.59%')
    expect(answer).toContain('跨 Namespace 指标仍受只读范围限制')
    expect(answer).toContain('未对集群进行任何变更')
    expect(answer).not.toContain('executionId=')
    expect(answer).not.toContain('planSummary=')
  })

  it('keeps an existing conversational answer unchanged', () => {
    const answer = '研判已完成。当前集群状态正常。'
    expect(formatAskAnswer(answer, '检查集群', undefined)).toBe(answer)
  })
})
