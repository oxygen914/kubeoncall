# 2026-07-30 四类 Kubernetes 故障 SOP 真实环境验收记录

## 1. 验收结论

本轮在本机真实 Minikube 环境中完成了 Pending、CrashLoopBackOff、OOMKilled 和 Node
NotReady 四类故障的首次端到端演练。四次最终持久化 Ask 均使用真实
`aliyun-dashscope/qwen-plus`，均命中版本化 v2 SOP，并关联真实 Kubernetes/Prometheus
证据。

准确结论是：

- **四类故障的 L1 只读诊断主链路已完成一轮真实验证。**
- Pending 与 CrashLoopBackOff 的主要诊断证据满足本轮标准。
- OOMKilled 因 previous log 采集语义和内存时间线缺失，记为受限通过。
- Node NotReady 已完成真实停机与人工恢复，但统一 Evidence 尚未直接纳入 Lease 和受影响
  Pod，记为部分通过。
- AI 没有执行任何变更；临时 Worker 的停止、启动和清理由验收人员在测试环境手工完成。
- 本轮不是 L3/L4 受控变更、回滚或“独立解决运营问题”验收，也不是生产验收。

## 2. 环境

| 项目                 | 现场值                                                       |
| -------------------- | ------------------------------------------------------------ |
| 日期                 | 2026-07-30                                                   |
| kube context/profile | `kubeoncall-monitoring`                                      |
| 集群                 | Docker Driver Minikube，1 个 control-plane + 1 个临时 Worker |
| control-plane        | `kubeoncall-monitoring`                                      |
| 临时 Worker          | `kubeoncall-monitoring-m02`                                  |
| 故障 Namespace       | `kubeoncall-aiops-acceptance`                                |
| KubeOnCall 后端      | Docker Compose，`127.0.0.1:18080`                            |
| Console              | `127.0.0.1:8081`                                             |
| Prometheus           | `127.0.0.1:9090`，真实 kube-state-metrics                    |
| Loki                 | `127.0.0.1:3100`                                             |
| Planner              | `REAL_MODEL / aliyun-dashscope / qwen-plus / HEALTHY`        |
| Kubernetes Adapter   | Bearer 鉴权、cluster/Namespace allowlist、只读 RBAC          |

真实模型 Key 和 Kubernetes Token 仅从已有本地 Secret/运行中容器环境传递，未写入仓库、
Compose 覆盖文件或本文。

## 3. 本轮新增和修复

### 3.1 版本化 SOP

| SOP                        | 版本 | 策略            |
| -------------------------- | ---- | --------------- |
| `runbook-cluster-capacity` | v2   | `diagnose-only` |
| `runbook-pod-crashloop`    | v2   | `diagnose-only` |
| `runbook-pod-oom`          | v2   | `diagnose-only` |
| `runbook-node-notready`    | v2   | `diagnose-only` |

每份 SOP 均补齐：

- 适用范围和排除条件。
- 真实 Kubernetes/日志/指标取证命令。
- 风险分层和最小处置建议。
- Recovery validation。
- Timeout and human escalation。
- Rollback。
- Evidence retention。
- 可重复 Acceptance scenario。

### 3.2 证据链修复

1. 外部 `knowledge.searchSop` 不可用时，Planner 使用项目内真实 RAG，而不是模拟 SOP。
2. 本地 RAG 结果标记为 `knowledge.rag.local / LOCAL_RAG / simulation=false`。
3. 显式 `runbook-id@version` 使用元数据精确过滤。
4. Planner 上下文会把记忆和 Skill 放在当前问题前面，因此精确筛选改为优先最后一个显式
   runbook 引用，确保当前用户选择不被旧会话覆盖。
5. Kubernetes 资源证据增加 Pod container lastState/exitCode 和 Node conditions 等结构化
   字段，避免只保留“Running/NotReady”摘要。
6. Prometheus 证据容器使用可覆盖的高位 Minikube 网络地址，避免临时 Worker 停机后地址被
   复用、阻断恢复。

## 4. 最终执行结果

| 场景             | executionId                            | 终态      | SOP                           | 置信度       | 本轮判断 |
| ---------------- | -------------------------------------- | --------- | ----------------------------- | ------------ | -------- |
| Pending          | `exe_b780eb6d816e4e8ca3eca928dac9ec6d` | SUCCEEDED | `runbook-cluster-capacity@v2` | MEDIUM / 73% | 通过     |
| CrashLoopBackOff | `exe_a36c0603cdd64e2bab80a651a0d6f5af` | SUCCEEDED | `runbook-pod-crashloop@v2`    | MEDIUM / 75% | 通过     |
| OOMKilled        | `exe_8ff552cf3aac48c99a1c434fa657cfa6` | SUCCEEDED | `runbook-pod-oom@v2`          | LOW / 49%    | 受限通过 |
| Node NotReady    | `exe_46f8e4c2aacd40dda577774bd572ae71` | SUCCEEDED | `runbook-node-notready@v2`    | MEDIUM / 75% | 部分通过 |

