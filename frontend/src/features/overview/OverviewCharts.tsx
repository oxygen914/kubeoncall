import { useMemo } from 'react'
import ReactEChartsCore from 'echarts-for-react/lib/core'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])

const CHART_COLORS = {
  primary: '#2563eb',
  success: '#15803d',
  danger: '#dc2626',
  warning: '#d97706',
  neutral: '#64748b',
  grid: '#e2e8f0',
  text: '#5f6f85',
}

export function ExecutionTrendChart({ values }: { values: Record<string, number> }) {
  const entries = Object.entries(values)
  const option = useMemo(
    () => ({
      animationDuration: 180,
      color: [CHART_COLORS.primary],
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: number) => `${value} 次`,
      },
      legend: {
        top: 0,
        right: 0,
        data: ['全部执行'],
        textStyle: { color: CHART_COLORS.text, fontSize: 12 },
      },
      grid: { left: 42, right: 16, top: 38, bottom: 36 },
      xAxis: {
        type: 'category',
        data: entries.map(([bucket]) => compactBucket(bucket)),
        axisTick: { alignWithLabel: true },
        axisLabel: { color: CHART_COLORS.text, fontSize: 11, hideOverlap: true },
        axisLine: { lineStyle: { color: CHART_COLORS.grid } },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        name: '次数',
        nameTextStyle: { color: CHART_COLORS.text, fontSize: 11 },
        axisLabel: { color: CHART_COLORS.text, fontSize: 11 },
        splitLine: { lineStyle: { color: CHART_COLORS.grid, type: 'dashed' } },
      },
      series: [
        {
          name: '全部执行',
          type: 'bar',
          barMaxWidth: 28,
          data: entries.map(([, count]) => count),
          itemStyle: { color: CHART_COLORS.primary, borderRadius: [3, 3, 0, 0] },
          emphasis: { itemStyle: { color: '#1d4ed8' } },
        },
      ],
    }),
    [entries],
  )

  if (entries.length === 0) {
    return <p className="koc-overview-empty">当前窗口无执行趋势数据。</p>
  }

  return (
    <>
      <div className="koc-chart" role="img" aria-label="执行总量时间趋势图，单位为次数">
        <ReactEChartsCore
          echarts={echarts}
          option={option}
          opts={{ renderer: 'svg' }}
          style={{ height: 240 }}
        />
      </div>
      <table className="koc-visually-hidden">
        <caption>执行趋势数据</caption>
        <tbody>
          {entries.map(([bucket, count]) => (
            <tr key={bucket}>
              <th>{bucket}</th>
              <td>{count} 次</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

export function FailureReasonChart({ values }: { values: Record<string, number> }) {
  const entries = Object.entries(values).sort((a, b) => b[1] - a[1])
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
        axisLabel: { color: CHART_COLORS.text, fontSize: 11 },
        splitLine: { lineStyle: { color: CHART_COLORS.grid, type: 'dashed' } },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: entries.map(([reason]) => reason),
        axisLabel: {
          color: CHART_COLORS.text,
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
          label: { show: true, position: 'right', color: CHART_COLORS.text, fontSize: 11 },
          itemStyle: { color: CHART_COLORS.danger, borderRadius: [0, 3, 3, 0] },
        },
      ],
    }),
    [entries],
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
