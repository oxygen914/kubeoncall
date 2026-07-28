# KubeOnCall Helm 部署

KubeOnCall 采用“多个职责独立的 OCI 镜像 + 一个 Helm Chart”的交付方式。Backend、Console、
Sandbox Controller 和 Sandbox 运行时分别构建、扫描和发布；使用者仍只需要执行一次 Helm
安装，不需要在 Kubernetes 节点上安装 Docker，也不应把 Docker Compose 转换为生产清单。

默认 values 保持费用敏感能力关闭；`values-production.yaml` 显式启用 RAG 增强、记忆 LLM/tokenizer、ChangeEvent Webhook、ServiceMonitor 和 Grafana Dashboard ConfigMap。动态 MCP 默认保持关闭，只有替换 `mcpEndpoint` 并配置对应鉴权后再启用，避免生产 profile 指向不存在的示例服务。

Chart 只负责部署 KubeOnCall；Redis、Elasticsearch、MinIO、Prometheus Operator/Grafana 和可选工具 adapter 属于外部依赖。生产安装前必须将 values 中的地址替换为目标集群可访问的 Service 或托管服务。Embedding/Rerank 默认使用已经联调过的阿里云百炼地址，不再引用未随 Chart 部署的 `rag-model` Service。

## 快速体验

Release Chart 可以在 Kind 或 Minikube 中启用临时 Quickstart 基础设施：

```bash
helm upgrade --install kubeoncall \
  oci://ghcr.io/oxygen914/charts/kubeoncall \
  --version 0.1.0 \
  --namespace kubeoncall \
  --create-namespace \
  --set quickstart.enabled=true \
  --wait \
  --timeout 15m
```

建议为本地集群准备至少 2 CPU、4 GiB 内存和约 10 GiB 可用磁盘。

Quickstart 会启动单副本 Backend、Console、MySQL、Redis、Elasticsearch 和 MinIO，并自动
生成安装所需的 Secret。数据卷使用 `emptyDir`，Pod 或 release 删除后数据不可恢复，因此
只能用于开发、演示和功能验收，不能升级为生产环境。启用 Sandbox 时，Quickstart 会为
Controller 临时放行到 Kubernetes API 的 HTTPS egress；生产 profile 不包含这条宽泛规则。
Quickstart 默认关闭需要模型密钥的 Spring AI 自动配置，仍可使用规则规划；生产 profile
显式启用 Spring AI，并要求 Secret 中存在 `aliyun-api-key`。

Quickstart 会创建一次性的 `admin` 管理员，并仅在该 HTTP 体验模式下关闭 Cookie 的
`Secure` 属性。获取随机密码并访问 Console：

```bash
kubectl -n kubeoncall get secret kubeoncall-secrets \
  -o 'go-template={{ index .data "bootstrap-admin-password" | base64decode }}{{ "\n" }}'

kubectl -n kubeoncall port-forward service/kubeoncall-kubeoncall-console 8080:80
```

浏览器打开 `http://127.0.0.1:8080`，用户名为 `admin`。密码不会写入 values、Pod 环境输出
或日志。`verify-helm-deployment.sh` 在实际部署模式下还会执行一次登录与 Session Cookie
Smoke，避免出现“Pod Ready 但浏览器不可用”。体验结束后执行：

```bash
helm uninstall kubeoncall --namespace kubeoncall
kubectl delete namespace kubeoncall
```

如果只从源码目录检查渲染结果，可以使用等价 profile：

```bash
helm template kubeoncall deploy/helm/kubeoncall \
  --namespace kubeoncall \
  --values deploy/helm/kubeoncall/values-quickstart.yaml
```

### 启用已发布的 Sandbox 运行时

Release 同时提供 `kubeoncall-release-values.yaml`，其中包含 Backend、Console、Controller
以及 Python、POSIX Shell、Manifest Validation 三类已实现运行时的不可变镜像 digest：

```bash
gh release download v0.1.0 \
  --repo oxygen914/kubeoncall \
  --pattern kubeoncall-release-values.yaml

helm upgrade --install kubeoncall \
  oci://ghcr.io/oxygen914/charts/kubeoncall \
  --version 0.1.0 \
  --namespace kubeoncall \
  --create-namespace \
  --set quickstart.enabled=true \
  --values kubeoncall-release-values.yaml \
  --wait \
  --timeout 15m
```

固定日志诊断、Kubernetes 一致性、配置差异和独立修复仿真目前没有可发布的运行时构建目录，
因此 Release 不会用占位镜像启用这些模式。待对应镜像实现、扫描并发布后，再加入 release
catalogue。

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
- `bootstrap-admin-password`（仅首次引导管理员时需要；完成后关闭引导开关）
- `api-viewer-token`、`api-operator-token`、`api-admin-token`（仅在旧 `/api/*` 仍启用静态
  Token 鉴权时需要；Production 默认关闭旧 API，因此不再强制要求）
- 可选 Provider key：`change-event-github-webhook-secret`、`change-event-gitlab-webhook-token`、`change-event-jenkins-webhook-token`、`change-event-argocd-webhook-token`

Secret 值不得写入 values 或仓库。