所有最终执行：

- 进入持久化 Execution/Task/节点时间线。
- Planner 为真实模型 `qwen-plus`。
- SOP 来源为 `knowledge.rag.local`，不是模拟。
- 任务风险为 LOW，只执行查询类工具。
- `approvalRequired=false` 的对象是只读查询。
- 没有 Kubernetes 变更 operation、审批绕过或伪造恢复结果。

## 5. 场景证据

### 5.1 Pending

真实 Pod：

```text
name=pending-node-selector
phase=Pending
scheduled=false
```

真实调度器 Event 包含：

```text
FailedScheduling
node(s) didn't match Pod's node affinity/selector
Preemption is not helpful for scheduling
```

Ask 同时引用：

- `SOP@knowledge.rag.local=SUCCEEDED`
- `METRIC@prometheus=SUCCEEDED`
- `RESOURCE_STATE@kubernetes-api=SUCCEEDED`
- 多条 `K8S_EVENT@kubernetes-api=SUCCEEDED`
- current/previous log 为 `EMPTY`，符合未调度 Pod 的事实

结论正确指向 selector/affinity 约束，没有把 Pending 伪造成 CPU 容量不足。

### 5.2 CrashLoopBackOff

真实 kubelet 状态：

```text
status=CrashLoopBackOff
lastState.reason=Error
lastState.exitCode=42
```

真实日志：

```text
KOC_ACCEPTANCE_CRASH_EXIT_42: deterministic application startup failure
```

真实 Event 包含 `Back-off restarting failed container crashloop`。Ask 命中
CrashLoopBackOff SOP，并引用资源状态、Event、日志和 Prometheus。

在一次采集窗口内，CRI previous-log 端点返回了
`unable to retrieve container logs for docker://...` 文本，Evidence 当时将其保存在成功日志
中；稍后直接 `kubectl logs --previous` 已能读取真实 marker。该波动不影响退出码 42 和
CrashLoop 主事实，但说明后续必须把此类 kubelet 文本规范化为 `EMPTY/UNAVAILABLE`，不能
作为成功业务日志。

### 5.3 OOMKilled

真实 kubelet/cgroup 状态：

```text
lastState.reason=OOMKilled
lastState.exitCode=137
memory.limit=16Mi
```

真实日志：

```text
KOC_ACCEPTANCE_OOM_ALLOCATING: PID 1 will exceed the 16Mi cgroup limit
```

Ask 命中 OOMKilled SOP，引用资源状态、BackOff Event、current log 和集群指标。置信度保持
LOW / 49%，原因包括：

- 缺少容器 working set 到 limit 的 Prometheus 时间线。
- 缺少 requests/limits、Node MemoryPressure 的完整统一证据。
- 本轮 previous-log 采集出现上述 CRI 文本语义问题。

系统没有仅凭日志关键词宣称内存泄漏，也没有建议无限提高 limit，因此记为受限通过。

### 5.4 Node NotReady

临时 Worker 停止后的真实状态：

```text
node=kubeoncall-monitoring-m02
Ready=Unknown
reason=NodeStatusUnknown
message=Kubelet stopped posting node status
```

Lease 在故障期间停留在：

```text
2026-07-30T05:38:50.071175Z
```

Prometheus 同时显示：

```text
nodes total=2
ready=1
notReady=1
```

`node-unavailable-witness` 调度在该 Worker，并在节点停止后进入不可正常收敛的状态。

首轮 Node Ask 暴露了真实缺陷：旧会话中的
`runbook-pod-crashloop@v2` 位于当前问题之前，本地 RAG 取首个显式 runbook，导致错误 SOP。
修复后重新构建、部署并提交最终 execution，结果正确命中
`runbook-node-notready@v2`，Node conditions 与 Prometheus 证据均为成功。

统一 Execution 仍将 Lease、受影响 Pod 列为 missingSignals，Node Event 为空，且不适用于
Node 的 Pod log 查询返回 `HttpStatusError`。因此本场景只记为部分通过。

## 6. 恢复验证

节点恢复由验收人员手工执行，不是 AI 自动操作。

首次启动 Worker 失败：

