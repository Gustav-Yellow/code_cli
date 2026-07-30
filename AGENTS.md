# AGENTS.md

> 本文件是 PaiCLI 项目的「AI 协作说明书」——给所有 AI Agent（Claude Code / Cursor / 其他）提供项目全景认知。
> 项目随 ROADMAP.md 的 21 期迭代持续演进，本文件也会按 Phase 持续追加内容。
> **维护约定**：每完成一期或新增/重构一类，请同步更新对应的「已完成模块」章节；尚未落地的期次保持占位说明，不要提前写入实现细节。

---

## 1. 项目定位

**PaiCLI** 是一个用 Java 从零手写的终端 coding agent，对标 Claude Code / Aider / DeepSeek TUI。

- **语言 / 运行时**：Java 17
- **构建**：Maven（`pom.xml` 继承 `spring-boot-starter-parent` 4.1.0，但项目本身**未使用 Spring**，只是借用 parent POM 的依赖管理；主程序是纯 Java 入口）
- **核心依赖**：OkHttp 4.12（HTTP）、Jackson 2.16（JSON）、slf4j-simple 2.0（日志）
- **默认 LLM**：智谱 GLM-5.2（`https://open.bigmodel.cn/api/paas/v4/chat/completions`），OpenAI 兼容协议
- **入口类**：`com.paicli.cli.Main`
- **当前进度**：第 1 期（ReAct + Tool Call）和第 2 期（Plan-and-Execute）已完成并文档化（`docs/chapter1-*.md` / `docs/chapter2-*.md`）；第 3–21 期见 ROADMAP.md

设计哲学：**手写优先，框架在后**。21 期主线全部手写完成后，才会开启 Pro 分支用 Spring AI / LangGraph4J 重构做对照实现。日常开发不要提前引入 Spring / LangChain4j 等框架抽象。

---

## 2. 目录结构

```
paicli/
├── AGENTS.md                          # 本文件
├── CLAUDE.md                          # Claude Code 专用指引
├── ROADMAP.md                         # 21 期迭代路线图（权威来源）
├── pom.xml                            # Maven 构建配置
├── docs/                              # 按期次组织的设计/教程文档
│   ├── chapter1-ReAct和Tool Call实现.md
│   └── chapter2-Plan-and-Execute实现.md
└── src/main/java/com/paicli/
    ├── cli/Main.java                  # CLI 入口 + REPL + API Key 加载
    ├── agent/
    │   ├── Agent.java                 # ReAct 循环（思考-行动-观察）
    │   └── PlanExecuteAgent.java      # Plan-and-Execute 编排
    ├── llm/
    │   └── GLMClient.java             # GLM HTTP 客户端 + Message/Tool/ToolCall record
    ├── tool/
    │   └── ToolRegistry.java          # 5 个内置工具 + JSON Schema 生成 + 工具执行
    └── plan/
        ├── Planner.java               # LLM 驱动的任务分解
        ├── ExecutionPlan.java         # 计划 DAG + 拓扑排序
        └── Task.java                  # 任务节点 + 依赖/状态
```

> 后续期次会新增 `memory/`、`rag/`、`mcp/`、`hitl/`、`skill/`、`tui/` 等包。新增包时请在上方目录树补一行，并在第 4 节追加模块说明。

---

## 3. 核心架构（当前）

```
┌──────────────┐   用户输入    ┌──────────────────┐
│   Main.java  │ ────────────► │  Agent /         │
│ (CLI REPL)   │ ◄──────────── │  PlanExecuteAgent│
└──────────────┘   最终文本     └────────┬─────────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  │                    │                    │
                  ▼                    ▼                    ▼
           ┌──────────┐         ┌────────────┐       ┌────────────┐
           │ GLMClient│         │ToolRegistry│       │  Planner   │
           │ (HTTP)   │         │  (5 工具)  │       │ (任务分解) │
           └──────────┘         └────────────┘       └────────────┘
```

**两种执行模式**（`PlanExecuteAgent.shouldPlan()` 启发式判断）：
- 简单任务（动作关键词 < 3 且输入 ≤ 50 字）：直接走 ReAct 单轮工具调用
- 复杂任务：`Planner.createPlan()` → `ExecutionPlan.computeExecutionOrder()` → 按 DAG 顺序执行每个 `Task` → 失败进度 < 50% 时触发 `Planner.replan()`

---

## 4. 已完成模块详解

> 每个 module 一节。新增类时请在本节追加；重构类时请同步更新。

### 4.1 `cli.Main` — CLI 入口（第 1 期基础，第 2 期增强）

- 文件：`src/main/java/com/paicli/cli/Main.java`、`src/main/java/com/paicli/cli/CliCommandParser.java`
- 职责：
  - 启动 banner 打印
  - API Key 加载顺序：当前目录 `.env` → `~/.env` → 环境变量 `GLM_API_KEY`
  - 支持 `/react`、`/plan` 命令运行时切换执行模式
  - ReAct 模式：`Agent.run()` 循环调用 LLM + 工具
  - Plan 模式：`PlanExecuteAgent.run()` 先规划后执行
  - 其他命令：`exit`/`quit` 退出、`clear` 清空历史
