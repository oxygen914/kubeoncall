# Kubernetes 故障 SOP 真实演练指南

> 适用环境：专用测试集群
>
> 禁止环境：生产集群、共享预发集群、唯一控制平面节点
>
> 本指南验证的是“真实模型 + 版本化 SOP + Kubernetes/Prometheus/Loki 证据 +
> 持久化 Ask”的只读诊断链路，不授权 AI 自动修改 Kubernetes。

## 1. 演练目标

使用真实 Kubernetes API、kubelet、调度器、cgroup 和临时 Worker，重复构造并诊断四类故障：

| 场景             | 故障对象                     | 固定 SOP                      | 必须出现的直接证据                                     |
| ---------------- | ---------------------------- | ----------------------------- | ------------------------------------------------------ |
| Pending          | `Pod/pending-node-selector`  | `runbook-cluster-capacity@v2` | `Pending`、`PodScheduled=False`、`FailedScheduling`    |
| CrashLoopBackOff | `Pod/crashloop-exit-42`      | `runbook-pod-crashloop@v2`    | `lastState=Error`、`exitCode=42`、current/previous log |
| OOMKilled        | `Pod/oomkilled-memory-limit` | `runbook-pod-oom@v2`          | `lastState=OOMKilled`、`exitCode=137`、16Mi limit      |
| Node NotReady    | 临时 Worker                  | `runbook-node-notready@v2`    | `Ready=False/Unknown`、Lease 停更、受影响 Pod          |

仓库入口：

- 故障清单：`deploy/kubernetes/aiops-acceptance-scenarios.yaml`
- 本地证据桥：`docker-compose.kubernetes-evidence.yml`
- 验收范围覆盖：`docker-compose.aiops-acceptance.yml`
- SOP：`backend/src/main/resources/runbooks/runbook-*.md`

## 2. 安全门禁

开始前必须满足：

1. 当前 kube context 明确指向专用测试集群。
2. 集群至少有一个独立 Worker；不得停止唯一控制平面节点。
3. 故障资源只创建在 `kubeoncall-aiops-acceptance`。
4. 该 Namespace 有 ResourceQuota，资源带
   `kubeoncall.io/acceptance-scenario=true` 标签。
5. Kubernetes Tool Adapter 保持只读，变更 action 必须返回
   `403 READ_ONLY_MODE`。
6. 模型 Key、Adapter Token 只从本地 Secret 或环境变量注入，不写入命令历史、Compose、
   文档和 Git。
7. Console 中所有演练问题明确写入“禁止执行任何变更”。
8. 演练结束必须恢复 Worker、验证 Lease，再删除 Namespace 和临时 Worker。

建议先保存基线：

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl get pods -A
kubectl -n kube-node-lease get lease
```

若 context、节点角色或测试范围不符合预期，立即停止。

## 3. 环境准备

### 3.1 检查真实依赖

后端能力接口应满足：

```text
planner.status=HEALTHY
planner.mode=REAL_MODEL
planner.provider=aliyun-dashscope
planner.model=qwen-plus
evidencePrometheus=true
evidenceLoki=true
evidenceK8sResourceState=true
evidenceK8sEvents=true
evidencePodLogs=true
durableAskWorkflow=true
conclusionEvidenceUi=true
```

同时检查：

```bash
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18080/api/v1/capabilities
curl -fsS http://127.0.0.1:9090/-/ready
curl -fsS http://127.0.0.1:3100/ready
kubectl -n kubeoncall-system rollout status deployment/kubernetes-tool-adapter
```

只看到 HTTP 200 不算通过，必须核对上述字段和值。

### 3.2 创建临时 Worker

优先使用 Minikube 原生命令：

```bash
minikube -p kubeoncall-monitoring node add --worker
kubectl get nodes -o wide
```

将实际新增节点名写入 `WORKER`，并隔离为演练专用节点：

```bash
WORKER="实际临时 Worker 名称"

kubectl label node "$WORKER" \
  kubeoncall.io/acceptance-node=true --overwrite
kubectl taint node "$WORKER" \
  kubeoncall.io/acceptance-only=true:NoSchedule --overwrite
