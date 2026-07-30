# Kubernetes 操作闭环接入指南

KubeOnCall 通过 `KUBERNETES_TOOL_ENDPOINT` 调用 Kubernetes Tool Adapter。仓库当前提供的
`kubernetes-tool-adapter` 是**只读证据适配器**，用于采集资源状态、Kubernetes Events 和
Pod current/previous logs；所有 Kubernetes 变更动作都会返回 `403 READ_ONLY_MODE`。

只有后续独立实现并验收“变更前快照、幂等执行、恢复验证、补偿回滚”契约后，才允许在目标环境
接入变更适配器。不能因为只读适配器已经部署，就宣称真实变更闭环已经完成。

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
| `describeResource` | 获取 Pod、Node、Deployment、StatefulSet、DaemonSet 当前状态 | 单个规范化资源状态 |
| `describeWorkload` | 获取 Deployment、StatefulSet、DaemonSet 当前状态 | 单个规范化工作负载状态 |
| `getPods` | 根据工作负载 selector 获取受限数量的 Pod | `items` |
| `queryEvents` | 按 Namespace、资源名称/UID 和时间窗读取 Events | `items` |
| `queryPodLogs` / `queryLogs` | 读取 Pod current 或 previous logs | `items` |

### 强制安全边界

1. 启动时必须提供至少 32 字符的 Bearer Token、稳定 `clusterId` 和非空 Namespace
   allowlist；缺少任意一项时进程拒绝启动。
2. 每个请求必须携带 `Authorization: Bearer <token>`，Token 只从 Secret 注入，不写入仓库。
3. 请求的 cluster 和 Namespace 必须同时命中适配器与 KubeOnCall 两层 allowlist。
4. ServiceAccount 只授予 `get/list/watch`；本地清单只允许读取
   `kubeoncall-system` 中的 Pod、logs、Events 和 apps 工作负载，并额外只读 Node。
5. 请求体、并发数、超时、Events 数、Pod 数、日志行数和日志字节数均有硬上限。
6. `rolloutRestart`、`rolloutUndo`、`scaleWorkload`、`patchConfig` 始终返回
   `403 READ_ONLY_MODE`。
7. 本地 NodePort 只用于 Docker Desktop/Minikube 联调。生产应使用 ClusterIP、
   NetworkPolicy 和平台托管 Secret。

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
`KUBERNETES_TOOL_BEARER_TOKEN`，并叠加本地桥接配置：

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

## 后续变更适配器契约

下表不是当前只读适配器已经具备的能力，而是启用真实变更前必须满足的接口：

| action | 用途 | 必须支持 |
| --- | --- | --- |
| `describeWorkload` | 变更前快照、变更后验证、回滚后验证 | 是                 |
| `rolloutRestart`   | 滚动重启                           | 是                 |
| `rolloutUndo`      | 恢复到快照中的工作负载 revision    | 使用重启功能时     |
| `scaleWorkload`    | 扩缩容及副本数回滚                 | 使用扩缩容功能时   |
| `patchConfig`      | 配置变更及旧值回滚                 | 使用配置变更功能时 |

## 幂等要求

所有变更和回滚请求都包含稳定的 `parameters.operationId`。适配器必须：

1. 在目标集群范围内持久化 `operationId` 与首次处理结果。
2. 同一个 `operationId` 的重复请求不得重复执行变更。
3. 重复请求返回首次请求的最终结果；处理中可返回 `status=success` 和 `response.verificationStatus=PENDING`。
4. 不得把不同参数复用同一个 `operationId`；发现冲突时返回 HTTP `409`。

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
    "configuration": {
      "timeout": "3s"
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
    "revision": 12,
    "desiredReplicas": 3,
    "readyReplicas": 3
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

也可以返回 `desiredReplicas`、`readyReplicas`、`configuration`、`revision`，由 KubeOnCall 与 `expectedState` 比较。

## 超时、回滚和升级

默认策略：

- 验证超时：120 秒。
- 轮询间隔：5 秒。
- 验证失败或超时：优先执行快照对应的补偿回滚。
- 回滚后再次调用 `describeWorkload`。
- 无法回滚或回滚验证失败：调用事件中心升级人工。
- 即使回滚成功，原执行仍标记为失败，并保留 `ROLLED_BACK` 闭环状态，避免把业务目标未达成误报为成功。

可通过以下环境变量调整：

```text
KUBEONCALL_POST_EXECUTION_VERIFICATION_ENABLED
KUBEONCALL_POST_EXECUTION_VERIFICATION_TIMEOUT_SECONDS
KUBEONCALL_POST_EXECUTION_VERIFICATION_POLL_MILLIS
KUBEONCALL_AUTOMATIC_ROLLBACK_ENABLED
KUBEONCALL_POST_EXECUTION_ESCALATION_ENABLED
```

关闭恢复验证时，KubeOnCall 会阻止变更操作，而不是退化为无验证执行。
