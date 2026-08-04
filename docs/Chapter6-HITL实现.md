# Chapter 6：HITL 人机协同审批实现

> 本文档整理 paicli 项目中 Human-in-the-Loop（HITL）审批系统的核心实现，涵盖危险操作识别、审批交互、透明拦截层设计、CLI 命令集成，以及流式输出与 HITL 提示的协同处理。后续补充了安全策略层增强（PathGuard 路径围栏、CommandGuard 命令快速拒绝、AuditLog 审计日志），作为 HITL 之外的兜底防线。

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
  - [9.2 /policy 与 /audit（安全策略增强）](#92-policy-与-audit安全策略增强)
  - [9.3 /memory clear](#93-memory-clear)
  - [9.4 /clear 联动](#94-clear-联动)
- [10. Memory 增强（同期改动）](#10-memory-增强同期改动)
  - [10.1 ContextCompressor 事实提取增强](#101-contextcompressor-事实提取增强)
  - [10.2 长期记忆语义对齐](#102-长期记忆语义对齐)
- [11. 安全策略层增强（com.paicli.policy 包）](#11-安全策略层增强compaiclipolicy-包)
  - [11.1 PolicyException：策略拦截异常](#111-policyexception策略拦截异常)
  - [11.2 PathGuard：路径围栏](#112-pathguard路径围栏)
  - [11.3 CommandGuard：命令快速拒绝](#113-commandguard命令快速拒绝)
  - [11.4 AuditLog：结构化审计日志](#114-auditlog结构化审计日志)
  - [11.5 ToolRegistry 中的策略集成](#115-toolregistry-中的策略集成)
  - [11.6 HitlToolRegistry 的审计增强](#116-hitltoolregistry-的审计增强)
  - [11.7 Prompt 中的安全策略硬规则](#117-prompt-中的安全策略硬规则)
  - [11.8 不做沙箱的取舍](#118-不做沙箱的取舍)
- [12. 完整端到端示例](#12-完整端到端示例)
- [13. 关键设计要点](#13-关键设计要点)
- [14. 与模板项目的差异说明](#14-与模板项目的差异说明)

---

## 1. 整体架构概览

PaiCLI 的 HITL 审批系统由 `com.paicli.hitl` 包的 6 个类 + `com.paicli.policy` 包的 4 个类组成，加上对 `Agent`、`PlanExecuteAgent`、`SubAgent`、`ToolRegistry`、`MemoryManager`、`ContextCompressor`、`CliCommandParser`、`Main` 的适配改动。

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

**安全策略层（HITL 之后的兜底防线）**：

```
ToolRegistry.executeTool()  ← HITL 通过（或跳过）后进入
  ├─ executor lambda 执行
  │    ├─ PathGuard.resolveSafe()      ← 文件路径必须在项目根之内
  │    ├─ CommandGuard.check()         ← 9 条正则命令黑名单
  │    └─ write_file > 5MB → PolicyException
  ├─ catch PolicyException → "🛡️ 策略拒绝: ..." + audit(deny, approver=policy)
  ├─ catch Exception       → "工具执行失败: ..." + audit(error)
  └─ 正常完成              → audit(allow)

AuditLog（旁路审计，JSONL 按天分文件）
  ~/.paicli/audit/audit-YYYY-MM-DD.jsonl
  {"timestamp":"...","tool":"write_file","outcome":"allow","approver":"none","durationMs":12}
  {"timestamp":"...","tool":"execute_command","outcome":"deny","reason":"禁止 sudo 提权","approver":"policy"}
  {"timestamp":"...","tool":"write_file","outcome":"deny","reason":"用户拒绝了此操作","approver":"hitl"}
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

### 9.2 /policy 与 /audit（安全策略增强）

基于 f90d9f5 提交新增的安全策略命令：

| 命令 | 行为 |
|---|---|
| `/policy` | 显示当前安全策略状态：项目根、危险工具列表、路径围栏规则、命令黑名单、文件/命令资源上限、审计目录路径 |
| `/audit` | 显示最近 10 条危险工具审计记录 |
| `/audit 20` | 显示最近 20 条（1–100 之间可调） |

`printPolicyStatus()`:
```java
System.out.println("🛡️ 安全策略状态：");
System.out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
System.out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()));
System.out.println("   路径围栏: 强制限定在项目根之内");
System.out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / ...");
System.out.println("   写入文件上限: 5MB");
System.out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
```

`printAuditTail()` 从 `AuditLog.readRecent(N)` 读取今日 JSONL 审计文件，按 `[OUTCOME] timestamp tool (durationMs, approver=xxx)` 格式展示，带拒绝原因。

### 9.3 /memory clear

新增 `MEMORY_CLEAR` 命令类型，调用 `MemoryManager.clearLongTerm()` 清空 `~/.paicli/memory/long_term_memory.json`，保留 Token 统计和压缩器状态不变。

### 9.4 /clear 联动

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

## 11. 安全策略层增强（com.paicli.policy 包）

基于模板项目 f90d9f5 提交，新增 `com.paicli.policy` 包作为 HITL 之外的兜底安全防线。HITL 是人机协同审批（需要用户参与），策略层是硬规则自动拦截（不需要用户参与），两者互补。

### 11.1 PolicyException：策略拦截异常

```java
public class PolicyException extends RuntimeException {
    public PolicyException(String message) { super(message); }
}
```

轻量级 RuntimeException，被 `ToolRegistry.executeTool()` 的 catch 分支统一捕获，返回 `"🛡️ 策略拒绝: ..."` 前缀的错误字符串给 LLM。设计为非受检异常是为了在工具 lambda 内部抛出时不需要声明 throws。

### 11.2 PathGuard：路径围栏

文件类工具（read_file / write_file / list_dir / create_project）的**共同入口校验器**。定位是 HITL 之前的 LLM 输入合法性检查，不是沙箱（不提供进程隔离）。

**解决三类越界场景**：
1. 绝对路径直接逃出项目根（LLM 给出 `/etc/passwd`）
2. 相对路径用 `..` 穿越（`../../etc/passwd`）
3. 符号链接逃逸（项目内的软链指向外部目录）

```java
public class PathGuard {
    private final Path rootPath;  // 初始化时已展开为真实路径

    public Path resolveSafe(String input) {
        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute() ? raw.normalize() : rootPath.resolve(raw).normalize();
        Path realResolved = resolveRealPath(resolved);  // 处理符号链接

        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException("路径越界: " + input + " 不在项目根之内");
        }
        return realResolved;
    }
}
```

**不存在的路径也能校验**（write_file 创建新文件场景）：`resolveRealPath()` 向上找到最近的存在祖先 → 调用 `toRealPath()` 解析符号链接 → 再接回剩余段。即使目标文件尚不存在，如果路径中段是软链指向外部目录，仍会被检测到越界。

**macOS 兼容**：初始化时将根本身先展开为真实路径（`/var/folders` → `/private/var/folders`），避免后续 startsWith 永远返回 false。

### 11.3 CommandGuard：命令快速拒绝

在 execute_command 进入 ProcessBuilder 之前的黑名单 fast-fail。定位是辅助 HITL 而非主防线——黑名单列不全，但能拦住 LLM 容易踩的明显破坏性命令，减少 HITL 弹窗骚扰。

```java
public final class CommandGuard {
    private static final List<DenyRule> RULES = List.of(
        new DenyRule("禁止 sudo 提权",        Pattern.compile("(?i)\\bsudo\\b")),
        new DenyRule("禁止 rm -rf 删除全盘",   Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)|...")),
        new DenyRule("禁止 mkfs 格式化磁盘",   Pattern.compile("(?i)\\bmkfs(\\.|\\b)")),
        new DenyRule("禁止 dd 写入裸设备",     Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/")),
        new DenyRule("识别为 fork bomb",       Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
        new DenyRule("禁止 curl|sh 执行远端脚本", Pattern.compile("(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b")),
        new DenyRule("不允许扫描 /、~ 全盘",    Pattern.compile("(?i)\\bfind\\s+(/|~|\\$home)")),
        new DenyRule("禁止 chmod 777 全盘",    Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+(/|~)")),
        new DenyRule("禁止 shutdown/reboot",   Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"))
    );

    // null = 放行，非 null = 拒绝原因
    public static String check(String command) { ... }
}
```

**设计取舍**：不做完整 shell 解析（太复杂），只做正则模式匹配。命令替换 `$(...)` 和反引号内的内容以原文存在，正则一并扫描，不需要单独展开。curl / git / 网络命令默认放行，只拦真正破坏性的。

被拒绝时抛 `PolicyException`，由 `ToolRegistry.executeTool()` 统一 catch 后返回 `"🛡️ 策略拒绝: 禁止 sudo 提权"` 格式的错误。

### 11.4 AuditLog：结构化审计日志

把 Agent 的"实际副作用"变成可回放的事实流。落盘策略：

- **格式**：一行一条 JSON（JSONL），按天分文件 `audit-YYYY-MM-DD.jsonl`
- **默认目录**：`~/.paicli/audit`，可通过 `-Dpaicli.audit.dir` 或 `PAICLI_AUDIT_DIR` 覆盖
- **容错**：写入失败只在 stderr 提示，不抛出，避免审计故障影响主流程

```java
public class AuditLog {
    public record AuditEntry(
        String timestamp,   // Instant.now().toString()
        String tool,        // write_file / execute_command / create_project
        String args,        // 原始 argumentsJson（截断到 1000 字符）
        String outcome,     // allow / deny / error
        String reason,      // 拒绝原因或异常消息
        String approver,    // none / hitl / policy
        long durationMs     // 工具执行耗时
    ) {
        static AuditEntry allow(String tool, String args, long durationMs) { ... }       // approver=none
        static AuditEntry denyByHitl(String tool, String args, String reason, long ms) { ... } // approver=hitl
        static AuditEntry denyByPolicy(String tool, String args, String reason, long ms) { ... } // approver=policy
        static AuditEntry error(String tool, String args, String reason, long ms) { ... } // approver=none, outcome=error
    }
}
```

三个接入点：
| 结果 | outcome | approver | 触发条件 |
|------|---------|----------|---------|
| 正常执行 | `allow` | `none` | 危险工具执行成功 |
| HITL 拒绝/跳过 | `deny` | `hitl` | 用户选 [n] 拒绝或 [s] 跳过 |
| 策略拦截 | `deny` | `policy` | PathGuard / CommandGuard / 文件大小越限 |
| 执行异常 | `error` | `none` | 工具内部 IOException 等异常 |

```jsonl
{"timestamp":"2026-04-28T12:00:00Z","tool":"write_file","args":"{\"path\":\"a.txt\"}","outcome":"allow","reason":null,"approver":"none","durationMs":12}
{"timestamp":"2026-04-28T12:01:00Z","tool":"execute_command","args":"{\"command\":\"sudo rm -rf /\"}","outcome":"deny","reason":"禁止 sudo 提权","approver":"policy","durationMs":1}
{"timestamp":"2026-04-28T12:02:00Z","tool":"write_file","args":"{\"path\":\"secret.txt\"}","outcome":"deny","reason":"用户拒绝了此操作","approver":"hitl","durationMs":5432}
```

线程安全：`record()` 用 `synchronized(writeLock)` 保护 JSONL 文件写入，并发场景不会交错。

### 11.5 ToolRegistry 中的策略集成

策略层在 `executeTool()` 方法中统一接入：

```java
public String executeTool(String name, String argumentsJson) {
    boolean shouldAudit = AUDIT_TOOLS.contains(name); // write_file / execute_command / create_project
    long start = System.nanoTime();

    try {
        // ... 解析参数 + 调 executor
        String result = tool.executor().execute(argMap);
        if (shouldAudit) auditLog.record(AuditEntry.allow(name, args, elapsed));
        return result;
    } catch (PolicyException e) {
        if (shouldAudit) auditLog.record(AuditEntry.denyByPolicy(name, args, e.getMessage(), elapsed));
        return "🛡️ 策略拒绝: " + e.getMessage();
    } catch (Exception e) {
        if (shouldAudit) auditLog.record(AuditEntry.error(name, args, e.getMessage(), elapsed));
        return "工具执行失败: " + e.getMessage();
    }
}
```

关键改动点：
- **read_file / write_file / list_dir**：executor lambda 内部调用 `pathGuard.resolveSafe(path)` 后再操作
- **create_project**：同样走 `pathGuard.resolveSafe(name)`
- **write_file**：额外检查 UTF-8 字节数是否超过 `MAX_WRITE_FILE_BYTES`（5MB）
- **execute_command**：`CommandGuard.check()` 替代了旧的 `isDisallowedBroadScan()`（旧方法已删除），拒绝时抛 `PolicyException` 统一走策略拦截路径
- **setProjectPath()**：同时重建 `pathGuard`

### 11.6 HitlToolRegistry 的审计增强

HITL 层拒绝/跳过时也写审计记录（approver=hitl），批准通过后由父类 ToolRegistry 写 allow/policy-deny/error，同一份审计文件。

```java
// HITL 拒绝
if (result.isRejected()) {
    getAuditLog().record(AuditEntry.denyByHitl(name, args, reason, elapsedMillis(start)));
    return "[HITL] 操作已被拒绝：" + reason;
}
// HITL 跳过
if (result.isSkipped()) {
    getAuditLog().record(AuditEntry.denyByHitl(name, args, "用户跳过", elapsedMillis(start)));
    return "[HITL] 操作已被跳过";
}
// 批准 → super.executeTool() 负责 allow audit
```

### 11.7 Prompt 中的安全策略硬规则

三个 Agent 的 System Prompt 均新增安全策略硬规则段：

```
安全策略硬规则（HITL 之外的兜底，无法绕过，请提前规避）：
- read_file / write_file / list_dir / create_project 的路径必须在项目根之内，绝对路径或 .. 越界会被拒绝
- write_file 单文件 5MB 上限
- execute_command 禁止 sudo、rm -rf 全盘或用户目录、mkfs、dd 写裸设备、fork bomb、curl|sh、find /、chmod 777 /、shutdown
- 若调用被策略拒绝（结果以 "🛡️ 策略拒绝" 开头），不要原样重试，改用项目内相对路径或更安全的方式
```

这段 Prompt 约束的作用：让 LLM **提前规避**已知会被策略拦截的操作，而不是等到被拒绝后再调整。比如 LLM 知道了"绝对路径会失败"，就不会给出 `/etc/hosts` 这种路径。

### 11.8 不做沙箱的取舍

PathGuard + CommandGuard + 5MB 上限 + 60s 超时是"代理级护栏"，不是"进程级沙箱"。

**参考依据**：Claude Code / Cursor / Aider 等主流 coding agent 都默认不做容器隔离。它们信任以下事实：
- 本地开发者在自己掌控的机器上工作
- Agent 只在项目目录内读写文件（路径围栏）
- 真正破坏性的系统操作会被命令黑名单拦截
- HITL 审批是最后一道防线

**结论**：教学项目不做沙箱。容器/Docker/虚拟机沙箱更适合 CI/CD pipeline（即 MCP Server 场景），当 Agent 需要访问外部系统或执行不受信任代码时才引入。

---

## 12. 完整端到端示例

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

## 13. 关键设计要点

1. **透明拦截，零开销降级**：`HitlToolRegistry` 继承 `ToolRegistry`，HITL 关闭时直接透传，与没有 HITL 时性能完全一致

2. **Fail-safe 默认行为**：无法识别的输入不默认放行；5 次无效输入后保守拒绝；输入流关闭时保守拒绝

3. **CJK-aware 终端渲染**：审批框的 padding 按显示列宽而非字符数计算，确保中文/emoji 不破坏边框对齐

4. **并发安全的审批序列化**：`synchronized` 方法确保多 Agent 并行时只有一个审批提示活跃

5. **流式输出与 HITL 的协同**：`resetBetweenIterations()` 在工具调用前 flush 所有 pending 文本，避免 Markdown 渲染器的缓冲文本被 HITL 提示"跨过"

6. **会话隔离的"全部放行"**：`approvedAllTools` 是内存中的 `ConcurrentHashSet`，`/clear` 和 `/hitl off` 时重置，不持久化到磁盘

7. **事实提取的"稳定 vs 临时"过滤**：LLM prompt 增强 + 客户端关键词过滤，双重保障减少噪声事实污染长期记忆

8. **PathGuard 的"不存在路径也能校验"**：write_file 创建新文件时目标路径尚不存在，`resolveRealPath()` 向上找最近的存在祖先解析符号链接后接回剩余段，仍能检测到路径中段的软链越界

9. **CommandGuard 的"命令替换自动扫描"**：不做 shell 解析展开，但 `$(...)` 和反引号内的危险模式仍以原文形式存在于命令字符串中，正则一并覆盖，不需要单独处理

10. **审计日志的"容错不卡主流程"**：`AuditLog.record()` 内部 try-catch，写入失败只在 stderr 打印警告，不抛出异常。审计是旁路（sidecar），不能影响 Agent 的工具执行

11. **HITL 审批 + 策略围栏的双层防御**：HITL 是人参与的审批（问用户），策略围栏是硬规则自动拦截（不问用户）。LLM 调用 `rm -rf /` 时：先被 CommandGuard 在 ProcessBuilder 之前拦截（返回 "🛡️ 策略拒绝"），用户根本没有机会审批——因为这种命令无论用户是否批准都不应该执行

---

## 14. 与模板项目的差异说明

本实现参考了模板项目（`/Users/hjh/Documents/Projects/Java_Resources/paicli/paicli`）中提交 `75e6642`（HITL 基础）和 `f90d9f5`（PathGuard / CommandGuard / AuditLog 安全增强）的设计，但在以下方面做了适配和取舍：

| 方面 | 模板项目 | 本项目的选择 | 原因 |
|---|---|---|---|
| 上下文架构 | 短期/长期记忆分离，各自管理 Entry | 保留共享上下文模式（`sharedHistory` + `sharedMemory`） | 当前项目的压缩上下文架构更先进，不需要模板的 Memory 分离设计 |
| `clearHistory()` | 移除事实提取 | **保留**事实提取 | 在清空前提取事实能避免对话知识永久丢失 |
| PlanExecuteAgent | PlanAgent 复用 ReAct 的 ToolRegistry | 新增公开构造器，兼容共享上下文 | 当前项目已有多模式共享上下文的完善设计 |
| 事实提取增强 | 完整移植 prompt + 过滤 | 完整移植 | 直接复用，适配到 `List<Message>` 输入格式 |
