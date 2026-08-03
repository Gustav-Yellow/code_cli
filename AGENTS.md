# AGENTS.md

> 本文件是 PaiCLI 项目的「AI 协作说明书」——给所有 AI Agent（Claude Code / Cursor / 其他）提供项目全景认知。
> 项目随 ROADMAP.md 的 21 期迭代持续演进，本文件也会按 Phase 持续追加内容。
> **维护约定**：每完成一期或新增/重构一类，请同步更新对应的「已完成模块」章节；尚未落地的期次保持占位说明，不要提前写入实现细节。

---

## 1. 项目定位

**PaiCLI** 是一个用 Java 从零手写的终端 coding agent，对标 Claude Code / Aider / DeepSeek TUI。

- **语言 / 运行时**：Java 17
- **构建**：Maven（`pom.xml` 继承 `spring-boot-starter-parent` 4.1.0，但项目本身**未使用 Spring**，只是借用 parent POM 的依赖管理；主程序是纯 Java 入口）
- **核心依赖**：OkHttp 4.12（HTTP）、Jackson 2.16（JSON）、Logback 1.5（日志）、JLine 3.26（终端）、jieba-analysis 1.0.2（中文分词）、sqlite-jdbc 3.49（SQLite）、javaparser-core 3.28（AST 解析）
- **默认 LLM**：智谱 GLM-5.2（`https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`），OpenAI 兼容协议
- **入口类**：`com.paicli.cli.Main`
- **当前进度**：第 1 期（ReAct + Tool Call）、第 2 期（Plan-and-Execute）、第 3 期（Memory 系统）、第 4 期（RAG 检索）已完成并文档化（`docs/chapter1-*.md` / `docs/chapter2-*.md` / `docs/chapter3-*.md` / `docs/chapter4-*.md`）；第 4.1 期（流式输出 + 日志 + CLI 修复）已完成（`docs/chapter4.1-*.md`）；第 5 期（Multi-Agent 协作）已完成并文档化（`docs/chapter5-*.md`）；第 6 期（HITL 审批）已完成并文档化（`docs/Chapter6-HITL实现.md`）；第 7 期（异步并行工具执行）已完成并文档化（`docs/Chapter7-Async_Parallel实现.md`）；第 7.1 期（LLMClient 多模型适配）已完成并文档化（`docs/Chapter7.1-LLMClient开发.md`）；第 8 期（Web Search Tool 联网工具）已完成并文档化（`docs/Chapter8-Web_Search_Tool开发.md`）；第 9–21 期见 ROADMAP.md

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
│   ├── chapter2-Plan-and-Execute实现.md
│   ├── chapter3-Memory实现.md
│   ├── chapter4-RAG开发.md
│   ├── chapter4.1-Streaming_and_Log实现.md
│   └── chapter5-Multi_Agent开发.md
│   └── Chapter6-HITL实现.md
│   └── Chapter7-Async_Parallel实现.md
│   └── Chapter7.1-LLMClient开发.md
│   └── Chapter8-Web_Search_Tool开发.md
└── src/main/java/com/paicli/
    ├── cli/
    │   ├── Main.java                  # CLI 入口 + REPL + JLine 终端 + 模式路由 + RAG 命令
    │   ├── CliCommandParser.java      # 命令解析（/plan /exit /clear /index /search /graph）
    │   └── PlanReviewInputParser.java # 审查输入解析（EXECUTE/SUPPLEMENT/CANCEL）
    ├── agent/
    │   ├── Agent.java                 # ReAct 循环（思考-行动-观察）
    │   ├── AgentBudget.java           # Agent 循环退出预算（Token/停滞/硬轮数保险阀）
    │   ├── PlanExecuteAgent.java      # Plan-and-Execute 编排 + 计划审查
    │   ├── AgentOrchestrator.java     # Multi-Agent 编排器（主从架构）
    │   ├── SubAgent.java              # 可配置角色的子代理（规划者/执行者/检查者）
    │   ├── AgentRole.java             # 角色枚举（PLANNER/WORKER/REVIEWER）
    │   └── AgentMessage.java          # Agent 间通信消息 record（6 种类型）
    ├── llm/
    │   ├── LlmClient.java                        # LLM 客户端接口
    │   ├── AbstractOpenAiCompatibleClient.java   # OpenAI 兼容 API 抽象基类（SSE/流式解析/超时可配）
    │   ├── GLMClient.java                        # GLM 模型客户端（智谱 AI）
    │   ├── DeepSeekClient.java                   # DeepSeek 模型客户端
    │   └── LlmClientFactory.java                 # LLM 客户端工厂（按 provider 创建）
    ├── tool/
    │   └── ToolRegistry.java          # 8 个内置工具（含 web_search/web_fetch）+ JSON Schema 生成 + 并行工具执行
    ├── plan/
    │   ├── Planner.java               # LLM 驱动的任务分解
    │   ├── ExecutionPlan.java         # 计划 DAG + 拓扑排序 + 批次计算
    │   └── Task.java                  # 任务节点 + 依赖/状态
    ├── memory/
    │   ├── Memory.java                # Memory 统一接口
    │   ├── MemoryEntry.java           # 记忆条目数据模型
    │   ├── LongTermMemory.java        # 长期记忆持久化（JSON 落盘）
    │   ├── ContextCompressor.java     # Map-Reduce 对话压缩 + 事实提取
    │   ├── MemoryRetriever.java       # 长期记忆检索 + 相关度排序
    │   ├── MemoryQueryTokenizer.java  # jieba 中文分词器
    │   ├── TokenBudget.java           # Token 预算管理 + 使用统计
    │   └── MemoryManager.java         # 门面类（组合上述组件）
    ├── rag/
    │   ├── CodeChunk.java             # 代码块数据模型（file/class/method 粒度）
    │   ├── CodeChunker.java           # 代码分块器（AST + 大小分段）
    │   ├── CodeAnalyzer.java          # 代码关系图谱构建器（5 种关系类型）
    │   ├── CodeRelation.java          # 代码关系数据模型
    │   ├── CodeIndex.java             # 索引编排器（遍历→分块→向量化→入库）
    │   ├── EmbeddingClient.java       # Embedding API 客户端（Ollama/OpenAI/GLM）
    │   ├── VectorStore.java           # SQLite 向量存储 + 关系图谱持久化
    │   ├── CodeRetriever.java         # 混合检索入口（语义 + 关键词 + 图谱）
    │   ├── RagQueryTokenizer.java     # 查询分词器（jieba + ASCII 标识符）
    │   └── SearchResultFormatter.java # 检索结果格式化（CLI / Tool 双模式）
    └── util/
        ├── AnsiStyle.java               # ANSI 终端样式辅助类
        ├── JiebaSegmenterFactory.java   # jieba 分词器静默构造工厂
        └── TerminalMarkdownRenderer.java # 流式 Markdown 终端渲染器
    └── web/
        ├── FetchResult.java            # web_fetch 结构化结果 record
        ├── HtmlExtractor.java          # HTML → Markdown 正文提取（Jsoup 极简 readability）
        ├── NetworkPolicy.java          # 网络访问策略（SSRF 防护 + Token Bucket 限流）
        ├── SearchProvider.java         # 搜索引擎接口抽象
        ├── SearchProviderFactory.java  # SearchProvider 工厂（自动按 Key/URL 选择实现）
        ├── SearchResult.java           # 搜索结果 record
        ├── SearxngSearchProvider.java  # SearXNG 搜索 provider（骨架）
        ├── SerpApiSearchProvider.java  # SerpAPI 搜索 provider（骨架）
        ├── WebFetcher.java             # HTTP 抓取器（5MB 上限、30s 超时、charset 解析）
        └── ZhipuSearchProvider.java    # 智谱 Web Search provider（完整实现）
    └── hitl/
        ├── ApprovalPolicy.java           # 危险操作静态识别（write_file / execute_command / create_project）
        ├── ApprovalRequest.java          # 审批请求数据模型 + CJK-aware 终端盒子绘制
        ├── ApprovalResult.java           # 审批决策（APPROVED / APPROVED_ALL / REJECTED / MODIFIED / SKIPPED）
        ├── HitlHandler.java             # 审批交互接口
        ├── HitlToolRegistry.java        # 透明 HITL 拦截层（继承 ToolRegistry）
        └── TerminalHitlHandler.java      # 终端交互实现（y/a/n/s/m + synchronized 并发安全）
