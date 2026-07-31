# Kubernetes 操作闭环接入指南

KubeOnCall 通过 `KUBERNETES_TOOL_ENDPOINT` 调用只读 Kubernetes Tool Adapter，并通过
独立的 `KUBERNETES_MUTATION_TOOL_ENDPOINT` 调用变更 Adapter。两者使用不同端点和 Token；
未配置变更端点时，KubeOnCall 对所有 Kubernetes mutating action 返回
`503 MUTATION_ADAPTER_NOT_CONFIGURED`，不会把变更请求发送到只读端点。

仓库同时提供两个独立进程：

- `kubernetes-tool-adapter`：**只读证据适配器**，用于采集资源状态、Kubernetes Events 和
  Pod current/previous logs；收到变更动作时仍返回 `403 READ_ONLY_MODE`。
- `kubernetes-mutation-adapter`：**受控变更适配器**，只接受显式 action/Namespace allowlist
  中的 Deployment 变更，并使用独立 Token、RBAC 和 Kubernetes ConfigMap 幂等账本。

变更 Adapter 代码和本地清单已经具备，但仓库默认不部署、不启用；真实测试集群中的审批、
超时、断点恢复、回滚和灰度验收仍必须单独完成。不能因为镜像和清单存在，就宣称生产变更闭环
已经验收完成。

## 当前只读证据适配器

代码和部署入口：

- 二进制：`sandbox-controller/cmd/kubernetes-tool-adapter`
- 只读实现：`sandbox-controller/internal/kubetooladapter`
- 容器镜像：`sandbox-controller/Dockerfile.kubernetes-tool-adapter`
- 本地测试集群清单：`deploy/kubernetes/kubernetes-tool-adapter-readonly.yaml`
- Compose 到 Minikube 的网络桥：`docker-compose.kubernetes-evidence.yml`

### 只读 action

| action | 用途 | 返回 |
| --- | --- | --- |
| `describeResource` | 获取 Pod、Node、Deployment、StatefulSet、DaemonSet 当前状态；Pod 包含 requests/limits 和所在 Node conditions，Node 包含 Lease 与允许范围影响面 | 单个规范化资源状态 |
| `describeWorkload` | 获取 Deployment、StatefulSet、DaemonSet 当前状态 | 单个规范化工作负载状态 |
| `getPods` | 根据工作负载 selector 获取受限数量的 Pod | `items` |
| `queryEvents` | 按 Namespace、资源名称/UID 和时间窗读取 Events | `items` |
| `queryPodLogs` / `queryLogs` | 读取 Pod current 或 previous logs | `items` |

### 强制安全边界

1. 启动时必须提供至少 32 字符的 Bearer Token、稳定 `clusterId` 和非空 Namespace
   allowlist；缺少任意一项时进程拒绝启动。
2. 每个请求必须携带 `Authorization: Bearer <token>`，Token 只从 Secret 注入，不写入仓库。
3. 请求的 cluster 和 Namespace 必须同时命中适配器与 KubeOnCall 两层 allowlist。
4. ServiceAccount 只授予 `get/list/watch`；本地清单只允许读取配置 Namespace 中的
   Pod、logs、Events、PDB 和 apps 工作负载，并额外只读 Node 与 `kube-node-lease` Lease。
5. 请求体、并发数、超时、Events 数、Pod 数、日志行数和日志字节数均有硬上限。
6. `rolloutRestart`、`rolloutUndo`、`scaleWorkload`、`patchConfig` 始终返回
   `403 READ_ONLY_MODE`。
7. 本地 NodePort 只用于 Docker Desktop/Minikube 联调。生产应使用 ClusterIP、
   NetworkPolicy 和平台托管 Secret。
8. `KUBERNETES_TOOL_ADAPTER_ALLOWED_CONFIG_KEYS` 默认为空；只有显式列出的普通环境变量
   才会进入变更前快照，`valueFrom` 不读取也不展示。
9. Backend 将只读调用计入 `kubernetes` 断路器，将变更调用计入
   `kubernetes-mutation` 断路器；HTTP 408、429、5xx、超时、传输和超大响应属于依赖故障，
   业务 4xx 不会误开断路器。

### 本地 Minikube 接入

