# Chapter 10 — CDP 会话复用与浏览器登录态持久化

> PaiCLI 第 10 期：在 Chrome DevTools MCP 基础上实现 CDP 会话复用，让 Agent 能够自动管理浏览器连接模式（isolated / shared），在敏感页面强制单步 HITL 审批，并支持长期记忆保存与浏览器偏好自动持久化。

---

## 目录

1. [功能概述](#1-功能概述)
2. [架构概览](#2-架构概览)
3. [第一阶段：CDP 会话复用核心](#3-第一阶段cdp-会话复用核心)
   - [3.1 浏览器模式（BrowserMode）](#31-浏览器模式browsermode)
   - [3.2 会话状态管理（BrowserSession）](#32-会话状态管理browsersession)
   - [3.3 端口探活（BrowserConnectivityCheck）](#33-端口探活browserconnectivitycheck)
   - [3.4 策略执行器（BrowserGuard）](#34-策略执行器browserguard)
   - [3.5 敏感页面策略（SensitivePagePolicy）](#35-敏感页面策略sensitivepagepolicy)
   - [3.6 审计集成（BrowserAuditMetadata）](#36-审计集成browserauditmetadata)
   - [3.7 CLI 命令层](#37-cli-命令层)
   - [3.8 工具执行链路改造](#38-工具执行链路改造)
   - [3.9 HITL 增强](#39-hitl-增强)
4. [第二阶段：autoConnect + save_memory](#4-第二阶段autoconnect--save_memory)
   - [4.1 BrowserConnector 接口](#41-browserconnector-接口)
   - [4.2 浏览器管理工具](#42-浏览器管理工具)
   - [4.3 save_memory 工具](#43-save_memory-工具)
   - [4.4 显式记忆提示（ExplicitMemoryHints）](#44-显式记忆提示explicitmemoryhints)
   - [4.5 autoConnect 模式](#45-autoconnect-模式)
   - [4.6 斜杠命令补全（PaiCliCompleter）](#46-斜杠命令补全paiclicompleter)
5. [端到端示例](#5-端到端示例)
6. [关键设计要点](#6-关键设计要点)

---

## 1. 功能概述

第 9 期（Chrome DevTools MCP）解决了"能用浏览器"的问题，但存在两个体验痛点：

1. **每次启动都是临时 user-data-dir**：没有登录态，每次访问需要登录的网站都要重新登录
2. **没有登录态感知**：Agent 拿到登录页后会反复重试，无法提示用户切换到 shared 模式

第 10 期解决这两个问题，并在此基础上增加了长期记忆保存能力：

| 阶段 | 核心能力 | 新增类 | 
|------|---------|--------|
| 第一阶段 | 双模式浏览器、/browser 命令、敏感页面 HITL 保护 | 7 个（browser 包） |
| 第二阶段 | autoConnect、Agent 自主管理浏览器、save_memory 工具 | 3 个 |

---

## 2. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                         CLI 层                               │
│  /browser status | connect [port] | disconnect | tabs        │
│  SlashCommandHint → PaiCliCompleter → JLine 补全              │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                    Main.java                                  │
│  • BrowserSession 初始化                                      │
│  • BrowserConnector 注入（lambda → handleBrowserCommand）     │
│  • handleBrowserCommand → status/autoConnect/port/disconnect  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   browser 包                                  │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐  │
│  │BrowserSession│  │BrowserGuard   │  │SensitivePage     │  │
│  │ • mode       │  │ • check()     │  │Policy            │  │
│  │ • browserUrl │  │ • applyAfter  │  │ • glob → regex   │  │
│  │ • tabs跟踪   │  │   Execution() │  │ • 14个默认规则    │  │
│  └──────────────┘  └───────┬───────┘  └──────────────────┘  │
│                            │                                  │
│  ┌──────────────┐  ┌───────▼───────┐  ┌──────────────────┐  │
│  │BrowserMode   │  │BrowserCheck   │  │BrowserConnecti   │  │
│  │ ISOLATED     │  │Result         │  │vityCheck         │  │
│  │ SHARED       │  │ • blocked     │  │ • probe(port)    │  │
│  └──────────────┘  │ • sensitive   │  └──────────────────┘  │
│                    └───────────────┘                         │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │BrowserConnector  │  │BrowserAudit      │                 │
│  │ (接口)           │  │Metadata          │                 │
│  └──────────────────┘  └──────────────────┘                 │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   工具执行链路                                 │
│                                                              │
│  ToolRegistry.executeTool()                                   │
│    ├─ checkBrowserTool()  ← BrowserGuard.check()             │
│    │   ├─ 敏感页面+改写操作 → requiresPerCallApproval         │
│    │   ├─ shared 模式 close_page 非自建tab → blocked         │
│    │   └─ 正常 → allow                                       │
│    ├─ 执行工具                                                │
│    └─ browserGuard.applyAfterExecution()                     │
│                                                              │
│  HitlToolRegistry.executeTool()                               │
│    ├─ checkBrowserTool(previewOnly=true)                     │
│    │   ├─ blocked → 交由父类 executeTool 走 PolicyException   │
│    │   └─ sensitive → 强制单步 HITL（禁止"全部放行"）          │
│    └─ executeAfterExplicitApproval()                          │
│                                                              │
│  TerminalHitlHandler                                          │
│    ├─ sensitivePerCall → 隐藏 [a] 全放行选项                   │
│    └─ clearApprovedAllForServer() 模式切换时清理               │
│                                                              │
│  AuditLog.AuditEntry                                          │
│    └─ BrowserAuditMetadata (mode, sensitive, targetUrl)       │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 第一阶段：CDP 会话复用核心

### 3.1 浏览器模式（BrowserMode）

```java
// src/main/java/com/paicli/browser/BrowserMode.java
public enum BrowserMode {
    ISOLATED,  // 每次启动临时 user-data-dir，无登录态（默认）
    SHARED     // 复用用户已登录的 Chrome，带登录态
}
```

两种模式的切换通过 MCP server 的启动参数实现：
- **ISOLATED**：`npx -y chrome-devtools-mcp@latest --isolated=true`
- **SHARED**：`npx -y chrome-devtools-mcp@latest --browser-url=http://127.0.0.1:9222`（旧式）或 `--autoConnect`（新式）

### 3.2 会话状态管理（BrowserSession）

```java
// src/main/java/com/paicli/browser/BrowserSession.java
public class BrowserSession {
    private BrowserMode mode = BrowserMode.ISOLATED;
    private String browserUrl;
    private String lastNavigatedUrl;
    private final Set<String> agentOpenedTabs = new LinkedHashSet<>();
    // ...
}
```

**设计要点：**
- 所有方法均为 `synchronized`，保证多线程并发访问安全
- 不是全局单例——由 `Main` 持有并注入 `ToolRegistry`，方便测试和多会话
- `agentOpenedTabs` 跟踪 PaiCLI 自己创建的 tab（`new_page` 时记录，用于 `close_page` 保护判断）

### 3.3 端口探活（BrowserConnectivityCheck）

```java
// src/main/java/com/paicli/browser/BrowserConnectivityCheck.java
public ProbeResult probe(int port) {
    // HTTP GET http://127.0.0.1:{port}/json/version
    // 成功 → ProbeResult.ok(browserUrl)
    // 失败 → ProbeResult.failed(message)
}
```

使用 OkHttp 客户端，2 秒超时。端口范围校验 1024–65535。

### 3.4 策略执行器（BrowserGuard）

这是整个 CDP 会话复用体系的核心类。

```java
// src/main/java/com/paicli/browser/BrowserGuard.java
public class BrowserGuard {
    // 需要感知的 chrome-devtools 工具前缀
    private static final String SERVER_PREFIX = "mcp__chrome-devtools__";

    // 会触发敏感页面单步 HITL 的"写入"类工具
    private static final Set<String> WRITE_TOOLS = Set.of(
            "click", "drag", "fill", "fill_form", "handle_dialog",
            "hover", "press_key", "resize_page", "upload_file", "evaluate_script"
    );
    // ...
}
```

**check() 方法的三条判定规则（优先级从高到低）：**

1. **close_page 保护**：SHARED 模式下，如果页面不是 Agent 自己打开的，直接 `block`
2. **敏感页面改写审批**：当前页面命中敏感规则 + 工具是写入类 → `requireApproval`
3. **正常放行**：否则 `allow`，并可选更新会话状态（`mutateSession` 时记录导航 URL）

**applyAfterExecution() 的职责：**
- 工具执行成功后，更新导航记录（`rememberNavigation`）
- 工具执行成功后，记录新创建的 tab（`recordOpenedTab`，从 args 或 result 中提取 pageId）

### 3.5 敏感页面策略（SensitivePagePolicy）

```java
// src/main/java/com/paicli/browser/SensitivePagePolicy.java
private static final List<String> DEFAULT_PATTERNS = List.of(
        "*://*.bank.*/*",
        "*://*.alipay.com/*",
        "*://*.paypal.com/*",
        "*://*.stripe.com/*",
        "*://github.com/settings/*",
        "*://*.github.com/settings/*",
        // ... 飞书管理后台、云控制台等 14 条默认规则
);
```

**设计要点：**
- 默认规则覆盖银行、支付、GitHub Settings、飞书管理后台、GCP/AWS/Azure 控制台
- 用户可通过 `~/.paicli/sensitive_patterns.txt` 追加自定义 glob 模式
- 支持 `*` 和 `?` 通配符，内部转正则匹配
- 策略文件读取失败不阻塞主流程

### 3.6 审计集成（BrowserAuditMetadata）

```java
// src/main/java/com/paicli/browser/BrowserAuditMetadata.java
public record BrowserAuditMetadata(
        @JsonProperty("browser_mode") String browserMode,
        Boolean sensitive,
        @JsonProperty("target_url") String targetUrl
) {}
```

`AuditLog.AuditEntry` 新增 `metadata` 字段（加上 `@JsonIgnoreProperties(ignoreUnknown = true)` 保证向前兼容），所有工厂方法增加了带 `metadata` 参数的重载。

审计输出示例：
```
[ALLOW] 2026-08-04T12:00:00Z mcp__chrome-devtools__click (150ms, approver=none)
        浏览器: mode=shared, sensitive=true, url=https://github.com/settings/admin
```

### 3.7 CLI 命令层

#### CliCommandParser 扩展

```java
// 新增枚举值
BROWSER

// 解析规则
"/browser"          → BROWSER ("status")
"/browser connect"  → BROWSER ("connect ...")
"/browser tabs"     → BROWSER ("tabs ...")
```

#### Main.java 新增方法

```java
// 命令路由
handleBrowserCommand(payload, browserSession, connectivityCheck, mcpServerManager, registry, hitlHandler)

// 四个子命令
browserStatus()     → 显示模式、server 状态、9222 探活
browserConnect()    → 端口探测 → restartWithArgs → switchToShared
browserDisconnect() → restartWithArgs --isolated=true → switchToIsolated
browserTabs()       → 委托 mcp__chrome-devtools__list_pages（仅 SHARED 模式）
```

### 3.8 工具执行链路改造

#### McpServerManager 新增方法

```java
// 动态替换 MCP server 启动参数后重启（用于切换 isolated ↔ shared）
public synchronized String restartWithArgs(String name, List<String> args)

// 按名称查找 MCP server
public McpServer server(String name)
```

#### ToolRegistry 改造

```java
// 新增字段
private BrowserGuard browserGuard;

// 新增方法
protected BrowserCheckResult checkBrowserTool(name, argsJson, previewOnly);

// executeTool() 中的变更：
// MCP 工具执行前 → browserGuard.check() → blocked? → PolicyException
// MCP 工具执行后 → browserGuard.applyAfterExecution()
// 所有审计记录 → 传递 auditMetadata
```

### 3.9 HITL 增强

#### HitlToolRegistry

```java
@Override
public String executeTool(String name, String argumentsJson) {
    // 1. 浏览器 Guard 前置检查（previewOnly）
    BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
    // 2. blocked → 直接走父类 executeTool（让它抛 PolicyException）
    // 3. sensitive → 强制 executeAfterExplicitApproval(sensitiveNotice)
    // 4. 正常 → 走原来的全放行/单步审批逻辑
}
```

#### ApprovalRequest

新增 `sensitiveNotice` 字段，在审批框中展示"敏感页面"一行提示。

#### TerminalHitlHandler

- 敏感页面操作：隐藏 `[a] 全部放行` 选项，只显示 `[y/Enter] 批准本次  [n] 拒绝  [s] 跳过  [m] 修改参数`
- 输入 `a` 时提示"敏感页面操作不支持全部放行"
- 新增 `clearApprovedAllForServer(serverName)` —— 模式切换时清理旧审批状态

---

## 4. 第二阶段：autoConnect + save_memory

### 4.1 BrowserConnector 接口

```java
// src/main/java/com/paicli/browser/BrowserConnector.java
public interface BrowserConnector {
    String status();          // → browser_status 工具
    String connectDefault();  // → browser_connect 工具
    String disconnect();      // → browser_disconnect 工具
}
```

在 `Main.java` 中以匿名类实现，lambda 连接到 `handleBrowserCommand`：

```java
hitlToolRegistry.setBrowserConnector(new BrowserConnector() {
    public String status() {
        return handleBrowserCommand("status", ...);
    }
    public String connectDefault() {
        return handleBrowserCommand("connect", ...); // 无参数 → autoConnect
    }
    public String disconnect() {
        return handleBrowserCommand("disconnect", ...);
    }
});
```

### 4.2 浏览器管理工具

`ToolRegistry` 新增三个浏览器管理工具，让 Agent 自己管理连接，而不是让用户手动输入命令：

```java
// browser_connect — LLM 在遇到登录页时自动调用
"当浏览器页面返回登录页、权限不足或明确需要登录态时，
 自动连接已允许远程调试的本机 Chrome 并复用其登录态；
 公开页面不要提前调用。"

// browser_disconnect — 完成后切回 isolated
"完成登录态页面访问后，可切回 isolated 浏览器模式。"

// browser_status — 查看当前状态
"查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。"
```

### 4.3 save_memory 工具

```java
// src/main/java/com/paicli/tool/ToolRegistry.java — registerMemoryTools()
tools.put("save_memory", new Tool(
    "save_memory",
    "当且仅当用户明确说「记一下」「记住」「以后记得」或要求保存
     长期偏好/稳定事实时调用，把精炼事实写入长期记忆；
     不要保存一次性任务请求、临时文件名或模型猜测。",
    createParameters(new Param("fact", "string",
        "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true)),
    args -> {
        // → memorySaver.accept(fact)
        // MemoryManager.storeFact() 写入磁盘
    }
));
```

`memorySaver` 是 `Consumer<String>`，在 `Agent`、`PlanExecuteAgent`、`AgentOrchestrator` 的构造函数中注入：

```java
this.toolRegistry.setMemorySaver(memoryManager::storeFact);
```

### 4.4 显式记忆提示（ExplicitMemoryHints）

```java
// src/main/java/com/paicli/memory/ExplicitMemoryHints.java
public static String browserLoginFact(String userInput, List<String> recentTexts)
```

**识别逻辑：**

1. 用户输入包含记忆意图关键词（"记一下"、"记住"、"以后记得"等）
2. 用户输入同时提及浏览器登录复用（chrome/浏览器 + 登录态/复用/连接）
3. 从上下文提取 URL host → 生成精确事实（如"访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。"）

在 `Agent.run()` 中，每轮用户输入时自动调用：

```java
storeExplicitBrowserMemoryHint(userInput);
// → ExplicitMemoryHints.browserLoginFact(...)
// → memoryManager.storeFact(fact)
```

### 4.5 autoConnect 模式

`/browser connect` 的两种模式：

| 命令 | 模式 | 说明 |
|------|------|------|
| `/browser connect` | autoConnect | Chrome 144+，用 `--autoConnect` 参数，需先在 `chrome://inspect` 勾选 Allow remote debugging |
| `/browser connect 9222` | CDP 端口探测 | 旧式，HTTP 探活 `GET /json/version` → 验证可用 → `--browser-url=...` |

`browserAutoConnect()` 方法：
```java
// 用 --autoConnect 重启 chrome-devtools MCP server
List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
// 成功 → browserSession.switchToShared("autoConnect")
// 失败 → 回滚旧参数 + 提示用户检查 chrome://inspect 设置
```

### 4.6 斜杠命令补全（PaiCliCompleter）

```java
// src/main/java/com/paicli/cli/PaiCliCompleter.java
final class PaiCliCompleter implements Completer {
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (input.startsWith("/")) {
            completeSlashCommand(line, candidates);  // 斜杠命令补全
        } else {
            new AtMentionCompleter(resourceSupplier).complete(...); // @-mention 补全
        }
    }
}
```

**Main.java 中的配合改动：**

```java
// SlashCommandHint 记录：每条命令对应 insertText + display + description
record SlashCommandHint(String insertText, String display, String description) {}

// 所有斜杠命令注册（35 条）
static List<SlashCommandHint> slashCommandHints() { ... }

// JLine / 键绑定：在 prompt 起始位置按 / 自动弹出命令列表
static void configureSlashCommandHint(LineReader lineReader) {
    lineReader.getWidgets().put("paicli-slash-command-hint", () -> {
        lineReader.getBuffer().write("/");
        lineReader.callWidget(LineReader.LIST_CHOICES);
        return true;
    });
}

// LineReader 配置
.completer(new PaiCliCompleter(mcpServerManager::resourceCandidates))
lineReader.option(LineReader.Option.AUTO_LIST, true);
lineReader.option(LineReader.Option.AUTO_MENU, true);
```

---

## 5. 端到端示例

### 场景 1：访问需要登录态的内部系统

```
👤 你: 帮我看一下 https://yuque.com/our-team/meeting-notes 的最近更新

🤔 思考中...
🔧 mcp__chrome-devtools__navigate_page: https://yuque.com/our-team/meeting-notes
   → 返回：登录页（需要认证）

🔧 browser_connect
   → 🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect 允许远程调试）
   → 浏览器会话已切换到 shared 模式

🔧 mcp__chrome-devtools__navigate_page: https://yuque.com/our-team/meeting-notes
   → 成功加载会议笔记页面（已登录）

🔧 mcp__chrome-devtools__take_snapshot
   → [DOM 文本快照]

🤖 Agent: 最近更新的会议笔记包括...
```

### 场景 2：用户保存浏览器偏好

```
👤 你: 记一下，以后访问语雀都用我浏览器里的登录态

💾 保存长期记忆: 访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。
```

`ExplicitMemoryHints` 自动检测到"记一下 + 浏览器登录复用"→ 提取 URL host → 生成事实 → 写入 MemoryManager。

### 场景 3：敏感页面操作强制单步 HITL

```
👤 你: 帮我在 GitHub Settings 里添加一个 SSH key

# Agent 导航到 github.com/settings/keys → 被 BrowserGuard 识别为敏感页面

────────── ⚠️  HITL 审批请求 ──────────
⚠️  敏感页面命中规则 *://github.com/settings/*，本次浏览器改写操作必须单步审批，不能复用全部放行。

请选择操作：[y/Enter] 批准本次  [n] 拒绝  [s] 跳过  [m] 修改参数
>
```

注意：这里没有 `[a] 全部放行` 选项，每次操作（点击、填写、按键等）都要单独审批。

### 场景 4：SHARED 模式下的 close_page 保护

```
# Agent 尝试关闭一个非自己创建的 tab
→ 🛡️ 策略拒绝: shared 浏览器模式下拒绝关闭非 PaiCLI 创建的标签页，
               请手动关闭该 Chrome 标签页
```

---

## 6. 关键设计要点

### 6.1 为什么 BrowserSession 不是全局单例？

由 `Main` 持有并注入到 `ToolRegistry`，避免：
- 测试时的全局状态污染
- 同一进程多会话时的模式串扰

### 6.2 为什么 checkBrowserTool 有 previewOnly 参数？

`HitlToolRegistry` 在 HITL 决策阶段调用时传 `previewOnly=true`（只检查策略，不修改会话状态）；`ToolRegistry.executeTool()` 执行阶段传 `previewOnly=false`（应用状态变更）。

这样可以避免 HITL 审批阶段就"提前"记录导航等副作用。

### 6.3 敏感页面 HITL 为什么禁止"全部放行"？

敏感页面（银行、支付、后台管理等）的每个操作都有风险。如果允许"为整个 server 放行"，后续连续操作都将静默通过，失去对敏感页面的保护。

### 6.4 为什么模式切换时要 clearApprovedAllForServer？

SHARED 模式下用户批准了全部放行 → 切换到 isolated → 再切回 SHARED 时，旧的全放行记录指向的是旧连接；清除后让用户在新连接下重新审批。

### 6.5 auditMetadata 为什么在 executeTool 开头就创建？

即使后续被 HITL 拦截或抛异常，审计记录里仍能带上浏览器上下文（模式、是否敏感、目标 URL），方便后续排查。

### 6.6 MemorySaver 为什么用 Consumer<String> 而不是直接依赖 MemoryManager？

`ToolRegistry` 是底层工具容器，不应反向依赖 `MemoryManager`（高层模块）。用 `Consumer<String>` 函数式接口作为桥梁，保持依赖方向正确。

### 6.7 类总结

| 类 | 包 | 职责 |
|---|-----|------|
| `BrowserMode` | `browser` | ISOLATED / SHARED 枚举 |
| `BrowserSession` | `browser` | 线程安全会话状态 + tab 跟踪 |
| `BrowserConnectivityCheck` | `browser` | CDP 端口 HTTP 探活 |
| `BrowserGuard` | `browser` | 浏览器操作策略执行（拦截 + 审批 + 状态变更） |
| `SensitivePagePolicy` | `browser` | 敏感 URL glob → regex 匹配 |
| `BrowserCheckResult` | `browser` | 检查结果 record（blocked / requireApproval / allow） |
| `BrowserAuditMetadata` | `browser` | 审计元数据 record |
| `BrowserConnector` | `browser` | Agent 可调用的浏览器连接接口 |
| `ExplicitMemoryHints` | `memory` | 自动识别"记住 + 浏览器登录复用"意图 |
| `PaiCliCompleter` | `cli` | 斜杠命令 + @-mention 组合补全器 |

---

> 完成日期：2026-08-04
> 对应提交：`170e5bd`（第一阶段）+ `9ed3755`（第二阶段）