└── src/main/resources/
    └── logback.xml                      # Logback 日志滚动配置
```

> 后续期次会新增 `mcp/`、`hitl/`、`skill/`、`tui/` 等包。新增包时请在上方目录树补一行，并在第 4 节追加模块说明。

---

## 3. 核心架构（当前）

```
                         用户输入
                            │
                            ▼
                   ┌─────────────────┐
                   │   Main.java     │  REPL + JLine + 命令路由
                   │   (CLI 入口)    │  /plan /index /search /graph
                   └────────┬────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
              ▼             ▼             ▼
       ┌──────────────┐ ┌──────────┐ ┌──────────────┐
       │ Agent /      │ │ Agent    │ │ CodeIndex /  │
       │ PlanExecute  │ │Orchestra-│ │ CodeRetriever│
       │ Agent        │ │tor(Team) │ │ (RAG 检索)   │
       └──────┬───────┘ └────┬─────┘ └──────┬───────┘
              │                          │
   ┌──────────┼──────────┐      ┌───────┼───────┐
   │          │          │      │       │       │
   ▼          ▼          ▼      ▼       ▼       ▼
┌──────┐ ┌────────┐ ┌──────┐ ┌────┐ ┌──────┐ ┌──────┐
│GLM   │ │ToolReg │ │Plan  │ │Emb │ │Code  │ │Vector│
│Client│ │(8工具) │ │(分解)│ │Cli │ │Chunk │ │Store │
└──────┘ └────────┘ └──────┘ │ent │ │er    │ │(SQL) │
   │          │               └────┘ └──────┘ └──────┘
   │          │                  │
   ▼          ▼                  ▼
┌──────────────────────────────────────┐
│          MemoryManager (门面)         │
│  LongTermMemory / ContextCompressor  │
│  MemoryRetriever / TokenBudget       │
└──────────────────────────────────────┘
```

**四种命令模式**（CLI 层通过命令切换）：
- **ReAct 模式**（默认）：`Agent.run()` 循环调用 LLM + 工具
- **Plan 模式**（`/plan` 触发）：`PlanExecuteAgent.run()` → `Planner.createPlan()` → 计划审查（HITL）→ 分批并发执行
- **Team 模式**（`/team` 触发）：`AgentOrchestrator.run()` → 规划者拆解 → 执行者按依赖批次并行 → 检查者审查 → 重试/完成
- **RAG 检索**（`/search` `/graph`）：`CodeRetriever.hybridSearch()` / `getRelationGraph()` 查询 SQLite 索引
- **代码索引**（`/index`）：`CodeIndex.index()` 遍历文件 → 分块 → 向量化 → 入库
- **共享上下文**：ReAct、Plan 与 Team 共享同一个 `List<Message> sharedHistory` + `MemoryManager sharedMemory`

---

## 4. 已完成模块详解

> 每个 module 一节。新增类时请在本节追加；重构类时请同步更新。

### 4.1 `cli.Main` — CLI 入口（第 1 期基础，第 2/4/4.1 期增强）

- 文件：`src/main/java/com/paicli/cli/Main.java`、`src/main/java/com/paicli/cli/CliCommandParser.java`、`src/main/java/com/paicli/cli/PlanReviewInputParser.java`
- 职责：
  - 启动 banner 打印
  - **日志配置**：`configureLogging()` 初始化 Logback（日志目录、级别、滚动策略），`configureLogProperty()` 支持系统属性 → 环境变量 → `.env` 三层回退
  - **`.env` 全量加载**：`loadEnvConfig()` 解析 `.env` 中所有 `KEY=VALUE` → `System.setProperty()`，使 `EmbeddingClient`、`GLMClient` 等组件通过 `System.getProperty(key)` 即可读取配置
  - API Key 加载顺序：当前目录 `.env` → `~/.env` → 环境变量 `GLM_API_KEY`
  - **JLine 终端控制**：raw mode 单键读取、ESC 序列分类（`EscapeSequenceType`）、方向键历史导航填充（`seedBufferForHistoryNavigation`）、括号粘贴处理、`NonBlockingReader` 优化输入读取
  - **模式路由**：`/plan` 命令切换到 Plan 模式；`/index` `/search` `/graph` 触发 RAG 功能；未知 `/` 命令显示可用命令列表
  - **计划审查**：通过 `createPlanReviewHandler()` 注入 `PlanReviewHandler`
  - ReAct 模式：`Agent.run()` 循环调用 LLM + 工具
  - Plan 模式：`PlanExecuteAgent.run()` 先规划后执行
  - **流式输出适配**：空响应（已流式输出过）不重复打印
- 关键约定：API Key 找不到时直接 `System.exit(1)`，不进入交互循环
- 详见 `docs/chapter2-Plan-and-Execute实现.md` 第 8 节 + `docs/chapter4-RAG开发.md` 第 6 节 + `docs/chapter4.1-Streaming_and_Log实现.md` 第 8-9 节

### 4.2 `agent.Agent` — ReAct 循环（第 1 期基础，第 4.1 期增强）

- 文件：`src/main/java/com/paicli/agent/Agent.java`
- 职责：维护 `conversationHistory`，循环调用 LLM 直到无工具调用或达到 `MAX_ITERATIONS=10`
- 核心循环（详见 `docs/chapter1-ReAct和Tool Call实现.md`）：
  1. `llmClient.chat(history, toolRegistry.getToolDefinitions(), streamRenderer)` 流式调用
  2. 若 `response.hasToolCalls()`：`streamRenderer.flushPending()` 刷出中间文本 → 把 assistant 消息（含 tool_calls）+ 每个工具结果（`Message.tool(id, result)`）追加到 history，`continue`
  3. 否则：`streamRenderer.finish()` 收尾 → 打印 token 统计 → 追加 assistant 消息并返回
- **第 4.1 期增强**：
  - `StreamRenderer` 内部类：实现 `GLMClient.StreamListener`，三缓冲区设计（`pendingReasoning` 防空标题 + `lateReasoning` 收集延迟推理 + 双通道流式渲染）
  - `flushPending()`：工具调用前刷出缓冲区中间文本，防止延迟显示
  - `hasStreamedOutput()` → 流式输出后返回空字符串，避免重复
  - SLF4J 日志埋点（工具调用、token 用量、异常）
- 系统提示词硬编码在 `SYSTEM_PROMPT` 常量里，第 19 期会迁移到 `src/main/resources/prompts/` 分层架构
- 详见 `docs/chapter4.1-Streaming_and_Log实现.md` 第 5 节

### 4.3 `llm` 包 — LLM 客户端（第 1 期基础，第 4.1/7.1 期增强）

- 文件：`src/main/java/com/paicli/llm/` 下 5 个类
- **接口**：`LlmClient` — 定义 `chat()` 两个重载 + 内嵌数据模型（`Message` / `Tool` / `ToolCall` / `ChatResponse` / `StreamListener`）
- **抽象基类**：`AbstractOpenAiCompatibleClient` — HTTP 请求构建、SSE 流式解析、tool_call 增量合并、超时可配（`paicli.llm.*.timeout.seconds`）
- **具体实现**：
  - `GLMClient`：端点 `https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`，模型 `glm-5.1`
  - `DeepSeekClient`：端点 `https://api.deepseek.com/chat/completions`，模型 `deepseek-v4-flash`