```bash
docker build \
  -f sandbox-controller/Dockerfile.kubernetes-tool-adapter \
  -t kubeoncall/kubernetes-tool-adapter:dev \
  sandbox-controller

minikube image load \
  kubeoncall/kubernetes-tool-adapter:dev \
  -p kubeoncall-monitoring

kubectl create namespace kubeoncall-system --dry-run=client -o yaml |
  kubectl apply -f -

openssl rand -hex 32 |
  kubectl -n kubeoncall-system create secret generic kubernetes-tool-adapter-auth \
    --from-file=token=/dev/stdin \
    --dry-run=client -o yaml |
  kubectl apply -f -

kubectl apply -f deploy/kubernetes/kubernetes-tool-adapter-readonly.yaml
kubectl -n kubeoncall-system rollout status deployment/kubernetes-tool-adapter
```

启动 KubeOnCall 时，必须把同一个 Secret 的值注入
`KUBERNETES_TOOL_BEARER_TOKEN`；`KUBERNETES_TOOL_ENDPOINT` 和该 Token 只用于只读
采集。叠加本地桥接配置：

```bash
KUBERNETES_TOOL_BEARER_TOKEN="$(
  kubectl -n kubeoncall-system get secret kubernetes-tool-adapter-auth \
    -o jsonpath='{.data.token}' | base64 --decode
)" docker compose \
  -f docker-compose.yml \
  -f docker-compose.kubernetes-evidence.yml \
  up -d --no-deps --force-recreate kubeoncall
```

该覆盖文件只启用资源状态、Events 和 Pod logs 证据，且只允许：

- cluster：`local`
- Namespace：`kubeoncall-system`

扩展到其他 Namespace 时，必须同时增加 Kubernetes Role/RoleBinding 和两层 allowlist；
不能只放宽其中一层。

Node 影响面只统计两层 allowlist 内的 Namespace，并在 Evidence 中返回：

```json
{
  "impactScope": {
    "coverage": "ALLOWED_NAMESPACES",
    "complete": false
  }
}
```

`remainingAllocatableWithinScope` 是 Node allocatable 减去允许范围内已采集 Pod requests 的
结果，不等同于集群级调度器的完整剩余容量。需要全量影响面时，应另行设计经安全评审的
ClusterRole 和数据聚合边界。

## HTTP 契约

请求统一使用：

```json
{
  "executor": "kubernetes",
  "action": "describeResource",
  "parameters": {
    "cluster": "local",
    "namespace": "kubeoncall-system",
    "resourceKind": "Pod",
    "resourceName": "example-pod",
    "resourceUid": "optional-but-recommended"
  }
}
```

适配器在 HTTP 2xx 时返回**原始业务 JSON**，例如：

```json
{
  "summary": "Pod/example-pod is Running",
  "phase": "Running",
  "resource": {
    "kind": "Pod",
    "name": "example-pod",
    "uid": "..."
  }
}
```

KubeOnCall 的 `ToolHttpClient` 会在进程内部将其包装为：

```json
{
  "status": "success",
  "httpStatus": 200,
  "response": {
    "summary": "Pod/example-pod is Running"
  }
}
```

因此适配器不能再额外返回一层 `status/httpStatus/response`，否则 Evidence Collector 会看到
错误的嵌套结构。非 2xx 使用稳定的 `errorType` 和安全错误消息，不能回传 Kubernetes 内部端点、
凭据或原始鉴权详情。

## 独立受控变更适配器

代码和部署入口：

- 二进制：`sandbox-controller/cmd/kubernetes-mutation-adapter`
- 受控变更与幂等账本：`sandbox-controller/internal/kubetooladapter`
- 容器镜像：`sandbox-controller/Dockerfile.kubernetes-mutation-adapter`
- 本地测试集群清单：`deploy/kubernetes/kubernetes-mutation-adapter.yaml`

当前实现只操作 Deployment，并支持：

| action | 当前行为 | 失败关闭条件 |
| --- | --- | --- |
| `scaleWorkload` | 把副本数设置为显式目标值，并标记 Deployment metadata | 超过 `MAX_REPLICAS`、UID/Generation 漂移或回滚前副本不匹配 |
| `rolloutRestart` | 写入由 `operationId` 派生的确定性 Deployment 与 Pod Template annotation | UID/Generation 漂移 |
| `rolloutUndo` | 恢复指定 revision 的受控 ReplicaSet Pod Template，并标记 Deployment metadata | revision 不存在、Owner 不匹配，或补偿请求缺少/不匹配 `rollbackOfOperationId` |
| `patchConfig` | 只修改 allowlist 中、已经存在且不是 `valueFrom` 的普通环境变量，并标记 Deployment metadata | key 未授权、值过大、旧值冲突或 UID/Generation 漂移 |

