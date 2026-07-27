# KubeOnCall Sandbox 重构计划书

## 1. 文档信息

| 项目 | 当前值 |
| --- | --- |
| 文档版本 | 1.0 |
| 制定日期 | 2026-07-27 |
| 目标项目 | KubeOnCall |
| 实施状态 | IN_PROGRESS |
| 代码实现进度 | 87%（SBX-00～20 已完成） |
| 自动化验证进度 | 87%（SBX-00～20 代码级验证通过；真实集群待验收） |
| 真实环境验收进度 | 0%，按阶段单独记录 |
| 计划提交数 | 24 个，`SBX-00`～`SBX-23` |

本文用于指导 KubeOnCall 从零建设独立的 Sandbox 能力。KubeOnCall 与 AgentSpace Sandbox
保持完全解耦，只参考其异步生命周期、幂等、资源限制、默认拒绝网络和自动清理等设计经验，
不依赖 AgentSpace API、SDK、数据库或部署环境。

本计划的首要目标不是建设通用云开发环境，而是为 Kubernetes 运维 Agent 提供四类能力：

1. 在隔离环境执行固定诊断工具。
2. 在隔离环境执行 Agent 临时生成的 Python 或 Shell。
3. 在隔离环境校验 YAML、Helm、Patch 和 Runbook。
4. 在独立仿真环境验证修复方案。

## 2. 总体判断

KubeOnCall 当前通过固定 `ToolExecutor` 查询 Kubernetes、Prometheus 等外部系统，并由
Verifier、审批和权限控制生产变更。现有链路没有在 Backend 或 Worker 本机直接执行 Agent
生成代码，因此当前系统不存在“必须先有 Sandbox 才能运行”的阻塞。

但是，当产品目标从“分析和建议”升级为“Agent 主动编写诊断程序、验证修复并尝试解决问题”
后，Sandbox 应成为正式安全边界。其职责限定为：

- 隔离不可信代码、复杂工具链和高资源消耗任务。
- 把日志、指标、Events 和资源快照转换为结构化诊断证据。
- 在生产变更前验证配置、补丁和 Runbook。
- 在无生产凭据的仿真环境中验证修复方案。

Sandbox 不负责绕过 KubeOnCall 的生产执行策略。生产集群的重启、扩缩容和 Patch 仍必须由
现有受控执行器完成，并继续经过 RBAC、策略校验、审批、审计、执行后验证和回滚控制。

## 3. 当前可复用基础

以下能力已经存在，应直接复用，不重复建设：

| 现有能力 | 复用方式 |
| --- | --- |
| `koc_async_task` | 承载短时派发任务，不用于长时间阻塞轮询 Sandbox Job |
| Worker lease、heartbeat、fencing | 复用其并发与所有权模型，Sandbox Run 采用同类 fencing 约束 |
| MySQL/Flyway | 保存 Sandbox Run、Artifact 和生命周期事实 |
| MinIO | 保存输入证据包、脚本、输出、报告和受限日志 |
| Audit + Outbox | 记录创建、取消、状态变化、策略拒绝和清理失败 |
| requestId/traceId | 贯穿告警、Execution、Task、Sandbox Run 和审计 |
| SSE | 向 Console 推送 Sandbox Run 和关联 Execution 状态 |
| Verifier/Approval | 保持生产变更最终控制权 |
| Dependency Circuit Breaker | 保护 Backend 到 Sandbox Controller 的调用 |
| OpenAPI Client | 生成 Console 使用的 Sandbox API 类型 |

当前通用 `AsyncTaskWorker` 由定时任务同步调用，长时间等待 Kubernetes Job 会占用 Worker
执行线程。因此本计划只使用异步任务完成“派发”这类短操作；运行状态由独立
`SandboxRunReconciler` 通过短轮询、租约和 fencing 收敛。

## 4. 建设范围

### 4.1 本轮范围

- Sandbox Run 领域模型、状态机、权限、API 和审计。
- 独立 Sandbox Controller 服务。
- 一次性 Kubernetes Job 运行时。
- 输入输出 Artifact 与内容校验。
- 固定诊断工具目录。
- Agent 生成 Python/Shell 的隔离执行。
- YAML、Helm、Patch、Runbook 校验。
- 独立非生产集群中的修复方案仿真。
- Planner、Workflow、Verifier 与 Sandbox 的异步衔接。
- Console、指标、告警、部署和清理 Runbook。

### 4.2 明确不做

- 不接入或调用 AgentSpace Sandbox。
- 不提供 Web Terminal、SSH 或任意交互式终端。
- 不提供长期 Session、暂停/恢复、用户工作区或 IDE。
- 不建设快照市场、模板市场和通用镜像市场。
- 不允许调用方提交任意容器镜像。
- 不把生产 kubeconfig、云 AK/SK、数据库管理密码注入 Sandbox。
- 不允许 Sandbox 输出直接触发生产变更。
- 不把 Kubernetes namespace 隔离误写成完整的强多租户安全边界。
- 首轮不仿真节点宕机、真实流量、云盘、CNI、CSI 等集群底层故障。

## 5. 架构决策

### 5.1 组件边界

采用“Backend 控制面 + 独立 Controller + 一次性 Job + 可选仿真集群”：

```mermaid
flowchart LR
    Alarm["告警 / Ask / Execution"] --> Evidence["证据采集与脱敏"]
    Evidence --> MinIO[("MinIO Artifact")]
    Evidence --> Backend["KubeOnCall Backend<br/>策略、Run、API、审计"]
    Backend --> Controller["Sandbox Controller<br/>仅管理隔离 Job"]
    Controller --> Job["一次性 Sandbox Job"]
    MinIO --> Job
    Job --> MinIO
    Controller --> Backend
    Backend --> Verifier["Verifier / Approval"]
    Verifier --> Prod["受控生产执行器"]
    Controller --> Sim["独立非生产仿真集群"]
```

组件职责：

- **KubeOnCall Backend**：认证、权限、策略、幂等、Artifact、Run 持久化、Agent 编排、
  审计和结果消费。
- **Sandbox Controller**：按 Run ID 幂等创建、查询、取消和清理 Kubernetes Job；不包含
  LLM、业务策略和生产修复逻辑。
- **Sandbox Job**：执行固定工具或受限代码；Job 完成后销毁，不保留会话。
- **Simulation Runtime**：连接独立非生产验证集群，创建临时 namespace，应用脱敏资源并
  执行健康检查。

### 5.2 Controller 技术选型

默认在仓库新增 `sandbox-controller/`，使用 Go 1.22、`client-go` 和标准 HTTP 服务实现。

选择原因：

- Controller 是轻量 Kubernetes 控制面，适合独立小镜像和独立 ServiceAccount。
- 避免给 KubeOnCall Backend 直接授予创建 Job、读取 Pod 日志的权限。
- 避免 Sandbox 运行故障扩大到主 JVM。
- 后续可以独立扩容、熔断和升级。

如果团队要求保持单语言，必须先新增 ADR 修改该决策，不应在实施中临时把 Kubernetes
Client 权限塞回 Backend。

### 5.3 数据流

1. KubeOnCall 采集日志、Events、指标和资源描述。
2. `DiagnosticEvidenceBuilder` 完成裁剪、脱敏、分类和哈希。
3. 证据、脚本和验证目标写入 `sandbox/{runId}/inputs/`。
4. Backend 创建 `SandboxRun` 与短时派发任务。
5. Controller 以 Run ID 幂等创建一次性 Job。
6. Job 读取指定 Artifact，执行后写入限定对象路径。
7. Backend Reconciler 收敛状态、校验输出大小与 SHA-256。
8. 结构化结果作为不可信证据交给 Verifier。
9. Verifier 重新检查真实集群状态，生成受控动作或进入人工审批。
10. Artifact TTL 与 Job TTL 分别清理，失败进入 `CLEANUP_FAILED` 告警。

## 6. 领域与契约

### 6.1 运行模式

| 模式 | 用途 | 默认审批 | 是否允许生产凭据 |
| --- | --- | ---: | ---: |
| `FIXED_DIAGNOSTIC` | 固定日志/配置诊断工具 | 否 | 否 |
| `GENERATED_CODE` | Agent 生成的 Python/Shell | 否，必须满足自动运行策略 | 否 |
| `MANIFEST_VALIDATION` | YAML/Helm/Patch/Runbook 校验 | 否 | 否 |
| `REMEDIATION_SIMULATION` | 独立仿真集群验证 | 按资源成本策略 | 否 |

调用方不能直接传入镜像地址。运行模式必须解析到服务端工具目录中的固定镜像 digest、
入口、输入 Schema、输出 Schema、资源上限和网络策略。

### 6.2 Run 状态

运行状态与清理状态分离，避免“任务成功但资源泄漏”被误报为完全成功。

`run_status`：

```text
PENDING -> DISPATCHING -> RUNNING -> COLLECTING
                                  -> SUCCEEDED
                                  -> FAILED
                                  -> TIMED_OUT
                                  -> CANCELLED
```

`cleanup_status`：

```text
NOT_REQUIRED -> PENDING -> RUNNING -> SUCCEEDED
                                   -> FAILED
```

规则：

- 每次状态更新都校验 `version` 或 `owner_token + fencing_token`。
- 终态不能被过期 Controller 回调覆盖。
- Run 成功不等于清理成功。
- `CANCELLED` 只表示业务取消已接受，仍需完成资源清理。
- Controller 不可达时保持可恢复状态，不伪造 `FAILED`。

### 6.3 持久化模型

新增下一可用版本的增量 Flyway Migration：

`koc_sandbox_run`：

- `public_id`
- `execution_public_id`
- `alarm_public_id`
- `mode`
- `tool_id`
- `tool_version`
- `runtime_image_digest`
- `run_status`
- `cleanup_status`
- `stage`
- `progress`
- `risk_level`
- `requested_by`
- `request_json`
- `result_json`
- `error_code`
- `error_summary`
- `controller_run_id`
- `owner_token`
- `lease_until`
- `fencing_token`
- `attempt`
- `max_attempts`
- `expires_at`
- `request_id`
- `trace_id`
- `version`
- `started_at`
- `finished_at`
- `created_at`
- `updated_at`