```

只有 `node-unavailable-witness` 具有该 taint 的 toleration。不要把真实业务 Pod 调度到该节点。

### 3.3 创建故障 Namespace

```bash
kubectl apply -f deploy/kubernetes/aiops-acceptance-scenarios.yaml
kubectl -n kubeoncall-aiops-acceptance get pods -o wide
```

预期：

- `pending-node-selector` 为 `Pending`。
- `crashloop-exit-42` 进入 `CrashLoopBackOff`。
- `oomkilled-memory-limit` 出现 `OOMKilled` 或随后进入 `CrashLoopBackOff`。
- `node-unavailable-witness` 运行在临时 Worker。

### 3.4 扩展只读范围

必须同时完成三层范围配置，缺一层都应失败关闭：

1. Acceptance Namespace 中给 Adapter ServiceAccount 创建只读 Role/RoleBinding。
2. Adapter 的 `KUBERNETES_TOOL_ADAPTER_ALLOWED_NAMESPACES` 增加 Acceptance Namespace。
3. KubeOnCall 的 `KUBEONCALL_EVIDENCE_ALLOWED_NAMESPACES` 增加 Acceptance Namespace。

清单已包含第 1 项。本地演练可以临时执行：

```bash
kubectl -n kubeoncall-system set env deployment/kubernetes-tool-adapter \
  KUBERNETES_TOOL_ADAPTER_ALLOWED_NAMESPACES=\
kubeoncall-system,kubeoncall-aiops-acceptance
kubectl -n kubeoncall-system rollout status deployment/kubernetes-tool-adapter
```

启动后端时叠加：

```text
docker-compose.yml
docker-compose.kubernetes-evidence.yml
docker-compose.aiops-acceptance.yml
```

真实 Key 和 Token 只通过当前终端环境或 Secret 注入。本指南不提供明文写法。

### 3.5 确认 SOP 已进入真实 RAG

四份 SOP 的 frontmatter 必须包含稳定 `runbookId`、`version=v2`、
`source_type=runbook`、`automationPolicy=diagnose-only`。

应用启动日志应显示 Runbook bootstrap 无失败。显式指定
`runbook-node-notready@v2` 时，RAG 结果必须返回同一个 ID/版本；不能被会话记忆中的其他 SOP
覆盖，也不能生成模拟 SOP。

## 4. 故障事实检查

### 4.1 Pending

```bash
kubectl -n kubeoncall-aiops-acceptance get pod pending-node-selector -o wide
kubectl -n kubeoncall-aiops-acceptance get pod pending-node-selector \
  -o jsonpath='{.status.phase}{"\t"}{.status.conditions[?(@.type=="PodScheduled")].status}{"\n"}'
kubectl -n kubeoncall-aiops-acceptance get events \
  --field-selector involvedObject.name=pending-node-selector \
  --sort-by=.lastTimestamp
```

必须看到 selector/affinity 不匹配。不能仅因为 Pod 为 Pending 就推断“CPU 不足”。

### 4.2 CrashLoopBackOff

```bash
kubectl -n kubeoncall-aiops-acceptance get pod crashloop-exit-42 -o wide
kubectl -n kubeoncall-aiops-acceptance get pod crashloop-exit-42 \
  -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}{"\t"}{.status.containerStatuses[0].lastState.terminated.exitCode}{"\n"}'
kubectl -n kubeoncall-aiops-acceptance logs crashloop-exit-42 --tail=20
kubectl -n kubeoncall-aiops-acceptance logs crashloop-exit-42 --previous --tail=20
```

预期退出原因为 `Error`、退出码为 `42`，日志包含
`KOC_ACCEPTANCE_CRASH_EXIT_42`。

### 4.3 OOMKilled

```bash
kubectl -n kubeoncall-aiops-acceptance get pod oomkilled-memory-limit -o wide
kubectl -n kubeoncall-aiops-acceptance get pod oomkilled-memory-limit \
  -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}{"\t"}{.status.containerStatuses[0].lastState.terminated.exitCode}{"\n"}'
kubectl -n kubeoncall-aiops-acceptance get pod oomkilled-memory-limit \
  -o jsonpath='{.spec.containers[0].resources}{"\n"}'
