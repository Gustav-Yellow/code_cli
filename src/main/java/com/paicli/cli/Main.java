package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserMode;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.mcp.McpServer;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.McpServerStatus;
import com.paicli.mcp.mention.AtMentionExpander;
import com.paicli.policy.AuditLog;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.tool.ToolRegistry;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.rag.CodeIndex;
import com.paicli.rag.CodeRelation;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.keymap.KeyMap;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PaiCLI v14.0.0 - Session-Aware Browser Agent CLI
 * 支持 ReAct、Plan-and-Execute、Memory、RAG、Multi-Agent、HITL、并行工具调用、多模型切换、MCP
 * 第 14 期新增：CDP 会话复用、/browser 命令组、敏感页面单步审批、shared 模式 close_page 保护
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.paicli.policy
 */
public class Main {
    private static final String VERSION = "14.0.0";
    private static final String ENV_FILE = ".env";
    private static final String LOG_DIR_PROPERTY = "paicli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicli.log.totalSizeCap";

    /** 终端括号粘贴模式的前缀标记（xterm 扩展） */
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    /** 终端括号粘贴模式的后缀标记 */
    private static final String BRACKETED_PASTE_END = "\u001b[201~";

    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";

    /** Ctrl+O 的 ASCII 码，用于展开完整计划视图 */
    private static final int CTRL_O = 15;
    /** 默认 MCP 配置模板：chrome-devtools（isolated 模式，首次启动时自动创建） */
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    enum EscapeSequenceType {
        STANDALONE_ESC,
        BRACKETED_PASTE,
        CONTROL_SEQUENCE,
        OTHER
    }

    /**
     * readPromptInput 的返回值 —— text 是用户输入内容，canceled 表示用户按 ESC 取消。
     */
    private record PromptInput(String text, boolean canceled) {
        static PromptInput submitted(String text) {
            return new PromptInput(text, false);
        }

        static PromptInput canceledInput() {
            return new PromptInput("", true);
        }
    }

    /**
     * readPrefillInputFromTerminal 的返回值 —— 解析 raw mode 下第一个按键的意图。
     * seedBuffer 非空 → 用户开始输入，buffer 作为 LineReader 的预填内容；
     * canceled → 用户按了 ESC；submitted → 用户直接按了回车（空输入）。
     */
    private record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {
        static PrefillResult canceledInput() {
            return new PrefillResult("", true, false);
        }

        static PrefillResult submittedInput() {
            return new PrefillResult("", false, true);
        }

        static PrefillResult seed(String seedBuffer) {
            return new PrefillResult(seedBuffer, false, false);
        }
    }

    /**
     * readKeyFromTerminal 的返回值 —— 解析 raw mode 下第一个按键的意图。
     * @param key 按键的 ASCII 码
     * @param ignoredControlSequence 是否忽略了控制序列（如 ESC 或 Ctrl+C）
     */
    private record KeyReadResult(Integer key, boolean ignoredControlSequence) {
        static KeyReadResult keyPressed(int key) {
            return new KeyReadResult(key, false);
        }

        static KeyReadResult ignoredSequence() {
            return new KeyReadResult(null, true);
        }

        static KeyReadResult unavailable() {
            return new KeyReadResult(null, false);
        }
    }

    public static void main(String[] args) {
        printBanner();
        configureLogging();

        // 加载配置（.env + ~/.paicli/config.json）
        loadEnvConfig();
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 GLM_API_KEY 或 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        System.out.println("✅ 已加载模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")\n");

        // 初始化 JLine 终端：支持 raw mode 单键读取 + 括号粘贴
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {

            // 会话级共享上下文：ReAct 与 Plan 共享同一份对话历史与长期记忆
            MemoryManager sharedMemory = new MemoryManager(llmClient);
            List<LlmClient.Message> sharedHistory = new ArrayList<>();

            // 创建 HITL 处理器（默认关闭）
            TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
            BrowserSession browserSession = new BrowserSession();
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));

