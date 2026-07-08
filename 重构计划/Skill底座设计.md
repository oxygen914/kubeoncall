# KubeOnCall Skill 兼容底座设计

> 目标：在现有 agent 架构（planner→verifier→executor 三段式 + 规则推断工具）上，**最小侵入**地增加一层兼容 Skill 的底座，让运维经验能以「可加载的指令包」形式沉淀复用。
> 参照：`paicli/src/main/java/com/paicli/skill/`（同 Java 生态成熟实现）、`as-cil` skill 机制。
> 前置分析见同目录「kubeoncall agent 架构是否具备运行 skill 基础设施」结论。

---

## 1. 设计目标与约束

### 1.1 约束（不可违背）

kubeoncall 现状决定了底座必须绕开几个硬约束：

1. **LLM 无原生 tool-calling**：`PlannerLlmService` 把 ChatClient 当文本补全 API 用（`system + user → .call().content()` → 正则抠 JSON）。**底座不能依赖 Spring AI function-calling**，否则要重写整个 LLM 调用层。
2. **工具选择是规则推断**：`TaskType → executorKind.action` 硬映射。底座不能假设"模型能自主选工具"。
3. **三段式 pipeline 固定**：planner/verifier/executor 各自有向图，底座要嵌入节点，不能另起一套 agent 循环。
4. **MCP 是空壳**：`McpToolRegistry.listPlannerTools()` 硬编码 6 个声明，无动态发现。底座不应依赖 MCP 动态化。

### 1.2 目标

在以上约束下，让 kubeoncall 能：

1. **加载 Skill**：从 `resources/skills/<name>/SKILL.md` 读取可复用运维经验包（frontmatter 元数据 + 正文步骤）。
2. **按需激活**：根据用户/告警意图匹配 skill，把 skill 正文注入 LLM context。
3. **约束工具集**：skill 可声明工具白名单，激活后过滤 `AgentToolCatalog` 暴露的工具。
4. **承载经验**：skill 正文可包含"判断逻辑/参数推荐/已知坑"，作为 planner 推断的补充知识源。
5. **渐进演进**：底座先做"指令注入 + 工具过滤"，不强行打通 LLM tool-calling；后续若启用 tool-calling，底座可无缝升级。

### 1.3 核心设计原则：Skill 是「指令包」而非「工具」

关键取舍：**底座第一版把 Skill 定位为「注入 LLM 的结构化指令 + 工具白名单」，而非「LLM 可自主调用的函数」**。

原因：约束 1 决定了模型现在发不出 tool_call，强行做"skill 即工具"要么得重写 LLM 层，要么回到规则推断（那 skill 就退化成另一种 `ToolDefinition`，没意义）。

所以第一版 Skill 的价值是：**把"碰到 payment-service OOM 该先查什么、推荐什么参数、已知什么坑"这类经验，从散落在 planner 规则代码里，沉淀成可加载、可版本化、可被 LLM 读到的指令包**。这恰好是 paicli/as-cil 的 skill 模型，也最匹配 kubeoncall 当前的规则推断架构——skill 内容喂给 planner，planner 的规则推断结果会更准。

---

## 2. Skill 模型定义

### 2.1 Skill 载体

参照 paicli `Skill`，kubeoncall 版增加运维特有字段（工具白名单、适用 TaskType、风险等级）：

```java
package com.kubeoncall.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个 Skill 是 KubeOnCall 沉淀运维决策与经验的复用单元。
 *
 * SKILL.md frontmatter 决定索引段元数据 + 工具白名单 + 适用场景；
 * body 是经验正文，激活时注入 planner/executor 的 LLM context。
 */
public record Skill(
        String name,                    // 唯一标识，kebab-case，如 payment-oom-triage
        String description,             // 一句话，决定相关性匹配 + 索引段展示
        String version,
        List<String> tags,              // 触发标签，如 [oom, payment, memory]
        List<TaskType> applicableTasks, // 适用 TaskType，空表示全适用
        List<String> toolWhitelist,     // 工具白名单（ToolDefinition.name），空表示不限制
        RiskLevel maxRisk,              // skill 允许的最高风险动作，空表示不限制
        Source source,                  // BUILTIN / PROJECT
        String body,                    // 经验正文 markdown
        Path skillMdPath
) {
    public enum Source { BUILTIN, PROJECT }

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (description == null) description = "";
        if (tags == null) tags = List.of(); else tags = List.copyOf(tags);
        if (applicableTasks == null) applicableTasks = List.of();
        if (toolWhitelist == null) toolWhitelist = List.of();
    }

    /** 是否限定工具集（非空白名单时激活后过滤 catalog） */
    public boolean restrictsTools() {
        return toolWhitelist != null && !toolWhitelist.isEmpty();
    }
}
```

