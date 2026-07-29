# Kubernetes 操作闭环接入指南

KubeOnCall 通过 `KUBERNETES_TOOL_ENDPOINT` 调用 Kubernetes 工具适配器。变更类操作必须实现“变更前快照、幂等执行、恢复验证、补偿回滚”契约，否则 KubeOnCall 会阻止变更或将执行标记为失败并升级人工。

## 支持的操作

| action             | 用途                               | 必须支持           |
| ------------------ | ---------------------------------- | ------------------ |
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

## `describeWorkload` 请求

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

## 快照返回字段

扩缩容至少返回：

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

## 恢复验证返回

适配器可以直接返回：

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
