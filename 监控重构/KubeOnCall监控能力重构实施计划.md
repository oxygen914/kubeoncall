# KubeOnCall 监控能力重构实施计划

> 版本：v1.0
> 日期：2026-07-28
> 当前状态：M0～M3 已完成，M4 真实 Kubernetes 生产验收待执行
> 范围：CPU 使用率、Pod 阶段、Node Ready、同集群节点态势
> 非本期范围：GPU 指标、多集群控制面、Grafana 全量替代

## 1. 背景

KubeOnCall 当前已经具备 Node Exporter → Prometheus → Alertmanager → KubeOnCall
的单节点告警闭环，但 Console 首页仍以告警、审批和执行统计为主。Prometheus 查询能力
仅服务于 Agent 工具执行，尚未形成面向 Console 的稳定、受控监控读取接口。

重构前的主要缺口：

- CPU 指标已经采集，但仅覆盖一个 `local/docker-host` 测试节点，Console 无查询入口。
- 未接入 kube-state-metrics，无法读取 Pod phase 和 Kubernetes Node Ready。
- 没有同集群节点列表，无法判断单节点故障与集群范围故障。
- 现有 Grafana 面板偏 KubeOnCall 应用自身可观测性，不是 Kubernetes 运维态势页。

## 2. 产品定位与边界

KubeOnCall 定位为“面向 Kubernetes 的 AI 告警诊断与处置平台”，监控数据用于：

1. 为告警提供实时上下文。
2. 判断故障影响范围。
3. 支持处置前后状态对比。
4. 为 Planner、Executor、Verifier 提供可信只读事实。

本次不会：

- 自建时序数据库或替代 Prometheus。
- 复制 Grafana 的自由 PromQL、复杂大盘编辑和长期分析能力。
- 允许浏览器直接访问 Prometheus。
- 将数据源缺失解释成“零异常”或“集群健康”。
- 在没有 GPU 使用场景时引入 DCGM Exporter。

## 3. 能力范围与优先级

| 能力 | 优先级 | 本期交付 |
|---|---|---|
| Pod 阶段状态 | P0 | Running/Pending/Failed/Unknown 汇总、异常 Pod、重启次数 |
| Node Ready 状态 | P0 | Ready/NotReady/Unknown、节点总数和健康摘要 |
| 同集群其他节点 | P0 | 节点列表、横向 CPU/内存/Pod 数对比 |
| CPU 使用率 | P1 | 当前值、15m/1h/6h 短期趋势 |
| GPU 使用率 | P2/P3 | 不实现，仅保留后续扩展点 |

## 4. 目标架构

```text
Node Exporter ─────────────┐
                          │
kube-state-metrics ───────┼─> Prometheus
                          │       │
KubeOnCall metrics ───────┘       │ 固定 PromQL 模板
                                  v
                    MonitoringQueryService
                                  │
                      /api/v1/monitoring/*
                                  │
                                  v
                     Console 集群态势页
                                  │
                                  └─> Grafana 深度趋势链接
```

设计原则：

- 后端使用固定 PromQL 模板，只允许有限时间窗口和分页上限。
- 复用 `dashboard:read` 权限，前后端同时校验。
- API 返回数据源可用性，区分 `0`、`UNKNOWN` 和 `UNAVAILABLE`。
- 节点和 Pod 查询只返回前端所需字段，不透传 Prometheus 原始响应。
- Prometheus 不可用时返回标准 `503 SERVICE_UNAVAILABLE`。
- kube-state-metrics 未接入时，CPU 仍可展示，但 Pod/Ready 明确标记不可用。

## 5. API 契约

### 5.1 集群列表

`GET /api/v1/monitoring/clusters`

返回 Prometheus 当前可发现的集群及数据源能力：

```json
{
  "name": "local",
  "nodeMetricsAvailable": true,
  "kubernetesStateAvailable": false
}
```

### 5.2 集群摘要

`GET /api/v1/monitoring/summary?cluster=local`

