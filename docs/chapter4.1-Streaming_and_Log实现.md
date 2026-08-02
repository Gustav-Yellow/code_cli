# Chapter 4.1：流式 LLM 输出、日志记录与 CLI 修复

> 本文档记录 paicli 项目中新增的流式 LLM 输出（SSE 解析 + 终端 Markdown 实时渲染）、Logback 日志系统以及 CLI 输入处理的增强与修复。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. GLMClient：SSE 流式解析](#2-glmclient-sse-流式解析)
  - [2.1 非流式 → 流式的演进](#21-非流式--流式的演进)
  - [2.2 chatStream() 核心流程](#22-chatstream-核心流程)
  - [2.3 StreamListener 回调接口](#23-streamlistener-回调接口)
  - [2.4 流式工具调用累加](#24-流式工具调用累加)
  - [2.5 buildRequestBody 抽取](#25-buildrequestbody-抽取)
- [3. TerminalMarkdownRenderer：终端 Markdown 渲染器](#3-terminalmarkdownrenderer-终端-markdown-渲染器)
  - [3.1 设计思路](#31-设计思路)
  - [3.2 流式追加与行缓冲](#32-流式追加与行缓冲)
  - [3.3 flushPending：中间刷出机制](#33-flushpending中间刷出机制)
  - [3.4 支持的 Markdown 语法](#34-支持的-markdown-语法)
- [4. AnsiStyle：ANSI 样式辅助](#4-ansistyleansi-样式辅助)
- [5. Agent 流式集成](#5-agent-流式集成)
  - [5.1 StreamRenderer 内部类](#51-streamrenderer-内部类)
  - [5.2 流式后跳过重复输出](#52-流式后跳过重复输出)
  - [5.3 工具调用前的中间文本刷出](#53-工具调用前的中间文本刷出)
- [6. PlanExecuteAgent 流式集成](#6-planexecuteagent-流式集成)
  - [6.1 TaskStreamRenderer：任务级流式渲染器](#61-taskstreamrenderer任务级流式渲染器)
  - [6.2 StreamState：跨任务共享标记](#62-streamstate跨任务共享标记)
  - [6.3 TaskRunResult：流式标记传递](#63-taskrunresult流式标记传递)
  - [6.4 buildFinalResult 适配](#64-buildfinalresult-适配)
- [7. Planner 流式集成](#7-planner-流式集成)
  - [7.1 PlanningStreamRenderer](#71-planningstreamrenderer)
  - [7.2 简单目标快速路径](#72-简单目标快速路径)
- [8. Logback 日志系统](#8-logback-日志系统)
  - [8.1 日志架构](#81-日志架构)
  - [8.2 logback.xml 配置](#82-logbackxml-配置)
  - [8.3 日志级别与路径配置](#83-日志级别与路径配置)
  - [8.4 代码中的日志埋点](#84-代码中的日志埋点)
- [9. CLI 输入处理修复](#9-cli-输入处理修复)
  - [9.1 ESC 序列分类](#91-esc-序列分类)
  - [9.2 方向键历史导航填充](#92-方向键历史导航填充)
  - [9.3 未知命令提示](#93-未知命令提示)
  - [9.4 readInputBurst 优化](#94-readinputburst-优化)
- [10. 端到端流程示例](#10-端到端流程示例)
  - [10.1 ReAct 模式流式输出](#101-react-模式流式输出)
  - [10.2 Plan 模式流式输出](#102-plan-模式流式输出)
- [11. 踩坑记录与关键设计要点](#11-踩坑记录与关键设计要点)

---

## 1. 整体架构概览

本轮在原有非流式 LLM 调用的基础上，新增了流式输出管道：

```
┌──────────────────────────────────────────────────────────────────┐
│                        LLM API（GLM / DeepSeek / OpenAI 兼容）     │
│                    SSE 流: data: {choices:[{delta:{...}}]}       │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                    GLMClient.chatStream()                        │
│  - 逐行解析 SSE data                                             │
│  - 提取 reasoning/content delta → StreamListener 回调             │
│  - 累加 tool_calls delta → ToolCallAccumulator                   │
│  - 汇总后返回 ChatResponse                                       │
└──────────────────────────┬───────────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                  ▼
┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐
│ Agent           │ │ PlanExecute  │ │ Planner          │
│ .StreamRenderer │ │ Agent        │ │ .PlanningStream  │
│                 │ │ .TaskStream  │ │ Renderer         │
│                 │ │ Renderer     │ │                  │
└────────┬────────┘ └──────┬───────┘ └────────┬─────────┘
         │                 │                   │
         └─────────────────┼───────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                  TerminalMarkdownRenderer                        │
│  - 流式追加 append(chunk)                                        │
│  - 行缓冲 + flushCompleteLines                                   │
│  - 中间刷出 flushPending                                         │
│  - 结束收尾 finish                                               │
└──────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                     System.out（终端）                            │
│              带 AnsiStyle 彩色标题 + Markdown 渲染                 │
└──────────────────────────────────────────────────────────────────┘
```

**关键设计决策**：
- `chat()` 保持签名不变——调用方（Agent、PlanExecuteAgent、Planner）只需多传一个 `StreamListener` 参数
- 流式输出与工具调用打印**互不干扰**——两条独立的 System.out 管道，分属不同执行阶段
- 日志系统通过 Logback 写文件，通过删除 slf4j-simple 确保不输出到终端

---

## 2. GLMClient：SSE 流式解析

### 2.1 非流式 → 流式的演进

| | 旧（非流式） | 新（流式） |
|---|---|---|
| HTTP 请求 | `stream: false` | `stream: true` |
| 响应读取 | `responseBodyObj.string()` 一次性读 | `BufferedSource` 逐行 SSE |
| 响应体 | `choices[0].message` | `choices[0].delta`（首帧回退到 message）|
| 工具调用 | JSON 数组一次性解析 | delta 累加拼接 |

**兼容性保证**：旧的 `chat(messages, tools)` 签名保持不变，内部委托到 `chatStream()`。调用方零改动。

### 2.2 chatStream() 核心流程

```java
public ChatResponse chatStream(List<Message> messages, List<Tool> tools,
                                StreamListener listener) throws IOException {
    // 1. 构建请求体（stream: true）
    RequestBody body = RequestBody.create(
            buildRequestBody(messages, tools, true).toString(),
            MediaType.parse("application/json"));

    // 2. 发送 HTTP 请求
    try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
        BufferedSource source = responseBodyObj.source();

        // 3. 逐行读取 SSE
        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) break;

            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;

            String payload = trimmed.substring("data:".length()).trim();
            if ("[DONE]".equals(payload)) break;

            // 4. 解析 JSON
            JsonNode root = mapper.readTree(payload);

            // 5. 提取 usage（token 统计）
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                outputTokens = usage.path("completion_tokens").asInt(outputTokens);
            }

            // 6. 提取 delta → content / reasoning / tool_calls
            JsonNode delta = choice.path("delta");
            if (delta.isMissingNode()) delta = choice.path("message"); // 首帧兼容

            // reasoning delta → listener 回调
            if (!reasoningDelta.isEmpty()) {
                reasoning.append(reasoningDelta);
                streamListener.onReasoningDelta(reasoningDelta);
            }

            // content delta → listener 回调
            if (!contentDelta.isEmpty()) {
                content.append(contentDelta);
                streamListener.onContentDelta(contentDelta);
            }

            // tool_calls delta → 累加器静默收集
            mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
        }

        // 7. 汇总返回
        return new ChatResponse(role, content.toString(), reasoning.toString(),
                buildToolCalls(toolAccumulators), inputTokens, outputTokens);
    }
}
```

### 2.3 StreamListener 回调接口

```java
public interface StreamListener {
    StreamListener NO_OP = new StreamListener() {};

    default void onReasoningDelta(String delta) {}
    default void onContentDelta(String delta) {}
}
```

- 所有方法都是 `default`，实现者只覆写需要的
- `NO_OP` 单例：`chat(messages, tools)` 无参调用时使用，静默忽略所有 delta
- 接口只传文本，不传 JSON 结构——解耦 SSE 解析与终端渲染

### 2.4 流式工具调用累加

GLM/DeepSeek/OpenAI 在流式模式下，工具调用以增量 delta 形式分多帧到达：

```json
// 第 1 帧：id + function.name
{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"list_dir"}}]}}]}

// 第 2 帧：function.arguments
{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"./myapp\"}"}}]}}]}
```

`ToolCallAccumulator` 用 `StringBuilder` 拼接 `name` 和 `arguments`，`id` 直接赋值：

```java
private static final class ToolCallAccumulator {
    private String id;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
}
```

`mergeToolCallDeltas()` 通过 `index` 字段定位对应的累加器，`buildToolCalls()` 最终将累加器转为 `List<ToolCall>`。

### 2.5 buildRequestBody 抽取

原来 `chat()` 方法中内联的请求体构建逻辑抽取为 `buildRequestBody(List<Message> messages, List<Tool> tools, boolean stream)`：

- `stream=true` 时追加 `"stream": true` 字段
- `stream=false` 时仍可用于非流式调用（兼容保留）
- 代码复用：`chat()` → `chatStream()` 统一走同一个请求体构建

---

## 3. TerminalMarkdownRenderer：终端 Markdown 渲染器

### 3.1 设计思路

目标不是完整支持所有 Markdown 语法，而是把**常见的标题、列表、表格、引用和代码块**渲染成适合 CLI 终端阅读的纯文本布局。核心约束：**流式追加**——内容以 delta chunk 形式逐帧到达，渲染器必须能增量输出。

### 3.2 流式追加与行缓冲

```java
public void append(String chunk) {
    pending.append(chunk);
    flushCompleteLines();   // 只刷出以 \n 结尾的完整行
}
```

**行缓冲的必要性**：Markdown 的语义单元是"行"（标题行、列表项、表格分隔线等），必须等到完整行才能判断渲染方式。不以 `\n` 结尾的内容暂存在 `pending` 缓冲区。

### 3.3 flushPending：中间刷出机制

ReAct 循环中，LLM 在工具调用前输出的中间文本（如"用户想查看...让我先列出目录结构"）往往不以 `\n` 结尾。如果等到 `finish()` 才刷出，这些文本会卡在缓冲区直到所有工具调用完成后才出现。

**`flushPending()` 方法**：先刷出完整行，再将缓冲区中不以 `\n` 结尾的残留文本也强制输出——不关闭代码块、不渲表格、不做任何 `finish()` 的收尾工作。在每次检测到工具调用后、执行工具前调用。

```java
public void flushPending() {
    flushCompleteLines();
    if (pending.length() > 0) {
        processLine(pending.toString());
        pending.setLength(0);
    }
}
```

与 `finish()` 的区别：

| | flushPending() | finish() |
|---|---|---|
| 刷完整行 | ✅ | ✅ |
| 刷残留文本 | ✅ | ✅ |
| 关闭代码块 | ❌ | ✅ |
| 渲染待渲表格 | ❌ | ✅ |
| 关闭 Markdown 块 | ❌ | ✅ |

### 3.4 支持的 Markdown 语法

| 语法 | 渲染效果 |
|------|---------|
| `# Heading` (1-6 级) | `AnsiStyle.heading()` 粗体青色 + 下划线 |
| `- item` / `* item` | `- item` 缩进列表 |
| `1. item` | `1. item` 有序列表 |
| `> quote` | `│ quote` 青色前缀 |
| ` ``` code ``` ` | `┌─ code: lang` / `└─ end` 代码块包裹 |
| `\| col1 \| col2 \|` | ASCII 表格（`+---+---+`）或 key-value 模式 |
| `**bold**` / `*italic*` / `` `code` `` | 去除标记，纯文本显示 |

---

## 4. AnsiStyle：ANSI 样式辅助

```java
public final class AnsiStyle {
    public static String heading(String text)   → 粗体青色
    public static String section(String text)   → 粗体绿色
    public static String subtle(String text)    → 暗色灰色
    public static String codeLabel(String text) → 粗体黄色
    public static String quotePrefix(String text)→ 暗色青色
    public static String emphasis(String text)  → 粗体
}
```

- 通过 `NO_COLOR` 环境变量或 `TERM=dumb` 自动禁用颜色
- `paicli.render.color` 系统属性可显式控制
- 所有 `wrap()` 调用检查 `ENABLED` 标志

---

## 5. Agent 流式集成

### 5.1 StreamRenderer 内部类

```java
private static final class StreamRenderer implements GLMClient.StreamListener {
    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean streamedOutput;
}
```

**三缓冲区设计**：

| 缓冲区 | 用途 |
|--------|------|
| `pendingReasoning` | 暂存 reasoning delta，直到攒出非空白内容才触发标题打印（避免空标题） |
| `lateReasoning` | 收集 content 开始后才到达的 reasoning delta，在 `finish()` 中以独立区块展示 |
| `reasoningRenderer` / `contentRenderer` | 正常流式渲染时的双通道输出 |

**四种渲染场景**：

```java
// 场景 1：reasoning 先到，且内容非空白 → 打印"🧠 思考过程"并流式渲染
public void onReasoningDelta(String delta) {
    if (!reasoningStarted) {
        pendingReasoning.append(delta);
        if (pendingReasoning.toString().isBlank()) {
            return;  // 还没攒出实质内容，等待下一帧
        }
        System.out.println(AnsiStyle.heading("🧠 思考过程"));
        reasoningRenderer = new TerminalMarkdownRenderer(System.out);
        reasoningRenderer.append(pendingReasoning.toString());  // 之前攒的一起输出
        pendingReasoning.setLength(0);
        reasoningStarted = true;
    } else {
        reasoningRenderer.append(delta);
    }
}

// 场景 2：reasoning 在 content 之后到达 → 收集到 lateReasoning
public void onReasoningDelta(String delta) {
    if (contentStarted) {
        lateReasoning.append(delta);  // content 已开始，缓冲到后面
        return;
    }
    // ... 正常路径
}

// 场景 3：content 来了，reasoning 已开启 → 收尾 reasoning 区再开始 content
// 场景 4：content 来了，reasoning 还没触发（pending 有内容）→ 先补打再切换
public void onContentDelta(String delta) {
    if (!contentStarted) {
        if (reasoningStarted && reasoningRenderer != null) {
            reasoningRenderer.finish();       // 关闭 reasoning 渲染器
            System.out.println();
        } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
            // pending 里攒了但没有触发标题 → 补救：补打思考过程
            System.out.println(AnsiStyle.heading("🧠 思考过程"));
            TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
            r.append(pendingReasoning.toString());
            r.finish();
            System.out.println();
            pendingReasoning.setLength(0);
        }
        System.out.println(AnsiStyle.section("🤖 最终结果"));
        contentRenderer = new TerminalMarkdownRenderer(System.out);
        contentStarted = true;
    }
    contentRenderer.append(delta);
}
```

### 5.2 finish() 收尾与补充思考

```java
private void finish() {
    if (reasoningRenderer != null) reasoningRenderer.finish();
    if (contentRenderer != null) contentRenderer.finish();

    // 输出 content 之后才到达的 reasoning（"补充思考"）
    String late = lateReasoning.toString().trim();
    if (!late.isEmpty()) {
        System.out.println();
        System.out.println(AnsiStyle.heading("🧠 补充思考"));
        TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(System.out);
        r.append(late);
        r.finish();
        lateReasoning.setLength(0);
        streamedOutput = true;
    }
    if (streamedOutput) {
        System.out.println();
    }
}
```

### 5.3 流式后跳过重复输出

```java
if (streamRenderer.hasStreamedOutput()) {
    streamRenderer.finish();    // ← finish 在 token 统计之前调用
    return "";                  // 已流式输出过，不再返回重复文本
}
```

关键顺序：**finish() → 打印 token → return**。必须先 finish 再打印 token，否则缓冲区中不以 `\n` 结尾的尾行会在 token 统计之后才出现（错位）。

### 5.4 工具调用前的中间文本刷出

```java
if (response.hasToolCalls()) {
    conversationHistory.add(...);
    streamRenderer.flushPending();  // ← 强制刷出缓冲区的中间文本
    // 执行工具...
}
```

### 5.5 三缓冲区解决的问题对照

| 你之前见到的 | 根因 | 三缓冲区修复 |
|-------------|------|-------------|
| `🧠 思考过程` 标题打了但下面空的 | GLM 首帧 reasoning delta 是空白 | `pendingReasoning` 攒到非空白才打印标题 |
| content 输出后末尾又冒出一段思考文本 | GLM 在 SSE 尾部补发 reasoning | `lateReasoning` 收集，`finish()` 时独立区块展示 |
| 思考还没显示完就被 content 打断 | pending 攒了但没触发阈值 | `onContentDelta` 补打遗漏的思考过程 |

---

## 6. PlanExecuteAgent 流式集成

### 6.1 TaskStreamRenderer：任务级流式渲染器

与 `Agent.StreamRenderer` 的核心差异：

| | Agent.StreamRenderer | TaskStreamRenderer |
|---|---|---|
| 标题 | `🧠 思考过程` | `🧠 任务思考 [task_X]` |
| 结果标题 | `🤖 最终结果` | `🤖 任务结果 [task_X]` |
| 线程安全 | 不需要（ReAct 单线程） | `synchronized`（并行批次多线程） |
| 状态共享 | 不需要 | 通知 `StreamState.markStreamed()` |
| 换行 | `\n` | `\n\n`（任务间空行分隔） |

### 6.2 StreamState：跨任务共享标记

```java
private static final class StreamState {
    private volatile boolean streamedOutput;  // volatile：多线程写入，主线程读取

    private void markStreamed() { this.streamedOutput = true; }
    private boolean hasStreamedOutput() { return streamedOutput; }
}
```

- 在 `executePlan()` 中创建，一路传到 `executeTask()` → `TaskStreamRenderer`
- 任何 task 的 `TaskStreamRenderer` 触发流式输出后，调用 `markStreamed()`
- 并行批次中多个 task 可能同时写入，`volatile` 保证可见性

### 6.3 TaskRunResult：流式标记传递

`executeTask()` 返回 `TaskRunResult(result, streamedOutput)` 替代原来的 `String`：

```java
private record TaskRunResult(String result, boolean streamedOutput) {
    static TaskRunResult of(String result, boolean streamedOutput) { ... }
}
```

`TaskExecutionResult` 同步增加 `streamedOutput` 字段：

```java
private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
    static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) { ... }
}
```

### 6.4 buildFinalResult 适配

已流式输出的任务在最终汇中跳过——用户已经通过实时渲染看过了完整内容：

```java
private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
    for (Task task : leafTasks) {
        if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
            continue;   // 跳过已流式输出的任务
        }
        // ...
    }
}
```

同时 `executePlan()` 中任务完成打印也做了区分：

```java
if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
    System.out.println("✅ 完成 [" + task.getId() + "]\n");  // 简化
} else {
    System.out.println("✅ 完成 [" + task.getId() + "]: "
        + batchResult.result().substring(0, 100) + "\n");    // 打印摘要
}
```

---

## 7. Planner 流式集成

### 7.1 PlanningStreamRenderer

`Planner.createPlan()` 在调用 LLM 生成计划 JSON 时，通过 `PlanningStreamRenderer` 将 LLM 的推理过程实时展示：

```java
GLMClient.ChatResponse response = llmClient.chat(messages, null, streamRenderer);
streamRenderer.finish();
```

`PlanningStreamRenderer` 只监听 `onReasoningDelta`（不监听 content，因为规划阶段的 content 是 JSON 计划数据，不需要流式渲染）。

### 7.2 简单目标快速路径

新增 `isSimpleGoal()` 方法：当用户输入满足以下条件时，**跳过 LLM 规划**，直接生成单任务计划：

- 不含多步骤关键词（"然后"、"并且"、"再"、"最后"……）
- 长度 ≤ 30 字符
- 包含简单操作词（"列出"、"查看"、"读取"、"显示"、"执行"……）

`createMinimalPlan()` 直接构造一个 `task_1` 的 `ExecutionPlan`，省去一次 LLM 调用。

---

## 8. Logback 日志系统

### 8.1 日志架构

| 组件 | 配置 |
|------|------|
| SLF4J 门面 | `slf4j-api`（随其他依赖传递引入） |
| 日志实现 | `logback-classic 1.5.18` |
| 配置文件 | `src/main/resources/logback.xml` |
| 日志目录 | `~/.paicli/logs/paicli.log` |

**重要变更**：`pom.xml` 中删除了 `slf4j-simple`，因为 classpath 上同时存在两个 SLF4J binding 会导致 logback 无法绑定。

### 8.2 logback.xml 配置

```xml
<configuration>
    <appender name="ROLLING_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/paicli.log</file>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/paicli.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>${LOG_MAX_FILE_SIZE}</maxFileSize>
            <maxHistory>${LOG_MAX_HISTORY}</maxHistory>
            <totalSizeCap>${LOG_TOTAL_SIZE_CAP}</totalSizeCap>
            <cleanHistoryOnStart>true</cleanHistoryOnStart>
        </rollingPolicy>
    </appender>
    <root level="${LOG_LEVEL}">
        <appender-ref ref="ROLLING_FILE"/>
    </root>
</configuration>
```

- **滚动策略**：按天 + 按大小（`SizeAndTimeBasedRollingPolicy`）
- **自动压缩**：归档日志以 `.gz` 存储
- **自动清理**：按最大历史天数（`maxHistory`）+ 总容量上限（`totalSizeCap`）双重控制

### 8.3 日志级别与路径配置

`Main.configureLogging()` 在启动时通过**三层回退**机制配置日志参数：

1. Java 系统属性（`-Dpaicli.log.level=DEBUG`）→ 最高优先级
2. 环境变量（`PAICLI_LOG_LEVEL`）
3. `.env` 文件中的配置
4. 代码默认值（`INFO` / `~/.paicli/logs` / `7` 天 / `10MB` / `100MB`）

支持的属性：
- `paicli.log.dir` / `PAICLI_LOG_DIR` → 日志目录
- `paicli.log.level` / `PAICLI_LOG_LEVEL` → 日志级别（ERROR/WARN/INFO/DEBUG）
- `paicli.log.maxHistory` / `PAICLI_LOG_MAX_HISTORY` → 最大保留天数
- `paicli.log.maxFileSize` / `PAICLI_LOG_MAX_FILE_SIZE` → 单文件最大体积
- `paicli.log.totalSizeCap` / `PAICLI_LOG_TOTAL_SIZE_CAP` → 归档总容量上限

`expandHome()` 支持 `~` 和 `~/path` 风格的主目录展开。

### 8.4 代码中的日志埋点

**Agent.java**：
- `log.info`：run 开始/完成、工具调用次数、token 用量
- `log.debug`：工具参数/结果预览、回复预览
- `log.warn`：达到最大迭代次数
- `log.error`：LLM 调用失败

**PlanExecuteAgent.java**：
- `log.info`：plan 开始、task 执行/完成、工具调用
- `log.debug`：工具参数/结果预览
- `log.warn`：task 失败
- `log.error`：plan 运行失败

---

## 9. CLI 输入处理修复

### 9.1 ESC 序列分类

**问题**：用户按方向键（`ESC [A`）时，只读到 `ESC` (27)，被误判为"取消"操作。

**修复**：引入 `EscapeSequenceType` 枚举和 `classifyEscapeSequence()` 方法：

```java
enum EscapeSequenceType {
    STANDALONE_ESC,       // 纯 ESC → 真正的取消
    BRACKETED_PASTE,      // ESC [200~ → 粘贴
    CONTROL_SEQUENCE,     // ESC [A/B/C/D 或 ESC OA/OB → 方向键等功能键
    OTHER
}
```

`readSingleKeyFromTerminal()` 中读到 ESC 后，立即用 `readInputBurst()` 读取后续字节，通过 `classifyEscapeSequence()` 判断：
- `STANDALONE_ESC` → 返回 key=27，触发取消
- `CONTROL_SEQUENCE` / `BRACKETED_PASTE` → 返回 `ignoredSequence()`，调用方 `continue` 跳过

`readEscapeInput()` 同理，用于处理 prefill 模式（等 Plan 输入时）。

### 9.2 方向键历史导航填充

当用户在"等待 Plan 输入"状态下按 **↑** 时，不再触发取消，而是用 JLine 历史中的最后一条记录作为 seed buffer 预填到输入行：

```java
static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
    if (isUpArrowSequence(sequence)) {
        return latestHistoryEntry(lineReader.getHistory());
    }
    if (isDownArrowSequence(sequence)) {
        return "";   // ↓ 清空预填
    }
    return "";
}
```

支持的按键序列：`[A` / `OA`（↑）和 `[B` / `OB`（↓）。

### 9.3 未知命令提示

`CliCommandParser` 新增 `UNKNOWN_COMMAND` 枚举值，捕获任何以 `/` 开头但不匹配已知命令的输入：

```java
if (trimmed.startsWith("/")) {
    return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
}
```

CLI 循环中显示可用命令列表，不再静默忽略。

### 9.4 readInputBurst 优化

原实现使用 `reader.ready()` + `Thread.sleep(5)` 轮询模式，改为 `NonBlockingReader.read(waitMs)` 阻塞等待：

```java
int next = reader.read(waitMs);
if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
    break;
}
buffer.append((char) next);
waitMs = idleWaitMs;  // 首字符之后切换为空闲等待时间
```

更简洁，减少 CPU 空转。

---

## 10. 端到端流程示例

### 10.1 ReAct 模式流式输出

```
👤 你: 为我打印 myapp 项目中的代码结构

🤔 思考中...

🧠 思考过程                                          ← StreamRenderer 流式渲染 reasoning
(LLM 推理内容逐 token 实时输出...)

🤖 最终结果                                          ← StreamRenderer 流式渲染 content
用户想查看 myapp 项目的代码结构。让我先列出项目的目录结构。 ← flushPending 确保文本在工具执行前出现

🔧 执行工具: list_dir                                 ← 工具执行的终端打印（独立的管道）
   参数: {"path":"./myapp"}
   结果: [F] pom.xml [D] src

Let me dig deeper into the directory structure.                ← 下一轮 LLM 的中间文本
🔧 执行工具: list_dir

Now I have the complete picture.                                ← 继续流式输出

好的，以下是 myapp 项目的完整代码结构：                          ← 最终回复（无 tool_calls）
┌─ code
    myapp/
    ├── pom.xml
    └── src/...
└─ end

📊 Token使用: 输入=1461, 输出=601
```

### 10.2 Plan 模式流式输出

```
📋 正在规划任务: 分析项目依赖

🧠 规划思考                                          ← PlanningStreamRenderer
(规划推理内容流式输出...)

📋 执行计划: ...（摘要）
🚀 开始执行计划...

▶️ 执行任务 [task_1]: 读取 pom.xml

🧠 任务思考 [task_1]                                 ← TaskStreamRenderer
(任务推理内容...)

🤖 任务结果 [task_1]                                 ← TaskStreamRenderer
(pom.xml 内容流式输出...)

✅ 完成 [task_1]                                     ← 已流式，简化打印

▶️ 执行任务 [task_2]: 分析依赖

🧠 任务思考 [task_2]
(分析推理...)

🤖 任务结果 [task_2]
依赖列表：Jackson 2.16.0, OkHttp 4.12.0...

✅ 完成 [task_2]

✅ 计划执行完成！
```

---

## 11. 踩坑记录与关键设计要点

### 11.1 行缓冲导致的文本延迟

**现象**：LLM 在工具调用前输出的中间文本（如"用户想查看...让我先列出"）全部积压在最后才出现。

**原因**：`TerminalMarkdownRenderer` 按行缓冲——GLM 的 content delta 按句子粒度输出，末尾不带 `\n`。

**解决**：新增 `flushPending()` 方法，在检测到工具调用后主动刷出缓冲区。

### 11.2 finish() 与 token 统计的顺序

**现象**：`📊 Token使用` 之后出现一段模型输出的尾行。

**原因**：`finish()` 在 token 打印之后调用，缓冲区中的尾行被延迟刷出。

**解决**：把 `finish()` 移到 token 打印之前。

### 11.3 slf4j-simple 与 logback 冲突

**现象**：日志以 `[main] INFO ...` 格式出现在终端，而非写入文件。

**原因**：`pom.xml` 同时存在 `slf4j-simple` 和 `logback-classic`，classpath 上两个 SLF4J binding 共存。

**解决**：删除 `slf4j-simple` 依赖。

### 11.4 readTimeout 对流式不友好

**现象**：长 reasoning 生成过程中连接超时。

**原因**：流式模式下 `readTimeout` 是两次 SSE chunk 之间的最大间隔，GLM-5.2 生成大段 reasoning 时可能超过 120 秒静默。

**解决**：`readTimeout` 从 120s 提高到 300s，并加 `callTimeout=600s`。

### 11.5 并发安全

`TaskStreamRenderer` 的所有回调方法（`onReasoningDelta` / `onContentDelta` / `finish` / `flushPending` / `hasStreamedOutput`）都标记为 `synchronized`，因为 Plan 模式的并行 task 批次中可能有多个 `TaskStreamRenderer` 同时工作。

`StreamState.streamedOutput` 使用 `volatile` 保证多线程可见性。

### 11.6 OpenAI 兼容性

当前 SSE 解析逻辑与模型无关——`StreamListener` 接口入参是纯 `String`，`TerminalMarkdownRenderer` 只处理文本。只要 LLM 提供 OpenAI 兼容的流式 API（`choices[0].delta.content` / `reasoning_content` / `tool_calls`），当前代码无需修改即可适配。需要在 `GLMClient` 中替换的是：API URL、Model 名称、API Key。

### 11.7 loadEnvConfig 保留

`Main.loadEnvConfig()` + `loadDotEnvFile()` 保留原有逻辑：将 `.env` 中**所有** `KEY=VALUE` 写入 `System.setProperty()`。新增的 `loadConfigValue()` / `readValueFromFile()` 方法仅供日志配置内部使用。RAG Embedding 等模块依赖 `System.getProperty(key)` 读取配置，此逻辑不能移除。
