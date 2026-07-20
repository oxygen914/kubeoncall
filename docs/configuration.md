# KubeOnCall 配置参考

KubeOnCall 使用 Spring Boot 配置体系。默认值位于 `src/main/resources/application.yml`，本地和容器覆盖分别位于 `application-local.yml`、`application-docker.yml`。Docker Compose 从根目录 `.env` 读取变量；Kubernetes/Helm 应通过 Secret 和 values 注入。

不要提交 `.env`、API Key、Webhook Secret、生产地址或真实业务数据。仓库只提交不含凭据的 `.env.example`。

## 1. Profile

| Profile | 用途 | 依赖地址 |
| --- | --- | --- |
| `local` | 源码开发和调试 | `localhost` 上的 Redis、Elasticsearch、MinIO |
| `docker` | Docker Compose 完整能力 | Compose 服务名 `redis`、`elasticsearch`、`minio` |

Compose 已设置 `SPRING_PROFILES_ACTIVE=docker`，无需在 `.env` 重复配置。

## 2. 最小必需配置

| 变量 | 用途 | 说明 |
| --- | --- | --- |
| `ALIYUN_API_KEY` | Planner、Embedding、Rerank | 完整 AI/RAG 能力需要；不得提交 |
| `KUBEONCALL_API_AUTH_ENABLED` | 通用 `/api/**` 鉴权 | 非本地环境建议保持 `true` |
| `KUBEONCALL_API_VIEWER_TOKEN` | 查询和 `/api/ask` | 使用独立强随机值 |
| `KUBEONCALL_API_OPERATOR_TOKEN` | 告警、审批、变更操作 | 不要与 Viewer 共用 |
| `KUBEONCALL_API_ADMIN_TOKEN` | 管理写操作 | 不要与其他角色共用 |
| `MINIO_ROOT_USER` | MinIO 用户 | Compose 同时配置服务端和客户端 |
| `MINIO_ROOT_PASSWORD` | MinIO 密码 | 必须替换示例值 |
| `ALERTMANAGER_WEBHOOK_TOKEN` | Alertmanager Webhook Bearer | 必须与凭据文件内容一致 |

## 3. 网络监听

| 变量 | 默认值 | 控制范围 |
| --- | --- | --- |
| `KUBEONCALL_BIND_ADDRESS` | `127.0.0.1` | KubeOnCall `8080` |
| `INFRA_BIND_ADDRESS` | `127.0.0.1` | Redis、Elasticsearch、MinIO |
| `OBSERVABILITY_BIND_ADDRESS` | `127.0.0.1` | Prometheus、Alertmanager、Node Exporter、Grafana |

服务之间通过 Compose 内部网络通信，通常不需要把基础设施端口监听到公网。生产入口建议使用 Nginx、Traefik 或云负载均衡器终止 TLS。

## 4. 模型与 RAG

Docker profile 默认启用 Elasticsearch kNN、阿里云 Embedding 和 Rerank：

| 变量 | 默认值 |
| --- | --- |
| `SPRING_AI_OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | `qwen-plus` |
| `RAG_EMBEDDING_MODEL` | `text-embedding-v4` |
| `RAG_EMBEDDING_DIMENSIONS` | `1536` |
| `RAG_RERANK_MODEL` | `qwen3-rerank` |
| `RAG_AUGMENTATION_ENABLED` | `false` |
| `RAG_AUTO_BIND_DATASET_VERSION` | `false` |

`RAG_EMBEDDING_DIMENSIONS` 必须与模型输出和 Elasticsearch mapping 一致。更换模型后不能直接复用维度不同的现有索引。

RAG 增强会把知识内容发送到配置的 Chat Completions 服务；只有确认数据边界后再启用：

```dotenv
RAG_AUGMENTATION_ENABLED=true
RAG_AUTO_BIND_DATASET_VERSION=true
```

真实模型协议和降级验证见[阿里云模型联调](阿里云模型联调.md)。

## 5. 记忆

记忆基础存储默认启用，但外部模型调用保持关闭：

```dotenv
MEMORY_LLM_EXTRACTION_ENABLED=false
MEMORY_SEMANTIC_DUPLICATE_ENABLED=false
MEMORY_TOKENIZER_ENABLED=false
```

启用后，记忆内容会发送到配置的模型端点。部署者应根据数据合规要求决定是否开启，并为模型超时和不可用保留 fallback。

## 6. MCP 与外部工具

Compose 默认关闭 MCP：

```dotenv
MCP_ENABLED=false
MCP_DISCOVERY_ENABLED=false
MCP_DYNAMIC_INVOCATION_ENABLED=false
```

接入真实服务时至少配置：

```dotenv
MCP_ENABLED=true
MCP_ENDPOINT=https://mcp.example.com/mcp
MCP_AUTH_MODE=static_bearer
MCP_API_KEY=YOUR_MCP_TOKEN
```

使用 OAuth client credentials 时改为 `MCP_AUTH_MODE=oauth_client_credentials`，并设置 token endpoint、client ID、client secret、scope/resource。动态发现和调用只有在 allowlist、认证和响应大小边界确认后再启用。

Compose 中若使用 `host.docker.internal`，依赖 Docker 的 `host-gateway` 支持；更稳定的生产方式是使用容器网络中的服务名或集群 DNS。

## 7. Webhook 与通知

### Alertmanager

```dotenv
ALERTMANAGER_WEBHOOK_TOKEN=YOUR_RANDOM_TOKEN
```

同一个值必须写入：

```text
deploy/alertmanager/secrets/kubeoncall-webhook-token
```

### CI/CD ChangeEvent

默认关闭：

```dotenv
CHANGE_EVENT_WEBHOOK_ENABLED=false
```

启用 bearer 模式至少设置：

```dotenv
CHANGE_EVENT_WEBHOOK_ENABLED=true
CHANGE_EVENT_WEBHOOK_AUTH_MODE=bearer
CHANGE_EVENT_WEBHOOK_TOKEN=YOUR_RANDOM_TOKEN
```

GitHub、GitLab、Jenkins 和 Argo CD 的 Provider 级签名配置见 [ChangeEvent 接入说明](../deploy/change-events/README.md)。

### 通知与 Incident

```dotenv
ALERT_NOTIFICATION_WEBHOOK_ENDPOINT=
INCIDENT_TOOL_ENDPOINT=
```

端点留空时，KubeOnCall 仍可记录、诊断和审计告警，但不会向真实人员或工单系统发送通知。

## 8. 存储与持久化

Compose 使用以下命名卷：

| 卷 | 数据 |
| --- | --- |
| `redis-data` | session、审批、告警 Inbox、锁和短期状态 |
| `elasticsearch-data` | 知识库和长期记忆 |
| `minio-data` | 原始知识文档 |
| `prometheus-data` | 指标时序数据 |
| `alertmanager-data` | Alertmanager 运行状态 |
| `grafana-data` | Grafana 本地状态 |

生产环境需要额外制定备份、恢复、容量、保留期和升级策略。Compose 命名卷只提供基础持久化，不等同于高可用或灾备。

## 9. 配置校验

静态检查：

```bash
docker compose config --quiet
./scripts/verify-prometheus-rules.sh
```

查看容器实际环境前应避免把输出粘贴到公开 issue，因为其中可能包含 Token 和 API Key。