返回：

- 节点总数、Ready、NotReady、Unknown。
- Pod 总数和各 phase 数量。
- node-exporter 与 kube-state-metrics 数据源是否可用。
- 本次查询时间。

### 5.3 节点列表

`GET /api/v1/monitoring/nodes?cluster=local`

每个节点返回：

- `name`
- `ready`: `READY | NOT_READY | UNKNOWN`
- `exporterUp`
- `cpuUsagePercent`
- `memoryUsagePercent`
- `podCount`

节点集合取 Node Exporter 与 kube-state-metrics 结果的并集，避免 exporter 故障后节点从
列表中消失。

### 5.4 Pod 列表

`GET /api/v1/monitoring/pods?cluster=local&phase=Pending&namespace=default&limit=100`

每个 Pod 返回：

- `namespace`
- `name`
- `node`
- `phase`
- `restartCount`

默认优先展示非 Running Pod，`limit` 最大为 500。

### 5.5 节点 CPU 趋势

`GET /api/v1/monitoring/nodes/{node}/cpu?cluster=local&window=15m`

允许窗口：`15m | 1h | 6h`。返回时间点和 CPU 百分比，不接受任意 PromQL。

## 6. Prometheus 指标契约

### Node Exporter

- `up{job="node-exporter"}`
- `node_cpu_seconds_total`
- `node_memory_MemAvailable_bytes`
- `node_memory_MemTotal_bytes`

### kube-state-metrics

- `kube_node_status_condition`
- `kube_pod_status_phase`
- `kube_pod_info`
- `kube_pod_container_status_restarts_total`

生产环境必须将指标标签归一为：

- `cluster`: 所有上述指标都必须带稳定集群标识。
- `node`: Node Exporter、`kube_node_status_condition` 和 `kube_pod_info` 必须带
  Kubernetes Node 名称。

标准 `kube_pod_status_phase` 和 `kube_pod_container_status_restarts_total` 不要求携带
`node`；后端通过 `cluster/namespace/pod` 与 `kube_pod_info` 关联节点。如果现有
Prometheus 使用其他标签，需要在 scrape/relabel 阶段完成归一化，不在业务代码中维护多套
标签猜测逻辑。

## 7. 前端交付

新增“集群态势”导航和页面：

1. 集群选择器和刷新频率。
2. Ready/NotReady、Pod、异常 Pod、平均 CPU 摘要卡片。
3. 节点表格：Ready、CPU、内存、Pod 数、Exporter。
4. Pod phase 分布和异常 Pod 表格。
5. 选中节点后的 CPU 短期趋势。
6. 数据源缺失提示和 Grafana 跳转。

页面每 15 秒刷新摘要、节点和 Pod，趋势数据按节点和窗口缓存。数据源缺失时显示配置指引，
不显示误导性的绿色健康状态。

## 8. 告警规则补齐

在已有节点规则基础上新增：

- `NodeNotReady`：Node Ready condition 持续为 false。
- `PodPendingTooLong`：Pod 持续 Pending。

规则即使在本地缺少 kube-state-metrics 时也应保持合法且不产生假告警；通过 promtool
规则单测覆盖 firing 条件。

## 9. Kubernetes 接入

KubeOnCall Helm Chart 不直接捆绑完整监控栈。生产接入采用外部 Prometheus：

1. 以 DaemonSet 部署 Node Exporter，采集所有 Kubernetes Node。
2. 部署 kube-state-metrics。
3. Prometheus/Prometheus Operator 发现两类 target。
4. 通过 target relabel 写入 `cluster`，并为 Node Exporter 归一出 `node`。
5. 将 `PROMETHEUS_TOOL_ENDPOINT` 指向 Prometheus HTTP API。

仓库提供指标接入指南、查询自检命令和验收清单，不在应用 Chart 内重复安装集群级监控组件。

## 10. 实施里程碑

### M0：计划与契约

