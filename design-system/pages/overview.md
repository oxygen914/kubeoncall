# Overview page rules

This page extends `design-system/MASTER.md`.

## Information hierarchy

1. **Page context:** title, current scope, time range, refresh controls, last update.
2. **Current risk summary:** cluster health, P1/P2 alarms, abnormal workloads, pending approvals,
   running executions, recent failures.
3. **Monitoring posture:** cluster snapshot/trend region and active alarm queue.
4. **Response handling:** execution trend, status distribution, failure reasons, pending approvals,
   and recent Sandbox runs.
5. **AI operations insights:** evidence-backed attention items with safe drill-down actions.

## Grid

- Use a 12-column desktop grid.
- Risk summary: six compact metrics, each 2 columns at 1440px+; three per row at narrower widths.
- Monitoring posture: 7 columns for cluster health, 5 for active alerts.
- Response: 7 columns for execution trend/status, 5 for failure reasons/work queues.
- AI insights: full width; items use a readable split between evidence and recommended action.

## Data mapping and honest fallbacks

| UI area                           | Existing source                   | Fallback                                            |
| --------------------------------- | --------------------------------- | --------------------------------------------------- |
| Alarm, approval, execution totals | `/api/v1/overview`                | Page-level error with retry                         |
| Cluster and workload health       | Existing monitoring endpoints     | Explicit data-source unavailable state              |
| Active alarm queue                | Existing alarms list endpoint     | Permission or empty state                           |
| Pending approval queue            | Existing approvals list endpoint  | Permission or empty state                           |
| Recent execution queue            | Existing executions list endpoint | Permission or empty state                           |
| Recent Sandbox runs               | Existing Sandbox runs endpoint    | Permission or empty state                           |
| Previous-period comparison        | Not currently supplied            | “同期基线未提供”; never fabricate                   |
| Cluster health history            | Not currently supplied            | Current snapshot plus missing-history note          |
| AI insight backend                | Not currently supplied            | Deterministic, clearly labelled rule-based insights |

Environment and Namespace selectors may be rendered as reserved local controls until a shared
backend context contract exists. They must not silently claim to filter aggregate metrics.

## Metric semantics

- Cluster health: healthy only when all known nodes are Ready; degraded for unknown state; critical
  when NotReady nodes exist.
- P1/P2 alarms: danger when P1 exists, warning when only P2 exists, neutral when zero.
- Abnormal workload: Pending + Failed + Unknown Pod phases from monitoring data.
- Pending approval: warning above zero.
- Running execution: blue informational state.
- Recent failure: danger above zero.

## AI insight safety

- Every insight includes risk, evidence, likely cause phrased as a hypothesis, related alarm/change
  availability, and a recommended next action.
- “查看分析” may route to the existing Ask page.
- “创建处置任务” routes to an existing alarm/detail workflow when possible. It is disabled when no
  approved creation path exists.
- No mutation is sent from the Overview page.
