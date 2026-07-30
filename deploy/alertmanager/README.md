# Alertmanager 本地接入说明

`alertmanager` 和 `node-exporter` 使用 Docker Compose 的 `alerting` profile，默认不影响已有基础依赖启动。该 profile 只用于一台容器测试节点的最小告警闭环，可同时在 macOS Docker Desktop 和 Linux 上演练目标失联；生产 Kubernetes 节点仍应以 DaemonSet 方式部署 Node Exporter 并挂载宿主机指标目录。

启动前，创建一个未纳入 Git 的 token 文件，并让其内容与应用的 `ALERTMANAGER_WEBHOOK_TOKEN` 完全一致：

```bash
mkdir -p deploy/alertmanager/secrets
openssl rand -hex 32 > deploy/alertmanager/secrets/kubeoncall-webhook-token
export ALERTMANAGER_WEBHOOK_TOKEN="$(cat deploy/alertmanager/secrets/kubeoncall-webhook-token)"
docker compose --profile alerting up -d
```

Alertmanager 只配置一个 KubeOnCall Webhook receiver，并使用 Bearer Token。此配置不包含独立值班 receiver、复杂路由、inhibition 或 silence。

验证规则：

```bash
./scripts/verify-prometheus-rules.sh
```

仓库当前提供 11 条 Node 告警：Node Exporter 路径覆盖节点不可达、CPU、内存、
磁盘、inode、conntrack、时钟偏移、只读文件系统和文件描述符压力；
`NodeNotReady`、`PodPendingTooLong` 依赖 kube-state-metrics。不同内核或
Node Exporter collector 可能不暴露全部指标，上线前应先确认对应时间序列存在。
规则携带的严重度、指标名、资源类型、负责人和 Runbook 标签与后端活动策略逐条对齐。

## 端到端演练

在 Docker Desktop 已启动、基础镜像已就绪时，下面的脚本会先验证 `401`、`202`、基础去重、resolved Webhook 和通知请求，再通过停止并恢复 Node Exporter 验证真实 `NodeDown` 的 `pending -> firing -> resolved`，并等待 Alertmanager 的 firing/resolved 两次投递实际写入 KubeOnCall Inbox。规则本身有 2 分钟 `for`，完整演练约需 3～6 分钟：

```bash
export ALERTMANAGER_WEBHOOK_TOKEN="$(cat deploy/alertmanager/secrets/kubeoncall-webhook-token)"
./scripts/verify-alerting-e2e.sh
```

脚本会临时启动 `scripts/mock-notification-adapter.py` 作为本地通知接收器，检查 KubeOnCall 的接收、去重、Redis Inbox 记录和工作流通知 HTTP 调用。它只证明通知链路成功，不会向真实人员发送消息；若要验证钉钉、邮件等外部通知，必须另行配置对应的通知工具端点。
