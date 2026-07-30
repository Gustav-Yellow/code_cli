# Chapter 1：ReAct 与 Tool Call 实现

> 本文档整理 paicli 项目中 Agent 工具调用循环的核心实现，涵盖 `GLMClient`（LLM 客户端）、`ToolRegistry`（工具注册表）、`Agent`（编排循环）三个类的格式解析、注册调用机制以及完整的请求/响应流程。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. GLMClient：LLM 客户端](#2-glmclientllm-客户端)
  - [2.1 类设计与配置](#21-类设计与配置)
  - [2.2 chat() 方法请求体格式](#22-chat-方法请求体格式)
  - [2.3 chat() 方法响应体格式](#23-chat-方法响应体格式)
  - [2.4 数据模型（record）](#24-数据模型record)
- [3. ToolRegistry：工具注册表](#3-toolregistry工具注册表)
  - [3.1 整体设计](#31-整体设计)
  - [3.2 Tool 与 ToolExecutor](#32-tool-与-toolexecutor)
  - [3.3 createParameters：生成 JSON Schema](#33-createparameters生成-json-schema)
  - [3.4 5 个内置工具](#34-5-个内置工具)
  - [3.5 getToolDefinitions：传给 LLM 的工具描述](#35-gettooldefinitions传给-llm-的工具描述)
  - [3.6 executeTool：执行工具调用](#36-executetool执行工具调用)
- [4. Agent：ReAct 循环编排](#4-agentreact-循环编排)
  - [4.1 状态与初始化](#41-状态与初始化)
  - [4.2 run() 循环逻辑](#42-run-循环逻辑)
- [5. 完整端到端示例](#5-完整端到端示例)
  - [5.1 场景](#51-场景)
  - [5.2 第 1 轮请求](#52-第-1-轮请求)
  - [5.3 第 1 轮响应与工具执行](#53-第-1-轮响应与工具执行)
  - [5.4 第 2 轮请求](#54-第-2-轮请求)
  - [5.5 第 2 轮响应](#55-第-2-轮响应)
  - [5.6 对话历史轨迹](#56-对话历史轨迹)
- [6. 关键设计要点](#6-关键设计要点)
- [7. 重要提醒：两个易混淆的关键问题](#7-重要提醒两个易混淆的关键问题)
  - [7.1 Agent 怎么知道何时把工具结果加入下一轮对话？](#71-agent-怎么知道何时把工具结果加入下一轮对话)
  - [7.2 第二轮 LLM 怎么知道具体的项目结构？](#72-第二轮-llm-怎么知道具体的项目结构)
  - [7.3 两个问题的对照小结](#73-两个问题的对照小结)
  - [7.4 核心启示](#74-核心启示)

---

## 1. 整体架构概览

paicli 是一个仿 Claude Code 的终端 coding agent，核心由三个类协作：

```
┌──────────────┐     用户输入      ┌──────────────┐
│   Main.java  │ ───────────────► │  Agent.java  │
│ (CLI 入口)   │ ◄─────────────── │ (循环编排)   │
└──────────────┘   最终文本回复    └──────┬───────┘
                                         │
                          ┌──────────────┴──────────────┐
                          │                             │
                          ▼                             ▼
                 ┌─────────────────┐         ┌─────────────────┐
                 │   GLMClient     │         │  ToolRegistry   │
                 │ (LLM HTTP 调用) │         │  (工具注册/执行) │
                 └─────────────────┘         └─────────────────┘
                          │                             │
                          ▼                             ▼
                 智谱 GLM-5.2 API                本地文件系统/Shell
```

**调用链路**：

```
Agent.run(userInput)
  ├─ conversationHistory.add(Message.user(...))
  ├─ while (iteration < MAX_ITERATIONS):
  │    ├─ ChatResponse resp = llmClient.chat(history, toolRegistry.getToolDefinitions())
  │    ├─ if resp.hasToolCalls():
  │    │    ├─ history.add(Message.assistant(resp.content, resp.toolCalls))
  │    │    ├─ for tc in resp.toolCalls:
  │    │    │    ├─ result = toolRegistry.executeTool(tc.name, tc.arguments)
  │    │    │    └─ history.add(Message.tool(tc.id, result))
  │    │    └─ continue
  │    └─ else:
  │         ├─ history.add(Message.assistant(resp.content))
  │         └─ return resp.content
```

---

## 2. GLMClient：LLM 客户端

**源文件**：`src/main/java/com/paicli/llm/GLMClient.java`

### 2.1 类设计与配置

```java
public class GLMClient {
    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String MODEL = "glm-5.2";
    private static final ObjectMapper mapper = new ObjectMapper();  // 线程安全，全局共享
    private final OkHttpClient httpClient;
    private final String apiKey;
}
```

| 配置项 | 值 | 说明 |
|--------|-----|------|
| API_URL | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | 智谱 AI chat completions 接口 |
| MODEL | `glm-5.2` | 智谱旗舰模型 |
| connectTimeout | 60s | TCP 连接超时 |
| readTimeout | 120s | 读取超时（LLM 生成耗时较长，需放宽） |
| 认证 | `Authorization: Bearer <apiKey>` | Bearer Token |

### 2.2 chat() 方法请求体格式

`chat(List<Message> messages, List<Tool> tools)` 方法组装的请求 JSON 结构：

#### 顶层字段

| JSON 字段 | 来源 | 说明 |
|----------|------|------|
| `model` | 常量 `MODEL` | 固定 `"glm-5.2"` |
| `messages` | 入参 `messages` | 对话消息数组 |
| `tools` | 入参 `tools`（可选） | 工具定义数组 |

#### `messages` 数组每一项

| JSON 字段 | 出现条件 | 说明 |
|----------|---------|------|
| `role` | 必有 | system / user / assistant / tool |
| `content` | 必有 | 消息文本内容 |
| `tool_calls` | 仅 assistant 发起工具调用时 | 数组，每项含 id / type / function |
| `tool_call_id` | 仅 tool 角色回传结果时 | 对应上一步 assistant 的 tool_calls[].id |

`tool_calls` 每项结构：

```json
{
  "id": "call_xxx",
  "type": "function",
  "function": {
    "name": "tool_name",
    "arguments": "{\"key\":\"value\"}"   // JSON 字符串，不是对象
  }
}
```

#### `tools` 数组每一项

```json
{
  "type": "function",
  "function": {
    "name": "...",
    "description": "...",
    "parameters": { /* JSON Schema */ }
  }
}
```

#### HTTP 请求封装

- 方法：`POST`
- Headers：`Authorization: Bearer <apiKey>`、`Content-Type: application/json`
- Body：上述 JSON 的字符串形式

### 2.3 chat() 方法响应体格式

`chat()` 方法只解析以下 JSON 路径：

| 解析字段 | JSON 路径 | 用途 |
|---------|----------|------|
| `role` | `choices[0].message.role` | 通常为 assistant |
| `content` | `choices[0].message.content` | 模型文本输出 |
| `toolCalls` | `choices[0].message.tool_calls`（可选） | 模型发起的工具调用 |
| `inputTokens` | `usage.prompt_tokens` | 输入 token 用量 |
| `outputTokens` | `usage.completion_tokens` | 输出 token 用量 |

**响应示例（含工具调用）**：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "我来帮你...",
        "tool_calls": [
          {
            "id": "call_001",
            "function": {
              "name": "create_project",
              "arguments": "{\"name\":\"demo\",\"type\":\"java\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 482, "completion_tokens": 36 }
}
```

**注意**：响应中的 `tool_calls` 每项**没有** `type` 字段（请求时写了 `"type":"function"`，但响应解析时忽略它）。`finish_reason` 字段当前代码未解析，Agent 通过 `hasToolCalls()` 判断是否继续循环。

**错误处理**：HTTP 状态码非 2xx 时抛 `IOException`，消息格式为 `API请求失败: <code> - <body>`。

### 2.4 数据模型（record）

```
Message(role, content, toolCalls, toolCallId)
  ├─ Message(role, content)                          // 简化构造
  ├─ Message.system(content)                         // → role="system"
  ├─ Message.user(content)                           // → role="user"
  ├─ Message.assistant(content)                      // → role="assistant"，无工具调用
  ├─ Message.assistant(content, toolCalls)           // → role="assistant"，带工具调用
  └─ Message.tool(toolCallId, content)               // → role="tool"，回传结果

ToolCall(id, function)
  └─ Function(name, arguments)                       // arguments 是 JSON 字符串

Tool(name, description, parameters)                  // 传给 LLM 的工具描述

ChatResponse(role, content, toolCalls, inputTokens, outputTokens)
  └─ hasToolCalls()                                  // 判断是否需要继续循环
```

---

## 3. ToolRegistry：工具注册表

**源文件**：`src/main/java/com/paicli/tool/ToolRegistry.java`

### 3.1 整体设计

```java
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRegistry() {
        registerFileTools();    // read_file / write_file / list_dir
        registerShellTools();   // execute_command
        registerCodeTools();    // create_project
    }
}
```

5 个内置工具分布如下：

| 工具名 | 注册方法 | 功能 |
|-------|---------|------|
| `read_file` | `registerFileTools()` | 读取文件内容 |
| `write_file` | `registerFileTools()` | 写入文件内容 |
| `list_dir` | `registerFileTools()` | 列出目录内容 |
| `execute_command` | `registerShellTools()` | 执行 Shell 命令 |
| `create_project` | `registerCodeTools()` | 创建新项目结构 |

### 3.2 Tool 与 ToolExecutor

```java
public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

public interface ToolExecutor {
    String execute(Map<String, String> args);
}
```

| 字段 | 用途 | 是否传给 LLM |
|------|------|-------------|
| `name` | 工具标识 | ✅ 传 |
| `description` | 工具描述 | ✅ 传 |
| `parameters` | 参数 JSON Schema | ✅ 传 |
| `executor` | 实际执行逻辑（Java lambda） | ❌ 不传 |

**关键点**：`ToolExecutor` 是函数式接口（只有一个抽象方法），因此注册工具时第 4 个参数用 lambda 语法传入：

```java
tools.put("read_file", new Tool(
        "read_file",                                                     // name
        "读取文件内容",                                                   // description
        createParameters(new Param("path", "string", "文件路径", true)), // parameters
        args -> {                                                        // executor (lambda)
            String path = args.get("path");
            try {
                return "文件内容:\n" + Files.readString(Path.of(path));
            } catch (Exception e) {
                return "读取文件失败: " + e.getMessage();
            }
        }
));
```

lambda `args -> { ... }` 等价于匿名内部类实现，编译器自动包装成 `ToolExecutor` 对象。

### 3.3 createParameters：生成 JSON Schema

**方法签名**：`private JsonNode createParameters(Param... params)`

**Param record**：

```java
private record Param(String name, String type, String description, boolean required) {}
```

**生成的 JSON Schema 结构**：

```json
{
  "type": "object",
  "properties": {
    "<paramName>": {
      "type": "<paramType>",
      "description": "<paramDesc>"
    }
  },
  "required": ["<必填参数名1>", "..."]
}
```

**执行流程**：

1. 创建顶层结构：`type=object`、空 `properties`、空 `required`。
2. 遍历每个 `Param`，往 `properties` 下添加一项（`type` + `description`）。
3. 若 `required=true`，把参数名加入 `required` 数组。

**作用**：把繁琐的 JSON Schema 构建封装成一行调用，让工具注册代码简洁统一。生成的 Schema 存入 `Tool.parameters`，最终通过 `getToolDefinitions()` 传给 LLM。

### 3.4 5 个内置工具

#### 3.4.1 `read_file`

**参数 Schema**：

```json
{
  "type": "object",
  "properties": { "path": { "type": "string", "description": "文件路径" } },
  "required": ["path"]
}
```

**执行逻辑**：`Files.readString(Path.of(path))`，返回 `"文件内容:\n" + content`；失败返回 `"读取文件失败: ..."`。

#### 3.4.2 `write_file`

**参数 Schema**：

```json
{
  "type": "object",
  "properties": {
    "path":    { "type": "string", "description": "文件路径" },
    "content": { "type": "string", "description": "文件内容" }
  },
  "required": ["path", "content"]
}
```

**执行逻辑**：先 `Files.createDirectories(parent)` 确保父目录存在，再 `Files.writeString(...)`；成功返回 `"文件已写入: " + path`。

#### 3.4.3 `list_dir`

**参数 Schema**：

```json
{
  "type": "object",
  "properties": { "path": { "type": "string", "description": "目录路径" } },
  "required": ["path"]
}
```

**执行逻辑**：`new File(path).listFiles()`，遍历输出 `[D] 目录名` 或 `[F] 文件名`；空目录或不存在返回 `"目录为空或不存在"`。

#### 3.4.4 `execute_command`

**参数 Schema**：

```json
{
  "type": "object",
  "properties": { "command": { "type": "string", "description": "要执行的命令" } },
  "required": ["command"]
}
```

**执行逻辑**：`ProcessBuilder("bash", "-c", command)`，`redirectErrorStream(true)` 合并 stderr 到 stdout，逐行读取后返回 `"命令执行完成 (exit code: N)\n" + output`。

#### 3.4.5 `create_project`

**参数 Schema**：

```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "项目名称" },
    "type": { "type": "string", "description": "项目类型 (java/python/node)" }
  },
  "required": ["name", "type"]
}
```

**执行逻辑**（按 `type` 分支）：

- `java`：创建 `src/main/java`、`src/main/resources`，写入 `pom.xml` 模板。
- `python`：创建同名子目录，写入 `main.py` 和 `requirements.txt`。
- `node`：写入 `package.json`。

成功返回 `"项目已创建: " + name + " (类型: " + type + ")"`。

### 3.5 getToolDefinitions：传给 LLM 的工具描述

**方法签名**：`public List<GLMClient.Tool> getToolDefinitions()`

```java
return tools.values().stream()
        .map(t -> new GLMClient.Tool(t.name(), t.description(), t.parameters()))
        .toList();
```

**作用**：把内部 `Tool`（含 `executor`）转换成 `GLMClient.Tool`（不含 `executor`），用于塞进 `GLMClient.chat()` 的 `tools` 参数。保证执行逻辑不会泄露到 LLM 请求里。

转换后的 JSON 形态（即请求体 `tools` 数组的内容）：

```json
[
  {
    "type": "function",
    "function": {
      "name": "read_file",
      "description": "读取文件内容",
      "parameters": {
        "type": "object",
        "properties": { "path": { "type": "string", "description": "文件路径" } },
        "required": ["path"]
      }
    }
  }
  /* 其余 4 个工具同构 */
]
```

### 3.6 executeTool：执行工具调用

**方法签名**：`public String executeTool(String name, String argumentsJson)`

**入参来源**：
- `name` ← `GLMClient.ToolCall.function().name()`
- `argumentsJson` ← `GLMClient.ToolCall.function().arguments()`（JSON 字符串）

**执行步骤**：

| 步骤 | 代码逻辑 | 说明 |
|------|---------|------|
| ① 查找工具 | `tools.get(name)` | 找不到返回 `"未知工具: " + name` |
| ② 解析 JSON | `mapper.readTree(argumentsJson)` | 字符串 → JsonNode |
| ③ 转 Map | 遍历 `args.fields()`，`asText()` 取值 | 存入 `Map<String,String>` |
| ④ 执行 | `tool.executor().execute(argMap)` | 触发注册时的 lambda |
| ⑤ 异常兜底 | `catch (Exception e)` | 返回 `"工具执行失败: " + e.getMessage()` |

**参数传递限制**：

所有参数值都被 `asText()` 强转为字符串：

- 字符串 / 数字 / 布尔值参数可正常工作（数字 `42` → `"42"`）。
- 嵌套对象或数组参数会丢失结构（`asText()` 对对象/数组返回空字符串 `""`）。
- 当前 5 个工具的参数均为字符串类型，暂不受影响；扩展工具时需注意。

**端到端调用示例**：

```java
ToolCall tc = resp.toolCalls().get(0);
// tc.function().name()      → "write_file"
// tc.function().arguments() → "{\"path\":\"/tmp/a.txt\",\"content\":\"hi\"}"

String result = registry.executeTool(tc.function().name(), tc.function().arguments());
// result → "文件已写入: /tmp/a.txt"

// 包成 Message.tool(tc.id(), result) 追加到 messages，发起下一轮 chat()
```

---

## 4. Agent：ReAct 循环编排

**源文件**：`src/main/java/com/paicli/agent/Agent.java`

### 4.1 状态与初始化

```java
public class Agent {
    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<GLMClient.Message> conversationHistory;
    private static final int MAX_ITERATIONS = 10;

    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。

            请用中文回复用户。
            """;
}
```

**初始化逻辑**（构造方法）：

1. 创建 `GLMClient` 实例（传入 apiKey）。
2. 创建 `ToolRegistry` 实例（自动注册 5 个工具）。
3. 初始化 `conversationHistory`，并立即追加 system 消息。

**`MAX_ITERATIONS = 10`**：防止死循环的安全阀，超过即返回错误。

### 4.2 run() 循环逻辑

```java
public String run(String userInput) {
    conversationHistory.add(Message.user(userInput));     // ① 追加用户消息

    int iteration = 0;
    while (iteration < MAX_ITERATIONS) {
        iteration++;
        ChatResponse response = llmClient.chat(
                conversationHistory,
                toolRegistry.getToolDefinitions()         // ② 调用 LLM
        );

        if (response.hasToolCalls()) {                    // ③ 有工具调用 → 继续
            conversationHistory.add(Message.assistant(
                    response.content(), response.toolCalls()));

            for (ToolCall toolCall : response.toolCalls()) {
                String toolResult = toolRegistry.executeTool(
                        toolCall.function().name(),
                        toolCall.function().arguments()); // ④ 执行工具
                conversationHistory.add(Message.tool(
                        toolCall.id(), toolResult));      // ⑤ 追加 tool 消息
            }
            continue;                                     // ⑥ 继续循环
        } else {                                          // ⑦ 无工具调用 → 结束
            conversationHistory.add(Message.assistant(response.content()));
            return response.content();
        }
    }
    return "❌ 达到最大迭代次数限制，任务未完成";
}
```

**循环退出条件**：

- LLM 响应无 `tool_calls`（`hasToolCalls() == false`）→ 返回最终文本。
- 达到 `MAX_ITERATIONS`（10 次）→ 返回错误信息。

**辅助方法**：

- `clearHistory()`：清空对话历史但保留 system 消息。
- `getConversationHistory()`：返回历史副本，用于调试。

---

## 5. 完整端到端示例

### 5.1 场景

用户输入：**"创建一个名为 demo 的 Java 项目"**

`Agent.run()` 会经历两轮 LLM 调用：

1. 第 1 轮：发送 system + user 消息，附带 5 个工具定义 → LLM 返回 `create_project` 工具调用。
2. Agent 本地执行工具，在磁盘上创建项目结构。
3. 第 2 轮：追加 assistant 工具调用消息 + tool 结果消息，再次请求 → LLM 返回最终文本。

### 5.2 第 1 轮请求

**请求体**：

```json
{
  "model": "glm-5.2",
  "messages": [
    {
      "role": "system",
      "content": "你是一个智能编程助手，可以帮助用户完成各种任务。\n\n你可以使用以下工具来完成任务：\n1. read_file - 读取文件内容\n2. write_file - 写入文件内容\n3. list_dir - 列出目录内容\n4. execute_command - 执行Shell命令\n5. create_project - 创建新项目结构\n\n当需要操作文件、执行命令或创建项目时，请使用工具调用。\n使用工具后，根据工具返回的结果继续思考下一步行动。\n\n请用中文回复用户。"
    },
    {
      "role": "user",
      "content": "创建一个名为 demo 的 Java 项目"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "读取文件内容",
        "parameters": {
          "type": "object",
          "properties": { "path": { "type": "string", "description": "文件路径" } },
          "required": ["path"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "write_file",
        "description": "写入文件内容",
        "parameters": {
          "type": "object",
          "properties": {
            "path":    { "type": "string", "description": "文件路径" },
            "content": { "type": "string", "description": "文件内容" }
          },
          "required": ["path", "content"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "list_dir",
        "description": "列出目录内容",
        "parameters": {
          "type": "object",
          "properties": { "path": { "type": "string", "description": "目录路径" } },
          "required": ["path"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "execute_command",
        "description": "执行Shell命令",
        "parameters": {
          "type": "object",
          "properties": { "command": { "type": "string", "description": "要执行的命令" } },
          "required": ["command"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "create_project",
        "description": "创建新项目结构",
        "parameters": {
          "type": "object",
          "properties": {
            "name": { "type": "string", "description": "项目名称" },
            "type": { "type": "string", "description": "项目类型 (java/python/node)" }
          },
          "required": ["name", "type"]
        }
      }
    }
  ]
}
```

**HTTP Headers**：

```
Authorization: Bearer <你的API Key>
Content-Type: application/json
```

### 5.3 第 1 轮响应与工具执行

**响应体**：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "好的，我来帮你创建一个名为 demo 的 Java 项目。",
        "tool_calls": [
          {
            "id": "call_001",
            "type": "function",
            "function": {
              "name": "create_project",
              "arguments": "{\"name\":\"demo\",\"type\":\"java\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 482, "completion_tokens": 36 }
}
```

**Agent 解析后的 `ChatResponse`**：

- `role = "assistant"`
- `content = "好的，我来帮你创建一个名为 demo 的 Java 项目。"`
- `toolCalls = [ToolCall("call_001", Function("create_project", "{\"name\":\"demo\",\"type\":\"java\"}"))]`
- `inputTokens = 482`，`outputTokens = 36`
- `hasToolCalls()` → `true`

**Agent 控制台输出**：

```
🤔 思考中...

🔧 执行工具: create_project
   参数: {"name":"demo","type":"java"}
   结果: 项目已创建: demo (类型: java)
```

**工具执行细节**（`ToolRegistry.executeTool` 内部）：

1. 从 Map 取出 `create_project` 的 `Tool` 对象。
2. `mapper.readTree("{\"name\":\"demo\",\"type\":\"java\"}")` 解析 JSON。
3. 转成 `Map<String,String>`：`{"name" -> "demo", "type" -> "java"}`。
4. 调用 executor lambda，执行：
   - 创建目录 `demo/`
   - 创建 `demo/src/main/java/`
   - 创建 `demo/src/main/resources/`
   - 写入 `demo/pom.xml`：
     ```xml
     <?xml version="1.0" encoding="UTF-8"?>
     <project>
         <modelVersion>4.0.0</modelVersion>
         <groupId>com.example</groupId>
         <artifactId>demo</artifactId>
         <version>1.0</version>
     </project>
     ```
5. 返回字符串：`"项目已创建: demo (类型: java)"`

此时 `conversationHistory` 追加了两条消息：

- `Message.assistant(content, toolCalls)`
- `Message.tool("call_001", "项目已创建: demo (类型: java)")`

### 5.4 第 2 轮请求

**请求体**（messages 累积到 4 条，tools 仍然全部带上）：

```json
{
  "model": "glm-5.2",
  "messages": [
    {
      "role": "system",
      "content": "你是一个智能编程助手，可以帮助用户完成各种任务。\n\n你可以使用以下工具来完成任务：\n1. read_file - 读取文件内容\n2. write_file - 写入文件内容\n3. list_dir - 列出目录内容\n4. execute_command - 执行Shell命令\n5. create_project - 创建新项目结构\n\n当需要操作文件、执行命令或创建项目时，请使用工具调用。\n使用工具后，根据工具返回的结果继续思考下一步行动。\n\n请用中文回复用户。"
    },
    {
      "role": "user",
      "content": "创建一个名为 demo 的 Java 项目"
    },
    {
      "role": "assistant",
      "content": "好的，我来帮你创建一个名为 demo 的 Java 项目。",
      "tool_calls": [
        {
          "id": "call_001",
          "type": "function",
          "function": {
            "name": "create_project",
            "arguments": "{\"name\":\"demo\",\"type\":\"java\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "content": "项目已创建: demo (类型: java)",
      "tool_call_id": "call_001"
    }
  ],
  "tools": [
    /* 同第 1 轮，5 个工具定义完整带上，此处省略 */
  ]
}
```

> **关键点**：第 4 条消息 `tool_call_id = "call_001"` 必须与第 3 条消息 `tool_calls[0].id = "call_001"` 完全一致，LLM 才能正确把结果关联到调用。

### 5.5 第 2 轮响应

**响应体**（LLM 最终文本，无工具调用）：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "已成功为你创建了名为 demo 的 Java 项目，目录结构如下：\n\n- demo/\n  - src/main/java/\n  - src/main/resources/\n  - pom.xml\n\n项目使用 Maven 构建，groupId 为 com.example，artifactId 为 demo。你可以开始往 src/main/java 下添加 Java 源文件了。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 568, "completion_tokens": 92 }
}
```

**Agent 解析后的 `ChatResponse`**：

- `role = "assistant"`
- `content = "已成功为你创建了名为 demo 的 Java 项目..."`
- `toolCalls = null`
- `inputTokens = 568`，`outputTokens = 92`
- `hasToolCalls()` → `false`，循环结束

**Agent 控制台输出**：

```
📊 Token使用: 输入=568, 输出=92

已成功为你创建了名为 demo 的 Java 项目，目录结构如下：

- demo/
  - src/main/java/
  - src/main/resources/
  - pom.xml

项目使用 Maven 构建，groupId 为 com.example，artifactId 为 demo。你可以开始往 src/main/java 下添加 Java 源文件了。
```

### 5.6 对话历史轨迹

| 轮次 | 消息追加方 | role | 关键字段 |
|------|-----------|------|---------|
| 初始 | Agent 构造方法 | system | content = 系统提示词 |
| 1 | `run()` 入口 | user | content = "创建一个名为 demo 的 Java 项目" |
| 1 响应 | LLM 返回 | assistant | content + tool_calls（id=call_001, name=create_project） |
| 1 工具 | `executeTool()` | tool | tool_call_id=call_001, content="项目已创建: demo (类型: java)" |
| 2 响应 | LLM 返回 | assistant | content = 最终回复，无 tool_calls → 循环结束 |

---

## 6. 关键设计要点

### 6.1 ReAct 模式

paicli 采用 **ReAct（Reasoning + Acting）** 模式：LLM 在每轮中决定"思考什么 + 调用什么工具"，Agent 执行工具后把结果回传，LLM 基于结果继续推理，直到不再需要工具调用。

循环结构：

```
Thought → Action (tool call) → Observation (tool result) → Thought → ... → Final Answer
```

### 6.2 工具调用的双向配对

| 请求侧 | 响应侧 |
|--------|--------|
| `messages[].tool_calls[].id`（assistant 发起） | `choices[0].message.tool_calls[].id`（模型新一轮发起） |
| `messages[].tool_call_id`（tool 回传） | — |
| `tools[].function`（能力清单） | `choices[0].message.tool_calls[].function`（模型选择调用） |

**核心约束**：`tool_call_id` 必须严格配对——每个 assistant 发出的 `id`，后续必须有一条 `tool` 消息回传结果。

### 6.3 历史累积与 token 增长

每轮请求都把完整 `conversationHistory` 发给 LLM，不是只发新增消息。这意味着：

- token 用量随轮次线性增长（第 1 轮 482 → 第 2 轮 568）。
- 长对话需要考虑上下文压缩或窗口管理（当前实现未做）。

### 6.4 工具描述与实现分离

`ToolRegistry.Tool` 同时携带"给 LLM 的描述"和"本地执行逻辑"，但通过 `getToolDefinitions()` 转换成 `GLMClient.Tool` 时**丢弃 executor**，保证执行逻辑不泄露到网络请求。

### 6.5 安全阀设计

- `MAX_ITERATIONS = 10`：防止 LLM 死循环调用工具。
- `executeTool` 全程 try-catch：工具失败返回错误字符串而不是抛异常，让 LLM 能感知失败并决定下一步。
- 工具描述中包含类型信息（如 `"项目类型 (java/python/node)"`），引导 LLM 生成合法参数。

### 6.6 当前实现的局限

| 局限 | 影响 | 改进方向 |
|------|------|---------|
| `executeTool` 用 `asText()` 强转参数 | 嵌套对象/数组参数丢失结构 | 改用 `JsonNode` 直接传，或按类型分发 |
| `arguments` 始终是字符串 | 复杂参数需二次解析 | 在 executor 内部按需解析 |
| 无流式响应 | 用户需等待完整回复 | 改用 SSE 流式接口 |
| 无上下文压缩 | 长对话会超 token 限制 | 实现滑动窗口或摘要压缩 |
| `finish_reason` 未解析 | 仅靠 `hasToolCalls()` 判断 | 解析后可区分 `length` / `stop` / `tool_calls` |

---

## 附录：文件清单

| 文件 | 职责 |
|------|------|
| `src/main/java/com/paicli/llm/GLMClient.java` | LLM HTTP 客户端，请求/响应序列化 |
| `src/main/java/com/paicli/tool/ToolRegistry.java` | 工具注册表，5 个内置工具 |
| `src/main/java/com/paicli/agent/Agent.java` | ReAct 循环编排 |
| `src/main/java/com/paicli/cli/Main.java` | CLI 入口，读取用户输入 |

---

## 7. 重要提醒：两个易混淆的关键问题

> 这两个问题揭示了 Agent 工作机制中最容易让人产生误解的地方，复习时请重点理解。

### 7.1 Agent 怎么知道何时把工具结果加入下一轮对话？

**答案：Agent 并不"知道"——它是一个事件驱动的机械循环，判断信号是 `hasToolCalls()`。**

关键代码在 `Agent.run()` 第 64–90 行：

```java
while (iteration < MAX_ITERATIONS) {
    ChatResponse response = llmClient.chat(conversationHistory, toolRegistry.getToolDefinitions());

    if (response.hasToolCalls()) {
        // ① 把 assistant 的工具调用消息加入历史
        conversationHistory.add(Message.assistant(response.content(), response.toolCalls()));

        // ② 执行每个工具调用，并把结果立即加入历史
        for (ToolCall toolCall : response.toolCalls()) {
            String toolResult = toolRegistry.executeTool(
                    toolCall.function().name(),
                    toolCall.function().arguments());
            // ③ 工具结果以 Message.tool() 形式追加
            conversationHistory.add(Message.tool(toolCall.id(), toolResult));
        }

        continue;  // ④ 回到 while 顶部，发起下一轮 chat()

    } else {
        // ⑤ 没有工具调用，循环结束
        conversationHistory.add(Message.assistant(response.content()));
        return response.content();
    }
}
```

#### 触发机制

| LLM 响应 | `hasToolCalls()` | Agent 行为 |
|---------|-----------------|-----------|
| 包含 `tool_calls` | `true` | 执行工具 → 把结果加入历史 → `continue` 进入下一轮 |
| 不含 `tool_calls` | `false` | 把最终文本加入历史 → `return` 结束循环 |

#### 时序拆解

```
第 1 轮 chat() 返回
   │
   ├─ hasToolCalls() = true
   │
   ├─ conversationHistory.add(Message.assistant(content, toolCalls))   ← 追加①
   ├─ 执行工具得到 toolResult
   ├─ conversationHistory.add(Message.tool(callId, toolResult))        ← 追加②
   │
   ├─ continue  ← 回到 while 顶部
   │
第 2 轮 chat() 调用
   │
   └─ 此时 conversationHistory 已经包含 4 条消息（system + user + assistant + tool）
      LLM 能看到工具结果，基于它继续推理
```

**核心结论**：只要 LLM 返回了工具调用，Agent 就**立即**执行工具并把结果追加到历史，然后**无条件**进入下一轮。驱动信号只有一个——`hasToolCalls()` 的真假。

#### 安全阀

如果 LLM 一直发起工具调用不停下来，第 15 行的 `MAX_ITERATIONS = 10` 是兜底：循环超过 10 次强制退出，返回 `"❌ 达到最大迭代次数限制，任务未完成"`。

---

### 7.2 第二轮 LLM 怎么知道具体的项目结构？

**答案：它其实并不知道——LLM 是基于训练知识"推断"，而不是从工具结果中"读出"。**

这是一个非常容易产生误解的地方，看似 LLM"知道"了项目结构，实际上它在**幻觉式推断**。

#### 工具实际返回的内容

`create_project` 工具执行后（`ToolRegistry.java` 第 188 行），返回的字符串只是：

```
项目已创建: demo (类型: java)
```

**仅此而已**。这个字符串里**没有**任何关于目录结构、pom.xml 内容、groupId、artifactId 的信息。

#### LLM 第二轮回复里的详细结构从哪来？

LLM 第二轮回复：

> 已成功为你创建了名为 demo 的 Java 项目，目录结构如下：
> - demo/
>   - src/main/java/
>   - src/main/resources/
>   - pom.xml
> 项目使用 Maven 构建，groupId 为 com.example，artifactId 为 demo...

这些细节是 LLM **基于训练知识推断**出来的，来源有三个：

1. **`create_project` 工具的描述和参数**：工具定义里写了 `"项目类型 (java/python/node)"`，LLM 知道这是创建项目的工具。
2. **参数 `type=java`**：LLM 知道用户要创建 Java 项目。
3. **LLM 训练数据中关于 Maven 标准布局的知识**：Java 项目 → Maven → `src/main/java`、`src/main/resources`、`pom.xml`、`groupId`、`artifactId` 这些是 Java 生态的常识。

#### 这其实是"碰巧正确"

看 `ToolRegistry.java` 第 166–177 行 `create_project` 的实际实现：

```java
case "java" -> {
    Files.createDirectories(projectPath.resolve("src/main/java"));
    Files.createDirectories(projectPath.resolve("src/main/resources"));
    Files.writeString(projectPath.resolve("pom.xml"),
            String.format("...
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0</version>
                    ...", name));
}
```

LLM 推断出的结构与实际实现**恰好一致**——但这是因为**两边的代码（工具实现和 LLM 训练数据）都遵循 Maven 约定**。这是巧合式的一致，不是信息传递。

#### 潜在风险

如果某天有人修改了 `create_project` 的实现（比如改用 Gradle 布局，或 groupId 改成 `cn.paicli`），但工具返回的字符串仍然是 `"项目已创建: demo (类型: java)"`，那么：

- **实际创建的结构**：Gradle 布局 / `cn.paicli`
- **LLM 第二轮告诉用户的**：Maven 布局 / `com.example`

LLM 会**自信地给出错误信息**，因为它没有任何途径看到真实的实现细节。

#### 改进方向

**方案 A：让工具返回更详细的结果**

```java
return String.format(
        "项目已创建: %s (类型: %s)\n结构:\n- src/main/java/\n- src/main/resources/\n- pom.xml (groupId=com.example, artifactId=%s)",
        name, type, name);
```

这样 LLM 第二轮就是**读出**结构，而不是**推断**结构，不会出错。

**方案 B：让 Agent 在工具执行后主动验证**

比如 LLM 第二轮如果想确认结构，可以再调用 `list_dir("demo")` 工具，看到真实目录后再回复用户。这其实就是 ReAct 模式 "Thought → Action → Observation → Thought" 的完整闭环——当前实现里 LLM 跳过了验证步骤，直接基于推断回复了。

---

### 7.3 两个问题的对照小结

| 问题 | 答案 |
|------|------|
| 何时把工具结果加入下一轮？ | LLM 返回 `tool_calls` 时，Agent 立即执行工具并追加 `Message.tool(...)`，然后 `continue` 进入下一轮。判断信号是 `hasToolCalls()`。 |
| 第二轮怎么知道项目结构？ | **它不知道**。工具只返回 `"项目已创建: demo (类型: java)"`，LLM 是基于训练知识中 Maven 标准布局**推断**出来的，恰好与实现一致。这是 ReAct 闭环未完整执行的潜在风险点。 |

### 7.4 核心启示

这两个问题点出了当前 Agent 实现的两个关键特性：

1. **循环是事件驱动的**——由 `hasToolCalls()` 信号驱动，Agent 本身不做任何"决策"，只是机械执行循环体。
2. **LLM 的"知识"混合了真实观察和训练知识推断**——工具返回的内容越简略，LLM 越倾向于用训练知识"填补空白"，这可能导致与实际不符的自信输出。

后续优化工具时，**让工具返回足够详细的结果**是降低 LLM 幻觉风险的最直接手段。

---

> 本文档基于 paicli 项目当前代码整理，后续代码演进时请同步更新。
