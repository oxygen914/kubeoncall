---
runbookId: runbook-pod-oom
title: Kubernetes Pod OOMKilled 处置 SOP
category: k8s-pod
owner: app
version: v2
document_type: runbook
source_type: runbook
approvalRequired: true
automationPolicy: diagnose-only
lastValidated: 2026-07-30
---
# Kubernetes Pod OOMKilled 处置 SOP

## 目标与适用范围

适用于容器 lastState reason 为 `OOMKilled`、退出码 137，或节点发生系统级 OOM。目标是区分
cgroup limit、request/limit 不匹配、流量突增、队列堆积、内存泄漏和节点 MemoryPressure，
并在保留证据的前提下恢复稳定容量。

本 SOP 默认只读。KubeOnCall 不得自动移除 limit、扩容、重启、抓取 heap dump 或回滚。

## 输入与前置条件

- 必填：`CLUSTER`、`NAMESPACE`、`POD`、`CONTAINER`。
- 可选：`WORKLOAD_KIND`、`WORKLOAD`、`PREVIOUS_REVISION`、监控时间窗。
- 必须先确认是容器级 OOM 还是节点级 OOM；两者的 owner 和处置不同。
- heap dump/profile 可能包含敏感数据，只能写入批准的受控存储。

## Steps

### 1. 确认 OOM 事实

```bash
kubectl -n "$NAMESPACE" get pod "$POD" \
  -o jsonpath='{range .status.containerStatuses[*]}{.name}{" restarts="}{.restartCount}{" lastReason="}{.lastState.terminated.reason}{" exitCode="}{.lastState.terminated.exitCode}{" startedAt="}{.lastState.terminated.startedAt}{" finishedAt="}{.lastState.terminated.finishedAt}{"\n"}{end}'
kubectl -n "$NAMESPACE" get pod "$POD" \
  -o jsonpath='{range .spec.containers[*]}{.name}{" requests="}{.resources.requests}{" limits="}{.resources.limits}{"\n"}{end}'
kubectl -n "$NAMESPACE" get events \
  --field-selector "involvedObject.kind=Pod,involvedObject.name=$POD" \
  --sort-by='.lastTimestamp'
```

只有 `lastReason=OOMKilled`、退出码 137 或可信内核 OOM 证据才能确认 OOM。单独的进程日志
“out of memory”只作为线索。

### 2. 区分容器 limit 与节点压力

```bash
NODE="$(kubectl -n "$NAMESPACE" get pod "$POD" -o jsonpath='{.spec.nodeName}')"
kubectl get node "$NODE" \
  -o jsonpath='{range .status.conditions[*]}{.type}{"="}{.status}{" reason="}{.reason}{" message="}{.message}{"\n"}{end}'
kubectl get node "$NODE" -o jsonpath='{.status.allocatable.memory}{"\n"}'
kubectl top pod "$POD" -n "$NAMESPACE" --containers
kubectl top node "$NODE"
```

`kubectl top` 不可用时标记指标 `UNAVAILABLE`，改用 Prometheus 的 container working set、
RSS、limit、node available memory、请求量、队列、GC/heap 指标；不得补造曲线。

### 3. 获取退出前证据并关联变更

```bash
kubectl -n "$NAMESPACE" logs "$POD" -c "$CONTAINER" \
  --previous --timestamps --tail=300
kubectl -n "$NAMESPACE" rollout history "$WORKLOAD_KIND/$WORKLOAD"
kubectl -n "$NAMESPACE" get "$WORKLOAD_KIND" "$WORKLOAD" -o yaml
```

记录内存增长斜率、到达 limit 的时间、流量/队列同步性、GC 情况、首次发生时间和发布/配置
时间。previous log 为空应标记 `EMPTY`。不得未经授权收集完整 heap dump。

### 4. 按证据选择主因

| 证据 | 主因与动作 |
| --- | --- |
| working set 到达 container limit，Node 健康，历史基线长期接近 limit | limit 与稳定工作集不匹配；评估节点余量后灰度调整 |
| 新 revision 后内存持续单调增长，负载无对应增长 | 疑似内存泄漏；优先回滚/摘流，交由应用修复 |
| 内存尖峰与流量、大对象或队列一致 | 扩容、限流、降级或拆分工作；先保护依赖 |
| 多个 Pod 同节点被杀，Node MemoryPressure 或内核 OOM | 节点内存故障；转入节点内存 SOP |
| request 远低于稳定使用量导致节点超卖 | 同步校准 request 与 limit，重新做容量预算 |

“泄漏”必须保持为假设，直到 profile、heap 或可重复增长曲线证实。

### 5. 创建受控处置任务

- 资源调整：必须同时给出旧值、新值、依据、节点余量、灰度范围和回滚阈值。
- 发布后泄漏：优先回滚到已验证 revision；保留故障 Pod 证据，但不得牺牲服务冗余。
- 流量突增：选择限流/扩容/降级中的最小风险动作，明确业务影响。
- 节点 OOM：停止对单 Pod 反复重启，升级平台 owner。

经审批的紧急资源补丁示例：

```bash
kubectl -n "$NAMESPACE" patch "$WORKLOAD_KIND" "$WORKLOAD" \
  --type=strategic --patch-file "$APPROVED_PATCH"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

## Recovery validation

恢复必须同时满足：

1. 新 Pod Ready，restartCount 在连续两个 10 分钟窗口内稳定。
2. working set/RSS 有界且低于新 limit 的策略水位，GC、队列和错误率恢复基线。
3. availableReplicas 达标，Node `MemoryPressure=False`，同节点无新增 OOM。
4. request/limit、容量预算和审批记录一致；不能只因容器暂时 Running 判定恢复。

```bash
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
kubectl -n "$NAMESPACE" get pods -l "app.kubernetes.io/name=$WORKLOAD" \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[*].ready,RESTARTS:.status.containerStatuses[*].restartCount,LAST_REASON:.status.containerStatuses[*].lastState.terminated.reason'
```

## Timeout and human escalation

- 关键服务多副本同时 OOM、节点级 OOM 或 availableReplicas 低于 SLO：立即升级。
- 10 分钟内无法确认 limit、节点压力或最近变更：标记 `NEEDS_HUMAN`。
- 一次资源调整/回滚后再次 OOM：停止自动重试，升级应用 owner 和容量负责人。
- 涉及 heap dump、敏感数据、运行时崩溃或内核异常：升级安全/平台团队。

## Rollback

变更前保存资源模板和 revision。提高 limit 后若节点余量跌破阈值、其他工作负载受影响或
错误率上升，回滚模板；发布回滚后若产生新故障，按发布系统恢复到最近已验证 revision。

```bash
kubectl -n "$NAMESPACE" rollout undo "$WORKLOAD_KIND/$WORKLOAD" \
  --to-revision="$PREVIOUS_REVISION"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

回滚后重新验证 OOM、内存曲线、Ready 副本和 Node MemoryPressure。禁止把 limit 设置为空
作为回滚。

## Evidence to retain

- Pod UID、container、lastState、exitCode、restartCount、requests/limits、QoS 和 Node。
- current/previous logs、Pod/Node Event、内存/流量/队列/GC 时间线。
- revision、配置变更、审批 ID、执行 ID、恢复窗口和回滚结果。
- SOP 引用固定为 `runbook-pod-oom@v2`。

## Acceptance scenario

专用演练清单的 `oomkilled-memory-limit` 运行真实容器并施加 16Mi cgroup limit。预期 kubelet
记录 `OOMKilled` 和退出码 137；诊断必须区分容器 limit 与 Node MemoryPressure，不能只建议
无限提高内存。
