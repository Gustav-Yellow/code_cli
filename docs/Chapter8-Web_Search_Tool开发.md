# Chapter 8：Web Search Tool 开发

> 本文档整理 PaiCLI 第 8 期"联网能力 + Web 工具"的核心实现，涵盖 `AgentBudget` 循环退出预算、`com.paicli.web` 包的 SearchProvider 多 provider 抽象、`web_search` / `web_fetch` 双工具注册、HTML → Markdown 正文提取、网络安全策略、以及 ReAct / Plan / Team 三模式 Prompt 的同步增强。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. AgentBudget：循环退出预算](#2-agentbudget循环退出预算)
  - [2.1 三种保险阀](#21-三种保险阀)
  - [2.2 Agent / SubAgent 集成](#22-agent--subagent-集成)
- [3. Web 包：SearchProvider 多 provider 抽象](#3-web-包searchprovider-多-provider-抽象)
  - [3.1 SearchProvider 接口](#31-searchprovider-接口)
  - [3.2 ZhipuSearchProvider（完整实现）](#32-zhipusearchprovider完整实现)
  - [3.3 SerpApi / Searxng Provider（骨架预留）](#33-serpapi--searxng-provider骨架预留)
  - [3.4 SearchProviderFactory：自动选择](#34-searchproviderfactory自动选择)
- [4. WebFetcher + HtmlExtractor + NetworkPolicy](#4-webfetcher--htmlextractor--networkpolicy)
  - [4.1 WebFetcher：HTTP 抓取器](#41-webfetcherhttp-抓取器)
  - [4.2 HtmlExtractor：HTML → Markdown](#42-htmlextractorhtml--markdown)
  - [4.3 NetworkPolicy：SSRF 防护 + 限流](#43-networkpolicyssrf-防护--限流)
- [5. ToolRegistry：web_search / web_fetch 工具注册](#5-toolregistryweb_search--web_fetch-工具注册)
  - [5.1 web_search 工具](#51-web_search-工具)
  - [5.2 web_fetch 工具](#52-web_fetch-工具)
  - [5.3 懒加载 getter 与格式化方法](#53-懒加载-getter-与格式化方法)
- [6. Prompt 增强](#6-prompt-增强)
  - [6.1 ReAct SYSTEM_PROMPT](#61-react-system_prompt)
  - [6.2 WORKER_PROMPT / EXECUTION_PROMPT](#62-worker_prompt--execution_prompt)
  - [6.3 工具选择优先级规则](#63-工具选择优先级规则)
- [7. LLM HTTP 超时可配化](#7-llm-http-超时可配化)
- [8. 完整端到端示例](#8-完整端到端示例)
- [9. 关键设计要点](#9-关键设计要点)

---

## 1. 整体架构概览

第 8 期的核心目标：让 Agent 具备联网能力——既能搜索互联网获取实时信息，又能抓取指定 URL 提取正文。

```
用户: "Java 21 虚拟线程怎么用？"
  │
  ├─(训练数据已知)──→ Agent 直接回答
  │
  └─(需要最新信息)──→ Agent 调用 web_search("Java 21 virtual threads")
                          │
                          ▼
              ┌──────────────────────────┐
              │  SearchProviderFactory   │
              │  自动选择 provider        │
              │  GLM_API_KEY → zhipu     │
              └──────────┬───────────────┘
                         │
                         ▼
              ┌──────────────────────────┐
              │  ZhipuSearchProvider     │
              │  POST /api/paas/v4/      │
              │       web_search         │
              │  Header: Bearer <key>    │
              └──────────┬───────────────┘
                         │
                         ▼
               搜索结果（标题 + 摘要 + URL）
                         │
                         ▼
              Agent 拿到 URL → web_fetch(url)
                         │
                         ▼
              ┌──────────────────────────┐
              │  NetworkPolicy.checkUrl()│ ← SSRF 防护
              │  NetworkPolicy.acquire() │ ← 限流 (60s/30次)
              │  WebFetcher.fetch()      │ ← HTTP GET → HTML
              │  HtmlExtractor.extract() │ ← HTML → Markdown
              └──────────┬───────────────┘
                         │
                         ▼
               格式化 Markdown 正文（截断到 max_chars）
                         │
                         ▼
              Agent 基于抓取内容生成最终回答
```

**涉及的改动范围：**

| 模块 | 类 | 改动要点 |
|------|-----|---------|
| agent | `AgentBudget`（**新增**） | Token 预算 / 停滞检测 / 硬轮数兜底，替代硬编码 MAX_ITERATIONS |
| agent | `Agent` / `SubAgent` | 循环接入 AgentBudget；SYSTEM_PROMPT / WORKER_PROMPT 增加 web 工具说明 |
| agent | `PlanExecuteAgent` | EXECUTION_PROMPT 增加 web 工具说明 |
| web | 8 个新类（**新增**） | SearchProvider 接口 + 3 实现 + Factory + WebFetcher + HtmlExtractor + NetworkPolicy + FetchResult / SearchResult |
| tool | `ToolRegistry` | 新增 registerWebTools() + webSearch() / webFetch() + 懒加载 getter |
| llm | `AbstractOpenAiCompatibleClient` | HTTP 超时改为系统属性可配 |
| build | `pom.xml` | 新增 jsoup 1.18.1 依赖 |

---

## 2. AgentBudget：循环退出预算

### 2.1 三种保险阀

在 a6fa3a8 提交中引入。设计目标是把循环退出主动权交给 LLM——只要它返回 content 不再调用工具，循环就正常退出。`AgentBudget` 只在异常情况下兜底。

| 保险阀 | 默认值 | 系统属性 | 触发条件 |
|--------|--------|---------|---------|
| Token 预算 | 300,000 | `paicli.react.token.budget` | 累计 input + output token 超过阈值 |
| 停滞检测 | 连续 3 轮 | `paicli.react.stagnation.window` | 连续 N 轮调用完全相同的工具名 + 参数 |
| 硬轮数上限 | 50 轮 | `paicli.react.hard.max.iterations` | 累计迭代轮数达到上限 |

```java
// AgentBudget 核心结构
public class AgentBudget {
    public enum ExitReason {
        WITHIN_BUDGET,          // 正常，继续循环
        TOKEN_BUDGET_EXCEEDED,  // Token 用尽
        STAGNATION_DETECTED,    // 疑似死循环
        HARD_ITERATION_LIMIT    // 硬轮数上限
    }

    // 工厂方法：从系统属性或默认值构造
    public static AgentBudget fromSystemProperties() { ... }

    public int beginIteration()    // 进入新一轮，返回轮次
    public void recordTokens(...)  // 记录 LLM token 消耗
    public void recordToolCalls(..)// 记录工具调用签名（用于停滞检测）
    public ExitReason check()      // 检查是否超出预算
    public String describeExit(...)// 生成中文退出描述
}
```

停滞检测的签名算法：取每轮所有 tool_calls 的 `"工具名|参数JSON;工具名|参数JSON;..."` 拼接，用 `ArrayDeque` 保留最近 N 轮签名。全部相同时触发。

### 2.2 Agent / SubAgent 集成

**Agent.run() 循环改造：**

```java
// 改造前（硬编码）
int iteration = 0;
while (iteration < MAX_ITERATIONS) {  // MAX_ITERATIONS = 10
    iteration++;
    // ... LLM 调用 + 工具执行
}
// 达到上限 → "达到最大迭代次数限制，任务未完成"

// 改造后（AgentBudget）
AgentBudget budget = AgentBudget.fromSystemProperties();
while (true) {
    ExitReason reason = budget.check();
    if (reason != ExitReason.WITHIN_BUDGET) {
        return "❌ " + budget.describeExit(reason) + statsLine;
    }
    int iteration = budget.beginIteration();
    // ... LLM 调用
    budget.recordTokens(response.inputTokens(), response.outputTokens());
    if (response.hasToolCalls()) {
        budget.recordToolCalls(response.toolCalls());
        // ... 执行工具
        continue;
    }
    // 没有工具调用 → 正常返回
    break;
}
```

SubAgent 与 Agent 采用完全对称的改造，只多了 `streamRenderer.finish()` 在退出前的调用。

---

## 3. Web 包：SearchProvider 多 provider 抽象

### 3.1 SearchProvider 接口

```java
public interface SearchProvider {
    String name();                          // provider 名称（zhipu/serpapi/searxng）
    boolean isReady();                      // 是否可用（API Key 已配置）
    String unavailableHint();               // 不可用时的提示信息
    List<SearchResult> search(String query, int topK) throws IOException;
}
```

`SearchResult` 是 `record` 类型，包含 `position`（从 1 开始的序号）、`title`、`url`、`snippet`、`source`（从 URL 提取的域名）。提供 `SearchResult.of(position, title, url, snippet)` 静态工厂，自动 trim 和提取 host。

### 3.2 ZhipuSearchProvider（完整实现）

**端点**：`POST https://open.bigmodel.cn/api/paas/v4/web_search`

```json
// 请求体
{
    "search_engine": "search_pro",
    "search_query": "西交利物浦大学",
    "count": 5,
    "content_size": "medium"
}

// 响应体
{
    "search_result": [
        {
            "title": "西交利物浦大学...",
            "link": "https://...",
            "content": "正文摘要...",
            "publish_date": "2026-07-16",
            "refer": "ref_1"
        }
    ]
}
```

**引擎选择**（由 `ZHIPU_SEARCH_ENGINE` 环境变量控制）：

| 引擎 | 价格 | 说明 |
|------|------|------|
| `search_std`（默认） | 0.01 元/次 | 通用搜索 |
| `search_pro` | 0.03 元/次 | 增强搜索 |
| `search_pro_sogou` | 0.05 元/次 | 搜狗引擎 |
| `search_pro_quark` | 0.05 元/次 | 夸克引擎 |

- API Key 与 GLM 推理共用 `GLM_API_KEY`，零额外配置
- 认证方式：`Authorization: Bearer <GLM_API_KEY>`
- 超时：connect 10s / read 20s

### 3.3 SerpApi / Searxng Provider（骨架预留）

- **`SerpApiSearchProvider`**：商业聚合 API，需 `SERPAPI_KEY`。已实现完整的 HTTP 调用与解析逻辑，`isReady()` 在 API Key 配置后返回 true。
- **`SearxngSearchProvider`**：开源元搜索引擎，需 `SEARXNG_URL`（Docker 自托管）。已实现完整的 SearXNG JSON API 调用与解析，`isReady()` 在 URL 配置且格式合法后返回 true。

两个骨架 provider 均已实现 `search()` 方法的完整逻辑，配置好 Key/URL 即可自动启用。

### 3.4 SearchProviderFactory：自动选择

```java
public static SearchProvider create() {
    String provider = readEnv("SEARCH_PROVIDER");
    String glmKey   = readEnv("GLM_API_KEY");
    String serpKey  = readEnv("SERPAPI_KEY");
    String searxUrl = readEnv("SEARXNG_URL");

    String chosen = pickProvider(provider, glmKey, serpKey, searxUrl);
    return switch (chosen) {
        case "searxng" -> new SearxngSearchProvider(searxUrl);
        case "serpapi" -> new SerpApiSearchProvider(serpKey);
        default        -> new ZhipuSearchProvider(glmKey, zhipuEngine);
    };
}
```

**自动选择优先级**（未显式指定 `SEARCH_PROVIDER` 时）：

| 优先级 | 条件 | 选中的 provider |
|--------|------|----------------|
| 1 | 显式 `SEARCH_PROVIDER=zhipu\|serpapi\|searxng` | 按指定值 |
| 2 | 有 `GLM_API_KEY` | **zhipu**（默认推荐） |
| 3 | 有 `SERPAPI_KEY` | serpapi |
| 4 | 有 `SEARXNG_URL` | searxng |
| 5 | 都没有 | zhipu 占位（`isReady()=false`） |

`readEnv()` 的读取顺序：`System.getenv()` → `System.getProperty()` → `.env` 文件直读。与 `Main.loadEnvConfig()` 将 `.env` 写入 `System.setProperty()` 的机制互补。

---

## 4. WebFetcher + HtmlExtractor + NetworkPolicy

### 4.1 WebFetcher：HTTP 抓取器

```java
public class WebFetcher {
    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024; // 5MB

    public RawResponse fetch(String url) throws IOException;

    public record RawResponse(
        String url, String body, String contentType,
        String charset, boolean truncated
    ) {}
}
```

- 流式读取，5MB 上限防 OOM
- 30s callTimeout 整体超时
- Charset 解析：优先 Content-Type header，回退 UTF-8
- User-Agent: `Mozilla/5.0 (compatible; paicli-web-fetch/1.0)`
- 4xx/5xx 直接抛 IOException，由调用方决定如何呈现

### 4.2 HtmlExtractor：HTML → Markdown

极简版 readability，不追求与 Mozilla Readability 对齐，目标是覆盖博客 / 文档 / 官网这类 SSR 页面的常见结构。

**四步处理流程：**

1. **清理噪声**：删除 `script`、`style`、`nav`、`aside`、`footer`、`header`、`form`、`iframe`、`svg`、`canvas`、`button` 标签；根据 class/id 关键词清理广告/导航壳（`ads`、`sidebar`、`comment`、`breadcrumb` 等 18 个关键词）
2. **找主语义容器**：优先 `<article>`、`<main>`、`[role=main]`（文本超过 80 字符）
3. **打分选最优**（无语义容器时）：`score = textLen × (1 - min(linkRatio × 2, 1))`，文本越长且链接密度越低得分越高
4. **递归转 Markdown**：h1–h6 → `# `～`###### `；p → 段落；pre/code → ` ``` ` 代码块；a → `[text](url)`；ul/ol/li → `- / 1.`；blockquote → `> `；table → GFM 表格；img → 只保留 alt 文本

SPA 渲染后的空 HTML 会得到空字符串，由 `FetchResult.bodyEmpty()` 标记，由 LLM 在拿到空正文时判断"这是已知边界，不再重试"。

### 4.3 NetworkPolicy：SSRF 防护 + 限流

```java
public class NetworkPolicy {
    public String checkUrl(String url);  // null = 通过，非 null = 拒绝原因
    public String acquire();             // null = 通过，非 null = 限流原因
}
```

**安全策略：**

| 策略 | 规则 |
|------|------|
| scheme 白名单 | 仅允许 `http`、`https` |
| localhost 黑名单 | 拒绝 `localhost`、`*.localhost`、`0.0.0.0` |
| 地址类型黑名单 | 拒绝 loopback（127.0.0.1）、site-local（192.168.x.x）、link-local、any-local |
| Token Bucket 限流 | 每 60 秒最多 30 次请求，超出返回"请求过于频繁" |

---

## 5. ToolRegistry：web_search / web_fetch 工具注册

### 5.1 web_search 工具

```java
tools.put("web_search", new Tool(
    "web_search",
    "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）",
    createParameters(
        new Param("query", "string", "搜索关键词...", true),
        new Param("top_k", "integer", "返回结果数量（默认5）", false)
    ),
    args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
));
```

`webSearch()` 执行流程：
1. 获取 `SearchProvider`（懒加载，首次调用时通过 `SearchProviderFactory.create()` 初始化）
2. 检查 `provider.isReady()`，不可用时返回提示
3. 调用 `provider.search(query, topK)` → `formatSearchResults()` 格式化

### 5.2 web_fetch 工具

```java
tools.put("web_fetch", new Tool(
    "web_fetch",
    "抓取指定 URL，提取正文转 Markdown。适用静态/SSR 页面；JS 渲染或防爬站会返回空正文，本期不重试。",
    createParameters(
        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
    ),
    args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
));
```

`webFetch()` 执行流程：
1. `NetworkPolicy.checkUrl(url)` URL 安全校验（scheme / host 黑白名单）
2. `NetworkPolicy.acquire()` 限流令牌申请
3. `WebFetcher.fetch(url)` HTTP GET → HTML 字符串
4. `HtmlExtractor.extract(html, url)` → Markdown
5. 按 `maxChars` 截断 → `FetchResult.ok()` → `formatFetchResult()` 格式化

### 5.3 懒加载 getter 与格式化方法

```java
// 四个懒加载 getter（synchronized 线程安全）
private synchronized SearchProvider searchProvider() { ... }
private synchronized WebFetcher webFetcher()           { ... }
private synchronized HtmlExtractor htmlExtractor()     { ... }
private synchronized NetworkPolicy networkPolicy()     { ... }

// 格式化方法
String webSearch(String query, int topK)                        // 搜索入口
private String formatSearchResults(providerName, query, results) // 搜索结果格式化
String webFetch(String url, int maxChars)                       // 抓取入口
private String formatFetchResult(FetchResult result)             // 抓取结果格式化
```

---

## 6. Prompt 增强

### 6.1 ReAct SYSTEM_PROMPT

在原来的 6 个工具说明基础上，新增 web_search 和 web_fetch 两个工具描述，并增加**工具选择优先级规则**：

```
7. web_search - 搜索互联网获取实时信息（最新版本、官方文档、技术资讯等），
                参数：{"query": "搜索关键词", "top_k": 5}
8. web_fetch - 抓取已知 URL 并返回正文 Markdown，
               参数：{"url": "https://...", "max_chars": 8000}
```

### 6.2 WORKER_PROMPT / EXECUTION_PROMPT

SubAgent 的 `WORKER_PROMPT` 和 PlanExecuteAgent 的 `EXECUTION_PROMPT` 同样增加了 web 工具描述和使用指引。

### 6.3 工具选择优先级规则

新增的有效规则解决了 LLM 滥用联网工具的问题：

```
工具选择优先级：
- 代码库相关问题 → search_code，不要走 web_search
- 训练数据已知的稳定知识 → 直接回答，不要联网
- 时效性 / 最新信息 / 不确定的事实 → web_search 找入口，找到 URL 后再 web_fetch 拿全文
- 已经有具体 URL → 直接 web_fetch，不要再 web_search 一次
- web_fetch 拿到空正文（提示 SPA / 防爬墙）→ 这是已知边界，告知用户即可，不要反复重试
```

---

## 7. LLM HTTP 超时可配化

`AbstractOpenAiCompatibleClient` 的 OkHttp 超时参数改为从系统属性读取：

```java
protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
    .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60),  SECONDS)
    .readTimeout   (readTimeoutSeconds("paicli.llm.read.timeout.seconds",    300), SECONDS)
    .writeTimeout  (readTimeoutSeconds("paicli.llm.write.timeout.seconds",   60),  SECONDS)
    .callTimeout   (readTimeoutSeconds("paicli.llm.call.timeout.seconds",    600), SECONDS)
    .build();
```

默认值较之前更宽松（readTimeout 60→300s，callTimeout 新增 600s），适应 GLM-5.1 在生成大段 reasoning_content 时的长时间静默。遇到频繁 timeout 可通过 `-D` 系统属性调优。

---

## 8. 完整端到端示例

```
👤 你: 搜一下西交利物浦大学

🤔 思考中...

  🌐 联网搜索 1 次
    └ 西交利物浦大学

🔍 [zhipu] 西交利物浦大学

1. 西交利物浦大学官网
   XJTLU是中国领先的中外合作大学，由西安交通大学和英国利物浦大学于2006年合作创立...
   🔗 https://www.xjtlu.edu.cn/zh  (xjtlu.edu.cn)

2. 西交利物浦大学2026年招生简章
   西交利物浦大学2026年本科招生面向全国31个省市区...
   🔗 https://www.xjtlu.edu.cn/zh/admissions  (xjtlu.edu.cn)

  📰 抓取 1 个网页
    └ https://www.xjtlu.edu.cn/zh

🌐 抓取: https://www.xjtlu.edu.cn/zh
📄 标题: 西交利物浦大学
📏 正文 3421 字符

---
西交利物浦大学创建于2006年，由西安交通大学和英国利物浦大学合作创立...
（完整的 Markdown 正文）

🤖 回复
西交利物浦大学（XJTLU）是一所...
```

---

## 9. 关键设计要点

1. **AgentBudget 与硬编码迭代的区别**：AgentBudget 不决定"什么时候停"（这是 LLM 的职责），只决定"什么时候强制停"。正常流程中 LLM 返回 content 不调用工具就退出；只有 token 耗尽 / 死循环 / 轮数爆表才触发强制兜底。

2. **SearchProvider 抽象的价值**：三种 provider 共用同一个接口，ToolRegistry 不需要知道具体实现。新增 provider（如未来加 Brave / Exa）只需实现 `SearchProvider` 接口 + 在工厂加一个分支，零侵入。

3. **为什么默认选 zhipu 而不是 serpapi**：PaiCLI 的主流用户是国内 GLM 用户，已配置 `GLM_API_KEY`。智谱 Web Search 复用同一把 Key、中文搜索效果好、价格更便宜。

4. **web_fetch 空正文是已知边界**：SPA 页面（React/Vue 渲染的）的 HTML body 几乎为空，提取不出正文。通过 `FetchResult.bodyEmpty()` + Prompt 规则告知 LLM"这是已知边界，不要反复重试"，避免无穷重试循环。

5. **WebFetcher 不处理 JS 渲染**：这是设计取舍。JS 渲染需要 Headless 浏览器（第 13/14 期 CDP 路线），本期只覆盖占互联网大部分内容量的静态/SSR 页面。

6. **端点的坑**：ZhipuSearchProvider 在模板项目中使用的是 `tools/web_search` 路径，返回 404。正确路径是 `web_search`（不带 tools）。这是本期开发中发现并修复的实际 bug。

7. **ThreadLocal 线程安全**：`webSearch()` / `webFetch()` 通过 `synchronized` 的懒加载 getter 保证单例，内部无共享可变状态。NetworkPolicy 的 token bucket 使用 `AtomicLong` 保证限流的线程安全。

8. **与 AGENTS.md 保持一致**：`AGENTS.md` 目录结构已追加 `web/` 包，第 4 节已追加 `4.15` 子节，第 6 节表格第 8 期已更新为「已完成」。
