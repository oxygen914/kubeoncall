# KubeOnCall 文档

README 只保留项目定位和最短成功路径；安装、配置、部署和联调细节在本目录及 `deploy/` 下维护。

## 使用与部署

| 文档 | 适用场景 |
| --- | --- |
| [安装指南](installation.md) | 在 Linux、macOS、Docker Compose 或 Kubernetes 中启动 KubeOnCall |
| [配置参考](configuration.md) | 配置模型、认证、存储、RAG、记忆、MCP、Webhook 和监听地址 |
| [后端运行手册](后端运行手册.md) | 开发运行、告警策略、RAG、记忆和运维接口的详细说明 |
| [阿里云模型联调](阿里云模型联调.md) | 验证百炼 Embedding、Rerank 和 Chat Completions |

## 集成与运维

| 文档 | 适用场景 |
| --- | --- |
| [Alertmanager 接入](../deploy/alertmanager/README.md) | 配置 Bearer Token receiver 和节点告警闭环 |
| [ChangeEvent 接入](../deploy/change-events/README.md) | 接入 GitHub、GitLab、Jenkins 和 Argo CD 变更事件 |
| [Helm 部署](../deploy/helm/kubeoncall/README.md) | 将已有镜像部署到 Kubernetes，并连接外部依赖 |
| [项目架构](../项目架构.md) | 理解模块边界、数据流和核心设计 |

## 开发与贡献

- [贡献指南](../CONTRIBUTING.md)
- [当前重构进度](../重构计划/当前重构进度.md)
- [全项目重构实施计划](../重构计划/全项目重构实施计划.md)

仓库当前未提供独立 `LICENSE` 和安全报告策略。对外发布或接收外部贡献前，应由维护者明确许可证并补充 `SECURITY.md`。
