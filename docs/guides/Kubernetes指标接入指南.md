# KubeOnCall Kubernetes 指标接入指南

## 1. 适用范围

本指南用于让 KubeOnCall 集群态势页获得真实 Kubernetes 数据：

- 所有 Node 的 CPU、内存和 Exporter 状态。
- Node Ready/NotReady。
- Pod phase、所在节点和容器重启次数。

KubeOnCall 不安装和维护完整监控栈。生产环境应复用平台已有 Prometheus；Node Exporter
和 kube-state-metrics 由集群监控管理员统一部署。

## 2. 必需组件

| 组件 | 用途 | 关键指标 |
|---|---|---|
| Prometheus | 指标抓取、存储和查询 | Prometheus HTTP API |
| Node Exporter | 每台节点的主机指标 | `node_cpu_seconds_total`、`node_memory_*` |
| kube-state-metrics | Kubernetes 对象状态 | `kube_node_status_condition`、`kube_pod_status_phase` |

Node Exporter 必须以 DaemonSet 或平台等价方式覆盖所有目标节点。单个 Docker
Node Exporter 只能用于本地链路演练，不能代表 Kubernetes 集群验收。

## 3. 本地单节点接入

仓库提供 Minikube 单节点验收配置，固定使用：

- Minikube profile：`kubeoncall-monitoring`
- kube-state-metrics：`v2.19.0`
- Kubernetes 资源范围：`nodes,pods`
- Prometheus cluster 标签：`local`
- kube-state-metrics NodePort：`30080`

执行：

```bash
./scripts/setup-single-node-monitoring.sh
```

脚本会：

1. 复用或创建一个 2 CPU、3 GiB 的单节点 Minikube。
2. 将官方 kube-state-metrics 镜像加载到 Minikube，避免集群内代理影响。
3. 部署只允许 `list/watch` Node 和 Pod 的最小只读 RBAC。
4. 通过 `docker-compose.monitoring.yml` 将 Prometheus 同时连接 Compose 与 Minikube
   Docker 网络。
5. 使用 `prometheus-single-node.yml` 抓取 kube-state-metrics，并把本地 Node Exporter
   标签对齐到 `kubeoncall-monitoring`。

验收：

```bash
kubectl get nodes
kubectl -n kube-system get deployment,pod,service -l app.kubernetes.io/name=kube-state-metrics
curl -fsS 'http://127.0.0.1:9090/api/v1/targets'
```

预期：

- 只有一个 Node，状态为 `Ready`。
- kube-state-metrics Deployment 为 `1/1`。
- Prometheus 的 `node-exporter` 和 `kube-state-metrics` target 均为 `UP`。
- Console `/monitoring` 显示 1 个 Ready 节点、Pod phase、所在节点和重启次数。

本地 Compose Node Exporter 用于链路验收，CPU 数值代表本地 Docker 主机，不是生产
DaemonSet 的容量数据；生产验收仍必须在目标集群每个 Node 部署 Node Exporter。

## 4. 标签契约

KubeOnCall 后端固定使用以下标签：

```text
cluster=<稳定集群标识>
node=<Kubernetes Node 名称，仅节点指标和 kube_pod_info>
```

kube-state-metrics 的 Node 状态和 `kube_pod_info` 自带 `node`，Pod phase 与重启指标
通常不带 `node`；KubeOnCall 会按 `cluster/namespace/pod` 与 `kube_pod_info` 关联。
Node Exporter 的目标标签必须归一出 `node`。

必须在抓取目标上通过 relabeling 写入 `cluster`。不要只依赖 Prometheus
`externalLabels`，因为 KubeOnCall 查询的是 Prometheus 本地时序。ServiceMonitor 示例：

```yaml
endpoints:
  - relabelings:
      - targetLabel: cluster
        replacement: prod-cn
```

Node Exporter ServiceMonitor 需要把 Kubernetes Node 名称写入目标标签，示例：

```yaml
relabelings:
  - sourceLabels: [__meta_kubernetes_pod_node_name]
    targetLabel: node
```

实际字段位置取决于平台使用的 Prometheus/ServiceMonitor Chart。不要给
kube-state-metrics target 强行写入其 Pod 所在节点作为 `node`，否则所有对象指标都会被
错误标到同一节点。

## 5. KubeOnCall 配置

将后端的 Prometheus 端点指向原生 Prometheus HTTP API：

```text
PROMETHEUS_TOOL_ENDPOINT=http://prometheus.monitoring.svc:9090
```

Helm values 对应：

```yaml
config:
  prometheusToolEndpoint: http://prometheus.monitoring.svc:9090
```

不要把 Prometheus 地址下发给浏览器。Console 只调用受 `dashboard:read` 保护的
`/api/v1/monitoring/*`。

