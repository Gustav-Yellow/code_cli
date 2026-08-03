# Chapter 5：Multi-Agent 协作开发

> 本文档整理 paicli 项目中 Multi-Agent 协作系统的核心实现，涵盖 `AgentOrchestrator`（编排器）、`SubAgent`（子代理）、`AgentRole`（角色定义）、`AgentMessage`（通信消息）四个类的架构设计、协作流程、并行策略以及完整的端到端示例。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. AgentRole：角色定义](#2-agentrole角色定义)
- [3. AgentMessage：通信消息](#3-agentmessage通信消息)
- [4. SubAgent：子代理](#4-subagent子代理)
  - [4.1 类设计与初始化](#41-类设计与初始化)
  - [4.2 三套角色系统提示词](#42-三套角色系统提示词)
  - [4.3 execute() 执行流程](#43-execute-执行流程)
  - [4.4 review() 审查专用方法](#44-review-审查专用方法)
  - [4.5 SubAgentStreamRenderer：流式渲染器](#45-subagentstreamrenderer流式渲染器)
- [5. AgentOrchestrator：编排器](#5-agentorchestrator编排器)
  - [5.1 类设计与初始化](#51-类设计与初始化)
  - [5.2 run() 编排流程](#52-run-编排流程)
  - [5.3 parsePlan()：解析规划者输出](#53-parseplan解析规划者输出)
  - [5.4 getExecutableSteps()：隐式波次推进](#54-getexecutablesteps隐式波次推进)
  - [5.5 runStep()：单步执行 + 审查 + 重试](#55-runstep单步执行--审查--重试)
  - [5.6 runBatchParallel()：并行批次执行](#56-runbatchparallel并行批次执行)
  - [5.7 parseReviewApproval() / parseReviewIssues()：审查结果解析](#57-parsereviewapproval--parsereviewissues审查结果解析)
- [6. 与 Plan-and-Execute 的对比](#6-与-plan-and-execute-的对比)
- [7. 完整端到端示例](#7-完整端到端示例)
- [8. 与记忆系统的集成](#8-与记忆系统的集成)
- [9. 关键设计要点](#9-关键设计要点)

---

## 1. 整体架构概览

PaiCLI 的 Multi-Agent 系统采用**主从架构**（Master-Worker）：一个编排器（Orchestrator）作为"主"，管理三个角色的子代理（SubAgent）作为"从"。

```
                         用户输入
                            │
                            ▼
                   ┌─────────────────┐
                   │ AgentOrchestrator│  ← "主"
                   │   (编排器)       │
                   └────────┬────────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
          ▼                 ▼                 ▼
   ┌────────────┐   ┌────────────┐   ┌────────────┐
   │ SubAgent   │   │ SubAgent   │   │ SubAgent   │
   │ (规划者)   │   │ (执行者×2) │   │ (检查者)   │
   │ PLANNER    │   │ WORKER     │   │ REVIEWER   │
   └────────────┘   └────────────┘   └────────────┘
          │                 │                 │
          └─────────────────┼─────────────────┘
                            │
                   ┌────────┴────────┐
                   │  GLMClient /    │
                   │  ToolRegistry / │
                   │  MemoryManager  │  ← 共享组件
                   └─────────────────┘
```

**核心协作流程**：

```
用户任务
  │
  ▼
规划者(Planner) ──► JSON 执行计划 [{step_1}, {step_2}, ...]
  │
  ▼
编排器 解析计划 → 按依赖顺序分配步骤
  │
  ├─► 执行者(Worker) 执行步骤 → 返回结果
  │        │
  │        ▼
  ├─► 检查者(Reviewer) 审查结果
  │        │
  │        ├─ 通过 → ✅ 步骤完成
  │        └─ 不通过 → 带反馈重试(最多2次)
  │
  ▼
汇总最终结果
```

---

## 2. AgentRole：角色定义

**源文件**：`src/main/java/com/paicli/agent/AgentRole.java`

```java
public enum AgentRole {
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;
}
```

| 角色 | 中文名 | 职责 | 是否使用工具 |
|------|--------|------|-------------|
| `PLANNER` | 规划者 | 分析任务 → 输出 JSON 执行计划 | ❌ 不调工具 |
| `WORKER` | 执行者 | 根据步骤调用工具完成操作 | ✅ 调工具 |
| `REVIEWER` | 检查者 | 审查结果 → 输出审批 JSON | ❌ 不调工具 |

**设计要点**：规划者和检查者都只做分析和输出，不需要工具调用——它们通过 `SubAgent.shouldUseTools()` 返回 `false` 来控制不下发 `tools` 参数。这减少了不必要的 token 消耗。

---

## 3. AgentMessage：通信消息

**源文件**：`src/main/java/com/paicli/agent/AgentMessage.java`

```java
public record AgentMessage(
        String fromAgent,
        AgentRole fromRole,
        String content,
        Type type
) {
    public enum Type {
        TASK,      // 主控分配给子代理的任务
        RESULT,    // 子代理返回的执行结果
        FEEDBACK,  // 检查者对结果的反馈
        APPROVAL,  // 检查者认可结果
        REJECTION, // 检查者拒绝结果
        ERROR      // 系统级错误（如 LLM 调用失败）
    }
}
```

**静态工厂方法**：

| 方法 | 用途 | type 字段 |
|------|------|-----------|
| `task(from, content)` | 编排器 → 子代理的任务 | `TASK` |
| `result(from, role, content)` | 子代理 → 编排器的结果 | `RESULT` |
| `feedback(from, content)` | 检查者反馈 | `FEEDBACK` |
| `approval(from, content)` | 检查者批准 | `APPROVAL` |
| `rejection(from, content)` | 检查者拒绝 | `REJECTION` |
| `error(from, role, content)` | 系统级错误 | `ERROR` |

---

## 4. SubAgent：子代理

**源文件**：`src/main/java/com/paicli/agent/SubAgent.java`

### 4.1 类设计与初始化

```java
public class SubAgent {
    private static final int MAX_ITERATIONS = 10;

    private final String name;                          // 实例名（如 "worker-1"）
    private final AgentRole role;                       // 角色枚举
    private final GLMClient llmClient;                  // 共享 LLM 客户端
    private final ToolRegistry toolRegistry;            // 共享工具注册表
    private final List<GLMClient.Message> conversationHistory;  // 独立对话历史

    public SubAgent(String name, AgentRole role, GLMClient llmClient, ToolRegistry toolRegistry) {
        // 初始化时立即追加一条 system 消息（角色提示词）
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(GLMClient.Message.system(getSystemPrompt()));
    }
}
```

每个 SubAgent 有**完全独立的 `conversationHistory`**——这是与 PlanExecuteAgent 中轻量 Task 执行器的最大区别。PlanExecuteAgent 的 `executeTask()` 每次新建 `List<Message>`，而 SubAgent 保持对话状态跨多次调用（通过 `clearHistory()` 重置）。

### 4.2 三套角色系统提示词

#### 规划者提示词（PLANNER_PROMPT）

```
你是一个任务规划专家。你的职责是分析用户的需求，将其拆解为清晰的执行步骤。

请按以下 JSON 格式输出执行计划：
{
    "summary": "任务摘要",
    "steps": [
        {
            "id": "step_1",
            "description": "步骤描述，要具体明确",
            "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
            "dependencies": []
        }
    ]
}

规则：
1. 每个步骤必须有唯一的 id（如 step_1, step_2）
2. dependencies 列出依赖的步骤 id
3. 步骤描述要具体，让执行者能直接理解要做什么
4. 简单任务可以只拆成 1-3 步
5. 复杂任务拆成 5-10 步
6. 不要为了凑步数引入无关操作

只输出 JSON，不要有其他内容。
```

#### 执行者提示词（WORKER_PROMPT）

```
你是一个任务执行专家。你的职责是根据给定的任务步骤，调用工具完成具体操作。

可用工具：
1. read_file - 读取文件内容
2. write_file - 写入文件内容
3. list_dir - 列出目录内容
4. execute_command - 执行命令
5. create_project - 创建项目
6. search_code - 语义检索代码库

如果任务涉及理解代码库，请优先使用 search_code 工具。
如果是 ANALYSIS 或 VERIFICATION 类型任务，请直接输出分析结果。
```

#### 检查者提示词（REVIEWER_PROMPT）

```
你是一个质量检查专家。你的职责是检查执行结果是否正确、完整和高质量。

检查要点：
1. 任务是否按要求完成
2. 结果是否正确，有无明显错误
3. 是否遗漏了重要步骤或细节
4. 输出格式是否规范

请以 JSON 格式输出检查结果：
{
    "approved": true 或 false,
    "summary": "检查摘要",
    "issues": ["问题1", "问题2"],
    "suggestions": ["建议1", "建议2"]
}

如果 approved 为 true，issues 为空即可。
如果 approved 为 false，请详细说明问题并给出改进建议。
只输出 JSON，不要有其他内容。
```

### 4.3 execute() 执行流程

```java
public AgentMessage execute(AgentMessage task, PrintStream out) {
    // 1. 将任务注入对话历史
    conversationHistory.add(GLMClient.Message.user(task.content()));

    int iteration = 0;
    while (iteration < MAX_ITERATIONS) {
        iteration++;
        // 2. 调用 LLM（Worker 带 tools，Planner/Reviewer 不带）
        GLMClient.ChatResponse response = llmClient.chat(
                conversationHistory,
                shouldUseTools() ? toolRegistry.getToolDefinitions() : null,
                streamRenderer
        );

        if (response.hasToolCalls()) {
            // 3. 工具调用路径：执行工具 → 回灌结果 → continue
            conversationHistory.add(Message.assistant(..., response.toolCalls()));
            for (ToolCall tc : response.toolCalls()) {
                String result = toolRegistry.executeTool(tc.function().name(), tc.function().arguments());
                conversationHistory.add(Message.tool(tc.id(), result));
            }
            continue;
        }

        // 4. 最终结果路径
        conversationHistory.add(Message.assistant(response.reasoningContent(), response.content()));
        streamRenderer.finish();
        return AgentMessage.result(name, role, response.content());
    }
    return AgentMessage.error(name, role, "达到最大迭代次数限制");
}
```

### 4.4 review() 审查专用方法

```java
public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
    String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
    AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
    return execute(reviewTask, out);
}
```

将原始任务描述和执行结果拼接成一个 user message 发送给 Reviewer，Reviewer 输出 `{approved, summary, issues, suggestions}` JSON。

### 4.5 SubAgentStreamRenderer：流式渲染器

SubAgent 内置了一个 `SubAgentStreamRenderer`，继承自 `GLMClient.StreamListener`，支持：

- **双通道分离**：`reasoning_content` → 「🧠 执行思考/规划思考/审查思考」，`content` → 「🤖 执行结果/规划结果/审查结果」
- **防空白标题**：reasoning 在攒够实质内容之前不触发标题打印（`pendingReasoning` 缓冲）
- **迟到推理处理**：content 开始后又收到 reasoning 时，不再渲染（终端无法回头插入）。完整 reasoning 仍通过 `response.reasoningContent()` 写入 `conversationHistory`，LLM 上下文不丢失
- **输出重定向**：通过 `PrintStream out` 参数支持写入任意流，并行模式下每个步骤写入独立 `ByteArrayOutputStream`，避免多线程输出交错

---

## 5. AgentOrchestrator：编排器

**源文件**：`src/main/java/com/paicli/agent/AgentOrchestrator.java`

### 5.1 类设计与初始化

```java
public class AgentOrchestrator {
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final GLMClient llmClient;
    private final SubAgent planner;                      // 规划者 × 1
    private final List<SubAgent> workers;                 // 执行者 × 2
    private final SubAgent reviewer;                      // 检查者 × 1
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final List<GLMClient.Message> sharedHistory;  // 会话级共享对话历史

    // 执行步骤的数据结构
    record ExecutionStep(String id, String description, String type,
                         List<String> dependencies, String result, StepStatus status) {
        // 静态工厂：pending() / withResult() / withFailed() / started()
    }

    enum StepStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
```

**构造器链**：编排器支持多种初始化方式，核心构造器接受 `sharedHistory` 实现会话上下文共享（与 ReAct/Plan 模式互通）：

```
AgentOrchestrator(apiKey)
  └─ AgentOrchestrator(apiKey, toolRegistry, memoryManager, null)     ← sharedHistory 留空
       └─ AgentOrchestrator(llmClient, toolRegistry, memoryManager, sharedHistory)
```

Main 启动时通过 `createTeamAgent(apiKey, reactAgent, sharedHistory)` 注入共享历史，确保 `/team` 执行结果写回后切回 ReAct 时上下文连续。

### 5.2 run() 编排流程

```
run(userInput)
  │
  ├─ compressContextIfNeeded(sharedHistory)     ← 压缩超预算的共享历史
  ├─ buildPriorContext(sharedHistory, 8)        ← 取最近 8 条对话供规划参考
  ├─ buildContextForQuery(userInput, 500)       ← 检索长期记忆
  ├─ sharedHistory.add(user(userInput))         ← 写共享历史
  │
  ├─ 1. 规划阶段
  │    └─ planner.execute(planMessage)
  │         └─ 输出 JSON 执行计划
  │
  ├─ 2. 解析计划 → List<ExecutionStep>
  │    └─ parsePlan(planResult.content())
  │
  ├─ 3. 执行阶段（while 循环逐批推进）
  │    ├─ getExecutableSteps() → 当前批次
  │    ├─ 单步骤 → runStep() 串行
  │    └─ 多步骤 → runBatchParallel() 并行
  │
  ├─ 4. 残留步骤提示
  │    └─ 因前置失败被跳过的 PENDING 步骤
  │
  ├─ 5. 汇总结果
  │    ├─ buildFinalResult(steps)
  │    ├─ sharedHistory.add(assistant(...))     ← 写回共享历史
  │    └─ extractAndSaveFacts(syntheticHistory) ← 提取到长期记忆
  │
  └─ return finalResult
```

### 5.3 parsePlan()：解析规划者输出

```java
List<ExecutionStep> parsePlan(String planJson) {
    String cleaned = planJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
    JsonNode root = mapper.readTree(cleaned);
    JsonNode stepsNode = root.path("steps");  // 兼容 "tasks" 字段

    // 两遍扫描：
    // 第一遍：创建步骤节点，收集 id 映射（处理 LLM 输出中的不连续 id）
    // 第二遍：回填 dependencies，通过 idMapping 重写依赖 ID
    return steps;
}
```

**关键设计**：
- 自动去除 markdown 代码块包裹
- **两遍扫描**处理前向引用（LLM 可能先输出 `dep: [step_3]`，后定义 `step_3`）
- **ID 重编号**：`idMapping` 将 LLM 输出的任意 ID 统一重写为 `step_1`, `step_2`, ... 确保内部一致性
- 兼容 `steps` 和 `tasks` 两种 JSON 键名（后者兼容 Plan-and-Execute 格式）

### 5.4 getExecutableSteps()：隐式波次推进

```java
List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
    // 构建状态映射
    Map<String, StepStatus> statusMap = ...;

    return steps.stream()
            .filter(step -> step.status() == StepStatus.PENDING)
            .filter(step -> step.dependencies().stream()
                    .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
            .toList();
}
```

**与 PlanExecuteAgent 的拓扑排序对比**：

| | PlanExecuteAgent | AgentOrchestrator |
|---|---|---|
| 拓扑计算 | 显式预计算（建 DAG 时 DFS 拓扑排序） | 隐式（while 循环自然分层） |
| 环检测 | ✅ 主动报错 | ❌ 静默跳过，残留 PENDING |
| 批次内顺序 | 拓扑序保证确定性 | LLM 输出 JSON 数组顺序 |
| 适用场景 | Planner 是确定性的 Java 代码 | Planner 是 LLM，输出格式不可完全信任 |

while 循环天然模拟了 BFS 拓扑分层——每轮重新扫描，能跑的就跑，不能跑的下轮再检查。

### 5.5 runStep()：单步执行 + 审查 + 重试

```java
private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                     Map<String, Integer> retryCount,
                     SubAgent worker, SubAgent reviewer, String context, PrintStream out) {
    // 1. Worker 执行
    AgentMessage result = worker.executeWithContext(taskMsg, context, out);

    // 2. Reviewer 审查
    AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);

    // 3. 解析审批结果
    boolean approved = parseReviewApproval(reviewResult.content());

    if (approved) {
        updateStep(steps, step.id(), step.withResult(result.content()));
        return;
    }

    // 4. 不通过 → 重试循环（最多 MAX_RETRIES_PER_STEP = 2 次）
    while (!approved && retries < MAX_RETRIES_PER_STEP) {
        retries++;
        String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
        AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
        // 再次审查...
    }
}
```

**重试机制**：每次重试时把 Reviewer 反馈注入到 Worker 的上下文中，Worker 能基于反馈改进。最多 2 次重试后保留当前结果，不阻塞后续步骤。

### 5.6 runBatchParallel()：并行批次执行

```java
private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                              Map<String, Integer> retryCount) {
    int parallelism = Math.min(batch.size(), workers.size());
    ExecutorService executor = Executors.newFixedThreadPool(parallelism);
    BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);

    for (ExecutionStep step : batch) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();   // 独立输出缓冲
        PrintStream stepOut = new PrintStream(baos, true, UTF_8);

        executor.submit(() -> {
            SubAgent worker = workerPool.take();                    // 池化获取 Worker
            SubAgent localReviewer = new SubAgent(...);             // 独立 Reviewer
            try {
                runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
            } finally {
                worker.clearHistory();
                workerPool.offer(worker);                           // 归还 Worker
            }
        });
    }

    // 等待全部完成 → 按 step_id 顺序 flush 缓冲区
    for (Future<?> f : futures) f.get();
    for (ExecutionStep step : batch) {
        System.out.print(buffers.get(step.id()).toString(UTF_8));
    }
}
```

**三层并发安全**：

| 层 | 机制 | 解决的问题 |
|----|------|-----------|
| 线程池 | `FixedThreadPool(parallelism)` | 真正 JVM 多线程并发 |
| Worker 池 | `BlockingQueue.take()/offer()` | SubAgent 对话历史隔离，防止并发污染 |
| 输出缓冲 | `ByteArrayOutputStream` + 事后按序 flush | 多线程输出不交错 |

### 5.7 parseReviewApproval() / parseReviewIssues()：审查结果解析

**审批解析**（`parseReviewApproval`）：

```java
boolean parseReviewApproval(String reviewContent) {
    // 1. 优先 JSON 解析：取 "approved" 字段
    // 2. JSON 解析失败时走关键词回退：
    //    - 含"未通过"/"不通过"/"不合格"等否定词 → 不通过
    //    - 无否定词但无肯定词 → 保守策略，默认不通过
    //    - 有肯定词无否定词 → 通过
}
```

**问题解析**（`parseReviewIssues`）：

```java
String parseReviewIssues(String reviewContent) {
    // 1. 优先取 "issues" 数组
    // 2. 其次取 "suggestions" 数组
    // 3. 兜底取 "summary" 字段
    // 4. 都失败返回 "审查未通过，请改进执行结果"
}
```

**保守策略**：Reviewer 输出无法解析时默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。这是 Multi-Agent 质量保证的最后一道防线。

---

## 6. 与 Plan-and-Execute 的对比

| 维度 | PlanExecuteAgent | AgentOrchestrator |
|------|-----------------|-------------------|
| **谁来干活** | Agent 自己调 LLM + 工具（轻量 Task 执行器） | 委派给完整 SubAgent（独立角色 + 对话历史） |
| **规划方式** | `Planner`（Java 代码驱动的 LLM 分解） | SubAgent(PLANNER)（角色化 LLM 分解） |
| **审查环节** | ❌ 无 Reviewer，产出直接算完成 | ✅ Reviewer 审查 → 通过/重试 |
| **重试机制** | 失败 + 进度 < 50% → 整体 replan | 单步最多重试 2 次 → 超限保留结果 |
| **并行模型** | `ExecutorService` 每次新建线程池 | `BlockingQueue` Worker 池化复用 |
| **上下文管理** | 每次 Task 新建 `List<Message>` | SubAgent 维护独立对话历史，`clearHistory()` 重置 |
| **拓扑排序** | 显式 DFS 预计算 | 隐式 while 循环波次推进 |
| **流式渲染** | `TaskStreamRenderer`（仅 content 通道） | `SubAgentStreamRenderer`（reasoning + content 双通道 + 角色标签） |
| **对话历史** | 写入 `sharedHistory` | 同样写入 `sharedHistory`（v5.0.1 对齐） |
| **长期记忆** | `extractAndSaveFacts` | 同样 `extractAndSaveFacts`（v5.0.1 对齐） |

**核心差异总结**：AgentOrchestrator 是在 PlanExecuteAgent 的基础上**加了三样东西**——完整 SubAgent 代理模型、Reviewer 审查重试环、BlockingQueue Worker 池化。可以说是「Plan-and-Execute 的多 Agent 增强版」。

---

## 7. 完整端到端示例

### 7.1 场景

用户输入：**"创建一个 Spring Boot 项目命名为 demo，写一个 HelloController，然后验证项目结构"**

### 7.2 第一阶段：规划

```
📋 第一阶段：规划
🧑‍💼 规划者正在分析任务...

🧠 规划思考 [planner]
(LLM 分析任务 → 输出 JSON 计划)

🤖 规划结果 [planner]
{
    "summary": "创建 Spring Boot 项目 demo，编写 HelloController 并验证",
    "steps": [
        {"id": "step_1", "description": "创建 pom.xml...", "dependencies": []},
        {"id": "step_2", "description": "创建主启动类...", "dependencies": ["step_1"]},
        {"id": "step_3", "description": "创建 HelloController...", "dependencies": ["step_2"]},
        {"id": "step_4", "description": "创建单元测试...", "dependencies": ["step_3"]},
        {"id": "step_5", "description": "验证项目结构...", "dependencies": ["step_4"]},
        {"id": "step_6", "description": "执行 mvn test...", "dependencies": ["step_5"]}
    ]
}
```

### 7.3 第二阶段：执行

步骤有线性依赖（每个步骤依赖前一个），所以逐步骤串行执行：

```
⚡ 第二阶段：执行

🛠️ worker-1 执行步骤 [step_1]: 创建 pom.xml...
🧠 执行思考 [worker-1]
🤖 执行结果 [worker-1]
(创建 pom.xml 成功)
🔍 reviewer 正在审查...
🧠 审查思考 [reviewer]
🤖 审查结果 [reviewer]
✅ 步骤 [step_1] 审查通过

🛠️ worker-2 执行步骤 [step_2]: 创建主启动类...
🧠 执行思考 [worker-2]
🤖 执行结果 [worker-2]
🔍 reviewer 正在审查...
⚠️ 审查未通过：未展示实际的 Java 源代码内容
   反馈: 请补充完整的源代码...
🛠️ worker-2 重新执行...
🤖 执行结果 [worker-2]
(补充了完整代码)
🔍 reviewer 再次审查...
✅ 步骤 [step_2] 重试后审查通过

... (step_3 ~ step_6 类似)
```

### 7.4 并行场景

假设规划者输出了两个互不依赖的步骤：

```json
{"steps": [
    {"id": "step_1", "description": "创建 pom.xml", "dependencies": []},
    {"id": "step_2", "description": "创建 README.md", "dependencies": []}
]}
```

此时两个步骤并行执行：

```
⚡ 批次 #1：2 个独立步骤并行执行（最多 2 个并发 Worker）

Thread-1: worker-1 执行 step_1  →  输出 buffer_1
Thread-2: worker-2 执行 step_2  →  输出 buffer_2

[主线程等待全部完成]
→ flush buffer_1 (step_1 先)
→ flush buffer_2 (step_2 后)
```

---

## 8. 与记忆系统的集成

AgentOrchestrator 与记忆系统的集成遵循与 ReAct / Plan 模式一致的约定：

### 短期记忆（sharedHistory）

```java
// run() 开头：
memoryManager.compressContextIfNeeded(sharedHistory);  // 压缩超预算历史
String priorContext = buildPriorContext(sharedHistory, 8);  // 最近 8 条供规划参考
sharedHistory.add(GLMClient.Message.user(userInput));  // 写入用户消息

// run() 结尾：
sharedHistory.add(GLMClient.Message.assistant(finalResult));  // 写回 Multi-Agent 结果
```

**效果**：`/team` 执行完后切回 ReAct 模式时，Agent 能看到刚才 Multi-Agent 做了什么。

### 长期记忆（MemoryManager）

```java
// 检索长期记忆，注入到规划消息中
String memoryContext = memoryManager.buildContextForQuery(userInput, 500);

// 执行完成后，提取关键事实
List<GLMClient.Message> syntheticHistory = new ArrayList<>();
syntheticHistory.add(GLMClient.Message.user(userInput));
syntheticHistory.add(GLMClient.Message.assistant("[多Agent结果] " + finalResult));
memoryManager.extractAndSaveFacts(syntheticHistory);
```

### 三种模式的记忆链路

```
Main.java 启动
  │
  ├─ sharedMemory = new MemoryManager(...)     ← 单例
  ├─ sharedHistory = new ArrayList<>()          ← 单例
  │
  ├─ ReAct 模式:   Agent(apiKey, sharedHistory, sharedMemory)
  ├─ Plan 模式:    PlanExecuteAgent(apiKey, ..., sharedHistory, sharedMemory)
  └─ Team 模式:    AgentOrchestrator(apiKey, ..., sharedMemory, sharedHistory)
                        │
                        └─ 复用 reactAgent.getMemoryManager()
                           复用 reactAgent.getToolRegistry()
```

三种模式共享同一个 `sharedHistory` 和 `sharedMemory`，模式切换时对话连续、长期记忆互通。

---

## 9. 关键设计要点

### 9.1 主从架构 + 角色分工

每个 SubAgent 有独立的角色、系统提示词、对话历史，但共享 LLM 客户端和工具注册表。这种设计让：
- **规划者**专注于任务分解（不调工具，节省 token）
- **执行者**专注于工具调用（有完整工具列表，支持多轮 ReAct 循环）
- **检查者**专注于质量把控（严格 JSON 输出，保守解析策略）

### 9.2 隐式拓扑排序

编排器没有显式实现拓扑排序，而是通过 `while(true) + getExecutableSteps()` 模拟波次推进。每轮重新扫描状态表，能跑的就跑。这种设计的优点是简单，缺点是没有环检测和确定性批次内顺序。

### 9.3 Worker 池化（BlockingQueue）

SubAgent 有状态（`conversationHistory`），不能两个线程同时修改。`BlockingQueue<SubAgent>` 确保同一时刻一个 Worker 只被一个线程持有，用完 `clearHistory()` 后归还。

### 9.4 输出缓冲 + 事后排序

并行执行时，每个步骤写入独立 `ByteArrayOutputStream`。全部完成后按 `step_id` 顺序 flush 到 stdout。这保证了用户看到的输出有序，但步骤实际是并发执行的。

### 9.5 独立的 Reviewer 实例

并行批次中，每个步骤在 `executor.submit()` 内部创建独立的 `new SubAgent("reviewer-" + step.id(), ...)`。这是因为 Reviewer 也有对话历史——共享实例会导致审查交叉污染。

### 9.6 保守的审查策略

`parseReviewApproval()` 在 JSON 解析失败时采用保守策略：除非同时满足"不含否定关键词"且"含有肯定关键词"，否则默认判为不通过。这是 Multi-Agent 质量保证的最后防线。

### 9.7 重试时上下文注入

重试时把 Reviewer 的反馈拼入上下文：`context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues`。Worker 能基于改进建议重新执行，提高重试成功率。

### 9.8 流式渲染的角色标签

`SubAgentStreamRenderer` 根据角色显示不同标签：

| 角色 | 思考标签 | 结果标签 |
|------|---------|---------|
| PLANNER | 🧠 规划思考 | 🤖 规划结果 |
| WORKER | 🧠 执行思考 | 🤖 执行结果 |
| REVIEWER | 🧠 审查思考 | 🤖 审查结果 |

### 9.9 迟到推理的处理

LLM（DeepSeek）有时在 content 开始后才追加 reasoning chunk。`SubAgentStreamRenderer` 的 `finish()` 不渲染迟到推理——因为 content 已写到终端，无法回头插入。完整 reasoning 仍通过 `response.reasoningContent()` 写入 `conversationHistory`，LLM 上下文不丢失。

---

## 附录：文件清单

| 文件 | 职责 |
|------|------|
| `src/main/java/com/paicli/agent/AgentRole.java` | 角色枚举（PLANNER / WORKER / REVIEWER） |
| `src/main/java/com/paicli/agent/AgentMessage.java` | 通信消息 record（6 种类型 + 静态工厂） |
| `src/main/java/com/paicli/agent/SubAgent.java` | 子代理（独立角色 + 对话历史 + 流式渲染器） |
| `src/main/java/com/paicli/agent/AgentOrchestrator.java` | 编排器（主从架构 + 波次推进 + 并行批次） |
| `src/main/java/com/paicli/cli/CliCommandParser.java` | 命令解析（新增 `/team` 命令） |
| `src/main/java/com/paicli/cli/Main.java` | CLI 入口（新增 `/team` 模式路由 + sharedHistory 注入） |

---

> 本文档基于 paicli 项目 Multi-Agent 协作系统的当前代码整理，后续代码演进时请同步更新。