- 关键约定：API Key 找不到时直接 `System.exit(1)`，不进入交互循环

### 4.2 `agent.Agent` — ReAct 循环（第 1 期）

- 文件：`src/main/java/com/paicli/agent/Agent.java`
- 职责：维护 `conversationHistory`，循环调用 LLM 直到无工具调用或达到 `MAX_ITERATIONS=10`
- 核心循环（详见 `docs/chapter1-ReAct和Tool Call实现.md`）：
  1. `llmClient.chat(history, toolRegistry.getToolDefinitions())`
  2. 若 `response.hasToolCalls()`：把 assistant 消息（含 tool_calls）+ 每个工具结果（`Message.tool(id, result)`）追加到 history，`continue`
  3. 否则：追加 assistant 消息并返回 content
- 系统提示词硬编码在 `SYSTEM_PROMPT` 常量里，第 19 期会迁移到 `src/main/resources/prompts/` 分层架构

### 4.3 `llm.GLMClient` — LLM HTTP 客户端（第 1 期）

- 文件：`src/main/java/com/paicli/llm/GLMClient.java`
- 端点：`https://open.bigmodel.cn/api/paas/v4/chat/completions`，模型 `glm-5.2`
- OkHttp 超时：connect 60s / read 120s（LLM 生成慢，read 必须放宽）
- 内嵌 record 数据模型：
  - `Message`（role + content + tool_calls + tool_call_id），提供 `system()` / `user()` / `assistant()` / `tool()` 静态工厂
  - `Tool`（name + description + parameters JSON Schema）
  - `ToolCall` / `Function`
  - `ChatResponse`（content + toolCalls + inputTokens + outputTokens + `hasToolCalls()`）
- 第 8 期会抽象出 `LlmClient` 接口与 `AbstractOpenAiCompatibleClient` 基类，届时 GLMClient 会瘦身为子类

### 4.4 `tool.ToolRegistry` — 工具注册表（第 1 期）

- 文件：`src/main/java/com/paicli/tool/ToolRegistry.java`
- 5 个内置工具：
  | 工具名 | 参数 | 用途 |
  |---|---|---|
  | `read_file` | `path` | 读取文件 |
  | `write_file` | `path`, `content` | 写文件（自动创建父目录） |
  | `list_dir` | `path` | 列目录 |
  | `execute_command` | `command` | `bash -c` 执行 Shell |
  | `create_project` | `name`, `type` | 创建 java/python/node 脚手架 |
- 关键设计：
  - `Tool` record 含 `executor`（函数式接口 `ToolExecutor`），注册时用 lambda 提供执行逻辑
  - `createParameters(Param...)` 动态生成 JSON Schema（type/description/required），传给 LLM
  - `getToolDefinitions()` 剥离 executor，只把 name/description/parameters 暴露给 LLM
  - `executeTool(name, argumentsJson)` 用 Jackson 解析参数 → `Map<String,String>` → 调 executor
- **已知限制**：参数值用 `asText()` 强转字符串，嵌套对象/数组会丢结构（当前 5 个工具都是字符串参数，不受影响；扩展时需改这里）

### 4.5 `agent.PlanExecuteAgent` — Plan-and-Execute 编排（第 2 期）

