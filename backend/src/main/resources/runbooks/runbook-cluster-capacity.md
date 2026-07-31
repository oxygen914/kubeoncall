---
runbookId: runbook-cluster-capacity
title: Kubernetes Pod Pending 与调度失败处置 SOP
category: capacity
owner: platform
version: v2
document_type: runbook
source_type: runbook
approvalRequired: true
automationPolicy: diagnose-only
lastValidated: 2026-07-30
---
# Kubernetes Pod Pending 与调度失败处置 SOP

## 目标与适用范围

适用于 Pod 长时间处于 `Pending`，或 Event 出现 `FailedScheduling`。目标是基于调度器
Event、Pod requests、节点 allocatable、ResourceQuota、污点与容忍、亲和性、拓扑和存储绑定
证据，区分真实容量不足与声明式约束错误。

本 SOP 默认只读。KubeOnCall 可以收集证据、形成结论和创建待审批任务，但不得自动修改
node label、taint、quota、affinity、replicas 或集群容量。

## 输入与前置条件

- 必填：`CLUSTER`、`NAMESPACE`、`POD`。
- 可选：控制器类型与名称 `WORKLOAD_KIND`、`WORKLOAD`，以及已审批补丁
  `APPROVED_PATCH`。
- 操作人必须确认 kube context 和 namespace，且目标 Pod UID 与事件中的 UID 一致。
- 生产处置前必须保存 workload 当前 YAML、revision 和 Pod 调度 Event。

```bash
kubectl config current-context
kubectl -n "$NAMESPACE" get pod "$POD" \
  -o jsonpath='{.metadata.uid}{"\t"}{.status.phase}{"\t"}{.status.startTime}{"\n"}'
```

## Steps

### 1. 固化 Pending 事实和时间线

```bash
kubectl -n "$NAMESPACE" get pod "$POD" -o wide
kubectl -n "$NAMESPACE" get pod "$POD" -o yaml
kubectl -n "$NAMESPACE" get events \
  --field-selector "involvedObject.kind=Pod,involvedObject.name=$POD" \
  --sort-by='.lastTimestamp'
```

必须记录 `PodScheduled` condition 的 reason/message、首次与最近一次 Event 时间、调度器
返回的节点数和阻塞原因。没有 `FailedScheduling` 时，不得直接判定为容量不足，还应检查
PVC、镜像拉取和初始化容器。

### 2. 核对资源和调度约束

```bash
kubectl -n "$NAMESPACE" get pod "$POD" \
  -o jsonpath='{range .spec.containers[*]}{.name}{" requests="}{.resources.requests}{" limits="}{.resources.limits}{"\n"}{end}'
kubectl get nodes \
  -o custom-columns='NAME:.metadata.name,READY:.status.conditions[?(@.type=="Ready")].status,CPU:.status.allocatable.cpu,MEMORY:.status.allocatable.memory,PODS:.status.allocatable.pods'
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" labels="}{.metadata.labels}{" taints="}{.spec.taints}{"\n"}{end}'
kubectl -n "$NAMESPACE" get resourcequota,limitrange
kubectl -n "$NAMESPACE" get pvc
```

禁止输出 Secret 值。亲和性、nodeSelector、topologySpreadConstraints、tolerations 和
schedulerName 只从 Pod/控制器声明中读取。

### 3. 按证据选择唯一主因

| 证据 | 主因 | 处置所有者 |
| --- | --- | --- |
| `Insufficient cpu/memory/ephemeral-storage` 且多个节点均命中 | 可调度容量不足或 request 过高 | 平台 + 应用 |
| `didn't match Pod's node affinity/selector` | selector/affinity 与现有标签不相交 | 应用 |
| `untolerated taint` | 缺少容忍或工作负载不应进入该节点池 | 平台 + 应用 |
| `exceeded quota` 或 LimitRange 拒绝 | Namespace 配额/默认资源约束 | 租户管理员 |
| `pod has unbound immediate PersistentVolumeClaims` | 存储绑定或 StorageClass | 存储平台 |
| `topology spread constraints` / anti-affinity | 故障域或副本分布约束 | 应用架构 |

结论必须引用原始 Event 片段，不能只写“资源不足”。多个原因同时出现时，先处理不会改变
容量的声明错误，再评估扩容。

### 4. 生成受控处置任务

- selector、affinity、toleration 或 resources 错误：通过 Git/Helm 修改控制器模板，保留
  审批后的 diff；禁止直接编辑 Pending Pod。
- quota 调整：确认租户预算、节点余量和回收计划。
- 容量不足：优先由集群平台扩容节点池；扩容后验证镜像、CNI、CSI、labels、taints 和监控。
- 紧急补丁仅能在审批记录关联 `APPROVED_PATCH` 后执行：

```bash
kubectl -n "$NAMESPACE" patch "$WORKLOAD_KIND" "$WORKLOAD" \
  --type=strategic --patch-file "$APPROVED_PATCH"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

## Recovery validation

恢复必须同时满足：

1. 目标 Pod 获得 `spec.nodeName`，`PodScheduled=True`，并最终 `Ready=True`。
2. 同一控制器期望副本全部 Ready，且连续两个 5 分钟窗口无新增 `FailedScheduling`。
3. Namespace Pending backlog 回落，节点 CPU、内存和 Pod density 保留策略要求的余量。
4. 未通过放宽 PDB、删除 taint、关闭 affinity 或无界提高 quota 掩盖问题。

```bash
kubectl -n "$NAMESPACE" wait --for=condition=PodScheduled "pod/$POD" --timeout=5m
kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$POD" --timeout=10m
kubectl -n "$NAMESPACE" get pods --field-selector=status.phase=Pending
```

## Timeout and human escalation

- P1 工作负载 5 分钟仍不可调度：升级 Incident Commander 和平台 on-call。
- 任何控制面调度异常、多个 failure domain 耗尽或集群扩容失败：立即升级平台负责人。
- PVC、quota 或调度约束在 15 分钟内无法确认 owner：创建人工处置任务，不得猜测修改。
- 超时后 KubeOnCall 只能把任务标记为 `NEEDS_HUMAN`，不得重复删除 Pod。

## Rollback

变更前记录 revision 和完整模板。若新模板产生更多 Pending、Ready 副本下降或错误率升高，
立即停止继续发布，并在审批范围内回滚：

```bash
kubectl -n "$NAMESPACE" rollout undo "$WORKLOAD_KIND/$WORKLOAD" \
  --to-revision="$PREVIOUS_REVISION"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

容量或 quota 变更按原审批中的逆向步骤恢复；回滚后重新执行全部 Recovery validation。
不得通过删除 Node 对象、强制驱逐其他租户 Pod 或忽略 PDB 获得临时容量。

## Evidence to retain

- Pod UID、控制器 revision、完整 `FailedScheduling` Event 和采集时间。
- requests/limits、节点 allocatable、quota、taint/toleration、affinity/topology、PVC 状态。
- 变更 diff、审批 ID、执行 ID、恢复窗口和回滚结果。
- SOP 引用固定为 `runbook-cluster-capacity@v2`。

## Acceptance scenario

专用演练清单创建的 `pending-node-selector` 使用不存在的 nodeSelector。预期证据为
`Pending`、`PodScheduled=False` 和 `FailedScheduling`，并明确指出 selector 不匹配，而不是
误报集群资源不足。演练清理只能删除带
`kubeoncall.io/acceptance-scenario=true` 标签的资源。
