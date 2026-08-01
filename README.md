# PaiCLI

> 一个用 Java 17 从零手写的终端 coding agent，按 21 期迭代路线图逐步演进。对标 Claude Code / Aider / DeepSeek TUI。

当前已完成 **第 1 期（ReAct + Tool Call）** 和 **第 2 期（Plan-and-Execute）**，支持两种执行模式：单步 ReAct 循环和多步 Plan-and-Execute DAG 编排。

---

## 目录

- [项目特色](#项目特色)
- [架构概览](#架构概览)
- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [使用示例](#使用示例)
- [命令参考](#命令参考)
- [可用工具](#可用工具)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [开发路线图](#开发路线图)
- [文档](#文档)

---

## 项目特色

- **手写优先，框架在后**：21 期主线全部手写，不引入 Spring / LangChain4j 等框架抽象
- **双模式执行**：ReAct（单步循环）+ Plan-and-Execute（DAG 编排），按 `/plan` 命令切换
- **HITL 计划审查**：计划生成后阻塞等待用户确认，支持执行 / 补充要求 / 取消三种决策
- **DAG 拓扑排序**：三色 DFS 同时完成排序和环检测，保证依赖顺序安全
- **分批并发执行**：同批次内无依赖的任务用线程池并行执行（上限 4 线程）
- **JLine 终端控制**：raw mode 单键读取、括号粘贴、ESC 序列处理，提供流畅的交互体验
- **失败自动重规划**：任务失败且进度 < 50% 时自动触发 replan

---

## 架构概览

```
┌──────────────┐   用户输入    ┌──────────────────┐
│   Main.java  │ ────────────► │  Agent /         │
│ (CLI REPL)   │ ◄──────────── │  PlanExecuteAgent│
└──────────────┘   最终文本     └────────┬─────────┘
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            │                          │                          │
            ▼                          ▼                          ▼
     ┌──────────┐               ┌────────────┐              ┌────────────┐
     │ GLMClient│               │ToolRegistry│              │  Planner   │
     │ (HTTP)   │               │  (5 工具)  │              │ (任务分解) │
     └──────────┘               └────────────┘              └────────────┘
                                         │                          │
                                         ▼                          ▼
                                ┌───────────────┐         ┌────────────────┐
                                │  执行工具调用  │         │ ExecutionPlan  │
                                │               │         │ (DAG + 拓扑排序)│
                                └───────────────┘         └────────────────┘
```

**两种执行模式**：
- **ReAct 模式**（默认）：`Agent.run()` 循环调用 LLM + 工具，适合简单任务
- **Plan 模式**（`/plan` 触发）：`PlanExecuteAgent` → `Planner` 生成 DAG → 用户审查 → 分批并发执行

---

## 功能特性

### 第 1 期：ReAct + Tool Call

- 🤖 基于 GLM 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 5 个内置工具（文件操作、Shell 命令、项目创建）
- 💬 交互式命令行界面
- 📊 Token 使用统计

### 第 2 期：Plan-and-Execute

- 📋 Plan-and-Execute 模式：先规划后执行
- 🎯 任务 DAG 分解与依赖管理
- 🔀 三色 DFS 拓扑排序 + 环检测
- ⚡ 分批并发执行（同批次内无依赖任务并行）
- 📝 计划审查 HITL：执行 / 补充要求 / 取消
- 🎨 ASCII 计划可视化（`visualize()` 完整视图 + `summarize()` 折叠摘要）
- 🔄 失败自动重规划（进度 < 50% 时触发 replan）
- ⌨️ JLine 终端控制（raw mode 单键读取、括号粘贴、ESC 序列处理）
- 🔁 单 Task 内多轮工具调用（`MAX_TASK_ITERATIONS = 5`）

---

## 快速开始

### 1. 配置 API Key

在项目根目录或用户主目录创建 `.env` 文件：

```bash
echo "GLM_API_KEY=your_zhipu_api_key" > .env
```

API Key 加载顺序：
1. 当前目录 `./.env`
2. 用户主目录 `~/.env`
3. 环境变量 `GLM_API_KEY`

### 2. 编译运行

```bash
# 编译打包
./mvnw clean package

# 运行（任选一种）
./mvnw spring-boot:run
java -jar target/paicli-0.0.1-SNAPSHOT.jar
```

### 3. 启动界面

```text
========================================
           PaiCLI v2.0.0
      Plan-and-Execute Agent CLI
========================================

✅ API Key 已加载

🔄 使用 ReAct 模式

💡 提示:
   - 输入你的问题或任务
   - 输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式
   - 输入 '/plan 任务内容' 直接用计划模式执行这条任务
   - 计划生成后可直接执行、补充要求重规划，或取消
   - 默认模式是 ReAct
   - 输入 '/clear' 清空对话历史
   - 输入 '/exit' 或 '/quit' 退出
```

---

## 使用示例

### ReAct 模式（默认）

```text
👤 你: 创建一个Java项目叫myapp

🤔 思考中...

🔧 执行工具: create_project
   参数: {"name":"myapp","type":"java"}
   结果: 项目已创建: myapp (类型: java)

📊 Token使用: 输入=156, 输出=89

🤖 Agent: 已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### Plan-and-Execute 模式

输入 `/plan 任务内容` 直接进入 Plan 模式：

```text
👤 你: /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 计划摘要
   - 目标: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml...
   - 任务数: 3 | 并行批次: 3 | 当前可执行: 1 | 状态: CREATED
   - 首批执行: task_1
   - 最终收敛: task_3

📝 计划已生成。
   - 回车：按当前计划执行
   - Ctrl+O：展开完整计划
   - ESC：折叠或取消本次计划
   - I：输入补充要求后重新规划
```

按 `Enter` 后开始执行：

```text
🚀 开始执行计划...

▶️ 执行任务 [task_1]: 创建 demoapp 项目结构
   🔧 调用工具: create_project
✅ 完成 [task_1]: 项目已创建: demoapp (类型: java)

▶️ 执行任务 [task_2]: 读取 demoapp/pom.xml 内容
   🔧 调用工具: read_file
✅ 完成 [task_2]: <?xml version="1.0" ...>

▶️ 执行任务 [task_3]: 验证项目结构与 Maven 配置
✅ 完成 [task_3]: 项目结构验证通过，Maven 配置正确

✅ 计划执行完成！
[task_3] 项目结构验证通过，Maven 配置正确
```

### 并发执行示例

当 DAG 中存在多个无依赖任务时，会自动并行执行：

```text
⚡ 本轮并行执行 2 个任务: task_1, task_2

▶️ 并行任务 [task_1]: 读取当前目录
   🔧 调用工具: list_dir
▶️ 并行任务 [task_2]: 检查 Java 环境
   🔧 调用工具: execute_command

✅ 完成 [task_1]: 当前目录包含 /src, /pom.xml...
✅ 完成 [task_2]: Java 17 已安装
```

---

## 命令参考

| 命令 | 说明 |
|---|---|
| `/plan` | 下一条输入使用 Plan-and-Execute 模式，执行完毕后自动回到 ReAct |
| `/plan <任务内容>` | 直接用 Plan 模式执行该任务 |
| `/clear` | 清空 ReAct 模式的对话历史 |
| `/exit` `/quit` | 退出程序 |
| `Ctrl+C` | 跳过当前输入 |
| `Ctrl+D` | 退出程序 |

### Plan 模式审查按键

计划生成后会阻塞等待用户决策：

| 按键 | 说明 |
|---|---|
| `Enter` | 按当前计划执行 |
| `Ctrl+O` | 展开完整计划视图（再次按 ESC 折叠） |
| `ESC` | 已展开时折叠；未展开时取消本次计划 |
| `I` | 输入补充要求后重新规划 |

---

## 可用工具

| 工具名 | 参数 | 用途 |
|---|---|---|
| `read_file` | `path` | 读取文件内容 |
| `write_file` | `path`, `content` | 写入文件（自动创建父目录） |
| `list_dir` | `path` | 列出目录内容 |
| `execute_command` | `command` | 通过 `bash -c` 执行 Shell 命令 |
| `create_project` | `name`, `type` | 创建 java/python/node 项目脚手架 |

工具执行失败时返回字符串 `"失败: ..."`，不抛异常打断 Agent 循环。

---

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Java | 17 | 运行时 |
| Maven | - | 构建（继承 spring-boot-starter-parent 4.1.0 仅用于依赖管理，非 Spring Boot 应用） |
| OkHttp | 4.12 | HTTP 客户端（调用 LLM API） |
| Jackson | 2.16 | JSON 序列化/反序列化 |
| JLine | 3.26 | 终端交互（raw mode、单键读取、括号粘贴） |
| slf4j-simple | 2.0 | 日志 |
| JUnit 5 | 5.10 | 测试（待后续期次引入） |

**默认 LLM**：智谱 GLM（`https://open.bigmodel.cn/api/paas/v4/chat/completions`），OpenAI 兼容协议。

---

## 项目结构

```
paicli/
├── AGENTS.md                          # AI 协作说明书
├── CLAUDE.md                          # Claude Code 专用指引
├── ROADMAP.md                         # 21 期迭代路线图
├── README.md                          # 本文件
├── pom.xml                            # Maven 构建配置
├── docs/                              # 按期次组织的设计/教程文档
│   ├── chapter1-ReAct和Tool Call实现.md
│   └── chapter2-Plan-and-Execute实现.md
└── src/main/java/com/paicli/
    ├── cli/
    │   ├── Main.java                  # CLI 入口 + REPL + JLine 终端 + 模式路由
    │   ├── CliCommandParser.java      # 命令解析（/plan /exit /clear）
    │   └── PlanReviewInputParser.java # 审查输入解析（EXECUTE/SUPPLEMENT/CANCEL）
    ├── agent/
    │   ├── Agent.java                 # ReAct 循环（思考-行动-观察）
    │   └── PlanExecuteAgent.java      # Plan-and-Execute 编排 + 计划审查
    ├── llm/
    │   └── GLMClient.java             # GLM HTTP 客户端 + Message/Tool/ToolCall record
    ├── tool/
    │   └── ToolRegistry.java          # 5 个内置工具 + JSON Schema 生成 + 工具执行
    └── plan/
        ├── Planner.java               # LLM 驱动的任务分解
        ├── ExecutionPlan.java         # 计划 DAG + 拓扑排序 + 批次计算
        └── Task.java                  # 任务节点 + 依赖/状态
```

---

## 开发路线图

项目按 21 期迭代推进，完整设计见 `ROADMAP.md`。

| 期次 | 主题 | 状态 |
|---|---|---|
| 1 | ReAct + Tool Call | ✅ 已完成 |
| 2 | Plan-and-Execute + DAG | ✅ 已完成 |
| 3 | Memory 系统 + 上下文工程 | 📋 规划中 |
| 4 | RAG 检索 + 代码库理解 | 📋 规划中 |
| 5 | Multi-Agent 协作 | 📋 规划中 |
| 6 | HITL 审批 | 🔶 部分实现（计划审查 HITL 已有，危险操作审批待补） |
| 7 | 异步并行 | 🔶 部分实现（分批并发已有，高级异步调度待补） |
| 8 | 多模型支持 | 📋 规划中 |
| 9–21 | 联网工具 / MCP / 长上下文 / TUI / LSP / Git / Prompt 分层 / 后台任务 / 图片输入 | 📋 规划中 |

---

## 文档

- [ROADMAP.md](ROADMAP.md) — 21 期迭代路线图（权威来源）
- [AGENTS.md](AGENTS.md) — AI 协作说明书，项目全景认知
- [CLAUDE.md](CLAUDE.md) — Claude Code 专用指引
- [docs/chapter1-ReAct和Tool Call实现.md](docs/chapter1-ReAct和Tool%20Call实现.md) — 第 1 期实现教程
- [docs/chapter2-Plan-and-Execute实现.md](docs/chapter2-Plan-and-Execute实现.md) — 第 2 期实现教程

---

## 设计哲学

**手写优先，框架在后**。21 期主线全部手写完成后，才会开启 Pro 分支用 Spring AI / LangGraph4J 重构做对照实现。日常开发不引入 Spring / LangChain4j 等框架抽象。

代码风格约定：
- 包结构：`com.paicli.<module>.<Class>`，一个模块一个包
- 数据模型：优先用 Java 17 `record`
- 注释：Javadoc 写在类级别解释职责，方法级别只写非显而易见的「为什么」
- 错误处理：工具执行失败返回字符串，不抛异常打断 Agent 循环
- 中文：所有面向用户的输出和教程用中文；类名/变量名用英文
