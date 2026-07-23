# KubeOnCall Helm 部署

默认 values 保持费用敏感能力关闭；`values-production.yaml` 显式启用 RAG 增强、记忆 LLM/tokenizer、ChangeEvent Webhook、ServiceMonitor 和 Grafana Dashboard ConfigMap。动态 MCP 默认保持关闭，只有替换 `mcpEndpoint` 并配置对应鉴权后再启用，避免生产 profile 指向不存在的示例服务。

Chart 只负责部署 KubeOnCall；Redis、Elasticsearch、MinIO、Prometheus Operator/Grafana 和可选工具 adapter 属于外部依赖。生产安装前必须将 values 中的地址替换为目标集群可访问的 Service 或托管服务。Embedding/Rerank 默认使用已经联调过的阿里云百炼地址，不再引用未随 Chart 部署的 `rag-model` Service。

## Secret

部署前在目标 namespace 创建 `kubeoncall-secrets`，按实际启用能力提供以下 key：

- `minio-access-key`
- `minio-secret-key`
- `aliyun-api-key`
- `mcp-api-key`，或 `mcp-oauth-client-secret`
- `alertmanager-webhook-token`
- `change-event-webhook-token`
- `mysql-password`（应用账户，仅 DML）
- `mysql-migration-password`（Flyway Migration Job 专用 DDL 账户）
- 可选 Provider key：`change-event-github-webhook-secret`、`change-event-gitlab-webhook-token`、`change-event-jenkins-webhook-token`、`change-event-argocd-webhook-token`

Secret 值不得写入 values 或仓库。

### 轮换

以不可变的新 Secret 名称进行两阶段轮换，避免原地覆盖而使回滚失去旧凭据：

```bash
KUBEONCALL_SECRET_FILE=/secure/kubeoncall-secrets.env \
KUBEONCALL_ROTATE_APPLY=true ./scripts/rotate-kubernetes-secret.sh kubeoncall kubeoncall-secrets-20260723
# 审核新 Secret key 完整后，再在受管 values override 中把 secrets.existingSecret 改为新名称并 helm upgrade。
```

升级后的健康验证通过前保留旧 revision；若失败，使用既有 Helm revision 回滚。脚本不输出
Secret 值，也不自动切换工作负载。

MySQL 启用时，Chart 在 `pre-install` / `pre-upgrade` 阶段运行一次 Flyway Job；常驻应用
明确禁用 Flyway，只使用 `mysql-password`。托管 MySQL 需预先创建与 `values-production.yaml`
一致的应用账户和迁移账户，应用账户不得授予 `CREATE`、`ALTER`、`DROP` 等 DDL 权限。

## 验收

只执行 lint、模板渲染和集群 server-side dry-run：

```bash
./scripts/verify-helm-deployment.sh
```

确认 Redis、Elasticsearch、MinIO、模型和工具 adapter 地址都可从集群访问后再实际安装：

```bash
KUBEONCALL_HELM_APPLY=true ./scripts/verify-helm-deployment.sh
```

脚本会从渲染结果读取实际 Secret 名称，等待 Deployment rollout，并检查 Service、Grafana Dashboard ConfigMap 和 ServiceMonitor。启用 ServiceMonitor 但没有 Prometheus Operator CRD 时会在安装前失败，不会留下半安装 release。

默认镜像 `kubeoncall:latest` 只适合本地 Minikube。实际安装时应在 values 中配置已经推送的镜像；若确认使用本机镜像，可执行：

```bash
KUBEONCALL_HELM_APPLY=true \
KUBEONCALL_HELM_LOAD_LOCAL_IMAGE=true \
./scripts/verify-helm-deployment.sh
```