            // 初始化 MCP 子系统
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            // BrowserConnector 必须在 mcpServerManager 之后创建（lambda 引用了 mcpServerManager）
            hitlToolRegistry.setBrowserConnector(new com.paicli.browser.BrowserConnector() {
                @Override
                public String status() {
                    return handleBrowserCommand("status", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String connectDefault() {
                    return handleBrowserCommand("connect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String disconnect() {
                    return handleBrowserCommand("disconnect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }
            });

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new PaiCliCompleter(mcpServerManager::resourceCandidates))
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureSlashCommandHint(lineReader);
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    System.out.println(bootstrapResult.message());
                }
                mcpServerManager.loadConfiguredServers();
                if (!mcpServerManager.servers().isEmpty()) {
                    System.out.println("🔌 启动 MCP server（" + mcpServerManager.servers().size() + " 个）...");
                }
                mcpServerManager.startAll(System.out);
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "paicli-mcp-shutdown"));
                System.out.println(mcpServerManager.startupSummary());
                System.out.println();
            } catch (Exception e) {
                System.out.println("⚠️ MCP 初始化失败: " + e.getMessage());
                System.out.println("   可检查 ~/.paicli/mcp.json 或 .paicli/mcp.json\n");
            }

            // 默认使用 ReAct 模式，注入 HITL 审批
            Agent reactAgent = new Agent(llmClient, sharedHistory, sharedMemory, hitlToolRegistry);
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            System.out.println("🔄 使用 ReAct 模式\n");
            // nextTaskUsePlanMode：/plan 命令设置此标记，下一条输入走 Plan 模式
            boolean nextTaskUsePlanMode = false;
            // nextTaskUseTeamMode：/team 命令设置此标记，下一条输入走 Team 模式
            boolean nextTaskUseTeamMode = false;

            printStartupHints();

            System.out.println("💡 提示:");
            System.out.println("   - 输入你的问题或任务");
            System.out.println("   - 输入 '/' 查看命令");
            System.out.println("   - 输入 '@server:protocol://path' 引用 MCP resource");
            System.out.println("   - 任务运行中按 ESC 取消当前任务");
            System.out.println("   - 默认模式是 ReAct\n");