### 2.2 SKILL.md 格式

```markdown
---
name: payment-oom-triage
description: payment-service Pod OOM 的标准排查与扩容路径，含已知坑
version: 1.0.0
tags: [oom, payment, memory, restart]
applicableTasks: [QUERY_LOGS, QUERY_METRICS, RESTART_SERVICE, SCALE_WORKLOAD]
toolWhitelist:
  - kubernetes.describeResource
  - prometheus.queryRange
  - kubernetes.scale
  - knowledge.searchSop
maxRisk: HIGH
---

# payment-service OOM 排查

## 判断逻辑
1. 先 prometheus.queryRange 查 container_memory_working_set_bytes，确认是否接近 limit。
2. 若 OOMKilled，查上次重启时间，判断是否反复发生（关联告警记忆）。

## 参数推荐
- 扩容目标：payment-service 当前 replicas=2，OOM 频繁建议 replicas=3，memory limit 4Gi→6Gi。

## 已知坑
- payment-service 重启后健康检查需 30s，期间会误报 NotReady，不要立刻再 restart。
- 该服务 db 连接池上限 50，scale up 后注意连接数。
```

### 2.3 与现有概念的关系

| 现有概念 | Skill 关系 |
|---------|-----------|
| `ToolDefinition` | Skill 的 `toolWhitelist` 引用 `ToolDefinition.name`，激活后过滤 catalog |
| `TaskType` | Skill 的 `applicableTasks` 限定适用场景 |
| `RiskLevel` | Skill 的 `maxRisk` 与 verifier 审批联动 |
| RAG 知识库 | Skill 正文是**结构化经验指令**（怎么判断/怎么干），RAG 是**非结构化文档**（SOP 全文）。二者互补：skill 决定"用什么思路"，RAG 提供"详细步骤" |
| 记忆（见记忆重构方案） | Skill 是**人工沉淀的通用经验**，记忆是**系统自动沉淀的特定设备历史**。Skill 跨设备复用，记忆按设备/服务作用域 |

---

## 3. 底座架构

### 3.1 模块拆分

新增 `skill` 包，与 `agent`/`tool`/`rag` 平级：

```
com.kubeoncall.skill
  ├── Skill.java                      # 载体（见 2.1）
  ├── SkillSource.java                # BUILTIN / PROJECT
  ├── SkillFrontmatterParser.java     # SKILL.md frontmatter 解析（参照 paicli，极简 YAML 子集）
  ├── SkillRegistry.java              # 三层目录扫描 + 注册 + 启用过滤
  ├── SkillStateStore.java            # disabled 列表持久化（参照 paicli，启用为隐式默认）
  ├── SkillMatcher.java               # 根据意图匹配相关 skill（关键词/TaskType/标签）
  ├── SkillIndexFormatter.java        # 渲染 skill 索引段注入 system prompt
  ├── SkillContextBuffer.java         # 单次执行内已激活 skill 正文缓冲（参照 paicli，drain 一次性消费 + LRU≤3）
  └── SkillActivationService.java     # 门面：匹配 + 激活 + 注入 + 工具过滤
```

### 3.2 整体数据流

```
用户 question / 告警 event
   ↓
SkillActivationService.matchAndActivate(intent, taskType, target)
   ├─ SkillRegistry.enabledSkills()        ← 启用 skill 清单
   ├─ SkillMatcher.match(intent, taskType, tags, target)  ← 选 ≤3 个相关 skill
   ├─ SkillContextBuffer.push(skill.body)  ← 正文进缓冲
   └─ 返回 ActivationResult(matchedSkills, toolWhitelist)
   ↓
注入点 1：planner system prompt
   ├─ SkillIndexFormatter.format(enabledSkills)   ← 索引段（常驻，展示有哪些 skill）
   └─ 已激活 skill 正文（从 buffer drain，前置到 user prompt）
   ↓
注入点 2：AgentToolCatalog 过滤
   └─ 若激活 skill 有 toolWhitelist，catalog 暴露的工具集被收窄
   ↓
planner → verifier → executor（现有 pipeline 不变）
   ├─ planner 用 skill 正文里的"判断逻辑/参数推荐"改进规则推断
   └─ verifier 用 skill.maxRisk 联动审批
```