## 6. 指标自检

在 Prometheus 表达式页面执行：

### 节点发现

```promql
max by (cluster, node) (up{job="node-exporter"})
```

预期：每个目标 Kubernetes Node 返回一条序列。

### CPU

```promql
100 - (
  avg by (cluster, node) (
    rate(node_cpu_seconds_total{job="node-exporter",mode="idle"}[5m])
  ) * 100
)
```

### Node Ready

```promql
max by (cluster, node) (
  kube_node_status_condition{condition="Ready",status="true"}
)
```

值为 `1` 表示 Ready，`0` 表示 NotReady。完全无结果表示 kube-state-metrics
未接入或标签不符合契约。

### Pod phase

```promql
max by (cluster, namespace, pod, phase) (
  kube_pod_status_phase == 1
)
```

Pod 所在节点：

```promql
max by (cluster, namespace, pod, node) (
  kube_pod_info
)
```

### Pod 重启次数

```promql
sum by (cluster, namespace, pod) (
  kube_pod_container_status_restarts_total
)
```

KubeOnCall 后端将上述三组数据按 `cluster/namespace/pod` 关联，不要求 phase 和 restart
指标额外携带非标准 `node` 标签。

## 7. API 自检

登录 KubeOnCall 或携带具备 `dashboard:read` 的凭证：

```bash
curl -fsS http://kubeoncall.example/api/v1/monitoring/clusters
curl -fsS 'http://kubeoncall.example/api/v1/monitoring/summary?cluster=prod-cn'
curl -fsS 'http://kubeoncall.example/api/v1/monitoring/nodes?cluster=prod-cn'
curl -fsS 'http://kubeoncall.example/api/v1/monitoring/pods?cluster=prod-cn&limit=100'
curl -fsS 'http://kubeoncall.example/api/v1/monitoring/nodes/worker-1/cpu?cluster=prod-cn&window=15m'
```

判断标准：

- Prometheus 断连：HTTP 503，而不是空健康页。
- Node Exporter 缺失：`nodeMetricsAvailable=false`。
- kube-state-metrics 缺失：`kubernetesStateAvailable=false`，Ready 为 `UNKNOWN`。
- 数据源存在但确实没有匹配 Pod：Pod 列表为空，但
  `kubernetesStateAvailable=true`。

## 8. 告警验收

规则：

- `NodeDown`：Node Exporter target 持续不可达 2 分钟。
- `NodeCPUHigh`：节点 CPU 使用率持续高于 85% 达 5 分钟。
- `NodeMemoryLow`：节点可用内存持续低于 15% 达 5 分钟。
- `NodeDiskHigh`：非临时文件系统使用率持续高于 85% 达 5 分钟。
- `NodeInodeHigh`：非临时文件系统 inode 使用率持续高于 85% 达 5 分钟。
- `NodeConntrackPressure`：conntrack 使用率持续高于 85% 达 5 分钟。
- `NodeClockOffsetHigh`：节点时钟偏移绝对值持续超过 1 秒达 5 分钟。
- `NodeFilesystemReadOnly`：非临时文件系统持续处于只读状态 2 分钟。
- `NodeFileDescriptorPressure`：文件描述符使用率持续高于 80% 达 5 分钟。
- `NodeNotReady`：Ready condition 持续为 0 达 5 分钟。
- `PodPendingTooLong`：Pod 持续 Pending 达 10 分钟。

仓库规则校验：

```bash
./scripts/verify-prometheus-rules.sh
```

生产演练建议：

1. 创建一个明确无法调度的测试 Pod，等待 Pending 告警产生。
2. 恢复调度条件，确认 resolved 投递。
3. 在可控测试节点模拟 NotReady，验证 firing/resolved。
4. 核对 Alertmanager、KubeOnCall 告警列表和集群态势页状态一致。

禁止在未获授权的生产节点直接停止 kubelet 或破坏网络。

## 9. 生产验收清单

- [ ] Node Exporter target 数量与目标节点数一致。
- [ ] kube-state-metrics target 为 UP。
- [ ] 每条节点指标同时包含 `cluster`、`node`。
- [ ] 集群态势页能发现同集群全部节点。
- [ ] Ready/NotReady 与 `kubectl get nodes` 一致。
- [ ] Pod phase 与 `kubectl get pods -A` 一致。
- [ ] CPU 当前值与 Grafana/Prometheus 查询基本一致。
- [ ] 15m/1h/6h CPU 趋势有数据。
- [ ] Prometheus 断连时 Console 明确显示错误。
- [ ] 11 条 Node 规则的 firing/resolved 链路按数据源分批完成演练。