`koc_sandbox_artifact`：

- `public_id`
- `sandbox_run_id`
- `artifact_type`
- `bucket`
- `object_key`
- `content_type`
- `size_bytes`
- `sha256`
- `classification`
- `retention_until`
- `created_at`

正文、脚本、完整日志和大结果不得写入 MySQL JSON 字段。数据库只保存摘要、引用和校验值。

### 6.4 外部 API

新增：

- `POST /api/v1/sandbox-runs`
- `GET /api/v1/sandbox-runs`
- `GET /api/v1/sandbox-runs/{runId}`
- `POST /api/v1/sandbox-runs/{runId}/cancel`
- `GET /api/v1/sandbox-runs/{runId}/artifacts`

约束：

- 创建接口要求 `Idempotency-Key`。
- 列表支持 `mode/status/executionId/alarmId/createdFrom/createdTo`。
- 默认响应不返回脚本正文、预签名地址和完整原始日志。
- Artifact 下载使用短时、单对象授权。
- 取消使用 CAS；终态重复取消返回当前状态。
- Console 只通过生成的 OpenAPI Client 调用。

Controller 内部 API：

- `POST /internal/v1/runs`
- `GET /internal/v1/runs/{runId}`
- `DELETE /internal/v1/runs/{runId}`
- `GET /internal/v1/runs/{runId}/logs`

内部接口使用独立 Service 身份、请求签名、时间戳和重放窗口；同时通过 NetworkPolicy
限制只允许 KubeOnCall Backend 访问。

### 6.5 权限

新增稳定权限码：

- `sandbox:read`
- `sandbox:execute`
- `sandbox:cancel`
- `sandbox:manage`

建议默认角色：

| 角色 | 权限 |
| --- | --- |
| Viewer | `sandbox:read` |
| Operator | `sandbox:read/execute/cancel` |
| Admin | 全部 Sandbox 权限 |
| System actor | 只允许策略批准的自动诊断，不继承 Admin |

Agent 自动创建 Run 必须携带原始用户或系统 actor、关联告警/Execution 和 requestId，不允许
使用模糊的“system=admin”绕过权限与审计。

## 7. 安全基线

### 7.1 Job 安全约束

每个 Sandbox Job 必须满足：

- 独立 `kubeoncall-sandbox` namespace。
- `automountServiceAccountToken: false`。
- `runAsNonRoot: true`。
- `allowPrivilegeEscalation: false`。
- `readOnlyRootFilesystem: true`。
- `seccompProfile.type: RuntimeDefault`。
- `capabilities.drop: ["ALL"]`。
- 禁止 privileged、hostPath、hostNetwork、hostPID、hostIPC。
- 只允许固定 digest 镜像，禁止 tag 和 `latest`。
- CPU、内存、临时磁盘、PID 和最大运行时间均有硬限制。
- 使用带 `sizeLimit` 的 `emptyDir` 作为临时目录。
- 默认拒绝外网，只开放 DNS 和受限 Artifact 通道。
- 不挂载 Backend Secret、生产 kubeconfig 和 Docker Socket。
- Job、Pod 和 Artifact 均带 Run ID、过期时间和数据分类标签。

建议首版默认值：

| 限制 | 默认值 |
| --- | ---: |
| 单 Run CPU | 1 core |
| 单 Run 内存 | 1 GiB |
| 临时存储 | 2 GiB |
| 默认超时 | 5 分钟 |
| 最大超时 | 15 分钟 |
| 输入总量 | 50 MiB |
| 输出总量 | 10 MiB |
| 展示日志 | 2 MiB |
| 生成脚本 | 256 KiB |
| Artifact TTL | 24 小时 |
| 单告警并发 Run | 1 |
| 全局初始并发 | 4 |

所有值必须可配置，但生产配置不得超过服务端硬上限。

### 7.2 数据与提示注入防护

- 不采集 Kubernetes Secret 的 `data/stringData`。
- 对 Token、Cookie、Authorization、私钥和常见 AK/SK 进行二次脱敏。
- 日志和 Sandbox 输出统一标记为“不可信证据”，不能被当成系统指令。
- Sandbox 输出中的命令字符串不能直接进入生产执行器。
- 结构化修复建议必须重新经过 Schema、目标范围和权限校验。
- Agent 在执行生产动作前必须重新查询当前集群状态，不能只依据 Sandbox 快照。

### 7.3 仿真边界

- 默认使用独立非生产 Kubernetes 集群。
- 每个 Run 创建独立临时 namespace。
- 只复制脱敏后的 Deployment、Service、ConfigMap 等必要资源。
- Secret 使用占位值或测试凭据，不复制生产 Secret。
- 不访问生产数据库、消息队列和云账号。
- 仿真结果只能证明语法、依赖、启动和健康检查成立，不能证明生产流量下必然恢复。

## 8. 提交与回滚规则

### 8.1 固定规则

1. 建议在独立分支 `codex/sandbox-refactor` 实施。
2. 严格按 `SBX-00`～`SBX-23` 顺序执行。
3. 一个提交只完成一个计划单元，不混入无关格式化、依赖升级或历史清理。
4. 每个实现提交同时包含该能力的单元测试或契约测试。
5. 每个提交完成测试后，将本计划对应状态改为 `COMPLETED`，与代码一起提交。
6. Commit subject 必须包含对应编号，例如：
   `feat(sandbox): add run policy contracts [SBX-03]`。
7. 已推送的提交不 amend；修正使用新的、明确编号的补充提交，并回写计划原因。
8. 不使用 squash 合并，否则失去逐提交回滚能力。
9. Flyway 只做向前兼容的增量变更；代码回滚时允许新增空表和列继续存在。
10. 功能开关默认关闭，直到该能力所在阶段通过完整质量门。
11. 不自动 push。只有用户明确要求后才推送远端。

### 8.2 单提交完成标准

每个提交必须同时满足：

- 暂存区只包含该 SBX 单元允许的文件。
- 新增接口有权限与错误契约。
- 新增持久化写入有幂等、CAS 或 fencing。
- 新增外部调用有超时、最大响应、重试语义和断路器。
- 日志不包含脚本正文、预签名 URL、Token 或原始 Secret。
- 相关单测通过。
- `git diff --cached --check` 通过。
- 计划状态与实现状态一致。

阶段结束额外执行：

```bash
make test
cd backend && ./mvnw --batch-mode --no-transfer-progress verify -Pintegration-test
cd sandbox-controller && go test ./... && go vet ./...
cd frontend && npm run typecheck && npm run lint && npm run test && npm run build
docker compose config --quiet
helm lint deploy/helm/kubeoncall
```

尚未创建的组件命令从对应 Scaffold 提交开始执行。

### 8.3 回滚原则

- 单提交回滚使用 `git revert <commit>`，不使用 `reset --hard`。
- 有依赖的提交按编号逆序回滚。
- 先关闭 `agent-auto-route`，再关闭单项 Sandbox 能力，最后关闭总开关。
- Controller 回滚不得删除仍在运行的 Job；先停止新建，等待或显式取消后再降级。
- 数据库 Migration 不执行在线 down；保留新增表，应用代码回退。
- Artifact 清理采用 TTL/Janitor，回滚时不批量删除无法确认归属的对象。

## 9. Commit 级实施计划

状态定义：`READY` 表示可以开始；`PLANNED` 表示等待前置提交；`IN_PROGRESS`、
`VALIDATING`、`COMPLETED`、`BLOCKED`、`DEFERRED` 用于后续实施记录。

| 编号 | 预期 Commit | 交付结果 | 状态 |
| --- | --- | --- | --- |
| SBX-00 | `docs(sandbox): define isolated diagnosis refactor plan` | 冻结范围、边界与提交顺序 | COMPLETED |
| SBX-01 | `feat(sandbox): add feature flags and limits` | 默认关闭的配置与能力发现 | COMPLETED |
| SBX-02 | `feat(identity): add sandbox permissions` | Sandbox 权限和角色映射 | COMPLETED |
| SBX-03 | `feat(sandbox): add run policy contracts` | 领域类型、状态机和策略契约 | COMPLETED |
| SBX-04 | `feat(sandbox): persist runs and artifacts` | MySQL 事实表与 Repository | COMPLETED |
| SBX-05 | `feat(sandbox): add artifact storage boundary` | MinIO Artifact 隔离与校验 | COMPLETED |
| SBX-06 | `feat(api): add sandbox run lifecycle endpoints` | 创建、查询、取消 API 与审计 | COMPLETED |
| SBX-07 | `feat(sandbox-controller): scaffold internal service` | 独立 Controller 基座 | COMPLETED |
| SBX-08 | `feat(sandbox-controller): build hardened jobs` | 安全 JobSpec 生成器 | COMPLETED |
| SBX-09 | `feat(sandbox-controller): manage job lifecycle` | 幂等创建、查询和取消 | COMPLETED |
| SBX-10 | `feat(sandbox-controller): collect results and cleanup` | 结果收集、超时和 TTL 清理 | COMPLETED |
| SBX-11 | `feat(deploy): isolate sandbox runtime` | Namespace、RBAC、Quota、NetworkPolicy | COMPLETED |
| SBX-12 | `feat(sandbox): dispatch runs through controller` | Backend Client、短时派发和断路器 | COMPLETED |
| SBX-13 | `feat(sandbox): reconcile run convergence` | 多实例安全的状态收敛与清理重试 | COMPLETED |
| SBX-14 | `feat(sandbox): build diagnostic evidence packages` | 证据采集、裁剪、脱敏和哈希 | COMPLETED |
| SBX-15 | `feat(sandbox): add fixed diagnostic tools` | 固定工具目录与首批工具 | COMPLETED |
| SBX-16 | `feat(sandbox): isolate generated code execution` | Python/Shell 受限执行 | COMPLETED |
| SBX-17 | `feat(sandbox): validate manifests and runbooks` | YAML、Helm、Patch、Runbook 校验 | COMPLETED |
| SBX-18 | `feat(sandbox): simulate remediation plans` | 独立仿真集群验证 | COMPLETED |
| SBX-19 | `feat(agent): route diagnostics through sandbox` | Planner/Executor 异步路由 | COMPLETED |
| SBX-20 | `feat(verifier): gate remediation with sandbox evidence` | 恢复工作流与生产动作硬边界 | COMPLETED |
| SBX-21 | `feat(console): add sandbox run operations` | Run 列表、详情、Artifact 和取消 | PLANNED |
| SBX-22 | `feat(observability): monitor sandbox operations` | 指标、Dashboard 和告警 | PLANNED |
| SBX-23 | `build(sandbox): close ci security and operations gates` | CI、安全契约、部署和运行手册 | PLANNED |