            while (true) {
                PromptInput promptInput;
                try {
                    promptInput = readPromptInput(terminal, lineReader, nextTaskUsePlanMode);
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 跳过
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D 退出
                }

                if (promptInput.canceled()) {
                    if (nextTaskUsePlanMode) {
                        nextTaskUsePlanMode = false;
                        System.out.println("↩️ 已取消待执行的 Plan-and-Execute，回到默认 ReAct。\n");
                    }
                    if (nextTaskUseTeamMode) {
                        nextTaskUseTeamMode = false;
                        System.out.println("↩️ 已取消待执行的 Multi-Agent，回到默认 ReAct。\n");
                    }
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                // 解析 CLI 命令
                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                switch (command.type()) {
                    case UNKNOWN_COMMAND -> {
                        System.out.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp();
                        continue;
                    }
                    case EXIT -> {
                        System.out.println("\n👋 再见!");
                        return;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        hitlHandler.clearApprovedAll();
                        System.out.println("🗑️ 对话历史已清空，关键事实已保存到长期记忆\n");
                        continue;
                    }
                    case CONTEXT_STATUS -> {
                        System.out.println("📋 上下文状态：");
                        System.out.println(reactAgent.getContextStatus());
                        System.out.println();
                        continue;
                    }
                    case POLICY_STATUS -> {
                        printPolicyStatus(reactAgent);
                        continue;
                    }
                    case AUDIT_TAIL -> {
                        printAuditTail(reactAgent, command.payload());
                        continue;
                    }
                    case MCP_LIST -> {
                        System.out.println(mcpServerManager.formatStatus());
                        System.out.println();
                        continue;
                    }
                    case MCP_RESTART -> {
                        printMcpCommandResult(mcpServerManager.restart(command.payload()));
                        continue;
                    }
                    case MCP_LOGS -> {
                        printMcpCommandResult(mcpServerManager.logs(command.payload()));
                        continue;
                    }
                    case MCP_DISABLE -> {
                        printMcpCommandResult(mcpServerManager.disable(command.payload()));
                        continue;
                    }
                    case MCP_ENABLE -> {
                        printMcpCommandResult(mcpServerManager.enable(command.payload()));
                        continue;
                    }
                    case MCP_RESOURCES -> {
                        printMcpCommandResult(mcpServerManager.resources(command.payload()));
                        continue;
                    }
                    case MCP_PROMPTS -> {
                        printMcpCommandResult(mcpServerManager.prompts(command.payload()));
                        continue;
                    }
                    case BROWSER -> {
                        printMcpCommandResult(handleBrowserCommand(
                                command.payload(),
                                browserSession,
                                browserConnectivityCheck,
                                mcpServerManager,
                                hitlToolRegistry,
                                hitlHandler));
                        continue;
                    }
                    case CANCEL -> {
                        System.out.println("⚠️ 当前没有运行中的 Agent 任务可取消。任务执行期间输入 /cancel 可请求取消。\n");
                        continue;
                    }
                    case SWITCH_MODEL -> {
                        String provider = command.payload();
                        if (provider == null || provider.isEmpty()) {
                            System.out.println("🤖 当前模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                            System.out.println("   可用模型：glm, deepseek");
                            System.out.println("   /model glm     - 切换到 GLM-4.7");
                            System.out.println("   /model deepseek - 切换到 DeepSeek V4\n");
                        } else {
                            LlmClient newClient = LlmClientFactory.create(provider, config);
                            if (newClient == null) {
                                System.out.println("❌ 切换失败：未配置 " + provider + " 的 API Key\n");
                            } else {
                                llmClient = newClient;
                                config.setDefaultProvider(provider);
                                config.save();
                                reactAgent.setLlmClient(llmClient);
                                System.out.println("✅ 已切换到: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                                System.out.println("   对话上下文已保留，使用 /clear 可清空\n");
                            }
                        }
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        System.out.println("📋 记忆系统状态：");
                        System.out.println(reactAgent.getMemoryManager().getSystemStatus(sharedHistory));
                        System.out.println("   /memory clear - 清空长期记忆");
                        System.out.println("   /save <事实> - 手动保存到长期记忆");
                        System.out.println();
                        continue;
                    }
                    case MEMORY_CLEAR -> {
                        reactAgent.getMemoryManager().clearLongTerm();
                        System.out.println("🧹 长期记忆已清空\n");
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        String fact = command.payload();
                        // 如果 /save 后没有内容，则打印提示
                        if (fact == null || fact.isEmpty()) {
                            System.out.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17\n");
                        } else {
                            reactAgent.getMemoryManager().storeFact(fact);
                            System.out.println("💾 已保存到长期记忆: " + fact + "\n");
                        }
                        continue;
                    }
                    case SWITCH_TEAM -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUseTeamMode = true;
                            System.out.println("👥 下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者），输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_PLAN -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            System.out.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_HITL -> {
                        String payload = command.payload();
                        if ("on".equals(payload)) {
                            hitlHandler.setEnabled(true);
                            System.out.println("🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认\n");
                        } else if ("off".equals(payload)) {
                            hitlHandler.setEnabled(false);
                            hitlHandler.clearApprovedAll();
                            System.out.println("🔓 HITL 审批已关闭：危险操作将直接执行\n");
                        } else {
                            String status = hitlHandler.isEnabled() ? "启用" : "关闭";
                            System.out.println("🔒 HITL 当前状态：" + status);
                            System.out.println("   /hitl on  - 启用人工审批");
                            System.out.println("   /hitl off - 关闭人工审批\n");
                        }
                        continue;
                    }
                    case INDEX_CODE -> {
                        String indexPath = command.payload() != null ? command.payload() : ".";
                        System.out.println("📦 正在索引代码库: " + indexPath);
                        CodeIndex indexer = new CodeIndex(System.out::println);
                        CodeIndex.IndexResult result = indexer.index(indexPath);
                        System.out.println(result.message() + "\n");

                        // 同步项目路径到 ToolRegistry，让 search_code 工具可以正常工作
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);
                        continue;
                    }
                    case SEARCH_CODE -> {
                        String query = command.payload();
                        if (query == null || query.isEmpty()) {
                            showSearchUsage();
                            continue;
                        }
                        System.out.println("🔍 检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<com.paicli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
                            if (results.isEmpty()) {
                                System.out.println("📭 未找到相关代码\n");
                            } else {
                                System.out.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                            }
                        } catch (Exception e) {
                            System.out.println("❌ 检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {
                        String className = command.payload();
                        if (className == null || className.isEmpty()) {
                            System.out.println("❌ 请提供类名，例如 /graph Main\n");
                            continue;
                        }
                        System.out.println("🕸️ 查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<CodeRelation> relations = retriever.getRelationGraph(className);
                            if (relations.isEmpty()) {
                                System.out.println("📭 未找到相关关系\n");
                            } else {
                                System.out.println("📋 找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                            : rel.relationType().equals("implements") ? "└── implements -->"
                                            : rel.relationType().equals("calls") ? "├── calls -->"
                                            : "├── " + rel.relationType() + " -->";
                                    System.out.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                System.out.println();
                            }
                        } catch (Exception e) {
                            System.out.println("❌ 查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case NONE -> {
                    }
                }

                // ── 模式路由：PlanExecuteAgent（带审查） vs Agent（ReAct） ──
                System.out.println();
                String response;
                if (nextTaskUsePlanMode || command.type() == CliCommandParser.CommandType.SWITCH_PLAN) {
                    PlanExecuteAgent planAgent = createPlanAgent(llmClient, terminal, lineReader,
                            sharedHistory, sharedMemory, hitlToolRegistry);
                    response = planAgent.run(input);
                    nextTaskUsePlanMode = false;  // 执行完毕后回到 ReAct
                 } else if (nextTaskUseTeamMode || command.type() == CliCommandParser.CommandType.SWITCH_TEAM) {
                    AgentOrchestrator orchestrator = createTeamAgent(llmClient, reactAgent, sharedHistory);
                    response = orchestrator.run(input);
                    nextTaskUseTeamMode = false;
                } else {
                    response = reactAgent.run(input);
                }

                if (response != null && !response.isEmpty()) {
                    System.out.println("🤖 Agent: " + response);
                    System.out.println();
                }

            }

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("\n👋 再见!");
    }

    /**
     * 创建带交互式审查的 PlanExecuteAgent —— 注入 PlanReviewHandler，
     * 让用户在计划生成后能预览、补充要求、取消或直接执行。
     */
    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Terminal terminal, LineReader lineReader,
                                                    List<LlmClient.Message> sharedHistory, MemoryManager sharedMemory) {
        return createPlanAgent(llmClient, terminal, lineReader, sharedHistory, sharedMemory, new ToolRegistry());
    }

    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Terminal terminal, LineReader lineReader,
                                                    List<LlmClient.Message> sharedHistory, MemoryManager sharedMemory,
                                                    ToolRegistry toolRegistry) {
        System.out.println("📋 使用 Plan-and-Execute 模式\n");
        return new PlanExecuteAgent(llmClient, toolRegistry,
                createPlanReviewHandler(terminal, lineReader), sharedHistory, sharedMemory);
    }

    /**
     * 创建 Multi-Agent 协作模式的 AgentOrchestrator —— 注入 PlanReviewHandler，
     * 让用户在计划生成后能预览、补充要求、取消或直接执行。
     * @param apiKey 密钥
     * @param reactAgent ReAct 模式的 Agent
     * @return AgentOrchestrator 调试器
     */
    private static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent,
                                                      List<LlmClient.Message> sharedHistory) {
        System.out.println("👥 使用 Multi-Agent 协作模式\n");
        // 复用 reactAgent 的 ToolRegistry、MemoryManager 和会话共享历史：
        // - ToolRegistry 共享意味着 /index 设置的项目路径同步到 Multi-Agent
        // - MemoryManager 共享避免重复加载长期记忆
        // - sharedHistory 让 /team 执行结果写回，切回 ReAct 时上下文连续
        return new AgentOrchestrator(llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                sharedHistory);
    }

    /**
     * 读取一行用户输入，支持 ESC 取消和预填 buffer。
     *
     * <h3>allowEscCancel = false（ReAct 模式）</h3>
     * 直接用 JLine readLine()，正常的行编辑体验。
     *
     * <h3>allowEscCancel = true（等待 Plan 模式输入时）</h3>
     * 先进入 raw mode 读第一个字符判断意图：
     * <ul>
     *   <li>ESC → 取消 Plan 模式，回到 ReAct</li>
     *   <li>Enter → 空输入</li>
     *   <li>其他字符 → 作为 seed buffer 传给 JLine，支持粘贴多行文本</li>
     * </ul>
     */
    private static PromptInput readPromptInput(Terminal terminal, LineReader lineReader, boolean allowEscCancel)
            throws UserInterruptException, EndOfFileException {
        if (!allowEscCancel) {
            return PromptInput.submitted(lineReader.readLine("👤 你: "));
        }

        String prompt = "👤 你: ";
        System.out.print(prompt);
        System.out.flush();

        PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
        if (prefill == null) {
            return PromptInput.submitted(lineReader.readLine(""));
        }

        if (prefill.canceled()) {
            System.out.println();
            return PromptInput.canceledInput();
        }

        if (prefill.submitted()) {
            System.out.println();
            return PromptInput.submitted("");
        }

        // 用户已开始输入 → 将预读到的字符作为 JLine 的 seed buffer
        return PromptInput.submitted(lineReader.readLine("", null, (MaskingCallback) null, prefill.seedBuffer()));
    }

    /**
     * 创建计划审查的交互式 handler —— 注入到 PlanExecuteAgent 中。
     *
     * <h3>交互设计</h3>
     * 计划生成后阻塞等待用户决策，四种操作：
     * <ul>
     *   <li>Enter → 直接执行当前计划</li>
     *   <li>Ctrl+O → 展开完整计划视图（visualize）</li>
     *   <li>I → 输入补充要求，解析后可能 SUPPLEMENT 或 CANCEL</li>
     *   <li>ESC → 双重语义：已展开时折叠回摘要；未展开时取消本次计划</li>
     * </ul>
     *
     * <h3>ESC 双重语义的实现</h3>
     * 用 {@code expanded} 布尔值区分：expanded=true 时 ESC 只是折叠，
     * expanded=false 时 ESC 才真正取消。这样用户可以先展开查看详情再决定。
     *
     * <h3>兜底：行输入模式</h3>
     * 如果 readSingleKeyFromTerminal 返回 null（无法进入 raw mode），
     * 回退到 JLine readLine 行输入，支持 /view、空输入（执行）、/cancel 等命令。
     */
    private static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal, LineReader lineReader) {
        return (String goal, ExecutionPlan plan) -> {
            boolean expanded = false;
            System.out.println(plan.summarize());
            System.out.println("📝 计划已生成。");
            System.out.println("   - 回车：按当前计划执行");
            System.out.println("   - Ctrl+O：展开完整计划");
            System.out.println("   - ESC：折叠或取消本次计划");
            System.out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }
                Integer key = keyReadResult.key();

                if (key != null) {
                    // Enter (13 或 10)
                    if (key == '\n' || key == '\r') {
                        System.out.println();
                        return PlanExecuteAgent.PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        System.out.println();
                        if (expanded) {
                            expanded = false;
                            System.out.println(plan.summarize());
                            System.out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanExecuteAgent.PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        System.out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        PlanReviewInputParser.Decision supplementDecision =
                                PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        System.out.println();
                        System.out.println(plan.visualize());
                        expanded = true;
                        System.out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    System.out.println();
                    System.out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    System.out.println();
                    System.out.println(plan.visualize());
                    expanded = true;
                    System.out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE
                            || escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal, lineReader);
                }

                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 处理 ESC 之后的输入：区分"纯取消"和"括号粘贴"。
     *
     * <h3>括号粘贴检测</h3>
     * 现代终端在粘贴多行文本时会在内容前后包裹 {@code \e[200~} ... {@code \e[201~}。
     * 粘贴操作以 ESC 开头，所以先读到 ESC 后需要检查后续字节是否匹配粘贴前缀。
     * 如果匹配 → 循环读取直到遇到粘贴后缀 → 提取中间文本作为 seed buffer。
     * 如果不匹配 → 纯 ESC → 用户取消。
     */
    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader) throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 80, 20, 300);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
            String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
            while (!pastedText.contains(BRACKETED_PASTE_END)) {
                String burst = readInputBurst(terminal, 30, 25, 500);
                if (burst.isEmpty()) {
                    break;
                }
                pastedText += burst;
            }

            return PrefillResult.seed(prepareSeedBuffer(stripBracketedPasteEndMarker(pastedText)));
        }

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        return PrefillResult.canceledInput();
    }

    /**
     * 在 raw mode 下批量读取连续到达的字节，用于捕获粘贴或快速输入。
     *
     * <h3>三阶段超时策略</h3>
     * <ul>
     *   <li>firstWaitMs：首个字节到达前的等待窗口。buffer 为空时用这个超时，
     *       确保有足够时间等待粘贴的第一个字节到达</li>
     *   <li>idleWaitMs：字节间的空闲超时。每读到一个字节就重置 idleDeadline，
     *       连续有数据到达就一直读，间隔超过 idleWaitMs 则认为输入结束</li>
     *   <li>maxWaitMs：整体最大等待时间，防止无限阻塞</li>
     * </ul>
     *
     * <h3>为什么不用 read() 阻塞等？</h3>
     * 终端输入没有 EOF 标记，无法知道"用户打完了没"。
     * 用轮询 + 空闲超时是实时终端处理粘贴的标准做法。
     */
    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }

        return buffer.toString();
    }

