# KubeOnCall 多渠道通知底座实施计划

> 版本：v0.2
> 日期：2026-07-30
> 当前状态：M0～M2 代码已完成；M3 单向群机器人适配器、M4 集成页和飞书 robotId 接入验证接口已完成代码实现；真实渠道、生产和互动回调未验收
> 范围：飞书、钉钉等协作渠道共用的通知事件、逻辑路由、能力声明、Provider SPI、分发结果和可靠投递演进路径
> 非本期范围：仓库内保存真实凭证、生产群直接投递、卡片回调、电话渠道实现

## 1. 背景

实施前的告警工作流已经具备通用 HTTP 通知出口，但它还不是多渠道通知系统：

- `NotificationNode` 直接构造通用参数并调用 HTTP Webhook，没有飞书、钉钉专用报文、签名和错误码适配。
- 告警策略已经包含 `notify: oncall`、`notify: oncall-phone`，普通通知链路尚未把它解析为实际目的地。
- 告警触发、未确认升级、确认、恢复、静默和审批事件分散在不同执行路径中，恢复通知没有经过 `NotificationNode`。
- 现有“通知记录”来自工作流节点执行记录，缺少 provider、destination、eventType、外部消息 ID 和 send/update 等渠道投递字段。
- 现有 Mock E2E 只证明通用 HTTP 调用成功，不证明飞书、钉钉或真实人员已经收到消息。

如果直接为每个平台继续添加条件分支，路由、重试、审计和生命周期状态会重复实现。需要先建立与平台协议无关的公共底座，再添加飞书、钉钉 Provider。

## 2. 目标与边界

### 2.1 本计划目标

1. 建立统一、不可变的通知消息模型。
2. 将 `oncall` 等业务渠道定义为逻辑路由，而不是某个平台名称。
3. 用 Provider SPI 隔离平台凭证、报文、签名、用户标识和错误码。
4. 支持一个逻辑路由 fan-out 到多个目的地，并隔离单个目的地失败。
5. 显式声明群 Webhook、私聊、@人、卡片更新、互动回调和撤回等能力。
6. 为后续 Outbox、投递表、前端投递记录和真实渠道验收提供稳定契约。

### 2.2 仍不在本轮实现

- 不把飞书或钉钉 Webhook 地址写入仓库。
- 不增加公网回调入口。
- 不让飞书或钉钉成为告警、审批或 Incident 的事实源。
- 不把 `oncall-phone` 降级为聊天软件通知；电话或 Pager 仍需独立 Provider。

## 3. 设计原则

- **业务事件与平台协议分离**：核心层只表达“通知什么、路由到哪里、需要什么能力”。
- **逻辑路由与物理目的地分离**：`oncall` 可以同时映射飞书群、钉钉群和其他值班渠道。
- **凭证不进入领域对象**：目的地只保存稳定别名，Webhook、Secret、App 凭证由 Provider 配置解析。
- **失败隔离**：一个目的地失败不阻断同一路由的其他目的地。
- **能力显式化**：路由需要互动回调时，不能落到只支持单向群 Webhook 的 Provider。
- **确定性幂等键**：每个事件和目的地生成稳定 delivery ID，为后续可靠重试提供依据。
- **明确至少一次边界**：内部投递状态可幂等，但群机器人没有服务端幂等契约，平台受理后、状态落库前崩溃仍可能产生重复消息。
- **事实源唯一**：飞书、钉钉卡片只展示和触发受控命令，最终状态仍以 KubeOnCall 持久化状态为准。
- **数据最小化**：平台卡片使用允许字段，不直接发送完整诊断结果、日志、指标标签或密钥。

## 4. 目标架构

```text
Alarm / Approval / Execution lifecycle
                  │
                  v
        NotificationMessage
                  │
                  v
      NotificationRouteResolver
                  │
          one route -> N targets
                  │
                  v
       NotificationDispatcher
          │               │
          v               v
  FeishuProvider    DingTalkProvider
          │               │
          └───────┬───────┘
                  v
       NotificationDelivery
       + Outbox retry/dead letter
       + metrics/audit/frontend
```

## 5. 模块布局