### SBX-00：冻结计划

改动：

- 只提交本计划书。
- 确认 KubeOnCall 与 AgentSpace 完全解耦。
- 冻结“无生产凭据、无任意镜像、无直接生产动作”三条红线。

验证：

- Markdown 结构完整。
- `git diff --check` 通过。

回滚：

- 仅回滚文档，不影响运行系统。

### SBX-01：配置与功能开关

改动：

- 在 `KubeOnCallProperties` 增加 `sandbox` 配置组。
- 增加总开关和四个模式开关：
  `enabled/fixed-diagnostic/generated-code/manifest-validation/remediation-simulation`。
- 增加 `agent-auto-route-enabled` 独立开关。
- 增加硬上限、Controller endpoint、超时和保留期配置。
- `/api/v1/capabilities` 回显部署能力和限制，不回显 Secret。
- 默认全部关闭，现有行为不变。

验证：

- 配置绑定、默认值、非法上限和 Capabilities 契约测试。
- 关闭 Sandbox 时 Spring Context 与现有测试不受影响。

完成记录：

- 新增 `SandboxProperties`：总开关与四模式开关、`agent-auto-route-enabled`、
  Controller endpoint/超时/最大响应、硬上限（超时 5/15 分钟、输入 50MiB、输出 10MiB、
  日志 2MiB、脚本 256KiB、Artifact 保留 24h、单告警并发 1、全局并发 4），CPU/内存/临时存储
  以 Kubernetes 量纲字符串保存，跨资源上限校验留待 SBX-08 JobSpec Builder。
- `KubeOnCallProperties.Sandbox` 组合复用，`application.yml` 增 `sandbox:` 配置块，
  全部经 `KUBEONCALL_SANDBOX_*` 环境变量覆盖，默认全关。
- `CapabilitiesService` 在 `features.sandbox` 回显总开关与四模式开关及自动路由标志；
  总开关开启时在 `limits.sandbox` 回显校验后的限制；Controller endpoint 与 Secret 永不回显。
- `CapabilitiesService` 构造期在总开关开启时调用 `SandboxProperties.validate()` 快速失败，
  收集全部越限字段而非静默裁剪。
- 测试：`KubeOnCallPropertiesTest` 增 6 例（默认全关、绑定覆盖、非法超时上限、非法
  max-timeout 上限、输出/脚本/并发/保留期越限、默认值通过校验）；新增 `CapabilitiesServiceTest`
  3 例（默认回显能力不含敏感字段、启用时回显能力与限制、Controller endpoint 与 Secret 不泄漏）。
- 关闭 Sandbox 时 `KubeOnCallApplicationTests.contextLoads` 与 `V1ApiContractTest` 通过；
  既有失败（`ApiTokensControllerTest`、`LegacyApiDeprecationWebTest`）在父提交已存在，与本单元无关。

Codex 审核补充（提交 `7712ccb` 后审核，补丁提交 `[SBX-01-fix]`、`[SBX-01-fix2]`、`[SBX-01-fix3]`）：

- CPU/内存/临时存储原先仅校验非空，`cpu=2`、`memory=2Gi`、`cpu=banana` 可越过 §7.1 硬上限
  通过启动校验并被回显为已校验限制。新增 Kubernetes 量纲解析（CPU millicores、字节
  二进制/十进制后缀），在 `validate()` 中对 CPU≤1 核、内存≤1Gi、临时存储≤2Gi 强校验，
  越限或畸形（负值、未知后缀）一律收集为违规字段；SBX-08 JobSpec Builder 仍二次校验。
- Controller 调用超时/最大响应原先未校验，零/负超时或无界响应可在总开关开启时启动成功。
  新增 `controllerConnectTimeoutMillis`≤30s、`controllerReadTimeoutMillis`≤60s、
  `controllerMaxResponseBytes`≤16MiB 的硬上限与正数校验。
- 第二轮审核指出解析精度与语法问题：`double`+`Math.round` 会让略超上限的小数向下取整越过
  上限（如 `1.0001` 核），`Double.parseDouble` 接受 `0x1.0p0`/`1f` 等 Kubernetes 不接受的
  Java 数值形式，且校验 trim 但未归一存储。改为 `BigDecimal` 精确运算 + `longValueExact()`
  （非整单位小数一律拒绝而非取整），显式 Kubernetes 十进制语法（拒绝 Java-only 形式），
  并在 `validate()` 中将 CPU/内存/临时存储归一为 trim 后的规范值，避免回显被 K8s 拒绝的串。
- 第三轮审核指出 millicores 分支（`m` 后缀）未走 `QUANTITY_MANTISSA` 语法校验，`1e3m` 会因
  `BigDecimal` 接受指数而被当作 1000 millicores 通过，但 Kubernetes 不允许指数与 `m` 后缀并存。
  补丁对该分支的尾数同样强制 Kubernetes 兼容十进制语法；新增 `1e3m` 拒绝回归测试。
- 测试新增 9 例：K8s 量纲越限、畸形量纲、边界与交替形式（`1000m`/`1024Mi`/`2048Mi`）、
  Controller 调用限制越限、整数单位刚好越上限（`1001m`/`1073741825`）、非整单位小数拒绝、
  Java-only 数值形式拒绝、millicores 指数拒绝、空格归一。`KubeOnCallPropertiesTest` 共 17 例全通过。

回滚：

- 直接 revert；无数据影响。

### SBX-02：权限与角色

改动：

- 增加四个 `PermissionCode`。
- 在下一 Flyway 版本增量写入权限及内置角色映射。
- 扩展 API Token scope 校验和前端权限常量。
- `TaskPermissionPolicy` 识别 Sandbox 任务。

验证：

- Viewer 只能读，Operator 可执行/取消，Admin 可管理。
- 被撤销角色和 Token scope 立即失效。

完成记录：

- `PermissionCode` 增 `sandbox:read/execute/cancel/manage` 四个稳定权限码。
- 新增 `V15__sandbox_permissions.sql`：向前兼容增量插入四条权限，并按 §6.5 映射内置角色
  —— Viewer 仅 `sandbox:read`，Operator `sandbox:read/execute/cancel`，Admin 全部四项；
  无 down 迁移、不触碰既有行。
- API Token scope 校验无需改动：`ApiTokensController.normalizeScopes` 已用
  `actor.hasPermission(scope)` 校验，`ApiTokenAuthenticationService` 取 token scope 与
  owner 当前权限的交集，因此新增权限随 owner 角色自动生效，撤销角色或 scope 立即失效。
- `TaskPermissionPolicy` 识别 Sandbox 任务（资源含 `sandbox` 或任务以 `SANDBOX_` 开头）→
  要求 `sandbox:execute`；取消由 Run 生命周期 API（SBX-06）单独强制。
- 前端 `permissions.ts` 增四个常量；`npm run typecheck` 通过。
- 测试：`TaskPermissionPolicyTest` 增 1 例（Sandbox 任务映射 `sandbox:execute`）；
  `IdentityMySqlIT` 增 1 例（真实 MySQL 容器验证四权限 seeded 且角色映射符合 §6.5），
  Failsafe 通过，V15 迁移成功应用至 v15。

Codex 审核补充（提交 `42c469e` 后审核，补丁提交 `[SBX-02-fix]`）：

- `V1AuthenticationFilter.legacyPermissions()` 未同步四项 sandbox 权限，而 legacy-token
  兼容默认开启，配置的 Viewer/Operator/Admin token 会绕过 §6.5 矩阵。按映射补齐：Viewer
  `sandbox:read`，Operator `sandbox:read/execute/cancel`，Admin 全部四项。
- `TaskPermissionPolicy` 顺序 bug：sandbox 识别排在 skill/knowledge/memory 之后，资源名同时
  含两者的任务（如 `sandbox_skill`）会被降级为 `skill:read`，Viewer 即可越权读取任务/SSE。
  将 sandbox 分类提前到只读域之前，确保 `sandbox:*` 资源恒映射 `sandbox:execute`。
- 测试新增 3 个文件/用例：`V1AuthenticationFilterSandboxPermissionsTest`（3 例验证 legacy
  三角色 §6.5 sandbox 权限）、`ApiTokenAuthenticationServiceSandboxTest`（3 例验证 scope 交集、
  角色撤销后下一请求立即失效、已撤销 token 永不认证）、`TaskPermissionPolicyTest` 增 1 例
  （混合信号资源仍映射 `sandbox:execute` 且非 sandbox 任务不受影响）。

回滚：

- 代码可 revert；数据库中的新增权限保留但不再被应用引用。

### SBX-03：领域与策略契约

改动：

- 新增 `sandbox/domain`、`sandbox/policy`。
- 定义 Run Mode、Run Status、Cleanup Status、Artifact Type、Classification。
- 实现显式状态迁移表，禁止终态倒退。
- 定义 `SandboxToolSpec`、`SandboxResourceLimits`、`SandboxExecutionPolicy`。
- 拒绝任意镜像、超限资源、生产凭据引用和未知工具。

验证：

- 状态机全组合测试。
- 工具 digest、资源上下限、模式开关和风险策略测试。

回滚：

- 纯领域代码，可独立 revert。

完成记录：

- 新增 `com.kubeoncall.sandbox.domain`：`SandboxRunMode`（四模式 + 默认审批/凭据/风险姿态）、
  `SandboxRunStatus`（PENDING→…→四终态，`allowedNext()` 显式迁移表 + `isTerminal()`）、
  `SandboxCleanupStatus`（NOT_REQUIRED→…→SUCCEEDED/FAILED，与运行状态分离）、
  `SandboxArtifactType`（INPUT/OUTPUT/LOG/REPORT，前缀由类型固定防穿越）、
  `SandboxClassification`（PUBLIC/INTERNAL/UNTRUSTED）、`SandboxRiskLevel`（LOW/MEDIUM/HIGH 有序比较）、
  `SandboxRunStateException`（域异常，留待 web 层映射统一信封）、`SandboxStateMachine`（无状态纯策略，
  `resolveRunStatus`/`resolveCleanupStatus`/`callbackIsApplicable`，终态不可倒退，迟到回调不覆盖终态）。