- 文件：`src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- 职责：模式路由 + 计划执行编排
- 关键设计：
  - `shouldPlan(input)` 用启发式规则（动作关键词 ≥ 3 或输入 > 50 字）判断是否走 Plan 路径
  - 简单任务 → `runSimple()` 单轮 LLM + 工具调用
  - 复杂任务 → `runWithPlan()` → `Planner.createPlan()` → `executePlan()` 按拓扑序执行
  - `executeTask()` 每个 Task 独立调 LLM，不维护跨 Task 对话历史；依赖通过 `buildTaskContext()` 注入
  - `executePlan()` 中失败 + 进度 < 50% 时触发 `Planner.replan()`
- 详见 `docs/chapter2-Plan-and-Execute实现.md`

### 4.6 `plan.Planner` — LLM 任务分解（第 2 期）

- 文件：`src/main/java/com/paicli/plan/Planner.java`
- 职责：用 `PLANNING_PROMPT` 让 LLM 输出 `{summary, tasks:[{id,description,type,dependencies}]}` JSON
- 解析流程：
  1. 去除 ```` ```json ```` / ```` ``` ```` markdown 包裹
  2. 两遍扫描：先建 Task 节点（处理前向引用），再回填 dependencies
  3. `idMapping` 重写为 `task_1, task_2, ...` 避免 LLM 给的 id 重复
  4. 调 `ExecutionPlan.computeExecutionOrder()` 做拓扑排序，有环抛异常
- `replan(failedPlan, reason)`：把已完成任务作为上下文重新调 `createPlan`

### 4.7 `plan.ExecutionPlan` — 计划 DAG（第 2 期）

- 文件：`src/main/java/com/paicli/plan/ExecutionPlan.java`
- `LinkedHashMap<id, Task>` 保持插入顺序；`executionOrder` 存拓扑序
- `computeExecutionOrder()` 用 DFS + `visiting/visited` 双集合（三色标记法）检测环
- `getProgress()` 返回完成比例（用于判断是否触发 replan，阈值 50%）
- `visualize()` 输出 ASCII 表格（含状态 emoji ⏳▶️✅❌⏭️）

### 4.8 `plan.Task` — 任务节点（第 2 期）

- 文件：`src/main/java/com/paicli/plan/Task.java`
- `TaskType`：`PLANNING / FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION`
- `TaskStatus`：`PENDING / RUNNING / COMPLETED / FAILED / SKIPPED`
- `dependencies`（我依赖谁） + `dependents`（谁依赖我）双向链
- `isExecutable(allTasks)`：状态必须 PENDING 且所有 dependencies 已 COMPLETED

---

## 5. 开发与运行

### 5.1 环境准备

```bash
# 在项目根目录或 ~ 下放 .env
echo "GLM_API_KEY=your_zhipu_api_key" > .env
```

### 5.2 构建 / 运行

```bash
./mvnw clean package          # 打 fat jar
./mvnw spring-boot:run        # 直接跑（用 spring-boot 插件，但应用本身不是 Spring Boot）
java -jar target/paicli-0.0.1-SNAPSHOT.jar
```

### 5.3 测试

当前还没有单元测试。ROADMAP 中后续期次会引入 `mvn test`。第 6 期以后的所有危险操作（write_file / execute_command / create_project）需走 HITL 审批（未实现）。

---

## 6. 待落地期次（占位，不要提前实现）

| 期次 | 主题 | 关键类（计划） | 状态 |
|---|---|---|---|
| 3 | Memory 系统 | `MemoryManager` / `ContextProfile` | 未开始 |
| 4 | RAG 检索 | `VectorStore` / `CodeIndexer` | 未开始 |
| 5 | Multi-Agent | `AgentOrchestrator` / `SubAgent` | 未开始 |
| 6 | HITL 审批 | `HitlToolRegistry` / `PathGuard` / `CommandGuard` / `AuditLog` | 未开始 |
| 7 | 异步并行 | `BatchToolExecutor` | 未开始 |
| 8 | 多模型 | `LlmClient` 接口 / `AbstractOpenAiCompatibleClient` / `DeepSeekClient` / `StepClient` / `KimiClient` | 未开始 |
| 9 | 联网工具 | `web_search` / `web_fetch` | 未开始 |
| 10–11 | MCP | `JsonRpcClient` / `McpTransport` / `McpServerManager` | 未开始 |
| 12 | 长上下文 | `AgentBudget` / `ContextProfile` | 未开始 |
| 13–14 | Chrome DevTools MCP | 浏览器接入 | 未开始 |
| 15 | Skill 系统 | `SkillLoader` / `SkillContextBuffer` | 未开始 |
| 16 | TUI 产品化 | `Renderer` 接口 + inline/lanterna/plain 三实现 | 未开始 |
| 17 | LSP 诊断 | `LspManager` / `LspHooks` | 未开始 |
| 18 | Git 快照 | `SideGitManager`（JGit） | 未开始 |
| 19 | Prompt 分层 | `PromptAssembler` + `src/main/resources/prompts/*.md` | 未开始 |
| 20 | 后台任务 + Runtime API | `DurableTaskManager` / `RuntimeApiServer` | 未开始 |
| 21 | 图片输入 | `LlmClient.Message.ContentPart` | 未开始 |

完整设计见 `ROADMAP.md`。**实现某期之前先读 ROADMAP 对应章节与 `docs/phase-N-*.md`（若存在）**。

---

## 7. 代码风格与约定

- **包结构**：`com.paicli.<module>.<Class>`，一个模块一个包
- **数据模型**：优先用 Java 17 `record`（参考 `GLMClient.Message` / `ToolRegistry.Tool`）
- **注释**：Javadoc 写在类级别解释职责，方法级别只写非显而易见的「为什么」，不写「做什么」（参考 `ToolRegistry.executeTool` 的现有风格）
- **提示词**：当前硬编码在 `static final String` 常量里，第 19 期迁移到 Markdown
- **错误处理**：工具执行失败返回字符串 `"失败: ..."`,不抛异常打断 Agent 循环
- **中文**：所有面向用户的输出和教程用中文；类名/变量名用英文

---

## 8. 增量维护规则

新增类或期次时，按以下顺序更新本文件：

1. **新模块**：第 2 节目录树补一行；第 4 节追加 `### 4.x` 子节
2. **新期次落地**：第 6 节表格把对应行从「未开始」改为「已完成」，并指向第 4 节新子节
3. **重构现有类**：直接修改对应 `### 4.x` 子节，不要追加重复内容
4. **跨模块约定**（如 HITL、prompt 分层）：在第 7 节追加
5. **设计文档**：详细设计放 `docs/phase-N-<topic>.md`，本文件只放索引

> 本文件是索引和约定，不是教程。教程放 `docs/`。
