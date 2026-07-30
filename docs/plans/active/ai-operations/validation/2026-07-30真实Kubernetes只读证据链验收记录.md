# 2026-07-30 真实 Kubernetes 只读证据链验收记录

## 1. 验收结论

本轮完成并通过了 **Kubernetes 只读证据适配器** 的代码、部署和真实集群读取验收：

- KubeOnCall 已能通过独立 Tool Adapter 读取真实 Kubernetes 资源状态、Events、
  current Pod logs 和 previous Pod logs。
- Adapter 使用 Bearer 鉴权、cluster/Namespace 双重 allowlist、最小只读 RBAC、请求与响应
  上限，并与 KubeOnCall 后端进程隔离。
- 从实际运行的 KubeOnCall 后端容器，经 Minikube Docker 网络和注入的 Secret，成功读取目标
  Pod；不是只在单元测试、Mock 或宿主机端口转发中成立。
- 所有 Kubernetes 变更 action 继续失败关闭，`rolloutRestart` 实测返回
  `403 READ_ONLY_MODE`。
- 真实 Planner canary 为 `HEALTHY`，Kubernetes 三类证据开关已在本地联调实例生效。
- 使用现有登录用户从 Console 提交的持久化 Ask 已完成：Execution、Task、7 个工作流节点、
  Evidence 和 Conclusion 使用同一个 `executionId` 落库。
- 最终回答明确区分 `SUCCEEDED`、`EMPTY` 和 `UNAVAILABLE`；Prometheus 指标、Kubernetes
  资源状态、Event 和 current log 均被结论引用，previous log 为空、SOP 不可用也被如实展示。

本轮结论仅为：**测试环境中，针对单个真实 Pod 的持久化只读诊断证据链验收通过**。不能据此宣称：

- 版本化 SOP、告警和变更事件已完成同窗关联。
- Pending、CrashLoopBackOff、OOMKilled、Node 不可用已经完成故障演练。
- Kubernetes 真实变更、恢复验证、回滚和人工升级已经完成测试集群验收。
- 当前单节点 Minikube 等同于生产或多节点 Kubernetes 环境。

## 2. 环境

| 项目 | 现场结果 |
| --- | --- |
| 日期 | 2026-07-30 |
| kube context | `kubeoncall-monitoring` |
| 集群类型 | 本机 Docker Driver 的单节点 Minikube |
| Kubernetes | v1.35.1 |
| Node | `kubeoncall-monitoring`，Ready，control-plane |
| Adapter Namespace | `kubeoncall-system` |
| Adapter Deployment | `kubernetes-tool-adapter`，1/1 Ready，0 restart |
| KubeOnCall 后端 | Docker Compose，端口 `127.0.0.1:18080` |
| Planner | `REAL_MODEL / aliyun-dashscope / qwen-plus` |

该环境使用真实 Kubernetes API、kubelet、ServiceAccount Token 和 RBAC；但只有一个
control-plane Node，不具备多节点调度、节点故障迁移和生产网络拓扑。

## 3. 实现范围

### 3.1 Adapter

新增独立二进制和容器镜像：

- `sandbox-controller/cmd/kubernetes-tool-adapter`
- `sandbox-controller/internal/kubetooladapter`
- `sandbox-controller/Dockerfile.kubernetes-tool-adapter`

允许的 action：

- `describeResource`
- `describeWorkload`
- `getPods`
- `queryEvents`
- `queryPodLogs` / `queryLogs`

固定拒绝的变更 action：

- `rolloutRestart`
- `rolloutUndo`
- `scaleWorkload`
- `patchConfig`

### 3.2 Kubernetes 权限

`deploy/kubernetes/kubernetes-tool-adapter-readonly.yaml` 创建：

- 专用 Namespace 和 ServiceAccount。
- `kubeoncall-system` 内只读 Pod、Pod logs、Events、Deployment、StatefulSet、
  DaemonSet、ReplicaSet 的 Role/RoleBinding。
- 仅 `get/list` Node 的 ClusterRole/ClusterRoleBinding，用于后续 Node NotReady 证据。
- 非 root、只读根文件系统、删除 Linux capabilities、资源 limits 和健康探针。
- 本地联调用的认证 NodePort；生产必须改为 ClusterIP 和 NetworkPolicy。

### 3.3 KubeOnCall

- Kubernetes Tool 请求增加 Bearer Header，Token 不进入 metadata、日志或仓库。
- Evidence Collector 增加 `RESOURCE_STATE`，并统一采集：
  - `describeResource`
  - `queryEvents`
  - `queryPodLogs(previous=false)`
  - `queryPodLogs(previous=true)`
- HTTP 403 映射为 `FORBIDDEN`，空 previous logs 映射为 `EMPTY`，不伪造成异常或正常日志。
- 能力接口新增 `evidenceK8sResourceState`。
- Compose 和 Helm 增加证据开关、allowlist、Adapter endpoint 和 Secret 注入。

## 4. 真实运行验证

验收目标为 Adapter 自身的 Pod：