- 新增 `com.kubeoncall.sandbox.policy`：`SandboxResourceLimits`（值类型，CPU/内存/临时存储为
  Kubernetes 量纲串，标量上限在构造期强校验非正即拒）、`SandboxToolSpec`（digest-pinned 镜像强制，
  `isDigestPinned` 拒 tag/`latest`/非 sha256/过短 hex；entrypoint/schema/网络策略非空；默认
  `NetworkEgressPolicy.DENY_ALL`）、`SandboxExecutionPolicy`（无状态评估器，返回 `Decision`：
  模式开关/凭据红线/审批规则——FIXED_DIAGNOSTIC、MANIFEST_VALIDATION 不审批；GENERATED_CODE
  满足 auto-run 免审批；REMEDIATION_SIMULATION 默认审批；HIGH 风险恒审批且不可豁免）。
- 将 SBX-01 的 `SandboxProperties.RunMode` 提升为权威域类型 `SandboxRunMode`，删除重复枚举；
  `isModeEnabled(SandboxRunMode)` 改用域类型，`KubeOnCallPropertiesTest` 同步更新（17 例仍通过）。
- ArchUnit：`coreBusinessDomainsDoNotDependOnWeb` 与 `newCorePackagesHoldNoWebFields` 均补
  `..sandbox..`，确保 sandbox 域不依赖 web 层；`ArchitectureRulesTest` 通过。
- 测试：`SandboxStateMachineTest`（9 例，全组合迁移表 + 终态不可逆 + 迟到回调不覆盖取消）、
  `SandboxToolSpecTest`（8 例，digest 拒 tag/无 digest/非 sha256/空字段/非正资源/空 mode、
  最短 32 hex 接受）、`SandboxExecutionPolicyTest`（6 例，模式禁用拒绝、低中高风险审批矩阵、
  豁免只放宽非高风险）。共 23 例全通过。
- 既有失败（`ApiTokensControllerTest` ×3、`LegacyApiDeprecationWebTest` ×1）在父提交已存在，
  与本单元无关；`spotless:apply`、`checkstyle:check`、`git diff --check` 通过。

### SBX-04：Run 与 Artifact 持久化

改动：

- 新增 `koc_sandbox_run`、`koc_sandbox_artifact`。
- 实现 Repository、分页查询、CAS 更新、租约 claim、heartbeat 和 fencing。
- 创建 Run、Artifact 元数据和短时派发任务保持同事务。
- 添加 `(mode, idempotency_key)` 或等价稳定去重约束。

验证：

- Repository 单测和 MySQL Testcontainers 集成测试。
- 并发 claim、过期 lease reclaim、旧 owner 写拒绝和重复创建测试。

回滚：

- revert Repository；新增表保留。

完成记录：

- 新增 `V16__sandbox_run_artifact.sql`：`koc_sandbox_run`（run 事实 + owner/lease/fencing/version，
  `(mode, idempotency_key)` 唯一去重、run_status/cleanup_status/mode/risk CHECK 约束、reconcile/
  alarm/execution/controller 索引）与 `koc_sandbox_artifact`（对象引用 + sha256/size/classification，
  `(bucket, object_key)` 唯一、retention 索引、FK→run）。只存摘要/引用/校验值，正文/脚本/完整日志留 MinIO。
  纯向前增量、无 down、不触碰既有表。
- 新增 `com.kubeoncall.sandbox`：`SandboxRunRecord`/`SandboxArtifactRecord`（值类型，引用 domain 枚举）、
  `SandboxRunRepository`（`@ConditionalOnProperty(mysql-enabled)`，与既有 MySQL 事实仓库一致）。
- Repository 能力：`create`（DuplicateKey → 回查既有 run，幂等去重不抛错）、`findByPublicId`/
  `findByModeAndIdempotencyKey`、`list`（mode/status/execution/alarm/createdFrom/createdTo 过滤 + 分页）、
  `claim`（全局扫描，PENDING 无 lease 或过期 lease，`FOR UPDATE SKIP LOCKED` + fencing_token+1）、
  `claimByPublicId`（定向恢复）、`heartbeat`/`updateProgress`（owner+fencing+lease 三重谓词）、
  `markDispatching`/`transitionRunStatus`/`complete`/`fail`（先经 `SandboxStateMachine` 校验合法迁移，
  再 owner+fencing+version CAS 写）、`cancel`（CAS + 非终态谓词，终态重复取消返回 false）、
  `transitionCleanupStatus`（cleanup 状态机校验，终态不可倒退）、
  `createArtifact`/`findArtifactsByRun`/`findArtifactByPublicId`/`findExpiredArtifacts`（TTL janitor）。
- 终态写清 owner_token/lease_until；终态迁移由 `SandboxStateMachine.resolveRunStatus` 在 SQL 前
  拦截，迟到回调/过期 owner 被 owner+fencing+version 谓词拒绝而不覆盖终态。
- 新增 `SandboxConfiguration`：注册无状态 `SandboxStateMachine`/`SandboxExecutionPolicy` 单例 bean。
- ArchUnit：`..sandbox..` 已在 SBX-03 纳入两条规则；本单元 repository 依赖 web 层为 0（`grep
  import com.kubeoncall.web` 为 0），`ArchitectureRulesTest` 通过。
- 测试：`SandboxRunRepositoryIT`（8 例，真实 MySQL Testcontainers + Flyway V16 迁移）：幂等去重、
  CAS 取消 + 终态重复取消、claim fencing 前进 + 过期 owner 合法迁移被拒、状态机非法迁移拦截、
  heartbeat 仅 owner 续约、cleanup 终态不可倒退、artifact 列表/过期查询、全局 claim 扫描。
  `-Pintegration-test` failsafe 全 57 例通过（含本单元 8 例）。
- 既有失败（`ApiTokensControllerTest` ×2、`AsyncTaskWorkerTest` ×1、`LegacyApiDeprecationWebTest`
  ×1）在父提交（SBX-03 HEAD）已存在，与本单元无关；`spotless:apply`、`checkstyle:check`、
  `git diff --check` 通过。MySQL 关闭时 Repository bean 不创建，Spring Context 与现有行为不变。

Codex 审核补充（提交 `2813b51` 后审核，补丁提交 `[SBX-04-fix]`）：

- claim/claimByPublicId 原先不递增 `attempt` 也不校验 `max_attempts`，反复失租的 run 会被无限
  重试，绕过配置上限。补丁在 SELECT 与 UPDATE 谓词均加 `attempt < max_attempts`，并在 claim
  时 `attempt = attempt + 1`（与 `AsyncTaskRepository.claimNext` 一致）；耗尽的 run 不再被 claim。
- `markDispatching`/`transitionRunStatus`/`complete`/`fail` 的 CAS 谓词原先只校验 owner+fencing+
  version，未要求 `lease_until > now`。若 owner 的 lease 已过期但尚无后继 reclaim，旧 owner 仍能
  写入/终态化 run，破坏 lease 即时吊销语义（`heartbeat`/`updateProgress` 原本已有该谓词）。
  补丁在这四个写入的 WHERE 子句统一补 `AND lease_until > ?`，lease 过期立即吊销写入权。
- 测试新增 2 例：`claimShouldHonorMaxAttemptsAndStopReclaimingExhaustedRuns`（max_attempts=2，
  两次 claim 各耗一次 attempt，第三次 claim 被拒）、`expiredOwnerCannotMutateRunBeforeReclaim`
  （owner lease 过期后、后继未 reclaim 前，markDispatching 被拒）。`SandboxRunRepositoryIT` 共
  10 例全通过，failsafe 全 57 例绿。

### SBX-05：Artifact 存储边界

改动：

- 新增独立 `SandboxArtifactStore`，不复用带知识库语义的方法名。
- 固定对象前缀 `sandbox/{runId}/{inputs|outputs|logs|reports}/`。
- 支持流式写入、最大字节数、SHA-256、MIME allowlist 和单对象短时授权。
- 增加结果读取上限、临时 URL 脱敏和 Janitor 查询能力。

验证：

- 对象 key 穿越、超限、哈希不一致、MIME 拒绝和删除幂等测试。
- MinIO 集成测试沿用现有 Testcontainers/真实依赖分层。

回滚：

- 关闭 Sandbox 后 revert；已写对象由 TTL Janitor 清理。

完成记录：

- 新增独立 `SandboxArtifactStore`（`com.kubeoncall.sandbox`，不复用知识库语义方法名）：
  对象 key 由服务端按 `sandbox/{runPublicId}/{inputs|outputs|logs|reports}/{flatFilename}` 派生，
  调用方只给 runId+type+filename，filename 经 `flatFilename` 归一为扁平 basename（取最后路径段、
  非 `[A-Za-z0-9._-]` 替为 `-`、空/`.`/`..` 回退 `artifact`），永不产生 `/` 或 `..`，从根本上杜绝
  object-key 穿越。
- 写入：`store` 先把内容读入有界缓冲（`BoundedDigest`，超 `maxBytes` 立即抛 `TooLargeException`，
  在任何字节进 MinIO 前强制上限——流式 digest 喂给 putObject 会让 OkHttp 吞掉中途异常并上传截断
  对象，故改用缓冲校验后再上传），同步计算 SHA-256；MIME 必须在固定 allowlist
  （json/ndjson/text/plain/yaml/octet-stream）内；按类型取 `SandboxProperties` 的 inputMaxBytes/
  outputMaxBytes/logMaxBytes 上限（REPORT 复用 output 上限）。返回 `StoredArtifact(bucket, key, mime,
  size, sha256)` 供 Repository 落库。
