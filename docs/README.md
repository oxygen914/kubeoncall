# KubeOnCall 文档

README 只保留项目定位和最短成功路径；安装、配置、部署和联调细节在本目录及 `deploy/` 下维护。

## 使用与部署

| 文档                                                      | 适用场景                                                        |
| --------------------------------------------------------- | --------------------------------------------------------------- |
| [安装指南](installation.md)                               | 在 Linux、macOS、Docker Compose 或 Kubernetes 中启动 KubeOnCall |
| [配置参考](configuration.md)                              | 配置模型、认证、存储、RAG、记忆、MCP、Webhook 和监听地址        |
| [后端运行手册](后端运行手册.md)                           | 开发运行、告警策略、RAG、记忆和运维接口的详细说明               |
| [Sandbox 运行与验收手册](guides/Sandbox运行与验收手册.md) | Sandbox 的启用、关闭、回滚、泄漏处置和代码/环境验收边界         |
| [阿里云模型联调](阿里云模型联调.md)                       | 验证百炼 Embedding、Rerank 和 Chat Completions                  |

## 集成与运维

| 文档                                                                | 适用场景                                                                |
| ------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [Alertmanager 接入](../deploy/alertmanager/README.md)               | 配置 Bearer Token receiver 和节点告警闭环                               |
| [ChangeEvent 接入](../deploy/change-events/README.md)               | 接入 GitHub、GitLab、Jenkins 和 Argo CD 变更事件                        |
| [Helm 部署](../deploy/helm/kubeoncall/README.md)                    | Quickstart 一键安装、多镜像 Release、生产外部依赖与 Sandbox digest 配置 |
| [Kubernetes 指标接入指南](guides/Kubernetes指标接入指南.md)         | 接入 kube-state-metrics、Node Exporter 和生产 Prometheus                |
| [Kubernetes 操作闭环接入指南](guides/Kubernetes操作闭环接入指南.md) | 实现操作幂等、变更前快照、恢复验证、回滚和人工升级契约                  |

## 架构与实施计划

| 文档                                                                                                            | 状态与用途                                        |
| --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| [项目架构](architecture/项目架构.md)                                                                            | 理解模块边界、数据流和核心设计                    |
| [监控能力重构实施计划](plans/active/monitoring/KubeOnCall监控能力重构实施计划.md)                               | 当前监控能力建设范围、阶段和验收标准              |
| [自主运维闭环实施计划](plans/active/ai-operations/KubeOnCall独立解决运营问题能力收口实施计划.md)                | 真实模型、统一证据、持久化 Ask 和恢复闭环收口计划 |
| [本地真实模型与统一证据链验收](plans/active/ai-operations/validation/2026-07-29本地真实模型与统一证据链验收记录.md) | 本地真实模型、Loki、Prometheus 与持久化 Ask 联调证据及能力边界 |
| [Sandbox 重构计划](plans/active/sandbox/sandbox重构计划书.md)                                                   | 当前 Sandbox 重构范围、任务拆分和验收边界         |

## 开发与贡献

- [贡献指南](../CONTRIBUTING.md)
- [后端开发说明](../backend/README.md)
- [前端控制台说明](../frontend/README.md)

仓库当前未提供独立 `LICENSE` 和安全报告策略。对外发布或接收外部贡献前，应由维护者明确许可证并补充 `SECURITY.md`。
