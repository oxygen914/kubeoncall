# KubeOnCall 安装指南

Docker Compose 是单机 Linux 和本地验证的首选方式。Kubernetes 单文件清单适合 Minikube/测试集群；Helm Chart 只部署 KubeOnCall，需要预先准备 Redis、Elasticsearch、MinIO、Prometheus Operator 等外部依赖。

## 1. 环境要求

### Docker Compose

- Linux 或 macOS
- Docker Engine 和 Docker Compose v2
- 可访问 Docker Hub、Elastic Container Registry、Maven Central 和 Spring Milestones
- 完整 AI/RAG 能力需要阿里云百炼 API Key

单机 Linux 建议至少准备 4 vCPU、8 GiB 内存和 30 GiB 可用磁盘。该数值是开发/演练建议，不是生产容量承诺。

Elasticsearch 所在 Linux 主机必须设置：

```bash
sudo sysctl -w vm.max_map_count=262144

echo 'vm.max_map_count=262144' |
  sudo tee /etc/sysctl.d/99-kubeoncall.conf

sudo sysctl --system
```

## 2. Docker Compose 快速安装

### 2.1 获取代码

```bash
git clone https://github.com/oxygen914/kubeoncall.git
cd kubeoncall
```

如果目标功能尚未合并到默认分支，部署前应明确检出所需分支或发布标签，不要在生产环境跟随浮动开发分支。

### 2.2 准备配置

```bash
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，至少替换：

- `ALIYUN_API_KEY`
- 三个 `KUBEONCALL_API_*_TOKEN`
- `MINIO_ROOT_PASSWORD`
- `ALERTMANAGER_WEBHOOK_TOKEN`
- `GRAFANA_ADMIN_PASSWORD`

Token 可使用以下命令生成：

```bash
openssl rand -hex 32
```

启用 `alerting` profile 前，把 `.env` 中的 `ALERTMANAGER_WEBHOOK_TOKEN` 原样写入凭据文件：

```bash
mkdir -p deploy/alertmanager/secrets

sed -n 's/^ALERTMANAGER_WEBHOOK_TOKEN=//p' .env \
  > deploy/alertmanager/secrets/kubeoncall-webhook-token

chmod 600 deploy/alertmanager/secrets/kubeoncall-webhook-token
```

校验 Compose 渲染结果：

```bash
docker compose config --quiet
```

### 2.3 启动

启动应用、Redis、Elasticsearch、MinIO、Prometheus 和 Grafana：

```bash
docker compose up -d --build
```

Compose 会通过一次性 `minio-init` 服务创建 `kubeoncall-docs` bucket。启动最小节点告警闭环时再启用可选 profile：

```bash
docker compose --profile alerting up -d --build
```

检查服务：

```bash
docker compose ps
docker compose logs --tail=200 kubeoncall
curl --fail http://127.0.0.1:8080/actuator/health
```

读取 Viewer Token 并验证 API：

```bash
VIEWER_TOKEN="$(
  sed -n 's/^KUBEONCALL_API_VIEWER_TOKEN=//p' .env
)"

curl --fail \
  -H "Authorization: Bearer ${VIEWER_TOKEN}" \
  http://127.0.0.1:8080/api/status
```

预期响应：

```json
{"service":"KubeOnCall","status":"UP"}
```

### 2.4 访问方式

Compose 默认只监听 `127.0.0.1`。远程演练可使用 SSH 隧道：

```bash
ssh -L 8080:127.0.0.1:8080 \
    -L 3000:127.0.0.1:3000 \
    user@linux-host
```

需要由反向代理直接连接时，可在 `.env` 中设置：

```dotenv
KUBEONCALL_BIND_ADDRESS=0.0.0.0
OBSERVABILITY_BIND_ADDRESS=127.0.0.1
INFRA_BIND_ADDRESS=127.0.0.1
```

不要把 Redis、Elasticsearch 或 MinIO 直接暴露到公网。公开 API 前应配置 TLS、网络访问控制和强随机 Token。

### 2.5 停止与清理

停止容器并保留命名卷：

```bash
docker compose down
```

删除容器和全部数据：

```bash
docker compose down -v
```

`down -v` 会删除 Redis、Elasticsearch、MinIO、Prometheus、Alertmanager 和 Grafana 数据，只能在确认无需保留后执行。

## 3. 本地源码运行

开发环境需要 JDK 17，并准备可访问的 Redis、Elasticsearch 和 MinIO：

```bash
export OPENAI_API_KEY=YOUR_API_KEY
./mvnw spring-boot:run
```

默认 `local` profile 使用 `localhost:6379`、`localhost:9200` 和 `localhost:9000`。可先通过 Compose 只启动依赖：

```bash
docker compose up -d redis elasticsearch minio minio-init
```

## 4. Kubernetes

### 单文件测试部署

`deploy/kubernetes/kubeoncall-standalone.yaml` 包含 KubeOnCall、Redis、Elasticsearch、MinIO、PVC 和 bucket 初始化 Job，适合 Minikube 或测试集群。完整步骤见[后端运行手册](后端运行手册.md#单文件-kubernetes-部署)。

### Helm

Helm Chart 位于 `deploy/helm/kubeoncall/`，只负责 KubeOnCall 应用。安装前必须：

1. 构建并推送可被集群拉取的固定版本镜像。
2. 准备 Redis、Elasticsearch 和 MinIO。
3. 创建 `kubeoncall-secrets`。
4. 将 values 中的外部地址替换为集群可解析地址。
5. 按需安装 Prometheus Operator CRD。

详细配置和验收命令见 [Helm 部署说明](../deploy/helm/kubeoncall/README.md)。

## 5. 安装后验证

```bash
./scripts/verify-prometheus-rules.sh
./scripts/verify-aliyun-models.sh
./scripts/verify-alerting-e2e.sh
```

模型、Alertmanager 和动态 MCP 脚本依赖对应凭据或外部服务；不能满足依赖时应跳过并记录为“未验证”，不能把静态配置检查当成真实联调成功。