每个请求必须同时携带稳定 `operationId`、变更前采集的 `expectedResourceUid` 和
`expectedGeneration`。缺少这些 guard 时 Adapter 返回 `409 MUTATION_GUARD_REQUIRED`。

### 本地构建和部署

```bash
docker build \
  -f sandbox-controller/Dockerfile.kubernetes-mutation-adapter \
  -t kubeoncall/kubernetes-mutation-adapter:dev \
  sandbox-controller

minikube image load \
  kubeoncall/kubernetes-mutation-adapter:dev \
  -p kubeoncall-monitoring

kubectl create namespace kubeoncall-operations --dry-run=client -o yaml |
  kubectl apply -f -

openssl rand -hex 32 |
  kubectl -n kubeoncall-operations create secret generic kubernetes-mutation-adapter-auth \
    --from-file=token=/dev/stdin \
    --dry-run=client -o yaml |
  kubectl apply -f -

kubectl apply -f deploy/kubernetes/kubernetes-mutation-adapter.yaml
kubectl -n kubeoncall-operations rollout status deployment/kubernetes-mutation-adapter
```

清单默认只开放 `rolloutRestart,rolloutUndo,scaleWorkload`，目标 Namespace 为
`kubeoncall-system`，账本写入独立 `kubeoncall-operations` Namespace。启用 `patchConfig`
前，必须在只读和变更 Adapter 同时配置相同的显式 config key allowlist。

把下列变量注入 KubeOnCall：

```text
KUBERNETES_MUTATION_TOOL_ENDPOINT=http://kubernetes-mutation-adapter.kubeoncall-operations.svc.cluster.local:8080/api/tools/kubernetes
KUBERNETES_MUTATION_TOOL_BEARER_TOKEN
```

Helm 对应值为：

```yaml
config:
  kubernetesMutationToolEndpoint: "http://kubernetes-mutation-adapter.kubeoncall-operations.svc.cluster.local:8080/api/tools/kubernetes"
  kubernetesMutationToolAuthEnabled: true
secrets:
  existingSecret: kubeoncall-secrets
  kubernetesMutationToolBearerTokenKey: kubernetes-mutation-tool-bearer-token
```

变更 Token 不得复用只读 Token。配置状态可在 Integrations API/Console 中以
`kubernetes-mutation / GOVERNED_MUTATION` 查看，但“已配置”只代表路由和凭据存在，不代表
测试集群演练或生产验收已经通过。

## 变更适配器契约

读写能力保持物理分离：

| 端点 | action | 用途 |
| --- | --- | --- |
| 只读 Adapter | `describeWorkload` | 变更前快照、变更后验证、回滚后验证 |
| 变更 Adapter | `rolloutRestart` | 确定性滚动重启 |
| 变更 Adapter | `rolloutUndo` | 恢复到快照中的 Deployment revision |
| 变更 Adapter | `scaleWorkload` | 扩缩容及副本数回滚 |
| 变更 Adapter | `patchConfig` | allowlist 普通环境变量变更及旧值回滚 |

## 幂等要求

所有变更和回滚请求都包含稳定的 `parameters.operationId`。当前实现：

1. 在独立账本 Namespace 中先创建 `koc-op-<operationId hash>` ConfigMap，再调用
   Kubernetes mutation API。
2. 账本只保存请求哈希、安全结果和稳定错误，不保存 Token 或配置值。
3. 同一 `operationId` 的已完成请求直接返回首次结果并标记 `replayed=true`。
4. Adapter/网络在 Kubernetes 响应前后超时时，账本保持 `PENDING`；重试用同一
   `operationId` 对确定性目标状态进行 reconcile，避免重复副作用。
5. 不同参数复用同一 `operationId` 时返回 HTTP `409 OPERATION_ID_REUSED`。
6. 终态账本默认保留 30 天并由 Adapter 定时清理；`PENDING` 代表未知外部结果，自动清理
   不会删除。保留期必须大于工作流最大重试/审计窗口。
7. 单个 Adapter 进程会把相同 `operationId` 的并发请求串行化；账本一旦进入
   `SUCCEEDED/FAILED`，迟到结果不能反向覆盖终态。
