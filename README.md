# KubeOnCall

<p align="center">
  <picture>
    <source media="(prefers-reduced-motion: reduce)" srcset="assets/readme/kubeoncall/wordmark.svg">
    <img src="assets/readme/kubeoncall/wordmark.webp" alt="KUBEONCALL animated wordmark" width="720">
  </picture>
</p>

[![CI](https://github.com/oxygen914/kubeoncall/actions/workflows/ci.yml/badge.svg)](https://github.com/oxygen914/kubeoncall/actions/workflows/ci.yml)

KubeOnCall 是一个面向 Kubernetes 和云基础设施运维的多智能体服务，把告警接入、诊断编排、RAG、长期记忆、Skill、MCP/HTTP 工具、审批与审计连接成可追踪的工作流。

项目采用前后端分离的单仓库结构：Spring Boot 后端承载核心能力，独立 Web Console 用于数据调试，根目录负责 Docker Compose、部署和文档。

> [!IMPORTANT]
> 当前源码版本为 `0.0.1-SNAPSHOT`，仓库已具备按 `vX.Y.Z` Tag 发布多架构镜像和 Helm OCI
> Chart 的流水线，但在首个 Release 实际成功前仍不能视为已有正式镜像。仓库尚无独立
> `LICENSE`；Compose 和 Helm Quickstart 都不代表高可用生产架构。

## 为什么使用 KubeOnCall

- **告警闭环**：接收 Alertmanager Webhook，完成标准化、指纹去重、聚合、策略、确认、恢复、升级和审计。
- **证据驱动诊断**：Planner → Executor → Verifier 工作流结合指标、工具输出、Runbook 和历史处置记录。
- **RAG 知识库**：支持文档/JSONL/Runbook 导入、Elasticsearch 关键词与向量检索、RRF 融合和 Cross Encoder 重排。
- **运维记忆**：支持会话压缩、长期记忆、结构化提取、证据归因、质量评分和统一 Token 预算。
- **可插拔能力**：从 Markdown Skill 和 MCP/HTTP 工具扩展能力，并提供 allowlist、版本冲突、动态发现和审批边界。
- **可观测与审计**：提供 Actuator、Prometheus 指标、Grafana Dashboard、执行审计和统一错误响应。

## 架构边界

```mermaid
flowchart LR
    C["Web Console"] --> API["KubeOnCall Backend API"]
    W["ChatOps / Alertmanager / CI-CD"] --> API
    API --> Agent["Planner → Executor → Verifier"]
    API --> Alarm["告警治理与工作流"]
    API --> RAG["RAG 与 Runbook"]
    API --> Memory["记忆与 Skill"]
    Agent --> Tools["MCP / HTTP / Kubernetes / Prometheus"]
    Alarm --> Redis[("Redis")]
    Memory --> Redis
    RAG --> ES[("Elasticsearch")]
    RAG --> MinIO[("MinIO")]
```

KubeOnCall 本身不部署生产 Kubernetes 集群、工单系统或模型服务。它通过 HTTP、Webhook、Redis、Elasticsearch、MinIO 和外部模型接口连接这些系统。

## 快速开始

Docker Compose 是最快的本地或单机 Linux 启动方式。

### 1. 前置条件

- Docker Engine
- Docker Compose v2
- 完整 AI/RAG 能力所需的阿里云百炼 API Key

Linux 首次运行 Elasticsearch 前设置：

```bash
sudo sysctl -w vm.max_map_count=262144
```

### 2. 配置

```bash
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，替换所有 `CHANGE_ME` 值。至少需要设置：

- Viewer、Operator、Admin 三个 API Token
- MinIO 密码
- Alertmanager Token
- Grafana 密码

基础栈可在没有模型密钥时启动，并回退到规则规划。若启用 Spring AI 规划模型，先设置 `ALIYUN_API_KEY`，再将 `SPRING_AUTOCONFIGURE_EXCLUDE` 置空并把 `SPRING_AI_OPENAI_CHAT_ENABLED=true`；当前 Spring AI `1.0.0-M2` 会无条件初始化 moderation client，因此两项必须同时调整。

启用 Alertmanager 前，把同一个 Token 写入 credentials file：

```bash
mkdir -p deploy/alertmanager/secrets

sed -n 's/^ALERTMANAGER_WEBHOOK_TOKEN=//p' .env \
  > deploy/alertmanager/secrets/kubeoncall-webhook-token

chmod 600 deploy/alertmanager/secrets/kubeoncall-webhook-token
```

### 3. 启动

```bash
docker compose config --quiet
docker compose up -d --build
```

Compose 会启动：

- KubeOnCall Backend
- KubeOnCall Console
- Redis 7
- Elasticsearch 8.14
- MinIO 和一次性 bucket 初始化任务
- Prometheus
- Grafana

启动可选 Alertmanager 和 Node Exporter：

```bash
docker compose --profile alerting up -d --build
```

### 4. 验证

```bash
docker compose ps
docker compose logs --tail=200 kubeoncall
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8081/healthz
```

使用 Viewer Token 验证 API：

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

发起一次运维问答：

```bash
curl --fail \
  -X POST http://127.0.0.1:8080/api/ask \
  -H "Authorization: Bearer ${VIEWER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "检查 payment 服务最近的错误告警",
    "sessionId": "quick-start"
  }'
```

完整的 Linux、源码和 Kubernetes 安装步骤见[安装指南](docs/installation.md)。

## 部署方式

| 方式 | 适用场景 | 依赖范围 |
| --- | --- | --- |
| Docker Compose | 本地开发、单机 Linux、功能演练 | 仓库内编排应用和基础依赖 |
| Standalone YAML | Minikube 或测试集群 | 包含应用、Redis、ES、MinIO、PVC 和初始化 Job |
| Helm Quickstart | Kind、Minikube、临时测试集群 | 单命令部署应用与临时 MySQL/Redis/ES/MinIO |
| Helm Production | 已有 Kubernetes 平台 | 多镜像部署；数据库、存储、监控和工具 Adapter 由平台提供 |

- Compose：[安装指南](docs/installation.md#2-docker-compose-快速安装)
- Standalone YAML：[后端运行手册](docs/后端运行手册.md#单文件-kubernetes-部署)
- Helm Quickstart、Production 和 Sandbox Release：[Chart 部署说明](deploy/helm/kubeoncall/README.md)

Compose 默认把所有端口绑定到 `127.0.0.1`，并使用命名卷保存数据。需要远程访问时优先使用 SSH 隧道或 TLS 反向代理，不要把 Redis、Elasticsearch 或 MinIO 直接暴露到公网。

## 配置

配置来源：

1. `backend/src/main/resources/application.yml`：公共默认值。
2. `application-local.yml` / `application-docker.yml`：运行环境覆盖。
3. `.env`：Docker Compose 的本地 Secret 和开关，不提交 Git。
4. Helm values 与 Kubernetes Secret：集群部署配置。

主要配置组：

| 组 | 关键变量 | 默认策略 |
| --- | --- | --- |
| API 鉴权 | `KUBEONCALL_API_*_TOKEN` | Compose 示例启用三角色 Bearer |
| Console/CORS | `CONSOLE_*`、`KUBEONCALL_CORS_*` | Console 监听 `8081`，仅允许显式来源 |
| 模型 | `ALIYUN_API_KEY`、`SPRING_AI_*` | `qwen-plus` |
| RAG | `RAG_EMBEDDING_*`、`RAG_RERANK_*` | `text-embedding-v4` + `qwen3-rerank` |
| 记忆 | `MEMORY_*` | 外部 LLM/Tokenizer 默认关闭 |
| MCP | `MCP_*` | 默认关闭，配置真实端点后启用 |
| ChangeEvent | `CHANGE_EVENT_*` | 默认关闭 |
| 通知 | `ALERT_NOTIFICATION_WEBHOOK_ENDPOINT` | 未配置时只记录和诊断 |
| 网络 | `*_BIND_ADDRESS` | 默认只监听 `127.0.0.1` |

完整变量、数据边界和持久化说明见[配置参考](docs/configuration.md)。

## 节点告警闭环

仓库保留一条轻量化节点告警链路：

```text
Node Exporter → Prometheus → Alertmanager → KubeOnCall
```

默认规则覆盖节点不可达、CPU、内存、磁盘和 inode；接入 kube-state-metrics 后还会启用
`NodeNotReady` 和 `PodPendingTooLong`。集群态势页与生产指标接入步骤见
[监控重构计划](docs/plans/active/monitoring/KubeOnCall监控能力重构实施计划.md)和
[Kubernetes 指标接入指南](docs/guides/Kubernetes指标接入指南.md)。

本地单节点 kube-state-metrics 验收：

```bash
./scripts/setup-single-node-monitoring.sh
```

验证规则和链路：

```bash
./scripts/verify-prometheus-rules.sh
./scripts/verify-alerting-e2e.sh
```

Compose 中的 Node Exporter 用于容器化演练；生产 Kubernetes 节点应使用 DaemonSet 或平台现有的节点监控。接入细节见 [Alertmanager 文档](deploy/alertmanager/README.md)。

## API 概览

| 接口 | 用途 |
| --- | --- |
| `GET /actuator/health` | 健康检查 |
| `GET /actuator/prometheus` | Prometheus 指标 |
| `GET /api/status` | 服务状态 |
| `POST /api/ask` | 运维问答和任务编排 |
| `POST /api/integrations/alertmanager/webhook` | Alertmanager Webhook |
| `POST /api/integrations/change-events/{provider}` | CI/CD 变更事件 |
| `POST /api/knowledge/ingest`、`/query` | 知识入库和检索 |
| `POST /api/memory/search` | 长期记忆检索 |
| `GET/POST /api/skills` | Skill 查询和治理 |
| `GET /api/tools` | 工具目录 |

启用通用 API 鉴权后，查询使用 Viewer，告警/审批等操作使用 Operator，管理写操作使用 Admin。Alertmanager 和 ChangeEvent Webhook 使用各自的签名或 Token。

### 本地调试控制台

应用启动后访问 [http://127.0.0.1:8081](http://127.0.0.1:8081)，可直接查看服务状态、执行统计、Tool/Skill 数量，并调试问答、审批和任意 JSON API。

- Compose 中 Console 通过 Nginx 将同源 `/api` 请求转发到后端，也可切换到其他 KubeOnCall 实例。
- Bearer Token 只保存在当前页面内存中，刷新即清除，不会写入浏览器存储。
- 查询和问答使用 Viewer Token；审批写操作使用 Operator 或 Admin Token。
- 每次请求展示 HTTP 状态、耗时和原始响应，最近记录也只保留在当前页面内存中。

该页面是面向开发调试的轻量 Demo，不包含生产控制台所需的用户体系、权限菜单和 Secret 托管能力。

## 文档

- [文档索引](docs/README.md)
- [安装指南](docs/installation.md)
- [配置参考](docs/configuration.md)
- [后端运行手册](docs/后端运行手册.md)
- [阿里云模型联调](docs/阿里云模型联调.md)
- [后端开发说明](backend/README.md)
- [前端控制台说明](frontend/README.md)
- [项目架构](docs/architecture/项目架构.md)
- [贡献指南](CONTRIBUTING.md)

## 仓库结构

```text
kubeoncall/
├── .github/workflows/       # CI
├── backend/                 # Spring Boot、Maven Wrapper、后端测试与镜像
├── frontend/                # 独立静态控制台、契约测试与 Nginx 镜像
├── deploy/                  # Compose 配套资源、Kubernetes、Helm、监控
├── docs/                    # 架构、实施计划、接入指南和运行文档
├── scripts/                 # 告警、模型、MCP、Helm 验证脚本
├── .env.example             # 不含真实 Secret 的配置模板
├── docker-compose.yml       # 单机编排
└── Makefile                 # 前后端验证和 Compose 快捷入口
```

## 开发

要求 JDK 17 和 Node.js 20+。一次验证前后端：

```bash
make test
```

运行真实依赖集成测试：

```bash
docker compose up -d redis elasticsearch minio minio-init
(cd backend && ./mvnw -Pintegration-test verify)
```

CI 会执行 Maven、前端契约测试、静态资源构建、Compose 渲染以及前后端镜像构建。代码规范、提交前检查和架构边界见[贡献指南](CONTRIBUTING.md)。

## 项目状态与许可证

项目仍在持续重构和验证中。公开仓库只保留使用、架构、部署和贡献文档；个人计划、IDE/Agent 状态及本地实验记录由 `.gitignore` 排除。

仓库当前没有独立 `LICENSE` 文件。这意味着代码尚未以明确的开源许可证对外授权；公开分发、二次使用或接受外部贡献前，应由维护者选择并添加许可证。