```text
backend/src/main/java/com/kubeoncall/notification/
├── domain/
│   ├── NotificationAction
│   ├── NotificationCapability
│   ├── NotificationDestination
│   ├── NotificationMessage
│   ├── NotificationPriority
│   └── NotificationRoute
├── application/
│   ├── NotificationConfiguration
│   ├── NotificationDeliveryIds
│   ├── NotificationPublisher
│   ├── NotificationSubmissionService
│   ├── NotificationReplayService
│   ├── FeishuRobotConnectionService
│   ├── NotificationDeliveryRequest
│   ├── NotificationDeliveryResult
│   ├── NotificationDispatchResult
│   ├── NotificationDispatcher
│   ├── NotificationProviderRegistry
│   └── StaticNotificationRouteResolver
├── config/
│   ├── NotificationProperties
│   ├── NotificationDurabilityConfiguration
│   └── NotificationProviderConfiguration
├── delivery/
│   ├── NotificationDeliveryRecord
│   ├── NotificationDeliveryRepository
│   ├── NotificationDeliveryReconciler
│   └── NotificationDeliveryOutboxHandler
├── provider/
│   ├── feishu/
│   │   └── FeishuRobotRegistry
│   ├── dingtalk/
│   └── webhook/
└── spi/
    ├── NotificationProvider
    ├── NotificationProviderException
    └── NotificationRouteResolver
```

## 6. 核心契约

### 6.1 通知消息

`NotificationMessage` 包含：

- 全局事件 ID 和事件类型。
- `oncall` 等逻辑路由键。
- 通知优先级。
- 标题、摘要和允许展示的事实字段。
- 跳转控制台等语义动作。
- 事件发生时间。

它不包含：

- 飞书 `open_id/chat_id` 或钉钉会话 ID。
- Webhook、Secret、App Token。
- 任一平台的原始卡片 JSON。

### 6.2 Provider SPI

每个 Provider 必须声明：

- 唯一 `providerKey`。
- 支持的能力集合。
- 接收统一 `NotificationDeliveryRequest` 的发送方法。
- 成功时返回外部消息 ID 和平台响应码。
- 失败时返回稳定错误码及是否适合重试。

### 6.3 路由与分发

- 路由键映射一个或多个 `NotificationDestination`。
- 每个目的地声明 provider、稳定目标别名和所需能力。
- Provider 未注册或能力不足属于非重试配置错误。
- Provider 明确报告的临时错误保留 `retryable=true`。
- Dispatcher 聚合为 `DELIVERED | PARTIAL_FAILURE | FAILED | NO_ROUTE`。

## 7. 里程碑

### M0：公共代码底座

- [x] 通知领域模型与防御性不可变集合。
- [x] Provider SPI、异常和能力声明。
- [x] Provider 注册表与重复键启动失败。
- [x] 静态逻辑路由解析器。
- [x] 多目的地分发、能力校验和失败隔离。
- [x] 空 Provider/空路由的 Spring 默认装配。
- [x] 聚焦单元测试和格式检查。

完成标准：代码可以独立编译和实例化；测试覆盖正常 fan-out、局部失败、Provider 缺失、能力不匹配、重复注册和无路由；现有生产通知行为不变。

### M1：可靠投递

- [x] 新增渠道投递表，记录 provider、destination、eventType、externalMessageId、状态、尝试次数和错误。
- [x] 一个目的地对应一个确定性 Outbox 投递单元，避免局部成功后整批重试造成重复消息。
- [x] 接入既有 Outbox 退避/死信、带权限的人工重放、投递指标和重放审计。
- [x] 代码区分 HTTP 成功、平台业务成功和无效/未知响应。
- [ ] 真实接收方验收。

### M2：告警生命周期接管

- [x] 首次触发在诊断前产生快速通知事件。
- [x] 诊断完成后产生补充通知；群 Webhook 为追加发送，原卡更新需应用机器人。
- [x] 未确认升级、确认、恢复、静默和审批生命周期走统一通知入口。
- [x] `AlarmAction.notificationChannel` 解析为逻辑路由。
- [x] P0/P1 可先通知再补充诊断，避免等待完整 AI/证据链。

### M3：飞书与钉钉 Provider

- [x] 飞书群自定义机器人单向通知适配器。
- [x] 钉钉群自定义机器人单向通知适配器。
- [x] 平台签名、HTTP/业务错误码、限流分类、响应脱敏和官方主机白名单。
- [ ] 真实测试群发送、重复事件、限流和凭证失效验收。
- [ ] 应用机器人、私聊、@人和卡片更新作为独立能力开启。

### M4：Console 与运营闭环