8. 仓库清单固定 `replicas: 1` 和 `Recreate`。在实现跨副本分布式互斥前，不得把变更
   Adapter 横向扩容；这不影响不同 operation 的有界并发。
9. 每次 mutation 都把 `operationId` 的 SHA-256 截断值写入 Deployment metadata 的
   `ops.kubeoncall.io/operation-id`；restart 还写入 Pod Template。只读
   `describeWorkload` 返回 `operationMarker`，后端借此区分“本次操作已生效”和“工作负载
   原本就健康”。
10. 每个补偿请求必须携带 `rollback=true` 和原变更的 `rollbackOfOperationId`。Adapter
    只有在 Deployment 仍保留原操作标记，或已经写入同一回滚操作标记时才继续，避免资源
    漂移后误回滚；响应未知时继续复用相同 rollback `operationId`。
11. 请求限流只包围业务 POST；`/healthz` 与 `/readyz` 不占用业务并发槽，因此业务饱和时
    编排器仍可判断进程和依赖是否可用。

账本生命周期由以下变量控制：

```text
KUBERNETES_MUTATION_ADAPTER_LEDGER_RETENTION=720h
KUBERNETES_MUTATION_ADAPTER_LEDGER_CLEANUP_INTERVAL=1h
```

`KUBERNETES_MUTATION_ADAPTER_REQUEST_TIMEOUT`、
`KUBERNETES_MUTATION_ADAPTER_SHUTDOWN_TIMEOUT`、
`KUBERNETES_MUTATION_ADAPTER_MAX_REQUEST_BYTES`、
`KUBERNETES_MUTATION_ADAPTER_MAX_CONCURRENT`、
`KUBERNETES_MUTATION_ADAPTER_MAX_REPLICAS`、
`KUBERNETES_MUTATION_ADAPTER_MAX_CONFIG_VALUE_BYTES` 及上述账本 duration 均严格解析为
正值；配置非法时进程拒绝启动，不会静默回退到默认值。账本保留时间必须大于清理间隔。

### 专用验收 Namespace

`kubernetes-mutation-adapter.yaml` 默认只允许 `kubeoncall-system`，不会自动获得验收
Namespace 的变更权限。使用
`deploy/kubernetes/aiops-acceptance-scenarios.yaml` 中的健康 `remediation-target` 前，必须：

1. 应用验收场景清单，创建独立 Namespace、目标 Deployment 和最小 RoleBinding。
2. 把只读 Adapter、变更 Adapter 与 KubeOnCall 后端的 Namespace allowlist 同步增加
   `kubeoncall-aiops-acceptance`；只改其中一层仍会失败关闭。
3. 仅对 `remediation-target` 执行审批后的 scale/restart/undo/patch 演练，不把四个故障 Pod
   当作变更目标。
4. 演练结束后核对 operation 账本、Closure/Escalation 事实和资源状态，再清理验收 Namespace。

这一步属于真实测试集群验收，不包含在镜像构建或清单 dry-run 结论中。

## 变更版 `describeWorkload` 请求

```json
{
  "executor": "kubernetes",
  "action": "describeWorkload",
  "parameters": {
    "namespace": "prod",
    "target": "payment-service",
    "executionId": "exec_xxx",
    "verificationPhase": "PRE_EXECUTION",
    "expectedState": {}
  }
}
```

`verificationPhase` 取值：

- `PRE_EXECUTION`：必须返回足以构造回滚的真实快照。
- `POST_EXECUTION`：验证变更是否达到期望状态。
- `POST_ROLLBACK`：验证旧状态是否恢复。

## KubeOnCall 内部快照视图

以下示例是 KubeOnCall 包装后的内部视图。适配器自身仍只返回 `response` 中的业务字段。

扩缩容至少形成：

```json
{
  "status": "success",
  "httpStatus": 200,
  "response": {
    "resource": {
      "kind": "Deployment",
      "name": "payment-service",
      "uid": "..."
    },
    "generation": 7,
    "desiredReplicas": 2,
    "readyReplicas": 2
  }
}
```

配置变更至少返回：

```json
{
  "status": "success",
  "httpStatus": 200,
  "response": {
    "resource": {
      "kind": "Deployment",
      "name": "payment-service",
      "uid": "..."
    },
    "generation": 7,
    "configuration": {
      "REQUEST_TIMEOUT": "3s"
    }
  }
}
```

滚动重启至少返回：