### 3.3 关键：不依赖 LLM tool-calling 的激活机制

paicli/as-cil 的 skill 激活是「LLM 调 `load_skill(name)` 工具」——这依赖 tool-calling。kubeoncall 没有这个通道，所以激活方式改为**两类**：

**模式 A：自动匹配激活（默认，无需 LLM 参与）**
- `SkillMatcher` 在 planner 前置运行，根据 `intent`/`taskType`/`target`/`tags` 关键词匹配，自动激活相关 skill。
- 匹配规则：`tags` 命中用户输入关键词 + `applicableTasks` 含推断出的 TaskType + `description` 相似度。取 top ≤3。
- 这是第一版主路径，**完全不需要 LLM tool-calling**，契合现有规则推断架构。

**模式 B：LLM 指名激活（可选，第二阶段）**
- 在 planner 的 LLM JSON 决策里增加一个 `requestedSkills: [...]` 字段。
- `PlannerLlmDecision` 扩展该字段，planner think 后按模型指名激活。
- 这是「伪 tool-calling」——靠 LLM 在 JSON 里输出 skill 名，而非真 function call。成本低，且为未来真 tool-calling 铺路。

第一版只做模式 A，模式 B 作为演进项。

---

## 4. 关键组件设计

### 4.1 SkillRegistry：三层扫描注册

参照 paicli `SkillRegistry`，简化为两层（kubeoncall 是后端服务，无 user 目录概念）：

```java
@Component
public class SkillRegistry {
    private final Path builtinDir;      // classpath:skills/ 解压到本地缓存
    private final Path projectDir;      // 外部配置目录 ${kubeoncall.skill.project-dir}
    private final SkillStateStore stateStore;
    private final Map<String, Skill> skillsByName = new LinkedHashMap<>();

    @PostConstruct
    public void init() { reload(); }

    public synchronized void reload() {
        skillsByName.clear();
        loadDirectory(builtinDir, Skill.Source.BUILTIN);    // 先 builtin
        loadDirectory(projectDir, Skill.Source.PROJECT);    // 后 project，同名覆盖
    }

    public List<Skill> enabledSkills() {
        Set<String> disabled = stateStore.disabled();
        return skillsByName.values().stream()
                .filter(s -> !disabled.contains(s.name()))
                .toList();
    }
    // loadDirectory: 扫描 <dir>/<name>/SKILL.md → SkillFrontmatterParser.parse → 注册
}
```

**覆盖语义**：project 同名 skill 整体覆盖 builtin（参照 paicli 三层覆盖）。

### 4.2 SkillFrontmatterParser：极简 YAML 子集

直接参照 paicli `SkillFrontmatterParser`（192 行，不引入 SnakeYAML，支持单行 `key: value` / 多行 `key: |` / 行内数组 `[a, b]`）。kubeoncall 已有 Jackson，但 frontmatter 用极简自实现更可控、无新依赖。解析失败的 skill 在 registry 层丢弃 + warning，不阻塞启动。

### 4.3 SkillMatcher：相关性匹配

```java
@Component
public class SkillMatcher {
    private static final int MAX_ACTIVATIONS = 3;

    /**
     * 根据当前意图匹配相关 skill。
     * 评分：tag 命中 + applicableTasks 命中 + description 关键词命中。
     * 宁缺毋滥：score=0 不激活。
     */
    public List<Skill> match(String intent, TaskType taskType, String target,
                             String userInput, List<Skill> candidates) {
        record Scored(Skill skill, double score) {}
        return candidates.stream()
                .map(s -> new Scored(s, score(s, intent, taskType, target, userInput)))
                .filter(s -> s.score() > 0)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(MAX_ACTIVATIONS)
                .map(Scored::skill)
                .toList();
    }

    private double score(Skill s, String intent, TaskType taskType, String target, String userInput) {
        double score = 0;
        // tag 命中
        Set<String> inputTokens = tokenize(userInput);
        score += s.tags().stream().filter(t -> inputTokens.contains(t.toLowerCase())).count() * 2.0;
        // applicableTasks 命中
        if (s.applicableTasks().isEmpty() || s.applicableTasks().contains(taskType)) score += 1.0;
        // description 关键词命中
        if (target != null && s.description().toLowerCase().contains(target.toLowerCase())) score += 1.5;
        return score;
    }
}
```