- [x] 明确产品边界。
- [x] 定义数据源和 API。
- [x] 定义降级语义。

### M1：后端监控读取层

- [x] Prometheus 固定模板客户端。
- [x] 聚合查询服务。
- [x] `/api/v1/monitoring/*` Controller。
- [x] 权限、参数和错误契约测试。
- [x] Prometheus 响应解析和聚合单测。

### M2：Console 集群态势页

- [x] API 类型和请求封装。
- [x] 页面、路由和导航。
- [x] 摘要、节点、Pod、CPU 趋势。
- [x] 缺数据源状态和页面测试。

### M3：告警与部署接入

- [x] NodeNotReady 和 PodPending 规则。
- [x] promtool 规则单测。
- [x] Kubernetes 指标接入指南。
- [x] 本地单节点 CPU 联调。
- [x] Minikube 单节点 kube-state-metrics、Node Ready、Pod phase 和重启次数验收。

### M4：生产验收

- [ ] 真实 Kubernetes 集群多节点验收。
- [ ] 人为 cordon/drain 或停止 kubelet 验证 Node Ready。
- [ ] Pending/Failed Pod 场景验证。
- [ ] 处置前后 CPU 趋势验证。
- [ ] Prometheus 超时、断连和大规模 Pod 数据测试。

## 11. 自动化验收

后端：

- Spotless。
- 监控包单元测试和 Controller 契约测试。
- 全量 Maven 测试。

前端：

- Prettier、ESLint、TypeScript。
- Vitest API/Page 测试。
- 生产构建。

部署：

- `promtool check rules`。
- `promtool test rules`。
- Helm lint/template。

## 12. 完成定义

代码完成必须同时满足：

- Console 能展示当前 Prometheus 中所有同集群节点。
- CPU 指标存在时能展示当前值和短期趋势。
- kube-state-metrics 存在时能展示 Node Ready、Pod phase 和重启次数。
- kube-state-metrics 不存在时明确提示“数据源未接入”。
- 前端不包含任意 PromQL 输入。
- 浏览器不直接访问 Prometheus。
- 所有接口受 `dashboard:read` 保护。
- 自动化检查通过。

生产完成还必须满足 M4 的真实 Kubernetes 验收；本地 Docker 单节点通过不等于生产完成。

## 13. 2026-07-28 实施记录

已完成：

- 新增 5 个受 `dashboard:read` 保护的监控读取接口，并更新 OpenAPI/前端类型。
- Console 新增“集群态势”导航，支持节点横向对比、CPU 当前值及 15m/1h/6h 趋势、
  Pod phase 筛选和数据源缺失提示。
- 新增 `NodeNotReady`、`PodPendingTooLong` 告警及 promtool firing 单测。
- 新增 `kubeoncall-monitoring` 单节点 Minikube 接入脚本、最小只读 kube-state-metrics
  RBAC/Deployment 和 Prometheus Compose 覆盖配置。
- 本地联调发现 `local/kubeoncall-monitoring`：Node Exporter 与 kube-state-metrics 均为
  `UP`，Node Ready、Pod phase、Pod 所在节点和重启次数可读。
- Console 收敛为单集群视图，常驻展示两类数据源状态和 Pod 阶段分布；数据源缺失时仍保留
  `UNKNOWN/UNAVAILABLE` 降级语义。

自动化结果：

- 后端全量测试：774 个通过，0 失败。
- 前端全量测试：26 个测试文件、67 个测试全部通过；Lint、构建与本轮修改文件的格式检查均通过。
- Prometheus：23 条规则校验通过，规则单测通过。
- Helm lint/template 与 `git diff --check` 通过。

仍待真实环境完成：

- 生产/测试集群的多节点 Node Exporter DaemonSet 与 kube-state-metrics 接入。
- 同集群多节点 Node Ready、Pod phase、重启次数真实性对照。
- NodeNotReady/PodPending 告警 firing/resolved 演练。
- Prometheus 故障、大规模 Pod 和生产性能验收。