- 读取授权：`presignedGetUrl` 单对象短时 GET URL，TTL≤5 分钟、objectKey 必须以 `sandbox/` 前缀
  （`requireBucketReference` 拒绝越界 key），URL 不入日志、不持久化。`delete` 幂等（缺失对象即成功）。
  `listObjectsForRun` 仅返回 `sandbox/{runPublicId}/` 前缀下对象，janitor 无法被指向任意前缀。
- 测试：`SandboxArtifactStoreKeyTest`（4 例，objectKey 规范前缀、flatFilename 路径剥离/穿越拒绝/无 `/`无 `..`、
  空 runId/null type 拒绝）；`SandboxArtifactStoreIT`（6 例，真实 MinIO `localhost:9000`：写入+SHA-256+
  规范 key、超 type 上限拒绝且不写对象、MIME 拒绝、穿越 filename 归一、presigned URL 短时+前缀约束+
  TTL≤5min、delete 幂等+list 仅 run 前缀）。failsafe 全 65 例通过。
- ArchUnit：`..sandbox..` 已纳入两条规则，store 不依赖 web 层；`spotless`/`checkstyle`/
  `git diff --check` 通过。MinIO 关闭时 store bean 仍创建但调用报 `bucket not configured`，不影响现有行为。

Codex 审核补充（提交 `420bf60` 后审核，补丁提交 `[SBX-05-fix]`）：

- `presignedGetUrl`/`delete` 原先只要 objectKey 以 `sandbox/` 开头就接受任意 bucket。共享 MinIO
  凭据可访问多 bucket，恶意/受损元数据记录可授权读取或删除配置 sandbox bucket 之外的对象。
  补丁将 `requireBucketReference` 改为实例方法，强制 `bucket.equals(requireBucket())`，否则拒绝。
- `objectKey`/`runPrefix` 原先只校验 runPublicId 非空，含 `/` 的 id（如 `a/inputs`）会与 run `a`
  产生嵌套前缀，cleanup run `a` 会误删另一 run 的 artifact。补丁新增 `requireRunId` 强制
  `sbx_[0-9a-f]{1,128}` 规范格式（Repository 生成的 publicId 即此格式），杜绝路径分隔符。
- 测试新增 2 例：`objectKeyShouldRejectRunIdWithPathSeparatorOrNonCanonicalForm`（`sbx_a/inputs`、
  `a/inputs`、`run-1` 均拒，`sbx_abc123` 接受）；`presignedGetUrlAndDeleteShouldRejectForeignBucket`
  （其他 bucket + 合法 sandbox key 仍被 presigned/delete 拒绝）。`SandboxArtifactStoreKeyTest` 5 例、
  `SandboxArtifactStoreIT` 7 例全通过，failsafe 全 66 例绿。

### SBX-06：Run API、审计与事件

改动：

- 实现创建、列表、详情、取消和 Artifact 元数据 API。
- 创建要求 `Idempotency-Key`，取消要求 version/CAS。
- 加入权限、统一错误码、OpenAPI 和生成 Client。
- 状态变化写 Audit + Outbox，SSE 复用现有事件通道。
- API 只创建 Run，不在请求线程调用 Controller。

验证：

- Controller 契约测试、权限矩阵、幂等、并发取消和敏感字段不返回测试。
- OpenAPI 与生成 Client 无差异。

回滚：

- 关闭总开关后 revert API；表和 Artifact 保留。

完成记录：

- 新增 `/api/v1/sandbox-runs`：创建、列表、详情、取消、Artifact 元数据五个接口。创建强制
  `Idempotency-Key`（16～128 字符），取消强制 `If-Match` 正版本；列表/详情/Artifact 要求
  `sandbox:read`，创建要求 `sandbox:execute`，取消要求 `sandbox:cancel`。
- 新增 `SandboxRunCommandService`：在单一事务内写入 Run、操作审计、Outbox 与幂等响应；请求线程
  不调用 Controller。创建事件为 `sandbox.run.created`，取消事件为 `sandbox.run.cancelled`；二者
  已注册至既有 Outbox→SSE 通道，新增 `sandbox-runs` 订阅主题并要求 `sandbox:read`。
- API 永不接收镜像、entrypoint、网络策略、对象 bucket/key、租约、fencing 或完整请求/结果内容。
  创建仅以 `toolId`/`toolVersion` 引用服务端 `SandboxToolCatalog`；SBX-15 注入固定工具前，空目录
  会拒绝未知工具，确保新接口不会成为任意镜像执行入口。
- OpenAPI 合同已由本地 Spring Boot 合同生成任务重新生成至 `api/openapi.json`，前端
  `openapi-typescript` 类型 Client 同步生成至 `frontend/src/api/generated/schema.ts`。
- 验证：`SandboxRunsControllerContractTest` 覆盖创建身份绑定、幂等键、取消 CAS 前置条件与
  Artifact 存储位置脱敏；`RealtimeConfigurationTest`/`RealtimeOutboxEventHandlerTest` 覆盖新增
  SSE 事件路由。相关定向测试共 31 项通过；OpenAPI 合同生成 `verify -Popenapi-contract -DskipTests`
  通过。

### SBX-07：Controller 服务基座

改动：

- 新增 `sandbox-controller/` Go module、配置、健康检查和 Dockerfile。
- 提供内部 API 契约、统一错误和 requestId。
- 实现 Backend 身份校验、时间戳、签名与重放窗口。
- 增加优雅关闭、请求大小、并发和超时限制。

验证：

- `go test ./...`、`go vet ./...`。
- 未签名、过期签名、重复 nonce、超大请求和关闭中的请求测试。

回滚：

- 独立目录可 revert，不影响 Backend。

完成记录：

- 新增独立 `sandbox-controller/` Go module 与多阶段、nonroot Dockerfile；该模块不依赖 Backend
  代码、AgentSpace API 或 Kubernetes 凭据。
- 新增 `/healthz` 与内部 `/internal/v1/runs` 契约。每个响应携带 `X-Request-Id`；内部 API 使用
  `X-Sandbox-Key-Id`、Unix 时间戳、nonce 与 HMAC-SHA256 签名，签名覆盖方法、路径、时间戳、nonce
  与请求体摘要。
- nonce 在验签成功后进入带过期清理的内存重放窗口；未签名、错误/过期签名和重复 nonce 统一返回
  无敏感细节的 `UNAUTHENTICATED`。请求大小、并发、读写超时和优雅关闭均由服务端固定配置控制。
- 尚未进入 SBX-08/09 的 JobSpec 与生命周期实现，已签名的 Run 请求明确返回
  `CONTROLLER_NOT_READY`，不会执行或接触集群。
- 验证：`go test ./...` 与 `go vet ./...` 通过；覆盖未签名、有效签名、重复 nonce、过期签名和超大
  请求。

### SBX-08：安全 JobSpec

改动：

- 实现纯函数式 JobSpec Builder。
- 固化 namespace、digest allowlist、资源上限、SecurityContext、TTL 和标签。
- 禁止 hostPath、hostNetwork、privileged、ServiceAccount Token 和任意 env Secret。
- 输入脚本通过 Artifact 传递，不拼接到 shell command。

验证：

- Golden/结构测试覆盖全部安全字段。
- 恶意镜像、命令、env、volume、标签和值超限全部拒绝。

回滚：

- revert Builder；Controller 仍只有健康检查。

完成记录：

- Controller 新增纯函数式 `internal/jobs.Builder` 与受限 `JobSpec` 投影；仅接收 Run ID、服务端
  Tool ID/Version、Artifact URI 和受控标签，镜像、命令、环境变量、卷和 ServiceAccount 不来自调用方。
- Tool 必须来自 Controller 的服务端目录且为 `@sha256:` digest；固定 namespace、资源/超时/TTL 天花板、
  `runAsNonRoot`、只读根文件系统、禁止提权、drop `ALL` capabilities、禁止 host network、privileged
  与 ServiceAccount Token 自动挂载。
- 输入仅接受 `minio://` Artifact URI，不把脚本或正文拼接进命令行。非法 Run ID、未知/非 digest Tool、
  非 Artifact 输入和非 sandbox 标签均拒绝。
- 验证：`go test ./...` 与 `go vet ./...` 通过；结构测试覆盖安全默认值和恶意输入拒绝。

### SBX-09：Kubernetes Job 生命周期

改动：

- 按 Run ID 幂等创建 Job。
- 查询 Pod/Job 状态并归一化。
- 支持取消、超时、已存在重放和 Controller 重启恢复。
- 使用 label 和 annotation 绑定 Run ID、工具版本和过期时间。

验证：

- Fake Client 单测。
- create 重放、cancel 重放、终态查询、冲突对象和未知对象测试。

回滚：

- 先关闭 Controller 创建入口，再 revert；不主动删除无法确认归属的 Job。

完成记录：

- 新增 `internal/kubernetes` 包：`Manager`（持 `kubernetes.Interface` + namespace + `jobs.Builder`，
  测试注入 fake clientset）+ `HTTPAdapter`（实现 `httpapi.LifecycleManager`，把 typed `Status` 转
  transport struct）+ helpers（run-id 正则、sentinel 错误、`NewMilliQuantity`/`NewQuantity` 资源量纲）。
- `EnsureJob`：先按 `sandbox.kubeoncall.io/run-id` label 查既有 Job，存在则返回其状态（create 重放
  不创建第二个 Job）；不存在则 `builder.Build` 生成 JobSpec，构造 `batchv1.Job`（completions=1/
  parallelism=1/BackoffLimit=0 一次性/ActiveDeadlineSeconds=超时/TTLSecondsAfterFinished=清理/
  AutomountServiceAccountToken=false/RunAsNonRoot/ReadOnlyRootFilesystem/AllowPrivilegeEscalation=false/
  SeccompProfile RuntimeDefault/Capabilities drop ALL/hostNetwork=false），Create 冲突（AlreadyExists）
  回查 winner 返回状态。Job 带 run-id/tool-version/expires-at(RFC3339) label，controller 重启可从集群
  状态恢复 scope。
- `Status`：按 Job conditions + active/succeeded/failed 归一化为 PENDING/RUNNING/SUCCEEDED/FAILED/
  TIMED_OUT（DeadlineExceeded）/CANCELLED（DeletionTimestamp!=nil）；无 Job 返回 Exists=false/UNKNOWN。
