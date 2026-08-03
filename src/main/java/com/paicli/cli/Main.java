package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.llm.GLMClient;
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
 * PaiCLI v7.0.0 - Async Tool CLI
 * 支持 ReAct、Plan-and-Execute、Memory、RAG、Multi-Agent、HITL 与并行工具调用能力
 */
public class Main {
    private static final String VERSION = "7.0.0";
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

        // 加载 .env 全部配置到 System properties，返回 GLM_API_KEY
        String apiKey = loadEnvConfig();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 GLM_API_KEY");
            System.err.println("请在 .env 文件中添加: GLM_API_KEY=your_api_key_here");
            System.exit(1);
        }

        System.out.println("✅ API Key 已加载\n");

        // 初始化 JLine 终端：支持 raw mode 单键读取 + 括号粘贴
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);

            // 会话级共享上下文：ReAct 与 Plan 共享同一份对话历史与长期记忆
            MemoryManager sharedMemory = new MemoryManager(new GLMClient(apiKey));
            List<GLMClient.Message> sharedHistory = new ArrayList<>();

            // 创建 HITL 处理器（默认关闭）
            TerminalHitlHandler hitlHandler = new TerminalHitlHandler(false);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);

            // 默认使用 ReAct 模式，注入 HITL 审批
            Agent reactAgent = new Agent(apiKey, sharedHistory, sharedMemory, hitlToolRegistry);
            System.out.println("🔄 使用 ReAct 模式\n");
            // nextTaskUsePlanMode：/plan 命令设置此标记，下一条输入走 Plan 模式
            boolean nextTaskUsePlanMode = false;
            // nextTaskUseTeamMode：/team 命令设置此标记，下一条输入走 Team 模式
            boolean nextTaskUseTeamMode = false;

            printStartupHints();

            System.out.println("💡 提示:");
            System.out.println("   - 输入你的问题或任务");
            System.out.println("   - 输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式");
            System.out.println("   - 输入 '/plan 任务内容' 直接用计划模式执行这条任务");
            System.out.println("   - 计划生成后可直接执行、补充要求重规划，或取消");
            System.out.println("   - 默认模式是 ReAct");
            System.out.println("   - 输入 '/hitl on' 启用危险操作人工审批");
            System.out.println("   - 输入 '/hitl off' 关闭 HITL 审批");
            System.out.println("   - 输入 '/memory' 查看记忆状态");
            System.out.println("   - 输入 '/memory clear' 清空长期记忆");
            System.out.println("   - 输入 '/save 事实内容' 手动保存关键事实");
            System.out.println("   - 输入 '/clear' 清空对话历史");
            System.out.println("   - 输入 '/exit' 或 '/quit' 退出\n");

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
                        System.out.println("可用命令：/plan /team /hitl /clear /memory /memory clear /save /index /search /graph /exit\n");
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
                    PlanExecuteAgent planAgent = createPlanAgent(apiKey, terminal, lineReader,
                            sharedHistory, sharedMemory, hitlToolRegistry);
                    response = planAgent.run(input);
                    nextTaskUsePlanMode = false;  // 执行完毕后回到 ReAct
                 } else if (nextTaskUseTeamMode || command.type() == CliCommandParser.CommandType.SWITCH_TEAM) {
                    AgentOrchestrator orchestrator = createTeamAgent(apiKey, reactAgent, sharedHistory);
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
    private static PlanExecuteAgent createPlanAgent(String apiKey, Terminal terminal, LineReader lineReader,
                                                    List<GLMClient.Message> sharedHistory, MemoryManager sharedMemory) {
        return createPlanAgent(apiKey, terminal, lineReader, sharedHistory, sharedMemory, new ToolRegistry());
    }

    private static PlanExecuteAgent createPlanAgent(String apiKey, Terminal terminal, LineReader lineReader,
                                                    List<GLMClient.Message> sharedHistory, MemoryManager sharedMemory,
                                                    ToolRegistry toolRegistry) {
        System.out.println("📋 使用 Plan-and-Execute 模式\n");
        return new PlanExecuteAgent(apiKey, toolRegistry,
                createPlanReviewHandler(terminal, lineReader), sharedHistory, sharedMemory);
    }

    /**
     * 创建 Multi-Agent 协作模式的 AgentOrchestrator —— 注入 PlanReviewHandler，
     * 让用户在计划生成后能预览、补充要求、取消或直接执行。
     * @param apiKey 密钥
     * @param reactAgent ReAct 模式的 Agent
     * @return AgentOrchestrator 调试器
     */
    private static AgentOrchestrator createTeamAgent(String apiKey, Agent reactAgent,
                                                      List<GLMClient.Message> sharedHistory) {
        System.out.println("👥 使用 Multi-Agent 协作模式\n");
        // 复用 reactAgent 的 ToolRegistry、MemoryManager 和会话共享历史：
        // - ToolRegistry 共享意味着 /index 设置的项目路径同步到 Multi-Agent
        // - MemoryManager 共享避免重复加载长期记忆
        // - sharedHistory 让 /team 执行结果写回，切回 ReAct 时上下文连续
        return new AgentOrchestrator(apiKey,
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
                "输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式",
                "输入 '/plan 任务内容' 直接用计划模式执行这条任务",
                "计划生成后可直接执行、补充要求重规划，或取消",
                "输入 '/index [路径]' 为代码库建立向量索引",
                "输入 '/search <查询>' 语义检索代码",
                "输入 '/graph <类名>' 查看代码关系图谱",
                "输入 '/team' 后，下一条任务使用 Multi-Agent 协作模式",
                "输入 '/team 任务内容' 直接用多 Agent 协作执行这条任务",
                "默认模式是 ReAct",
                "输入 '/hitl on' 启用危险操作人工审批（HITL）",
                "输入 '/hitl off' 关闭 HITL 审批",
                "输入 '/clear' 清空对话历史",
                "输入 '/memory' 查看记忆状态",
                "输入 '/memory clear' 清空长期记忆",
                "输入 '/save 事实内容' 手动保存关键事实",
                "输入 '/exit' 或 '/quit' 退出"
        );
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
        System.out.printf("║      Async Tool CLI %-37s║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