- [x] 集成页展示平台、目标数量、能力、配置状态和最近投递。
- [x] 通知记录展示 send/update、外部消息 ID、重试、重放次数和错误。
- [x] 飞书机器人上层注册表以本地 `robotId` 解析服务端托管目标，调用侧不接触凭证。
- [x] 管理员可仅提供 `robotId` 发起带权限、操作审计和可选幂等键的接入验证通知。
- [ ] 告警、审批、执行详情展示渠道投递状态。

### M5：互动回调

- [ ] 平台回调验签、解密、防重放和用户身份绑定。
- [ ] 卡片动作映射内部用户、RBAC、资源版本和幂等键。
- [ ] 优先开放“打开控制台”和低风险确认；审批、静默、处置动作单独验收。
- [ ] 回调快速响应，业务命令异步执行，卡片随后更新。

## 8. 验收分层

必须分别记录：

1. **代码完成**：公共模型、SPI、路由和分发器存在并通过编译。
2. **自动化验证**：聚焦单元测试和项目质量门禁通过。
3. **集成验证**：平台形状的 Mock 验证签名、报文和错误码。
4. **真实渠道验证**：真实租户、真实群和真实接收者确认送达。
5. **生产验收**：路由、限流、重试、死信、凭证轮换、回调安全和运营告警完成演练。

代码完成不代表真实飞书或钉钉租户已经接入，也不代表真实人员已经收到告警。

## 9. M0 验证记录

2026-07-30 本地完成：

- Java 17 主源码 616 个文件和测试源码 261 个文件编译成功。
- 通知领域、注册表、路由、分发和 Spring 空装配测试 15 个全部通过。
- 架构规则测试 2 个全部通过，`notification` 核心包纳入禁止持有 Web 层状态的约束。
- 新增通知范围 Spotless 检查通过。
- 未执行完整项目测试、真实依赖集成测试或真实渠道验收；M0 没有接管现有通知链路。

## 10. M1～M4 代码验证记录

2026-07-30 本地完成：

- 新增 `V19__notification_delivery.sql`，以确定性 `delivery_key` 保证一个事件、一个实际目的地只有一条投递记录。
- 同一事务写投递记录和 Outbox；Worker 按目的地调用 Provider，永久错误终止，临时错误沿用现有指数退避和死信。
- `integration:manage` 人工重放会校验终态、写操作审计、累计重放次数并创建新 Outbox 事件。
- 飞书 Mock 验证 `interactive` 卡片、签名向量、业务码与限流；钉钉 Mock 验证 `markdown`、URL 签名向量、业务码与限流。
- 告警首次触发、诊断完成、升级、确认、恢复、静默，以及审批请求/决定已映射为统一通知事件；新能力未启用或无路由时保留既有告警通知路径。
- 通知及相关聚焦回归测试 69 项通过；后端完整单元测试 877 项通过（含重放接口契约测试），Spotless、Checkstyle 和跳过测试的打包构建通过。
- Testcontainers 使用真实 MySQL 8 完整执行 V1～V19 共 19 个 Flyway 迁移；通知集成测试覆盖首次写入、重复幂等、失败、重放和 Outbox 死信对账。
- 前端完整测试 73 项通过（含集成页投递展示测试）；TypeScript、ESLint 和生产构建通过。
- OpenAPI 已同步重放接口、平台目标能力及逐目的地投递字段，生成的前端类型与快照一致。
- 尚未执行真实飞书/钉钉测试群投递、限流/凭证失效演练或生产验收。

## 11. 飞书 robotId 上层封装验证记录

2026-07-30 本地完成：

- 新增本地 `robotId` 注册表、接入验证 Service 和无请求体 REST 接口；Webhook、签名密钥不进入路径、请求体、响应或通知领域模型。
- 同一机器人与同一可选 `Idempotency-Key` 生成稳定事件 ID，逐目的地投递和 Outbox 仍使用原有唯一键防重。
- 11 项聚焦测试通过，覆盖 ID 校验、凭证无关目的地、直达投递、事件幂等、权限接口和条件装配。
- 完整后端单元测试共 889 项通过，0 failure、0 error、0 skipped；全局 Spotless、Checkstyle、跳过测试的打包构建及 OpenAPI 生成 Profile 均通过。
- OpenAPI 契约和前端生成类型已同步，前端 TypeScript 检查及生成文件 Prettier 检查通过。
- 尚未配置真实飞书机器人凭证，也未执行测试群真实接收确认；当前结论只到代码和自动化验证。