- `Cancel`：`Delete`(Background 传播) 幂等；缺失 Job 返回 CANCELLED 成功（late retry 不报错）。
- `httpapi.Server`：`NewServerWithManager` 注入可选 `LifecycleManager`；`POST /internal/v1/runs`→EnsureJob、
  `GET /internal/v1/runs/{runId}`→Status、`DELETE /internal/v1/runs/{runId}`→Cancel、
  `GET /internal/v1/runs/{runId}/logs`→NOT_READY（SBX-10 收口）。GET/DELETE 用空 body HMAC 校验。
  `Config` 增 `SandboxNamespace`/`Tools`/`JobCeiling`（默认 pod-inspect:v1 + §7.1 上限）。
  `main.go` 用 `rest.InClusterConfig` 构造 clientset + Manager，无 in-cluster config 时降级 scaffold
  模式（lifecycle 端点 CONTROLLER_NOT_READY，health/auth 仍服务）。
- 测试：`manager_test.go`（fake clientset，create 幂等/重放只产 1 Job、非法 run-id 拒绝、
  PENDING/RUNNING/SUCCEEDED/UNKNOWN 归一、TIMED_OUT vs FAILED 区分、cancel 幂等+缺失 Job 成功、
  hardened SecurityContext/TTL/ActiveDeadline/label 断言）；`lifecycle_test.go`（HTTP 层 fake manager，
  各端点未签名拒绝、POST 经 manager 创建、GET/DELETE 委托、nil manager 返回 NOT_READY、logs NOT_READY）。
  `go test ./...` + `go vet ./...` + `gofmt -l .` 全通过。
- 依赖：新增 `k8s.io/api`/`apimachinery`/`client-go` v0.36.3（经 goproxy.cn + sum.golang.google.cn 拉取）。

Codex 审核补充（提交 `ae94ebb` 后审核，补丁提交 `[SBX-09-fix]`）：

- `ExpiryLabel` 原先用 `time.RFC3339`（含冒号）作为 label value，Kubernetes label value 禁止冒号，
  真实 apiserver 会拒绝所有 Job 创建（fake clientset 不做该校验，测试漏网）。补丁将 expiry 改为
  存 annotation（`ExpiryAnnotation`，annotation 值无冒号限制），run-id/tool-version 仍为合法 label。
  测试增断言：expiry annotation 非空 + 遍历所有 label value 不含冒号。

### SBX-10：结果、日志和清理

改动：

- 收集退出码、终止原因、受限日志和输出 Artifact 引用。
- 区分 OOM、Deadline、ImagePull、PolicyDenied、ToolFailure。
- 增加 Job TTL、孤儿 Job Janitor 和清理失败重试。
- 日志裁剪并过滤 URL、Token 和 Secret。

验证：

- 各终止原因映射测试。
- 超长日志、缺失输出、输出校验失败和孤儿清理测试。

回滚：

- 保留 Job TTL，revert 结果增强；不得关闭已部署的基础 TTL。

完成记录：

- 新增 `Manager.CollectResult`：读取 Job/Pod 终态、退出码、开始/结束时间、输出标记与受限日志；日志
  读取和对外返回均受字节上限约束，超过上限显式追加截断标记，绝不超过配置预算。
- 失败原因归一为 `OOM`、`DEADLINE`、`IMAGE_PULL`、`POLICY_DENIED`、`TOOL_FAILURE` 和 `UNKNOWN`；
  容器终止原因优先于 Job 通用失败原因。日志中的 Authorization/Bearer、Token、Secret、Password、
  JWT 和 URL 用户凭据均在离开 Controller 前脱敏。
- 新增 `Janitor.ReapOnce`：只扫描有 sandbox 应用标签的 Job；对终态 Job 或到期 annotation Job 清理，
  单个删除失败记录在结果中但不阻断后续 Job，下一轮可重试；未知/非 sandbox Job 永不删除。
- HTTP `GET /internal/v1/runs/{runId}/logs` 现返回归一化、脱敏且裁剪后的结果，不再返回原始日志。
- 验证：`go test ./...`、`go vet ./...`、`gofmt -l .` 通过；新增 OOM/ImagePull/Deadline 映射、
  Token/URL 脱敏、日志严格上限、缺失输出、终态/过期 Job 清理与非 sandbox Job 保护测试。

### SBX-11：部署隔离

改动：

- Helm 增加可选 Sandbox Controller Deployment/Service。
- 增加独立 namespace、ServiceAccount、最小 RBAC、ResourceQuota、LimitRange。
- 增加 Backend→Controller 和 Sandbox Job 网络策略。
- Secret 只注入 Controller 身份，不注入 Job。
- Compose 仅提供 Mock/开发 Controller，不把 Docker Socket 挂给 Backend。

验证：

- `helm lint`、`helm template`、Schema/快照测试。
- RBAC 规则不允许跨 namespace，不允许读取 Secret。
- `docker compose config --quiet`。

回滚：

- values 关闭 Controller；保留 namespace 中仍需清理的 Run 资源。

完成记录：

- Helm 新增默认关闭的 `sandboxController`：Controller 部署在独立 namespace，使用独立 ServiceAccount、
  namespace-scoped Role/RoleBinding（仅 Jobs、Pods、Pods/log，绝不读取 Secret 或使用 ClusterRole）。
- 新增 ResourceQuota、LimitRange、Controller 和 Job NetworkPolicy；Job 默认拒绝所有入站/外网出站，仅允许
  集群 DNS，Controller 仅允许 Release namespace 入站与 DNS/Kubernetes API 出站。
- HMAC Secret 仅注入 Backend 与 Controller；Job 不接收 Secret 挂载或 Docker Socket。`helm lint`、启用 Controller 的
  `helm template`、RBAC 静态检查和 `docker compose config --quiet` 均通过。

### SBX-12：Backend 派发

改动：

- 新增 `SandboxControllerClient`。
- 使用短时 `SANDBOX_DISPATCH` Task 调用 Controller 后立即返回。
- Controller 调用设置连接/读取超时、最大响应、重试预算和断路器。
- 请求以 Run ID 幂等，重试不创建第二个 Job。

验证：

- 成功、超时、断路器打开、重复派发、非重试错误和敏感日志测试。
- AsyncTask handler 不长时间轮询。

回滚：

- 关闭总开关并 revert Client/Handler；已派发 Run 由 Controller TTL 处理。

完成记录：

- 新增 `SandboxControllerClient`：只调用 `POST /internal/v1/runs` 一次，不轮询 Job；使用与 Go Controller
  一致的 HMAC-SHA256 签名（method/path/timestamp/nonce/body digest），连接/读取/响应大小均受 Sandbox
  配置硬上限约束。Controller 错误正文、HMAC Secret 与内部 Artifact URL 均不写入异常或日志。
- 创建 Run 的同一事务内新增 `SANDBOX_DISPATCH` 任务；任务以 Run ID 为唯一幂等键。首次派发通过 Run lease/
  fencing 将状态置为 `DISPATCHING`，网络异常可重试并复用相同 Run ID，永久 4xx 拒绝会终止任务而不继续重试。
  该 Handler 只做一次派发，状态轮询、结果收集与清理仍由 SBX-13 负责。
- Helm 同时向 Backend 和 Controller 注入同一个 HMAC Secret，Backend 仅通过内部 ClusterIP 调用 Controller，
  Job 仍无 Secret 挂载。Go 控制器契约改为显式 lower-camel JSON tag，避免跨语言字段命名漂移。
- 自动化验证：`SandboxControllerClientTest` 覆盖成功签名、超时、断路器、4xx 非重试和错误内容脱敏；
  `SandboxDispatchTaskHandlerTest`/`AsyncTaskWorkerTest` 覆盖初次 claim、重复 Run ID 派发、非重试任务终止，
  定向 Maven 测试、Controller `go test ./...`/`go vet ./...`、`helm lint` 与启用 Controller 的 `helm template`
  均通过。真实 Kubernetes Job 生命周期、NetworkPolicy 和跨 Pod 调用仍待环境验收。

### SBX-13：状态收敛

改动：

- 新增 `SandboxRunReconciler`，短轮询非终态 Run。
- 使用 run-level lease、heartbeat 和 fencing 支持多 Backend 实例。
- 收敛 Controller 状态、Artifact 状态和 cleanup 状态。
- Controller 不可达时保留可恢复状态并退避，不伪造成功或失败。
- 取消和超时优先于迟到成功结果。

验证：

- 多实例 claim、lease 丢失、迟到响应、Controller 重启、重复终态和清理重试测试。

回滚：

- 停止新建，等待非终态 Run 或显式取消，再 revert Reconciler。

完成记录：

- 新增 `SandboxRunReconciler`：每轮只 claim 一个 Run，持久化 run-level lease/fencing 后执行一次
  Controller 查询；非终态 Run 在本轮结束释放 claim，多个 Backend 实例可安全接力，且不会消耗
  `max_attempts` 的派发重试预算。
- 收敛 `DISPATCHING -> RUNNING -> COLLECTING -> SUCCEEDED`，Controller 结果仅保存摘要；控制器
  日志转换为大小受限、`UNTRUSTED` 的 MinIO Artifact，并只在 Run 结果中引用其 SHA-256。
- Run 到期时先写 `TIMED_OUT` 再尽力取消 Controller Job，保证迟到成功不能覆盖超时；Controller
  不可达时维持可恢复 Run/cleanup 状态，不伪造失败。终态 Run 进入独立 cleanup 状态机，Controller
  确认 Job 消失后才标记 cleanup 成功，仍存在时重复发起幂等取消。
- 自动化验证：覆盖 Controller 不可达、超时优先、成功收集、无内联日志及 cleanup 重试；真实多副本
  Backend、Controller 重启和 Kubernetes Job/TTL 生命周期仍待环境验收。

### SBX-14：诊断证据包

改动：

- 新增 `DiagnosticEvidenceBuilder`。
- 统一打包日志、Events、Pod/Deployment 描述、指标窗口和脱敏资源 YAML。
- 限制时间窗口、容器数、日志行数、对象总量和单字段长度。
- 移除 Secret data、ServiceAccount Token、Authorization 和私钥。
- 保存来源、采集时间、requestId、内容哈希和数据分类。