### 4.4 SkillIndexFormatter + SkillContextBuffer：注入

**索引段（常驻 system prompt）**——参照 paicli `SkillIndexFormatter`，预算约束（单条 desc ≤500、≤20 skill、总段 ≤4096）：

```java
public final class SkillIndexFormatter {
    public static String format(List<Skill> enabled) {
        if (enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## 可用 Skills\n\n");
        for (Skill s : limit(enabled, 20)) {
            sb.append("- **").append(s.name()).append("**：")
              .append(truncate(s.description(), 500)).append('\n');
        }
        sb.append("\n（匹配场景的 skill 会自动加载其完整指引到下方上下文）\n");
        return capBytes(sb, 4096);
    }
}
```

**已激活正文缓冲**——参照 paicli `SkillContextBuffer`（drain 一次性消费 + LRU≤3 + 同名替换）：

```java
public final class SkillContextBuffer {
    private static final int MAX = 3;
    private final Map<String, String> entries = new LinkedHashMap<>();

    public synchronized void push(String name, String body) {
        entries.remove(name);
        entries.put(name, body);
        while (entries.size() > MAX) entries.remove(entries.keySet().iterator().next());
    }

    public synchronized String drain() { /* 拼成 "## 已加载 Skill: <name>\n<body>" 段，清空 entries */ }
}
```

### 4.5 SkillActivationService：门面

```java
@Service
public class SkillActivationService {
    private final SkillRegistry registry;
    private final SkillMatcher matcher;

    /** planner 前置调用：匹配并激活 skill，返回激活结果（含工具白名单） */
    public ActivationResult activate(String intent, TaskType taskType, String target, String userInput) {
        List<Skill> matched = matcher.match(intent, taskType, target, userInput, registry.enabledSkills());
        return new ActivationResult(matched, mergeToolWhitelist(matched), maxRisk(matched));
    }

    public record ActivationResult(List<Skill> matched, List<String> toolWhitelist, RiskLevel maxRisk) {}
}
```

---

## 5. 与现有架构的接入点

底座不动三段式 pipeline，只改 4 个接入点：

### 5.1 接入点 1：AskService / AlertWorkflowService 入口激活

```java
// AskService.handle 改造
public AskExecutionResult handle(String question) {
    GraphState state = new GraphState();
    state.setUserRequest(question);

    // 新增：skill 激活（planner 推断前）
    ActivationResult activation = skillActivationService.activate(
        inferIntent(question), inferTaskType(question), inferTarget(question), question);
    state.getContext().put("skillActivation", activation);   // 供后续节点用
    activation.matched().forEach(s -> state.getSkillBuffer().push(s.name(), s.body()));

    plannerAgent.run(state);
    ...
}
```

### 5.2 接入点 2：PlannerLlmService.buildSystemPrompt / buildUserPrompt 注入

```java
private String buildSystemPrompt(GraphState state) {
    String index = SkillIndexFormatter.format(skillRegistry.enabledSkills());  // 索引段常驻
    return BASE_PLANNER_PROMPT + index;
}

private String buildUserPrompt(String request, Map<String, Object> knowledge, GraphState state) {
    String loadedSkills = state.getSkillBuffer().drain();  // 已激活正文前置
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("request", loadedSkills + "\n" + request);   // 正文前置到 request 前
    payload.put("plannerKnowledge", knowledge);
    return objectMapper.writeValueAsString(payload);
}
```

### 5.3 接入点 3：AgentToolCatalog 工具白名单过滤

