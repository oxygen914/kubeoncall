# KubeOnCall Sandbox 运行与验收手册

本文只覆盖 KubeOnCall 自建 Sandbox。它不调用、不依赖 AgentSpace Sandbox，也不授予 Backend、Worker
或 Sandbox Job 生产集群管理凭据。Sandbox 的输出始终是不可信证据；任何生产动作仍要经过既有
Verifier、审批、审计和受控 ToolExecutor。

## 1. 上线前边界

- 默认关闭：`config.sandboxEnabled=false`、各模式开关均为 `false`、`sandboxController.enabled=false`。
- Controller 在独立 `kubeoncall-sandbox` namespace 运行，持有 namespace 内 Job/Pod/Pod 日志的最小权限；
  Job 不挂载 ServiceAccount Token，不读取 Kubernetes Secret。
- Controller 与 Backend 仅使用内部 ClusterIP、HMAC 和短超时通信。Controller endpoint、HMAC Secret、
  MinIO 预签名 URL、Artifact 内容与原始日志不可写入 Dashboard、审计正文或指标标签。
- `REMEDIATION_SIMULATION` 仅允许独立验证集群；未提供独立 kubeconfig 时 Controller 必须返回
  `CONTROLLER_NOT_READY`，不得回退到普通或生产集群。

## 2. 安装与启用顺序

1. 使用 digest 固定的 Controller 与运行时镜像，创建 `kubeoncall-secrets`。至少配置
   `sandbox-controller-hmac`，并保留现有 MySQL、MinIO Secret；不要把值写进 values 文件。
2. 先以所有 Sandbox 开关关闭的状态执行 Helm 模板校验。此时应用行为应等同于未接入 Sandbox 的版本。
3. 在隔离 namespace 部署 Controller，但仍保持 Backend 的 Sandbox 总开关关闭；确认 ServiceAccount、Role、
   ResourceQuota、LimitRange 和 NetworkPolicy 已渲染且没有 Secret 读取权限。
4. 配置 Backend 到 Controller 的内部地址、key ID 和 HMAC 后，仅开启
   `config.sandboxEnabled=true` 与 `config.sandboxFixedDiagnostic=true`。其他模式保持关闭。
5. 完成固定诊断真实 Job 验收后，按风险逐一开启 `generatedCode`、`manifestValidation` 和
   `remediationSimulation`。生成代码和仿真不得与未验证的运行时镜像一同开启。

推荐操作前先渲染而不安装：

```bash
./scripts/verify-helm-deployment.sh
```

只有在真实集群验收窗口内、且已准备回滚 revision 时才允许实际安装：

```bash
KUBEONCALL_HELM_APPLY=true ./scripts/verify-helm-deployment.sh
```

## 3. 日常运营

Grafana 的 **KubeOnCall Sandbox Operations** Dashboard 应至少查看：

- 活跃 Run、最老活跃 Run 与 cleanup backlog；
- Controller 请求错误率和客户端时延；
- 按 mode/tool/outcome 的终态时长与失败原因；
- Artifact 写入/删除数量和字节速率。

Prometheus 已配置积压、失败率、清理泄漏、Controller 不可用和超时告警。阈值是初始保护值，需在真实
负载、SLO 和告警噪声验收后由平台团队调整；未经观测数据验证不得把它们当作生产容量承诺。

## 4. 故障、泄漏与关闭

| 现象 | 处置顺序 |
| --- | --- |
| Controller 不可用 | 保持 Backend 运行；Run 会保留为可恢复状态。修复 Controller/网络/HMAC 后让 Reconciler 重试，禁止手工把 Run 改成成功。 |
| Run 超时 | 检查 `SANDBOX_RUN_EXPIRED`、Controller 状态和 Job deadline；确认 Job 已取消，再由 cleanup 收敛。 |
| OOM/ImagePull/PolicyDenied | 只使用归一化原因排障；核对镜像 digest、ResourceQuota/LimitRange、NetworkPolicy 和工具目录，禁止改为任意镜像或放宽 Job 安全上下文。 |
| Cleanup 泄漏 | 先保留 `koc_sandbox_run` 与 Artifact 元数据作为证据；确认 runId 和 namespace 后由 Controller Janitor 清理。不得按宽泛 label、namespace 根目录或 MinIO bucket 根目录删除。 |
| 需要紧急止损 | 先关闭 Backend 总开关，阻止新 Run；待活动 Run 与 cleanup 达到可接受状态后再关闭 Controller。 |

回滚遵循“先入口、后运行时、保留事实”的顺序：关闭模式开关 → 关闭 Sandbox 总开关 → 回滚 Backend/Console
revision → 待清理结束后缩容或关闭 Controller。不要回滚 Flyway Sandbox 表，也不要删除审计、Run 或 Artifact
元数据来伪造干净状态。

## 5. 代码验收清单

- [x] Backend、Controller、Frontend、OpenAPI、Helm、Compose 的代码和自动化门禁已纳入仓库。
- [x] Controller CI 覆盖 `go test`、`go vet`、构建与短时认证模糊测试；三类镜像均纳入 Trivy 与 CycloneDX SBOM。
- [x] JobSpec、Artifact 路径/大小、签名/nonce、失败原因归一化和全局/模式开关 `00/10/01/11` 组合具备回归测试。
- [x] Dashboard、Prometheus 规则和 Helm 模板具备静态校验。
- [x] 默认关闭时，模式开关不能单独启用任何 Sandbox Run。

## 6. 真实环境验收清单（尚未完成）

- [ ] 隔离集群实际创建、完成、超时、取消和 TTL/Janitor 清理一次性 Job。
- [ ] NetworkPolicy、RBAC、Quota、LimitRange 和 Job 无 ServiceAccount Token 的集群现场证据。
- [ ] 固定诊断、生成 Python/Shell、YAML/Helm/Patch/Runbook 和独立仿真四类真实运行时验收。
- [ ] Grafana 数据源、Prometheus 抓取、告警送达、Dashboard 阈值和容量演练。
- [ ] Controller 故障恢复、Backend 多副本 fencing、SSE 断线恢复和 Artifact 下载实际演练。
- [ ] 一次 Sandbox 证据 → Verifier → 人工审批 → 受控生产动作的完整留痕验收。

在以上项目完成前，Sandbox 状态应保持 **VALIDATING**，不得标记为生产完整交付。