验证：

- Secret、Token、私钥、超大日志、二进制内容和重复证据测试。
- 证据顺序与哈希稳定。

回滚：

- revert Builder；不影响现有日志查询工具。

完成记录：

- 新增 `DiagnosticEvidenceBuilder`：统一封装日志、Events、资源描述、指标窗口与资源 YAML；限制 24 小时时间
  窗口、100 个对象、日志 500 行/项及 8 KiB 单字段，二进制内容只保留哈希占位。
- 对所有文本应用统一敏感信息脱敏，并额外移除 PEM 私钥；`Secret` YAML 的 `data`/`stringData`/Token 字段不进入
  证据包。输入按类型/来源稳定排序，以 canonical JSON 计算 SHA-256，重排同一证据不会改变包内容或哈希。
- 新增 `DiagnosticEvidenceArtifactService`，只将脱敏 canonical JSON 写入 Controller 固定读取的
  `sandbox/{runId}/inputs/evidence.json`；数据库只保留 Artifact 引用、哈希与 `INTERNAL` 分类，不保存正文。
- 自动化验证覆盖 Secret、Token、私钥、超长日志、二进制、时间/数量限额、稳定哈希与 Artifact 写入。真实 Kubernetes/
  Prometheus 数据采集、MinIO 故障恢复和大对象吞吐仍待环境验收。

### SBX-15：固定诊断工具

改动：

- 新增版本化工具目录，例如 `sandbox-tools/*.yaml`。
- ToolSpec 固定 image digest、entrypoint、Schema、资源和网络。
- 首批提供日志模式分析、Kubernetes 资源一致性检查和配置差异分析。
- 输出统一为 `diagnosis.json`，包含发现、证据引用、置信度和建议，不包含可直接执行命令。

验证：

- 工具目录 Schema、重复 ID、digest、输入输出契约测试。
- 固定样例回归，结果可重放。

回滚：

- 关闭 `fixed-diagnostic` 开关并 revert 工具目录。

完成记录：

- 新增版本化 `sandbox-tools/tools.yaml`，只允许三种 `FIXED_DIAGNOSTIC` 工具：日志模式分析、Kubernetes
  资源一致性检查和配置差异分析；调用方仍只能引用工具 ID 与版本。
- 每个条目固定 64 位 SHA-256 镜像 digest、`/tool` 入口、输入/输出 Schema、资源上限及
  `DNS_AND_ARTIFACT_CHANNEL` 网络策略。Backend 在启动时拒绝重复 ID/版本、缺失 Schema、非固定模式或不合规 digest。
- Controller 内置白名单与 Backend 目录使用相同的 ID、版本、入口和镜像 digest，签名请求不能改选任意镜像。
- 新增 `evidence-v1` 和 `diagnosis-v1` 契约及三组可重放 `diagnosis.json` 样例；统一输出只包含发现、证据引用、置信度
  和建议，显式排除 `command`、`commands`、`shell` 与 `exec` 字段。
- 自动化验证覆盖目录加载、重复 ID、缺失 Schema、精确 digest/入口/资源/网络约束、Schema 字段及三组回放样例。
  镜像构建、漏洞扫描、签名校验和真实 Job 运行仍待环境验收。

### SBX-16：Agent 生成代码

改动：

- 支持 `python3` 和受限 POSIX shell 两种固定 runtime。
- 代码作为 Artifact 保存并记录模型、prompt 版本、SHA-256 和生成原因。
- 运行容器无生产凭据、无 ServiceAccount Token、默认无外网。
- 强制超时、输出路径、最大进程数和结构化结果 Schema。
- 静态检查只作为提前拒绝，不作为主要安全边界。

验证：

- 无限循环、fork bomb 特征、超内存、超输出、路径穿越、网络尝试和异常退出测试。
- 确认代码永不在 Backend/Worker 进程执行。

回滚：

- 单独关闭 `generated-code`，固定工具仍可工作。

完成记录：

- 新增仅服务端可选的 `generated-python:v1` 与 `generated-posix-shell:v1` Runtime；两者均固定 64 位镜像
  digest、入口、资源上限、输入/输出 Schema 和 `DENY_ALL` 网络策略。目录加载器会拒绝任意生成代码 Runtime 放宽网络策略。
- 新增 `GeneratedCodeArtifactService`：代码连同模型、prompt 版本、生成原因、源代码 SHA-256 和运行约束写入
  `generated-code.json`；MySQL 仅保存 Artifact 引用和哈希，分类固定为 `UNTRUSTED`。静态检查会提前拒绝无限循环、
  fork bomb、网络访问和路径穿越特征，但不把静态检查当作安全边界。
- Backend 给 Job 签发单对象、5 分钟有效的输入读取 URL，而非注入 MinIO 或生产凭据；Job 无 ServiceAccount Token、
  只读根文件系统，仅挂载 `/sandbox` 空目录，固定 PID/输出上限、超时和 `/sandbox/output/result.json` 输出路径。
- Python/Shell 固定 wrapper 将结构化结果写入限定路径并输出受限标记；Controller 提取上限 1 MiB 的结果，Backend
  再按 Schema 校验后才保存为 `UNTRUSTED` 输出 Artifact。Sandbox Job NetworkPolicy 仅保留 DNS 和同发布域 MinIO 的
  单对象 Artifact 通道，无通用外网出口。
- Java 定向测试、Controller Go 测试与 `go vet`、Helm lint 均通过。镜像构建/签名/扫描、真实 MinIO Artifact
  通道、Kubernetes 运行时隔离效果和故障演练仍属于环境验收项。

### SBX-17：YAML、Helm、Patch、Runbook 校验

改动：

- 提供 YAML 解析、Kubernetes Schema、kubeconform、Helm template、Conftest/OPA 校验。
- JSON Patch/Strategic Merge Patch 先应用到快照，再输出差异。
- Runbook 校验步骤、工具引用、参数 Schema、危险动作和回滚段落。
- 校验工具版本与规则集版本写入结果。

验证：

- 合法/非法 YAML、未知 CRD、Helm 渲染失败、策略拒绝、危险 Runbook 和差异快照测试。

回滚：

- 关闭 `manifest-validation`；不影响固定诊断和生成代码。

完成记录：

- 新增 `manifest-validation:v1` 固定工具与版本化输入/输出 Schema；固定运行时镜像包含 Helm、kubeconform
  和 Conftest，且无生产凭据、ServiceAccount Token 或生产写路径。
- 新增只读 `ManifestValidationService`：解析内置 Kubernetes Kind、拒绝未知 CRD、校验 Helm 控制块、检查
  hostNetwork/privileged 策略；它只做预检，绝不调用 Helm、kubectl、OPA 或集群。
- JSON Patch 和 Strategic Merge Patch 始终基于显式快照应用并返回 canonical 差异；Runbook 校验 frontmatter、
  Steps/Rollback 段落、工具引用危险动作及 approvalRequired 元数据。
- `ManifestValidationArtifactService` 将输入、规则集版本和预检结果写入固定 Artifact；Controller 返回的结果
  必须通过严格 allowlist Schema 后才会成为 `UNTRUSTED` 输出 Artifact。
- Java 定向测试覆盖合法/非法 YAML、未知 CRD、Helm 模板错误、策略拒绝、Patch 快照、危险/已审批 Runbook
  及 Artifact 契约；Controller Go 测试/`go vet` 和 Helm lint 均通过。真实镜像构建、kubeconform/Conftest
  二进制运行和集群内 NetworkPolicy/Artifact 通道属于环境验收项。

### SBX-18：修复方案仿真

完成记录：

- Controller 新增独立 `/internal/v1/simulations` 生命周期入口和 `internal/simulation.Manager`；它只接受
  明确配置的外部 kubeconfig 与 `validation-`、`staging-` 或 `nonprod-` 集群标识。未配置时返回
  `CONTROLLER_NOT_READY`，绝不会回退到 Controller 所在集群。
- 每个 Run 由 SHA-256 派生短临时 namespace；创建受限 `sandbox-simulation` ServiceAccount、默认拒绝
  NetworkPolicy、ResourceQuota、脱敏输入 ConfigMap 和非生产 Secret 占位。普通 Job 没有选择
  ServiceAccount 的调用方入口，仿真身份只由 Controller 的可信配置注入。
- namespace 已有冲突时拒绝执行；创建阶段错误会清理本 Run 新建的 namespace。取消和清理路径只删除同时
  匹配 run-id 与 simulation 标签的 namespace，失败会显式返回，以供既有 Reconciler 继续重试。
- Backend 注册 `remediation-simulation:v1` 固定 Runtime、版本化输入/输出 Schema、脱敏输入 Artifact
  服务和严格输出校验；仿真结果始终为 `UNTRUSTED`，带 `simulationOnly=true`，不能构成生产执行命令。
- `SandboxControllerClient` 按 Run mode 选择专用入口和 `remediation-simulation.json`，普通 Controller
  管理器不接收仿真请求。Go Fake Client 测试覆盖 namespace 冲突、配额准备失败、启动失败、取消清理失败、
  身份/配额/NetworkPolicy；Java 测试覆盖脱敏拒绝、Artifact、结果契约和专用路由。

延期验收：

- 真实独立 Kind/K3s 集群、镜像构建、Artifact 网络通道和实际工作负载 readiness/startup/recovery 信号
  属于真实环境验收；它们不阻塞本轮代码收口，但不得据此宣称完成生产仿真验收。

回滚：

- 关闭 `remediation-simulation`，等待 Controller 清理已归属的临时 namespace 后 revert 本提交。

### SBX-19：Agent 路由

完成记录：

- 新增 `SandboxRoutingPolicy`：默认关闭；只有已开启自动路由、Planner 明确标记证据不足、请求显式要求
  隔离诊断且当前任务不是只读查询时，才选择固定诊断 Sandbox。日志与指标查询继续走既有只读工具。
- 生成代码、清单校验和修复仿真必须通过独立 API 提交它们各自的受控输入 Artifact；自动路由拒绝这些
  尚无 Agent 输入构造器的模式，避免生成空 Run 或把 Agent 文本直接作为代码/资源配置执行。
