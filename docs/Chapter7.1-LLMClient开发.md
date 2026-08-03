# Chapter 7.1：LLMClient 解耦与多模型支持

> 本文档整理 PaiCLI 在完成第 7 期"异步并行工具执行"之后，引入的 LLMClient 接口抽象与多 provider（GLM / DeepSeek）运行时切换能力。核心改动是将 `GLMClient` 中的 records（Message / ToolCall / Tool / ChatResponse）和 HTTP/SSE 流式解析逻辑解耦到接口和抽象基类中，使所有 Agent 只依赖 `LlmClient` 接口，不再依赖具体实现。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. LlmClient 接口设计](#2-llmclient-接口设计)
  - [2.1 核心契约](#21-核心契约)
  - [2.2 数据模型（Records）](#22-数据模型records)
  - [2.3 StreamListener 流式监听](#23-streamlistener-流式监听)
- [3. AbstractOpenAiCompatibleClient 抽象基类](#3-abstractopenaicompatibleclient-抽象基类)
  - [3.1 模板方法模式](#31-模板方法模式)
  - [3.2 SSE 流式解析](#32-sse-流式解析)
  - [3.3 ToolCall 增量合并](#33-toolcall-增量合并)
- [4. 具体实现：GLMClient 与 DeepSeekClient](#4-具体实现glmclient-与-deepseekclient)
- [5. LlmClientFactory 工厂](#5-llmclientfactory-工厂)
- [6. PaiCliConfig 持久化配置](#6-paicliconfig-持久化配置)
  - [6.1 配置加载优先级](#61-配置加载优先级)
  - [6.2 运行时模型切换](#62-运行时模型切换)
- [7. Agent 层适配](#7-agent-层适配)
  - [7.1 Agent 构造器签名变更](#71-agent-构造器签名变更)
  - [7.2 setLlmClient 与 MemoryManager 联动](#72-setllmclient-与-memorymanager-联动)
  - [7.3 Token 统计与 getContextStatus](#73-token-统计与-getcontextstatus)
- [8. CLI 新增命令](#8-cli-新增命令)
- [9. 完整端到端示例](#9-完整端到端示例)
- [10. 关键设计要点](#10-关键设计要点)

---

## 1. 整体架构概览

改造前的 LLM 调用链路：

```
Agent / PlanExecuteAgent / SubAgent / ...
        │
        ▼
   new GLMClient(apiKey)          ← 直接依赖具体类
        │
        ▼
  GLMClient.chat() / chatStream()  ← 所有 HTTP/SSE 逻辑耦合在一个类中
```

改造后的三层架构：

```
┌─────────────────────────────────────────────────────┐
│  Agent / PlanExecuteAgent / SubAgent / Planner / …  │  ← 只依赖 LlmClient 接口
└─────────────────────┬───────────────────────────────┘
                      │
                      ▼
              ┌──────────────┐
              │  LlmClient   │  ← 接口（数据模型 + 核心契约）
              └──────┬───────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  AbstractOpenAiCompatibleClient    │  ← 抽象基类（HTTP 请求 / SSE 解析 / ToolCall 合并）
    └────┬──────────────────────┬───────┘
         │                      │
         ▼                      ▼
   ┌──────────┐          ┌──────────────┐
   │GLMClient │          │DeepSeekClient│  ← 具体实现（API URL + 模型名 + Key）
   │(智谱 AI) │          │(DeepSeek)    │
   └──────────┘          └──────────────┘
```

新增和修改的类汇总：

| 类别 | 文件 | 说明 |
|---|---|---|
| 新增 | `llm/LlmClient.java` | LLM 客户端接口（Message / ToolCall / Tool / ChatResponse / StreamListener） |
| 新增 | `llm/AbstractOpenAiCompatibleClient.java` | 抽象基类，承载 HTTP + SSE 通用逻辑 |
| 新增 | `llm/DeepSeekClient.java` | DeepSeek 客户端实现 |
| 新增 | `llm/LlmClientFactory.java` | 工厂，支持按 provider 创建和自动检测 |
| 新增 | `config/PaiCliConfig.java` | 持久化配置管理（`~/.paicli/config.json`） |
| 重构 | `llm/GLMClient.java` | 从 487 行精简到 51 行，继承抽象基类 |
| 适配 | `agent/Agent.java` | 依赖 LlmClient + setLlmClient + getContextStatus + token 统计 |
| 适配 | `agent/PlanExecuteAgent.java` | 依赖 LlmClient + 构造函数签名变更 |
| 适配 | `agent/SubAgent.java` | 依赖 LlmClient + token 追踪 |
| 适配 | `agent/AgentOrchestrator.java` | 依赖 LlmClient + 构造函数签名变更 |
| 适配 | `plan/Planner.java` | 依赖 LlmClient |
| 适配 | `tool/ToolRegistry.java` | getToolDefinitions() 返回 `List<LlmClient.Tool>` |
| 适配 | `memory/MemoryManager.java` | 构造函数接受 LlmClient + 新增 setLlmClient() |
| 适配 | `memory/ContextCompressor.java` | 字段改为可变 + 新增 setLlmClient() |
| 适配 | `memory/TokenBudget.java` | 类型引用 GLMClient → LlmClient |
| 适配 | `cli/Main.java` | PaiCliConfig + LlmClientFactory + /model + /context 命令 |
| 适配 | `cli/CliCommandParser.java` | 新增 SWITCH_MODEL / CONTEXT_STATUS 命令 |

---

## 2. LlmClient 接口设计

### 2.1 核心契约

```java
public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    /** 当前使用的模型名称，如 "glm-4.7" */
    String getModelName();

    /** 当前 provider 名称，如 "glm"、"deepseek" */
    String getProviderName();
}
```

设计要点：
- 两个 `chat()` 方法：一个无流式监听（面向 Planner 等只取最终结果的调用方），一个带 `StreamListener`（面向 Agent 等需要实时渲染的调用方）
- `getModelName()` / `getProviderName()` 用于 CLI 展示（启动 banner、`/model` 命令等）

### 2.2 数据模型（Records）

所有 records 从 `GLMClient` 内部提升为 `LlmClient` 接口的成员，成为公共 API：

```java
record Message(String role, String content, String reasoningContent,
               List<ToolCall> toolCalls, String toolCallId) {
    static Message system(String content) { ... }
    static Message user(String content) { ... }
    static Message assistant(String content) { ... }
    static Message assistant(String reasoningContent, String content) { ... }
    static Message assistant(String content, List<ToolCall> toolCalls) { ... }
    static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) { ... }
    static Message tool(String toolCallId, String content) { ... }
}

record ToolCall(String id, Function function) {
    record Function(String name, String arguments) {}
}

record Tool(String name, String description, JsonNode parameters) {}

record ChatResponse(String role, String content, String reasoningContent,
                    List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
    boolean hasToolCalls() { ... }
}
```

**为什么 Message 要有多个静态工厂方法？**

`Message` 有 8 种构造组合（不同角色、是否携带 reasoning、是否携带 tool_calls、是否为 tool 回传结果），如果全部用构造器，调用方需要反复传 `null`。静态工厂方法按语义命名，提升可读性：

```java
// 之前：需要理解每个 null 的含义
new Message("assistant", content, null, toolCalls, null);

// 之后：意图一目了然
Message.assistant(content, toolCalls);
```

### 2.3 StreamListener 流式监听

```java
interface StreamListener {
    StreamListener NO_OP = new StreamListener() {};

    default void onReasoningDelta(String delta) {}
    default void onContentDelta(String delta) {}
}
```

使用 default 方法 + `NO_OP` 实例，调用方无需判空：`llmClient.chat(msgs, tools, StreamListener.NO_OP)`。

---

## 3. AbstractOpenAiCompatibleClient 抽象基类

### 3.1 模板方法模式

所有 OpenAI 兼容 API（GLM、DeepSeek 等）共享相同的 HTTP 请求结构和 SSE 流协议。抽象基类用模板方法模式将差异收敛到三个抽象方法：

```java
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)     // 推理可能耗时较长
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build();

    protected abstract String getApiUrl();   // 子类提供 API 端点
    protected abstract String getModel();    // 子类提供模型名称
    protected abstract String getApiKey();   // 子类提供 API Key
```

子类只需实现这三个方法，无需关心 HTTP 组装和流解析：

```java
// GLMClient —— 只需 51 行
public class GLMClient extends AbstractOpenAiCompatibleClient {
    private static final String API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-4.7";
    private final String apiKey;
    private final String model;

    public GLMClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    @Override protected String getApiUrl() { return API_URL; }
    @Override protected String getModel() { return model; }
    @Override protected String getApiKey() { return apiKey; }
    @Override public String getModelName() { return model; }
    @Override public String getProviderName() { return "glm"; }
}
```

### 3.2 SSE 流式解析

基类中的 SSE 解析逻辑与模板项目 `GLMClient` 的原生实现完全一致：

```
HTTP Response (SSE Stream)
      │
      ▼
┌─────────────────────┐
│ 逐行读取 BufferedSource  │
│ 过滤非 "data:" 前缀的行   │
│ "[DONE]" 终止           │
└──────┬──────────────┘
       │
       ▼
┌──────────────────────────────┐
│ 解析 JSON payload             │
│ ├─ usage → inputTokens / outputTokens
│ ├─ choices[0].delta           │
│ │   ├─ role                   │
│ │   ├─ reasoning_content      │ → streamListener.onReasoningDelta()
│ │   ├─ content                │ → streamListener.onContentDelta()
│ │   └─ tool_calls             │ → mergeToolCallDeltas()
│ └─ (非流式) choices[0].message │ → 兼容 fallback
└──────────────────────────────┘
       │
       ▼
  ChatResponse (含完整 content / reasoning / toolCalls / tokens)
```

### 3.3 ToolCall 增量合并

由于 SSE 流式传输中，一个工具调用可能被拆成多段下发（如 name 在一段、arguments 在另一段），需要增量合并：

```java
private static final class ToolCallAccumulator {
    private String id;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
}
```

合并逻辑按照 `tool_calls[index]` 定位累加器，将各字段追加到对应的 `StringBuilder`：

```java
private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
    for (JsonNode tc : toolCallsNode) {
        int index = tc.path("index").asInt(accumulators.size());
        while (accumulators.size() <= index) {
            accumulators.add(new ToolCallAccumulator());
        }
        // 增量合并 id、name、arguments
    }
}
```

---

## 4. 具体实现：GLMClient 与 DeepSeekClient

| 属性 | GLMClient | DeepSeekClient |
|---|---|---|
| API URL | `https://open.bigmodel.cn/api/coding/paas/v4/chat/completions` | `https://api.deepseek.com/chat/completions` |
| 默认模型 | `glm-4.7` | `deepseek-v4-flash` |
| Provider 名 | `glm` | `deepseek` |
| 代码行数 | 51 行 | 43 行 |

新增 provider 只需：
1. 继承 `AbstractOpenAiCompatibleClient`
2. 实现 5 个抽象方法
3. 在 `LlmClientFactory` 中注册

---

## 5. LlmClientFactory 工厂

```java
public class LlmClientFactory {

    /** 按 provider 名称创建客户端，未配置 API Key 则返回 null */
    public static LlmClient create(String provider, PaiCliConfig config) {
        String normalized = provider.toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) return null;
        String model = config.getModel(normalized);

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            default -> null;
        };
    }

    /** 自动检测：defaultProvider → glm → deepseek，返回第一个可用的 */
    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) return client;

        for (String provider : new String[]{"glm", "deepseek"}) {
            client = create(provider, config);
            if (client != null) return client;
        }
        return null;
    }
}
```

**createFromConfig() 的回退逻辑**：如果 `defaultProvider` 对应 provider 的 API Key 未配置（如配置文件中写了 `"defaultProvider": "deepseek"` 但 `.env` 中未设置 `DEEPSEEK_API_KEY`），则依次尝试已知的 provider，确保只要有任意一个 provider 可用就能启动。

---

## 6. PaiCliConfig 持久化配置

### 6.1 配置加载优先级

数据结构（存储于 `~/.paicli/config.json`）：

```json
{
  "defaultProvider": "glm",
  "providers": {
    "glm": {
      "apiKey": null,
      "baseUrl": null,
      "model": null
    }
  }
}
```

API Key 和模型名称的解析优先级（以 GLM 为例）：

```
配置文件 ~/.paicli/config.json 中的 providers.glm.apiKey
        │ （未配置或为空）
        ▼
环境变量 GLM_API_KEY
        │ （未设置）
        ▼
.env 文件中的 GLM_API_KEY=xxx
        │ （未找到）
        ▼
返回 null → LlmClientFactory 跳过此 provider
```

模型名称同理，环境变量名规则：`{PROVIDER}_MODEL`（如 `GLM_MODEL`、`DEEPSEEK_MODEL`）。

### 6.2 运行时模型切换

用户在 CLI 中执行 `/model deepseek` 时：

```
1. LlmClientFactory.create("deepseek", config)
       │
       ├─ config.getApiKey("deepseek")
       │   └─ 优先级查找：config.json → env → .env
       │
       └─ 成功 → new DeepSeekClient(apiKey, model)
           失败 → 提示"未配置 DEEPSEEK_API_KEY"

2. config.setDefaultProvider("deepseek")
   config.save()  ← 持久化到 ~/.paicli/config.json

3. reactAgent.setLlmClient(newClient)
       │
       └─ Agent.setLlmClient() → MemoryManager.setLlmClient() → ContextCompressor.setLlmClient()
           └─ 对话历史保留，压缩/检索的 LLM 调用也切换到新模型
```

---

## 7. Agent 层适配

### 7.1 Agent 构造器签名变更

```java
// 改造前
public Agent(String apiKey) { ... }
public Agent(String apiKey, List<Message> sharedHistory, MemoryManager sharedMemory) { ... }

// 改造后
public Agent(LlmClient llmClient) { ... }
public Agent(LlmClient llmClient, List<Message> sharedHistory, MemoryManager sharedMemory) { ... }
```

`PlanExecuteAgent`、`AgentOrchestrator` 同样改为接受 `LlmClient`。`SubAgent` 和 `Planner` 的构造器签名不变（它们本来就是接受 LLM 客户端对象），只是参数类型从 `GLMClient` 改为 `LlmClient`。

### 7.2 setLlmClient 与 MemoryManager 联动

模型切换时需要同步更新所有持有 LLM 客户端的组件：

```java
// Agent.java
public void setLlmClient(LlmClient llmClient) {
    this.llmClient = llmClient;
    this.memoryManager.setLlmClient(llmClient);   // → ContextCompressor.setLlmClient()
}
```

`MemoryManager.setLlmClient()` 只转发给 `ContextCompressor`，因为 `MemoryManager` 本身不持有 LLM 客户端（在构造时传入给子组件），而 `ContextCompressor` 负责在压缩/提取事实时调用 LLM。

### 7.3 Token 统计与 getContextStatus

**累计 Token 统计**：不同于改造前每次 LLM 调用后 `memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens())`（只记录最后一次），改造后改为对整个 ReAct 循环累计：

```java
long startNanos = System.nanoTime();
int totalInputTokens = 0;
int totalOutputTokens = 0;

while (iteration < MAX_ITERATIONS) {
    LlmClient.ChatResponse response = llmClient.chat(...);
    totalInputTokens += response.inputTokens();
    totalOutputTokens += response.outputTokens();
    ...
}

// 循环结束后统一记录
memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens);

// 输出统计
String statsLine = formatTokenStats(totalInputTokens, totalOutputTokens, startNanos);
// → "📊 Token: 1234 输入 / 567 输出 / 1801 合计 | ⏱ 3.2s"
```

**getContextStatus()**：新增的 `/context` 命令通过此方法查看对话状态：

```
对话上下文: 15 条消息, 3 轮对话, ~4200 字符
   system: 1 / user: 4 / assistant: 6 / tool: 4
长期记忆: 12 条 (FACT: 8, SUMMARY: 4)
Token 统计: 调用 5 次 | 总输入: 8234 | 总输出: 3456
```

---

## 8. CLI 新增命令

| 命令 | 说明 |
|---|---|
| `/model` | 展示当前模型名称和 provider，以及可用模型列表 |
| `/model glm` | 切换到 GLM 模型（需配置 `GLM_API_KEY`） |
| `/model deepseek` | 切换到 DeepSeek 模型（需配置 `DEEPSEEK_API_KEY`） |
| `/context` 或 `/ctx` | 查看对话上下文消息数、轮数、字符数 + 记忆状态 |

`/model <provider>` 执行流程：

```
CliCommandParser.parse("/model deepseek")
    → CommandType.SWITCH_MODEL, payload="deepseek"

Main.java switch case SWITCH_MODEL:
    LlmClient newClient = LlmClientFactory.create("deepseek", config);
    if (newClient == null) → 提示"未配置 API Key"
    else:
        llmClient = newClient;
        config.setDefaultProvider("deepseek");
        config.save();
        reactAgent.setLlmClient(llmClient);
        → 模型切换完成，对话历史保留
```

---

## 9. 完整端到端示例

以从启动到执行一条任务的完整调用链为例：

```
>>> 启动 PaiCLI
1. Main.main()
2. loadEnvConfig()           → 加载 .env → System.setProperty()
3. PaiCliConfig.load()       → 读取 ~/.paicli/config.json，不存在则用默认值
4. LlmClientFactory.createFromConfig(config)
   └─ create("glm", config)
      └─ config.getApiKey("glm")
         └─ .env 中找到 GLM_API_KEY
      └─ new GLMClient(apiKey, null)  ← model 为 null，使用默认 glm-4.7
5. ✅ 已加载模型: glm-4.7 (glm)

>>> 用户输入 "读取 pom.xml"
6. new Agent(llmClient, sharedHistory, sharedMemory, hitlToolRegistry)
7. reactAgent.run("读取 pom.xml")
   └─ llmClient.chat(history, tools, streamRenderer)
      └─ AbstractOpenAiCompatibleClient.chat()
         └─ buildRequestBody() → {"model":"glm-4.7","stream":true,"messages":[...]}
         └─ POST https://open.bigmodel.cn/api/coding/paas/v4/chat/completions
         └─ SSE 流式解析 → streamRenderer 实时渲染 + 返回 ChatResponse
   └─ tool call: read_file(pom.xml)
   └─ LLM 输出解析结果
8. 📊 Token: 1234 输入 / 567 输出 / 1801 合计 | ⏱ 3.2s

>>> 用户输入 "/model"
9. CliCommandParser.parse("/model") → SWITCH_MODEL, payload=null
10. 输出：🤖 当前模型: glm-4.7 (glm)
          可用模型：glm, deepseek
          /model glm     - 切换到 GLM-4.7
          /model deepseek - 切换到 DeepSeek V4
```

---

## 10. 关键设计要点

1. **Records 归属变更**：`Message`、`ToolCall`、`Tool`、`ChatResponse` 从 `GLMClient` 的内部 record 提升为 `LlmClient` 接口的成员。Java 允许接口包含 record，这一特性使类型定义与契约声明可以共存于同一文件，减少包级别的文件数。

2. **GLMClient 构造器保留 model 参数**：`GLMClient(String apiKey, String model)` — 当 model 为 null 或空时使用默认值 `glm-4.7`。DeepSeekClient 采用同样模式，默认 `deepseek-v4-flash`。

3. **向后兼容**：`PlanExecuteAgent` 和 `AgentOrchestrator` 保留了接受 `String apiKey` 的构造器（内部 `new GLMClient(apiKey)`），方便测试和独立使用。新代码推荐直接注入 `LlmClient`。

4. **模板方法最细粒度**：`AbstractOpenAiCompatibleClient` 只暴露三个抽象方法（`getApiUrl`、`getModel`、`getApiKey`），而非让子类重写整个 `chat()` 方法。这样新增 provider 时几乎零出错空间。

5. **降级回退**：`LlmClientFactory.createFromConfig()` 的依次尝试策略确保用户只要配置了任意一个 provider 就能启动，不会因为 `defaultProvider` 指向未配置的 provider 而挂掉。

6. **配置文件的向前兼容**：`PaiCliConfig` 使用 `@JsonIgnoreProperties(ignoreUnknown = true)`，未来新增字段不会使旧配置文件解析失败。

7. **模型切换不丢上下文**：`setLlmClient()` 只替换 LLM 客户端引用，不触碰 `conversationHistory`，因此切换模型后对话历史仍然完整。只有调用 `/clear` 才会重置对话。