```json
{
  "status": "success",
  "httpStatus": 200,
  "response": {
    "resource": {
      "kind": "Deployment",
      "name": "payment-service",
      "uid": "..."
    },
    "generation": 7,
    "observedGeneration": 7,
    "updatedReplicas": 3,
    "revision": 12,
    "desiredReplicas": 3,
    "readyReplicas": 3,
    "operationMarker": "32-character-operation-hash"
  }
}
```

缺少对应快照字段时，KubeOnCall 不会执行该变更。

## KubeOnCall 内部恢复验证视图

适配器可以返回 `verificationStatus` 和 `reason`，KubeOnCall 包装后形成：

```json
{
  "status": "success",
  "httpStatus": 200,
  "response": {
    "verificationStatus": "HEALTHY",
    "reason": "all replicas are ready"
  }
}
```

`verificationStatus` 支持：

- `HEALTHY`、`SUCCEEDED`、`READY`、`RECOVERED`：验证成功。
- `PENDING`、`RUNNING`：继续轮询，直到超时。
- `FAILED`、`UNHEALTHY`、`DEGRADED`：立即进入回滚。

也可以返回 `desiredReplicas`、`readyReplicas`、`updatedReplicas`、`generation`、
`observedGeneration`、`configuration`、`revision` 和 `operationMarker`，由 KubeOnCall 与
`expectedState` 比较。对于受控变更，`operationMarker` 必须与本次 `operationId` 派生值
一致；即使适配器返回 `verificationStatus=HEALTHY`，缺少本次标记也不会确认成功。滚动重启和
配置变更还必须满足 generation 已被观测、updated/ready replicas 已收敛。

## 超时、回滚和升级

默认策略：

- 验证超时：120 秒。
- 轮询间隔：5 秒。
- 健康稳定窗口：30 秒；只有连续健康达到该窗口才标记 `VERIFIED`。
- 验证失败或超时：优先执行快照对应的补偿回滚。
- 回滚使用独立 rollback `operationId`，并在同一超时边界内反复调用
  `describeWorkload`；只有回滚操作标记和快照目标状态同时收敛才算成功。
- 回滚调用本身返回 5xx/超时时，以相同 rollback `operationId` 有界重试；Adapter 同时用
  `rollbackOfOperationId` 和 Deployment 操作标记 fencing。已知 4xx 拒绝不重试。
- 无法回滚或回滚验证失败：调用事件中心升级人工。
- 即使回滚成功，原执行仍标记为失败，并保留 `ROLLED_BACK` 闭环状态，避免把业务目标未达成误报为成功。
- 变更 Adapter 最终返回 5xx 或超时时，结果视为“外部状态未知”，后端先执行独立状态验证，
  再决定成功或回滚；已知的策略/参数拒绝直接记录为 `DISPATCH_REJECTED`，不会伪装成已执行。

可通过以下环境变量调整：

```text
KUBEONCALL_POST_EXECUTION_VERIFICATION_ENABLED
KUBEONCALL_POST_EXECUTION_VERIFICATION_TIMEOUT_SECONDS
KUBEONCALL_POST_EXECUTION_VERIFICATION_POLL_MILLIS
KUBEONCALL_POST_EXECUTION_VERIFICATION_STABLE_WINDOW_SECONDS
KUBEONCALL_AUTOMATIC_ROLLBACK_ENABLED
KUBEONCALL_POST_EXECUTION_ESCALATION_ENABLED
```

关闭恢复验证时，KubeOnCall 会阻止变更操作，而不是退化为无验证执行。

## 闭环事实和恢复边界

- Redis Graph state/checkpoint 是工作流续跑状态，负责 Worker/Backend 恢复后继续节点执行。
- MySQL V20 的 `koc_operation_closure_fact` 记录单个 operation 的
  `PREPARED/VERIFYING/STABILIZING/VERIFIED/ROLLING_BACK/ROLLED_BACK/ESCALATED/
  DISPATCH_REJECTED` 等脱敏审计读投影。
- `koc_operation_escalation_fact` 记录 Incident 投递或 `PENDING_MANUAL` 人工接管状态。
- MySQL 开启时，如果变更前的 `PREPARED` 事实无法持久化，KubeOnCall 在调用变更 Adapter
  前失败关闭。
- MySQL 事实不是包含凭据或补偿秘密的独立恢复引擎，不能脱离 Redis checkpoint 冒充续跑
  能力。