- `ASK_EXECUTION` 将已持久化的真实发起人最小投影写入 `GraphState.workflowActor`。`ExecutorThinkNode`
  创建关联 execution 的固定诊断 Run，并在同一事务写入脱敏 `evidence.json` 后返回 `WAITING`；它不阻塞
  Worker 等待控制器执行结果，也不会落入 Kubernetes/设备/数据库执行器。
- 路由 Idempotency Key 由 execution 与 task 派生；既有 Sandbox 每告警并发、全局并发、异步任务和
  ReAct `max-loops` 限制继续生效。SBX-20 将消费 Run 终态并恢复该暂停 Workflow。

验证：

- 定向测试覆盖默认关闭、只读查询不路由、证据不足与显式请求的固定诊断路由、身份传播、Run 创建后
  Artifact 写入、未支持模式拒绝、Executor `WAITING` 及现有 Ask/Executor 行为。

回滚：

- 关闭 `agent-auto-route-enabled`；独立 Sandbox API 与既有执行器保持可用。

### SBX-20：Workflow 恢复与生产动作硬边界

改动：

- Sandbox Run 终态通过 Outbox 恢复关联 Workflow。
- Verifier 将结果视为不可信证据，重新查询当前生产状态。
- Sandbox 输出转换为结构化 Remediation Proposal，不直接执行命令。
- 生产 restart/scale/patch 仍走原有 ToolExecutor、审批和审计。
- 加入执行前置条件、成功信号、停止条件和回滚建议。

验证：

- Sandbox 成功/失败/超时/取消后的 Workflow 恢复测试。
- 提示注入、伪造工具结果、越权目标和输出命令直通均被拒绝。
- 中高风险生产动作仍要求审批。

回滚：

- 关闭 Agent 自动路由；恢复现有 Planner→Executor→Verifier 行为。

完成记录：

- Sandbox Run 首次进入终态时由 Reconciler 写入 `sandbox.run.terminal` Outbox 事件；事件处理器以
  `(task_type, dedupe_key)` 唯一键创建恢复任务，重复投递按幂等成功处理。
- 新增 `WAITING_SANDBOX` 持久化状态，Sandbox 等待不再伪装为人工审批；终态恢复时以 Worker
  lease/fencing 将关联 Execution 切回 `RUNNING`，随后统一经既有工作流终态协调器写回 Execution、节点、审计和 Outbox。
- 结果只作为 `UNTRUSTED` 证据和无可执行命令的 Remediation Proposal 落入 GraphState。恢复阶段只允许固定、
  声明为只读的 `kubernetes.describeWorkload` 复核当前工作负载；原始响应不写回 GraphState。
- 通过复核后仅重建受控执行计划并运行 Verifier。任何 Sandbox 派生方案均强制进入人工审批；恢复处理器不调用
  `ExecutorAgent.executePrepared`，Sandbox 失败、超时或取消均保持生产执行阻断。
- 自动化验证覆盖终态 Outbox 去重/参数校验、恢复证据边界、只读复核约束、恢复后 Verifier/审批门、路由防重入及
  Reconciler 终态事件投递。真实 Sandbox Job、生产集群复核和审批后的受控动作仍待环境验收。

### SBX-21：Console

改动：

- 新增 Sandbox Run 列表和详情页。
- 展示模式、状态、进度、关联告警/Execution、工具版本、资源用量和清理状态。
- 提供取消、受限日志和 Artifact 下载。
- 权限控制与 SSE/轮询降级复用现有框架。
- 不提供任意脚本编辑器、Terminal 和任意镜像输入。

验证：

- TypeScript、Vitest、权限矩阵和 Mock API E2E。
- 页面不持久化预签名 URL，不展示被脱敏字段。

回滚：

- 移除路由和菜单；Backend API 不受影响。

### SBX-22：可观测性

改动：

- 增加 Run 创建、排队、运行、成功、失败、超时、取消和清理指标。
- 增加按 mode/tool/error 的时延和结果分布。
- 增加 Controller 调用、Job 调度、ImagePull、OOM、PolicyDenied 和 Artifact 指标。
- Grafana 增加 Sandbox 运营 Dashboard。
- Prometheus 增加积压、失败率、清理泄漏、Controller 不可用和超时告警。

验证：

- Micrometer 指标测试、规则语法测试和 Dashboard JSON 契约测试。
- 指标标签禁止 runId、alarmId 等高基数字段。

回滚：

- 可独立 revert Dashboard/规则；不影响运行事实。

### SBX-23：CI、安全与运维收口

改动：

- CI 增加 Controller test/vet/build、镜像扫描和 SBOM。
- 增加 JobSpec 安全契约、Controller API fuzz、Artifact 边界和提示注入回归。
- 补充 Helm values、安装、升级、关闭、回滚、泄漏清理和故障处理 Runbook。
- 增加独立开关的 `00/10/01/11` 组合测试。
- 输出代码验收清单与真实集群验收清单，二者分开标记。

验证：

- Backend、Controller、Frontend、OpenAPI、Helm、Compose、扫描和 SBOM 全部通过。
- 默认关闭 Sandbox 时原部署行为不变。
- 开关降级不丢 Run 事实，不绕过生产审批。

回滚：

- 按 `SBX-23` → `SBX-01` 逆序回滚，Migration 保留。

## 10. 分阶段退出条件

| 阶段 | Commit | 代码退出条件 | 真实环境退出条件 |
| --- | --- | --- | --- |
| A：控制面事实 | SBX-00～06 | Run/API/权限/Artifact 单测与 MySQL IT 通过 | 无 |
| B：隔离运行时 | SBX-07～13 | Controller、JobSpec、派发和收敛测试通过 | 测试集群 Job 生命周期与 NetworkPolicy |
| C：诊断能力 | SBX-14～17 | 四类输入输出契约和恶意样例通过 | 固定工具与生成代码真实 Job |
| D：仿真与 Agent | SBX-18～20 | 仿真 adapter、挂起恢复、Verifier 门禁通过 | 独立仿真集群与一次受控生产审批链路 |
| E：交付 | SBX-21～23 | Console、指标、CI、安全和文档通过 | Dashboard、告警、清理和容量演练 |

某阶段的代码完成不能代替真实环境验收；真实环境暂未执行时应标记为
`VALIDATING` 或“代码完成、环境待验收”，不得标记为生产完整交付。

## 11. 最终业务验收

### 11.1 固定诊断工具

- [ ] 告警触发后能生成脱敏证据包。
- [ ] 固定工具在一次性 Job 内执行。
- [ ] Backend/Worker 不加载工具依赖、不执行工具进程。
- [ ] 输出可以定位到原始证据。
- [ ] 超时、OOM、失败和取消均有明确状态。

### 11.2 Agent 生成 Python/Shell

- [ ] Agent 代码只进入 Artifact，不进入 Backend command line。
- [ ] Job 无生产凭据、无 ServiceAccount Token。
- [ ] 网络、CPU、内存、磁盘、PID、时间和输出均有限制。
- [ ] 恶意或失控代码不会影响 Backend/Worker。
- [ ] 结果不能直接触发生产命令。

### 11.3 YAML、Helm、Patch、Runbook

- [ ] 返回语法、Schema、策略和差异四类结果。
- [ ] 记录校验工具与规则版本。
- [ ] 未知 CRD 和缺少依赖时明确降级，不伪造通过。
- [ ] 危险 Runbook 必须指出审批和回滚缺口。

### 11.4 修复仿真

- [ ] 使用独立非生产集群和临时 namespace。
- [ ] 不复制生产 Secret。
- [ ] 能验证资源应用、启动、健康和预期恢复信号。
- [ ] 成功失败后均能清理。
- [ ] 仿真通过不会自动绕过生产审批。

### 11.5 全链路

- [ ] `alarmId → executionId → sandboxRunId → taskId → auditId → requestId` 可关联。
- [ ] 同一告警不会无限创建 Sandbox Run。
- [ ] Controller 故障不拖垮主告警链路。
- [ ] Sandbox 关闭后回退到现有只读诊断和人工建议。
- [ ] 生产动作始终由受控 ToolExecutor 执行。

## 12. 风险与应对

| 风险 | 应对 |
| --- | --- |
| 把容器隔离误认为生产权限隔离 | Sandbox 永不持有生产写凭据，生产动作走独立受控执行器 |
| Agent 输出被日志提示注入 | 输入输出标记不可信，结构化 Schema + Verifier 二次校验 |
| 长任务占用现有 Worker | 派发任务短执行，Run Reconciler 独立收敛 |
| Controller 重试创建重复 Job | Run ID 幂等、Kubernetes label、CAS 和 fencing |
| Job 或 namespace 泄漏 | TTL + Janitor + cleanup 独立状态 + 告警 |
| MinIO URL 或原始日志泄漏 | 单对象短授权、日志脱敏、大小限制、默认不返回 URL |
| 工具镜像供应链风险 | digest 固定、allowlist、镜像扫描和 SBOM |
| 仿真环境与生产差异 | 明确结果边界，生产执行前重新查询真实状态 |
| Sandbox 能力拖累主链路 | 全局及分模式开关、断路器、超时和降级 |

## 13. 当前停止点

SBX-00～20 已完成（含此前的审核修复）。Backend 已具备 Run/Artifact/API/权限、短时派发任务、
HMAC Controller Client、断路器、短轮询状态收敛和脱敏诊断证据包；Controller 已具备基座、hardened JobSpec、
生命周期、结果收集清理与隔离部署。首批固定诊断工具目录、受限 Python/Shell Runtime、生成代码 Artifact 与结构化
结果契约、YAML/Helm/Patch/Runbook 校验及固定验证 Runtime 已落地。独立仿真入口、临时 namespace、受限身份、
配额/默认拒绝网络、脱敏 Artifact 与清理边界也已落地。固定诊断的保守 Agent 自动路由、真实用户归属、
Artifact 先行写入和异步挂起已落地。Sandbox 终态现可通过 Outbox 恢复到固定只读复核、Verifier 与人工审批，
且不会直通生产执行器。下一单元为 SBX-21：Console。

每次只提交一个 SBX 单元，代码验证通过并产生本地 commit 后再进入下一个单元；远端推送仍需用户单独授权。
