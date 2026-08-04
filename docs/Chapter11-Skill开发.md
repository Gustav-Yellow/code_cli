# Chapter 11：Skill 系统与 web-access Skill 实现

> 本文档整理 paicli 项目中 Skill 系统的完整实现，涵盖 `com.paicli.skill` 包下 7 个核心类、`load_skill` 内置工具、三层目录加载、SkillContextBuffer 惰性注入机制、CLI 命令组，以及内置 `web-access` Skill 的决策手册与站点经验参考文件。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. Skill 是什么](#2-skill-是什么)
- [3. 核心类设计](#3-核心类设计)
  - [3.1 Skill：不可变数据模型](#31-skill不可变数据模型)
  - [3.2 SkillFrontmatterParser：手写 YAML 解析器](#32-skillfrontmatterparser手写-yaml-解析器)
  - [3.3 SkillRegistry：三层目录加载与覆盖](#33-skillregistry三层目录加载与覆盖)
  - [3.4 SkillBuiltinExtractor：jar 内置 Skill 解压](#34-skillbuiltinextractorjar-内置-skill-解压)
  - [3.5 SkillStateStore：启用状态持久化](#35-skillstatestore启用状态持久化)
  - [3.6 SkillIndexFormatter：system prompt 索引段渲染](#36-skillindexformattersystem-prompt-索引段渲染)
  - [3.7 SkillContextBuffer：惰性注入缓冲区](#37-skillcontextbuffer惰性注入缓冲区)
- [4. load_skill 内置工具](#4-load_skill-内置工具)
- [5. 与现有架构的集成](#5-与现有架构的集成)
  - [5.1 Agent.java 集成点](#51-agentjava-集成点)
  - [5.2 PlanExecuteAgent.java 集成点](#52-planexecuteagentjava-集成点)
  - [5.3 SubAgent + AgentOrchestrator 集成点](#53-subagent--agentorchestrator-集成点)
  - [5.4 ToolRegistry 集成点](#54-toolregistry-集成点)
  - [5.5 CLI 集成点（Main.java + CliCommandParser + SkillCommandHandler）](#55-cli-集成点mainjava--clicommandparser--skillcommandhandler)
- [6. CLI 命令组 `/skill`](#6-cli-命令组-skill)
- [7. 内置 web-access Skill](#7-内置-web-access-skill)
  - [7.1 SKILL.md：决策手册](#71-skillmd决策手册)
  - [7.2 references/：站点经验目录 + CDP 速查](#72-references站点经验目录--cdp-速查)
- [8. 完整端到端示例](#8-完整端到端示例)
  - [8.1 场景：读微信公众号文章](#81-场景读微信公众号文章)
  - [8.2 第 1 轮：LLM 自决加载 web-access](#82-第-1-轮llm-自决加载-web-access)
  - [8.3 第 2 轮：按 web-access 指引执行工具链](#83-第-2-轮按-web-access-指引执行工具链)
- [9. 关键设计要点](#9-关键设计要点)
- [10. 与模板项目的差异适配](#10-与模板项目的差异适配)

---

## 1. 整体架构概览

Skill 系统是 PaiCLI 第 15 期的核心功能，将「Agent 该怎么思考」沉淀为可复用的决策单元。它的核心架构由两条数据流组成：

### 启动期数据流

```
jar 内置 resources/skills/web-access/
  ↓ SkillBuiltinExtractor.extractAll()
~/.paicli/skills-cache/web-access/          ← 解压 references/ 到磁盘

~/.paicli/skills/（用户级）       ──→ SkillRegistry.reload()
./.paicli/skills/（项目级）            三层扫描，同名后者覆盖
                                          ↓
                                    SkillIndexFormatter.format()
                                          ↓
                                    注入 Agent / PlanExecuteAgent / SubAgent
                                    的 system prompt 末尾
```

### 运行时数据流

```
用户输入 "帮我看下 https://mp.weixin.qq.com/s/xxx"

LLM 看到 system prompt 中有：
  ## 可用 Skills
  - **web-access**：所有联网与浏览器操作的决策手册...

LLM 判断：这是微信公众号文章 → 调 load_skill("web-access")
  ↓
ToolRegistry.executeTool("load_skill", {"name": "web-access"})
  ↓
SkillContextBuffer.push("web-access", <SKILL.md body>)
  ↓
返回：已加载 skill 'web-access' 的完整指引（X bytes）

下一轮 LLM 收到 user message：
  ## 已加载 Skill：web-access
  <SKILL.md 完整正文>
  ---
  用户输入：（原内容）

LLM 按 web-access 指引：先 web_fetch → 失败 → chrome-devtools MCP
```

### 为什么注入 user message 而不是 system prompt？

- system prompt 在多轮对话中只发一次（节省 token），但 LLM 自决加载是**每轮可能不同**的
- user message 是每轮重发，注入 body 会随轮次自然衰减（buffer 一次性消费）
- system prompt 不变 → prompt cache 命中率高

---

## 2. Skill 是什么

| 维度 | 定义 |
|---|---|
| 形态 | 一个目录，根文件必须是 `SKILL.md` |
| 加载顺序 | jar 内置 < 用户级 `~/.paicli/skills/` < 项目级 `./.paicli/skills/`（同名后者**整体覆盖**前者） |
| 启用控制 | 默认全部启用；`~/.paicli/skills.json` 的 `disabled` 列表关闭 |
| 系统提示词占位 | 启用后只把 `name` + `description` 注入 system prompt（"轻量索引"，≤ 4096 字符） |
| 完整指令展开 | LLM 调 `load_skill("name")`，PaiCLI 把 SKILL.md 正文拼到**下一轮** user message 开头 |

SKILL.md 文件格式（Markdown + YAML frontmatter）：

```markdown
---
name: web-access
description: |
  所有联网与浏览器操作的决策手册：搜索、网页抓取、读 SPA / 防爬墙站点...
version: "1.0.0"
author: PaiCLI
tags: [web, browser, fetch]
---

# 决策手册正文（任意 markdown）
...
```

**字段约定**：
- 必填：`name`（kebab-case，与目录名一致）、`description`（≤ 500 codepoint，超出截断）
- 选填：`version`、`author`、`tags`（数组）
- 未知字段：忽略（不报错），保前向兼容

---

## 3. 核心类设计

`com.paicli.skill` 包下共 7 个类，职责分明、无外部依赖（只依赖 Jackson 和 Java 标准库）。

### 3.1 Skill：不可变数据模型

```java
public record Skill(
        String name,           // kebab-case，必填
        String description,    // 简短描述，必填
        String version,        // 语义版本号，选填
        String author,         // 作者，选填
        List<String> tags,     // 标签，选填
        Source source,         // BUILTIN / USER / PROJECT
        String body,           // SKILL.md 正文（去 frontmatter）
        Path skillMdPath,      // SKILL.md 文件路径
        Path referencesDir     // references/ 目录（可为 null）
) {
    public enum Source { BUILTIN, USER, PROJECT }
}
```

**设计要点**：
- 使用 Java 17 `record`，不可变、线程安全
- 紧凑构造器中做防御性校验：`name` 不可为空，`tags` 做 `List.copyOf` 防止外部修改
- `displaySource()` 返回中文可读的来源标签

### 3.2 SkillFrontmatterParser：手写 YAML 解析器

**为什么不用 SnakeYAML？** 本期 frontmatter 只有 5 个字段（name / description / version / author / tags），全是 string 或 list-of-string。手写 190 行解析器完全够用，引入 SnakeYAML 会带 90KB jar 体积。

**支持的语法**（覆盖 95% 实际写法）：

| 语法 | 示例 |
|---|---|
| 单行字符串 | `name: web-access` |
| 带引号字符串 | `version: "1.0.0"` |
| 多行字符串（literal block） | `description: \|\n  第一行\n  第二行` |
| 行内数组 | `tags: [web, browser, fetch]` |

**不支持的语法**（命中即 warning，跳过该字段，不阻塞整个 skill 加载）：
- 嵌套对象 `key: { nested: ... }`
- YAML anchor / alias / merge key
- 复杂 YAML 类型（`!!str` 等）

**解析流程**：

```
ParseResult parse(String fullText)
  1. 规范化换行符（\r\n → \n）
  2. 检查是否以 "---\n" 开头 → 否则返回 warning
  3. findFrontmatterEnd()：逐行扫描找第二个 "---"
  4. 切分 frontmatterText 和 body
  5. parseFrontmatter()：逐行解析
     - 空行 / 注释行 → 跳过
     - 找第一个不在引号内的冒号位置
     - | → 多行 literal block
     - [...] → 数组
     - 否则 → 单行字符串（去引号）
  6. 返回 ParseResult(frontmatter, body, warnings)
```

**关键方法 `findKeyColonIndex`**：在行内查找第一个不在引号内的冒号，处理了单引号和双引号的嵌套情况，避免 `description: "a: b"` 被错误切分。

### 3.3 SkillRegistry：三层目录加载与覆盖

```java
public final class SkillRegistry {
    // 构造参数
    public SkillRegistry(Path builtinCacheRoot, Path userSkillsDir,
                         Path projectSkillsDir, SkillStateStore stateStore)

    // 核心方法
    public synchronized void reload()           // 重新扫描三层目录
    public synchronized List<Skill> allSkills() // 所有 skill（含 disabled）
    public synchronized List<Skill> enabledSkills() // 启用的 skill
    public synchronized Skill findSkill(String name) // 找已启用的 skill
    public synchronized Skill findAnySkill(String name) // 找任意 skill（含 disabled）
}
```

**三层加载顺序**（后者覆盖前者同名 skill）：

```
loadDirectory(builtinCacheRoot, BUILTIN)   // 1. jar 内置
loadDirectory(userSkillsDir, USER)          // 2. 用户级，可覆盖内置
loadDirectory(projectSkillsDir, PROJECT)    // 3. 项目级，可覆盖前两者
```

**覆盖语义**：同名 skill 按**完整 skill 替换**覆盖（不是字段级 merge）。后加载的 skill 直接 `skillsByName.put(name, skill)` 覆盖前面的。这样用户只需修改不满意的部分，整个 SKILL.md 替换即可。

**`loadDirectory` 实现**：`Files.list(dir)` → 过滤目录 → 找 `SKILL.md` → `parseSkill()`。解析失败的 skill 只写 warning 到 stderr，不阻塞其他 skill 加载。

### 3.4 SkillBuiltinExtractor：jar 内置 Skill 解压

jar 内的 `resources/skills/` 是只读的。LLM 需要通过 `read_file` 读取 `references/` 下的站点经验文件，所以必须在启动时解压到磁盘。

```java
public final class SkillBuiltinExtractor {
    public static final String CURRENT_VERSION = "1.0.0";

    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (BuiltinSkillSpec spec : BUILTIN_SKILLS) {
            extract(spec);  // 逐个解压
        }
    }
}
```

**解压策略**：
1. 检查 `~/.paicli/skills-cache/<name>/.version` 文件
2. 版本一致 → 跳过（避免每次启动 IO）
3. 版本不一致或缺失 → 删除旧目录 → 从 jar `getResourceAsStream` 解压 → 写入 `.version`

**内置 Skill 文件清单**（硬编码，避免 jar 内 resource walk 的跨平台问题）：

```java
private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
    new BuiltinSkillSpec("web-access", List.of(
        "SKILL.md",
        "references/cdp-cheatsheet.md",
        "references/site-patterns/github.com.md",
        "references/site-patterns/juejin.cn.md",
        "references/site-patterns/mp.weixin.qq.com.md",
        "references/site-patterns/x.com.md",
        "references/site-patterns/xiaohongshu.com.md",
        "references/site-patterns/zhuanlan.zhihu.com.md"
    ))
);
```

### 3.5 SkillStateStore：启用状态持久化

设计原则：**只持久化 disabled 列表，启用为隐式默认**。这样新加的 skill 不会被遗漏。

```json
// ~/.paicli/skills.json
{
  "disabled": ["some-skill-name"]
}
```

```java
public final class SkillStateStore {
    public synchronized Set<String> disabled()  // 读 disabled 列表
    public synchronized void disable(String name)  // 加入 disabled
    public synchronized void enable(String name)   // 从 disabled 移除
}
```

- 文件不存在 → 视为空 disabled（不主动创建文件）
- 解析失败 → stderr 警告 + 视为空 disabled，不阻塞启动
- `/skill off <name>` 写入文件，`/skill on <name>` 从文件移除

### 3.6 SkillIndexFormatter：system prompt 索引段渲染

把启用 skill 列表渲染成 system prompt 中的索引段：

```
## 可用 Skills（按需调用 load_skill 加载完整指引）

- **web-access**：所有联网与浏览器操作的决策手册...
- **other-skill**：其他技能的简短描述...

判断准则：当任务描述匹配某个 skill 的触发场景时，调用 load_skill(name) 加载完整指引...
```

**三重预算约束**（命中即截断 + stderr 警告）：

| 约束 | 值 | 实现 |
|---|---|---|
| 单条 description 上限 | ≤ 500 codepoint | `truncateByCodepoint()` 按 Unicode 码点截断 |
| 启用 skill 数上限 | ≤ 20 个 | 按 name 字典序保留前 20 |
| 索引段总大小上限 | ≤ 4096 字符 | `StringBuilder.length()` 检查后截断 |

**为什么用 codepoint 而不是 `String.length()`？** Java 的 `String.length()` 返回 UTF-16 代码单元数，对中文和 emoji 不准确。`codePointCount()` 才能正确处理多语言截断。

### 3.7 SkillContextBuffer：惰性注入缓冲区

```java
public final class SkillContextBuffer {
    private static final int MAX_SKILLS = 3;  // 最多保留 3 个
    private final Map<String, String> entries = new LinkedHashMap<>();

    public synchronized void push(String skillName, String body)
    public synchronized String drain()    // 一次性消费
    public synchronized void clear()
    public synchronized boolean isEmpty()
}
```

**核心机制**：

1. LLM 调 `load_skill("web-access")` → `push("web-access", body)` 写入 buffer
2. 同一 skill 重复 push → 替换旧 body，刷新到末尾（LRU 语义）
3. 下一轮构造 user message 时 → `drain()` 取出全部 body **并清空**（一次性消费）
4. 同一会话内最多保留 3 个 skill body，超出淘汰最旧的

**drain() 输出格式**：

```
## 已加载 Skill：web-access
<SKILL.md body>

---
```

这个字符串被直接前置到用户输入之前，形成完整的 user message content。

**多 Agent 角色隔离**：主 Agent / PlanExecuteAgent / AgentOrchestrator 的 Worker 和 Reviewer 各自持有独立 `SkillContextBuffer` 实例，不共享 buffer，避免角色间提示词污染。

---

## 4. load_skill 内置工具

`load_skill` 是在 `ToolRegistry.registerSkillTools()` 中注册的，与 `read_file` / `web_search` 同级：

```java
tools.put("load_skill", new Tool(
    "load_skill",
    "Load full SKILL.md instructions for a skill the system has indexed...",
    createParameters(new Param("name", "string",
        "the exact kebab-case skill name (e.g. web-access)", true)),
    args -> {
        // 1. 校验 name 非空
        // 2. 校验 skillRegistry 已初始化
        // 3. 查找 skill（findSkill 检查 enabled 状态）
        //    - 不在注册表中 → 提示 /skill list
        //    - 被禁用 → 提示 /skill on <name>
        // 4. body 限 5KB，超出截断
        // 5. push 到 skillContextBuffer
        // 6. 返回简短确认（不返回 body 正文）
    }
));
```

**为什么工具结果不直接返回 body？** 因为工具结果通常被 LLM 当"事实输入"而非"指令"。走 user message 注入 → 模型把它当"用户附加要求"理解，决策权重更高。

---

## 5. 与现有架构的集成

### 5.1 Agent.java 集成点

**三处改动**：

```java
// 改动 1：字段
private SkillRegistry skillRegistry;
private SkillContextBuffer skillContextBuffer;

// 改动 2：updateSystemPromptWithMemory() 追加 skill 索引
private void updateSystemPromptWithMemory(String memoryContext) {
    String externalContext = buildExternalContext();
    String skillIndex = buildSkillIndex();  // ← 新增
    // 三个都空 → 恢复原始 SYSTEM_PROMPT
    // 否则 → StringBuilder 拼接 memory + external + skill
}

private String buildSkillIndex() {
    if (skillRegistry == null) return "";
    return SkillIndexFormatter.format(skillRegistry.enabledSkills());
}

// 改动 3：run() 中 user message 注入 skill body
String userMessageContent = prependSkillBodies(userInput);
conversationHistory.add(LlmClient.Message.user(userMessageContent));

private String prependSkillBodies(String userInput) {
    if (skillContextBuffer == null || skillContextBuffer.isEmpty()) return userInput;
    String drained = skillContextBuffer.drain();
    if (drained.isEmpty()) return userInput;
    return drained + "\n用户输入：\n" + userInput;
}
```

**注意**：当前项目已有 `memoryManager.compressContextIfNeeded()` 处理上下文压缩（第 180 行），因此**未引入**模板项目的 `ConversationHistoryCompactor`。

### 5.2 PlanExecuteAgent.java 集成点

`PlanExecuteAgent` 有自己硬编码的 `EXECUTION_PROMPT`，每个 Task 创建独立的 messages 列表。集成方式：

```java
// 字段
private SkillRegistry skillRegistry;
private SkillContextBuffer skillContextBuffer;

// buildExecutionPrompt() 替代直接使用 EXECUTION_PROMPT
private String buildExecutionPrompt() {
    if (skillRegistry == null) return EXECUTION_PROMPT;
    String skillIndex = SkillIndexFormatter.format(skillRegistry.enabledSkills());
    if (skillIndex.isEmpty()) return EXECUTION_PROMPT;
    return EXECUTION_PROMPT + "\n" + skillIndex;
}

// executeTask() 中使用
String prompt = String.format(buildExecutionPrompt(), task.getType(), task.getDescription());
```

### 5.3 SubAgent + AgentOrchestrator 集成点

`SubAgent` 有三个角色硬编码提示词（PLANNER / WORKER / REVIEWER）。**只给 WORKER 和 REVIEWER 注入 skill 索引**（Planner 只输出 JSON，不需要）。

```java
// SubAgent.java
private SkillRegistry skillRegistry;

private String getSystemPrompt() {
    String basePrompt = switch (role) {
        case PLANNER -> PLANNER_PROMPT;
        case WORKER -> WORKER_PROMPT;
        case REVIEWER -> REVIEWER_PROMPT;
    };
    if (role == AgentRole.PLANNER || skillRegistry == null) return basePrompt;
    String skillIndex = SkillIndexFormatter.format(skillRegistry.enabledSkills());
    return skillIndex.isEmpty() ? basePrompt : basePrompt + "\n" + skillIndex;
}
```

`AgentOrchestrator` 通过 `setSkillSystem()` 分发给 SubAgent：

```java
public void setSkillSystem(SkillRegistry skillRegistry, SkillContextBuffer skillContextBuffer) {
    for (SubAgent worker : workers) worker.setSkillRegistry(skillRegistry);
    reviewer.setSkillRegistry(skillRegistry);
    toolRegistry.setSkillRegistry(skillRegistry);
    toolRegistry.setSkillContextBuffer(skillContextBuffer);
}
```

### 5.4 ToolRegistry 集成点

```java
// 在 ToolRegistry 基类中增加（HitlToolRegistry 通过继承自动获得）
private com.paicli.skill.SkillRegistry skillRegistry;
private com.paicli.skill.SkillContextBuffer skillContextBuffer;

// 构造函数末尾追加
registerSkillTools();

// 新增方法
private void registerSkillTools() { /* load_skill 工具注册 */ }
```

### 5.5 CLI 集成点（Main.java + CliCommandParser + SkillCommandHandler）

**Main.java** 中的初始化顺序（关键）：

```java
// 1. 解压内置 skill
new SkillBuiltinExtractor(skillsCacheDir).extractAll();

// 2. 创建状态存储和注册表
SkillStateStore skillStateStore = new SkillStateStore(home.resolve(".paicli/skills.json"));
SkillRegistry skillRegistry = new SkillRegistry(
        skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
skillRegistry.reload();

// 3. 创建 buffer
SkillContextBuffer skillContextBuffer = new SkillContextBuffer();

// 4. 注入到 ToolRegistry（HitlToolRegistry 继承）
hitlToolRegistry.setSkillRegistry(skillRegistry);
hitlToolRegistry.setSkillContextBuffer(skillContextBuffer);

// 5. 注入到三个 Agent
reactAgent.setSkillRegistry(skillRegistry);
reactAgent.setSkillContextBuffer(skillContextBuffer);
// ... planAgent 和 orchestrator 同理
```

**CliCommandParser** 新增 5 个命令枚举：`SKILL_LIST` / `SKILL_SHOW` / `SKILL_ON` / `SKILL_OFF` / `SKILL_RELOAD`

**SkillCommandHandler** 处理所有 `/skill` 子命令的展示与状态切换逻辑。

---

## 6. CLI 命令组 `/skill`

| 命令 | 行为 |
|---|---|
| `/skill` | 等价 `/skill list` |
| `/skill list` | 列出所有发现的 skill：name / 来源 / 启用状态 / 版本 / description 摘要 |
| `/skill show <name>` | 打印 SKILL.md 全文（含 frontmatter + body + 路径 + references 目录） |
| `/skill on <name>` | 启用 skill，从 `skills.json` 的 disabled 列表中移除 |
| `/skill off <name>` | 禁用 skill，写入 `skills.json` 的 disabled 列表 |
| `/skill reload` | 重新扫描三层目录，重建 skill 表 |

**设计约束**：
- `/skill on` 对未发现的 name 报错，不创建空记录
- `/skill reload` 不影响当前轮次（system prompt 已发出）
- `/skill list` 输出按 name 字典序排列
- 禁用列表只持久化 disabled（默认全启用，新 skill 不会被遗漏）

**启动期摘要**：

```
📚 Skills 加载（1 个）...
   ✓ web-access      builtin   所有联网与浏览器操作的决策手册：搜索、网页抓取、读 SPA...
   1/1 启用
```

---

## 7. 内置 web-access Skill

### 7.1 SKILL.md：决策手册

`src/main/resources/skills/web-access/SKILL.md`（约 110 行），包含以下核心内容：

**浏览哲学（四步法）**：
1. **明确目标** — 用户到底要什么？（事实结论 / 正文摘要 / 决策依据 / 操作完成）
2. **选择起点** — 根据任务性质选最可能直达的一个工具试一次
3. **过程校验** — 每一步结果都是证据，失败有失败的信息，不同工具不反复重试
4. **完成判断** — 拿到目标内容就停，不为"完整"而过度操作

**工具选择表**：

| 场景 | 首选 | 备选 |
|---|---|---|
| 搜索关键词、找入口 | `web_search` | — |
| URL 已知，目标是正文 | `web_fetch` | Jina `r.jina.ai/<url>` |
| web_fetch 返回空 / SPA 提示 | CDP `navigate_page` + `take_snapshot` | — |
| 微信公众号 / 知乎 / Twitter / 小红书 | 直接 chrome-devtools MCP | 不要先 web_fetch（90% 失败） |
| 需要登录态 | `/browser connect` 切 shared | — |
| 表单交互 | CDP `click` / `fill` / `fill_form` | — |

**浏览器优先级**：
```
1. web_fetch（先试一次）
     ↓ 失败 / 空正文
2. chrome-devtools MCP（isolated 模式）
     ↓ 拿到登录页 / 内容仍缺
3. /browser connect 切 shared 模式
     ↓ 仍拿不到
4. Jina Reader 兜底（curl r.jina.ai/）
```

### 7.2 references/：站点经验目录 + CDP 速查

```
src/main/resources/skills/web-access/references/
├── cdp-cheatsheet.md              # 28 个 chrome-devtools MCP 工具速查
└── site-patterns/
    ├── github.com.md              # GitHub 仓库 README / API / 私仓访问
    ├── juejin.cn.md               # 掘金文章（SSR 友好）
    ├── mp.weixin.qq.com.md        # 微信公众号（反爬墙，必须浏览器）
    ├── x.com.md                   # Twitter/X（SPA，data-testid 选择器）
    ├── xiaohongshu.com.md         # 小红书（只能拿搜索列表，拿不到详情）
    └── zhuanlan.zhihu.com.md      # 知乎专栏（SSR 友好但有风控）
```

每个站点经验文件按统一的三段式模板：

```markdown
---
domain: example.com
aliases: [别名]
updated: 2026-05-07
---

## 平台特征（架构 / 反爬强度 / 登录态 / 关键技术事实）

## 有效模式（已验证的 URL 模式、选择器、JS 提取片段、推荐进入路径）

## 已知陷阱（失败模式 + 原因 + 应对）
```

**写回机制**：操作中发现新站点的陷阱或有效模式时，LLM 应主动写到 `~/.paicli/skills/web-access/references/site-patterns/<domain>.md`（用户级目录，不要写 jar 内置缓存）。

---

## 8. 完整端到端示例

### 8.1 场景：读微信公众号文章

**前置条件**：web-access（builtin）已启用，chrome-devtools MCP 已就绪。

**用户输入**：
```
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

### 8.2 第 1 轮：LLM 自决加载 web-access

**system prompt 中可见的 skill 索引段**：
```
## 可用 Skills（按需调用 load_skill 加载完整指引）

- **web-access**：所有联网与浏览器操作的决策手册：搜索、网页抓取、读 SPA / 防爬墙站点...

判断准则：当任务描述匹配某个 skill 的触发场景时，调用 load_skill(name)...
```

**LLM 推理**："这是微信公众号文章 → system prompt 里 web-access 的描述匹配 → 先调 load_skill 了解该怎么处理"

**LLM 返回 tool_calls**：
```json
[
  {
    "id": "call_1",
    "function": {
      "name": "load_skill",
      "arguments": "{\"name\": \"web-access\"}"
    }
  }
]
```

**工具执行**：`SkillRegistry.findSkill("web-access")` 命中 → body 写入 `SkillContextBuffer` → 返回简短确认：
```
已加载 skill 'web-access' 的完整指引（3247 bytes），将在下一轮上下文中以 "## 已加载 Skill：web-access" 段出现。
```

### 8.3 第 2 轮：按 web-access 指引执行工具链

**user message**（PaiCLI 自动构造）：
```

## 已加载 Skill：web-access
# web-access Skill

## 浏览哲学
**像人一样思考，带着目标进入，边看边判断。**
...
## 工具选择表
| 微信公众号 / 知乎专栏 / Twitter / 小红书 | 直接走 chrome-devtools MCP | 不要先 web_fetch（90% 失败） |
...

---
用户输入：
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

**LLM 推理**："web-access 说微信公众号不要先 web_fetch，直接 chrome-devtools MCP → navigate_page → wait_for #js_content → take_snapshot"

**LLM 返回 tool_calls**：
```json
[
  {
    "id": "call_2",
    "function": {
      "name": "mcp__chrome-devtools__navigate_page",
      "arguments": "{\"url\": \"https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg\"}"
    }
  }
]
```

然后 `wait_for("#js_content")` → `take_snapshot()` → 提取正文 → 输出摘要。

---

## 9. 关键设计要点

### 9.1 为什么三层覆盖是"整体替换"而非"字段级 merge"？

字段级 merge 意味着用户改了 description 但 references 还可能指向 builtin 缓存——出现诡异的混合状态。整体替换后用户的 SKILL.md 完全控制一切，可观测性强。

### 9.2 为什么 Skill 索引段有三重预算约束？

如果不加约束，30 个 skill 的索引段轻松突破 10KB，大量挤占对话上下文的 token 预算。三重约束（单条 500 codepoint / 总数 20 个 / 总 4KB）确保索引段始终轻量。

### 9.3 为什么 load_skill 工具返回简短确认而不是 body 正文？

工具结果通常被 LLM 当"事实"理解，而非"指令"。走 user message 注入 → 模型把它当"用户附加要求"，决策权重更高。

### 9.4 为什么 SkillContextBuffer 是一次性消费？

如果 buffer 不清空，同一 skill body 会在每轮都被重复注入，造成 token 浪费和上下文膨胀。一次性消费 + "同一会话内一次足够"的提示词确保 LLM 不重复加载。

### 9.5 为什么只给 WORKER 和 REVIEWER 注入 skill 索引而不给 PLANNER？

PLANNER 的角色只是拆解任务并输出 JSON 计划，不执行工具调用。注入 skill 索引只会浪费 token，对计划质量没有帮助。

### 9.6 与 HITL 的协同

Skill 内 SKILL.md 可能提示 LLM 调用危险工具（如 `execute_command "curl r.jina.ai/..."`），这些调用仍然走 `HitlToolRegistry` 的既有审批流。本期**不**给 Skill 单独的审批维度——沿用既有维度避免审批 UX 的复杂度爆炸。

---

## 10. 与模板项目的差异适配

在参考模板项目 `b946269` 提交实现本期功能时，针对当前项目的架构做了以下适配：

| 差异点 | 模板项目 | 当前项目适配 |
|---|---|---|
| ConversationHistoryCompactor | 作为新类引入 | **未引入**。当前项目已有 `MemoryManager.compressContextIfNeeded()` + `ContextCompressor` + `TokenBudget`，功能更完善 |
| ToolRegistry 类型 | 直接使用 `ToolRegistry` | 使用 `HitlToolRegistry extends ToolRegistry`。setter 加在父类 `ToolRegistry` 上，子类通过继承自动获得 |
| PlanExecuteAgent system prompt | 共享 `Agent.SYSTEM_PROMPT` | 有独立硬编码 `EXECUTION_PROMPT`，通过 `buildExecutionPrompt()` 在末尾拼接 skill 索引 |
| SubAgent 角色提示词 | 共享模板 | 三个独立硬编码提示词。PLANNER 不注入 skill 索引，WORKER/REVIEWER 注入 |
| AgentOrchestrator | `setSkillSystem()` 方法 | 同样增加此方法，分发给 workers + reviewer + toolRegistry |

---

## 11. 文件清单

### 新增文件（16 个）

**Java 类（8 个）**：
- `src/main/java/com/paicli/skill/Skill.java`
- `src/main/java/com/paicli/skill/SkillFrontmatterParser.java`
- `src/main/java/com/paicli/skill/SkillRegistry.java`
- `src/main/java/com/paicli/skill/SkillIndexFormatter.java`
- `src/main/java/com/paicli/skill/SkillContextBuffer.java`
- `src/main/java/com/paicli/skill/SkillBuiltinExtractor.java`
- `src/main/java/com/paicli/skill/SkillStateStore.java`
- `src/main/java/com/paicli/cli/SkillCommandHandler.java`

**资源文件（8 个）**：
- `src/main/resources/skills/web-access/SKILL.md`
- `src/main/resources/skills/web-access/references/cdp-cheatsheet.md`
- `src/main/resources/skills/web-access/references/site-patterns/github.com.md`
- `src/main/resources/skills/web-access/references/site-patterns/juejin.cn.md`
- `src/main/resources/skills/web-access/references/site-patterns/mp.weixin.qq.com.md`
- `src/main/resources/skills/web-access/references/site-patterns/x.com.md`
- `src/main/resources/skills/web-access/references/site-patterns/xiaohongshu.com.md`
- `src/main/resources/skills/web-access/references/site-patterns/zhuanlan.zhihu.com.md`

### 修改文件（7 个）

- `src/main/java/com/paicli/tool/ToolRegistry.java`
- `src/main/java/com/paicli/agent/Agent.java`
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- `src/main/java/com/paicli/agent/SubAgent.java`
- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- `src/main/java/com/paicli/cli/Main.java`
- `src/main/java/com/paicli/cli/CliCommandParser.java`
