const EXECUTION_STATUS_ORDER = [
  'SUCCEEDED',
  'FAILED',
  'RUNNING',
  'WAITING_APPROVAL',
  'PENDING',
  'REJECTED',
  'CANCELLED',
]

const CHART_WIDTH = 720
const CHART_HEIGHT = 240
const CHART_MARGIN = { top: 34, right: 14, bottom: 42, left: 44 }

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

  if (buckets.length === 0) {
    return <p className="koc-overview-empty">当前窗口无执行趋势数据。</p>
  }

  const bucketValues = buckets.map((bucket) =>
    hasStatusTrend
      ? statuses.reduce((sum, status) => sum + (statusValues?.[bucket]?.[status] ?? 0), 0)
      : (values[bucket] ?? 0),
  )
  const maxValue = Math.max(...bucketValues, 1)
  const plotWidth = CHART_WIDTH - CHART_MARGIN.left - CHART_MARGIN.right
  const plotHeight = CHART_HEIGHT - CHART_MARGIN.top - CHART_MARGIN.bottom
  const bucketWidth = plotWidth / buckets.length
  const barWidth = Math.min(30, Math.max(6, bucketWidth * 0.58))
  const ticks = buildTicks(maxValue)
  const labelStride = Math.max(1, Math.ceil(buckets.length / 8))

  return (
    <>
      {hasStatusTrend ? (
        <ul className="koc-chart-legend" aria-label="执行状态图例">
          {statuses.map((status) => (
            <li key={status}>
              <span data-status={status} aria-hidden="true" />
              <code>{status}</code>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="koc-chart">
        <svg
          className="koc-trend-chart"
          viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
          role="img"
          aria-labelledby="execution-trend-title execution-trend-description"
          preserveAspectRatio="xMidYMid meet"
        >
          <title id="execution-trend-title">
            {hasStatusTrend ? '执行状态堆叠时间趋势图' : '执行总量时间趋势图'}
          </title>
          <desc id="execution-trend-description">
            横轴为时间，纵轴为执行次数；精确数据同时提供在图表后的数据表中。
          </desc>

          {ticks.map((tick) => {
            const y =
              CHART_MARGIN.top + plotHeight - (Math.min(tick, maxValue) / maxValue) * plotHeight
            return (
              <g className="koc-trend-chart__grid" key={tick}>
                <line x1={CHART_MARGIN.left} x2={CHART_WIDTH - CHART_MARGIN.right} y1={y} y2={y} />
                <text x={CHART_MARGIN.left - 8} y={y + 4} textAnchor="end">
                  {tick}
                </text>
              </g>
            )
          })}

          {buckets.map((bucket, index) => {
            const centerX = CHART_MARGIN.left + bucketWidth * index + bucketWidth / 2
            let stackedValue = 0
            const description = hasStatusTrend
              ? statuses
                  .map((status) => `${status} ${statusValues?.[bucket]?.[status] ?? 0} 次`)
                  .join('，')
              : `全部执行 ${values[bucket] ?? 0} 次`
            return (
              <g key={bucket}>
                <title>
                  {bucket}：{description}
                </title>
                {(hasStatusTrend ? statuses : ['ALL']).map((status) => {
                  const count =
                    status === 'ALL'
                      ? (values[bucket] ?? 0)
                      : (statusValues?.[bucket]?.[status] ?? 0)
                  const height = (count / maxValue) * plotHeight
                  const y =
                    CHART_MARGIN.top + plotHeight - ((stackedValue + count) / maxValue) * plotHeight
                  stackedValue += count
                  return (
                    <rect
                      key={status}
                      className="koc-trend-chart__bar"
                      data-status={status}
                      x={centerX - barWidth / 2}
                      y={y}
                      width={barWidth}
                      height={Math.max(height, count > 0 ? 1 : 0)}
                      rx={status === statuses.at(-1) || status === 'ALL' ? 2 : 0}
                    />
                  )
                })}
                {index % labelStride === 0 || index === buckets.length - 1 ? (
                  <text
                    className="koc-trend-chart__axis-label"
                    x={centerX}
                    y={CHART_HEIGHT - 14}
                    textAnchor="middle"
                  >
                    {compactBucket(bucket)}
                  </text>
                ) : null}
              </g>
            )
          })}
        </svg>
      </div>
      <table className="koc-visually-hidden">
        <caption>执行趋势数据</caption>
        <thead>
          <tr>
            <th>时间</th>
            {hasStatusTrend ? (
              statuses.map((status) => <th key={status}>{status}</th>)
            ) : (
              <th>全部执行</th>
            )}
          </tr>
        </thead>
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

export function FailureReasonChart({ values }: { values: Record<string, number> }) {
  const entries = Object.entries(values).sort((a, b) => b[1] - a[1])

  if (entries.length === 0) {
    return <p className="koc-overview-empty">当前窗口无失败执行。</p>
  }

  const maxValue = Math.max(...entries.map(([, count]) => count), 1)
  return (
    <>
      <ol className="koc-failure-bars" aria-label="失败原因及次数">
        {entries.map(([reason, count]) => (
          <li key={reason}>
            <div>
              <code title={reason}>{reason}</code>
              <strong>{count} 次</strong>
            </div>
            <span aria-hidden="true">
              <i style={{ width: `${(count / maxValue) * 100}%` }} />
            </span>
          </li>
        ))}
      </ol>
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

function buildTicks(maxValue: number): number[] {
  const tickCount = Math.min(4, Math.max(1, Math.ceil(maxValue)))
  return Array.from({ length: tickCount + 1 }, (_, index) =>
    Math.round((maxValue * index) / tickCount),
  ).filter((value, index, values) => index === 0 || value !== values[index - 1])
}

function compactBucket(bucket: string): string {
  const match = bucket.match(/(\d{2}:\d{2})$/)
  return match?.[1] ?? bucket
}