```text
cluster: local
namespace: kubeoncall-system
kind: Pod
name: kubernetes-tool-adapter-58dcbdddf5-pt6j2
uid: 64bb8e93-238e-4911-bd84-707f31955fa0
```

Pod 名称和 UID 是本轮现场值，重建 Deployment 后会变化，不应写入固定配置。

严格 Bearer scheme 修正重新部署后的最终现场 Pod 为：

```text
name: kubernetes-tool-adapter-58ff54d4d9-ss25p
uid: 79a275d2-5c76-436d-ad35-c1b731b53fb4
imageID: sha256:e9946485b3b2c45573a81bd957bea4938efdd81d28648052ad26130f57624234
```

| 验证项 | 现场结果 | 判断 |
| --- | --- | --- |
| Adapter health | `{"mode":"read-only","status":"ok"}` | 通过 |
| 未认证请求 | HTTP 401 | 通过 |
| 裸 Token、缺少 Bearer scheme | HTTP 401 | 通过 |
| 资源状态 | Pod `Running`，container Ready，restartCount=0，Node 为 `kubeoncall-monitoring` | 通过 |
| Kubernetes Events | 4 条：Scheduled、Pulled、Created、Started | 通过 |
| current logs | 1 条真实启动日志，`truncated=false` | 通过 |
| previous logs | 当前容器无历史重启，返回 `items=[]` | 通过，未伪造 |
| 变更拒绝 | `rolloutRestart` 返回 HTTP 403、`READ_ONLY_MODE` | 通过 |
| 后端到 Adapter | 后端容器加入 `kubeoncall-monitoring` 网络并使用注入 Token 成功读取同一 Pod | 通过 |
| 后端稳定性 | `status=running`、`restartCount=0` | 通过 |
| Planner canary | `HEALTHY`，最近一次真实模型调用成功 | 通过 |
| capabilities | 资源状态、Events、Pod logs、durable Ask、operation closure、evidence UI 均为 true | 通过 |

后端运行时网络：

```text
kubeoncall_default
kubeoncall-monitoring
```

Adapter Token 由随机值生成并只保存在
`kubeoncall-system/kubernetes-tool-adapter-auth` Secret；未写入 `.env`、Compose 覆盖文件、
文档或 Git。

## 5. 自动化验证

| 验证项 | 结果 |
| --- | --- |
| Adapter 与 Sandbox Controller 全量 Go 测试 | 全部通过 |
| Adapter 鉴权、allowlist、变更拒绝、失败关闭配置测试 | 通过 |
| fake client 的 Events、Pod/Deployment 状态和 workload Pod 选择测试 | 通过 |
| 本次 AI Operations 变更隔离全量 Maven 门禁 | 831 个测试，0 failure，0 error；Spotless、Checkstyle、build success |
| 后端 `KubernetesToolExecutorTest` | 通过 |
| 后端 `KubernetesEvidenceCollectorTest` | 通过 |
| 本轮 Planner、Evidence、Ask 和 Workflow 聚焦测试 | 31 个测试，0 failure，0 error |
| Spotless / Checkstyle（聚焦 Maven 流程） | 通过，0 violation |
| 前端 Vitest | 29 个测试文件、72 个测试通过 |
| 前端 production build | 通过；保留既有大 chunk 警告 |
| Helm 默认模板 | 渲染通过 |
| Helm Kubernetes Tool Secret 条件注入 | 渲染通过 |
| Kubernetes 清单 client dry-run | 通过 |
| Compose Kubernetes evidence 覆盖 | `config --quiet` 通过 |
| 后端容器镜像构建 | 通过 |

全量 Maven 门禁使用“当前提交基线 + 本次 AI Operations 相关文件”的隔离快照执行，避免把同一
共享工作区内尚未完成的通知模块改动计入本次结论。共享工作区的全量门禁仍存在通知模块格式、
final mock 和双 `@Autowired` 构造器问题，因此本记录不宣称整个未提交工作区已经全绿。

## 6. 持久化 Ask 验收

### 6.1 提交与终态

使用现有登录用户在 Console `/ask` 提交：

```text
请只读分析 kubeoncall-system 命名空间中 Pod
kubernetes-tool-adapter-58ff54d4d9-ss25p 的当前状态，结合 Kubernetes Events、
当前日志、上一次日志和知识库 SOP，逐项给出证据片段、来源与置信度；禁止执行任何变更。
```

最终持久化事实：

| 项目 | 结果 |
| --- | --- |
| executionId | `exe_55756f2ec7f6408f9548ccea1ff1049b` |
| taskId | `tsk_fbd69887d7a947218ac1199cbbbf6b04` |
| Execution | `SUCCEEDED / LOW / USER` |
| Task | `SUCCEEDED / COMPLETED / 100% / attempt=1` |
| Planner | `REAL_MODEL / aliyun-dashscope / qwen-plus` |
| 工作流节点 | Planner Query、Planner Think、Executor Think、Verifier、Approval、Execute、Closure 共 7 个，全部 `SUCCEEDED` |
| 推荐动作 | `QUERY_LOGS`，`requiresApproval=false` |
| 审批事实 | 0 条 |
| Conclusion | `PARTIALLY_SUPPORTED / LOW / 0.5300` |