kubectl -n kubeoncall-aiops-acceptance logs oomkilled-memory-limit --previous --tail=20
```

预期为 `OOMKilled / 137`，limit 为 `16Mi`。不能把日志中的“OOM”关键词当作唯一确认依据。

### 4.4 Node NotReady

先记录正常状态和 Lease：

```bash
kubectl get node "$WORKER" -o wide
kubectl -n kube-node-lease get lease "$WORKER" \
  -o jsonpath='{.spec.renewTime}{"\n"}'
kubectl get pods -A --field-selector "spec.nodeName=$WORKER" -o wide
```

停止临时 Worker：

```bash
minikube -p kubeoncall-monitoring node stop "$WORKER"
```

等待控制面判定故障：

```bash
kubectl get node "$WORKER" \
  -o jsonpath='{range .status.conditions[*]}{.type}{"="}{.status}{" reason="}{.reason}{" message="}{.message}{"\n"}{end}'
kubectl -n kube-node-lease get lease "$WORKER" \
  -o jsonpath='{.spec.renewTime}{"\n"}'
```

预期 `Ready=False/Unknown`、`NodeStatusUnknown` 或等价 kubelet 心跳中断证据，且 Lease
不再更新。

## 5. Console 持久化 Ask

每次提交前：

1. 打开 `/ask`。
2. 集群选择 `local`。
3. Namespace 选择 `kubeoncall-aiops-acceptance`。
4. 刷新页面后重新检查范围；当前前端不会保证刷新后仍保留 Namespace 选择。
5. 确认 Planner 显示“真实模型”，不是规则降级或模拟。

### 5.1 Pending 提问

```text
请只读分析 local 集群 kubeoncall-aiops-acceptance 命名空间中 Pod
pending-node-selector 为什么处于 Pending。必须结合 Kubernetes 资源状态、Events 和
runbook-cluster-capacity@v2，逐项展示证据片段、证据来源、SOP 来源与置信度；
禁止执行任何变更。
```

### 5.2 CrashLoopBackOff 提问

```text
请只读分析 local 集群 kubeoncall-aiops-acceptance 命名空间中 Pod
crashloop-exit-42 的 CrashLoopBackOff。必须结合 Kubernetes 资源状态、Events、
current/previous logs 和 runbook-pod-crashloop@v2，逐项展示退出码、日志证据、
SOP 来源与置信度；禁止执行任何变更。
```

### 5.3 OOMKilled 提问

```text
请只读分析 local 集群 kubeoncall-aiops-acceptance 命名空间中 Pod
oomkilled-memory-limit 的 OOMKilled。必须结合 Kubernetes lastState/exitCode、
Events、current/previous logs、Prometheus 指标和 runbook-pod-oom@v2，
逐项展示证据片段、SOP 来源与置信度；禁止执行任何变更。
```

### 5.4 Node NotReady 提问

```text
请只读分析 local 集群中 Node 临时Worker名称 当前 NotReady 的原因和影响范围，
Namespace 范围为 kubeoncall-aiops-acceptance。必须结合 Node conditions/Lease、
相关 Events、受影响 Pod、Prometheus 指标和 runbook-node-notready@v2，
逐项展示证据片段、SOP 来源与置信度；禁止执行任何变更。
```

## 6. 单次执行验收标准

每次执行必须同时满足：

- 通过持久化 `/api/v1/executions` 进入 Execution/Task/节点时间线。
- Execution 到达终态，不长期停留在 `QUEUED`、`RUNNING` 或 `DEAD_LETTER`。
- Planner 为 `REAL_MODEL`，模型名称可见。
- SOP 来自 `knowledge.rag.local` 或已验收的真实知识服务，`simulation=false`。
- SOP ID 和版本与问题中显式指定的引用一致。
- 资源状态、Event、日志和指标分别显示 `SUCCEEDED`、`EMPTY`、`UNAVAILABLE` 或
  `FORBIDDEN`，不能把失败文本标成成功日志。
- 结论展示证据片段、来源、置信度与缺失信号。
- 只读任务 `approvalRequired=false`；不得产生 Kubernetes 变更。
- 缺少 Lease、previous log、指标时间线或外部依赖时，应降低置信度并列为缺失证据。

工作流 `SUCCEEDED` 只代表只读诊断流程完成，不等于故障已恢复。

## 7. Node 恢复验证

恢复临时 Worker：

```bash
minikube -p kubeoncall-monitoring node start "$WORKER"
kubectl wait --for=condition=Ready "node/$WORKER" --timeout=180s
kubectl -n kube-node-lease get lease "$WORKER" \
  -o jsonpath='{.spec.renewTime}{"\n"}'