Sandbox Controller 默认位于 `kubeoncall-sandbox` namespace，Secret 无法跨 namespace
引用。生产环境应分别在应用 namespace 和 Sandbox namespace 同步同一个 HMAC 值；
`sandboxController.existingSecret` 用于指定后者。Quickstart 会自动创建两份同值 Secret。
私有镜像仓库的 `global.imagePullSecrets` 也必须在两个 namespace 中分别存在。

生产 Chart 默认不创建 `kubeoncall-sandbox`，避免卸载应用 Release 时删除仍在运行或由平台
共享的 Sandbox 资源。启用生产 Sandbox 前先执行：

```bash
kubectl create namespace kubeoncall-sandbox
kubectl label namespace kubeoncall-sandbox \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted
```

仅 Quickstart 会让 Helm 拥有并随 Release 删除这个临时 Namespace。

Sandbox Job 默认只允许 DNS 和当前 release 内的 MinIO。使用托管对象存储、Artifact Gateway
或平台特有的 Kubernetes API 地址时，通过
`sandboxController.networkPolicy.jobExtraEgress` 和 `controllerExtraEgress` 增加经过审核的
最小 CIDR/端口规则，不要整体关闭默认拒绝策略。`config.minioEndpoint` 必须使用
Sandbox namespace 也能解析和访问的地址；Quickstart 会自动使用完整的集群 Service DNS。

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

## 生产可用性与网络

Backend、Console 和 Migration Job 默认使用非 root 用户、`RuntimeDefault` Seccomp、能力全
删除和只读根文件系统。Backend/Console 默认配置 PDB、滚动更新、主机/可用区拓扑分散及软
反亲和；HPA 已提供但默认关闭，可分别设置：

```yaml
autoscaling:
  enabled: true
console:
  autoscaling:
    enabled: true
```

`values-production.yaml` 默认启用 Backend/Console 入站 NetworkPolicy，并假设 Ingress
Controller 和 Prometheus 分别位于 `ingress-nginx`、`monitoring` Namespace。平台名称不同
时必须覆盖 `networkPolicy.ingressControllerNamespace` 和
`networkPolicy.monitoringNamespace`，否则 Ingress 或采集会被拒绝。Actuator 不再由
Ingress 或 Console 对外代理，仅供集群内部健康探针和 ServiceMonitor 使用。

Chart 使用 `values.schema.json` 在安装前检查副本、端口、镜像 Digest、Sandbox 模式、资源
上限和 Namespace 等关键字段；不要使用 `--skip-schema-validation` 绕过门禁。

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

Chart 默认使用 GHCR Release 镜像，空 tag 自动采用 `Chart.appVersion`，生产环境优先使用
Release 生成的 digest values。若确认使用已经构建并加载到本机 Docker 的镜像，请叠加
`values-local-images.yaml`：

```bash
KUBEONCALL_HELM_VALUES=deploy/helm/kubeoncall/values-quickstart.yaml \
KUBEONCALL_HELM_EXTRA_VALUES=deploy/helm/kubeoncall/values-local-images.yaml \
KUBEONCALL_HELM_APPLY=true \
KUBEONCALL_HELM_LOAD_LOCAL_IMAGE=true \
./scripts/verify-helm-deployment.sh
```

脚本支持自动加载到当前 Minikube 或 Kind 集群。它会按镜像内容 ID 生成不可变本地 Tag，
Minikube 加载时强制覆盖同名缓存，因此镜像内容改变会自动触发新的 Pod rollout。

## 发布

推送 `vX.Y.Z` Git tag 会触发 `.github/workflows/release.yml`：

1. 执行后端完整验证/OpenAPI 漂移、Console 单测与 Playwright、Controller fuzz/vet，以及
   Helm/镜像封装门禁。
2. 构建带 provenance/SBOM 的候选多架构 Manifest，并分别扫描其中的 `linux/amd64` 与
   `linux/arm64` 实际镜像。
3. 拉取六个已扫描候选镜像，在非 root、只读根文件系统下执行 Console 和三类 Sandbox
   Runtime 烟测。
4. 只有六个镜像全部通过后，才把同一 Manifest Digest 提升为正式版本 Tag；流水线不会
   为扫描和发布各重建一次镜像。
5. 生成 `kubeoncall-release-values.yaml`，并把严格校验后的 Chart 发布到
   `oci://ghcr.io/oxygen914/charts/kubeoncall`。
6. 将 Chart 包和 Digest values 附加到对应 GitHub Release。

Dockerfile 的每个基础阶段、GitHub Action 依赖以及 Quickstart 基础设施镜像均固定到
SHA-256/提交 SHA。升级这些依赖时应由单独 PR 更新 Digest 并重新通过全部扫描。

本地提交前可运行：

```bash
./scripts/verify-release-packaging.sh
```

设置 `KUBEONCALL_BUILD_RELEASE_IMAGES=true` 时还会构建全部六个发布镜像，检查镜像声明的
非 root 用户，并在只读根文件系统下实际执行 Console 配置及 Python、POSIX Shell、Manifest
Validation 三类 Sandbox Runtime 的成功样例。
