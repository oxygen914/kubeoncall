---
runbookId: runbook-pod-crashloop
title: Kubernetes Pod CrashLoopBackOff 处置 SOP
category: k8s-pod
owner: app
version: v2
document_type: runbook
source_type: runbook
approvalRequired: true
automationPolicy: diagnose-only
lastValidated: 2026-07-30
---
# Kubernetes Pod CrashLoopBackOff 处置 SOP

## 目标与适用范围

适用于容器反复退出并进入 `CrashLoopBackOff`。目标是用 lastState、退出码、current/previous
logs、Event、探针、镜像与 revision 证据，区分程序崩溃、配置错误、依赖不可用、探针错误、
权限问题和 OOM。

本 SOP 默认只读。KubeOnCall 不得自动删除 Pod、重启工作负载、修改探针或回滚发布。

## 输入与前置条件

- 必填：`CLUSTER`、`NAMESPACE`、`POD`。
- 可选：`CONTAINER`、`WORKLOAD_KIND`、`WORKLOAD`、`PREVIOUS_REVISION`。
- 必须确认 Pod ownerReference；变更只能作用于声明式控制器，不作用于当前 Pod。
- previous logs 可能随容器再次重启而被覆盖，应优先采集并记录时间。

## Steps

### 1. 固化容器终止事实

```bash
kubectl -n "$NAMESPACE" get pod "$POD" -o wide
kubectl -n "$NAMESPACE" get pod "$POD" \
  -o jsonpath='{range .status.containerStatuses[*]}{.name}{" ready="}{.ready}{" restarts="}{.restartCount}{" waiting="}{.state.waiting.reason}{" lastReason="}{.lastState.terminated.reason}{" exitCode="}{.lastState.terminated.exitCode}{" finishedAt="}{.lastState.terminated.finishedAt}{"\n"}{end}'
kubectl -n "$NAMESPACE" get events \
  --field-selector "involvedObject.kind=Pod,involvedObject.name=$POD" \
  --sort-by='.lastTimestamp'
```

`OOMKilled` 或退出码 137 转入 `runbook-pod-oom@v2`。`ImagePullBackOff`、`CreateContainerConfigError`
和未调度 Pod 不是 CrashLoop，应转入对应 SOP。

### 2. 先取 previous logs，再取 current logs

```bash
kubectl -n "$NAMESPACE" logs "$POD" -c "$CONTAINER" \
  --previous --timestamps --tail=300
kubectl -n "$NAMESPACE" logs "$POD" -c "$CONTAINER" \
  --timestamps --tail=300
```

保存第一条错误、堆栈根因、依赖地址、配置键名和退出前最后一条日志。必须脱敏 Token、
Authorization、Cookie、Secret、个人信息和业务载荷。没有 previous logs 时标记 `EMPTY`，
不得伪造日志。

### 3. 对比声明、revision 和健康副本

```bash
kubectl -n "$NAMESPACE" get pod "$POD" -o jsonpath='{.metadata.ownerReferences}'
kubectl -n "$NAMESPACE" get "$WORKLOAD_KIND" "$WORKLOAD" -o yaml
kubectl -n "$NAMESPACE" rollout history "$WORKLOAD_KIND/$WORKLOAD"
kubectl -n "$NAMESPACE" get pods \
  -l "app.kubernetes.io/name=$WORKLOAD" -o wide
```

对比镜像 digest、command/args、ConfigMap/Secret 名称及 resourceVersion、serviceAccount、
requests/limits、startup/readiness/liveness probe 和依赖端点。禁止读取或输出 Secret data。

### 4. 按证据选择主因

| 证据 | 主因与动作 |
| --- | --- |
| 固定退出码和相同堆栈，每次启动均复现 | 程序或启动参数缺陷；关联 revision，优先回滚 |
| `connection refused`、DNS、TLS 或鉴权错误 | 依赖/网络/证书；保护依赖，限制重试风暴 |
| startup/liveness probe 失败但进程仍在启动 | 校准真实启动时间后灰度修改探针 |
| ConfigMap/Secret 引用、权限或挂载错误 | 修复声明来源；不得复制 Secret 到日志 |
| lastState 为 `OOMKilled` | 转入 Pod OOM SOP，不通过重启掩盖 |

结论必须给出“已证实”“高概率”和“待验证”层级，不能把日志关键词直接写成确定根因。

### 5. 创建受控处置任务

- 发布后立即出现且旧 revision 健康：创建回滚审批任务。
- 配置错误：提交最小声明式 diff，先在一个非关键副本或测试环境验证。
- 依赖不可用：由依赖 owner 恢复，应用侧限流/退避需单独审批。
- 探针调整：不得永久关闭探针；新阈值必须来自实际启动分布。

经审批执行回滚时：

```bash
kubectl -n "$NAMESPACE" rollout undo "$WORKLOAD_KIND/$WORKLOAD" \
  --to-revision="$PREVIOUS_REVISION"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

## Recovery validation

恢复必须同时满足：

1. 期望副本全部 Ready，availableReplicas 达到策略要求。
2. 每个新 Pod 的 restartCount 在连续两个 5 分钟窗口内不再增长。
3. previous logs 中的根因错误不再出现，错误率、延迟和依赖负载恢复基线。
4. revision、镜像 digest 和配置 resourceVersion 与审批结果一致。

```bash
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
kubectl -n "$NAMESPACE" get pods -l "app.kubernetes.io/name=$WORKLOAD" \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[*].ready,RESTARTS:.status.containerStatuses[*].restartCount,IMAGE:.status.containerStatuses[*].imageID'
```

## Timeout and human escalation

- 关键服务无健康副本、重启风暴冲击依赖或 5 分钟内无法保留 previous logs：立即升级。
- 10 分钟未定位 revision/owner，或一次回滚后仍 CrashLoop：标记 `NEEDS_HUMAN`。
- 涉及 Secret、证书私钥、数据迁移、不可逆 schema 或安全事件：停止自动建议，升级相应 owner。
- KubeOnCall 不得以工作流 `SUCCEEDED` 代替业务恢复验证。

## Rollback

配置或探针变更前保存原始模板和 revision。新变更导致 Ready 副本下降、错误率上升或出现新
退出码时，回滚到 `PREVIOUS_REVISION`；若处置本身是发布回滚，则其回滚动作是重新部署经过
验证的新版本，不得盲目“再回到故障版本”。

```bash
kubectl -n "$NAMESPACE" rollout undo "$WORKLOAD_KIND/$WORKLOAD" \
  --to-revision="$PREVIOUS_REVISION"
kubectl -n "$NAMESPACE" rollout status "$WORKLOAD_KIND/$WORKLOAD" --timeout=10m
```

回滚后重新采集 Pod UID、restartCount、current/previous logs 和 Event，并完整执行 Recovery
validation。

## Evidence to retain

- Pod UID、container、restartCount、lastState、exitCode、current/previous log 片段。
- Event、ownerReference、revision、镜像 digest、探针和配置 resourceVersion。
- 关联告警/变更、审批 ID、执行 ID、恢复窗口和回滚结果。
- SOP 引用固定为 `runbook-pod-crashloop@v2`。

## Acceptance scenario

专用演练清单的 `crashloop-exit-42` 使用真实 kubelet 重启容器，并在 previous log 写入
`KOC_ACCEPTANCE_CRASH_EXIT_42`。预期诊断引用退出码与 previous log，不能误判为 OOM、调度
或节点故障。