```java
// AgentToolCatalog 增加方法
public List<ToolDefinition> executorTools(List<String> whitelist) {
    List<ToolDefinition> all = executorTools();
    if (whitelist == null || whitelist.isEmpty()) return all;   // 无限制
    return all.stream().filter(t -> whitelist.contains(t.name())).toList();
}

// ExecutorThinkNode.findExecutorTool 时，若激活 skill 有限制，只在白名单内查
```

### 5.4 接入点 4：VerifierAgent 用 skill.maxRisk 联动审批

```java
// VerifierThinkNode：若激活 skill.maxRisk < 当前 task.riskLevel，强制走 ApprovalNode
ActivationResult activation = (ActivationResult) state.getContext().get("skillActivation");
if (activation != null && activation.maxRisk() != null
        && task.riskLevel().ordinal() > activation.maxRisk().ordinal()) {
    return NodeResult.waiting(...);  // 触发审批
}
```

---

## 6. 配置

```yaml
kubeoncall:
  skill:
    enabled: true
    builtin-dir: classpath:skills              # jar 内置
    project-dir: ${user.home}/.kubeoncall/skills  # 外部覆盖
    state-file: ${user.home}/.kubeoncall/skill-state.json
    max-activations: 3                          # 单次最多激活 skill 数
    index-max-bytes: 4096
    auto-match: true                            # 模式 A 自动匹配
    llm-request: false                          # 模式 B LLM 指名（第二阶段）
```

落在 `KubeOnCallProperties.Skill` 下。

---

## 7. 分阶段实施

### 阶段 1：Skill 载体 + 注册 + 解析（底座骨架）—— P0

**任务**：`Skill` / `SkillFrontmatterParser` / `SkillRegistry` / `SkillStateStore`，三层扫描 + 解析 + 启用过滤。
**验收**：放一个 `resources/skills/payment-oom-triage/SKILL.md`，启动后 `SkillRegistry.enabledSkills()` 能读到。
**风险**：极简 YAML 解析器要覆盖 `key: value` / 多行 / 行内数组；解析失败不阻塞启动。

### 阶段 2：自动匹配 + 注入（模式 A 主路径）—— P0

**任务**：`SkillMatcher` / `SkillIndexFormatter` / `SkillContextBuffer` / `SkillActivationService`，接入 `AskService` 入口 + `PlannerLlmService` 注入点。
**验收**：输入"payment-service OOM"，planner 的 user prompt 出现已加载 skill 正文；无关输入不激活。
**关键**：drain 一次性消费防跨轮重复；索引段预算约束防超窗。

### 阶段 3：工具白名单过滤 —— P1

**任务**：`AgentToolCatalog.executorTools(whitelist)` + `ExecutorThinkNode` 接入。
**验收**：激活 `payment-oom-triage`（白含 4 工具）后，executor 只能选白名单内工具；无白名单 skill 不影响。

### 阶段 4：verifier 风险联动 —— P1

**任务**：`VerifierThinkNode` 读 `skillActivation.maxRisk`，超限触发审批。
**验收**：skill 声明 `maxRisk: HIGH`，task 是 CRITICAL 时走 `ApprovalNode`。

### 阶段 5：LLM 指名激活（模式 B，演进）—— P2

**任务**：`PlannerLlmDecision` 增加 `requestedSkills` 字段，planner think 后按模型指名激活。
**验收**：LLM 在 JSON 输出 `requestedSkills: ["payment-oom-triage"]`，该 skill 被激活。
**意义**：为未来真 tool-calling 铺路——一旦 `PlannerLlmService` 改用 `ChatClient.tools()`，`load_skill` 可直接变成真工具。

### 阶段 6：与 MCP 动态化协同（远期）—— P2

**任务**：若 `McpToolRegistry` 改为从 `McpClient` 动态发现工具，skill 的 `toolWhitelist` 可引用动态工具名。
**依赖**：MCP 空壳先被激活（不在本方案范围）。

---

## 8. 风险与边界

1. **不重写 LLM 层**：本方案刻意不碰 `PlannerLlmService` 的反射调用方式。skill 是「注入指令」不是「注册函数」，绕开了 tool-calling 缺失的硬约束。这是底座能在当前架构落地的关键。

