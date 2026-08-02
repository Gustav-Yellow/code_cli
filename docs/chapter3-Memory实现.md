# Chapter 3：Memory 上下文记忆实现

> 本文档整理 paicli 项目中 Memory 模块的核心实现，涵盖长期记忆持久化、对话压缩机制、Token 预算管理、共享上下文架构，以及 ReAct/Plan 模式切换时的记忆连续性保障。并记录实现过程中发现的四个关键问题及其修复方案。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. Memory 接口与 MemoryEntry](#2-memory-接口与-memoryentry)
- [3. LongTermMemory：长期记忆持久化](#3-longtermmemory长期记忆持久化)
- [4. ConversationMemory 的引入与删除](#4-conversationmemory-的引入与删除)
- [5. ContextCompressor：对话压缩器](#5-contextcompressor对话压缩器)
- [6. MemoryRetriever：长期记忆检索器](#6-memoryretriever长期记忆检索器)
- [7. TokenBudget：Token 预算管理器](#7-tokenbudgettoken-预算管理器)
- [8. MemoryQueryTokenizer：中文分词器](#8-memoryquerytokenizer中文分词器)
- [9. MemoryManager：门面类 + 压缩-事实提取联动](#9-memorymanager门面类--压缩-事实提取联动)
- [10. 共享上下文架构：ReAct/Plan 模式下的记忆连续性](#10-共享上下文架构reactplan-模式下的记忆连续性)
- [11. 四个关键问题与修复](#11-四个关键问题与修复)
- [12. 完整端到端验证](#12-完整端到端验证)
- [13. 关键设计要点](#13-关键设计要点)
- [14. `/memory` 动态展示 + SUMMARY 存储](#14-memory-动态展示--summary-存储)
  - [14.1 对话上下文统计与动态预算](#141-对话上下文统计与动态预算)
  - [14.2 压缩摘要存入长期记忆](#142-压缩摘要存入长期记忆)
  - [14.3 TOOL\_RESULT 扩展点](#143-tool_result-扩展点)

---

## 1. 整体架构概览

Memory 模块位于 `com.paicli.memory` 包，由 8 个类组成。它不直接持有对话上下文——对话上下文由 `Agent` / `PlanExecuteAgent` 自己维护的 `conversationHistory`（`List<GLMClient.Message>`）承担——Memory 模块负责**跨会话**的长期记忆、对话压缩、Token 预算和检索。

```
Main.java (会话级单例)
  ├── List<Message> sharedHistory       ← 对话上下文（喂 LLM 的唯一来源）
  └── MemoryManager sharedMemory        ← 门面
        ├── LongTermMemory              ← 长期事实持久化（~/.paicli/memory/）
        ├── ContextCompressor           ← Map-Reduce 摘要 + 事实提取
        ├── MemoryRetriever             ← 长期记忆检索 + 相关度排序
        ├── TokenBudget                 ← 上下文窗口预算 + Token 统计
        └── [依赖] MemoryQueryTokenizer ← jieba 中文分词
```

**数据格式严格分离**：
- **`GLMClient.Message`**（role/content/toolCalls/toolCallId）：对话上下文载体，存于 `conversationHistory`。压缩、事实提取输入。
- **`MemoryEntry`**（id/content/type/timestamp/metadata/tokenCount）：**仅长期记忆**的持久化载体。落盘 JSON、检索、事实存储。

Message ↔ MemoryEntry 只在**一个方向、一个点**衔接：`ContextCompressor.extractFacts` 把 `List<Message>` 喂给 LLM 提取事实文本，再由 `LongTermMemory.store` 包成 `MemoryEntry`（FACT 类型）落盘。不存在双向重建。

### 依赖

新增了 `jieba-analysis` 依赖（`pom.xml`），用于中文分词。

---

## 2. Memory 接口与 MemoryEntry

### 2.1 Memory 接口

文件：`src/main/java/com/paicli/memory/Memory.java`

```java
public interface Memory {
    void store(MemoryEntry entry);
    Optional<MemoryEntry> retrieve(String id);
    List<MemoryEntry> search(String query, int limit);
    List<MemoryEntry> getAll();
    boolean delete(String id);
    void clear();
    int getTokenCount();
    int size();
}
```

当前只有 `LongTermMemory` 一个实现。最初 `ConversationMemory` 也实现此接口，后来因与 `conversationHistory` 冗余而被删除（见第 4 节）。

### 2.2 MemoryEntry

文件：`src/main/java/com/paicli/memory/MemoryEntry.java`

记忆系统的基础数据单元。字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 唯一标识（如 `fact-a1b2c3d4`） |
| `content` | String | 文本内容 |
| `type` | MemoryType | CONVERSATION / FACT / SUMMARY / TOOL_RESULT |
| `timestamp` | Instant | 创建时间（默认 `Instant.now()`） |
| `metadata` | Map<String, String> | 附加信息 |
| `tokenCount` | int | 粗略 token 估算 |

`estimateTokens(text)` 先区分中文字符和英文 / 其他字符，按中文 1.5 字/token、英文 4 字符/token 粗估。这个估算存在系统性偏差（如 Finding 5 所指），但不影响压缩判断的正确性。

---

## 3. LongTermMemory：长期记忆持久化

文件：`src/main/java/com/paicli/memory/LongTermMemory.java`

### 3.1 存储机制

- **内存**：`ConcurrentHashMap<String, MemoryEntry>`，线程安全。
- **持久化**：JSON 数组写入 `long_term_memory.json`。
- **存储目录**：`~/.paicli/memory/`（可通过 `paicli.memory.dir` 系统属性或 `PAICLI_MEMORY_DIR` 环境变量覆盖）。
- **加载**：构造器启动时从磁盘加载已有记忆。
- **写入**：每次 `store()` 调用后立即 `saveToDisk()` 落盘。
- **去重**：基于内容完全相等检查，避免重复存储同一事实。

### 3.2 关键方法

- `store(entry)`：去重后放入 map，累加 token 计数，立即落盘。
- `search(query, limit)`：用 `MemoryQueryTokenizer` 对 query 分词，匹配 entry 的 `content` 和 `metadata` 值（子串匹配），限制返回条数。
- `getByType(type)`：按 `MemoryType` 筛选。
- `entryToMap / mapToEntry`：序列化/反序列化辅助，处理 `Instant` → `String` 和类型解析。

### 3.3 持久化格式

```json
[
  {
    "id": "fact-a1b2c3d4",
    "content": "用户姓名为 Gustav",
    "type": "FACT",
    "timestamp": "2026-08-01T10:30:00Z",
    "metadata": {},
    "tokenCount": 5
  }
]
```

---

## 4. ConversationMemory 的引入与删除

最初实现了一个 `ConversationMemory` 类（`Memory` 接口的实现），用 `LinkedHashMap` 维护对话条目，带有 token 上限（`maxTokens`）和自动淘汰机制（`evictOldest`）。设计意图是作为短期记忆的独立容器。

但在实际开发中发现三个根本问题：

1. **与 `conversationHistory` 重复**：Agent 已经维护了 `List<Message> conversationHistory` 作为发给 LLM 的上下文。ConversationMemory 是同一份数据的影子副本（通过 `addUserMessage`/`addAssistantMessage`/`addToolResult` 双写同步）。

2. **压缩作用不到 LLM 上下文**：压缩作用在 ConversationMemory 上，摘要回注到 memory entries，但 `conversationHistory` 只增不减——发给 LLM 的 token 从未减少。

3. **`compressedSummaries` 死存储**：淘汰旧条目时暂存到 `compressedSummaries` 列表，但压缩触发时 `clear()` 会将其清空——从未被真正使用。

最终决定删除 `ConversationMemory`，以 `conversationHistory` 为唯一对话上下文源。压缩和事实提取都直接操作 `List<Message>`。这消除了双写、同步步骤和格式转换。

---

## 5. ContextCompressor：对话压缩器

文件：`src/main/java/com/paicli/memory/ContextCompressor.java`

### 5.1 压缩策略

采用 **Map-Reduce** 两段法：

1. **Map**：旧消息每 5 条一片，各自调 LLM 生成 ≤200 字摘要。
2. **Reduce**：多个分片摘要合并为 ≤300 字总摘要。

压缩结果作为独立 `Message.system("[历史对话摘要] ...")` 插在 `history[1]`（不在 `history[0]`，因为 Agent 每轮会重置 index 0）。下次压缩时，这条摘要落入 old 段被纳入 map-reduce，**自然向前滚动**。

### 5.2 切分保证：user-boundary

`compressHistory` 的切分点**必须落在 `user` 消息上**。从末尾向前数 `retainRecentRounds`（默认 3）个 `user` 消息作为 recent 窗口起点。这保证：

- recent 窗口内 `assistant(toolCalls)` → `tool` 的配对完整，不会被切断。
- 返回的 `trimmed` 列表 = `[system, 摘要, user, assistant, tool, ...]`，消息序列合法。

返回类型为 `CompressionResult(trimmed, discarded)`，把被丢弃的旧消息一并交出，供调用方在丢弃前提取事实（见第 9 节）。

### 5.3 事实提取

`extractFacts(List<Message>, LongTermMemory)`：把对话消息按 `role: content` 拼接 → LLM 用 `EXTRACT_FACTS_PROMPT` 提取关键事实 → 逐行过滤 → `longTermMemory.store` 落盘。

提取目标：
- 用户偏好和习惯
- 项目信息（名称、路径、技术栈）
- 重要决策和约定

### 5.4 降级处理

LLM 调用失败时：
- Map 阶段：降级为截取前 200 字标 `[压缩]`。
- Reduce 阶段：降级为直接分号拼接。

---

## 6. MemoryRetriever：长期记忆检索器

文件：`src/main/java/com/paicli/memory/MemoryRetriever.java`

### 6.1 设计定位

只检索**长期记忆**（`LongTermMemory`）。短期对话上下文已由 `conversationHistory` 直接发给 LLM，无需在此重复检索（避免 LLM 看两遍同样的内容）。

### 6.2 检索策略

- **精确匹配**：content 包含完整 query → 得分 1.0。
- **关键词匹配**：query 经 `MemoryQueryTokenizer` 分词后，计算 content 中命中的词数占比。
- **无时间衰减**：长期事实不应因"老"被降权（这是从初版改进的关键点——初版有 0.5 的地板衰减）。

### 6.3 上下文组装

`buildContextForQuery(query, maxTokens)` → 检索 top-10 → 格式化为 `## 相关记忆\n\n- [FACT] xxx` → 控制在 `maxTokens` 内 → 注入到 LLM 的 system prompt。

---

## 7. TokenBudget：Token 预算管理器

文件：`src/main/java/com/paicli/memory/TokenBudget.java`

### 7.1 预算模型

```
可用预算 = contextWindow - reservedForSystem(500) - reservedForTools(800) - reservedForResponse(2000)
        ≈ 196,700 tokens（默认 contextWindow=200,000）
```

### 7.2 关键方法

| 方法 | 作用 | 调用时机 |
|---|---|---|
| `isWithinBudget(List<Message>)` | 检查消息列表是否在预算内 | 每次 `compressContextIfNeeded` 的触发判断 |
| `getAvailableForConversation()` | 返回历史可用的 token 数 | 预算计算 |
| `estimateMessagesTokens(messages)` | 估算消息列表的 token 数 | `isWithinBudget` 内部 |
| `recordUsage(in, out)` | 累加输入/输出 token 统计 | 每次 `llmClient.chat` 后（含工具调用迭代） |

### 7.3 Token 估算

`estimateMessagesTokens` 累加每条消息的 `content` + `toolCalls.arguments`，每条消息额外 4 token（role/separator 开销）。暂未计入 `toolCallId` 和 `function.name`（Finding 5，轻微低估，不影响压缩判断）。

---

## 8. MemoryQueryTokenizer：中文分词器

文件：`src/main/java/com/paicli/memory/MemoryQueryTokenizer.java`

基于 **jieba** 分词，过滤单字和纯标点，保留 ≥2 字的词语用于关键词匹配。

- `tokenize(query)`：对 query 执行 jieba 分词 → 过滤 → 去重的 `LinkedHashSet`。
- `matches(text, tokens)`：检查 text 中子串匹配任一 token。

---

## 9. MemoryManager：门面类 + 压缩-事实提取联动

文件：`src/main/java/com/paicli/memory/MemoryManager.java`

### 9.1 组合结构

```
MemoryManager
  ├── LongTermMemory  longTermMemory    ← 持久化事实
  ├── ContextCompressor compressor      ← 摘要 + 事实提取
  ├── MemoryRetriever retriever         ← 长期记忆检索
  └── TokenBudget      tokenBudget      ← 预算 + 统计
```

### 9.2 核心 API

| 方法 | 说明 |
|---|---|
| `storeFact(fact)` | 手动存储事实到长期记忆（`/save` 命令） |
| `buildContextForQuery(query, maxTokens)` | 检索长期记忆，返回可注入 system prompt 的文本 |
| `compressContextIfNeeded(List<Message> history)` | 检查预算 → 超限则压缩 + 提取事实 → 原地替换 history |
| `extractAndSaveFacts(List<Message> history)` | 过滤 system 消息 → LLM 提取事实 → 长期记忆落盘 |
| `recordTokenUsage(in, out)` | 记录一次 LLM 调用的 token 消耗 |
| `getSystemStatus()` | 长期记忆统计 + Token 统计（`/memory` 命令） |

### 9.3 压缩 → 事实提取联动（Finding 1）

```java
public void compressContextIfNeeded(List<Message> history) {
    if (tokenBudget.isWithinBudget(history)) return;
    CompressionResult result = compressor.compressHistory(history, RETAIN_RECENT_ROUNDS);
    if (result == null) return;
    // ⬇️ 丢弃旧消息前，先从中提取事实到长期记忆
    extractAndSaveFacts(result.discarded());
    // 用压缩后的消息替换 history
    history.clear();
    history.addAll(result.trimmed());
}
```

这是 Finding 1 修复的核心：旧消息被摘要替换前，先调用 `extractAndSaveFacts` 把旧消息中的关键事实落盘，避免知识随摘要永久丢失。

---

## 10. 共享上下文架构：ReAct/Plan 模式下的记忆连续性

### 10.1 设计动机

初版中 ReAct（`Agent.java`）和 Plan（`PlanExecuteAgent.java`）各自维护独立的 `conversationHistory` 和 `MemoryManager`。这导致：

- **长期记忆不互通**：两个 Agent 各 `new LongTermMemory`，虽然落盘文件相同，但内存实例互不可见（需重启才能看到对方的写入）。
- **对话上下文断裂**：从 ReAct 切到 Plan 再切回 ReAct，ReAct 的上下文消失。
- **Plan 无压缩**：PlanExecuteAgent 没有持久化的 `conversationHistory`，无法在"轮开始前"压缩。

### 10.2 共享机制

`Main.java` 在启动时创建**会话级单例**，注入两个 Agent：

```java
MemoryManager sharedMemory = new MemoryManager(new GLMClient(apiKey));
List<GLMClient.Message> sharedHistory = new ArrayList<>();

Agent reactAgent = new Agent(apiKey, sharedHistory, sharedMemory);
PlanExecuteAgent planAgent = new PlanExecuteAgent(apiKey, handler, sharedHistory, sharedMemory);
```

- **`sharedHistory`**：两个 Agent 共读共写。ReAct 直接读写；Plan 只在 `run()` 开头压缩 + 追加 `goal`、末尾追加 `result`。
- **`sharedMemory`**：含共享 `LongTermMemory`，任一 Agent 提取的事实立即可被另一 Agent 检索。

### 10.3 各 Agent 的行为

**ReAct（`Agent.run`）**：

1. `buildContextForQuery` 检索长期记忆 → 注入 system prompt。
2. `sharedHistory.add(Message.user(input))`。
3. while 循环：`compressContextIfNeeded(sharedHistory)` → `chat` → `recordTokenUsage` → 工具/返回。
4. 结束时 `sharedHistory.add(Message.assistant(result))`。

**Plan（`PlanExecuteAgent.run`）**：

1. `compressContextIfNeeded(sharedHistory)`（轮开始前压缩，与 ReAct 同一调用）。
2. `buildPriorContext(sharedHistory, 8)` → 取最近 8 条历史（跳过 index 0 system），格式化为 `role: content` 供 Planner 做上下文感知规划（Stage 2）。
3. `sharedHistory.add(Message.user(goal))`。
4. `runWithPlan(goal, priorContext)` → `planner.createPlan(goal, priorContext)`。
5. `executePlan` 用**局部 `messages`**（不污染共享历史），每 Task 内无压缩，`recordTokenUsage` 正常记录。
6. `executePlan` 末尾 `extractFactsFromPlan(plan)` 写入共享长期记忆。
7. `sharedHistory.add(Message.assistant(结果))`。

### 10.4 数据流（示意图）

```
Main: sharedHistory + sharedMemory（会话级单例）
  ├─ ReAct run():  检索长期记忆 → set(0, system+memory) → add user
  │                 循环{ compress(sharedHistory)→超限则摘要+提取 discarded 事实
  │                       chat → recordTokenUsage → 工具/返回 }
  └─ Plan run():   compress(sharedHistory) → buildPriorContext → add user(goal)
                   → createPlan(goal, priorContext)  ← Stage 2 读历史
                   → executePlan（局部 messages，无压缩，recordTokenUsage 每次）
                   → add assistant(result) → extractFactsFromPlan → 共享 LTM
```

---

## 11. 四个关键问题与修复

### Finding 1：压缩丢弃旧消息时未提取事实

**问题**：`compressContextIfNeeded` 用摘要替换旧消息时，旧消息中的关键事实直接丢失——既不在长期记忆也不在 history。下次 `/clear` 提取时只剩有损摘要。

**修复**：`compressHistory` 返回 `CompressionResult(trimmed, discarded)`，把被丢弃的旧消息交出来。`MemoryManager` 先 `extractAndSaveFacts(discarded)` 再应用 `trimmed`。

### Finding 2：PlanExecuteAgent 的 per-task 压缩形同虚设

**问题**：`executeTask` 内每个 Task 用局部 `messages`，只有 1 条 user。`compressHistory` 需要从末尾数到第 3 个 `user` 才做切分——永远 `return null`。那行压缩是死代码。

**修复**：删除 `executeTask` 的 `compressContextIfNeeded(messages)`。改为 Plan `run()` 开头对 `sharedHistory` 调用同一个压缩——因共享上下文而成立。

### Finding 3：长期记忆实例不共享

**问题**：两个 Agent 各自 `new LongTermMemory`。Plan 提取的事实落盘了，但 ReAct 的内存实例不重载 → 会话内互不可见。

**修复**：`Main` 创建单一 `LongTermMemory`（包在 `sharedMemory` 中），注入两个 Agent。任一 Agent 的 `store`/`extractFacts` 立即对另一 Agent 的 `retrieval` 可见。

### Finding 4：工具调用迭代的 token 未统计

**问题**：`recordTokenUsage` 只在"无工具调用"分支调用。工具调用迭代的 `llmClient.chat` 响应的 input/output tokens 全被漏掉。

**修复**：把 `recordTokenUsage` 上移到 `llmClient.chat` 之后、`if (hasToolCalls)` 之前，一次调用覆盖两条分支。

---

## 12. 完整端到端验证

用真实 LLM 调用跑通了 `ReAct → Plan → ReAct` 的完整对话流程，并打印 `sharedHistory` 每一步内容。流程如下。

### Step 1 · ReAct #1：建立上下文

```
👤 我叫 Gustav，主要用 Java 17 开发，请记住
```

Agent 调用了 `write_file` 将信息保存到 `./user_profile.md`。

**sharedHistory 状态**（5 条）：

| idx | role | content |
|---|---|---|
| 0 | system | 你是一个智能编程助手... |
| 1 | user | 我叫 Gustav，主要用 Java 17 开发，请记住 |
| 2 | assistant | 你好，Gustav！...（含 toolCalls: write_file） |
| 3 | tool | 文件已写入: ./user_profile.md |
| 4 | assistant | 已经记录好了！... |

### Step 2 · Plan：上下文感知规划

```
👤(/plan) 用一句话说明 Java 语言是什么
```

Plan `run()`：`buildPriorContext(sharedHistory, 8)` 从共享历史取最近 8 条 → 传入 `createPlan(goal, priorContext)`。

**Stage 2 证据**：Planner 生成的 **task_1 是"读取用户画像文件 ./user_profile.md，确认用户 Gustav 的身份和技术背景（Java 17 开发者）"**——原始 goal 只写了"用一句话说明 Java 语言是什么"，**没有提到 Gustav 或 user_profile.md**。这说明 Planner 确实读了共享历史做上下文感知。

Plan 用 4 个 Task（读画像 → 分析 Java → 写描述文件 → 验证）完成目标，并在 `executePlan` 末尾 `extractFactsFromPlan` 提取了 **8 条事实**到共享长期记忆。

**sharedHistory 状态**（7 条，多了 goal + plan 结果，Task 内部细节不进共享历史）：

| idx | role | content |
|---|---|---|
| 5 | user | 用一句话说明 Java 语言是什么 |
| 6 | assistant | ✅ 计划执行完成！... |

### Step 3 · ReAct #2：验证连续性

```
👤 我叫什么名字？我用什么语言开发？
```

Agent 回答：**"你的名字：Gustav，你的开发语言：Java 17"**。

这证明：ReAct #1 的"我叫 Gustav"跨过 Plan 完整保留到 ReAct #2（输入 1075 tokens = 完整的 7 条 sharedHistory + 注入的长期记忆上下文）。

### Token 统计验证

`/memory` 输出：

```
Token 统计: 调用 10 次 | 总输入: 8654 | 总输出: 2155
```

对账：ReAct#1(2) + plan: task_1(2) + task_2(1) + task_3(2) + task_4(2) + ReAct#2(1) = **10 次** — 含所有工具调用迭代。

### 长期记忆验证

Plan 提取的 8 条事实：

```
[FACT] 用户姓名为 Gustav
[FACT] 用户的主要开发语言为 Java 17
[FACT] 用户画像文件路径为 ./user_profile.md
[FACT] 用户具备 Java 技术背景，交流时可直接使用技术术语
...
```

两个 Agent 共享同一个 `LongTermMemory` 实例，ReAct#2 的 `buildContextForQuery` 可检索到这些事实——**会话内无需重启即可见**。

---

## 13. 关键设计要点

### 1. Message / MemoryEntry 单向转换

```
Message (对话上下文) ──extractFacts()→ LLM → 事实文本 ──LongTermMemory.store()→ MemoryEntry (落盘)
```

仅此一个衔接点。不存在 MemoryEntry → Message 的重建。这避免了双向转换的信息丢失和维护复杂度。

### 2. 压缩摘要作为独立 system 消息

不合入 `history[0]`，因为 Agent 的 `updateSystemPromptWithMemory` 每轮会 `set(0, ...)` 重置。独立消息插入 index 1，可跨轮存活，且下次压缩时落入 old 段被纳入 map-reduce 自然滚动。

### 3. 压缩前提取事实

这是保证"知识不随摘要丢失"的关键：旧消息被丢弃前，先 `extractAndSaveFacts(discarded)`。`/clear` 时也会提取一次（从整个 `conversationHistory`），形成双层保障。

### 4. 共享历史中 Plan 只追加 goal + result

Plan 内部 Task 的局部 `messages`（含工具调用细节）**不入共享历史**，避免污染。共享历史保留高层次的 goal → result 对，ReAct 看到的是精炼的上下文。

### 5. 长期记忆不动，短期记忆删除

`LongTermMemory`、`MemoryEntry`、`Memory` 接口始终未动。被删除的只有 `ConversationMemory`——它是对 `conversationHistory` 的冗余镜像，删除后消除了双写、同步、格式转换的复杂度。

### 6. Token 预算阈值偏高

默认 `contextWindow=200,000`，可用预算 ≈196K。大多数会话碰不到触发压缩。若需更早压缩，调小 `contextWindow` 或预留值即可（参数选择，非逻辑错误）。

---

## 14. `/memory` 动态展示 + SUMMARY 存储

### 14.1 对话上下文统计与动态预算

**问题**：`/memory` 命令（`MemoryManager.getSystemStatus()`）只展示长期记忆条目数和 Token 累计调用统计，不显示当前对话历史的规模。且 `TokenBudget.getAvailableForConversation()` 返回的是静态值 196,700（`200000 - 500 - 800 - 2000`），"可用"字段永远不变——没有反映当前历史的实际占用。

**修复**：

- `TokenBudget` 新增 `estimateCurrentHistoryTokens(history)` 和 `getBudgetUsagePercent(history)` 两个实例方法，委托已有的 `estimateMessagesTokens` 静态方法。
- `MemoryManager` 新增 `getSystemStatus(List<Message> history)` 重载，前端展示：

```
对话上下文: 9 条消息 | 估算占用 1,075 / 最大 196,700 tokens (0.5%) | 预算剩余 ~195,625
长期记忆: 8条 / 114 tokens (事实: 8, 摘要: 1, 工具结果: 0)
Token 统计: 调用 10 次 | 总输入: 8,654 | 总输出: 2,155 | 平均输入: 865 | 预算: 200000 (可用: 196700)
```

- `Main.java:/memory` 调用处改为 `sharedMemory.getSystemStatus(sharedHistory)`，传入当前会话历史。
- 无参 `getSystemStatus()` 保留，用于不需要上下文统计的场景。

**效果**：对话增长时「预算剩余」递减，压缩后回升——用户可直观感知上下文占用与压缩效果。

### 14.2 压缩摘要存入长期记忆

**问题**：压缩生成的摘要（Map-Reduce 结果）只以 `Message.system("[历史对话摘要] ...")` 形式留在 `conversationHistory` 中。会话结束后摘要随内存释放而消失——无法被后续会话检索，不能跨会话传达"上次聊了什么"。

**修复**：`MemoryManager.compressContextIfNeeded` 中，在应用 trimmed 之前，调用新增的私有方法 `storeCompressionSummary(trimmed)`：

```java
private void storeCompressionSummary(List<Message> trimmed) {
    // trimmed.get(1) = 压缩生成的摘要 system 消息
    String content = trimmed.get(1).content()
            .replaceFirst("\\[历史对话摘要\\] ", "");
    longTermMemory.store(new MemoryEntry("summary-...", content, SUMMARY, ...));
}
```

摘要以 `MemoryType.SUMMARY` 存入共享长期记忆，`MemoryRetriever.buildContextForQuery` 可检索——下次新会话问"上次我们聊了什么"，摘要可被命中。

压缩日志序列（新增第 3 行）：

```
📦 上下文超预算，触发压缩...
🧠 提取关键事实到长期记忆...   提取了 N 条事实
   已将压缩摘要存入长期记忆
   ✓ 已压缩历史对话
```

### 14.3 TOOL_RESULT 扩展点

`MemoryEntry.MemoryType.TOOL_RESULT` 枚举已存在，`LongTermMemory.store` 本就接受任意类型。当前未实现自动存储通路——工具结果量大（`read_file` 大文件可达数万字符），无条件存储会快速膨胀长期记忆。

此枚举作为第 4 期 RAG 阶段的扩展点保留。届时可在选择性存储策略下（如只存成功后的小结果、或对结果做切分后再存）激活 `storeToolResult` 通路。
