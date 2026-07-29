import { useMemo } from 'react'
import ReactEChartsCore from 'echarts-for-react/lib/core'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { useTheme } from '@/features/theme/themeContext'

echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])

const LIGHT_CHART_COLORS = {
  primary: '#2563eb',
  success: '#15803d',
  danger: '#dc2626',
  warning: '#d97706',
  neutral: '#64748b',
  grid: '#e2e8f0',
  text: '#5f6f85',
}

const EXECUTION_STATUS_ORDER = [
  'SUCCEEDED',
  'FAILED',
  'RUNNING',
  'WAITING_APPROVAL',
  'PENDING',
  'REJECTED',
  'CANCELLED',
]

export function ExecutionTrendChart({
  values,
  statusValues,
}: {
  values: Record<string, number>
  statusValues?: Record<string, Record<string, number>>
}) {
  const fallbackEntries = Object.entries(values)
  const statusBuckets = Object.keys(statusValues ?? {})
  const discoveredStatuses = Array.from(
    new Set(Object.values(statusValues ?? {}).flatMap((bucket) => Object.keys(bucket))),
  )
  const statuses = [
    ...EXECUTION_STATUS_ORDER.filter((status) => discoveredStatuses.includes(status)),
    ...discoveredStatuses
      .filter((status) => !EXECUTION_STATUS_ORDER.includes(status))
      .sort((a, b) => a.localeCompare(b)),
  ]
  const hasStatusTrend = statusBuckets.length > 0 && statuses.length > 0
  const buckets = hasStatusTrend ? statusBuckets : fallbackEntries.map(([bucket]) => bucket)
  const { theme } = useTheme()
  const colors = chartColors(theme)
  const option = {
    animationDuration: 180,
    color: [colors.primary],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: number) => `${value} 次`,
    },
    legend: {
      top: 0,
      right: 0,
      data: hasStatusTrend ? statuses : ['全部执行'],
      textStyle: { color: colors.text, fontSize: 12 },
    },
    grid: { left: 42, right: 16, top: 38, bottom: 36 },
    xAxis: {
      type: 'category',
      data: buckets.map(compactBucket),
      axisTick: { alignWithLabel: true },
      axisLabel: { color: colors.text, fontSize: 11, hideOverlap: true },
      axisLine: { lineStyle: { color: colors.grid } },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      name: '次数',
      nameTextStyle: { color: colors.text, fontSize: 11 },
      axisLabel: { color: colors.text, fontSize: 11 },
      splitLine: { lineStyle: { color: colors.grid, type: 'dashed' } },
    },
    series: hasStatusTrend
      ? statuses.map((status) => ({
          name: status,
          type: 'bar',
          stack: 'execution-status',
          barMaxWidth: 28,
          data: buckets.map((bucket) => statusValues?.[bucket]?.[status] ?? 0),
          itemStyle: { color: executionStatusColor(status, colors) },
        }))
      : [
          {
            name: '全部执行',
            type: 'bar',
            barMaxWidth: 28,
            data: fallbackEntries.map(([, count]) => count),
            itemStyle: { color: colors.primary, borderRadius: [3, 3, 0, 0] },
            emphasis: { itemStyle: { color: colors.primaryStrong } },
          },
        ],
  }

  if (buckets.length === 0) {
    return <p className="koc-overview-empty">当前窗口无执行趋势数据。</p>
  }

  return (
    <>
      <div
        className="koc-chart"
        role="img"
        aria-label={
          hasStatusTrend ? '执行状态堆叠时间趋势图，单位为次数' : '执行总量时间趋势图，单位为次数'
        }
      >
        <ReactEChartsCore
          echarts={echarts}
          option={option}
          opts={{ renderer: 'svg' }}
          style={{ height: 240 }}
        />
      </div>
      <table className="koc-visually-hidden">
        <caption>执行趋势数据</caption>
        {hasStatusTrend ? (
          <thead>
            <tr>
              <th>时间</th>
              {statuses.map((status) => (
                <th key={status}>{status}</th>
              ))}
            </tr>
          </thead>
        ) : null}
        <tbody>
          {buckets.map((bucket) => (
            <tr key={bucket}>
              <th>{bucket}</th>
              {hasStatusTrend ? (
                statuses.map((status) => (
                  <td key={status}>{statusValues?.[bucket]?.[status] ?? 0} 次</td>
                ))
              ) : (
                <td>{values[bucket] ?? 0} 次</td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

function executionStatusColor(status: string, colors: ReturnType<typeof chartColors>): string {
  if (status === 'SUCCEEDED') return colors.success
  if (status === 'FAILED' || status === 'REJECTED' || status === 'CANCELLED') return colors.danger
  if (status === 'RUNNING') return colors.primary
  if (status === 'WAITING_APPROVAL' || status === 'PENDING') return colors.warning
  return colors.neutral
}

export function FailureReasonChart({ values }: { values: Record<string, number> }) {
  const entries = Object.entries(values).sort((a, b) => b[1] - a[1])
  const { theme } = useTheme()
  const colors = chartColors(theme)
  const option = useMemo(
    () => ({
      animationDuration: 180,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        valueFormatter: (value: number) => `${value} 次`,
      },
      grid: { left: 118, right: 28, top: 8, bottom: 22 },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: colors.text, fontSize: 11 },
        splitLine: { lineStyle: { color: colors.grid, type: 'dashed' } },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: entries.map(([reason]) => reason),
        axisLabel: {
          color: colors.text,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: 11,
          width: 104,
          overflow: 'truncate',
        },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      series: [
        {
          name: '失败次数',
          type: 'bar',
          barMaxWidth: 18,
          data: entries.map(([, count]) => count),
          label: { show: true, position: 'right', color: colors.text, fontSize: 11 },
          itemStyle: { color: colors.danger, borderRadius: [0, 3, 3, 0] },
        },
      ],
    }),
    [colors, entries],
  )

  if (entries.length === 0) {
    return <p className="koc-overview-empty">当前窗口无失败执行。</p>
  }

  const height = Math.max(150, entries.length * 36 + 36)
  return (
    <>
      <div className="koc-chart" role="img" aria-label="失败原因水平条形图，单位为次数">
        <ReactEChartsCore
          echarts={echarts}
          option={option}
          opts={{ renderer: 'svg' }}
          style={{ height }}
        />
      </div>
      <table className="koc-visually-hidden">
        <caption>失败原因数据</caption>
        <tbody>
          {entries.map(([reason, count]) => (
            <tr key={reason}>
              <th>{reason}</th>
              <td>{count} 次</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

export function ExecutionStatusDistribution({ values }: { values: Record<string, number> }) {
  const entries = Object.entries(values).filter(([, count]) => count > 0)
  const total = entries.reduce((sum, [, count]) => sum + count, 0)
  if (total === 0) {
    return <p className="koc-overview-empty">当前窗口无执行状态数据。</p>
  }

  return (
    <div className="koc-status-distribution">
      <div
        className="koc-status-distribution__bar"
        role="img"
        aria-label={entries.map(([status, count]) => `${status} ${count} 次`).join('，')}
      >
        {entries.map(([status, count]) => (
          <span
            key={status}
            data-status={status}
            style={{ width: `${(count / total) * 100}%` }}
            title={`${status}: ${count} 次`}
          />
        ))}
      </div>
      <ul className="koc-status-distribution__legend">
        {entries.map(([status, count]) => (
          <li key={status}>
            <span data-status={status} aria-hidden="true" />
            <code>{status}</code>
            <strong>{count}</strong>
          </li>
        ))}
      </ul>
    </div>
  )
}

function compactBucket(bucket: string): string {
  const match = bucket.match(/(\d{2}:\d{2})$/)
  return match?.[1] ?? bucket
}

function chartColors(theme: 'light' | 'dark') {
  if (theme === 'dark') {
    return {
      ...LIGHT_CHART_COLORS,
      primary: '#60a5fa',
      primaryStrong: '#93c5fd',
      danger: '#f87171',
      grid: '#334155',
      text: '#cbd5e1',
    }
  }
  return { ...LIGHT_CHART_COLORS, primaryStrong: '#1d4ed8' }
}