```text
failed to set up container networking: Address already in use
```

只读检查确认停止的 Worker 保留 `192.168.58.3`，而 Prometheus 动态加入 Minikube 网络后
复用了同一地址。释放冲突、启动 Worker、再将 Prometheus 接回网络后：

```text
node/kubeoncall-monitoring-m02 condition met
Ready=True
reason=KubeletReady
Lease=2026-07-30T06:31:02.869191Z
```

`node-unavailable-witness` 随 kubelet 恢复完成删除；Prometheus 重启后
`count(kube_node_info)=2`。据此确认本次真实 Node 故障的人工恢复路径成立。

仓库已将 Prometheus 的 Minikube 网络地址改为高位、可覆盖配置，避免再次抢占低位 Worker
地址。该处理仅适用于本地 Docker Driver Minikube。

## 7. 自动化和构建

| 验证项                                                     | 结果                                                       |
| ---------------------------------------------------------- | ---------------------------------------------------------- |
| SOP catalog、Manifest 校验、Planner/RAG、Evidence 聚焦测试 | 隔离快照 17 个测试通过，0 failure，0 error                 |
| 显式 SOP 被旧上下文覆盖的回归测试                          | 已包含在上述 17 个测试中                                   |
| Checkstyle                                                 | 0 violation                                                |
| 本次隔离后端镜像构建                                       | BUILD SUCCESS                                              |
| 镜像内 Spotless                                            | 913 个 Java 文件 clean                                     |
| 镜像内 Checkstyle                                          | 0 violation                                                |
| 浏览器持久化 Ask                                           | 四个最终 execution 均到达 SUCCEEDED                        |
| Kubernetes 真实状态检查                                    | Pending、Error/42、OOMKilled/137、NodeStatusUnknown 均实证 |

隔离镜像和最终聚焦测试使用“当前 Git 基线 + 本次 SOP/证据链及其必要 Skill 策略依赖”
构建，以避免把共享工作区中并行进行的其他 Skill/诊断改动混入本轮镜像。该结果不替代最终
合并后的全仓门禁。

## 8. 首轮发现项的代码收口与剩余门禁

本节在不改写第 4～6 节历史现场结果的前提下，同步首轮验收后完成的代码修复。标记为“代码
完成”只表示实现和自动化通过，必须重新部署后才可把对应实景结论升级为通过。

### 8.1 已完成代码修复、待实景复验

| 首轮缺口 | 本轮代码修复 | 当前判定 |
| --- | --- | --- |
| Node 缺 Lease、目标节点 Pod、owner、PDB 和剩余容量 | 只读 Adapter 直接读取 Node Lease，并在允许 Namespace 内关联 Pod、直接 owner、PDB、requests 和剩余 allocatable；明确标记影响面不是集群全量 | 代码完成，Node 场景待复跑 |
| previous-log 把 kubelet/CRI 错误文本当作成功日志 | 无历史容器映射为 `EMPTY/PREVIOUS_LOG_EMPTY`；kubelet/CRI 不可读映射为 `UNAVAILABLE/PREVIOUS_LOG_UNAVAILABLE` | 代码完成，CrashLoop/OOM 待复跑 |
| `/ask` 刷新后 Namespace 范围可能丢失 | cluster/environment/namespace 写入本地存储，刷新后先恢复并使用实时目录校验；无效范围自动重置 | 代码和前端回归完成 |
| OOM 缺 working set/RSS/limit、requests/limits 和 Node MemoryPressure | Prometheus 增加 Pod 精确、有界时间线；Pod 资源证据增加 requests/limits 和所在节点 conditions | 代码完成，OOM 场景待复跑 |
| 告警、变更未使用同一 evidence window | Evidence Orchestrator 从 MySQL 读模型采集同一 execution/window 的 `ALERT` 和 `CHANGE_EVENT` | 代码完成，四场景待复跑 |
| Closure 阶段、稳定窗口和升级事实不持久 | Redis 保留可续跑图状态；MySQL V20 持久化 Closure/Escalation 事实；稳定 operationId、Deployment 操作标记、未知执行结果收敛、稳定窗口、回滚同 ID 重试/fencing/有界轮询复验和 Incident 缺失时 `PENDING_MANUAL` 已覆盖 | 代码及真实 MySQL 8 测试完成，故障注入待执行 |
| 读写 Kubernetes Adapter 未隔离 | 只读和变更进程、端点、Token、ServiceAccount/RBAC、断路器分离；未配置变更端点时返回 503；新增受控变更 Adapter 的 action/Namespace/config key allowlist、UID/Generation guard、ConfigMap 幂等账本、同 operation 并发串行/终态保护、严格配置、探针绕过业务限流、Deployment 操作标记和四类 action 契约 | 代码与 Go 自动化/race 检查完成，测试集群真实变更待验收 |
| 缺少可重复且不污染故障 Pod 的变更目标 | 验收清单新增健康 `remediation-target` Deployment，并为变更 Adapter 单独授予该 Namespace 的最小 Deployment/ReplicaSet RBAC | 清单 dry-run 通过，尚未实际部署演练 |