- **工厂**：`LlmClientFactory` — 按 provider 名称创建，自动回退
- OkHttp 超时：connect 60s / read 300s（流式 SSE 需要放宽）/ write 60s / call 600s
- 内嵌 record 数据模型：
  - `Message`（role + content + tool_calls + tool_call_id + reasoningContent），提供 `system()` / `user()` / `assistant()` / `tool()` 静态工厂
  - `Tool`（name + description + parameters JSON Schema）
  - `ToolCall` / `Function`
  - `ChatResponse`（content + reasoningContent + toolCalls + inputTokens + outputTokens + `hasToolCalls()`）
  - `StreamListener`（流式回调接口，`NO_OP` 单例 + `onReasoningDelta` / `onContentDelta` 两个 default 方法）
- **第 4.1 期增强**：
  - `chatStream()`：SSE 逐行解析（`BufferedSource`），提取 `choices[0].delta.content` / `reasoning_content` / `tool_calls`，`[DONE]` 终止
  - `buildRequestBody()`：抽取请求体构建，支持 `stream` 参数
  - `mergeToolCallDeltas()` / `buildToolCalls()`：流式工具调用增量累加（`ToolCallAccumulator`）
  - `chat(messages, tools)` 保持签名不变，内部委托到 `chatStream()`
- 第 8 期会抽象出 `LlmClient` 接口与 `AbstractOpenAiCompatibleClient` 基类，届时 GLMClient 会瘦身为子类
- 详见 `docs/chapter4.1-Streaming_and_Log实现.md` 第 2 节

### 4.4 `tool.ToolRegistry` — 工具注册表（第 1 期基础，第 4/7/8 期增强）

- 文件：`src/main/java/com/paicli/tool/ToolRegistry.java`
- 8 个内置工具：
  | 工具名 | 参数 | 用途 | 期次 |
  |---|---|---|---|
  | `read_file` | `path` | 读取文件 | 1 |
  | `write_file` | `path`, `content` | 写文件（自动创建父目录） | 1 |
  | `list_dir` | `path` | 列目录 | 1 |
  | `execute_command` | `command` | `bash -c` 执行 Shell | 1 |
  | `create_project` | `name`, `type` | 创建 java/python/node 脚手架 | 1 |
  | `search_code` | `query`, `top_k` | 语义检索代码库（混合检索） | 4 |
  | `web_search` | `query`, `top_k` | 联网搜索（智谱 Web Search，复用 GLM_API_KEY） | 8 |
  | `web_fetch` | `url`, `max_chars` | 抓取 URL 提取正文 Markdown | 8 |
- 关键设计：
  - `Tool` record 含 `executor`（函数式接口 `ToolExecutor`），注册时用 lambda 提供执行逻辑
  - `createParameters(Param...)` 动态生成 JSON Schema（type/description/required），传给 LLM
  - `getToolDefinitions()` 剥离 executor，只把 name/description/parameters 暴露给 LLM
  - `executeTool(name, argumentsJson)` 用 Jackson 解析参数 → `Map<String,String>` → 调 executor
- **已知限制**：参数值用 `asText()` 强转字符串，嵌套对象/数组会丢结构（当前工具都是字符串/数字参数，不受影响；扩展时需改这里）

### 4.5 `agent.PlanExecuteAgent` — Plan-and-Execute 编排（第 2 期基础，第 3/4.1 期增强）

