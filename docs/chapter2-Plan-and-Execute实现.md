# Chapter 2：Plan-and-Execute 实现

> 本文档整理 paicli 项目中 Plan-and-Execute 模式的核心实现，涵盖 `PlanExecuteAgent`（编排入口）、`Planner`（LLM 任务分解）、`ExecutionPlan`（DAG 与拓扑排序）、`Task`（任务节点）四个类的设计、协作流程，以及如何复用第 1 期的 `GLMClient` 和 `ToolRegistry` 来执行每个子任务。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. 模式路由：CLI 层控制](#2-模式路由cli-层控制)
  - [2.1 Main.java 的 REPL 循环](#21-mainjava-的-repl-循环)
  - [2.2 /plan 命令与 nextTaskUsePlanMode 标记](#22-plan-命令与-nexttaskuseplanmode-标记)
- [3. Planner：LLM 生成任务 DAG](#3-plannerllm-生成任务-dag)
  - [3.1 PLANNING_PROMPT 设计](#31-planning_prompt-设计)
  - [3.2 两遍扫描解析 JSON](#32-两遍扫描解析-json)
  - [3.3 idMapping：重写 LLM 给的 id](#33-idmapping重写-llm-给的-id)
  - [3.4 replan：失败后的重规划](#34-replan失败后的重规划)
- [4. ExecutionPlan：DAG 与拓扑排序](#4-executionplandag-与拓扑排序)
  - [4.1 数据结构设计](#41-数据结构设计)
  - [4.2 三色 DFS 拓扑排序](#42-三色-dfs-拓扑排序)
  - [4.3 执行批次计算](#43-执行批次计算)
  - [4.4 可视化与进度追踪](#44-可视化与进度追踪)
- [5. Task：任务节点与状态机](#5-task任务节点与状态机)
  - [5.1 双向依赖链](#51-双向依赖链)
  - [5.2 状态机设计](#52-状态机设计)
  - [5.3 运行时校验：isExecutable()](#53-运行时校验isexecutable)
- [6. 计划审查：HITL 交互](#6-计划审查hitl-交互)
  - [6.1 PlanReviewHandler 接口与依赖反转](#61-planreviewhandler-接口与依赖反转)
  - [6.2 三种审查决策](#62-三种审查决策)
  - [6.3 CLI 层的交互式实现](#63-cli-层的交互式实现)
  - [6.4 补充要求后的重新规划](#64-补充要求后的重新规划)
- [7. 分批并发执行](#7-分批并发执行)
  - [7.1 while 循环 vs 旧版 for 循环](#71-while-循环-vs-旧版-for-循环)
  - [7.2 getExecutableTasksInOrder：按拓扑序过滤可执行任务](#72-getexecutabletasksinorder按拓扑序过滤可执行任务)
  - [7.3 executeTaskBatch：单任务 vs 多任务](#73-executetaskbatch单任务-vs-多任务)
  - [7.4 并发执行案例](#74-并发执行案例)
  - [7.5 僵局检测](#75-僵局检测)
- [8. JLine 终端控制](#8-jline-终端控制)
  - [8.1 raw mode 单键读取](#81-raw-mode-单键读取)
  - [8.2 ESC 序列 draining](#82-esc-序列-draining)
  - [8.3 括号粘贴处理](#83-括号粘贴处理)
  - [8.4 prefill 机制：首字符预读](#84-prefill-机制首字符预读)
  - [8.5 readInputBurst：三阶段超时策略](#85-readinputburst三阶段超时策略)
- [9. executeTask：单个 Task 的 LLM + 工具调用](#9-executetask单个-task-的-llm--工具调用)
  - [9.1 组装 prompt](#91-组装-prompt)
  - [9.2 调用 LLM 与工具](#92-调用-llm-与工具)
  - [9.3 buildTaskContext：依赖结果注入](#93-buildtaskcontext依赖结果注入)
- [10. 完整端到端示例](#10-完整端到端示例)
  - [10.1 场景](#101-场景)
  - [10.2 阶段 1：CLI 模式路由](#102-阶段-1cli-模式路由)
  - [10.3 阶段 2：LLM 生成计划 JSON](#103-阶段-2llm-生成计划-json)
  - [10.4 阶段 3：parsePlan 两遍解析 + 拓扑排序](#104-阶段-3parseplan-两遍解析--拓扑排序)
  - [10.5 阶段 4：计划审查与执行](#105-阶段-4计划审查与执行)
  - [10.6 阶段 5：汇总结果](#106-阶段-5汇总结果)
- [11. 核心问题解答](#11-核心问题解答)
  - [11.1 executionOrder 的顺序是什么？](#111-executionorder-的顺序是什么)
  - [11.2 阻塞是如何实现的？](#112-阻塞是如何实现的)
  - [11.3 reviewHandler.review() 是怎么实现的？](#113-reviewhandlerreview-是怎么实现的)
  - [11.4 为什么 ESC 的判断是 key == 27？](#114-为什么-esc-的判断是-key--27)
  - [11.5 batch 执行实现了并行吗？](#115-batch-执行实现了并行吗)
- [12. 关键设计要点](#12-关键设计要点)
- [13. 与第 1 期 ReAct 的对比](#13-与第-1-期-react-的对比)

---

## 1. 整体架构概览

第 2 期新增了 7 个类（`com.paicli.plan.*` + `com.paicli.agent.PlanExecuteAgent` + CLI 辅助类），并大幅修改了 `Main.java` 支持模式切换和交互式审查。新架构在第 1 期的 `GLMClient` 和 `ToolRegistry` 之上增加了一个**规划层 + 交互层**：

```
                         用户输入
                            │
                            ▼
                   ┌─────────────────┐
                   │  Main.java      │  REPL 循环 + JLine 终端 + /plan 命令路由
                   │  (CLI REPL)     │  └─ PlanReviewHandler（交互式审查）
                   └────────┬────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
     ┌─────────────────┐        ┌──────────────────────┐
     │  Agent.java      │        │  PlanExecuteAgent    │
     │  (ReAct 循环)    │        │  (Plan-and-Execute)  │
     │  [第 1 期]       │        │  [第 2 期新增]        │
     └────────┬────────┘        └──────────┬───────────┘
              │                            │
              │                   ┌────────┴────────┐
              │                   │                 │
              │                   ▼                 ▼
              │            ┌──────────┐      ┌──────────────┐
              │            │ Planner  │      │ExecutionPlan │
              │            │(LLM分解) │      │(DAG+拓扑排序) │
              │            └────┬─────┘      └──────────────┘
              │                 │
              ▼                 ▼
     ┌─────────────────────────────────────┐
     │          GLMClient (LLM HTTP)        │  ← 第 1 期，两模式共用
     └─────────────────────────────────────┘
                            │
                            ▼
     ┌─────────────────────────────────────┐
     │       ToolRegistry (5 个工具)        │  ← 第 1 期，两模式共用
     └─────────────────────────────────────┘
```

**关键关系**：Plan 模式不是替代 ReAct，而是**在 ReAct 之上加了一层规划 + HITL 交互**。每个 Task 的执行本质上还是"LLM 决定调哪个工具 → ToolRegistry 执行 → 返回结果"，和 Agent.run() 做的事一样。

---

## 2. 模式路由：CLI 层控制

### 2.1 Main.java 的 REPL 循环

`Main.java` 的 `main()` 方法启动一个 JLine 驱动的 REPL 循环，核心逻辑如下：

```java
while (true) {
    PromptInput promptInput = readPromptInput(terminal, lineReader, nextTaskUsePlanMode);

    CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
    switch (command.type()) {
        case SWITCH_PLAN -> {
            // /plan 或 /plan <content>
            if (command.payload() == null) {
                nextTaskUsePlanMode = true;  // 标记下一条输入走 Plan 模式
            } else {
                input = command.payload();  // 直接走 Plan 模式执行
            }
        }
        // ... 其他命令
    }

    // 模式路由
    if (nextTaskUsePlanMode || command.type() == SWITCH_PLAN) {
        PlanExecuteAgent planAgent = createPlanAgent(apiKey, terminal, lineReader);
        response = planAgent.run(input);
        nextTaskUsePlanMode = false;  // 执行完毕后回到 ReAct
    } else {
        response = reactAgent.run(input);
    }
}
```

### 2.2 /plan 命令与 nextTaskUsePlanMode 标记

- `/plan`（无参数）：设置 `nextTaskUsePlanMode = true`，下一条输入走 Plan 模式。用户可以在输入前按 ESC 取消，执行完成后自动回到 ReAct。
- `/plan 任务内容`：直接走 Plan 模式执行该内容。
- **模式路由由 CLI 层负责**，不再在 Agent 内部用 `shouldPlan()` 启发式判断。

---

## 3. Planner：LLM 生成任务 DAG

文件：`src/main/java/com/paicli/plan/Planner.java`

### 3.1 PLANNING_PROMPT 设计

Planner 用一段精心设计的 system prompt 让 LLM 按固定 JSON 格式输出任务 DAG：

```
你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。

可用任务类型：
- FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION

请按以下JSON格式输出执行计划：
{
    "summary": "任务摘要",
    "tasks": [
        {
            "id": "task_1",
            "description": "任务描述",
            "type": "FILE_READ",
            "dependencies": []
        },
        {
            "id": "task_2",
            "description": "任务描述",
            "type": "FILE_WRITE",
            "dependencies": ["task_1"]
        }
    ]
}
```

注意：`createPlan()` 调用 `llmClient.chat(messages, null)` 时 **tools=null**——规划阶段不需要工具调用，LLM 直接输出 JSON 文本。

### 3.2 两遍扫描解析 JSON

`parsePlan()` 是核心解析逻辑，之所以需要两遍扫描，是因为 **LLM 输出的 JSON 数组中可能存在前向引用**：

```json
{
    "tasks": [
        {"id": "task_3", "dependencies": ["task_5"]},  // task_5 还没出现！
        {"id": "task_5", "dependencies": []}
    ]
}
```

**第一遍（建节点，忽略依赖）**：

```java
for (JsonNode taskNode : tasksNode) {
    String originalId = taskNode.path("id").asText();
    String newId = "task_" + taskIndex++;        // 重写 id
    idMapping.put(originalId, newId);

    Task task = new Task(newId, description, type);
    plan.addTask(task);                           // 不处理 dependencies
}
```

**第二遍（回填依赖）**：

```java
for (JsonNode taskNode : tasksNode) {
    Task task = plan.getTask(newId);
    for (JsonNode depNode : taskNode.path("dependencies")) {
        String newDepId = idMapping.getOrDefault(depNode.asText(), ...);
        task.addDependency(newDepId);
    }
}
```

**回填双向链**：最后遍历所有 Task，为每个 `addDependency` 的关系在另一端补 `addDependent`。

**拓扑校验**：`plan.computeExecutionOrder()` → 有环则抛 `IOException("计划中存在循环依赖")`。

### 3.3 idMapping：重写 LLM 给的 id

LLM 输出的 id 不可靠（可能重复、格式奇怪），所以统一重写：

```
LLM 输出               →   重写后（内部使用）
task_1                       task_1
task_3, deps: [task_5]       task_2, deps: [task_3]
task_5                        task_3
```

`idMapping` 记录映射关系，解析依赖时通过 `idMapping.getOrDefault()` 转换。

### 3.4 replan：失败后的重规划

当执行过程中某个 Task 失败且进度不足 50% 时，触发 replan：

```java
public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) {
    // 组装上下文：原目标 + 失败原因 + 已完成任务列表
    StringBuilder context = new StringBuilder();
    context.append("原任务: ").append(failedPlan.getGoal());
    context.append("失败原因: ").append(failureReason);
    for (Task task : failedPlan.getAllTasks()) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            context.append("- ").append(task.getId())
                   .append(": ").append(task.getDescription());
        }
    }
    return createPlan(context.toString());  // 重新调 LLM 生成计划
}
```

这样 LLM 知道哪些工作已经完成，只需要规划剩余部分。

---

## 4. ExecutionPlan：DAG 与拓扑排序

文件：`src/main/java/com/pacicli/plan/ExecutionPlan.java`

### 4.1 数据结构设计

```java
private final Map<String, Task> tasks;          // LinkedHashMap 保持插入顺序
private final List<String> executionOrder;      // DFS 后序遍历的结果
```

- `LinkedHashMap`：保持 LLM JSON 中任务的原始顺序，让 `visualize()` 输出稳定
- `executionOrder`：拓扑排序的结果列表，被依赖的节点排在前面，依赖别人的节点排在后面

### 4.2 三色 DFS 拓扑排序

这是整个 Phase 2 中最精巧的算法。用两个 `HashSet` 模拟三种颜色：

| 颜色 | 含义 | Set 中的位置 |
|---|---|---|
| 白色 | 未访问 | 不在 visited 也不在 visiting |
| 灰色 | 正在递归栈中 | 在 visiting 中 |
| 黑色 | 已完成 | 在 visited 中 |

```java
public boolean computeExecutionOrder() {
    executionOrder.clear();
    Set<String> visited = new HashSet<>();   // 黑色
    Set<String> visiting = new HashSet<>();  // 灰色

    for (Task task : tasks.values()) {
        if (!visited.contains(task.getId())) {
            if (!topologicalSort(task, visited, visiting)) {
                return false;  // 有环！
            }
        }
    }
    return true;
}

private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
    String id = task.getId();

    if (visiting.contains(id)) return false;     // 再次遇到灰色 → 后向边 → 有环
    if (visited.contains(id))  return true;       // 黑色 → 已处理

    visiting.add(id);                             // 入栈 → 灰色

    for (String depId : task.getDependencies()) {
        Task dep = tasks.get(depId);
        if (dep != null) {
            if (!topologicalSort(dep, visited, visiting)) return false;
        }
    }

    visiting.remove(id);                          // 出栈 → 黑色
    visited.add(id);
    executionOrder.add(id);                       // 后序加入
    return true;
}
```

**环检测原理**：DFS 沿 dependencies 边向下深入。如果在递归栈中再次遇到同一个节点（visiting 中已有），说明存在一条从该节点出发又回到该节点的路径 → 后向边 → 有环。

**为什么是后序遍历**：递归返回后才 `add(id)`，保证每个节点的所有前置依赖先于它加入列表。例如 task_1 ← task_2 ← task_3（task_3 依赖 task_2，task_2 依赖 task_1）的链：

```
DFS(task_3) → deps=[task_2] → DFS(task_2) → deps=[task_1] → DFS(task_1)
                                                                  task_1 无依赖 → 先加入
                                                             task_2 加入（task_1 已在列表中）
                                                        task_3 加入（task_1, task_2 已在列表中）
最终 executionOrder = [task_1, task_2, task_3]
执行时从左到右就是安全的。
```

### 4.3 执行批次计算

`getExecutionBatches()` 模拟执行过程，计算 DAG 可以分成几批并发执行：

```java
public List<List<Task>> getExecutionBatches() {
    Map<String, Task> remaining = new LinkedHashMap<>(tasks);
    Set<String> completed = new HashSet<>();
    List<List<Task>> batches = new ArrayList<>();

    while (!remaining.isEmpty()) {
        // 当前可执行的任务：所有依赖都已完成
        List<Task> batch = remaining.values().stream()
                .filter(task -> completed.containsAll(task.getDependencies()))
                .toList();

        if (batch.isEmpty()) break;

        batches.add(batch);
        // 标记这一批为已完成，继续下一轮
        for (Task task : batch) {
            remaining.remove(task.getId());
            completed.add(task.getId());
        }
    }

    return batches;
}
```

例如一个 DAG：
```
task_1 (无依赖)
task_2 (无依赖)
task_3 (依赖 task_1)
task_4 (依赖 task_1, task_2)
task_5 (依赖 task_3, task_4)
```

执行批次为：
- **第 1 批**：`[task_1, task_2]`（无依赖，可并发）
- **第 2 批**：`[task_3]`（依赖 task_1 已完成）
- **第 3 批**：`[task_4]`（依赖 task_1, task_2 已完成）
- **第 4 批**：`[task_5]`（依赖 task_3, task_4 已完成）

### 4.4 可视化与进度追踪

**`visualize()`** 在执行前打印 ASCII 表格，让用户预览完整计划：

```
╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个 Java Web 项目                       ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1              [FILE_READ ] 依赖: 无       ║
║     读取当前目录结构                                    ║
║  2. ⏳ task_2              [COMMAND   ] 依赖: task_1   ║
║     使用 Maven 创建项目骨架                             ║
║  ...
╚══════════════════════════════════════════════════════════╝
   进度: 0% | 状态: CREATED
```

**`summarize()`** 提供折叠摘要，避免完整 DAG 占满终端：

```
📋 计划摘要
   - 目标: 创建一个 Java Web 项目
   - 任务数: 5 | 并行批次: 3 | 当前可执行: 2 | 状态: CREATED
   - 首批执行: task_1, task_2
   - 最终收敛: task_5
```

`getProgress()` 返回 0.0-1.0，被 `executePlan()` 用于判断失败时是否值得触发 replan。

---

## 5. Task：任务节点与状态机

文件：`src/main/java/com/paicli/plan/Task.java`

### 5.1 双向依赖链

```java
private final List<String> dependencies;  // 我依赖谁（前置任务）
private final List<String> dependents;    // 谁依赖我（后继任务）
```

双向链的设计意图：
- `dependencies` 方向 → 用于拓扑排序（沿边向下 DFS）和 `isExecutable()` 校验
- `dependents` 方向 → 用于 `buildFinalResult()` 找叶子节点（没有后继的任务才是最终产出）

### 5.2 状态机设计

```
PENDING ──markStarted()──► RUNNING ──markCompleted()──► COMPLETED
   │                         │
   │                         └───markFailed()──────────► FAILED
   └───────────────────────────────────────────────────► SKIPPED
```

状态一旦离开 PENDING 就不会回去，保证每个 Task 只执行一次。

### 5.3 运行时校验：isExecutable()

```java
public boolean isExecutable(Map<String, Task> allTasks) {
    if (status != TaskStatus.PENDING) return false;
    for (String depId : dependencies) {
        Task dep = allTasks.get(depId);
        if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
            return false;
        }
    }
    return true;
}
```

**为什么拓扑排序之后还要运行时校验？** 拓扑排序是建图时的一次性静态分析，运行时情况会变——前置任务可能 FAILED（而非 COMPLETED），此时后继任务就不该执行。拓扑序告诉你"可以按这个顺序"，`isExecutable()` 告诉你"此刻真的可以"。

---

## 6. 计划审查：HITL 交互

文件：`src/main/java/com/paicli/agent/PlanExecuteAgent.java` + `src/main/java/com/paicli/cli/Main.java`

### 6.1 PlanReviewHandler 接口与依赖反转

```java
public interface PlanReviewHandler {
    PlanReviewDecision review(String goal, ExecutionPlan plan);
}
```

这是一个**依赖反转设计**：`PlanExecuteAgent` 只定义接口，CLI 层（`Main.java`）实现具体的终端交互逻辑。默认实现是直接执行（无审查）。

### 6.2 三种审查决策

```java
public enum PlanReviewAction {
    EXECUTE,      // 执行当前计划
    SUPPLEMENT,   // 补充要求，重新规划
    CANCEL        // 取消本次计划
}

public record PlanReviewDecision(PlanReviewAction action, String feedback) {
    static PlanReviewDecision execute() { ... }
    static PlanReviewDecision supplement(String feedback) { ... }
    static PlanReviewDecision cancel() { ... }
}
```

### 6.3 CLI 层的交互式实现

`createPlanReviewHandler()` 返回一个 lambda，实现终端交互：

```java
PlanReviewHandler handler = (goal, plan) -> {
    boolean expanded = false;
    System.out.println(plan.summarize());
    System.out.println("📝 计划已生成。");
    System.out.println("   - 回车：按当前计划执行");
    System.out.println("   - Ctrl+O：展开完整计划");
    System.out.println("   - ESC：折叠或取消本次计划");
    System.out.println("   - I：输入补充要求后重新规划\n");

    while (true) {
        Integer key = readSingleKeyFromTerminal(terminal);

        if (key == '\n' || key == '\r') {
            return PlanReviewDecision.execute();
        }

        if (key == 27) {  // ESC
            if (expanded) {
                expanded = false;
                System.out.println(plan.summarize());
                continue;
            }
            return PlanReviewDecision.cancel();
        }

        if (key == 'i' || key == 'I') {
            String supplement = lineReader.readLine("补充> ").trim();
            return PlanReviewDecision.supplement(supplement);
        }

        if (key == CTRL_O) {  // Ctrl+O = 15
            System.out.println(plan.visualize());
            expanded = true;
            continue;
        }
    }
};
```

**交互流程**：
1. 计划生成后打印摘要，显示四种操作
2. 阻塞等待用户按键（`readSingleKeyFromTerminal`）
3. 根据按键返回对应的 `PlanReviewDecision`
4. ESC 有双重语义：expanded=true 时折叠，expanded=false 时取消

### 6.4 补充要求后的重新规划

`reviewAndExecutePlan()` 处理 SUPPLEMENT 决策：

```java
private String reviewAndExecutePlan(ExecutionPlan plan) throws IOException {
    while (true) {
        PlanReviewDecision decision = reviewHandler.review(goal, plan);

        if (decision.action() == PlanReviewAction.EXECUTE) {
            return executePlan(plan);
        }

        if (decision.action() == PlanReviewAction.CANCEL) {
            return "❌ 已取消本次计划执行。";
        }

        // SUPPLEMENT：将补充要求拼接到原 goal 上，重新规划
        String feedback = decision.feedback();
        plan = planner.createPlan(goal + "\n补充要求: " + feedback);
        // 再次进入审查循环
    }
}
```

用户可以多次补充要求，每次都会重新生成计划并再次审查，直到满意或放弃。

---

## 7. 分批并发执行

文件：`src/main/java/com/paicli/agent/PlanExecuteAgent.java`

### 7.1 while 循环 vs 旧版 for 循环

**旧版**：`for taskId in executionOrder` 逐个执行

**新版**：`while(true) + 每轮重新计算"当前可执行任务"`

```java
private String executePlan(ExecutionPlan plan) {
    while (true) {
        List<Task> executableTasks = getExecutableTasksInOrder(plan);
        if (executableTasks.isEmpty()) break;

        List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks);
        // 处理结果...
    }

    if (!plan.isAllCompleted() && !plan.hasFailed()) {
        plan.markFailed();
        return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";  // 僵局
    }

    return "✅ 计划执行完成！\n" + buildFinalResult(plan);
}
```

**优势**：
- 一轮可能同时完成多个互不依赖的任务
- 下一轮才有新的任务变得可执行（其前置依赖刚完成）
- while 保证不会漏掉后续批次

### 7.2 getExecutableTasksInOrder：按拓扑序过滤可执行任务

```java
private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
    Set<String> executableIds = plan.getExecutableTasks().stream()
            .map(Task::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    return plan.getExecutionOrder().stream()
            .filter(executableIds::contains)
            .map(plan::getTask)
            .toList();
}
```

**为什么不用 `plan.getExecutableTasks()` 直接？**
`getExecutableTasks()` 返回所有 `isExecutable==true` 的任务，但不保证顺序。本方法先取可执行集合，再按拓扑序过滤，确保同一批次内的任务也保持依赖顺序。

### 7.3 executeTaskBatch：单任务 vs 多任务

```java
private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks) {
    // 单任务：直接在当前线程执行，避免线程池开销
    if (executableTasks.size() == 1) {
        Task task = executableTasks.get(0);
        try {
            return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task)));
        } catch (Exception e) {
            return List.of(TaskExecutionResult.failure(task, e));
        }
    }

    // 多任务：开线程池并行执行
    ExecutorService executor = Executors.newFixedThreadPool(executableTasks.size());
    try {
        List<Future<TaskExecutionResult>> futures = new ArrayList<>();
        for (Task task : executableTasks) {
            futures.add(executor.submit(() -> {
                try {
                    return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task));
                } catch (Exception e) {
                    return TaskExecutionResult.failure(task, e);
                }
            }));
        }

        List<TaskExecutionResult> results = new ArrayList<>();
        for (Future<TaskExecutionResult> future : futures) {
            results.add(future.get());  // 等待全部完成
        }
        return results;
    } finally {
        executor.shutdownNow();  // 立即释放线程池
    }
}
```

**并行安全前提**：同一批次中的任务都通过了 `Task.isExecutable()` 校验，即所有前置依赖都已完成，因此并行执行互不干扰。

### 7.4 并发执行案例

假设 DAG 结构如下：

```
task_1 (无依赖)：读取当前目录
task_2 (无依赖)：检查 Java 环境
task_3 (依赖 task_1)：创建项目结构
task_4 (依赖 task_1, task_2)：配置 build 工具
task_5 (依赖 task_3, task_4)：编写源代码
```

**第 1 轮**：`getExecutableTasksInOrder()` 返回 `[task_1, task_2]`

```
⚡ 本轮并行执行 2 个任务: task_1, task_2

▶️ 并行任务 [task_1]: 读取当前目录
   🔧 调用工具: list_dir
   ✅ 完成 [task_1]: 当前目录包含 /src, /pom.xml...

[线程池中并发执行]
▶️ 并行任务 [task_2]: 检查 Java 环境
   🔧 调用工具: execute_command
   ✅ 完成 [task_2]: Java 17 已安装
```

**第 2 轮**：`getExecutableTasksInOrder()` 返回 `[task_3]`（只有 task_1 完成，task_4 需要 task_2 也完成）

```
▶️ 执行任务 [task_3]: 创建项目结构
   🔧 调用工具: create_project
   ✅ 完成 [task_3]: 项目已创建
```

**第 3 轮**：`getExecutableTasksInOrder()` 返回 `[task_4]`（task_1, task_2 都已完成）

```
▶️ 执行任务 [task_4]: 配置 build 工具
   🔧 调用工具: write_file
   ✅ 完成 [task_4]: pom.xml 已写入
```

**第 4 轮**：`getExecutableTasksInOrder()` 返回 `[task_5]`

```
▶️ 执行任务 [task_5]: 编写源代码
   🔧 调用工具: write_file
   ✅ 完成 [task_5]: Main.java 已写入
```

### 7.5 僵局检测

while 退出但计划未全部完成且没有失败 → 存在永远无法满足的依赖（如依赖了不存在的任务）→ 标记 FAILED 并返回提示。

```java
if (!plan.isAllCompleted() && !plan.hasFailed()) {
    plan.markFailed();
    return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
}
```

---

## 8. JLine 终端控制

文件：`src/main/java/com/pacicli/cli/Main.java`

### 8.1 raw mode 单键读取

进入 raw mode 后，`InputStream.read()` 读到的是单个字节的 ASCII/Unicode 码点，而不是行缓冲后的整行文本：

```java
private static Integer readSingleKeyFromTerminal(Terminal terminal) {
    terminal.flush();
    Attributes originalAttributes = terminal.enterRawMode();
    try {
        int key = terminal.reader().read();  // 阻塞等待按键
        if (key < 0) return null;

        if (key == 27) {  // ESC，需要 drain 掉后续方向键序列
            drainEscapeSequence(terminal);
        }
        return key;
    } finally {
        terminal.setAttributes(originalAttributes);  // 恢复终端属性
    }
}
```

### 8.2 ESC 序列 draining

方向键（上/下/左/右）和功能键（F1-F12）都以 ESC (27) 开头后跟多字节序列（如 `[A`、`[B`）。用户误按方向键时，如果只读到 ESC 而后续字节残留在缓冲区，下次 read 会读到脏数据。

```java
private static void drainEscapeSequence(Terminal terminal) {
    Thread.sleep(50);  // 等待后续字节到达
    while (terminal.reader().ready()) {
        terminal.reader().read();  // 丢弃所有待读字节
    }
}
```

### 8.3 括号粘贴处理

现代终端在粘贴多行文本时会在内容前后包裹 `\[200~` ... `\[201~`：

```java
private static PrefillResult readEscapeInput(Terminal terminal) {
    String sequence = readInputBurst(terminal, 30, 25, 250);

    if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
        String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
        // 循环读取直到遇到粘贴后缀
        while (!pastedText.contains(BRACKETED_PASTE_END)) {
            String burst = readInputBurst(terminal, 30, 25, 500);
            pastedText += burst;
        }
        return PrefillResult.seed(prepareSeedBuffer(pastedText));
    }

    // 不是粘贴 → 纯 ESC → 取消
    return PrefillResult.canceledInput();
}
```

### 8.4 prefill 机制：首字符预读

在等待 Plan 模式输入时，先进入 raw mode 读第一个字符判断意图：

```java
private static PrefillResult readPrefillInputFromTerminal(Terminal terminal) {
    Attributes originalAttributes = terminal.enterRawMode();
    try {
        int key = terminal.reader().read();

        if (key == 27) return readEscapeInput(terminal);  // ESC
        if (isSubmitKey(key)) return PrefillResult.submittedInput();  // Enter

        // 其他字符：用户开始输入，继续读取剩余字节
        String rawInput = Character.toString((char) key);
        rawInput += readInputBurst(terminal, 20, 25, 250);
        return PrefillResult.seed(prepareSeedBuffer(rawInput));
    } finally {
        terminal.setAttributes(originalAttributes);
    }
}
```

**三种分支**：
- ESC → 进一步判断是取消还是粘贴
- Enter → 空提交
- 其他字符 → 开始输入，将预读到的字符作为 JLine 的 seed buffer

### 8.5 readInputBurst：三阶段超时策略

```java
private static String readInputBurst(Terminal terminal,
                                      long firstWaitMs,   // 首字节到达前的等待
                                      long idleWaitMs,    // 字节间的空闲超时
                                      long maxWaitMs) {   // 整体最大等待
    long firstDeadline = start + firstWaitMs;
    long idleDeadline = 0;

    while (now - start < maxWaitMs) {
        if (terminal.reader().ready()) {
            int next = terminal.reader().read();
            buffer.append((char) next);
            idleDeadline = now + idleWaitMs;  // 重置空闲超时
            continue;
        }

        if (buffer.isEmpty()) {
            if (now >= firstDeadline) break;  // 等待首字节超时
        } else if (now >= idleDeadline) {
            break;  // 空闲超时
        }
    }
    return buffer.toString();
}
```

**为什么不用 `read()` 阻塞等？** 终端输入没有 EOF 标记，无法知道"用户打完了没"。用轮询 + 空闲超时是实时终端处理粘贴的标准做法。

---

## 9. executeTask：单个 Task 的 LLM + 工具调用

### 9.1 组装 prompt

```java
private static final String EXECUTION_PROMPT = """
你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

当前任务类型：%s
任务描述：%s

可用工具：
1. read_file - 读取文件内容，参数：{"path": "文件路径"}
2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
3. execute_command - 执行命令，参数：{"command": "命令"}
4. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}

如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

请用中文回复。
""";

private String executeTask(String goal, ExecutionPlan plan, Task task) {
    String prompt = String.format(EXECUTION_PROMPT, task.getType(), task.getDescription());
    List<Message> messages = Arrays.asList(
        Message.system(prompt),
        Message.user(buildTaskContext(goal, plan, task))
    );
    // ...
}
```

### 9.2 调用 LLM 与工具

```java
GLMClient.ChatResponse response = llmClient.chat(messages, toolRegistry.getToolDefinitions());

if (response.hasToolCalls()) {
    StringBuilder results = new StringBuilder();
    for (GLMClient.ToolCall toolCall : response.toolCalls()) {
        String toolResult = toolRegistry.executeTool(
            toolCall.function().name(),
            toolCall.function().arguments()
        );
        results.append(toolResult).append("\n");
    }
    return results.toString().trim();
} else {
    return response.content();  // ANALYSIS/VERIFICATION 类型直接返回文本
}
```

### 9.3 buildTaskContext：依赖结果注入

```java
private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
    StringBuilder context = new StringBuilder();
    context.append("总目标：").append(goal).append("\n");
    context.append("当前任务：").append(task.getDescription()).append("\n");

    if (task.getDependencies().isEmpty()) {
        context.append("依赖任务：无\n");
    } else {
        context.append("依赖任务结果：\n");
        for (String depId : task.getDependencies()) {
            Task dep = plan.getTask(depId);
            context.append("- ").append(dep.getId())
                   .append(" / ").append(dep.getStatus())
                   .append("\n");
            if (dep.getResult() != null) {
                context.append(dep.getResult()).append("\n");  // 前置任务产出
            }
        }
    }

    context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
    return context.toString();
}
```

**关键：每个 Task 是一次独立的 LLM 调用**，不维护跨 Task 的对话历史。依赖关系通过 `buildTaskContext()` 把前置 Task 的执行结果作为 user message 传入。

---

## 10. 完整端到端示例

### 10.1 场景

用户输入：`/plan 帮我创建一个 Java 项目，写一个 Hello World，然后编译运行`

### 10.2 阶段 1：CLI 模式路由

```
CliCommandParser.parse("/plan 帮我创建...") → ParsedCommand(SWITCH_PLAN, "帮我创建...")
command.type() == SWITCH_PLAN → 走 Plan 模式
```

### 10.3 阶段 2：LLM 生成计划 JSON

Planner 把 `PLANNING_PROMPT` + 用户目标发给 LLM（tools=null），LLM 返回：

```json
{
    "summary": "创建并运行一个 Java Hello World 项目",
    "tasks": [
        {"id": "task_1", "description": "创建 Java 项目结构", "type": "COMMAND", "dependencies": []},
        {"id": "task_2", "description": "写入 HelloWorld.java 源代码", "type": "FILE_WRITE", "dependencies": ["task_1"]},
        {"id": "task_3", "description": "编译 Java 项目", "type": "COMMAND", "dependencies": ["task_2"]},
        {"id": "task_4", "description": "运行编译后的程序", "type": "COMMAND", "dependencies": ["task_3"]},
        {"id": "task_5", "description": "验证输出是否为 Hello World", "type": "VERIFICATION", "dependencies": ["task_4"]}
    ]
}
```

### 10.4 阶段 3：parsePlan 两遍解析 + 拓扑排序

**第一遍**（建节点，id 重写）：
```
task_1 → task_1: COMMAND "创建 Java 项目结构"
task_2 → task_2: FILE_WRITE "写入 HelloWorld.java 源代码"
task_3 → task_3: COMMAND "编译 Java 项目"
task_4 → task_4: COMMAND "运行编译后的程序"
task_5 → task_5: VERIFICATION "验证输出是否为 Hello World"
```

**第二遍**（回填依赖）：
```
task_1.dependencies = []
task_2.dependencies = [task_1]
task_3.dependencies = [task_2]
task_4.dependencies = [task_3]
task_5.dependencies = [task_4]
```

**拓扑排序**（DFS 后序）：
```
executionOrder = [task_1, task_2, task_3, task_4, task_5]
```

### 10.5 阶段 4：计划审查与执行

**计划审查**（阻塞）：
```
📋 计划摘要
   - 目标: 创建并运行一个 Java Hello World 项目
   - 任务数: 5 | 并行批次: 5 | 当前可执行: 1 | 状态: CREATED
   - 首批执行: task_1
   - 最终收敛: task_5

📝 计划已生成。
   - 回车：按当前计划执行
   - Ctrl+O：展开完整计划
   - ESC：折叠或取消本次计划
   - I：输入补充要求后重新规划
```

用户按 Enter → `PlanReviewDecision.execute()` → 进入 `executePlan()`

**执行循环**（分批，但此例每批只有 1 个）：

```
第 1 轮: executableTasks = [task_1]
▶️ 执行任务 [task_1]: 创建 Java 项目结构
   🔧 调用工具: create_project(name="hello", type="java")
   ✅ 完成 [task_1]: 项目已创建: hello (类型: java)

第 2 轮: executableTasks = [task_2]
▶️ 执行任务 [task_2]: 写入 HelloWorld.java 源代码
   🔧 调用工具: write_file(path="hello/src/main/java/HelloWorld.java", content="public class HelloWorld {...}")
   ✅ 完成 [task_2]: 文件已写入: hello/src/main/java/HelloWorld.java

第 3 轮: executableTasks = [task_3]
▶️ 执行任务 [task_3]: 编译 Java 项目
   🔧 调用工具: execute_command(command="cd hello && javac src/main/java/HelloWorld.java")
   ✅ 完成 [task_3]: 编译成功

第 4 轮: executableTasks = [task_4]
▶️ 执行任务 [task_4]: 运行编译后的程序
   🔧 调用工具: execute_command(command="cd hello && java HelloWorld")
   ✅ 完成 [task_4]: Hello World

第 5 轮: executableTasks = [task_5]
▶️ 执行任务 [task_5]: 验证输出是否为 Hello World
   → VERIFICATION 类型，直接返回 LLM 分析结果
   ✅ 完成 [task_5]: 输出正确：Hello World
```

### 10.6 阶段 5：汇总结果

```java
buildFinalResult(plan) → 优先取叶子节点（task_5）的结果
```

```
✅ 计划执行完成！
[task_5] 输出正确：Hello World
```

---

## 11. 核心问题解答

### 11.1 executionOrder 的顺序是什么？

`executionOrder` 是通过**三色 DFS 拓扑排序**生成的，采用**后序遍历**策略：
- DFS 沿 `dependencies` 边向根方向深入
- 递归返回后才把节点加入 `executionOrder`
- 保证每个节点的所有前置依赖先于它加入列表

对于依赖链 `task_1 ← task_2 ← task_3`（task_3 依赖 task_2，task_2 依赖 task_1）：
```
DFS(task_3) → deps=[task_2] → DFS(task_2) → deps=[task_1] → DFS(task_1)
                                                                  task_1 无依赖 → 先加入
                                                             task_2 加入（task_1 已在列表中）
                                                        task_3 加入（task_1, task_2 已在列表中）
最终 executionOrder = [task_1, task_2, task_3]
```

**结论**：被依赖的节点（根节点）排在前面，依赖别人的节点排在后面。从左到右遍历执行就是安全的。

### 11.2 阻塞是如何实现的？

整个阻塞链路是：

```
PlanExecuteAgent.reviewAndExecutePlan(plan)
  └─ reviewHandler.review(goal, plan)   // 调用注入的 lambda
       └─ createPlanReviewHandler 中的 while(true) 循环
            └─ readSingleKeyFromTerminal(terminal)
                 └─ terminal.enterRawMode()
                 └─ terminal.reader().read()   ← 阻塞点
```

核心阻塞点是 `terminal.reader().read()`，这是 Java 标准库的**同步阻塞 I/O 调用**——进入 raw mode 后，`InputStream.read()` 会阻塞当前线程，直到终端有按键输入才返回。

拿到按键后回到 `createPlanReviewHandler` 的 `while(true)` 循环中判断：
- **Enter** → `return PlanReviewDecision.execute()` → 跳出循环，进入执行
- **ESC（未展开）** → `return PlanReviewDecision.cancel()` → 跳出循环，返回取消消息
- **ESC（已展开）** → 折叠回摘要，`continue` 继续循环等待
- **I** → 弹出 `lineReader.readLine("补充> ")` 读一行补充要求，解析后返回 SUPPLEMENT 决策
- **Ctrl+O** → 打印完整计划，`continue` 继续循环等待

本质就是 Java 标准库的 `InputStream.read()` 阻塞，没有用任何异步/事件驱动机制。

### 11.3 reviewHandler.review() 是怎么实现的？

`reviewHandler.review(goal, plan)` 是 `PlanReviewHandler` 接口的定义：

```java
public interface PlanReviewHandler {
    PlanReviewDecision review(String goal, ExecutionPlan plan);
}
```

实现在 `Main.java` 的 `createPlanReviewHandler()` 方法中，返回一个 **lambda 表达式**：

```java
PlanReviewHandler handler = (goal, plan) -> {
    boolean expanded = false;
    System.out.println(plan.summarize());
    // 显示操作提示...

    while (true) {
        Integer key = readSingleKeyFromTerminal(terminal);  // 阻塞等按键
        if (key == '\n' || key == '\r') {
            return PlanReviewDecision.execute();
        }
        // ... 其他按键处理
    }
};
```

这个 lambda 在 `createPlanAgent()` 中注入到 `PlanExecuteAgent` 构造器，然后在 `PlanExecuteAgent.reviewAndExecutePlan()` 中被调用。Agent 只依赖接口，不关心终端交互细节——这就是"依赖反转"。

### 11.4 为什么 ESC 的判断是 key == 27？

因为 **27 是 ESC 键的 ASCII 码**（十六进制 `0x1B`，八进制 `\033`）。

在 raw mode 下，`InputStream.read()` 读到的不再是行缓冲后的字符，而是终端直接发送的每一个字节。按下 ESC 键时终端发送的就是字节 `0x1B`，转成 int 就是 `27`。

代码里有两处用它：
- `readSingleKeyFromTerminal`（:345）——读单键，读到 ESC 后 drain 掉后续方向键序列字节
- `createPlanReviewHandler`（:275）——审查循环中判断用户是否按 ESC

### 11.5 batch 执行实现了并行吗？

是的，实现了并行。逻辑在 `PlanExecuteAgent.executeTaskBatch()`：

```java
// 单任务：直接在当前线程执行
if (executableTasks.size() == 1) {
    return List.of(TaskExecutionResult.success(task, executeTask(...)));
}

// 多任务：开线程池并行执行
ExecutorService executor = Executors.newFixedThreadPool(executableTasks.size());
for (Task task : executableTasks) {
    futures.add(executor.submit(() -> executeTask(...)));  // 每个任务一个线程
}
for (Future<TaskExecutionResult> future : futures) {
    results.add(future.get());  // 等待全部完成
}
```

**并行安全的保证**：能被放进同一批次的 task 都通过了 `Task.isExecutable()` 校验——即它们的所有前置依赖都已经是 `COMPLETED`。所以同一批次内的多个 task 互不依赖，并行执行不会互相干扰。

---

## 12. 关键设计要点

1. **两遍扫描解析 JSON**：因为 LLM 可能前向引用未出现的任务 ID，所以先建节点再回填依赖
2. **idMapping 重写**：不信任 LLM 输出的 ID，统一重写为 `task_1, task_2, ...`
3. **三色 DFS 拓扑排序**：在同一趟 DFS 中同时完成排序和环检测，时间复杂度 O(V+E)
4. **后序加入 executionOrder**：保证被依赖的节点排在列表前面，依赖别人的排在后面
5. **双层安全网**：拓扑排序（静态）+ isExecutable（动态），防止 FAILED 的前置导致后继错误执行
6. **每个 Task 独立调 LLM**：不维护跨 Task 的对话历史，依赖通过 context 注入
7. **重规划复用 Planner**：replan 就是 `createPlan(已完成任务 + 失败原因)`，递归替换当前计划
8. **50% 阈值不做强制**：是启发式的，后续可以根据经验调整
9. **复用第 1 期的 ToolRegistry**：Plan 模式的工具调用和 ReAct 模式用的是同一个 `ToolRegistry` 实例
10. **依赖反转的 PlanReviewHandler**：Agent 定义接口，CLI 实现交互逻辑，便于测试和替换
11. **分批并发执行**：while 循环 + 每轮重新计算可执行任务，同批次内多任务用 ExecutorService 并行
12. **JLine raw mode 终端控制**：支持单键读取、括号粘贴、ESC 序列 draining，实现流畅的 HITL 交互
13. **ESC 双重语义**：expanded=true 时折叠视图，expanded=false 时取消计划
14. **僵局检测**：while 退出但计划未完成且无失败 → 标记 FAILED 并提示
15. **模式路由由 CLI 层负责**：不再在 Agent 内部用 `shouldPlan()` 启发式判断，由 `/plan` 命令显式切换

---

## 13. 与第 1 期 ReAct 的对比

| 对比维度 | ReAct（Agent.java） | Plan-and-Execute（PlanExecuteAgent.java） |
|---|---|---|
| 适用场景 | 简单问答、单文件操作 | 多文件创建、编译+运行、跨步骤任务 |
| 任务颗粒度 | 单轮交互，每次一个 tool_calls | 多步任务，先规划再批量执行 |
| 模式选择 | 无 | CLI 层 `/plan` 命令显式切换 |
| 计划审查 | 无 | 支持执行前预览、补充要求、取消 |
| LLM 调用次数 | 每轮 1 次，最多 10 轮（有循环） | 规划 1 次 + 每个 Task 1 次（+ 可能 replan） |
| 对话历史 | 跨轮累积（conversationHistory） | 每个 Task 独立调 LLM（通过 context 传依赖结果） |
| 依赖感知 | 无（依赖靠 LLM 从上下文推断） | 有（显式 DAG + 拓扑排序） |
| 失败恢复 | 无（达到 MAX_ITERATIONS=10 直接报错） | progress < 50% 自动 replan |
| 执行前预览 | 无 | summarize() / visualize() 打印计划表 |
| 执行方式 | 串行 ReAct 循环 | 分批并行执行（同批次内） |
| 与工具的关系 | 直接调 ToolRegistry | 每个 Task 内调 ToolRegistry（复用同一套工具） |
| 终端控制 | Scanner | JLine（raw mode、单键读取、括号粘贴） |

---

*第 2 期在约 1200 行新增代码中实现了 Plan-and-Execute 的完整链路，为后续第 5 期 Multi-Agent 和第 7 期异步并行执行奠定了 DAG 调度基础。*

---

## 14. Robustness 增强与 Bug 修复

在第 2 期开发过程中，针对一些潜在的 bug 和性能问题进行了以下增强，提升了系统的鲁棒性：

### 14.1 限制并发线程数上限

**修改位置**：`PlanExecuteAgent.executeTaskBatch()` 第 338 行

**修改内容**：
```java
// 旧版本：根据可执行任务数量创建等量线程
ExecutorService executor = Executors.newFixedThreadPool(executableTasks.size());

// 新版本：限制最大并发数为 4
ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4));
```

**解决的潜在问题**：
- **资源耗尽风险**：如果 LLM 生成了一个包含几十个无依赖任务的计划，旧版本会创建几十个线程同时执行，可能导致 CPU 爆满、内存不足或线程调度开销过大
- **线程切换成本**：过多线程会导致频繁的上下文切换，反而降低并发效率
- **网络连接数爆炸**：每个任务可能触发 HTTP 请求（LLM 调用），同时创建几十个连接可能触发服务端限流或本地端口耗尽

**设计考量**：
- 4 是经验值，平衡了并发效率和系统资源占用
- 单任务路径仍保持直接执行，避免线程池开销
- 后续可通过配置参数动态调整此上限

### 14.2 executeTask 支持多轮工具调用

**修改位置**：`PlanExecuteAgent.executeTask()` 第 388-432 行

**修改内容**：
```java
// 旧版本：单轮调用 LLM + 工具
private String executeTask(...) {
    String prompt = ...;
    List<Message> messages = Arrays.asList(
        Message.system(prompt),
        Message.user(context)
    );
    GLMClient.ChatResponse response = llmClient.chat(messages, tools);
    if (response.hasToolCalls()) {
        // 执行工具，返回结果
    }
    return response.content();
}

// 新版本：支持 ReAct 风格的多轮工具调用
private static final int MAX_TASK_ITERATIONS = 5;

private String executeTask(...) throws IOException {
    List<Message> messages = new ArrayList<>(Arrays.asList(
        Message.system(prompt),
        Message.user(context)
    ));
    StringBuilder allResults = new StringBuilder();
    int iteration = 0;

    while (iteration < MAX_TASK_ITERATIONS) {
        iteration++;
        GLMClient.ChatResponse response = llmClient.chat(messages, tools);

        if (!response.hasToolCalls()) {
            // 没有工具调用，返回最终结果
            if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                return allResults.toString().trim();
            }
            return response.content();
        }

        // 有工具调用：执行工具并将结果回灌到消息历史
        messages.add(Message.assistant(response.content(), response.toolCalls()));
        for (ToolCall toolCall : response.toolCalls()) {
            String toolResult = toolRegistry.executeTool(...);
            allResults.append(toolResult).append("\n");
            messages.add(Message.tool(toolCall.id(), toolResult));
        }
    }
    return allResults.toString().trim();
}
```

**解决的潜在问题**：
- **复杂任务无法完成**：旧版本每个 Task 只能单轮调用 LLM + 工具，对于需要"先读取文件 → 分析内容 → 根据结果决定下一步操作"的场景无法处理
- **工具调用结果未被利用**：LLM 在第一轮调用工具后，无法根据工具返回的结果决定是否需要继续调用其他工具
- **与第 1 期 ReAct 能力不一致**：第 1 期的 `Agent.run()` 已经支持多轮工具调用，第 2 期的 Plan 模式也应该具备相同能力

**设计考量**：
- `MAX_TASK_ITERATIONS = 5` 防止单个 Task 无限循环调用工具
- 消息历史在每个 Task 内维护，跨 Task 仍通过 `buildTaskContext()` 传递依赖结果
- 工具结果同时追加到 `allResults` 和消息历史中，保证最终输出完整

### 14.3 添加迭代次数限制常量

**修改位置**：`PlanExecuteAgent` 第 374-375 行

**修改内容**：
```java
private static final int MAX_TASK_ITERATIONS = 5;
```

**解决的潜在问题**：
- **无限循环风险**：如果 LLM 陷入死循环，不断调用同一个工具（如反复读取同一个文件但没有进展），会导致程序永不终止
- **资源浪费**：无限循环会持续消耗 LLM API 配额和网络带宽
- **用户体验差**：用户无法感知程序陷入循环，可能误以为卡死

**设计考量**：
- 5 次迭代是经验值，足以处理绝大多数需要多步工具调用的任务
- 达到上限后返回已收集的工具结果，而不是报错，保证至少有部分产出
- 可通过调整此常量来平衡任务完成能力和资源安全性

### 14.4 其他小优化

- **注释完善**：在 `Planner.parsePlan()` 中添加了更清晰的注释说明两遍扫描的目的
- **可视化格式调整**：优化了 `ExecutionPlan.visualize()` 中 description 的格式化方式，提高可读性

---

### Robustness 总结

| 修改项 | 解决的问题 | 影响范围 |
|---|---|---|
| 并发线程数上限限制（max=4） | 资源耗尽、线程切换成本、网络连接数爆炸 | `executeTaskBatch()` |
| executeTask 多轮工具调用 | 复杂任务无法完成、工具结果未利用、与 ReAct 不一致 | `executeTask()` |
| MAX_TASK_ITERATIONS = 5 | 无限循环风险、资源浪费、用户体验差 | `executeTask()` |

这些修改在不破坏原有架构的前提下，显著提升了 Plan-and-Execute 模式的稳定性和实用性。

---

## 15. Memory 阶段增强（第 3 期联动）

第 3 期 Memory 系统引入了**共享会话上下文**架构，PlanExecuteAgent 获得了多项增强。详见 `docs/chapter3-Memory实现.md`。

### 15.1 共享会话上下文

`PlanExecuteAgent` 新增构造器接收 `List<Message> sharedHistory` 和 `MemoryManager sharedMemory`，与 ReAct 的 `Agent` 共享同一份对话历史与长期记忆。

`sharedHistory` 由 `Main.java` 在启动时创建，注入两个 Agent。Plan 只在 `run()` 首尾追加高层面的 `goal` 和 `result`，Task 内部的局部 `messages`（含工具调用细节）不入共享历史，避免污染。

### 15.2 轮开始前压缩

Plan `run()` 开头调用 `memoryManager.compressContextIfNeeded(sharedHistory)`，与 ReAct 的 `Agent.run()` 循环内同一调用。压缩超限时对 `sharedHistory` 做 Map-Reduce 摘要并替换旧消息，保证切回 ReAct 时上下文不爆。

每 Task 的局部 `executeTask` 不再做压缩（删除原死代码），因为单 task 只有 1 条 user 消息、`MAX_TASK_ITERATIONS=5`，概率上不会撑爆上下文。

### 15.3 上下文感知规划（Stage 2）

`Planner` 新增 `createPlan(String goal, String priorContext)` 重载：

```java
// 规划前先取共享历史最近 8 条作为先前对话上下文
String priorContext = buildPriorContext(sharedHistory, 8);
// 跳过 index 0（system prompt）→ 按 "role: content" 格式化
sharedHistory.add(Message.user(goal));
ExecutionPlan plan = planner.createPlan(goal, priorContext);
```

`buildPriorContext` 取共享历史最近 N 条，跳过 index 0 system prompt。若 history 仅有 system 则返回 `""`。

效果：Planner 在规划时能看到之前的 ReAct 对话内容（如用户偏好、项目信息、已创建的文件），生成的 Task 可以引用前文上下文。

### 15.4 事实提取与共享长期记忆

`executePlan` 末尾新增 `extractFactsFromPlan(plan)`：用 plan 的 goal + 各 task 结果构建 `List<Message>`，调用 `memoryManager.extractAndSaveFacts` → `ContextCompressor.extractFacts` → LLM 提取事实 → `LongTermMemory.store` 落盘。

共享 `MemoryManager` 确保提取的事实立即可被 ReAct 的 `buildContextForQuery` 检索——无需重启进程。

### 15.5 Token 统计完善

`executeTask` 把 `recordTokenUsage` 上移到 `llmClient.chat` 之后、分支之前，一次调用覆盖工具调用迭代和最终响应两条分支（此前只在非工具响应时调用，漏掉了工具调用迭代的 token）。
