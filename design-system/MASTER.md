# KubeOnCall Design System

## Product model

KubeOnCall is an **Enterprise Observability Command Center** for Kubernetes operations and
incident response. The interface optimizes for continuous monitoring, fast risk triage, safe
approval, and traceable execution. It is an application console, not a SaaS marketing dashboard.

This system was synthesized with `ui-ux-pro-max` 2.11.0 using product, style, color, chart, UX,
icon, and React-stack searches. The adopted matches are:

- Data-Dense Dashboard
- Real-Time Monitoring
- Status Page / Incident Management
- Trust & Authority
- Drill-Down Analytics
- Accessible & Ethical

Rejected generator matches include Exaggerated Minimalism, green as the primary brand color,
glassmorphism, AI-purple gradients, dark-first presentation, pulsing alerts, and landing-page CTA
patterns. They conflict with the product context and the explicit operational constraints.

## Design principles

1. **Risk before activity.** P1/P2 incidents, unhealthy workloads, pending approvals, and failed
   executions appear before general throughput.
2. **Scope is always visible.** Cluster, environment, Namespace, time range, refresh state, and
   last-updated time remain near the working context.
3. **Summary leads to evidence.** Metrics and charts provide drill-down links to alarms,
   monitoring, approvals, executions, and Sandbox runs.
4. **Truthful degradation.** Missing historical or cross-domain data is labelled unavailable.
   Never synthesize a trend, owner, change event, or backend capability.
5. **Safe AI assistance.** AI insights cite current data and route operators into existing detail,
   approval, or confirmation flows. They never trigger high-risk actions directly.
6. **Dense, not cramped.** Use a 12-column grid, compact panels, 36–40px table rows, and short
   labels while preserving readable grouping and keyboard targets.

## Foundations

### Color

| Token                 | Light value | Purpose                              |
| --------------------- | ----------- | ------------------------------------ |
| `--koc-bg`            | `#f4f7fb`   | Application canvas                   |
| `--koc-surface`       | `#ffffff`   | Panels and navigation                |
| `--koc-surface-muted` | `#f1f5f9`   | Secondary controls and hover         |
| `--koc-border`        | `#dbe3ee`   | Default dividers and panel borders   |
| `--koc-border-strong` | `#c6d1df`   | Selected and structural borders      |
| `--koc-text`          | `#0f172a`   | Primary text                         |
| `--koc-text-muted`    | `#5f6f85`   | Secondary text                       |
| `--koc-primary-600`   | `#2563eb`   | Primary actions and navigation       |
| `--koc-danger`        | `#dc2626`   | P0/P1, failures, destructive actions |
| `--koc-warning`       | `#d97706`   | P2, degraded, waiting                |
| `--koc-caution`       | `#ca8a04`   | P3 and attention                     |
| `--koc-success`       | `#15803d`   | Healthy and succeeded                |

Blue is the only general accent. Red, orange, yellow, and green are semantic and must not decorate
unrelated modules. Status must also include text, icons, line styles, or patterns.

Dark-theme values may override the same semantic tokens later. This delivery is light-first and
must not switch automatically based on OS preference.

### Typography

- Chinese UI and body: system sans stack (`-apple-system`, `Segoe UI`, `PingFang SC`,
  `Microsoft YaHei`, sans-serif).
- Default body: 14px / 1.5.
- Dense metadata: 12–13px, never below 12px.
- Page title: 22–24px, 650 weight.
- Section title: 15–16px, 600 weight.
- Technical identifiers (Pod, Namespace, error code, HTTP status, execution ID): system monospace.
- Use tabular numerals for metrics, durations, percentages, and timestamps.

### Shape and elevation

- Control radius: 5–6px.
- Panel radius: 8px.
- Large decorative card radii are prohibited.
- Hierarchy comes from borders, surface changes, dividers, and spacing.
- Shadows are reserved for overlays and menus; normal panels use no shadow.

### Spacing and layout

- Base unit: 4px.
- Dense scale: 4, 8, 12, 16, 20, 24, 32.
- Sidebar: 232px expanded, 72px collapsed.
- Top bar: 56px.
- Page maximum: none; content uses the available workspace.
- Desktop grid: 12 columns, 12px gap at 1280px, 16px gap at 1440px and above.
- Panel padding: 14–16px. Table rows: 36–40px.
- At widths below 1180px, secondary panels stack and the navigation may collapse.

## Components

### Application shell

- Brand area and user area remain fixed; only the grouped navigation scrolls.
- Navigation groups: 监控中心, 响应处置, AI 助手, 平台治理.
- The active route uses a left rail, stronger text/icon color, and tinted background. Background
  alone is never the selected indicator.
- Collapsed navigation preserves SVG icons, tooltips, `aria-label`, and `aria-current`.
- A skip link targets `#main-content`.

### Top bar and context controls

- Reserve cluster, environment, Namespace, global search/command, time range, auto-refresh,
  notifications, and user actions.
- Controls without backend scope support are explicitly presented as local/static context and do
  not alter API requests.
- Icon-only controls require both tooltip text and `aria-label`.

### Panels and metrics

- Metric cards are compact and clickable only when a real destination exists.
- Each metric contains label, value, semantic state, comparison text, and a sparkline or an
  explicit “no baseline” placeholder.
- Panels use a consistent header with title, explanatory metadata, and optional drill-down action.
- Loading, empty, error, disabled, and no-permission states occupy the same layout region to avoid
  cumulative layout shift.

### Charts

- Reuse ECharts already installed in the project.
- Time series: primary blue; anomalies use red markers plus labels.
- Execution status: SUCCEEDED green, FAILED red, RUNNING blue, WAITING orange, other states slate.
- Trends include tooltip, legend, axis/unit labels, and a textual summary or table fallback.
- Fewer than four time points should be shown as actual entries or bars, not smoothed into a
  misleading curve.
- Failure reasons use descending horizontal bars with visible values; do not pad to Top 5.

### Interaction and accessibility

- Keyboard order follows visual order. All custom clickable rows support Enter and Space.
- Focus ring: 2px primary blue with 2px offset.
- Minimum pointer target is 36px for dense desktop controls; isolated icon buttons are 40px.
- Hover is supplementary; focus, selected, loading, disabled, and error states remain visible.
- Respect `prefers-reduced-motion`. Transitions are 120–180ms and limited to color, border,
  opacity, and transform.
- Long table values use ellipsis with a `title` tooltip or an accessible expansion path.
- Errors use `role="alert"` and provide a retry path where possible.

## Anti-patterns

- Purple/pink gradients, glassmorphism, neon, marketing illustration, and decorative AI glow.
- Card soup: one oversized card for every value.
- Status conveyed by color alone.
- Fake historical trends, fake owners, fake changes, or invented APIs.
- Unbounded auto-refresh, blinking alerts, count-up animations, and attention-seeking motion.
- Direct high-risk AI execution that bypasses approval or confirmation.