- 文件：`src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- 职责：计划执行编排 + HITL 审查流程
- 关键设计：
  - **模式路由由 CLI 层负责**：`run()` 直接走 `runWithPlan()`，不再用 `shouldPlan()` 启发式判断
  - **计划审查**：`reviewAndExecutePlan()` 调用注入的 `PlanReviewHandler.review()`，支持 EXECUTE/SUPPLEMENT/CANCEL 三种决策
    - SUPPLEMENT 时将补充要求拼接到原 goal，重新 `planner.createPlan()` 并再次审查
    - 用户可多次补充要求，直到满意或放弃
  - **分批并发执行**：`executePlan()` 用 `while(true) + getExecutableTasksInOrder()` 逐轮计算可执行任务
    - 单任务：直接在当前线程执行，避免线程池开销
    - 多任务：用 `ExecutorService` 线程池并行执行（**并发上限 4**，防止资源耗尽）
    - 僵局检测：while 退出但计划未完成且无失败 → 存在无法满足的依赖
  - **executeTask() 多轮工具调用**：单个 Task 内支持 ReAct 风格的多轮 LLM + 工具循环
    - `MAX_TASK_ITERATIONS = 5` 限制单 Task 最大迭代次数，防止无限循环
    - 消息历史在 Task 内维护，跨 Task 通过 `buildTaskContext()` 传递依赖结果
    - 工具结果回灌到消息历史，LLM 可基于结果决定下一步操作
  - **失败恢复**：失败 + 进度 < 50% 时触发 `Planner.replan()`
- 内部类型：`TaskExecutionResult`（封装成功/失败/流式输出结果）、`TaskRunResult`（流式标记传递）、`PlanReviewHandler`（审查回调接口）、`PlanReviewAction`（EXECUTE/SUPPLEMENT/CANCEL）、`PlanReviewDecision`（决策 + 补充说明）、`StreamState`（跨任务流式输出标记）、`TaskStreamRenderer`（任务级流式渲染器）
- **第 3 期增强**（Memory 联动）：共享会话上下文、轮开始前压缩、上下文感知规划、事实提取、Token 统计完善
- **第 4.1 期增强**（流式输出 + 日志）：
  - `TaskStreamRenderer`：任务级流式渲染器（`synchronized` 线程安全 + taskId 标签 + `flushPending` 中间刷出）
  - `StreamState`：`volatile` 跨任务流式标记共享
  - `TaskRunResult`：流式标记从 `executeTask` → `executePlan` → `buildFinalResult` 的传递链
  - 已流式输出的任务在 `buildFinalResult` 和完成打印中跳过，避免重复
  - SLF4J 日志埋点（plan 开始/完成、task 执行/完成/失败、工具调用）
- 详见 `docs/chapter2-Plan-and-Execute实现.md` 第 15 节 + `docs/chapter3-Memory实现.md` + `docs/chapter4.1-Streaming_and_Log实现.md` 第 6 节

### 4.5b `agent.AgentOrchestrator` — Multi-Agent 编排器（第 5 期新增）

- 文件：`src/main/java/com/paicli/agent/AgentOrchestrator.java`
- 职责：Multi-Agent 系统的"主"，采用主从架构管理团队、分配任务、路由消息、解决冲突
- **协作流程**：
  1. 用户提交任务 → 编排器交给规划者（SubAgent/PLANNER）
  2. 规划者拆解任务为 JSON 执行计划 → 编排器解析为 `List<ExecutionStep>`
  3. 编排器按依赖顺序将子任务分配给执行者（SubAgent/WORKER，默认 2 个轮询分配）
  4. 执行者返回结果 → 编排器交给检查者（SubAgent/REVIEWER）
  5. 检查者审批通过则完成，未通过则带反馈重试（最多 2 次）
  6. 所有子任务完成后，编排器汇总返回最终结果
- **并行策略**：
  - 同一依赖批次内部**并行**执行（最多 Worker 池大小并发，默认 2）
  - 每个并行步骤使用独立 `PrintStream` 缓冲流式输出，批次结束后按 `step_id` 顺序 flush 到 stdout
  - Worker 通过 `BlockingQueue` 池化分配，确保同一 Worker 不会被两个步骤并发占用
  - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
- **关键方法**：
  - `run(userInput)`：编排主流程（压缩上下文 → 检索记忆 → 规划 → 解析 → 波次执行 → 汇总 → 写回 sharedHistory + 长期记忆）
  - `parsePlan(planJson)`：两遍扫描解析 LLM 输出的 JSON 计划（去 markdown 包裹、ID 重编号、依赖回填、兼容 `tasks` 字段）
  - `getExecutableSteps(steps)`：隐式波次推进——每轮重新扫描状态表，找所有依赖已完成的 PENDING 步骤
  - `runStep(step, steps, retryCount, worker, reviewer, context, out)`：单步执行（Worker 执行 + Reviewer 审查 + 最多 2 次重试，重试时把反馈注入上下文）
  - `runBatchParallel(batch, steps, retryCount)`：多步骤线程池并行 + Worker 池化 + 按序 flush
  - `parseReviewApproval(content)`：JSON 优先解析 → 关键词回退 → 保守策略（解析失败默认不通过）
  - `parseReviewIssues(content)`：依次取 `issues` / `suggestions` / `summary` 字段
- **内部类型**：
  - `ExecutionStep` record：步骤数据模型（id / description / type / dependencies / result / StepStatus），提供 `pending()` / `withResult()` / `withFailed()` / `started()` 工厂方法
  - `StepStatus` enum：PENDING / RUNNING / COMPLETED / FAILED
- **构造器链**：支持 `(apiKey)` / `(apiKey, toolRegistry)` / `(apiKey, toolRegistry, memoryManager)` / `(apiKey, toolRegistry, memoryManager, sharedHistory)` 四种初始化方式，`Main` 注入共享历史实现三模式上下文互通
- **记忆集成**：
  - 短期：`sharedHistory`（compressContextIfNeeded → add user → write assistant）
  - 长期：`buildContextForQuery` 检索 → `extractAndSaveFacts` 提取
- 详见 `docs/chapter5-Multi_Agent开发.md`

### 4.5c `agent.SubAgent` — 子代理（第 5 期新增）

- 文件：`src/main/java/com/paicli/agent/SubAgent.java`
- 职责：可配置角色的轻量 Agent，每个实例有独立的角色、系统提示词和对话历史，但共享 LLM 客户端和工具注册表
- **三套角色系统提示词**：
  | 角色 | 提示词常量 | 是否使用工具 |
  |------|-----------|-------------|
  | `PLANNER` | `PLANNER_PROMPT`（任务分解 → JSON 执行计划） | ❌ |
  | `WORKER` | `WORKER_PROMPT`（工具调用 → 具体操作） | ✅ |
  | `REVIEWER` | `REVIEWER_PROMPT`（质量检查 → 审批 JSON） | ❌ |
- **关键方法**：
  - `execute(task, out)`：注入任务到对话历史 → ReAct 循环（最多 10 轮）→ 返回 `AgentMessage.result()` 或 `.error()`
  - `executeWithContext(task, context, out)`：带上下文注入的执行（Worker 接收依赖步骤结果）
  - `review(originalTask, executionResult, out)`：拼接原始任务 + 执行结果 → Reviewer 输出审批 JSON
  - `clearHistory()`：保留 system 提示词，清空对话历史
  - `shouldUseTools()`：只有 `WORKER` 角色返回 true
- **`SubAgentStreamRenderer`**（内部类）：实现 `GLMClient.StreamListener`
  - 双通道分离：reasoning → 「🧠 执行思考/规划思考/审查思考」+ content → 「🤖 执行结果/规划结果/审查结果」
  - 防空白标题：`pendingReasoning` 缓冲区等攒够实质内容才触发渲染
  - 迟到推理不渲染：content 开始后收到的 reasoning 不显示（终端无法回头插入），但完整 reasoning 仍写入 conversationHistory
  - 输出重定向：通过 `PrintStream out` 参数支持并行模式下写入独立 `ByteArrayOutputStream`
- 详见 `docs/chapter5-Multi_Agent开发.md` 第 4 节

### 4.5d `agent.AgentRole` — 角色枚举（第 5 期新增）

- 文件：`src/main/java/com/paicli/agent/AgentRole.java`
- 三个枚举值：`PLANNER("规划者", "负责分析用户任务，制定执行计划...")`、`WORKER("执行者", "负责执行具体任务步骤...")`、`REVIEWER("检查者", "负责检查执行结果的质量和正确性...")`
- 每个枚举值持有 `displayName`（中文显示名）和 `description`（职责描述）

### 4.5e `agent.AgentMessage` — 通信消息（第 5 期新增）

- 文件：`src/main/java/com/paicli/agent/AgentMessage.java`
- Java 17 `record`：`AgentMessage(fromAgent, fromRole, content, type)`
- 6 种消息类型（`Type` enum）：`TASK`（任务分配）、`RESULT`（执行结果）、`FEEDBACK`（审查反馈）、`APPROVAL`（批准）、`REJECTION`（拒绝）、`ERROR`（系统错误）
- 静态工厂方法：`task()` / `result()` / `feedback()` / `approval()` / `rejection()` / `error()`
- `Type.ERROR` 由调用方独立处理（与 `RESULT` 区分），SubAgent 在 LLM 调用失败或达到最大迭代时返回

### 4.6 `plan.Planner` — LLM 任务分解（第 2 期基础，第 3/4.1 期增强）

- 文件：`src/main/java/com/pacicli/plan/Planner.java`
- 职责：用 `PLANNING_PROMPT` 让 LLM 输出 `{summary, tasks:[{id,description,type,dependencies}]}` JSON
- 解析流程：
  1. 去除 ```` ```json ```` / ```` ``` ```` markdown 包裹
  2. 两遍扫描：先建 Task 节点（处理前向引用），再回填 dependencies
  3. `idMapping` 重写为 `task_1, task_2, ...` 避免 LLM 给的 id 重复
  4. 调 `ExecutionPlan.computeExecutionOrder()` 做拓扑排序，有环抛异常
- `replan(failedPlan, reason)`：把已完成任务列表和失败原因作为上下文重新调 `createPlan()`
- **第 3 期增强**：新增 `createPlan(String goal, String priorContext)` 重载——当 `priorContext` 非空时，把先前对话上下文拼入 user 消息
- **第 4.1 期增强**（流式规划 + 简单目标优化）：
  - `PlanningStreamRenderer`：流式渲染规划阶段的 LLM 推理过程（仅 reasoning 通道）
  - `isSimpleGoal()` / `createMinimalPlan()`：简单任务跳过 LLM 规划，直接生成单任务计划（长度 ≤ 30 且含简单操作词）
  - `PLANNING_PROMPT` 优化：新增规则 5-8（允许 1-3 任务、禁止无意义的中间文件读写、鼓励最短计划）
- 详见 `docs/chapter2-Plan-and-Execute实现.md` 第 3、15 节 + `docs/chapter4.1-Streaming_and_Log实现.md` 第 7 节

### 4.7 `plan.ExecutionPlan` — 计划 DAG（第 2 期）

- 文件：`src/main/java/com/pacicli/plan/ExecutionPlan.java`
- `LinkedHashMap<id, Task>` 保持插入顺序；`executionOrder` 存拓扑序
- `computeExecutionOrder()` 用 DFS + `visiting/visited` 双集合（三色标记法）检测环
- `getProgress()` 返回完成比例（用于判断是否触发 replan，阈值 50%）
- `getExecutionBatches()` 模拟执行过程，计算 DAG 可分成几批并发执行
- `visualize()` 输出 ASCII 表格（含状态 emoji）；`summarize()` 输出折叠摘要
- 详见 `docs/chapter2-Plan-and-Execute实现.md` 第 4 节

### 4.8 `plan.Task` — 任务节点（第 2 期）

- 文件：`src/main/java/com/paicli/plan/Task.java`
- `TaskType`：`PLANNING / FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION`
- `TaskStatus`：`PENDING / RUNNING / COMPLETED / FAILED / SKIPPED`
- `dependencies`（我依赖谁） + `dependents`（谁依赖我）双向链
- `isExecutable(allTasks)`：状态必须 PENDING 且所有 dependencies 已 COMPLETED

### 4.9 `memory` 包 — Memory 系统（第 3 期）

- 文件：`src/main/java/com/paicli/memory/` 下 8 个类（见目录树）
- **整体设计**：
  - 对话上下文由 Agent 自维护的 `conversationHistory`（`List<GLMClient.Message>`）承担，Memory 模块不持有对话上下文。
  - 长期记忆以 `MemoryEntry` 形式持久化到 `~/.paicli/memory/long_term_memory.json`。
  - **Message / MemoryEntry 边界严格单向**：仅在 `ContextCompressor.extractFacts` 处将 `List<Message>` → LLM 提取 → 事实文本 → `LongTermMemory.store` 包成 `MemoryEntry` 落盘。不存在双向重建。
- **共享上下文架构**：`Main.java` 启动时创建会话级单例 `sharedHistory` + `sharedMemory`，注入 ReAct 和 Plan 两个 Agent。模式切换时对话历史连续、长期记忆互通。
- **核心类**：
  | 类 | 职责 |
  |---|---|
  | `Memory` | 统一接口（store/retrieve/search/getAll/delete/clear） |
  | `MemoryEntry` | 数据模型（id/content/type/timestamp/metadata/tokenCount） |
  | `LongTermMemory` | 长期记忆持久化（JSON 落盘、ConcurrentHashMap、内容去重） |
  | `ContextCompressor` | Map-Reduce 对话压缩（user-boundary 切分）+ 事实提取 |
  | `MemoryRetriever` | 长期记忆检索（关键词匹配 + 相关度排序，无时间衰减） |
  | `MemoryQueryTokenizer` | 基于 jieba 的中文分词器 |
  | `TokenBudget` | 上下文窗口预算管理（`isWithinBudget` 触发压缩）+ Token 使用统计 |
  | `MemoryManager` | 门面类（组合上述组件） |
- 详见 `docs/chapter3-Memory实现.md`

### 4.10 `rag` 包 — RAG 代码检索（第 4 期）

- 文件：`src/main/java/com/paicli/rag/` 下 10 个类（见目录树）+ `com.paicli.util.JiebaSegmenterFactory`
- **整体设计**：
  - 代码库索引存储于 `~/.paicli/rag/codebase.db`（SQLite），两张表：`code_chunks`（代码块 + 向量 JSON）+ `code_relations`（关系图谱）
  - 向量化通过 `EmbeddingClient` 调用 Embedding API（支持 Ollama / OpenAI 兼容 / 智谱 GLM），通过 `.env` 配置 provider
  - 检索使用**混合策略**：语义检索（余弦相似度 TopK）+ 关键词检索（jieba 分词 + SQL LIKE）+ 合并加权排序
- **三条 CLI 命令**：
  | 命令 | 功能 | 对应方法 |
  |---|---|---|
  | `/index [path]` | 索引代码库 | `CodeIndex.index()` → 遍历→分块→向量化→入库 |
  | `/search <query>` | 混合检索代码 | `CodeRetriever.hybridSearch()` → 语义 + 关键词 → 合并排序 |
  | `/graph <类名>` | 查询代码关系图谱 | `CodeRetriever.getRelationGraph()` → `VectorStore.getRelations()` |
- **一个 LLM 工具**：`search_code` — 让 Agent 在 ReAct 循环中主动检索代码库
- **核心类**：
  | 类 | 职责 |
  |---|---|
  | `CodeChunk` | 数据模型（filePath/chunkType/name/content/startLine/endLine） |
  | `CodeChunker` | 代码分块：Java → AST 按类/方法切分；非 Java → 2000 字符分段 |
  | `CodeAnalyzer` | JavaParser AST 提取 5 种关系（extends/implements/imports/calls/contains） |
  | `CodeRelation` | 关系数据模型（fromFile/fromName → toFile/toName + relationType） |
  | `CodeIndex` | 索引编排器：遍历→分块→向量化→关系提取→持久化，支持进度回调 |
  | `EmbeddingClient` | Embedding API 客户端，支持 Ollama/OpenAI/GLM 多 provider |
  | `VectorStore` | SQLite 持久化 + 余弦相似度检索 + 关键词 LIKE 检索 + 图谱查询 |
  | `CodeRetriever` | 混合检索入口：semanticSearch + keywordSearch → mergeResult + boost → limitPerFile |
  | `RagQueryTokenizer` | jieba 中文分词 + ASCII 标识符提取 + 停用词过滤 |
  | `SearchResultFormatter` | 双模式格式化（CLI 输出摘要+片段；Tool 输出结构化结果+导航建议） |
- **关键机制**：
  - **混合检索排序**：语义候选（0.0~1.0）+ 关键词加权（基准 0.3，name 命中 +0.3，file 命中 +0.1，content 命中 +0.1，双重命中 +0.1）+ 代码类型奖励（method +0.15，class +0.10）
  - **全量内存余弦相似度**：每次检索从 SQLite 加载当前项目全部向量到内存计算，O(n) 时间复杂度。代码库几百到几千块足够；规模再大换 FAISS/pgvector
  - **事务保护写入**：`setAutoCommit(false)` + `executeBatch()` + rollback on error
  - **`.env` 全局加载**：`Main.loadEnvConfig()` 把所有 `KEY=VALUE` → `System.setProperty()`，`EmbeddingClient` 无需单独解析 `.env`
  - **无参 `/search` 三层兜底**：已索引 → 显示统计+示例；未索引 → 提示 `/index`；异常 → 降级提示
- **依赖**：sqlite-jdbc 3.49、javaparser-core 3.28、jieba-analysis 1.0.2
- 详见 `docs/chapter4-RAG开发.md`

### 4.11 `util` 包 — 工具类（第 4.1 期新增）

- 文件：`src/main/java/com/paicli/util/AnsiStyle.java`、`src/main/java/com/paicli/util/TerminalMarkdownRenderer.java`（`JiebaSegmenterFactory.java` 为第 4 期已有）
- **`AnsiStyle`**：终端 ANSI 样式辅助类，提供 `heading()`（粗体青色）、`section()`（粗体绿色）、`subtle()`（暗色灰色）、`codeLabel()`（粗体黄色）、`quotePrefix()`（暗色青色）、`emphasis()`（粗体）等静态方法；通过 `NO_COLOR` 环境变量或 `TERM=dumb` 自动禁用颜色
- **`TerminalMarkdownRenderer`**：轻量终端 Markdown 流式渲染器，支持标题（H1-H6）、有序/无序列表、引用、代码块（`┌─` / `└─` 包裹）、表格（ASCII 或 key-value 两种模式）、行内格式（粗体/斜体/代码/链接→纯文本去除标记）
  - 核心方法：`append(chunk)` 流式追加 → `flushCompleteLines()` 按行刷出 → `flushPending()` 强制刷出残留文本 → `finish()` 收尾关闭代码块
- 详见 `docs/chapter4.1-Streaming_and_Log实现.md` 第 3-4 节

### 4.12 `hitl` 包 — HITL 审批系统（第 6 期）

- 文件：`src/main/java/com/paicli/hitl/` 下 6 个类（见目录树）
- **整体设计**：
  - `ApprovalPolicy` 通过静态规则识别危险工具（`write_file` / `execute_command` / `create_project`），提供三级危险等级（🔴高危 / 🟡中危 / 🟢安全）
  - `HitlToolRegistry` 作为透明拦截层继承 `ToolRegistry`，HITL 关闭时行为与父类完全相同
  - `TerminalHitlHandler` 默认关闭，通过 `/hitl on|off` 运行时切换；支持 `[y/Enter]` 批准 / `[a]` 全部放行 / `[n]` 拒绝 / `[s]` 跳过 / `[m]` 修改参数后执行
  - `ApprovedAllTools` 在 `/clear` 时自动重置
- **Agent 集成**：`Agent` 和 `PlanExecuteAgent` 新增接受外部 `ToolRegistry` 的构造器，`Main` 启动时将 `HitlToolRegistry` 注入三个 Agent 模式（ReAct / Plan / Team）
- **CLI 命令**：`/hitl on|off|status` + `/memory clear`
- **与流式输出的协同**：`StreamRenderer.resetBetweenIterations()` 在工具调用前 flush 并重置渲染器，避免 Markdown pending 文本被 HITL 提示"跨过"

### 4.13 `resources/logback.xml` — 日志配置（第 4.1 期新增）

- 文件：`src/main/resources/logback.xml`
- 配置：`RollingFileAppender` + `SizeAndTimeBasedRollingPolicy`
  - 日志文件：`~/.paicli/logs/paicli.log`
  - 滚动策略：按天 + 按大小（`maxFileSize`）双重触发
  - 归档压缩：`.gz` 存储
  - 自动清理：`maxHistory`（最大保留天数）+ `totalSizeCap`（总容量上限）+ `cleanHistoryOnStart`
- 日志级别通过 `paicli.log.level` 系统属性 / `PAICLI_LOG_LEVEL` 环境变量 / `.env` 三层回退配置（默认 `INFO`）
- 注意：`pom.xml` 中已删除 `slf4j-simple`，确保 logback 为唯一 SLF4J binding
- 详见 `docs/chapter4.1-Streaming_and_Log实现.md` 第 8 节

### 4.14 `tool.ToolRegistry` — 第 7 期增强（异步并行工具执行）

- 文件：`src/main/java/com/paicli/tool/ToolRegistry.java`、`src/main/java/com/paicli/agent/Agent.java`、`src/main/java/com/paicli/agent/PlanExecuteAgent.java`、`src/main/java/com/paicli/agent/SubAgent.java`
- **新增类型**：
  - `ToolInvocation` record：工具调用请求模型 `(id, name, argumentsJson)`
  - `ToolExecutionResult` record：工具执行结果模型 `(id, name, argumentsJson, result, elapsedMillis, timedOut)`
- **核心方法**：
  - `ToolRegistry.executeTools(List<ToolInvocation>)`：同一轮 LLM 返回多个 `tool_calls` 时并行执行。单调用走原 `executeTool()`，多调用走 daemon 线程池（最多 4 线程），使用 `invokeAll` + 批次超时（默认 90 秒）。超时工具会被取消并返回可回灌的超时结果。
- **Agent / PlanExecuteAgent / SubAgent 集成**：
  - 各 Agent 新增 `executeToolCalls()` 方法：将 LLM 返回的 `toolCalls` 转为 `List<ToolInvocation>`，调 `toolRegistry.executeTools()`，结果按原顺序回灌到对话历史。
  - 新增 `printToolCalls()` / `toolLabel()` / `extractKeyParam()`：按工具名分组打印紧凑的工具调用摘要（📖 读取 N 个文件 / ⚡ 执行 N 条命令等），替代原先逐条打印 `🔧 执行工具: xxx` 的冗长输出。
- **PlanExecuteAgent 增强**：
  - `executeTask()` 新增 `PrintStream out` 参数，`executeTaskBatch()` 并行任务使用 `Map<String, ByteArrayOutputStream>` + 独立 `PrintStream` 缓冲，批次完成后按 Task ID 顺序 flush 到 stdout，避免多线程写 `System.out` 造成输出交错。
  - `TaskStreamRenderer` 重构：新增 `pendingReasoning` 缓冲区（防空白标题）、`lateReasoning` 收集（防 content 开始后追加的 reasoning 丢失）、`flushLateReasoning()` 独立展示补充思考、标签从「任务结果」改为「任务输出」（避免 tool-call 前的叙述被误标为最终结果）。
- **Agent.StreamRenderer 增强**：
  - 新增 `reasoningHeadingPrinted` 标记：同一次 ReAct 运行中只打印一次「🧠 思考过程」标题，工具调用前后的 reasoning 归在同一块下。
  - 新增 `containsLineBreak()` 检查：pending reasoning 不含换行时暂不触发标题，避免空白标题。
  - 新增 `flushPendingReasoning()` / `printReasoningHeadingIfNeeded()`：在 `resetBetweenIterations()` / `finish()` 中统一刷出 pending 文本。
- **Prompts 增强**：
  - ReAct `SYSTEM_PROMPT`、Plan `EXECUTION_PROMPT`、SubAgent `WORKER_PROMPT` 均新增"同一轮返回多个工具调用时系统会并行执行"的提示，并给出同时读取多个文件的示例。
  - SubAgent `PLANNER_PROMPT` 新增规则 7-8：鼓励无依赖步骤（让编排器能并行分配），只有真正需要前一步结果时才写 dependencies。
- **其他改动**：
  - `AgentOrchestrator` 并行执行器 `shutdown()` → `shutdownNow()`。
  - `pom.xml` 新增 `maven-surefire-plugin` 3.2.5 + UTF-8 `argLine`。
  - CLI 版本号 6.0.0 → 7.0.0，banner `"HITL Approval CLI"` → `"Async Tool CLI"`。
- **HITL 兼容**：`HitlToolRegistry` 继承 `ToolRegistry`，`executeTools()` 自动获得 HITL 拦截能力，无需额外适配。
- 详见 `docs/Chapter7-Async_Parallel实现.md`

### 4.15 `web` 包 — Web Search Tool 联网工具（第 8 期）

- 文件：`src/main/java/com/paicli/web/` 下 10 个类（见目录树，含 `agent/AgentBudget.java`）
- **整体设计**：
  - `SearchProvider` 接口抽象三种搜索引擎实现，`SearchProviderFactory` 按 `GLM_API_KEY` / `SERPAPI_KEY` / `SEARXNG_URL` 自动选择
  - `web_search` 和 `web_fetch` 两个工具注册在 `ToolRegistry.registerWebTools()` 中，通过懒加载 getter 初始化 provider
  - `web_fetch` 流水线：`NetworkPolicy.checkUrl()`（SSRF 防护）→ `NetworkPolicy.acquire()`（限流）→ `WebFetcher.fetch()`（HTTP GET）→ `HtmlExtractor.extract()`（HTML → Markdown）→ 截断格式化
- **核心类**：

| 类 | 职责 |
|---|---|
| `SearchProvider` | 搜索引擎接口（name/isReady/unavailableHint/search） |
| `ZhipuSearchProvider` | **默认 provider**：智谱 Web Search（`POST /api/paas/v4/web_search`），复用 GLM_API_KEY |
| `SerpApiSearchProvider` | 骨架实现：商业聚合 API（需 SERPAPI_KEY） |
| `SearxngSearchProvider` | 骨架实现：开源自托管搜索引擎（需 SEARXNG_URL） |
| `SearchProviderFactory` | 工厂类：自动按 Key/URL 选择 provider，支持显式 SEARCH_PROVIDER 指定 |
| `SearchResult` | 搜索结果 record（position/title/url/snippet/source） |
| `WebFetcher` | HTTP 抓取器（5MB 上限、30s 超时、charset 解析） |
| `HtmlExtractor` | Jsoup 极简 readability（去噪→打分选主容器→递归转 Markdown） |
| `NetworkPolicy` | 安全策略（scheme 白名单 + SSRF 黑名单 + Token Bucket 限流 60s/30 次） |
| `FetchResult` | web_fetch 结果 record（含 bodyEmpty 标记，提示 SPA 空正文边界） |
| `AgentBudget`（位于 `agent/` 包） | Agent 循环退出预算：Token 预算（300K）+ 停滞检测 + 硬轮数兜底（50 轮），替代硬编码 MAX_ITERATIONS |

- **配置（`.env`）**：
  ```bash
  # 搜索 provider（不配置时自动按 Key/URL 判断）
  # SEARCH_PROVIDER=zhipu
  # 智谱搜索引擎（search_std/search_pro/search_pro_sogou/search_pro_quark）
  # ZHIPU_SEARCH_ENGINE=search_pro
  # SerpAPI Key（SEARCH_PROVIDER=serpapi 时启用）
  # SERPAPI_KEY=your_key
  # SearXNG 地址（SEARCH_PROVIDER=searxng 时启用）
  # SEARXNG_URL=http://localhost:8888
  ```
- **Agent 集成**：
  - Agent / SubAgent / PlanExecuteAgent 的 Prompt 均已增加 web 工具说明 + 工具选择优先级规则
  - Agent / SubAgent 的循环已从硬编码 `MAX_ITERATIONS=10` 改为 `AgentBudget.fromSystemProperties()` 动态预算
  - `AbstractOpenAiCompatibleClient` HTTP 超时改为系统属性可配（`paicli.llm.*.timeout.seconds`）
- **依赖**：jsoup 1.18.1（HTML 解析）
- 详见 `docs/Chapter8-Web_Search_Tool开发.md`

---

## 5. 开发与运行

### 5.1 环境准备

```bash
# 在项目根目录或 ~ 下放 .env（支持所有 KEY=VALUE 配置）
cat > .env << 'EOF'
GLM_API_KEY=your_zhipu_api_key
DEEPSEEK_API_KEY=your_deepseek_api_key
# Embedding 配置（可选，默认 Ollama 本地）
EMBEDDING_PROVIDER=glm
EMBEDDING_MODEL=embedding-3
EMBEDDING_API_KEY=your_zhipu_api_key
# Web 搜索配置（可选，有 GLM_API_KEY 时自动启用 zhipu）
# SEARCH_PROVIDER=zhipu
# ZHIPU_SEARCH_ENGINE=search_pro
EOF
```

### 5.2 构建 / 运行

```bash
./mvnw clean package          # 打 fat jar
./mvnw spring-boot:run        # 直接跑（用 spring-boot 插件，但应用本身不是 Spring Boot）
java -jar target/paicli-0.0.1-SNAPSHOT.jar
```

### 5.3 测试

`mvn test` 使用 `maven-surefire-plugin` 3.2.5（UTF-8 编码）。当前有少量单元测试，后续期次逐步补充。

---

## 6. 待落地期次（占位，不要提前实现）

| 期次 | 主题 | 关键类（计划） | 状态 |
|---|---|---|---|
| 3 | Memory 系统 | `MemoryManager` / `ContextCompressor` / `LongTermMemory` / `MemoryRetriever` / `TokenBudget` | 已完成 → [4.9](#49-memory-包--memory-系统第-3-期) + `docs/chapter3-Memory实现.md` |
| 4 | RAG 检索 | `VectorStore` / `CodeIndex` / `CodeChunker` / `CodeAnalyzer` / `EmbeddingClient` / `CodeRetriever` | 已完成 → [4.10](#410-rag-包--rag-代码检索第-4-期) + `docs/chapter4-RAG开发.md` |
| 5 | Multi-Agent | `AgentOrchestrator` / `SubAgent` / `AgentRole` / `AgentMessage` | 已完成 → [4.5b–4.5e](#45b-agentagentorchestrator--multi-agent-编排器第-5-期新增) + `docs/chapter5-Multi_Agent开发.md` |
| 6 | HITL 审批 | `HitlToolRegistry` / `ApprovalPolicy` / `ApprovalRequest` / `ApprovalResult` / `HitlHandler` / `TerminalHitlHandler` | 已完成 → [4.12](#412-hitl-包--hitl-审批系统第-6-期) |
| 7 | 异步并行 | `ToolRegistry.executeTools` / `ToolInvocation` / `ToolExecutionResult` | 已完成 → [4.14](#414-toolregistry-第-7-期增强) |
| 8 | 多模型 + 联网工具 | `LlmClient` 接口 / `AbstractOpenAiCompatibleClient` / `DeepSeekClient` / `AgentBudget` / `SearchProvider` / `ZhipuSearchProvider` / `WebFetcher` / `HtmlExtractor` / `NetworkPolicy` | 已完成 → [4.15](#415-web-包--web-search-tool-联网工具第-8-期) + `docs/Chapter7.1-LLMClient开发.md` + `docs/Chapter8-Web_Search_Tool开发.md` |
| 9 | MCP 协议核心 | `JsonRpcClient` / `McpTransport` / `McpServerManager` | 未开始 |
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
- **数据模型**：优先用 Java 17 `record`（参考 `GLMClient.Message` / `ToolRegistry.Tool` / `CodeChunk` / `CodeRelation`）
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
