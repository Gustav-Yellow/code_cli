# CLAUDE.md

> 本文件是 Claude Code 在 PaiCLI 仓库工作时的专属指引。
> 与 `AGENTS.md` 的关系：`AGENTS.md` 是给所有 AI Agent 的项目说明书，本文件只放**与 Claude Code 协作强相关的约定**。项目全景请先读 `AGENTS.md` 和 `ROADMAP.md`。

---

## 项目一句话介绍

PaiCLI 是一个用 Java 17 从零手写的终端 coding agent（仿 Claude Code），按 `ROADMAP.md` 的 21 期迭代推进。当前已完成第 1 期（ReAct + Tool Call）和第 2 期（Plan-and-Execute）。

## 工作前必读

按顺序读以下文件，建立全局认知：

1. `ROADMAP.md` — 21 期迭代路线图，确认当前期次和下一期目标
2. `AGENTS.md` — 项目架构、已完成模块、目录结构、维护规则
3. `docs/chapter1-ReAct和Tool Call实现.md` — 第 1 期实现细节教程
4. 相关源码：`src/main/java/com/paicli/` 下的 `cli/`、`agent/`、`llm/`、`tool/`、`plan/` 包

## 关键约定

### 不要做的事

- **不要提前引入框架**：项目主线 21 期坚持手写，不引入 Spring / LangChain4j / Spring AI。框架重写是 21 期完成后 Pro 分支的事。
- **不要把 Spring Boot parent 当 Spring Boot 应用**：`pom.xml` 继承 `spring-boot-starter-parent` 4.1.0 只为依赖管理，主入口 `com.paicli.cli.Main` 是纯 Java `public static void main`，没有 `@SpringBootApplication`。不要随手加 `@Component` / `@Service`。
- **不要给工具抛异常**：`ToolRegistry.executeTool` 失败时返回字符串 `"失败: ..."`，让 Agent 循环继续。新增工具时遵循同一约定。
- **不要改 `GLMClient` 内嵌 record 的字段名**：第 8 期会把它抽象成 `LlmClient` 接口，届时统一改。在那之前保持现状。
- **不要在 `Agent.SYSTEM_PROMPT` / `PlanExecuteAgent.EXECUTION_PROMPT` / `Planner.PLANNING_PROMPT` 里做大幅改写**：第 19 期会把它们迁到 `src/main/resources/prompts/*.md`，届时统一处理。
- **不要跳期实现**：每一期都有前置依赖（见 ROADMAP）。比如第 6 期 HITL 依赖第 1–5 期，不要在第 3 期之前写 HITL 代码。

### 要做的事

- **新增类**：放对模块包下（`com.paicli.<module>.<Class>`），并在 `AGENTS.md` 第 4 节追加子节、第 2 节目录树补一行。
- **新增期次**：先读 ROADMAP 对应章节，若有 `docs/phase-N-*.md` 设计文档则按文档落地；完成后把 `AGENTS.md` 第 6 节表格的状态改为「已完成」并指向新子节。
- **数据模型**：优先用 Java 17 `record`（参考 `GLMClient.Message`、`ToolRegistry.Tool`、`ToolRegistry.Param`）。
- **Javadoc**：类级别写职责，方法级别只写「为什么」不写「做什么」。参考 `ToolRegistry.createParameters` 和 `ToolRegistry.executeTool` 的现有风格。
- **中文输出**：所有面向用户的提示词、错误信息、教程用中文；类名 / 变量名 / 方法名用英文。
- **危险操作前确认**：第 6 期 HITL 未落地前，凡涉及 `write_file` / `execute_command` / `create_project` 的批量改动，先和用户确认范围。

## 构建与运行

```bash
./mvnw clean package                  # 打包
./mvnw spring-boot:run                # 运行（不是 Spring Boot 应用，只是借用插件）
java -jar target/paicli-0.0.1-SNAPSHOT.jar
```

API Key 加载顺序：`./.env` → `~/.env` → 环境变量 `GLM_API_KEY`。格式：`GLM_API_KEY=your_key`。

## 常见任务模式

### 「实现 ROADMAP 第 N 期」
1. 读 `ROADMAP.md` 第 N 期章节，确认前置依赖都已落地
2. 检查 `docs/phase-N-*.md` 是否存在设计文档
3. 按 `AGENTS.md` 第 6 节表格确认计划的关键类
4. 实现完成后，更新 `AGENTS.md` 第 4 节（追加模块说明）和第 6 节表格（状态改「已完成」）
5. 视情况在 `docs/` 下补一篇 `chapterN-*.md` 教程

### 「修 bug / 加小功能」
- 改完代码同步更新对应 `AGENTS.md` 第 4 节的子节描述
- 涉及行为变化的，检查 `docs/chapter1-*.md` 是否需要同步

### 「写教程 / 文档」
- 设计文档放 `docs/phase-N-<topic>.md`，教程放 `docs/chapterN-<title>.md`
- 参考已有 `docs/chapter1-ReAct和Tool Call实现.md` 的结构：目录 → 架构概览 → 类设计 → 端到端示例 → 关键设计要点

## 当前仓库状态参考

- Git 主分支：`main`，初始提交 `39a91e5 initial commit`
- 已暂存未提交：`PlanExecuteAgent.java` / `ExecutionPlan.java` / `Planner.java` / `Task.java`（第 2 期代码）
- 用户名：Gustav

提交前先确认是否包含敏感文件（`.env`、API Key）。`.env` 不应入 git。