2. **skill 与 RAG 不混淆**：skill 正文是结构化经验指令（怎么判断/推荐什么参数/已知坑），RAG 是非结构化 SOP 文档。二者并存：skill 指导"思路"，RAG 提供"详细步骤"。skill 正文里可引用 RAG（如"详见 knowledge.searchSop 的 SOP-OOM-001"），但不复制 SOP 全文。

3. **skill 与记忆不混淆**：skill 是人工沉淀的通用经验（跨设备复用），记忆是系统自动沉淀的特定设备历史（按作用域）。参见记忆重构方案。skill 可在正文中提示"关联告警记忆"，但二者独立。

4. **激活噪声**：`SkillMatcher` 必须"宁缺毋滥"（score=0 不激活），避免无关 skill 污染 planner context。`MAX_ACTIVATIONS=3` + `SkillContextBuffer` LRU≤3 控制注入量。

5. **token 预算**：skill 索引段（≤4096）+ 已激活正文（≤3 个）+ RAG 知识块 + 对话历史，要计入 `TokenBudget`（见记忆重构方案阶段 1）。skill 注入是预算消费方之一。

6. **工具白名单收紧风险**：白名单过严可能导致 executor 找不到工具而失败。`ExecutorThinkNode` 在白名单内找不到工具时，应回退到全量 catalog + warning，而非直接失败。

7. **frontmatter 解析鲁棒性**：极简 YAML 解析器对嵌套对象/anchor 不支持，命中即 warning 跳过该字段不阻塞。复杂 skill 元数据用行内数组或多行字符串表达。

8. **演进路径不锁死**：模式 A（自动匹配）和模式 B（LLM 指名）并存不冲突。未来若启用 Spring AI tool-calling，`load_skill` 可升级为真 `ToolCallback`，`SkillActivationService` 改为响应 tool-call 即可，载体/注册/注入层无需重写。

---

## 9. 最小可落地版本（MVP）

若先做小步快跑：

1. `Skill` 模型 + `SkillFrontmatterParser` + `SkillRegistry`（两层扫描）+ `SkillStateStore`。
2. `SkillMatcher`（关键词 + tag + TaskType 评分）+ `SkillContextBuffer`。
3. `AskService` 入口激活 + `PlannerLlmService.buildUserPrompt` 注入已激活正文。
4. 一个示例 skill：`resources/skills/payment-oom-triage/SKILL.md`。

这个版本让 kubeoncall 从"经验散落在 planner 规则代码"进化到"经验可加载、可注入、可版本化"，且完全不依赖 LLM tool-calling、不重写三段式 pipeline、不碰 MCP 空壳。工具白名单过滤和 verifier 风险联动作为第二迭代。

---

## 10. 结论

kubeoncall 当前不具备 skill 运行底座（LLM 无 tool-calling、工具是规则推断、MCP 是空壳），但**不需要先补齐这些才能上 skill**——关键取舍是把第一版 Skill 定位为「注入 LLM 的指令包 + 工具白名单」而非「LLM 自主调用的函数」，这样底座完全建立在现有架构之上：

- **载体**：`Skill`（frontmatter + 正文），`SKILL.md` 文件，参照 paicli。
- **注册**：`SkillRegistry` 两层扫描（builtin + project），参照 paicli 三层覆盖。
- **激活**：模式 A 自动匹配（`SkillMatcher` 关键词/tag/TaskType 评分），不依赖 tool-calling；模式 B LLM 指名作为演进。
- **注入**：`SkillIndexFormatter` 索引段常驻 system prompt + `SkillContextBuffer` 已激活正文前置 user prompt，参照 paicli。
- **工具约束**：`toolWhitelist` 过滤 `AgentToolCatalog`。
- **风险联动**：`maxRisk` 接入 `VerifierAgent` 审批。

4 个接入点（AskService 入口、PlannerLlmService 注入、AgentToolCatalog 过滤、VerifierThinkNode 风险）均最小侵入，不破坏三段式 pipeline。演进路径清晰：模式 B → 真 tool-calling（`load_skill` 升级为 `ToolCallback`）→ MCP 动态工具，每步都不推翻前一步。

这个底座让运维经验从"硬编码在 planner 推断规则里"变成"可加载、可版本化、可注入、可约束工具"的一等公民，是 kubeoncall 走向经验可沉淀的 oncall agent 的基础。
