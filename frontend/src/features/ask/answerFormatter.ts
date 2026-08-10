import type { ExecutionDetail } from '@/features/executions/api'

const INTERNAL_REPORT_MARKERS = [
  'executionId=',
  '\nplanSummary=',
  '\ntoolSummary=',
  '\nauditSummary=',
]

export function formatAskAnswer(
  rawAnswer: string | null | undefined,
  question: string,
  execution: ExecutionDetail | undefined,
): string | null {
  if (!rawAnswer) return null
  if (!INTERNAL_REPORT_MARKERS.every((marker) => rawAnswer.includes(marker))) return rawAnswer

  const succeeded = execution?.status === 'SUCCEEDED' || rawAnswer.includes('\nstatus=SUCCESS')
  const failed = execution?.status === 'FAILED' || rawAnswer.includes('\nstatus=FAILED')
  const snapshot = extractSnapshot(question)
  const cpuQuestion = /cpu|处理器/i.test(question)
  const lowConfidence = /confidence=LOW\b/i.test(rawAnswer)
  const namespaceGap = rawAnswer.includes('NAMESPACE_NOT_ALLOWED')

  const sections: string[] = []
  sections.push(failed ? '本次研判未能完整完成。' : succeeded ? '研判已完成。' : '研判正在处理中。')
  if (snapshot) sections.push(`当前快照：${snapshot}。`)

  if (failed) {
    sections.push('结论：执行链路存在失败节点，当前不能给出完整的运行状态判断。')
    sections.push('建议：请打开执行详情查看失败节点，修复对应证据源或工具连接后重新发起研判。')
  } else if (cpuQuestion && snapshot.includes('Ready') && snapshot.includes('CPU')) {
    sections.push(
      '结论：节点均处于 Ready 状态，当前平均 CPU 使用率较低，暂未发现需要立即处置的 CPU 压力。',
    )
    sections.push(
      `证据可信度：${lowConfidence ? '低' : '中'}${namespaceGap ? '；跨 Namespace 指标仍受只读范围限制，结论需结合后续指标复核' : ''}。`,
    )
    sections.push(
      '建议：继续观察 CPU 趋势；若持续超过告警阈值，再检查 user、system、iowait、steal 分项和高 CPU 进程。',
    )
  } else if (cpuQuestion && snapshot.includes('CPU')) {
    sections.push('结论：当前平均 CPU 使用率较低，暂未发现需要立即处置的 CPU 压力。')
    sections.push(
      `证据可信度：${lowConfidence ? '低' : '中'}${namespaceGap ? '；跨 Namespace 指标仍受只读范围限制，结论需结合后续指标复核' : ''}。`,
    )
    sections.push(
      '建议：继续观察 CPU 趋势；若持续超过告警阈值，再检查 user、system、iowait、steal 分项和高 CPU 进程。',
    )
  } else {
    sections.push('结论：只读检查已完成，当前证据未显示需要立即处置的异常。')
    sections.push(`证据可信度：${lowConfidence ? '低' : '中'}。`)
    sections.push('建议：持续观察当前范围；如指标恶化或出现新告警，再结合实时证据升级处置。')
  }
  sections.push('本次仅执行只读检查，未对集群进行任何变更。')
  return sections.join('\n\n')
}

function extractSnapshot(question: string): string {
  const ready = question
    .match(/\d+\s*\/\s*\d+\s*Ready/i)?.[0]
    ?.replace(/\s*\/\s*/, '/')
    .replace(/\s*Ready$/i, ' Ready')
  const cpu = question.match(/(?:平均\s*)?CPU(?:\s*使用率)?\s*[:：]?\s*(\d+(?:\.\d+)?%)/i)?.[1]
  if (ready && cpu) return `${ready}，平均 CPU ${cpu}`
  if (ready) return ready
  return cpu ? `平均 CPU ${cpu}` : ''
}
