# Chapter 4：RAG 代码检索实现

> 本文档整理 paicli 项目中 RAG（Retrieval-Augmented Generation）模块的核心实现，涵盖代码索引（`/index`）、混合检索（`/search`）、图谱查询（`/graph`）三条命令的完整执行流程，以及 `CodeIndex`、`CodeChunker`、`CodeAnalyzer`、`EmbeddingClient`、`VectorStore`、`CodeRetriever` 六个核心类的设计与协作关系。

---

## 目录

- [1. 整体架构概览](#1-整体架构概览)
- [2. 数据模型：CodeChunk 与 CodeRelation](#2-数据模型codechunk-与-coderelation)
  - [2.1 CodeChunk：代码块](#21-codechunk代码块)
  - [2.2 CodeRelation：代码关系](#22-coderelation代码关系)
- [3. 存储层：VectorStore](#3-存储层vectorstore)
  - [3.1 存储位置与表结构](#31-存储位置与表结构)
  - [3.2 语义检索：全量内存余弦相似度](#32-语义检索全量内存余弦相似度)
  - [3.3 关键词检索：SQL LIKE 匹配](#33-关键词检索sql-like-匹配)
  - [3.4 图谱查询：双向关系检索](#34-图谱查询双向关系检索)
  - [3.5 批量写入：事务保护](#35-批量写入事务保护)
- [4. 索引构建：三组件流水线](#4-索引构建三组件流水线)
  - [4.1 CodeChunker：代码分块器](#41-codechunker代码分块器)
  - [4.2 EmbeddingClient：向量化客户端](#42-embeddingclient向量化客户端)
  - [4.3 CodeAnalyzer：关系图谱构建器](#43-codeanalyzer关系图谱构建器)
  - [4.4 CodeIndex：索引编排器](#44-codeindex索引编排器)
- [5. 检索层：CodeRetriever 混合检索](#5-检索层coderetriever-混合检索)
  - [5.1 hybridSearch：混合检索核心流程](#51-hybridsearch混合检索核心流程)
  - [5.2 语义检索](#52-语义检索)
  - [5.3 关键词检索与加权](#53-关键词检索与加权)
  - [5.4 合并去重与排序](#54-合并去重与排序)
- [6. CLI 命令集成](#6-cli-命令集成)
  - [6.1 /index：索引代码库](#61-index索引代码库)
  - [6.2 /search：混合检索](#62-search混合检索)
  - [6.3 /graph：图谱查询](#63-graph图谱查询)
  - [6.4 search_code 工具：LLM 可调用的检索](#64-search_code-工具llm-可调用的检索)
- [7. 辅助组件](#7-辅助组件)
  - [7.1 RagQueryTokenizer：查询分词器](#71-ragquerytokenizer查询分词器)
  - [7.2 SearchResultFormatter：结果格式化](#72-searchresultformatter结果格式化)
  - [7.3 JiebaSegmenterFactory：分词器工厂](#73-jiebasegmenterfactory分词器工厂)
  - [7.4 .env 配置加载](#74-env-配置加载)
- [8. 完整端到端示例](#8-完整端到端示例)
  - [8.1 场景](#81-场景)
  - [8.2 阶段 1：索引代码库 `/index`](#82-阶段-1索引代码库-index)
  - [8.3 阶段 2：语义检索 `/search`](#83-阶段-2语义检索-search)
  - [8.4 阶段 3：图谱查询 `/graph`](#84-阶段-3图谱查询-graph)
  - [8.5 阶段 4：Agent 使用 search_code 工具](#85-阶段-4agent-使用-search_code-工具)
- [9. 关键设计要点](#9-关键设计要点)
- [10. 已知限制与后续演进](#10-已知限制与后续演进)

---

## 1. 整体架构概览

RAG 模块位于 `com.paicli.rag` 包，由 10 个类 + 1 个工具类组成。它不依赖 Spring 或任何框架——SQLite 用原生 JDBC、向量计算在内存完成、AST 解析用 JavaParser。

```
                          用户命令
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
         /index          /search         /graph
              │              │              │
              ▼              ▼              ▼
       ┌──────────┐   ┌──────────┐   ┌──────────┐
       │CodeIndex │   │CodeRe-   │   │CodeRe-   │
       │(索引编排)│   │triever   │   │triever   │
       └────┬─────┘   │(混合检索)│   │(图谱查询)│
            │         └────┬─────┘   └────┬─────┘
   ┌────────┼────────┐     │              │
   │        │        │     │              │
   ▼        ▼        ▼     ▼              ▼
┌──────┐┌──────┐┌──────┐ ┌──────────────────────┐
│Code  ││Embe- ││Code  │ │    VectorStore       │
│Chun- ││dding ││Analy-│ │  (SQLite 持久化)     │
│ker   ││Client││zer   │ │                      │
│(分块)││(向量)││(关系)│ │  code_chunks 表      │
└──────┘└──────┘└──────┘ │  code_relations 表   │
            │             └──────────────────────┘
            ▼                       ▲
   ┌────────────────┐               │
   │ GLM Embedding  │               │
   │ API (远程)     │               │
   └────────────────┘               │
                                    │
   ┌────────────────────────────────┘
   │  ~/.paicli/rag/codebase.db
   └────────────────────────────────
```

**分层设计**：

| 层 | 类 | 职责 |
|---|---|---|
| CLI 集成 | `Main.java` / `CliCommandParser` / `ToolRegistry` | 命令路由、工具注册 |
| 索引编排 | `CodeIndex` | 遍历文件 → 分块 → 向量化 → 入库 |
| 检索入口 | `CodeRetriever` | 混合检索（语义+关键词）、图谱查询 |
| 存储引擎 | `VectorStore` | SQLite CRUD、余弦相似度计算 |
| 分块/分析 | `CodeChunker` / `CodeAnalyzer` | AST 解析、代码切分、关系提取 |
| 向量化 | `EmbeddingClient` | 调 Embedding API 生成向量 |
| 辅助 | `RagQueryTokenizer` / `SearchResultFormatter` / `JiebaSegmenterFactory` | 分词、格式化、工具类 |

**调用链路**：

```
/index → CodeIndex.index(projectPath)
           ├─ collectFiles(root)                    遍历文件树
           ├─ for each file:
           │    ├─ CodeChunker.chunkFile(file)       分块
           │    ├─ EmbeddingClient.embed(text)        向量化
           │    └─ CodeAnalyzer.analyzeFile(file)     关系提取
           └─ VectorStore.clearProject()
              VectorStore.insertChunks(entries)       写入 SQLite
              VectorStore.insertRelations(relations)

/search → CodeRetriever.hybridSearch(query, topK)
            ├─ semanticSearch(query)                 语义检索
            │    └─ EmbeddingClient.embed(query)
            │       VectorStore.search(vector, topK*2)
            ├─ keywordSearch(tokens...)               关键词检索
            │    └─ RagQueryTokenizer.tokenize(query)
            │       VectorStore.searchByKeyword(keyword)
            ├─ mergeResult + boostKeywordMatch       合并加权
            └─ limitPerFile                         同文件去重

/graph  → CodeRetriever.getRelationGraph(name)
            └─ VectorStore.getRelations(name)
```

### 依赖

新增了三个依赖（`pom.xml`）：

| 依赖 | 用途 |
|---|---|
| `org.xerial:sqlite-jdbc:3.49.1.0` | SQLite JDBC 驱动 |
| `com.github.javaparser:javaparser-core:3.28.0` | Java AST 解析（分块 + 关系提取） |
| `com.huaban:jieba-analysis:1.0.2` | 中文分词（查询分词，第 3 期已引入） |

---

## 2. 数据模型：CodeChunk 与 CodeRelation

### 2.1 CodeChunk：代码块

文件：`src/main/java/com/paicli/rag/CodeChunk.java`

```java
public record CodeChunk(String filePath, String chunkType, String name,
                        String content, int startLine, int endLine) {
}
```

三种粒度，对应三种 `chunkType`：

| chunkType | 含义 | name 示例 | 来源 |
|---|---|---|---|
| `file` | 整个文件或文件分段 | `src/.../Main.java` 或 `Main.java#1` | 非 Java 文件 / Java 解析失败回退 |
| `class` | 类声明 | `Agent` | JavaParser AST 提取的类声明 |
| `method` | 方法声明 | `Agent.run` | JavaParser AST 提取的方法体 |

**三个静态工厂方法**简化构造：

```java
CodeChunk.fileChunk(path, content)
CodeChunk.classChunk(path, "Agent", header, 12, 45)
CodeChunk.methodChunk(path, "Agent.run", body, 30, 42)
```

**`toEmbeddingText()`** 生成送给 Embedding API 的文本：

```java
// 输入 → "[class:Agent] public class Agent { ..."
return String.format("[%s:%s] %s", chunkType, name, content);
```

前缀 `[class:Agent]` 帮助 Embedding 模型理解这个文本块的语义角色。

### 2.2 CodeRelation：代码关系

文件：`src/main/java/com/paicli/rag/CodeRelation.java`

```java
public record CodeRelation(String fromFile, String fromName,
                           String toFile, String toName, String relationType) {
}
```

| relationType | 含义 | 示例 |
|---|---|---|
| `extends` | 类继承 | `PlanExecuteAgent` extends `Agent` |
| `implements` | 接口实现 | `LongTermMemory` implements `Memory` |
| `imports` | 导入依赖 | `Main.java` imports `CodeIndex` |
| `calls` | 方法调用 | `Agent.run()` calls `GLMClient.chat()` |
| `contains` | 类包含方法 | `Agent` contains `Agent.run` |

`fromFile` / `toFile` 非空保证可追溯；`toFile` 可为 null（当目标是非项目内类时，如 JDK 类）。

---

## 3. 存储层：VectorStore

文件：`src/main/java/com/paicli/rag/VectorStore.java`

`VectorStore` 是 SQLite 持久化层，一张 `code_chunks` 表存代码块和向量，一张 `code_relations` 表存图谱关系。实现 `AutoCloseable`，确保连接正确关闭。

### 3.1 存储位置与表结构

数据库文件在 `~/.paicli/rag/codebase.db`，可通过 JVM 属性 `paicli.rag.dir` 自定义。

```sql
CREATE TABLE code_chunks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    project_path    TEXT NOT NULL,    -- 项目绝对路径（区分多项目）
    file_path       TEXT NOT NULL,    -- 源文件相对路径
    chunk_type      TEXT NOT NULL,    -- file / class / method
    name            TEXT NOT NULL,    -- 类名 / 方法签名 / 文件名
    content         TEXT NOT NULL,    -- 代码原文
    embedding_json  TEXT,             -- 向量 JSON，如 [0.12, -0.34, ...]
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE code_relations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    project_path    TEXT NOT NULL,
    from_file       TEXT NOT NULL,
    from_name       TEXT NOT NULL,
    to_file         TEXT,
    to_name         TEXT,
    relation_type   TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

6 个索引加速查询：

```sql
CREATE INDEX idx_project      ON code_chunks(project_path);
CREATE INDEX idx_file         ON code_chunks(file_path);
CREATE INDEX idx_type         ON code_chunks(chunk_type);
CREATE INDEX idx_rel_project  ON code_relations(project_path);
CREATE INDEX idx_rel_from     ON code_relations(from_name);
CREATE INDEX idx_rel_to       ON code_relations(to_name);
```

### 3.2 语义检索：全量内存余弦相似度

```java
public List<SearchResult> search(float[] queryEmbedding, int topK) {
    // 1. 加载当前项目全部 chunk
    SELECT file_path, chunk_type, name, content, embedding_json
    FROM code_chunks WHERE project_path = ?

    // 2. 逐条计算余弦相似度
    for each row:
        float[] embedding = jsonToEmbedding(embedding_json);
        double similarity = cosineSimilarity(queryEmbedding, embedding);

    // 3. 降序排序取 TopK
    candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
    return candidates.subList(0, topK);
}
```

余弦相似度公式：

```java
private double cosineSimilarity(float[] a, float[] b) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

**设计取舍**：每检索一次全量加载所有向量到内存，O(n) 时间复杂度。对代码库规模（几百到几千个块）足够；规模再大可换 FAISS / pgvector / DiskANN。代码注释已写明这一点。

### 3.3 关键词检索：SQL LIKE 匹配

```java
public List<SearchResult> searchByKeyword(String keyword) {
    SELECT file_path, chunk_type, name, content
    FROM code_chunks
    WHERE project_path = ? AND (name LIKE ? ESCAPE '\' OR content LIKE ? ESCAPE '\')
}
```

关键细节：

- **SQL 注入防护**：使用 `PreparedStatement`，不拼接字符串
- **LIKE 转义**：`\`、`%`、`_` 三字符全部转义，防止用户输入被当作通配符
- **固定基准分**：所有关键词命中结果 `similarity = 0.3`，后续由 `CodeRetriever` 按命中位置加权
- **不经过 Embedding**：纯文本匹配，用于精确查找类名/方法名

### 3.4 图谱查询：双向关系检索

```java
// 查询与指定名称相关的所有关系（incoming + outgoing）
public List<CodeRelation> getRelations(String name) {
    SELECT ... FROM code_relations
    WHERE project_path = ? AND (from_name = ? OR to_name = ?)
}

// 只查询 outgoing 关系
public List<CodeRelation> getOutgoingRelations(String name) {
    SELECT ... FROM code_relations
    WHERE project_path = ? AND from_name = ?
}
```

### 3.5 批量写入：事务保护

`insertChunks()` 和 `insertRelations()` 都用 `setAutoCommit(false)` + `executeBatch()` + `commit()` 模式，写入失败自动 `rollback()`：

```java
connection.setAutoCommit(false);
try (PreparedStatement ps = ...) {
    for (entry : entries) {
        ps.setString(1, ...);
        ps.addBatch();
    }
    ps.executeBatch();
    connection.commit();
} catch (SQLException e) {
    connection.rollback();
    throw e;
} finally {
    connection.setAutoCommit(autoCommit);
}
```

---

## 4. 索引构建：三组件流水线

`/index` 命令触发一条三阶段流水线：

```
文件遍历 → CodeChunker 分块 → EmbeddingClient 向量化 → VectorStore 入库
              └─ CodeAnalyzer 关系提取 ──────────────────┘
```

### 4.1 CodeChunker：代码分块器

文件：`src/main/java/com/paicli/rag/CodeChunker.java`

**分块策略**：

| 文件类型 | 策略 | 粒度 |
|---|---|---|
| `.java` | JavaParser AST 解析 → 类声明 + 每个方法 | class / method |
| `.java`（解析失败） | 回退到按大小分段 | file |
| 非 `.java`（如 `.py` `.js` `.md`） | 按 2000 字符分段 | file |

**类级别 chunk**：只取类声明头 5 行（含注解、类名、extends/implements），不作为完整类体，语义足够让 Embedding 理解"这是一个什么类"。

**方法级别 chunk**：取完整方法体，`methodSignature` 用 `getDeclarationAsString(false, false, false)` 不带修饰符/注解/异常，保留核心签名。

**分块上限**：`MAX_CHUNK_CHARS = 2000`（中文约 4000~6000 token），安全适配 8192 上下文模型。

**关键代码**：

```java
private List<CodeChunk> chunkJavaFile(Path filePath, String content) {
    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
        // 类级别
        chunks.add(CodeChunk.classChunk(filePath, className, classHeader, start, end));

        // 方法级别
        clazz.getMethods().forEach(method -> {
            chunks.add(CodeChunk.methodChunk(filePath,
                    className + "." + methodSignature, methodContent, start, end));
        });
    });
}
```

### 4.2 EmbeddingClient：向量化客户端

文件：`src/main/java/com/paicli/rag/EmbeddingClient.java`

**支持的 Provider**：

| provider | 默认 URL | API 格式 |
|---|---|---|
| `ollama` | `http://localhost:11434` | Ollama `/api/embeddings` |
| `openai` | 需配置 `EMBEDDING_BASE_URL` | OpenAI `/embeddings` |
| `zhipu` / `glm` | `https://open.bigmodel.cn/api/paas/v4` | OpenAI 兼容 `/embeddings` |

**配置方式**（通过环境变量或 `.env` 文件）：

```
EMBEDDING_PROVIDER=glm
EMBEDDING_MODEL=embedding-3
EMBEDDING_BASE_URL=https://open.bigmodel.cn/api/paas/v4
EMBEDDING_API_KEY=your_key
```

**`getEnv()` 取值顺序**：`System.getenv(key)` → `System.getProperty(key)` → `defaultValue`。`Main.loadEnvConfig()` 会把 `.env` 全部写入 `System.setProperty()`，因此 `.env` 中的配置自动生效。

**输入截断**：`embed()` 自动截断超过 2000 字符的文本，防止 API 报错。

**超时配置**：connect 30s / read 120s（向量化比聊天快，但大文本仍需放宽 read 超时）。

### 4.3 CodeAnalyzer：关系图谱构建器

文件：`src/main/java/com/paicli/rag/CodeAnalyzer.java`

基于 JavaParser AST 提取五类关系：

```java
public List<CodeRelation> analyzeFile(Path filePath) {
    // 1. imports → 导入依赖（过滤 java.* / javax.*）
    extractImports(filePath, cu, relations);

    // 2. extends → 类继承
    // 3. implements → 接口实现
    // 4. contains → 类包含方法
    // 5. calls → 方法调用（通过 findParentMethod 找到调用者）
    extractClassRelations(filePath, cu, relations);
}
```

**方法调用归属**：用 `findParentMethod()` 从 AST 节点向上遍历找到包含它的 `MethodDeclaration`，确保 `calls` 关系的 `fromName` 是完整的 `ClassName.methodName` 格式。

**import 过滤**：只记录非 JDK 导入（`!importName.startsWith("java.") && !importName.startsWith("javax.")`），作为项目内依赖的近似判断。

### 4.4 CodeIndex：索引编排器

文件：`src/main/java/com/paicli/rag/CodeIndex.java`

**核心方法**：`index(String projectPath)`

```
index(root)
  ├─ collectFiles(root)
  │    └─ Files.walkFileTree → 跳过 node_modules/.git/target/build/.idea/.vscode/dist/out/.* 目录
  │    └─ 只收集 17 种代码文件扩展名（.java .py .js .ts .go .rs .c .cpp .h .md .xml .properties .yaml .yml .json .sh .gradle .kt）
  │
  ├─ for each file（带进度回调，每 10 个文件通知一次）:
  │    ├─ chunker.chunkFile(file)
  │    ├─ embeddingClient.embed(chunk.toEmbeddingText())
  │    └─ analyzer.analyzeFile(file)（仅 .java）
  │
  └─ VectorStore.clearProject() + insertChunks() + insertRelations()
```

**三个构造函数**：

```java
new CodeIndex()                                          // 默认 EmbeddingClient + noop listener
new CodeIndex(EmbeddingClient client)                     // 自定义 Embedding + noop listener
new CodeIndex(ProgressListener listener)                  // 默认 Embedding + 自定义进度回调
new CodeIndex(EmbeddingClient client, ProgressListener l) // 全自定义
```

**进度回调**：`ProgressListener` 是函数式接口 `void onProgress(String message)`，CLI 层注入 `System.out::println` 实现实时进度输出。

**容错设计**：

- **单文件容错**：某个文件解析/向量化失败不影响其他文件，打印 `⚠️ 索引失败: xxx.java` 继续
- **全量替换**：每次 `/index` 先 `clearProject()` 再写入，保证索引与代码库一致
- **问题 1：向量丢失风险**：如果持久化阶段失败（`clearProject` 或 `insertChunks` 抛异常），前面已调 API 生成的向量全部丢失（当前阶段可接受，重新 `/index` 即可）

---

## 5. 检索层：CodeRetriever 混合检索

文件：`src/main/java/com/paicli/rag/CodeRetriever.java`

### 5.1 hybridSearch：混合检索核心流程

```
hybridSearch(query, topK)
  │
  ├─ ① 语义检索
  │    └─ EmbeddingClient.embed(query) → VectorStore.search(vector, topK*2)
  │       返回相似度 0.0 ~ 1.0 的结果
  │
  ├─ ② 关键词检索
  │    └─ RagQueryTokenizer.tokenize(query)
  │       for each keyword: VectorStore.searchByKeyword(keyword)
  │       返回相似度 0.3 的结果，再按命中位置加权（+0.1 ~ +0.5）
  │
  ├─ ③ 合并去重 (mergeResult)
  │    └─ key = filePath + "#" + name
  │    └─ 重复出现 → 取 max(semantic, keyword) + 0.1 双重命中奖励
  │
  ├─ ④ 代码类型加分
  │    └─ method → +0.15, class → +0.10, file → 不加
  │
  └─ ⑤ 同文件去重 (limitPerFile)
       └─ 同一文件最多保留 2 个结果，总数不超过 topK
```

### 5.2 语义检索

```java
public List<SearchResult> semanticSearch(String query, int topK) {
    float[] queryEmbedding = embeddingClient.embed(query);  // 调 API 生成查询向量
    return vectorStore.search(queryEmbedding, topK);         // SQLite 内存余弦排序
}
```

`topK * 2`（最小 10）作为语义检索的候选池，确保合并去重后有足够候选。

### 5.3 关键词检索与加权

`RagQueryTokenizer.tokenize(query)` 用 jieba 中文分词 + 正则提取 ASCII 标识符，生成关键词集合。每个关键词调 `VectorStore.searchByKeyword()` 做 LIKE 匹配。

`boostKeywordMatch()` 按命中位置加权：

| 命中位置 | 加分 | 说明 |
|---|---|---|
| name（类名/方法名） | +0.3 | 最强信号——精确命中标识符 |
| filePath（文件名） | +0.1 | 中等信号——文件名包含关键词 |
| content（代码内容） | +0.1 | 弱信号——代码中出现关键词 |

**基准分 0.3 + 最高加分 0.5 = 0.8**，确保关键词结果不会压过语义结果（max 1.0）。

### 5.4 合并去重与排序

**合并策略**（`mergeResult`）：

```java
// key = "src/Agent.java#Agent.run" 保证同一 chunk 不重复
String key = candidate.filePath() + "#" + candidate.name();

if (existing == null) {
    merged.put(key, candidate);               // 首次出现
} else {
    double best = Math.max(existing, candidate); // 取高分
    if (!dualMatchBonused.contains(key)) {
        best += 0.1;                             // 语义+关键词双命中 → +0.1
        dualMatchBonused.add(key);               // 只加一次
    }
}
```

**类型加分**：method/class 比 file 更直接回答"怎么实现"。

**同文件限制**（`limitPerFile`）：同一文件最多保留 2 个结果，防止同一个大类的所有方法占据全部 TopK。

---

## 6. CLI 命令集成

### 6.1 /index：索引代码库

**CLI 入口**（`Main.java`）：

```java
case INDEX_CODE -> {
    String indexPath = command.payload() != null ? command.payload() : ".";
    System.out.println("📦 正在索引代码库: " + indexPath);
    CodeIndex indexer = new CodeIndex(System.out::println);
    CodeIndex.IndexResult result = indexer.index(indexPath);
    System.out.println(result.message() + "\n");

    // 同步项目路径到 ToolRegistry
    String absPath = new File(indexPath).getAbsolutePath();
    reactAgent.getToolRegistry().setProjectPath(absPath);
}
```

**命令解析**（`CliCommandParser.java`）：

```java
if (trimmed.equalsIgnoreCase("/index")) {
    return new ParsedCommand(CommandType.INDEX_CODE, null);         // 默认索引当前目录
}
if (trimmed.regionMatches(true, 0, "/index ", 0, 7)) {
    return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim()); // 指定路径
}
```

**使用示例**：

```
/index                        # 索引当前目录
/index /path/to/project       # 索引指定项目
```

### 6.2 /search：混合检索

```java
case SEARCH_CODE -> {
    String query = command.payload();
    if (query == null || query.isEmpty()) {
        showSearchUsage();     // 三层兜底：已索引 → 显示统计 + 示例；未索引 → 提示 /index；异常 → 降级提示
        continue;
    }
    try (CodeRetriever retriever = new CodeRetriever(".")) {
        var stats = retriever.getStats();
        if (stats.chunkCount() == 0) {
            System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令");
        }
        List<SearchResult> results = retriever.hybridSearch(query, 5);
        System.out.println(SearchResultFormatter.formatForCli(query, results));
    }
}
```

`showSearchUsage()` 三层兜底策略：

| 场景 | 输出 | 设计意图 |
|---|---|---|
| 已索引 | `📊 当前索引: 349 个代码块, 2247 条关系` + 用法示例 | 让用户知道有多少内容可搜 |
| 未索引 | `⚠️ 代码库尚未索引，请先使用 /index 命令` | 明确告知下一步 |
| 异常 | `💡 用法: /search <关键词或自然语言描述>` | 降级不崩溃 |

**使用示例**：

```
/search 用户登录实现
/search Agent 类的工具调用逻辑
```

### 6.3 /graph：图谱查询

```java
case GRAPH_QUERY -> {
    String className = command.payload();
    if (className == null || className.isEmpty()) {
        System.out.println("❌ 请提供类名，例如 /graph Main\n");
        continue;
    }
    try (CodeRetriever retriever = new CodeRetriever(".")) {
        List<CodeRelation> relations = retriever.getRelationGraph(className);
        for (CodeRelation rel : relations) {
            String arrow = switch (rel.relationType()) {
                case "contains"   -> "├── contains -->";
                case "extends"    -> "└── extends -->";
                case "implements" -> "└── implements -->";
                case "calls"      -> "├── calls -->";
                default           -> "├── " + rel.relationType() + " -->";
            };
            System.out.printf("   %s %s [%s]%n", rel.fromName(), arrow, rel.toName());
        }
    }
}
```

**使用示例**：

```
/graph Main
/graph Agent
```

### 6.4 search_code 工具：LLM 可调用的检索

文件：`src/main/java/com/paicli/tool/ToolRegistry.java`

```java
tools.put("search_code", new Tool(
    "search_code",
    "语义检索代码库，根据自然语言描述查找相关代码块",
    createParameters(
        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
        new Param("top_k", "integer", "返回结果数量（默认是 5）", false)
    ),
    args -> {
        try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
            }
            List<SearchResult> results = retriever.hybridSearch(query, topK);
            return SearchResultFormatter.formatForTool(query, results);
        }
    }
));
```

**Agent 使用场景**：

```
用户: "帮我找一下 TokenBudget 是怎么管理 token 的"

Agent 内部:
  → LLM 决定调用 search_code(query="TokenBudget token管理", top_k=5)
  → CodeRetriever.hybridSearch() 返回 Top5 相关代码块
  → LLM 基于检索结果回答用户
```

---

## 7. 辅助组件

### 7.1 RagQueryTokenizer：查询分词器

文件：`src/main/java/com/paicli/rag/RagQueryTokenizer.java`

**分词策略**：jieba 中文分词 + 正则提取 ASCII 标识符（类名/方法名如 `Agent.run`、`TokenBudget`）。

**停用词过滤**：

```java
case "怎么", "如何", "什么", "哪些", "一下", "实现", "的是", "一个", "可以", "这里", "那里" -> true;
```

**有意义性检查**：至少包含一个汉字或一个 ASCII 字母/数字，过滤纯标点符号。

### 7.2 SearchResultFormatter：结果格式化

文件：`src/main/java/com/paicli/rag/SearchResultFormatter.java`

两种输出模式：

| 方法 | 目标读者 | 格式 |
|---|---|---|
| `formatForCli(query, results)` | 终端用户 | 排名 + 摘要 + 120 字符代码片段 |
| `formatForTool(query, results)` | LLM | 检索摘要 + 导航建议 + 180 字符代码片段 |

**摘要内容**（`buildSummary`）：

```
搜索摘要:
- 最相关的入口是 [method:Agent.run]，位于 paicli/agent/Agent.java。
- 当前结果主要集中在 Agent.java、Main.java 这些文件。
- 这次排序综合参考了 Agent、循环 等关键词与语义相似度；先看第 1 条，再按文件继续展开最稳妥。
```

路径截断：`shortenPath()` 保留最后 3 级目录。

### 7.3 JiebaSegmenterFactory：分词器工厂

文件：`src/main/java/com/paicli/util/JiebaSegmenterFactory.java`

jieba-analysis 在首次加载词典时会向 stdout 打印初始化信息。此工厂在构造分词器时临时将 `System.out` 重定向到 `ByteArrayOutputStream`，初始化完成后恢复，避免词典加载日志污染 CLI 界面。

```java
public static JiebaSegmenter createSilently() {
    synchronized (JiebaSegmenterFactory.class) {
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            return new JiebaSegmenter();
        } finally {
            System.setOut(originalOut);
        }
    }
}
```

使用 `synchronized` 防止并发场景下 stdout 状态错乱。

### 7.4 .env 配置加载

文件：`src/main/java/com/paicli/cli/Main.java`（`loadEnvConfig()` 方法）

重构后的 `.env` 加载器将**所有 `KEY=VALUE`** 写入 `System.setProperty()`：

```java
private static String loadEnvConfig() {
    for (File envFile : new File[]{new File(ENV_FILE),
            new File(System.getProperty("user.home"), ENV_FILE)}) {
        if (envFile.exists()) {
            loadDotEnvFile(envFile); // 逐行解析 KEY=VALUE → System.setProperty
            break;
        }
    }
    // 优先 System.getProperty，其次 System.getenv
    String apiKey = System.getProperty("GLM_API_KEY");
    return apiKey != null ? apiKey : System.getenv("GLM_API_KEY");
}
```

这使得 `EmbeddingClient.getEnv()` 的 `System.getProperty(key)` 回退路径可以命中 `.env` 中的配置，不再需要手动 `export` 到 OS 环境变量。

---

## 8. 完整端到端示例

### 8.1 场景

用户在 PaiCLI 中索引 paicli 项目自身，然后用自然语言检索 "Token 预算是怎么管理的"，最后查看 `Agent` 类的关系图谱。

### 8.2 阶段 1：索引代码库 `/index`

```
👤 你: /index
📦 正在索引代码库: .
🔍 开始索引: /Users/hjh/.../paicli
📁 发现 50 个文件待索引
   进度: 10/50 (Main.java)
   进度: 20/50 (Agent.java)
   进度: 30/50 (TokenBudget.java)
   进度: 40/50 (VectorStore.java)
   进度: 50/50 (SearchResultFormatter.java)
✅ 索引完成：349 个代码块，2247 条关系
```

**内部执行流程**：

```
CodeIndex.index(".")
  ├─ collectFiles → 50 个代码文件
  ├─ for Main.java:
  │    ├─ CodeChunker.chunkFile → 1 class chunk + 15 method chunks
  │    ├─ EmbeddingClient.embed() × 16 次 → 16 个 float[] 向量
  │    └─ CodeAnalyzer.analyzeFile → 45 条关系
  ├─ for Agent.java:
  │    └─ ...
  ├─ ...（50 个文件全部处理）
  └─ VectorStore
       ├─ clearProject()
       ├─ insertChunks(349 entries)   → code_chunks 表
       └─ insertRelations(2247 rels)  → code_relations 表
```

### 8.3 阶段 2：语义检索 `/search`

```
👤 你: /search Token 预算是怎么管理的
🔍 检索: Token 预算是怎么管理的
📋 找到 5 个相关代码块:

搜索摘要:
- 最相关的入口是 [class:TokenBudget]，位于 paicli/memory/TokenBudget.java。
- 当前结果主要集中在 TokenBudget.java、MemoryManager.java、Agent.java 这些文件。
- 这次排序综合参考了 Token、预算 等关键词与语义相似度；先看第 1 条，再按文件继续展开最稳妥。

1. [class:TokenBudget] (相似度: 0.923) src/main/java/com/paicli/memory/TokenBudget.java
   public class TokenBudget {
       private static final int MAX_CONTEXT_TOKENS = 8192;
       ...

2. [method:MemoryManager.compressContextIfNeeded] (相似度: 0.856) ...MemoryManager.java
   public void compressContextIfNeeded(List<Message> history) { ... }

...
```

**内部执行流程**：

```
CodeRetriever.hybridSearch("Token 预算是怎么管理的", 5)
  │
  ├─ ① RagQueryTokenizer.tokenize("Token 预算是怎么管理的")
  │    → {"Token", "预算", "管理"}  （"怎么"、"的" 被过滤）
  │
  ├─ ② semanticSearch("Token 预算是怎么管理的", 10)
  │    └─ EmbeddingClient.embed(query) → GLM API → float[2048]
  │    └─ VectorStore.search(vector, 10) → 10 个语义候选
  │
  ├─ ③ keywordSearch("Token") + keywordSearch("预算") + keywordSearch("管理")
  │    └─ "Token" → LIKE 命中 name="TokenBudget" → boostKeywordMatch(+0.3 name)=0.6
  │    └─ "预算" → LIKE 命中 name="TokenBudget"(content) → boostKeywordMatch(+0.1)=0.4
  │    └─ "管理" → LIKE 命中 content 多处 → boostKeywordMatch(+0.1)=0.4
  │
  ├─ ④ mergeResult：TokenBudget 同时出现在语义和关键词结果 → 双重命中 +0.1
  │
  ├─ ⑤ class 类型 +0.10
  │
  └─ ⑥ limitPerFile：TokenBudget.java 最多保留 2 个结果
```

### 8.4 阶段 3：图谱查询 `/graph`

```
👤 你: /graph TokenBudget
🕸️ 查询类关系图谱: TokenBudget
📋 找到 8 条关系:

   Agent TokenBudget ├── imports --> [TokenBudget]
   MemoryManager TokenBudget ├── imports --> [TokenBudget]
   TokenBudget MAX_CONTEXT_TOKENS ├── contains --> [TokenBudget.MAX_CONTEXT_TOKENS]
   TokenBudget isWithinBudget ├── contains --> [TokenBudget.isWithinBudget]
   MemoryManager getSystemStatus ├── calls --> [isWithinBudget]
   MemoryManager compressContextIfNeeded ├── calls --> [isWithinBudget]
   MemoryManager recordTokenUsage ├── calls --> [addTokens]
   TokenBudget addTokens ├── contains --> [TokenBudget.addTokens]
```

**内部执行流程**：

```
CodeRetriever.getRelationGraph("TokenBudget")
  └─ VectorStore.getRelations("TokenBudget")
       └─ SELECT * FROM code_relations WHERE from_name='TokenBudget' OR to_name='TokenBudget'
```

### 8.5 阶段 4：Agent 使用 search_code 工具

```
👤 你: 帮我看看 MemoryManager 里面是怎么压缩对话的

Agent 内部:
  → LLM: "用户想知道 MemoryManager 的对话压缩实现，我应该先检索代码"
  → tool_call: search_code { query: "MemoryManager 对话压缩 compressContextIfNeeded", top_k: 5 }
  → ToolRegistry: CodeRetriever.hybridSearch(...)
  → 返回 Top5 相关代码块（含 MemoryManager.compressContextIfNeeded 方法体）
  → LLM: 基于检索结果，总结压缩流程：Map-Reduce、user-boundary 切分、ContextCompressor
  → 输出给用户
```

---

## 9. 关键设计要点

1. **一张表存四种信息**：`code_chunks` 同时存关键字、类/方法元信息、代码内容、向量，避免多表 JOIN。`code_relations` 单独存关系图。

2. **混合检索优于单一策略**：语义检索覆盖面广但可能漏精确匹配（类名/方法名），关键词检索精确但语义理解弱。合并后双重命中奖励 + 类型加权，综合效果更好。

3. **全量内存余弦相似度**：代码库几百到几千个块时 O(n) 计算足够快，避免引入 FAISS 等外部依赖。规模再大可换。

4. **事务保护 + 批量写入**：`setAutoCommit(false)` + `executeBatch()` + rollback on error，保证数据一致性。

5. **单文件容错不中断**：某个文件分块或向量化失败不阻断整体索引，只打印警告继续下一个。

6. **全量替换而非增量更新**：每次 `/index` 先 `clearProject()` 再写入，简单可靠，避免增量的脏数据问题。

7. **`.env` 全局加载**：`loadEnvConfig()` 把所有 `KEY=VALUE` 写入 `System.setProperty()`，使得 `EmbeddingClient` 无需单独解析 `.env`。

8. **无参 `/search` 三层兜底**：已索引时显示统计 + 用法、未索引时提示 `/index`、异常时降级提示。

9. **ProgressListener 函数式接口**：CLI 层注入 `System.out::println`，索引/检索过程可观测，降低"黑盒感"。

10. **EmbeddingClient 多 Provider 支持**：通过切换 `EMBEDDING_PROVIDER` 自动适配不同 API（Ollama 本地 / OpenAI / 智谱），URL 和认证方式自动推断。

---

## 10. 已知限制与后续演进

| 限制 | 影响 | 后续计划 |
|---|---|---|
| 语义检索全表扫描 | 块数 >5000 时检索变慢 | 换 FAISS / pgvector / DiskANN |
| 只有 Java 文件走 AST 分块和关系提取 | Python/JS/Go 等文件降级为 file 粒度 | 增加多语言解析器 |
| 持久化失败导致向量丢失 | 需重新 `/index`，浪费一次 API 调用 | 增量写入 + 断点续传 |
| 图谱查询未注册为 LLM 工具 | Agent 无法在 ReAct 循环中查询代码关系 | 按需注册 `query_graph` 工具 |
| Embedding 文本上限 2000 字符 | 超大方法/文件被截断 | 滑动窗口分块策略 |
| `VectorStore` 未实现连接池 | 多线程并发检索可能冲突 | 增加连接池或切换到 HikariCP |
