# Alertmanager 本地接入说明

`alertmanager` 和 `node-exporter` 使用 Docker Compose 的 `alerting` profile，默认不影响已有基础依赖启动。该 profile 只用于一台测试节点的最小告警闭环。

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

如需 `NodeNotReady`，需单独部署 kube-state-metrics 并添加 scrape target；默认 Node Exporter 配置只覆盖节点不可达、CPU、内存、磁盘和 inode 五条规则。
