# Chapter 6：HITL 人机协同审批实现

> 本文档整理 paicli 项目中 Human-in-the-Loop（HITL）审批系统的核心实现，涵盖危险操作识别、审批交互、透明拦截层设计、CLI 命令集成，以及流式输出与 HITL 提示的协同处理。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. ApprovalPolicy：危险操作静态识别](#2-approvalpolicy危险操作静态识别)
- [3. ApprovalRequest：审批请求与终端展示](#3-approvalrequest审批请求与终端展示)
  - [3.1 数据模型](#31-数据模型)
  - [3.2 CJK-aware 终端盒子绘制](#32-cjk-aware-终端盒子绘制)
  - [3.3 JSON-aware 参数格式化](#33-json-aware-参数格式化)
- [4. ApprovalResult：审批决策模型](#4-approvalresult审批决策模型)
- [5. HitlHandler：审批交互接口](#5-hitlhandler审批交互接口)
- [6. TerminalHitlHandler：终端交互实现](#6-terminalhitlhandler终端交互实现)
  - [6.1 交互选项设计](#61-交互选项设计)
  - [6.2 全部放行机制](#62-全部放行机制)
  - [6.3 并发安全](#63-并发安全)
  - [6.4 修改参数子流程](#64-修改参数子流程)
- [7. HitlToolRegistry：透明拦截层](#7-hitltoolregistry透明拦截层)
- [8. Agent / PlanExecuteAgent / SubAgent 的 HITL 集成](#8-agent--planexecuteagent--subagent-的-hitl-集成)
  - [8.1 构造器注入](#81-构造器注入)
  - [8.2 resetBetweenIterations：流式输出与 HITL 的协同](#82-resetbetweeniterations流式输出与-hitl-的协同)
- [9. CLI 命令集成](#9-cli-命令集成)
  - [9.1 /hitl on|off|status](#91-hitl-onoffstatus)
  - [9.2 /memory clear](#92-memory-clear)
  - [9.3 /clear 联动](#93-clear-联动)
- [10. Memory 增强（同期改动）](#10-memory-增强同期改动)
  - [10.1 ContextCompressor 事实提取增强](#101-contextcompressor-事实提取增强)
  - [10.2 长期记忆语义对齐](#102-长期记忆语义对齐)
- [11. 完整端到端示例](#11-完整端到端示例)
- [12. 关键设计要点](#12-关键设计要点)
- [13. 与模板项目的差异说明](#13-与模板项目的差异说明)

---

## 1. 整体架构概览

PaiCLI 的 HITL 审批系统由 `com.paicli.hitl` 包的 6 个类组成，加上对 `Agent`、`PlanExecuteAgent`、`SubAgent`、`MemoryManager`、`ContextCompressor`、`CliCommandParser`、`Main` 的适配改动。

```
Main.java
  ├── TerminalHitlHandler hitlHandler          ← 终端交互 + 状态管理
  ├── HitlToolRegistry hitlToolRegistry        ← 透明拦截层
  │     └── extends ToolRegistry
  │           └── executeTool() 覆写：
  │                 ├── HITL 关闭 → 直接调 super.executeTool()
  │                 └── HITL 开启 + 危险工具 → ApprovalPolicy 判断
  │                       └── TerminalHitlHandler.requestApproval()
  │                             ├── 全部放行缓存命中 → 直接通过
  │                             └── 否则 → 展示审批框 → 等待用户决策
  │
  ├── Agent(apiKey, sharedHistory, sharedMemory, hitlToolRegistry)
  ├── PlanExecuteAgent(apiKey, hitlToolRegistry, reviewHandler, sharedHistory, sharedMemory)
  └── AgentOrchestrator(apiKey, hitlToolRegistry, sharedMemory, sharedHistory)
```

**数据流**：

```
LLM 返回 tool_calls
  └─ Agent 循环: for each toolCall
       ├─ streamRenderer.resetBetweenIterations()   ← flush + 重置渲染器
       └─ hitlToolRegistry.executeTool(name, args)
            ├─ HitlHandler.isEnabled()?  ──── No ──→ super.executeTool(name, args)
            ├─ ApprovalPolicy.requiresApproval(name)? ── No ──→ super.executeTool(name, args)
            └─ Yes:
                 ├─ ApprovalRequest.of(name, args)   ← 构建审批请求
                 ├─ hitlHandler.requestApproval(req) ← 阻塞等待用户
                 ├─ REJECTED  → 返回 "[HITL] 操作已被拒绝：..."
                 ├─ SKIPPED   → 返回 "[HITL] 操作已被跳过"
                 └─ APPROVED / MODIFIED → super.executeTool(name, effectiveArgs)
```

---

## 2. ApprovalPolicy：危险操作静态识别

`ApprovalPolicy` 是一个纯静态工具类，基于静态规则判断哪些工具调用需要人工确认。

```java
private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file",
        "execute_command",
        "create_project"
);
```

**设计原则**：
- **读取类操作**（`read_file`、`list_dir`、`search_code`）无副作用，不需要确认
- **写入/执行类操作**需要确认，有潜在破坏性
- 三个危险工具通过 `getDangerLevel()` 映射到三级危险等级：

| 工具 | 等级 | 风险说明 |
|---|---|---|
| `execute_command` | 🔴 高危 | 将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态 |
| `write_file` | 🟡 中危 | 将写入或覆盖文件内容，原有内容将丢失 |
| `create_project` | 🟡 中危 | 将在磁盘上创建新目录和文件 |

```java
public static boolean requiresApproval(String toolName) {
    return DANGEROUS_TOOLS.contains(toolName);
}
```

---

## 3. ApprovalRequest：审批请求与终端展示

### 3.1 数据模型

```java
public record ApprovalRequest(
        String toolName,        // 工具名（如 "execute_command"）
        String arguments,       // 原始 JSON 参数
        String dangerLevel,     // 危险等级 emoji（如 "🔴 高危"）
        String riskDescription, // 风险说明
        String suggestion,      // 执行理由（当前未使用，预留）
        String callerContext    // 调用来源（当前未使用，预留）
)
```

通过静态工厂 `ApprovalRequest.of(toolName, arguments, suggestion)` 自动填充 `dangerLevel` 和 `riskDescription`。

### 3.2 CJK-aware 终端盒子绘制

`toDisplayText()` 方法生成如下审批框：

```
┌──────────────────────────────────────────────────────────┐
│  ⚠️  需要审批                                            │
├──────────────────────────────────────────────────────────┤
│  工具: execute_command                                   │
│  等级: 🔴 高危                                           │
│  风险: 将在系统上执行 Shell 命令，可能修改文件、安装软件...│
├──────────────────────────────────────────────────────────┤
│  参数:                                                   │
│    command: "rm -rf /tmp/build"                          │
└──────────────────────────────────────────────────────────┘
```

**关键技术点**：所有 padding 都按**终端显示列宽**计算。CJK 字符、emoji 占 2 列，ASCII 占 1 列。如果按 Java `String.length()` 做字符数 pad，中文和 emoji 会把右边框挤歪。

核心方法 `displayWidth(String s)` 遍历 Unicode code point，通过 `isWideCodePoint()` 判断宽度：

```java
private static boolean isWideCodePoint(int cp) {
    return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
            || (cp >= 0x2E80 && cp <= 0x9FFF)  // CJK Radicals/统一
            || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角
            || (cp >= 0x1F300 && cp <= 0x1FAFF); // Emoji 主体区间
    // ... 其他范围
}
```

### 3.3 JSON-aware 参数格式化

`formatArgs()` 方法将 JSON 参数解析为结构化展示：
- **JSON 对象**：逐字段 `key: value_preview`
- **长字符串**（>120 字符）：展示前 120 字符 + `...` + 总长度摘要
- **换行符**：替换为 `⏎` 符号，保持单行预览
- **非法 JSON**：退回到原始字符串按显示宽度换行

---

## 4. ApprovalResult：审批决策模型

```java
public record ApprovalResult(
        Decision decision,          // 5 种决策类型
        String modifiedArguments,   // MODIFIED 时的修改后参数
        String reason               // REJECTED 时的拒绝原因
)
```

五种决策和对应的便捷工厂方法：

| 决策 | 方法 | 含义 |
|---|---|---|
| `APPROVED` | `approve()` | 批准执行，使用原始参数 |
| `APPROVED_ALL` | `approveAll()` | 批准本次会话所有后续同类操作 |
| `REJECTED` | `reject(reason)` | 拒绝执行，Agent 收到拒绝通知后可重新规划 |
| `MODIFIED` | `modify(args)` | 修改参数后执行 |
| `SKIPPED` | `skip()` | 跳过本步骤，继续后续操作 |

`isApproved()` 返回 `true` 当决策为 `APPROVED`、`APPROVED_ALL` 或 `MODIFIED`。

`effectiveArguments(original)` 在 `MODIFIED` 时返回修改后的参数，否则返回原始参数。

---

## 5. HitlHandler：审批交互接口

```java
public interface HitlHandler {
    ApprovalResult requestApproval(ApprovalRequest request);
    boolean isEnabled();
    void setEnabled(boolean enabled);
}
```

**设计约定**：
- 审批是**同步阻塞**操作，实现类需等待用户输入后才返回
- 实现类不负责判断"是否需要审批"——该判断由 `ApprovalPolicy` 负责
- 实现类只负责"展示请求 + 收集决策"
- `isEnabled()` / `setEnabled()` 允许运行时切换，默认关闭

---

## 6. TerminalHitlHandler：终端交互实现

### 6.1 交互选项设计

```
请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数
>
```

- **y / Enter**：批准本次操作（Enter 等价于 y，降低误操作成本）
- **a**：批准本次会话所有后续同类危险操作
- **n**：拒绝（可选填拒绝原因，LLM 可据此重新规划）
- **s**：跳过（继续后续操作）
- **m**：修改参数后执行（进入参数输入子流程）

**fail-safe 设计**：最多重试 5 次，无法识别的输入重新提示而非默认放行。连续 5 次无效输入后保守处理为拒绝。

### 6.2 全部放行机制

```java
private final Set<String> approvedAllTools = ConcurrentHashMap.newKeySet();
```

当用户选 `[a]` 时，工具名加入 `approvedAllTools`。后续同一工具的调用在 `requestApproval()` 入口处直接通过：

```java
if (approvedAllTools.contains(request.toolName())) {
    out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
    return ApprovalResult.approveAll();
}
```

`clearApprovedAll()` 在 `/clear` 和 `/hitl off` 时调用，重置全部放行记录。

### 6.3 并发安全

`requestApproval()` 方法整体 `synchronized`，确保多 Agent 并行场景下（如 PlanExecuteAgent 的并行批次）同一时刻只有一个审批提示活跃，避免 stdout 串扰与 stdin 争抢。

### 6.4 修改参数子流程

`promptModifiedArguments()` 让用户输入修改后的 JSON 参数。输入会经过 `ObjectMapper.readTree()` 校验合法性，非法 JSON 会提示错误并回到主菜单。

---

## 7. HitlToolRegistry：透明拦截层

```java
public class HitlToolRegistry extends ToolRegistry {
    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();          // 复用父类的全部内置工具注册
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.executeTool(name, argumentsJson);  // 直接透传
        }

        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            return "[HITL] 操作已被拒绝：" + ...;
        }
        if (result.isSkipped()) {
            return "[HITL] 操作已被跳过";
        }

        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.executeTool(name, effectiveArgs);
    }
}
```

**关键设计**：
- HITL **关闭**或工具**不需要审批**时，直接调 `super.executeTool()`，与普通 `ToolRegistry` 行为完全相同，**零额外开销**
- 审批通过（含修改参数）时，用 `effectiveArguments()` 获取最终参数执行
- 拒绝/跳过时返回 `[HITL]` 前缀的字符串，Agent 循环将其作为工具结果加入对话历史，LLM 可据此调整策略

---

## 8. Agent / PlanExecuteAgent / SubAgent 的 HITL 集成

### 8.1 构造器注入

三个 Agent 类各新增接受外部 `ToolRegistry` 的构造器：

```java
// Agent
public Agent(String apiKey, List<Message> sharedHistory,
             MemoryManager sharedMemory, ToolRegistry toolRegistry)

// PlanExecuteAgent
public PlanExecuteAgent(String apiKey, ToolRegistry toolRegistry,
             PlanReviewHandler reviewHandler,
             List<Message> sharedHistory, MemoryManager sharedMemory)
```

`Main` 启动时创建 `HitlToolRegistry` 并注入所有模式：

```java
TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false);
HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);

// ReAct 模式
Agent reactAgent = new Agent(apiKey, sharedHistory, sharedMemory, hitlToolRegistry);

// Plan 模式（也走 hitlToolRegistry）
PlanExecuteAgent planAgent = createPlanAgent(..., hitlToolRegistry);

// Team 模式（Worker 通过 AgentOrchestrator 共享 hitlToolRegistry）
AgentOrchestrator orchestrator = new AgentOrchestrator(apiKey, hitlToolRegistry, ...);
```

### 8.2 resetBetweenIterations：流式输出与 HITL 的协同

这是本期的关键技术点。`TerminalMarkdownRenderer` 按换行 flush 输出，当一段 LLM 回复的中间文本尚未遇到换行时，它滞留在 renderer 的内部缓冲区中。

**问题场景**：LLM 返回部分 content → tool_calls → HITL 审批框弹出。如果此时不 flush 渲染器，HITL 的审批框会"跨过"尚未刷出的文本，导致 🧠/🤖 标题与实际内容错位。

**解决方案**：在工具调用前调用 `resetBetweenIterations()`：

```java
// 在 tool_calls 处理前
streamRenderer.resetBetweenIterations();
// 然后执行工具（可能触发 HITL 审批）
for (GLMClient.ToolCall toolCall : response.toolCalls()) { ... }
```

`resetBetweenIterations()` 做的事：
1. `finish()` 当前 reasoning/content 渲染器（刷出所有 pending 文本）
2. 将 late reasoning（content 之后追加的思考内容）当场 flush 为「🧠 补充思考」
3. 重置所有状态标记（`reasoningStarted` / `contentStarted` / `pendingReasoning`）
4. 下一轮迭代到达时重新打印 🧠/🤖 标题

三个 Agent 的 StreamRenderer 都实现了该方法。

---

## 9. CLI 命令集成

### 9.1 /hitl on|off|status

| 命令 | 行为 |
|---|---|
| `/hitl on` | 启用 HITL，提示"write_file / execute_command / create_project 执行前将请求人工确认" |
| `/hitl off` | 关闭 HITL，同时调用 `clearApprovedAll()` 清除全部放行缓存 |
| `/hitl`（无参数） | 显示当前状态 |

### 9.2 /memory clear

新增 `MEMORY_CLEAR` 命令类型，调用 `MemoryManager.clearLongTerm()` 清空 `~/.paicli/memory/long_term_memory.json`，保留 Token 统计和压缩器状态不变。

### 9.3 /clear 联动

`/clear` 清空对话历史时，同时调用 `hitlHandler.clearApprovedAll()`，避免"全部放行"的缓存跨越不同对话上下文生效。

---

## 10. Memory 增强（同期改动）

### 10.1 ContextCompressor 事实提取增强

**原 Prompt 问题**：LLM 经常把"用户想创建一个 Controller"这种临时任务描述提取为"事实"，导致长期记忆被噪声污染。

**增强 Prompt**：
```
请从以下对话中提取"跨会话仍然成立、未来复用仍有价值"的稳定事实：
...
绝对不要提取以下内容：
- 当前这一轮让你执行的临时任务、步骤、todo
- 一次性的文件名、目录名、输出要求
- 模型自己的猜测、纠错、提醒、推断
- "用户想要/需要/让我/请你..." 这类请求句
```

**客户端过滤**：新增 `isPersistentFactCandidate()` 方法：

```java
private static final List<String> EPHEMERAL_FACT_PREFIXES = List.of(
    "用户想", "用户要", "用户需要", "用户请求", "帮我", "让我",
    "新建", "创建", "删除", "修改", "生成", "补充要求", "当前这一轮", "本次任务"
);

private static final List<String> SPECULATION_CUES = List.of(
    "可能", "应该", "猜测", "推测", "笔误", "提醒"
);

private static final List<String> DURABLE_FACT_HINTS = List.of(
    "用户偏好", "用户习惯", "喜欢", "倾向", "项目", "仓库", "路径", "技术栈",
    "版本", "模型", "接口", "配置", "环境变量", "命令", "约定", "规则", "默认"
);
```

过滤规则：
1. 过短（≤5 字符）→ 丢弃
2. 以临时性前缀开头 → 丢弃
3. 包含推测性词汇 → 丢弃
4. 包含冒号（说明是格式化的事实陈述）→ 保留
5. 包含持久性关键词 → 保留

### 10.2 长期记忆语义对齐

`MemoryRetriever.buildContextForQuery()` 的 section 标题从 `"## 相关记忆"` 改为 `"## 相关长期记忆"`，明确区分对话上下文和跨会话记忆。

---

## 11. 完整端到端示例

**场景**：用户启用 HITL 后，让 Agent 创建一个 Java 项目。

```
👤 你: /hitl on
🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认

👤 你: 帮我创建一个名为 demo 的 Java 项目

🤔 思考中...

🧠 思考过程
用户想要创建一个 Java 项目，我可以使用 create_project 工具。

────────── ⚠️  HITL 审批请求 ──────────
┌──────────────────────────────────────────────────────────┐
│  ⚠️  需要审批                                            │
├──────────────────────────────────────────────────────────┤
│  工具: create_project                                    │
│  等级: 🟡 中危                                           │
│  风险: 将在磁盘上创建新目录和文件                          │
├──────────────────────────────────────────────────────────┤
│  参数:                                                   │
│    name: "demo"                                          │
│    type: "java"                                          │
└──────────────────────────────────────────────────────────┘

请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数
> y
  已批准

🔧 执行工具: create_project
   参数: {"name":"demo","type":"java"}
   结果: 项目 demo 已创建，类型: java

🤖 回复
已成功创建 Java 项目 demo，包含标准的 Maven 项目结构。
```

**全部放行场景**：

```
────────── ⚠️  HITL 审批请求 ──────────
┌──────────────────────────────────────────────────────────┐
│  ... write_file 审批框 ...                               │
└──────────────────────────────────────────────────────────┘

请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数
> a
  已批准，后续 write_file 操作将自动通过

[HITL] write_file 已在本次会话中全部放行，自动通过   ← 后续自动通过
```

---

## 12. 关键设计要点

1. **透明拦截，零开销降级**：`HitlToolRegistry` 继承 `ToolRegistry`，HITL 关闭时直接透传，与没有 HITL 时性能完全一致

2. **Fail-safe 默认行为**：无法识别的输入不默认放行；5 次无效输入后保守拒绝；输入流关闭时保守拒绝

3. **CJK-aware 终端渲染**：审批框的 padding 按显示列宽而非字符数计算，确保中文/emoji 不破坏边框对齐

4. **并发安全的审批序列化**：`synchronized` 方法确保多 Agent 并行时只有一个审批提示活跃

5. **流式输出与 HITL 的协同**：`resetBetweenIterations()` 在工具调用前 flush 所有 pending 文本，避免 Markdown 渲染器的缓冲文本被 HITL 提示"跨过"

6. **会话隔离的"全部放行"**：`approvedAllTools` 是内存中的 `ConcurrentHashSet`，`/clear` 和 `/hitl off` 时重置，不持久化到磁盘

7. **事实提取的"稳定 vs 临时"过滤**：LLM prompt 增强 + 客户端关键词过滤，双重保障减少噪声事实污染长期记忆

---

## 13. 与模板项目的差异说明

本实现参考了模板项目（`/Users/hjh/Documents/Projects/Java_Resources/paicli/paicli`）中提交 `75e6642` 的 HITL 设计，但在以下方面做了适配和取舍：

| 方面 | 模板项目 | 本项目的选择 | 原因 |
|---|---|---|---|
| 上下文架构 | 短期/长期记忆分离，各自管理 Entry | 保留共享上下文模式（`sharedHistory` + `sharedMemory`） | 当前项目的压缩上下文架构更先进，不需要模板的 Memory 分离设计 |
| `clearHistory()` | 移除事实提取 | **保留**事实提取 | 在清空前提取事实能避免对话知识永久丢失 |
| PlanExecuteAgent | PlanAgent 复用 ReAct 的 ToolRegistry | 新增公开构造器，兼容共享上下文 | 当前项目已有多模式共享上下文的完善设计 |
| 事实提取增强 | 完整移植 prompt + 过滤 | 完整移植 | 直接复用，适配到 `List<Message>` 输入格式 |