```

至少确认：

1. `Ready=True / KubeletReady`。
2. Lease renewTime 重新更新。
3. `node-unavailable-witness` 完成删除或恢复为预期状态。
4. Prometheus 的 `kube_node_info` 仍能看到全部节点。

本地 Docker 网络中，停止的 Minikube Worker 会保留固定低位地址。证据桥为 Prometheus
设置了高位静态地址，默认 `192.168.58.250`，可通过
`KUBEONCALL_PROMETHEUS_MINIKUBE_IPV4` 覆盖。若本地 Minikube 子网不是
`192.168.58.0/24`，必须先选择该子网内未占用的高位地址；不得照抄默认值。

## 8. 清理

先恢复 Worker，再清理：

```bash
kubectl delete namespace kubeoncall-aiops-acceptance --wait=true

kubectl -n kubeoncall-system set env deployment/kubernetes-tool-adapter \
  KUBERNETES_TOOL_ADAPTER_ALLOWED_NAMESPACES=kubeoncall-system
kubectl -n kubeoncall-system rollout status deployment/kubernetes-tool-adapter

minikube -p kubeoncall-monitoring node delete "$WORKER"
kubectl get nodes
```

随后去掉 `docker-compose.aiops-acceptance.yml`，重建 KubeOnCall 后端，使后端 allowlist
恢复为 `kubeoncall-system`。最后确认：

- Acceptance Namespace 不存在。
- 临时 Worker 不存在。
- 控制平面 Node 为 Ready。
- Adapter 与后端只允许正式联调 Namespace。
- 后端 health、真实模型 canary、Prometheus 和 Loki 正常。

## 9. 常见失败解释

| 现象                               | 优先检查                                                   | 正确处理                                         |
| ---------------------------------- | ---------------------------------------------------------- | ------------------------------------------------ |
| 所有证据 `FORBIDDEN`               | Console Namespace、后端 allowlist、Adapter allowlist、RBAC | 同时修正三层范围，不关闭安全校验                 |
| SOP 命中其他场景                   | 会话上下文是否含旧 runbook、显式 ID/版本是否存在           | 用户显式引用优先；不得接受相似度误命中           |
| previous log 返回 kubelet 错误文本 | 容器轮换、CRI 日志是否已回收                               | 标记 `EMPTY/UNAVAILABLE`，不能作为成功日志       |
| Prometheus 没有 KSM 指标           | 配置挂载、Minikube 网络、KSM target                        | 修复采集路径，不补造指标                         |
| Worker 启动提示地址被占用          | 停机节点地址是否被其他容器复用                             | 为证据容器使用未占用高位地址，再执行恢复         |
| Node 结论缺少 Lease/受影响 Pod     | Adapter/RBAC/统一 Evidence 契约                            | 降低置信度并标记缺口，不能宣称 Node 场景全量通过 |
| Execution 成功但故障仍存在         | 当前执行是否只是只读诊断                                   | 只标记 `DIAGNOSED`，不得标记 `RESOLVED`          |

## 10. 能力边界

完成本指南的一轮演练，只能证明四类故障的真实只读诊断链路可运行。达到“独立解决运营问题”
还需要：

- 告警、变更事件、Loki Pod 日志和 Kubernetes 证据同窗关联。
- Node Lease、受影响 Pod、PDB 和容量证据进入统一 Evidence。
- 独立变更 Adapter、幂等 operationId、审批、操作后稳定窗口。
- 验证超时、补偿回滚、回滚后复验和 Incident 人工升级。
- 每个场景至少三次重复演练，并覆盖模型超时、依赖不可用、证据冲突和进程重启恢复。
- 预发布与生产级安全、性能、网络、权限和审计验收。
