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
- 可选 Provider key：`change-event-github-webhook-secret`、`change-event-gitlab-webhook-token`、`change-event-jenkins-webhook-token`、`change-event-argocd-webhook-token`

Secret 值不得写入 values 或仓库。

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