最终合并工作区的本地门禁结果为：后端 `verify -Popenapi-contract` 938 个测试通过，另有
MySQL/Testcontainers 4 个测试并从空库执行 Flyway V1～V20；前端 96 个单测和 12 个
Playwright 场景通过；Go 全量测试、vet、全部命令构建及变更 Adapter race 检查通过；只读和
变更 Adapter 镜像均构建成功并验证缺少独立长 Token 时拒绝启动。Helm、Compose、工作流 YAML
和 Kubernetes 清单静态检查通过。以上仍不替代远程 CI、新镜像部署和真实变更演练。

### 8.2 仍阻塞 L3/L4 的 P0 门禁

1. 在专用测试集群部署独立变更 Adapter，验证最小 RBAC/Secret/allowlist，并实测同一
   `operationId` 重试不重复变更、参数冲突返回 409、Adapter 重启后账本仍可重放。
2. 在统一审批中心批准后执行至少一种真实测试集群变更，并验证操作后稳定窗口。
3. 完成 Backend/Worker 在 Planner、审批后、验证中和回滚中的进程重启恢复。
4. 完成执行响应丢失、验证超时、自动回滚、回滚复验、Incident 不可用/投递失败的故障注入。
5. 使用包含本轮修复的新镜像重新执行四类场景，并按计划每类补足 3 次。

### 8.3 P1 与环境/发布门禁

1. 当前 Loki 对这些 Kubernetes Pod 没有匹配日志，Kubernetes API logs 仍是主证据；需接通
   集群 Pod 日志采集并验证标签和延迟。
2. Topology、CMDB 等可选外部源仍未接入；不可用时继续显式显示 `UNAVAILABLE`，不得阻塞
   已有本地告警/变更证据。
3. 未执行真实模型 429、5xx、超时、非法 JSON 和依赖断线演练。
4. 当前是本地 Minikube，不是多可用区、真实 CNI/CSI、云节点或生产环境。
5. V19 checksum 漂移属于首轮现场的旧工作区事实；当前仓库已在干净 MySQL 8 中验证
   Flyway V1～V20 全量迁移。已有本地旧库仍应按迁移治理处理，禁止在生产使用
   `validate-on-migrate=false` 绕过校验。

## 9. 能力判定

截至本记录，可以描述为：

> KubeOnCall 已在真实 Minikube 环境完成 Pending、CrashLoopBackOff、OOMKilled 和 Node
> NotReady 四类故障的一轮真实模型、版本化 SOP、持久化只读诊断验证，并能在 Console
> 展示证据来源和置信度。

不能描述为：

> KubeOnCall 已经可以独立解决 Kubernetes 运营问题。

原因是本轮现场 AI 只完成了诊断，没有通过持久化审批执行真实修复。本轮之后虽然已在代码中
补齐 Node Lease/允许范围影响面、OOM 时间线、告警/变更、稳定窗口、回滚和人工升级事实，
但新代码尚未重新部署实证；Loki Pod 日志、独立变更 Adapter 的真实审批/变更、重启/故障
注入、每场景三轮和生产灰度仍未形成完整 L4 闭环。

## 10. 环境清理结果

验收结束后已完成：

- 删除 `kubeoncall-aiops-acceptance` Namespace 及全部故障 Pod。
- 恢复 Adapter allowlist 为 `kubeoncall-system`，Deployment 为 1/1 Ready。
- 删除临时 Worker `kubeoncall-monitoring-m02`。
- 恢复后端 evidence allowlist 为 `kubeoncall-system`。
- control-plane `kubeoncall-monitoring` 保持 Ready。
- Prometheus 使用 `192.168.58.250`，恢复单节点
  `count(kube_node_info)=1`。
- 后端 health 为 UP，真实 Planner canary 为 HEALTHY。

故障 Namespace 和临时 Worker 已删除，不能从集群恢复；需要再次演练时应按
[Kubernetes 故障 SOP 真实演练指南](../../../../guides/Kubernetes故障SOP真实演练指南.md)
重新创建。