    /**
     * 将 raw mode 读取的原始文本规范化，准备作为 JLine seed buffer。
     * 主要处理 \r\n → \n 的转换，因为终端粘贴可能混入不同的换行符。
     */
    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 查看命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "任务运行中按 ESC 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model glm", "/model glm", "切换到 GLM-5.1"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek V4"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan-and-Execute 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用计划模式执行这条任务"),
                new SlashCommandHint("/team", "/team", "下一条任务使用 Multi-Agent 协作模式"),
                new SlashCommandHint("/team ", "/team <任务内容>", "直接用多 Agent 协作执行这条任务"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new SlashCommandHint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save <事实内容>", "手动保存关键事实到长期记忆"),
                new SlashCommandHint("/exit", "/exit", "退出 PaiCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 PaiCLI")
        );
    }

    private static void printSlashCommandHelp() {
        System.out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            System.out.println("   " + hint.display() + " - " + hint.description());
        }
        System.out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-slash-command-hint", () -> {
            boolean atPromptStart = lineReader.getBuffer().length() == 0;
            lineReader.getBuffer().write("/");
            if (atPromptStart) {
                lineReader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        Reference slashHint = new Reference("paicli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       BrowserConnectivityCheck connectivityCheck,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       TerminalHitlHandler hitlHandler) {
        String normalized = payload == null || payload.isBlank() ? "status" : payload.trim();
        String[] parts = normalized.split("\\s+");
        String subCommand = parts[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> browserStatus(browserSession, connectivityCheck, mcpServerManager);
            case "connect" -> {
                if (parts.length >= 2) {
                    int port = parseBrowserPort(parts[1]);
                    yield browserConnectByPort(port, browserSession, connectivityCheck, mcpServerManager, hitlHandler);
                }
                yield browserAutoConnect(browserSession, mcpServerManager, hitlHandler);
            }
            case "disconnect" -> browserDisconnect(browserSession, mcpServerManager, hitlHandler);
            case "tabs" -> browserTabs(browserSession, registry);
            default -> """
                    ❌ 未知 /browser 子命令: %s
                    可用命令：
                      /browser status
                      /browser connect [port]
                      /browser disconnect
                      /browser tabs
                    """.formatted(normalized).trim();
        };
    }

    private static String browserStatus(BrowserSession browserSession,
                                        BrowserConnectivityCheck connectivityCheck,
                                        McpServerManager mcpServerManager) {
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(9222);
        McpServer server = mcpServerManager.server("chrome-devtools");
        String serverStatus = server == null
                ? "未配置"
                : server.status() == McpServerStatus.READY
                ? "● ready (" + server.tools().size() + " tools)"
                : server.status().name().toLowerCase() + (server.errorMessage() == null ? "" : " - " + server.errorMessage());
        String mode = browserSession.mode() == BrowserMode.SHARED
                ? "shared（复用 " + browserSession.browserUrl() + "）"
                : "isolated（临时 user-data-dir，无登录态）";
        return """
                🌐 浏览器会话
                  当前模式: %s
                  chrome-devtools server: %s
                  旧式 /json/version 探活: %s
                  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                """.formatted(mode, serverStatus, probe.ok() ? "✅ " + probe.browserUrl() : "⚠️ " + probe.message()).trim();
    }

    private static String browserAutoConnect(BrowserSession browserSession,
                                             McpServerManager mcpServerManager,
                                             TerminalHitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
        String result = mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared("autoConnect");
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：\n" + result
                + "\n\n请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。";
    }

    private static String browserConnectByPort(int port,
                                               BrowserSession browserSession,
                                               BrowserConnectivityCheck connectivityCheck,
                                               McpServerManager mcpServerManager,
                                               TerminalHitlHandler hitlHandler) {
        if (port < 1024 || port > 65535) {
            return "❌ /browser connect 端口必须在 1024-65535 之间。默认 /browser connect 使用 --autoConnect；旧式 CDP 端口连接可用 /browser connect 9222。";
        }
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 未检测到 Chrome 调试端口 127.0.0.1:" + port + "：" + probe.message() + "\n\n"
                    + chromeLaunchHelp(port);
        }

        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> sharedArgs = List.of("-y", "chrome-devtools-mcp@latest", "--browser-url=" + probe.browserUrl());
        String result = mcpServerManager.restartWithArgs("chrome-devtools", sharedArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared(probe.browserUrl());
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 切换 chrome-devtools server 到 shared 模式 (" + probe.browserUrl() + ")\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ shared 模式切换失败，已回滚 chrome-devtools 启动参数：\n" + result;
    }

    private static String browserDisconnect(BrowserSession browserSession,
                                            McpServerManager mcpServerManager,
                                            TerminalHitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            browserSession.switchToIsolated();
            return "❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态";
        }
        String result = mcpServerManager.restartWithArgs(
                "chrome-devtools",
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
        browserSession.switchToIsolated();
        hitlHandler.clearApprovedAllForServer("chrome-devtools");
        return "🔄 已切回 isolated 浏览器模式\n" + result;
    }

    private static String browserTabs(BrowserSession browserSession, HitlToolRegistry registry) {
        if (browserSession.mode() != BrowserMode.SHARED) {
            return "当前为 isolated 模式，没有真实 Chrome tab 可复用。可用 /browser connect 切到 shared 模式。";
        }
        return registry.executeTool("mcp__chrome-devtools__list_pages", "{}");
    }

    private static int parseBrowserPort(String value) {
        if (value == null || value.isBlank()) {
            return 9222;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeLaunchHelp(int port) {
        return """
                请先用调试端口启动 Chrome：
                  macOS: open -na "Google Chrome" --args --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                  Windows: start chrome.exe --remote-debugging-port=%d --user-data-dir=%%TEMP%%\\paicli-chrome-profile
                  Linux: google-chrome --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                然后重新执行 /browser connect %d
                """.formatted(port, port, port, port).trim();
    }

    private static void printPolicyStatus(Agent reactAgent) {
        System.out.println("🛡️ 安全策略状态：");
        System.out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        System.out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        System.out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        System.out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        System.out.println("   写入文件上限: 5MB");
        System.out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        System.out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        System.out.println();
    }

    private static void printMcpCommandResult(String result) {
        System.out.println(result);
        System.out.println();
    }

    private static void printAuditTail(Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            System.out.println("📭 今日尚无审计记录\n");
            return;
        }
        System.out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            System.out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                System.out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                System.out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        System.out.println();
    }

    private static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    private static void printStartupHints() {
        System.out.println("💡 提示:");
        for (String hint : startupHints()) {
            System.out.println("   - " + hint);
        }
        System.out.println();
    }

    /**
     * /search 无参数时的兜底展示：先显示索引状态，再给用法示例。
     *
     * <h3>三层策略</h3>
     * <ol>
     *   <li>已索引且数据正常 → 展示统计（代码块数 / 关系数）+ 用法示例</li>
     *   <li>未索引 → 提示先运行 /index</li>
     *   <li>查询统计异常 → 降级为简单用法提示，不阻塞用户</li>
     * </ol>
     */
    private static void showSearchUsage() {
        try (CodeRetriever retriever = new CodeRetriever(".")) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                System.out.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
            } else {
                System.out.println("📊 当前索引: " + stats.chunkCount() + " 个代码块, "
                        + stats.relationCount() + " 条关系");
                System.out.println("💡 用法: /search <关键词或自然语言描述>");
                System.out.println("   例如: /search 用户登录实现");
                System.out.println("   例如: /search Agent 类的工具调用逻辑\n");
            }
        } catch (Exception e) {
            // 降级兜底：连不上数据库时仍给出基本提示
            System.out.println("💡 用法: /search <关键词或自然语言描述>");
            System.out.println("   例如: /search 用户登录实现\n");
        }
    }

    /** 统一换行符：\r\n 和单独的 \r 都转为 \n，保证跨平台一致性。 */
    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    /** 去除括号粘贴的后缀标记 {@code \e[201~}，只保留用户实际粘贴的文本。 */
    private static String stripBracketedPasteEndMarker(String rawInput) {
        int endMarkerIndex = rawInput.indexOf(BRACKETED_PASTE_END);
        if (endMarkerIndex >= 0) {
            return rawInput.substring(0, endMarkerIndex);
        }
        return rawInput;
    }

    /** 判断按键是否为回车（\n / \r），兼容不同终端的回车表示。 */
    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    static EscapeSequenceType classifyEscapeSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return EscapeSequenceType.STANDALONE_ESC;
        }
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            return EscapeSequenceType.BRACKETED_PASTE;
        }
        if (sequence.startsWith("[") || sequence.startsWith("O")) {
            return EscapeSequenceType.CONTROL_SEQUENCE;
        }
        return EscapeSequenceType.OTHER;
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    /**
     * 将 PlanReviewInputParser 的解析结果映射为 PlanExecuteAgent 的审查决策类型。
     * 两个类型语义一一对应：EXECUTE/SUPPLEMENT/CANCEL。
     */
    private static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
    }

    /**
     * 从 .env 文件加载所有 KEY=VALUE 到 {@link System#setProperty(String, String)}，
     * 使后续代码通过 {@code System.getProperty(key)} 即可读取配置。
     *
     * <h3>加载顺序（与 POSIX .env 语义一致：先到先得）</h3>
     * <ol>
     *   <li>当前目录 {@code .env}</li>
     *   <li>用户主目录 {@code ~/.env}</li>
     *   <li>OS 环境变量（已在 System.getenv 中，无需 setProperty）</li>
     * </ol>
     *
     * @return GLM_API_KEY 的值，找不到返回 null
     */
    private static String loadEnvConfig() {
        // 步骤1：从 .env 文件加载所有变量到 System properties
        boolean loadedFromFile = false;
        for (File envFile : new File[]{new File(ENV_FILE),
                new File(System.getProperty("user.home"), ENV_FILE)}) {
            if (envFile.exists()) {
                loadDotEnvFile(envFile);
                loadedFromFile = true;
                break;  // 只加载第一个找到的 .env
            }
        }

        if (loadedFromFile) {
            // 优先从 System property 取（刚写入的），其次从环境变量取
            String apiKey = System.getProperty("GLM_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                return apiKey;
            }
        }

        // 步骤2：兜底 — 真正的 OS 环境变量
        return System.getenv("GLM_API_KEY");
    }

    /**
     * 逐行解析 .env 文件，将每个 {@code KEY=VALUE} 写入 System.setProperty。
     * 忽略空行和 {@code #} 开头的注释行。
     */
    private static void loadDotEnvFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    // 如果已通过更高优先级来源设置过，不覆盖
                    if (System.getProperty(key) == null) {
                        System.setProperty(key, value);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
    }

    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".paicli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║");
        System.out.println("║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║");
        System.out.println("║   ██████╔╝███████║██║██║     ██║     ██║                ║");
        System.out.println("║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║");
        System.out.println("║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║");
        System.out.println("║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║");
        System.out.println("║                                                          ║");
        System.out.printf("║      Session-Aware Browser Agent CLI %-17s║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 首次启动时自动创建默认 MCP 配置（包含 chrome-devtools）。
     * 已有配置但未含 chrome-devtools 时打印提示。
     */
    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".paicli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.paicli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }
}
