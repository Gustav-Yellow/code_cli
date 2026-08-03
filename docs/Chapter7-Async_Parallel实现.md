# Chapter 7：异步并行工具执行

> 本文档整理 PaiCLI 第 7 期"异步执行 + 并行工具调用"的核心实现，涵盖 `ToolRegistry` 批量工具执行、ReAct / Plan / Team 三模式的工具调度重构、流式渲染器的标题去重与缓冲优化、Plan 并行任务的顺序渐进式输出缓冲，以及 Prompts 增强。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. ToolRegistry：批量并行工具执行](#2-toolregistry批量并行工具执行)
  - [2.1 新增数据模型](#21-新增数据模型)
  - [2.2 executeTools()：核心执行逻辑](#22-executetools核心执行逻辑)
  - [2.3 构造器链扩展](#23-构造器链扩展)
- [3. Agent 层工具调度重构](#3-agent-层工具调度重构)
  - [3.1 executeToolCalls()：统一批量入口](#31-executetoolcalls统一批量入口)
  - [3.2 printToolCalls()：紧凑工具摘要](#32-printtoolcalls紧凑工具摘要)
- [4. 流式渲染器增强](#4-流式渲染器增强)
  - [4.1 Agent.StreamRenderer：标题去重](#41-agentstreamrenderer标题去重)
  - [4.2 PlanExecuteAgent.TaskStreamRenderer：缓冲输出 + 标签修正](#42-planexecuteagenttaskstreamrenderer缓冲输出--标签修正)
- [5. Plan 并行任务输出缓冲](#5-plan-并行任务输出缓冲)
  - [5.1 问题背景](#51-问题背景)
  - [5.2 有序渐进式 flush](#52-有序渐进式-flush)
- [6. AgentOrchestrator 并行步骤顺序输出](#6-agentorchestrator-并行步骤顺序输出)
- [7. Prompts 增强](#7-prompts-增强)
- [8. 完整端到端示例](#8-完整端到端示例)
- [9. 关键设计要点](#9-关键设计要点)

---

## 1. 整体架构概览

第 7 期的核心目标：同一轮 LLM 返回多个 `tool_calls` 时并行执行，提升整体响应速度。

```
LLM 返回 3 个 tool_calls: [read_file(pom.xml), read_file(README.md), read_file(ROADMAP.md)]
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │  ToolRegistry.executeTools()  │
                    │  单条 → 直接 executeTool()     │
                    │  多条 → 线程池 invokeAll + 超时│
                    └───────────┬───────────────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
      read_file(pom.xml) read_file(README.md) read_file(ROADMAP.md)
      (daemon thread 1)  (daemon thread 2)   (daemon thread 3)
              │                 │                 │
              └─────────────────┼─────────────────┘
                                │
                                ▼
                    ToolExecutionResult 列表（保持传入顺序）
                                │
                                ▼
                    按原顺序回灌到 conversationHistory
```

**涉及的改动范围：**

| 模块 | 类 | 改动要点 |
|---|---|---|
| 基础设施 | `ToolRegistry` | 新增 `executeTools()`、`ToolInvocation`、`ToolExecutionResult` |
| ReAct | `Agent` | `executeToolCalls()` 批量调度、`printToolCalls()` 紧凑摘要、`StreamRenderer` 标题去重 |
| Plan | `PlanExecuteAgent` | 同上 + 并行任务有序渐进 flush、`TaskStreamRenderer` 标签修正 |
| Team | `SubAgent` | 同上 + `PLANNER_PROMPT` 增强 |
| 编排 | `AgentOrchestrator` | 并行步骤有序渐进 flush |
| 其他 | `Main` / `pom.xml` | 版本号 v7、surefire 插件、logback-core 版本修复 |

**HITL 兼容：** `HitlToolRegistry` 继承 `ToolRegistry`，`executeTools()` 自动获得 HITL 拦截能力，无需额外适配。

---

## 2. ToolRegistry：批量并行工具执行

### 2.1 新增数据模型

```java
// 工具调用请求 —— 来自 LLM 返回的 tool_calls 中的单条
public record ToolInvocation(String id, String name, String argumentsJson) {}

// 工具执行结果 —— 包含原始调用信息、执行结果、耗时与超时标记
public record ToolExecutionResult(String id, String name, String argumentsJson,
                                  String result, long elapsedMillis, boolean timedOut) {
    // 正常完成
    static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis);
    // 执行失败（包装异常信息）
    static ToolExecutionResult failed(ToolInvocation invocation, String message);
    // 超时取消
    static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds);
}
```

**设计意图：**
- `ToolInvocation` 把 LLM 返回的 `GLMClient.ToolCall`（LLM 专有类型）转换为 `ToolRegistry` 层的通用请求，隔离 LLM 协议与工具执行层。
- `ToolExecutionResult` 携带充足的上下文（id / name / argumentsJson），调用方可以直接用它回灌 `conversationHistory`，无需再查原始的 `ToolCall` 对象。
- `timedOut` 标记让调用方知道这个结果是超时取消的，LLM 看到"工具执行超时"文本后可以自行决定重试。

### 2.2 executeTools()：核心执行逻辑

```java
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    // 空列表 / null → 直接返回
    if (invocations == null || invocations.isEmpty()) return List.of();

    // 单条工具 → 在当前线程执行，避免线程池开销
    if (invocations.size() == 1) {
        ToolInvocation inv = invocations.get(0);
        String result = executeTool(inv.name(), inv.argumentsJson());
        return List.of(ToolExecutionResult.completed(inv, result, elapsedMillis(startedAt)));
    }

    // 多条工具 → 创建 daemon 线程池并行执行
    ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
        Thread t = new Thread(r, "paicli-tool-executor");
        t.setDaemon(true);
        return t;
    });

    // invokeAll 带超时：超时的 Future 会被 cancel
    List<Future<ToolExecutionResult>> futures =
            executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

    // 按传入顺序收集结果（超时的 Future → ToolExecutionResult.timedOut）
    for (int i = 0; i < futures.size(); i++) {
        if (future.isCancelled()) → timedOut
        else → future.get()  // 正常完成或异常
    }
}
```

**关键设计决策：**

| 决策 | 理由 |
|---|---|
| 单条不启动线程池 | 大多数 ReAct 轮次只有 1 个 tool_call，避免不必要的线程开销 |
| daemon 线程 | 工具执行线程不应阻止 JVM 退出 |
| `invokeAll` 带超时 | 防止某个工具（如 `execute_command`）长时间阻塞导致整个批次卡死 |
| 顺序返回 | 调用方按原 `tool_call` 顺序回灌 `conversationHistory`，保持消息历史中的工具结果与 LLM 返回顺序一致 |
| `MAX_PARALLEL_TOOLS = 4` | 防止工具并发数过多抢占 IO/CPU，4 是经验值 |

**超时常量：**
- `DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90`：批次总超时
- `DEFAULT_COMMAND_TIMEOUT_SECONDS = 60`：单条命令超时
- 构造器自动保证 `toolBatchTimeoutSeconds ≥ commandTimeoutSeconds + 5`

### 2.3 构造器链扩展

```java
// 旧（兼容）
ToolRegistry()                         → (60s, 90s)
ToolRegistry(long cmdTimeout)          → (cmdTimeout, max(cmdTimeout+5, 90s))

// 新（完整控制）
ToolRegistry(long cmdTimeout, long batchTimeout)
```

旧有调用方（`new ToolRegistry()` 和 `new ToolRegistry(N)`）不受影响，行为与之前完全一致。

---

## 3. Agent 层工具调度重构

### 3.1 executeToolCalls()：统一批量入口

Agent、PlanExecuteAgent、SubAgent 三个类各有一个同名方法，逻辑完全一致：

```java
private List<ToolExecutionResult> executeToolCalls(
        List<GLMClient.ToolCall> toolCalls, [int iteration | String taskId]) {

    // 1. LLM ToolCall → ToolInvocation（脱耦 LLM 类型）
    List<ToolInvocation> invocations = toolCalls.stream()
        .map(tc -> new ToolInvocation(tc.id(), tc.function().name(), tc.function().arguments()))
        .toList();

    // 2. 多个工具时打一条并行执行日志
    if (invocations.size() > 1)
        log.info("Executing {} tool calls in parallel", invocations.size());

    // 3. 委托 ToolRegistry 批量执行
    return toolRegistry.executeTools(invocations);
}
```

**在 ReAct 循环中的集成（Agent.java）：**

```java
if (response.hasToolCalls()) {
    printToolCalls(System.out, response.toolCalls());          // 紧凑摘要
    messages.add(GLMClient.Message.assistant(...));            // assistant 消息
    streamRenderer.resetBetweenIterations();                   // 重置渲染器

    List<ToolExecutionResult> toolResults =
        executeToolCalls(response.toolCalls(), iteration);     // 批量执行
    for (ToolExecutionResult r : toolResults) {
        messages.add(GLMClient.Message.tool(r.id(), r.result())); // 回灌历史
    }
    continue;
}
```

**与旧版的关键区别：**

| 旧版 | 新版 |
|---|---|
| `for` 循环逐个 `executeTool()` | `executeTools(invocations)` 批量调度 |
| 每个工具单独打印 `🔧 执行工具: xxx` + 参数 + 结果 | 执行前打印紧凑摘要（见 3.2），不再逐条打印结果 |
| 串行执行（顺序保证但慢） | 并行执行 + 顺序回灌（快且正确） |

### 3.2 printToolCalls()：紧凑工具摘要

```java
private static void printToolCalls(PrintStream out, List<GLMClient.ToolCall> toolCalls) {
    // 按工具名分组
    Map<String, List<ToolCall>> grouped = ...
    for (var group : grouped) {
        out.println("  📖 读取 2 个文件");           // toolLabel() 生成
        out.println("    └ pom.xml");                // extractKeyParam() 提取
        out.println("    └ README.md");
    }
}
```

**toolLabel() 映射表：**

| 工具名 | 中文标签 |
|---|---|
| `read_file` | 📖 读取 N 个文件 |
| `write_file` | ✏️ 写入 N 个文件 |
| `list_dir` | 📂 列出 N 个目录 |
| `execute_command` | ⚡ 执行 N 条命令 |
| `create_project` | 🏗️ 创建 N 个项目 |
| `search_code` | 🔍 搜索代码 N 次 |
| 其他 | 🔧 toolName × N |

**extractKeyParam() 提取规则：**

| 工具名 | 提取字段 |
|---|---|
| `read_file` / `write_file` / `list_dir` | `path` |
| `execute_command` | `command` |
| `create_project` | `name` |
| `search_code` | `query` |

值超过 80 字符自动截断加 `...`。

---

## 4. 流式渲染器增强

### 4.1 Agent.StreamRenderer：标题去重

**问题：** 同一轮 ReAct 运行中，LLM 可能多次返回 reasoning（工具调用前一段、工具结果回来后又一段），旧版每次 `resetBetweenIterations()` 后 reasoning 重新到达时会再打印一次「🧠 思考过程」标题，导致同一个 ReAct 回答中出现多个思考区标题，用户困惑。

**解决：** 新增 `reasoningHeadingPrinted` 标记，同一次 StreamRenderer 生命周期内只打印一次标题。

```java
private boolean reasoningHeadingPrinted;

// 所有打印标题的地方改为条件调用
private void printReasoningHeadingIfNeeded() {
    if (!reasoningHeadingPrinted) {
        System.out.println(AnsiStyle.heading("🧠 思考过程"));
        reasoningHeadingPrinted = true;
    }
}
```

**辅助改进：**

| 方法 | 用途 |
|---|---|
| `containsLineBreak(CharSequence)` | pending reasoning 不含换行 → 暂不触发标题（防空白标题） |
| `flushPendingReasoning()` | `resetBetweenIterations()` / `finish()` 中统一刷出 pending 文本（rendering 未启动但有实质内容时） |

**resetBetweenIterations() 行为变化：**

```java
// 旧版：仅 finis 活跃的 renderer
if (reasoningRenderer != null) { reasoningRenderer.finish(); reasoningRenderer = null; }

// 新版：无活跃 renderer 时也 flush pending reasoning（确保不丢内容）
if (reasoningRenderer != null) {
    reasoningRenderer.finish();
    reasoningRenderer = null;
} else {
    flushPendingReasoning();  // ← 新增
}
```

### 4.2 PlanExecuteAgent.TaskStreamRenderer：缓冲输出 + 标签修正

**改动一：标签从"任务结果"改为"任务输出"**

```java
// 旧版
out.println(AnsiStyle.section("🤖 任务结果 [" + taskId + "]"));

// 新版
out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
```

理由：LLM 在 tool-call 之前可能先 narrate 一段文本（如"我来读取 pom.xml 文件"），这段 content 不是最终结果。标"任务结果"会误导用户以为下面的内容就是答案，改为"任务输出"在 narration 和最终回答两种情况下都准确。

**改动二：pendingReasoning 缓冲（防空白标题）**

```java
// onReasoningDelta() 中：
pendingReasoning.append(delta);
if (pendingReasoning.toString().isBlank()) return;  // 纯空白暂存
// 有实质内容才触发标题打印
out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
reasoningRenderer.append(pendingReasoning.toString());
pendingReasoning.setLength(0);
```

**改动三：lateReasoning 收集**

content 开始后追加的 reasoning 不再丢弃，而是收集到 `lateReasoning` 缓冲区，在 `finish()` 或 `resetBetweenIterations()` 中通过 `flushLateReasoning()` 以「🧠 补充思考」独立展示。

**改动四：构造器新增 `PrintStream out` 参数**

```
旧：TaskStreamRenderer(String taskId, StreamState streamState)
新：TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out)
```

这是支持 Plan 并行任务的 per-task 缓冲输出（第 5 节）的基础——每个并行任务的渲染器写入独立的 `ByteArrayOutputStream` 而非共享的 `System.out`。

---

## 5. Plan 并行任务输出缓冲

### 5.1 问题背景

Plan 模式中，同批次的多个任务并行执行。如果每个任务直接写 `System.out`，多线程输出会严重交错，用户看到的内容不可读。

**解决方案：** 每个并行任务分配独立的 `ByteArrayOutputStream` 作为输出缓冲区，任务完成后按 task 顺序 flush 到 `System.out`。

### 5.2 有序渐进式 flush

**第一版问题：** 等所有任务完成后一次性 flush 所有缓冲区 → 用户长时间看不到任何输出。

**第二版问题（CompletionService 直接 flush）：** 按完成顺序 flush → task_3 输出可能出现在 task_1 前面，顺序混乱。

**最终版（有序渐进式 flush）：**

```java
int nextToFlush = 0;                    // 下一个待 flush 的位置
Set<String> completedIds = new HashSet(); // 已完成但可能暂存的 task

for (每完成一个 task) {
    completedIds.add(完成的taskId);

    // 从 nextToFlush 开始，连续 flush 所有已完成的 task
    while (nextToFlush < executableTasks.size()) {
        Task pending = executableTasks.get(nextToFlush);
        if (completedIds.contains(pending.getId())) {
            flushBuffer(pending.getId());   // 立即输出
            nextToFlush++;                  // 指针前进
        } else {
            break;  // 遇到未完成的 → 暂停，等它完成
        }
    }
}
```

**行为示例：**

| 实际完成顺序 | 展示顺序 | 用户体验 |
|---|---|---|
| task_1 → task_2 → task_3 | task_1 → task_2 → task_3 | 完美渐进 |
| task_2 → task_1 → task_3 | (等 task_1) task_1 → task_2 → task_3 | task_2 虽先完但暂存，task_1 完后一起按序刷 |
| task_1 → task_3 → task_2 | task_1 → (等 task_2) task_2 → task_3 | task_1 立即显示，task_3 暂存，task_2 完后连续刷 |

**核心保证：**
- **输出顺序永远和任务提交顺序一致**（task_1 → task_2 → task_3，不会乱序）
- **能渐进就渐进**：只要前一个 task 完成了，后续已完成的 task 立即连续 flush
- **最坏情况**：第一个 task 最慢 → 行为和旧版一样（全缓冲后一次输出），但不比旧版更差

---

## 6. AgentOrchestrator 并行步骤顺序输出

Team 模式的 `runBatchParallel()` 使用**完全相同的算法**（有序渐进式 flush），只是步骤 ID 来自 `ExecutionStep.id()` 而非 `Task.getId()`。

另外，`runBatchParallel()` 的 executor 从 `shutdown()` 改为 `shutdownNow()`，确保线程池立即释放，不留残留线程。

```java
// 每个并行任务返回 step.id() 作为完成信号
completionService.submit(() -> {
    // ... Worker 执行 + Reviewer 审查 + 重试 ...
    return step.id();
});

// 同 Plan 模式的有序渐进 flush
while (nextToFlush < batch.size()) {
    if (completedIds.contains(batch.get(nextToFlush).id())) {
        flushBuffer(batch.get(nextToFlush).id());
        nextToFlush++;
    } else break;
}
```

---

## 7. Prompts 增强

### 7.1 ReAct SYSTEM_PROMPT / Plan EXECUTION_PROMPT / SubAgent WORKER_PROMPT

新增并行工具调用指导：

```
同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。
```

**设计意图：**
- 告知 LLM 系统有并行执行能力，鼓励它在合适场景下返回多个 tool_calls
- 给具体示例（同时读取 pom.xml / README.md / ROADMAP.md），帮助 LLM 理解什么场景适合并行
- 明确"有依赖就分轮"——防止 LLM 把有依赖关系的操作放到同一轮

### 7.2 SubAgent PLANNER_PROMPT

新增规则 7-8，鼓励 Planner 生成更多可并行的步骤：

```
7. 如果多个步骤可以独立完成，不要给它们添加依赖；保持 dependencies 为空，让编排器能并行分配给多个 Worker。
   例如同时读取 pom.xml、README.md、ROADMAP.md 时，应拆成 3 个无依赖 FILE_READ 步骤。
8. 只有后一步确实需要前一步结果时，才写 dependencies。
```

**效果：** Planner 生成的步骤中，可并行的会被标记为无依赖（`dependencies: []`），编排器的波次推进算法会将它们放入同一批次并行执行。

---

## 8. 完整端到端示例

### 8.1 ReAct 模式：并行读取多个文件

```
👤 你: 读取 pom.xml、README.md、ROADMAP.md 这三个文件

🤔 思考中...

🧠 思考过程
用户需要我同时读取三个文件，它们互不依赖，我可以一次返回多个 tool_calls。

  📖 读取 3 个文件
    └ pom.xml
    └ README.md
    └ ROADMAP.md

🤖 回复
三个文件的内容如下：

## pom.xml
...
```

**关键观察：**
- 「🧠 思考过程」只出现一次（标题去重生效）
- 工具调用摘要「📖 读取 3 个文件」紧凑清晰（替代旧版三条 `🔧 执行工具: read_file`）
- 三个 `read_file` 并行执行，整体等待时间 ≈ 最慢的那个文件读取时间（而不是三个之和）

### 8.2 Plan 模式：并行任务渐进输出

```
/plan 分析 PaiCLI 项目：读取 pom.xml 了解依赖，读取 README.md 了解功能，读取 ROADMAP.md 了解规划

📋 计划摘要
   任务数: 4 | 并行批次: 2 | 当前可执行: 3 | 首批执行: task_1, task_2, task_3

🚀 开始执行计划...
⚡ 本轮并行执行 3 个任务: task_1, task_2, task_3
▶️ 并行任务 [task_1]: 读取 pom.xml
▶️ 并行任务 [task_2]: 读取 README.md
▶️ 并行任务 [task_3]: 读取 ROADMAP.md

🧠 任务思考 [task_1]
pom.xml 文件很小，直接读取即可。

  📖 读取 1 个文件
    └ pom.xml

🤖 任务输出 [task_1]
pom.xml 内容如下：...
✅ 完成 [task_1]

🧠 任务思考 [task_2]
...（task_2 和 task_3 可能稍后完成，按完成顺序渐进显示）

🤖 任务输出 [task_2]
README.md 内容如下：...
✅ 完成 [task_2]

🧠 任务思考 [task_3]
...

🤖 任务输出 [task_3]
ROADMAP.md 内容如下：...
✅ 完成 [task_3]

⚡ 本轮并行执行 1 个任务: task_4
▶️ 执行任务 [task_4]: 分析上述文件内容

🤖 任务输出 [task_4]
PaiCLI 是一个 Java 17 的终端 coding agent...
✅ 完成 [task_4]
```

**关键观察：**
- 首批 3 个读取任务并行执行，每个完成即按序显示（有序渐进 flush）
- 每个任务的 LLM 推理过程实时流式输出，工具调用也显示紧凑摘要
- 第二批（task_4）等待前三个完成后才执行（依赖满足）

---

## 9. 关键设计要点

### 9.1 为什么 ToolInvocation / ToolExecutionResult 是 record？

- Java 17 record 自动提供 `equals`/`hashCode`/`toString`，适合作为值对象
- 不可变性保证：工具执行结果不会被意外修改
- 和项目中已有的 `Tool`、`Param` 等 record 风格一致

### 9.2 为什么三个类各有一份 executeToolCalls()？

Agent、PlanExecuteAgent、SubAgent 各有一份完全相同的 `executeToolCalls()` / `printToolCalls()` / `toolLabel()` / `extractKeyParam()` 方法。

**当前选择（不抽取）：**
- 第 7 期处于开发中期，各 Agent 的工具调度逻辑可能在未来期次中分化
- 过早抽取公共方法会增加抽象层，不利于理解和调试
- 第 19 期（Prompt 分层重构）后会统一审视是否需要抽取

### 9.3 为什么单条工具不走线程池？

统计上，大多数 ReAct 轮次 LLM 只返回 1 个 tool_call。为单条工具创建线程池再立即销毁是纯浪费。`executeTools()` 中：

```java
if (invocations.size() == 1) {
    // 当前线程直接执行，零开销
}
```

### 9.4 并行上限为什么是 4？

```java
private static final int MAX_PARALLEL_TOOLS = 4;
```

- 工具以 IO 操作为主（文件读写、Shell 执行），4 并发已经能充分打满 IO
- 更多并发不会进一步提升吞吐，反而增加线程切换开销
- 这是经验值，可根据实际场景调整

### 9.5 为什么用有序渐进 flush 而不是直接 System.out？

Plan 和 Team 模式的并行任务共享 `System.out`，如果每个任务直接写，多线程输出会严重交错：

```
🧠 任务思🧠 任务思考 [task_2] 考 [task_1]       ← 不可读
```

缓冲方案保证每个任务的输出是一个完整的原子块，而有序渐进 flush 在保证顺序的同时最大化渐进反馈。

### 9.6 HITL 兼容性

`HitlToolRegistry` 继承 `ToolRegistry`，只 override 了 `executeTool()`。`executeTools()` 内部调用 `executeTool()`，因此每条工具调用仍会经过 HITL 审批。

但并行工具执行 + HITL 有一个交互细节：如果同一轮有 3 个 tool_calls，其中 2 个是 `read_file`（安全）、1 个是 `execute_command`（危险），HITL 弹窗只出现在 `execute_command` 上，其他 2 个继续并行执行。这是预期行为——`invokeAll` 的超时参数保证了即使 HITL 弹窗长时间等待用户决策，其他工具也能正常返回。

### 9.7 logback-core 版本修复

Spring Boot parent POM 4.1.0 管理了 `logback-core` 版本，与显式声明的 `logback-classic` 版本不匹配会导致 `ROLLING_FILE` appender 无法正确挂载到 root logger。

修复方法：在 `pom.xml` 中显式声明 `logback-core` 版本，与 `logback-classic` 保持一致：

```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-core</artifactId>
    <version>1.5.18</version>
</dependency>
```

---

*Chapter 7 完成。第 8 期将引入 `LlmClient` 接口抽象与多模型适配（GLM / DeepSeek / StepFun / Kimi）。*
