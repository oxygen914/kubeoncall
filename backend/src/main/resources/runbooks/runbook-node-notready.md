---
runbookId: runbook-node-notready
title: Kubernetes Node NotReady 处置 SOP
category: k8s-node
owner: infra
version: v2
document_type: runbook
source_type: runbook
approvalRequired: true
automationPolicy: diagnose-only
lastValidated: 2026-07-30
---
# Kubernetes Node NotReady 处置 SOP

## 目标与适用范围

适用于 Node `Ready=False/Unknown`、Lease/heartbeat 中断、kubelet 或运行时不可达，以及由此
产生的 Pod 驱逐/不可用。目标是先判断影响范围和业务冗余，再区分节点主机、kubelet、运行时、
网络、资源压力、证书/时间和控制面问题。

本 SOP 默认只读。KubeOnCall 不得自动 cordon、drain、删除 Node、重启 kubelet 或主机。

## 输入与前置条件

- 必填：`CLUSTER`、`NODE`。
- 可选：故障域、云实例 ID、受影响 namespace/service 和维护窗口。
- 必须先确认 kube context，且目标不是唯一控制平面节点。
- 任何 drain/reboot/replace 前必须确认 PDB、本地盘、DaemonSet、StatefulSet 和剩余容量。

## Steps

### 1. 固化 Node condition、Lease 和 Event

```bash
kubectl get node "$NODE" -o wide
kubectl get node "$NODE" \
  -o jsonpath='{range .status.conditions[*]}{.type}{"="}{.status}{" reason="}{.reason}{" transition="}{.lastTransitionTime}{" message="}{.message}{"\n"}{end}'
kubectl -n kube-node-lease get lease "$NODE" -o yaml
kubectl get events \
  --field-selector "involvedObject.kind=Node,involvedObject.name=$NODE" \
  --sort-by='.lastTimestamp'
```

必须记录 Ready condition、最后心跳/Lease renewTime、首次异常时间和 taint。Node 为 Ready 但
node-exporter down 时，优先判定监控路径故障，而不是 Node NotReady。

### 2. 计算业务影响和可迁移性

```bash
kubectl get pods -A --field-selector "spec.nodeName=$NODE" -o wide
kubectl get pdb -A
kubectl get nodes \
  -o custom-columns='NAME:.metadata.name,READY:.status.conditions[?(@.type=="Ready")].status,CPU:.status.allocatable.cpu,MEMORY:.status.allocatable.memory'
```

列出关键 Pod、owner、可用副本、PDB allowed disruptions、本地卷和 failure domain。没有剩余
容量或关键服务为单副本时，不得直接 drain。

### 3. 区分故障层级

| 证据 | 主因与动作 |
| --- | --- |
| 单节点 Lease stale，其他节点/API 正常 | 节点主机、kubelet、运行时或节点网络 |
| 多节点同时 Unknown，API/控制面异常 | 控制面或网络面故障；立即平台升级 |
| Ready=True 但 exporter/指标缺失 | 监控采集路径，不执行节点恢复动作 |
| Memory/Disk/PIDPressure=True | 转入对应资源压力 SOP |
| kubelet 证书、系统时间或运行时错误 | 按平台标准服务恢复，需审批 |
| 云平台实例异常/维护事件 | 关联云事件并走节点替换流程 |

在节点可达时，由平台人员从受控运维通道采集 kubelet、container runtime、系统日志、磁盘、
内存、时间和网络证据；不得把 SSH 凭据交给 KubeOnCall。

### 4. 选择最小风险处置

1. 先保护业务：确认其他节点容量、PDB 和副本分布。
2. 节点暂时不可恢复且业务有冗余：审批后 cordon，再决定是否 drain。
3. kubelet/运行时异常：保存日志后按平台服务管理流程恢复。
4. 主机/云实例故障：替换节点，重新验证 CNI、CSI、runtime、labels、taints 和监控。
5. 多节点或控制面异常：停止单节点操作，交由 Incident Commander 统一处置。

经审批的迁移示例：

```bash
kubectl cordon "$NODE"
kubectl drain "$NODE" \
  --ignore-daemonsets --delete-emptydir-data=false --timeout=15m
```

不得使用 `--force`、`--disable-eviction` 或忽略 PDB。包含本地盘或无法驱逐 Pod 时必须人工
决策。

## Recovery validation

恢复必须同时满足：

1. Node `Ready=True` 且 Lease 连续两个 5 分钟窗口更新。
2. kubelet、container runtime、CNI/CSI、Node conditions 和监控 target 正常。
3. 系统 Pod 与业务 Pod Ready，网络、DNS、存储挂载和错误率无异常。
4. 若节点被 cordon，只有完成上述检查后才能审批 uncordon。

```bash
kubectl wait --for=condition=Ready "node/$NODE" --timeout=10m
kubectl -n kube-node-lease get lease "$NODE" \
  -o jsonpath='{.spec.renewTime}{"\n"}'
kubectl get pods -A --field-selector "spec.nodeName=$NODE" -o wide
```

## Timeout and human escalation

- 唯一控制平面节点、多个节点异常或 API 不稳定：立即升级，禁止自动恢复。
- P0/P1 服务无冗余或 PDB 阻塞迁移：立即升级应用 owner 与 Incident Commander。
- 5 分钟无法确认影响范围，或 10 分钟节点仍 Unknown：标记 `NEEDS_HUMAN`。
- 一次 kubelet/runtime 恢复后再次 NotReady：停止重试，进入节点替换流程。

## Rollback

- cordon 后若迁移风险高于节点故障且 Node 已稳定恢复，审批后取消 cordon：

```bash
kubectl uncordon "$NODE"
```

- drain 中若 PDB、存储或容量不满足，立即停止继续驱逐；已迁移 Pod 保持在健康节点，不能
  强制迁回。
- 节点配置/升级通过原平台变更系统回滚；重启或替换失败时保持节点不可调度并人工升级。
- 回滚后重新执行全部 Recovery validation，不能只检查 `Ready=True`。

## Evidence to retain

- Node UID、conditions、taints、Lease、Event、故障域和云实例事件。
- 受影响 Pod/owner/PDB/存储、其他节点余量和监控 target。
- 平台日志摘要、审批 ID、执行 ID、迁移清单、恢复窗口和回滚结果。
- SOP 引用固定为 `runbook-node-notready@v2`。

## Acceptance scenario

真实演练应在 Minikube 中新增临时 Worker，先加专用 taint 并确认无业务 Pod，再停止该 Worker。
预期控制面将 Node 标记为 `Ready=Unknown/False`、Lease 停止更新；恢复 Worker 后必须重新
`Ready=True`。禁止停止唯一控制平面节点，演练结束删除临时 Worker。