### 6.2 同一 execution 的证据

| 证据 | source | 状态 | 现场摘要 |
| --- | --- | --- | --- |
| `evd_ffbdca9e46ce46de6221ec6d4f0fa9f2` | Prometheus | `SUCCEEDED` | `nodes total=1 ready=1`，目标 Pod 为 `Running` |
| `evd_c6b0dec04b4266763ad46c03764b8dde` | Kubernetes API | `SUCCEEDED` | 资源 UID 匹配，Pod 为 `Running` |
| `evd_2ef667fede3ba741848f2f6b11caf8b6` | Kubernetes API | `SUCCEEDED` | 捕获到 `/readyz` 超时的 Readiness probe Event |
| `evd_a5ec366f351b4473637cccb36d276133` | Kubernetes API | `SUCCEEDED` | current log 包含适配器监听 `:8080` 的真实启动日志 |
| `evd_46560f7946341841d9ded3816fcc5474` | Kubernetes API | `EMPTY` | `PREVIOUS_LOG_EMPTY`，未伪造历史日志 |
| `evd_8b4d5b64c4b361bc50407a9fc8b5ca23` | Loki | `EMPTY` | 当前查询窗口无匹配 Loki 条目 |
| `evd_7100e44a353d77ea6f8ad6801aa55590` | `knowledge.searchSop` | `UNAVAILABLE` | 没有生成伪造 SOP 引用 |

Conclusion `con_5de042a82543c36f9022c2e0265af1e5` 引用了指标、资源状态、Event 和 current
log 四条成功证据，`sop_refs=[]`。由于 SOP、告警、拓扑和 CMDB 证据不可用，结论保持
`PARTIALLY_SUPPORTED / LOW`，没有标记为 `RESOLVED`。

### 6.3 结论一致性修复

首轮真实模型执行虽成功，但模型文字把已经成功采集的 Prometheus 指标误写成
`metrics unavailable`。本轮未将该结果直接判定为通过，而是增加确定性的证据落地层：

1. 真实模型继续负责意图、目标、任务类型和风险判断。
2. 只读 Conclusion 根据实际 `EvidenceItem` 生成，逐项写明 source、collection status 和有界片段。
3. 已有成功证据会清除相应的 `missingSignals`，避免结论与证据状态冲突。
4. `EMPTY` 与 `UNAVAILABLE` 保持不同语义；previous log 为空不等于采集失败。
5. Conclusion 明确写入 `This conclusion does not authorize any mutation`。

修复后重新提交的新 execution 即本节记录的最终通过 execution。

### 6.4 安全边界

- Planner 保留了“禁止执行任何变更”，任务风险为 `LOW`，只调度 `kubernetes.queryLogs`。
- Verifier 返回 `ALLOW` 的对象是只读查询，不是 Kubernetes 变更。
- 没有审批请求、变更 operation 或伪造恢复结果。
- 直接向 Adapter 请求 `rolloutRestart` 仍返回 HTTP 403、`READ_ONLY_MODE`。
- 安全校验前后 Pod UID 均为 `79a275d2-5c76-436d-ad35-c1b731b53fb4`，状态
  `Running/Ready`、restartCount=0；Deployment generation/observedGeneration 均为 3。

### 6.5 本轮修复的问题

- Console 使用旧镜像，仍调用同步 Ask 接口。
- Ask actor/request scope 含 null 时的不可变 Map 异常。
- Workflow 终态 details 含 null 时的持久化异常。
- 中文 Namespace/Pod 范围解析错误，以及“禁止执行任何变更”被误识别成第二个变更任务。
- current/previous 空日志使用相同 content hash 导致证据冲突。
- Planner 未获得统一证据快照，以及模型文字与成功指标证据不一致。

## 7. 下一步

1. 导入带稳定 ID、版本和来源的 Pending、CrashLoopBackOff、OOMKilled、Node NotReady SOP。
2. 建立专用故障 Namespace，并为它单独创建只读 RoleBinding 和 allowlist。
3. 把告警和变更事件接入同一 evidence window，避免只依赖日志、Event 和指标。
4. 依次完成四场景的只读诊断，并保存可重复证据包。
5. 完成真实模型 429、5xx、超时和非法 JSON 故障注入，验证降级路径没有变更旁路。
6. 另行设计具有独立身份、幂等存储、审批门禁和回滚能力的变更 Adapter；不得扩权当前只读
   Adapter。
7. 完成验证超时、回滚、回滚后复验和 Incident 人工升级后，才进入 L3/L4 验收。

## 8. 最终能力边界

截至本记录：

- 可以确认：真实模型健康，真实 Kubernetes 只读证据 Adapter 已部署，资源状态、Events 和
  Pod current/previous logs 已在同一持久化 execution 中完成采集、落库、引用和前端展示，
  安全边界按预期工作。
- 不能确认：助手已经能够独立解决真实运营问题。
- 当前准确描述：**KubeOnCall 已通过单个真实 Pod 的持久化只读诊断验收，仍缺少版本化 SOP、
  告警/变更同窗证据、四场景证据包和受控变更恢复闭环。**
