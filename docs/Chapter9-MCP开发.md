# Chapter 9 — MCP 协议集成开发

> PaiCLI 第 9–10 期：接入 MCP（Model Context Protocol）生态，让 Agent 可以调用外部 MCP server 提供的工具和资源。

---

## 目录

1. [MCP 是什么](#1-mcp-是什么)
2. [架构概览](#2-架构概览)
3. [第一阶段：MCP 协议核心](#3-第一阶段mcp-协议核心)
   - [3.1 JSON-RPC 基础层](#31-json-rpc-基础层)
   - [3.2 传输层](#32-传输层)
   - [3.3 协议数据模型](#33-协议数据模型)
   - [3.4 管理层与配置系统](#34-管理层与配置系统)
   - [3.5 集成改造](#35-集成改造)
4. [第二阶段：MCP 高级能力](#4-第二阶段mcp-高级能力)
   - [4.1 Resources 支持](#41-resources-支持)
   - [4.2 Prompts 查看](#42-prompts-查看)
   - [4.3 @-mention 系统](#43--mention-系统)
   - [4.4 被动通知处理](#44-被动通知处理)
   - [4.5 运行时取消](#45-运行时取消)
5. [端到端示例](#5-端到端示例)
6. [关键设计要点](#6-关键设计要点)

---

## 1. MCP 是什么

MCP（Model Context Protocol）是 Anthropic 发布的一套开放协议，定义了 LLM 应用如何与外部工具/资源服务器通信的标准。它基于 JSON-RPC 2.0，定义了：

- **Tools**：外部 server 暴露的可调用工具（如文件读写、数据库查询、浏览器操控）
- **Resources**：外部 server 暴露的数据资源（如文件内容、数据库记录、日志）
- **Prompts**：外部 server 提供的 prompt 模板
- **Notifications**：server → client 的被动事件通知

PaiCLI 接入 MCP 后，可以直接使用社区成百上千个 MCP server（filesystem、database、chrome-devtools、postgres 等），从而大幅扩展 Agent 的能力边界。

---

## 2. 架构概览

```
┌─────────────────────────────────────────────────────┐
│                      CLI 层                          │
│  /mcp | /mcp restart|logs|disable|enable <name>     │
│  /mcp resources <name> | /mcp prompts <name>         │
│  /cancel | @server:protocol://path                   │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                 McpServerManager                     │
│  • 加载 mcp.json → 并行启动 server                    │
│  • 工具注册到 ToolRegistry（mcp__{s}__{tool}）        │
│  • resource 缓存 + 虚拟工具注册                       │
│  • 被动通知处理（tools/resources list_changed）        │
│  • 生命周期管理（restart / disable / enable）          │
└──────┬───────────────────────┬──────────────────────┘
       │                       │
┌──────▼──────┐   ┌────────────▼────────────┐
│  McpServer  │   │  McpResourceCache       │
│  状态管理    │   │  并发缓存 + 过期追踪     │
└──────┬──────┘   └─────────────────────────┘
       │
┌──────▼──────────────────────────────────┐
│              McpClient                   │
│  initialize → tools/list → tools/call    │
│  resources/list → resources/read         │
│  prompts/list → onNotification           │
└──────┬──────────────────────────────────┘
       │
┌──────▼──────────┐  ┌─────────────────────┐
│  JsonRpcClient  │  │   McpTransport       │
│  JSON-RPC 2.0   │  │   ┌─────────────────┤
│  请求-响应配对   │  │   │ StdioTransport  │
│  超时 + 通知    │  │   │ (子进程 stdio)  │
└─────────────────┘  │   ├─────────────────┤
                      │   │ StreamableHttp  │
                      │   │ Transport (SSE) │
                      │   └─────────────────┘
                      └─────────────────────┘
```

**三层架构**：

| 层 | 职责 |
|---|---|
| **传输层** (`McpTransport` + `StdioTransport` / `StreamableHttpTransport`) | 负责 JSON-RPC 消息的实际收发：stdio 子进程或 HTTP SSE |
| **协议层** (`JsonRpcClient` + `McpClient`) | JSON-RPC 2.0 请求-响应配对、超时控制、MCP 初始化握手 |
| **管理层** (`McpServerManager` + `McpServer`) | 多 server 生命周期管理、工具注册/注销、被动通知处理 |

---

## 3. 第一阶段：MCP 协议核心

> 对应 ROADMAP 第 9 期，基于模板项目 commit `89296e9`。

### 3.1 JSON-RPC 基础层

**类设计**：

```java
// JsonRpcClient — 核心通信类
public class JsonRpcClient implements AutoCloseable {
    // 自增 ID 生成器，保证每个请求有唯一 id
    private final AtomicLong ids = new AtomicLong(1);
    // 待响应 Map：id → CompletableFuture<JsonNode>
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending;
    // 超时调度器：daemon 单线程，延迟 completeExceptionally
    private final ScheduledExecutorService scheduler;
    // 被动通知监听器列表（CopyOnWriteArrayList 保证线程安全遍历）
    private final List<Consumer<JsonNode>> notificationListeners;

    // 发送请求 → Future.get() 等响应（带超时）
    public JsonNode request(String method, JsonNode params, long timeoutSeconds);

    // 发送通知（无 id，不等待响应）
    public void sendNotification(String method, JsonNode params);

    // 注册被动通知监听器
    public void onNotification(Consumer<JsonNode> listener);
}
```

**关键设计**：

- **请求-响应配对**：通过 `AtomicLong` 自增 id + `ConcurrentHashMap` 实现。响应到达时按 id 匹配 `CompletableFuture`，唤醒等待线程。
- **超时机制**：`ScheduledExecutorService` 在指定秒数后尝试从 `pending` Map 移除并 `completeExceptionally`，如果已被正常完成则忽略（先完成者胜）。
- **通知 vs 请求**：接收消息时先检查 `id` 字段——无 id 为通知，分发给 `notificationListeners`；有 id 为响应，匹配 `pending` Map。
- **错误处理**：JSON-RPC error 对象转为 `JsonRpcException`（含 code），调用方可根据 code 判断错误类型（如 -32601 MethodNotFound）。

**配套类**：

```java
// JsonRpcException — 携带 JSON-RPC 错误码的运行时异常
public class JsonRpcException extends RuntimeException {
    private final int code;  // JSON-RPC error.code
}

// JsonRpcMessage — 消息类型 records（Request / Response / Notification / Error）
public final class JsonRpcMessage { ... }
```

---

### 3.2 传输层

#### McpTransport 接口

```java
public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;       // 发送一条消息
    void onReceive(Consumer<JsonNode> listener);           // 注册接收回调
    default List<String> stderrLines() { return List.of(); }  // stderr 日志
    default Long processId() { return null; }              // 子进程 PID
    default String transportName() { return "unknown"; }   // "stdio" / "http"
    void close();
}
```

#### StdioTransport — 子进程传输

**启动流程**：
```
ProcessBuilder.start()
    ├─ stdout → daemon reader thread → JSON 行 → 分发给 listeners
    ├─ stderr → daemon reader thread → 环形缓冲区（最多 200 行）
    └─ stdin  ← BufferedWriter.write(json + "\n")
```

**关闭流程**：
```
1. stdin.close()     → 子进程读到 EOF
2. process.waitFor(1s) → 优雅退出成功？返回
3. process.destroy()   → 发 SIGTERM
4. process.waitFor(2s) → 优雅退出成功？返回
5. process.destroyForcibly() → 发 SIGKILL
```

**关键细节**：
- 每行一条 JSON-RPC 消息（newline-delimited JSON）
- stderr 环形缓冲区用于 `/mcp logs` 命令诊断
- 所有 reader 线程都是 daemon，不阻止 JVM 退出

#### StreamableHttpTransport — HTTP 传输

**协议特点**（MCP 2025-03-26 规范）：
- 单 POST 发送 JSON-RPC 请求
- 服务端通过 SSE（`text/event-stream`）流式返回响应
- 会话通过 `Mcp-Session-Id` header 管理
- 关闭时发 DELETE 请求（best-effort，5 秒短超时）

**SSE 解析**：
```java
// 输入: "data: {...}\n\ndata: {...}\n\n"
// 输出: List<JsonNode> — 每个 data 块解析为一条 JSON
```

---

### 3.3 协议数据模型

所有协议类型使用 Java 17 `record`，共 8 个类：

```
protocol/
├── McpCapabilities.java       # record: 空壳（server 申报的能力），Jackson 忽略未知字段
├── McpContent.java            # record: {type, text} — tools/call 返回的单条内容
├── McpCallToolRequest.java    # 静态工厂: toJson(name, arguments) → ObjectNode
├── McpCallToolResult.java     # record: {content, isError} + formatForLlm() 扁平化
├── McpInitializeRequest.java  # 静态工厂: toJson() → {protocolVersion, capabilities, clientInfo}
├── McpInitializeResult.java   # record: {protocolVersion, capabilities, serverInfo}
├── McpToolDescriptor.java     # record: {serverName, name, namespacedName, description, inputSchema}
└── McpSchemaSanitizer.java    # 清洗 inputSchema: 去 $schema/$id/$ref/anyOf/oneOf，description 截断 1K
```

**关键设计 —— `McpCallToolResult.formatForLlm()`**：

MCP tools/call 返回的 `content` 数组可能是 text / image / resource 混合类型。该方法将其扁平化为 LLM 可直接理解的纯文本：

```java
public String formatForLlm() {
    return content.stream()
        .map(item -> {
            if ("text".equals(type)) return item.text();
            return "[此工具返回了 " + type + "，请向用户描述结果]";
        })
        .collect(Collectors.joining("\n\n"));
}
```

**关键设计 —— `McpSchemaSanitizer`**：

第三方 MCP server 返回的 `inputSchema` 可能包含 `$schema`、`$ref`、`anyOf`、`oneOf` 等 JSON Schema 关键字，部分 LLM function calling API 不接受这些字段。清洗策略：

- 递归删除 `$schema` / `$id` / `$ref`
- `anyOf` / `oneOf` 展平为 description 文本（如 "anyOf options: string, number"）
- description 截断至 1000 字符
- 保证 `type` 和 `properties` 字段存在（缺失时补默认值）

**关键设计 —— `McpToolDescriptor.namespaced()`**：

```java
// 生成命名空间工具名，格式: mcp__{serverName}__{toolName}
// 示例: "mcp__filesystem__read_file"
// 命名空间隔离确保不同 server 的同名工具不会冲突
public static String namespaced(String serverName, String toolName) {
    return "mcp__" + serverName + "__" + toolName;
}
```

---

### 3.4 管理层与配置系统

#### McpClient

封装标准 MCP 交互流程：

```
initialize(30s)                    → 握手 + capabilities 协商
  └─ initialized notification     → 通知 server 客户端就绪
tools/list(30s)                    → 获取工具列表 → 清洗 inputSchema → 生成 descriptors
tools/call(name, args, 60s)       → 调用工具 → 扁平化结果 → 回灌给 LLM
```

#### McpServer

单 server 状态机：

```
DISABLED ──(config.disabled=true)──▶ DISABLED
DISABLED ──(enable())──────────────▶ STARTING ──(success)──▶ READY
                                                  ──(error)────▶ ERROR
READY    ──(disable())────────────▶ DISABLED
ERROR    ──(restart())────────────▶ STARTING
```

持有字段：`config`、`client`、`tools`、`errorMessage`、`startedAt`。所有可变字段使用 `volatile` 保证线程可见性。

#### McpServerManager

核心功能：

```
loadConfiguredServers()   → 解析 mcp.json → 创建 McpServer 实例
startAll()                → 并行启动（daemon 线程池，最多 8 并发）
restart/disable/enable()  → synchronized 方法，串行化状态变更
formatStatus()            → ASCII 表格展示所有 server 状态
startupSummary()          → 启动摘要打印
```

**单 server 启动流程（`start()` 方法）**：
```
1. unregisterTools(server)         → 注销旧工具
2. server.close()                  → 断开旧连接
3. configLoader.prepare(config)    → 展开 ${VAR} + 校验 transport
4. createTransport(config)         → StdioTransport / StreamableHttpTransport
5. new McpClient(name, transport)  → 创建客户端
6. client.initialize()             → MCP 握手
7. client.listTools()              → 获取真实工具列表
8. validateNoDuplicateTools()      → 同名检测
9. 逐工具 → toolRegistry.registerMcpTool()  → 注册到全局注册表
10. server.markStarted()           → 记录启动时间
```

**错误隔离**：单个 server 启动失败只标记 `ERROR`，不阻塞其他 server。

#### 配置系统

**文件位置**（与 Claude Code 兼容）：
```
~/.paicli/mcp.json          # 用户级配置（全局生效）
.paicli/mcp.json             # 项目级配置（覆盖用户级）
```

**格式**：
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"],
      "env": { "NODE_ENV": "production" }
    },
    "remote-api": {
      "url": "https://mcp.example.com/api",
      "headers": { "Authorization": "Bearer ${MY_TOKEN}" },
      "disabled": false
    }
  }
}
```

**变量展开**（`McpConfigLoader.expandString()`）：

| 变量 | 解析来源 |
|---|---|
| `${PROJECT_DIR}` | `projectDir.toAbsolutePath()` |
| `${HOME}` | `System.getProperty("user.home")` |
| `${ANY_KEY}` | `System.getenv(name)` → `System.getProperty(name)` 两级回退 |

> **注意**：`.env` 文件中的变量被 `Main.loadEnvConfig()` 通过 `System.setProperty()` 注入 Java 系统属性，而非操作系统环境变量。因此 `expandString()` 在 `System.getenv()` 取不到时回退到 `System.getProperty()`，确保 `.env` 配置也能被 MCP 配置引用。

**配置校验**：
- command 和 url **必须且只能**配置一个（互斥）
- 未设置的 `${VAR}` 抛出 `IllegalArgumentException`，该 server 标记 `ERROR`

---

### 3.5 集成改造

#### ToolRegistry 改动

新增 MCP 工具注册能力：

```java
// 新增字段
private final Map<String, McpRegisteredTool> mcpTools = new LinkedHashMap<>();

// 注册 MCP 工具 —— 生成 mcp__{server}__{tool} 命名空间名
public void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker);

// 注销 MCP 工具
public void unregisterMcpTool(String toolName);

// executeTool() 改动 —— MCP 工具优先走独立执行路径（不经过 Map<String,String> 参数解析）
// 审计判断改为动态：AUDIT_TOOLS.contains(name) || name.startsWith("mcp__")
```

**为什么 MCP 工具不走 `Map<String,String>` 路径？**

内置工具的 executor lambda 使用 `Map<String, String>` 接收参数（所有值通过 `asText()` 强转字符串）。MCP 工具需要将原始 JSON 参数字符串原样透传给 `McpClient.callTool()`，保留嵌套结构。因此 MCP 工具使用 `Function<String, String>` 作为执行器签名，直接接收 JSON 字符串。

#### HitlToolRegistry 兼容

`HitlToolRegistry` 继承 `ToolRegistry`，覆写 `executeTool()` 在危险工具前插入审批。MCP 工具的 `mcp__` 前缀被 `ApprovalPolicy.requiresApproval()` 识别为需要审批，自动走 HITL 拦截链，**无需额外适配**。

#### CLI 集成

新增命令：

| 命令 | 功能 |
|---|---|
| `/mcp` | 查看所有 MCP server 状态（ASCII 表格） |
| `/mcp restart <name>` | 重启指定 server |
| `/mcp logs <name>` | 查看指定 server 的 stderr 日志 |
| `/mcp disable <name>` | 禁用指定 server（注销工具 + 断开连接） |
| `/mcp enable <name>` | 启用指定 server |

启动流程：
```java
// Main.main() 中，在 HitlToolRegistry 创建之后、Agent 创建之前
McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
mcpServerManager.loadConfiguredServers();
mcpServerManager.startAll();
Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close));
System.out.println(mcpServerManager.startupSummary());
```

MCP 子系统**默认开启**。未配置 `mcp.json` 时静默跳过。

---

## 4. 第二阶段：MCP 高级能力

> 对应 ROADMAP 第 10 期，基于模板项目 commit `5de88fb`。

### 4.1 Resources 支持

MCP resources 是 server 暴露的数据资源（文件内容、数据库记录、日志等）。PaiCLI 通过**双轨策略**让 LLM 可以使用 resources：

```
策略 A（工具层）：为每个支持 resources 的 server 注册两个虚拟工具
  └─ mcp__{server}__list_resources  →  列出所有 resources
  └─ mcp__{server}__read_resource   →  读取指定 URI 的 resource

策略 B（用户层）：@-mention 内联展开
  └─ 用户输入 @server:protocol://path  →  替换为 <resource> XML 块
```

**Resource 数据模型**：

```java
// 描述符（resources/list 返回的条目）
public record McpResourceDescriptor(
    String serverName, String uri, String name, String title,
    String description, String mimeType, Long size
) { ... }

// 内容（resources/read 返回的内容）
public record McpResourceContent(
    String uri, String mimeType, String text, String blob
) {
    public boolean isText() { return text != null; }
}
```

**Resource 缓存**（`McpResourceCache`）：

```
put(server, resources)          → 更新缓存 + 清除过期标记
get(server)                     → 返回缓存（server 级过期时返回空）
all()                           → 返回所有未过期 resource
invalidateServer(server)        → resources/list_changed 到达时标记 server 过期
invalidateResource(server, uri) → resources/updated 到达时标记 URI 过期
markResourceFresh(server, uri)  → 读取成功后清除 URI 过期标记
```

**McpClient 扩展**：

```java
// 列出 resources（-32601 MethodNotFound 时返回空列表，不抛异常）
public List<McpResourceDescriptor> listResources() throws IOException;

// 读取 resource 内容（返回 text/blob 混合列表）
public List<McpResourceContent> readResource(String uri) throws IOException;

// 订阅 resource 变更通知
public void subscribeResource(String uri) throws IOException;

// 格式化辅助方法
public static String formatResources(List<McpResourceDescriptor> resources);
public static String formatResourceContents(List<McpResourceContent> contents);
```

**formatResourceContents 输出格式**：

```xml
<resource uri="file:///src/main/App.java" mimeType="text/x-java">
public class App {
    public static void main(String[] args) { ... }
}
</resource>

<resource uri="screenshot://page1" mimeType="image/png">
[binary resource blob omitted, base64 length=45200]
</resource>
```

二进制 resource 的内容被省略（只显示长度），引导 LLM 使用 text-based 替代方案。

---

### 4.2 Prompts 查看

MCP server 可以暴露预定义的 prompt 模板。PaiCLI 通过 `/mcp prompts <server>` 命令查看，但不自动加载到对话流。

```java
// McpClient.listPrompts() — 对不支持 prompts 的 server 返回空列表
public List<String> listPrompts() throws IOException {
    try {
        // 调 prompts/list，解析 name/title/description
        // 格式化: "title (name) - description"
    } catch (JsonRpcException e) {
        if (e.code() == -32601) return List.of();  // 优雅降级
        throw e;
    }
}
```

---

### 4.3 @-mention 系统

用户可以通过 `@server:protocol://path` 语法直接引用 MCP resource，输入时 Tab 可自动补全。

**三个类分工**：

```
AtMentionParser  → 正则在用户输入中识别 @server:protocol://path
AtMentionExpander → 调 readResourceForMention() 获取内容 → 替换为 <resource> XML
AtMentionCompleter → JLine Completer，Tab 时提供候选项
```

**解析规则**：

```java
// 正则：@字母开头、后跟字母/数字/连字符的标识符 : 协议://非空白非@字符
static final Pattern RESOURCE_PATTERN = Pattern.compile("@([a-zA-Z][\\w-]*):([a-z]+)://([^\\s@]+)");
```

- 引号内的 `@server:...` 不匹配（避免字符串字面量误触发）
- 支持转义字符（`\\\"` 等）

**展开流程**：

```java
// AtMentionExpander.expand(input)
1. AtMentionParser.parse(input) → List<MentionToken>
2. 从后往前替换（保留 offset 有效性）
3. 每个 token: serverManager.readResourceForMention(server, uri)
   → 成功: <resource server="..." uri="..." mimeType="...">内容\n</resource>
   → 失败: <resource_error ...>错误信息</resource_error>
4. 内容上限 200KB（超出截断 + 标注）
```

**审计集成**：

`readResourceForMention()` 每次读取都写审计日志（outcome=allow, approver=mention），与 HITL 审计链统一。

---

### 4.4 被动通知处理

MCP server 可以推送被动通知，PaiCLI 注册以下处理器：

```java
// NotificationRouter — 异步派发，防止 stdout reader 线程死锁
private void registerNotificationHandlers(McpServer server, McpClient client) {
    NotificationRouter router = new NotificationRouter();

    // tools/list_changed → 重拉完整工具列表 → 全量替换注册
    router.on("notifications/tools/list_changed", ignored -> {
        List<McpToolDescriptor> newTools = buildToolList(server, client);
        replaceTools(server, client, newTools);
        server.tools(newTools);
    });

    // resources/list_changed → 标记 server 级缓存过期
    router.on("notifications/resources/list_changed",
        ignored -> resourceCache.invalidateServer(server.name()));

    // resources/updated → 标记特定 URI 过期
    router.on("notifications/resources/updated", params -> {
        resourceCache.invalidateResource(server.name(), params.path("uri").asText(""));
    });

    client.onNotification(router);
}
```

**死锁预防**：通知处理器在独立 daemon executor 里异步执行。如果同步执行（在 transport stdout reader 线程里），handler 内部发 JSON-RPC 请求等响应时会阻塞 reader → 响应永远读不到 → 死锁。这是实际踩过的坑。

**全量替换工具（`replaceMcpToolsForServer`）**：

```java
// ToolRegistry.replaceMcpToolsForServer(serverName, descriptors, invokerFactory)
// 1. 注销该 server 下所有旧工具（按 serverName 过滤 mcpTools）
// 2. 注册所有新工具（按 descriptor 生成 invoker）
// 比逐个 unregister + register 更安全，避免工具数量变化时出现间隙窗口
```

---

### 4.5 运行时取消

提供 `/cancel` 命令在 Agent 运行期间请求取消。

**两个核心类**：

```java
// CancellationContext — 线程安全的取消信号全局管理
public final class CancellationContext {
    // InheritableThreadLocal 确保子线程（工具执行线程池）也能感知取消
    private static final InheritableThreadLocal<CancellationToken> LOCAL;
    // 全局 fallback（线程池中 ThreadLocal 未继承时）
    private static final AtomicReference<CancellationToken> CURRENT;

    public static CancellationToken startRun();   // 开始新的 run
    public static boolean isCancelled();           // 检查当前 run 是否已取消
    public static void clear(CancellationToken);   // run 结束后清理
}

// CancellationToken — 原子取消标记
public class CancellationToken {
    private final AtomicBoolean cancelled;

    public void cancel();                          // 设置取消
    public boolean isCancelled();                  // 检查（同时感知线程中断）
}
```

**Agent 集成**——三个 Agent 的 while 循环开始处均添加检查：

```java
// Agent.java — ReAct 循环
while (true) {
    if (CancellationContext.isCancelled()) return "⏹️ 任务已取消";
    // ...
}

// PlanExecuteAgent.java — 计划执行循环
while (true) {
    if (CancellationContext.isCancelled()) return "⏹️ 计划执行已取消";
    // ...
}

// AgentOrchestrator.java — Multi-Agent 编排循环
while (true) {
    if (CancellationContext.isCancelled()) return "⏹️ Multi-Agent 任务已取消";
    // ...
}
```

---

## 5. 端到端示例

### 配置 mcp.json

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"]
    },
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

### 启动输出

```
🔌 启动 MCP server（2 个）...
   ✓ filesystem     stdio   14 工具
   ✓ chrome-devtools stdio   29 工具
   2/2 就绪，共 43 个 MCP 工具
```

### /mcp 命令

```
👤 你: /mcp
🔌 MCP Servers
  chrome-devtools ● ready     stdio  29 tools  uptime 15s pid 12345
  filesystem     ● ready     stdio  14 tools  uptime 12s pid 12346
```

### MCP 工具调用流程

```
👤 你: 读取当前项目的 pom.xml 内容

🔄 Agent ReAct 循环:
  1. LLM 返回 tool_call: mcp__filesystem__read_file({"path": "pom.xml"})
  2. ToolRegistry.executeTool("mcp__filesystem__read_file", "{...}")
     → 识别为 MCP 工具
     → mcpTools.get("mcp__filesystem__read_file").invoker().apply(argumentsJson)
     → McpClient.callTool("read_file", argumentsJson)
     → JSON-RPC: tools/call { name: "read_file", arguments: { path: "pom.xml" } }
     → server 返回: { content: [{ type: "text", text: "..." }], isError: false }
     → formatForLlm() 扁平化
  3. LLM 基于工具结果回复用户
```

### Resource 使用

```
👤 你: /mcp resources filesystem
📚 MCP resources（15）
- file:///pom.xml | pom.xml | text/xml
- file:///src/main/java/com/paicli/cli/Main.java | Main.java | text/x-java
...

👤 你: 检查 @filesystem:file:///pom.xml 中的依赖配置

→ AtMentionExpander 展开为:
  <resource server="filesystem" uri="file:///pom.xml" mimeType="text/xml">
  <?xml version="1.0"...>
  ...
  </resource>

→ 注入 LLM 上下文，Agent 直接分析 pom.xml 内容
```

---

## 6. 关键设计要点

### 6.1 命名空间隔离

MCP 工具以 `mcp__{server}__{tool}` 格式注册，确保：
- 不同 server 的同名工具不冲突（如多个 server 都有 `read_file`）
- MCP 工具名不会与内置工具名冲突
- LLM 看到前缀即可区分 MCP 工具和内置工具

### 6.2 错误隔离

MCP 是"外部依赖"，必须做防御性设计：
- **单 server 失败不阻塞其他 server**：`startAll()` 用 `CompletableFuture.allOf().join()`，单个异常被 catch 后在 `start()` 内部转为 ERROR 状态
- **工具调用失败返回字符串**：`invokeMcpTool()` 用 try-catch 包裹，返回 `"MCP 工具调用失败 (server/tool): error"`，不抛异常打断 Agent 循环
- **不支持的 MCP 方法优雅降级**：`listResources()` / `listPrompts()` 对 -32601（MethodNotFound）返回空列表
- **关闭时 best-effort**：`close()` 不因 server 已死/网络不通而卡住

### 6.3 安全模型

MCP 工具默认走 HITL 审批链：
- `ApprovalPolicy.requiresApproval(name)` 对 `mcp__` 前缀返回 `true`
- 危险等级标记为"🟡 MCP 外部工具"
- 审计日志中 tool 字段带 `mcp__` 前缀，可区分来源

`HitlToolRegistry` 继承 `ToolRegistry` 覆写 `executeTool()`，MCP 工具自然也走 HITL 拦截，零适配成本。

### 6.4 Schema 清洗的必要性

第三方 MCP server 的 inputSchema 自由度很高，可能包含 `$ref`、`anyOf`、`oneOf` 等 JSON Schema 高级特性。部分 LLM 的 function calling API 只接受简化的 JSON Schema（type + properties + required + description），遇到 `$ref` 等会返回 400。

`McpSchemaSanitizer` 做最小清洗：去掉不兼容关键字、展平 union 类型到 description、保证 type 和 properties 存在。这样最大化了 MCP 工具的 LLM 兼容性。

### 6.5 通知异步派发的必要性

MCP server 可以在任何时刻推送通知。如果通知处理器在 transport stdout reader 线程里同步执行，而 handler 内部又要发 JSON-RPC 请求并等响应（如 tools/list_changed → 重拉 tools/list），就会形成**自我死锁**：

```
stdout reader 线程 → 收到通知 → handler.apply() → 发 tools/list → 读响应
                                                    ↑                ↓
                                                    └── 线程被自己阻塞，响应进 buffer 但无人消费
```

`NotificationRouter` 用独立 daemon executor 异步派发，从根上避免了这个问题。

---

*第 9–10 期 MCP 集成开发完成。代码实现基于模板项目 commit `89296e9` 和 `5de88fb`，适配当前 PaiCLI 项目的上下文管理、ToolRegistry 设计和 CLI 架构。*
