package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.llm.GLMClient;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.rag.CodeIndex;
import com.paicli.rag.CodeRelation;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PaiCLI v4.0 - RAG-Enhanced Agent CLI
 * 支持 ReAct、Plan-and-Execute、Memory 与 RAG 能力
 */
public class Main {
    private static final String VERSION = "4.0.0";
    private static final String ENV_FILE = ".env";

    /** 终端括号粘贴模式的前缀标记（xterm 扩展） */
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    /** 终端括号粘贴模式的后缀标记 */
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    /** Ctrl+O 的 ASCII 码，用于展开完整计划视图 */
    private static final int CTRL_O = 15;

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

    public static void main(String[] args) {
        printBanner();

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

            // 默认使用 ReAct 模式
            Agent reactAgent = new Agent(apiKey, sharedHistory, sharedMemory);
            System.out.println("🔄 使用 ReAct 模式\n");
            // nextTaskUsePlanMode：/plan 命令设置此标记，下一条输入走 Plan 模式
            boolean nextTaskUsePlanMode = false;

            printStartupHints();

            System.out.println("💡 提示:");
            System.out.println("   - 输入你的问题或任务");
            System.out.println("   - 输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式");
            System.out.println("   - 输入 '/plan 任务内容' 直接用计划模式执行这条任务");
            System.out.println("   - 计划生成后可直接执行、补充要求重规划，或取消");
            System.out.println("   - 默认模式是 ReAct");
            System.out.println("   - 输入 '/memory' 查看记忆状态");
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
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                // 解析 CLI 命令
                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                switch (command.type()) {
                    case EXIT -> {
                        System.out.println("\n👋 再见!");
                        return;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        System.out.println("🗑️ 对话历史已清空，关键事实已保存到长期记忆\n");
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        System.out.println("📋 记忆系统状态：");
                        System.out.println(reactAgent.getMemoryManager().getSystemStatus(sharedHistory));
                        System.out.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        String fact = command.payload();
                        if (fact != null && !fact.isEmpty()) {
                            reactAgent.getMemoryManager().storeFact(fact);
                            System.out.println("💾 已保存到长期记忆: " + fact + "\n");
                        }
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            System.out.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
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
                    PlanExecuteAgent planAgent = createPlanAgent(apiKey, terminal, lineReader, sharedHistory, sharedMemory);
                    response = planAgent.run(input);
                    nextTaskUsePlanMode = false;  // 执行完毕后回到 ReAct
                } else {
                    response = reactAgent.run(input);
                }
                System.out.println("🤖 Agent: " + response);
                System.out.println();
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
        System.out.println("📋 使用 Plan-and-Execute 模式\n");
        return new PlanExecuteAgent(apiKey, createPlanReviewHandler(terminal, lineReader), sharedHistory, sharedMemory);
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

        PrefillResult prefill = readPrefillInputFromTerminal(terminal);
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
                Integer key = readSingleKeyFromTerminal(terminal);
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

    /**
     * 进入 raw mode 读取单个按键，读取后恢复终端属性。
     *
     * <h3>为什么读完后 drain ESC 序列？</h3>
     * 终端的方向键、功能键以 ESC (27) 开头后跟 [A/[B/[C/[D 等字节序列。
     * 如果用户误按方向键，只读到 ESC 而后续字节残留在输入缓冲区，
     * 下次 read 会读到脏数据。drain 确保缓冲区干净。
     *
     * @return 按键的 ASCII/Unicode 码点，读取失败返回 null
     */
    private static Integer readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                // 如果是 ESC，需要 drain 掉后续的方向键序列字节
                if (key == 27) {
                    drainEscapeSequence(terminal);
                }

                return key;
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在 raw mode 下读取第一个字符，判断用户意图。
     *
     * <h3>三种分支</h3>
     * <ul>
     *   <li>ESC (27) → 进一步读后续字节，判断是取消还是括号粘贴</li>
     *   <li>Enter → 空提交（用户直接回车）</li>
     *   <li>其他字符 → 用户开始输入，继续 burst read 剩余字节后作为 seed buffer</li>
     * </ul>
     *
     * <h3>退格键处理</h3>
     * 退格 (8/127) 被视为空字符串，因为 seed buffer 中不应该包含退格字符——
     * 用户按退格意味着清空了预填内容。
     */
    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal);
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
    private static PrefillResult readEscapeInput(Terminal terminal) throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 30, 25, 250);
        if (sequence.isEmpty()) {
            return PrefillResult.canceledInput();
        }

        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
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
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long firstDeadline = start + firstWaitMs;
        long idleDeadline = 0;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (terminal.reader().ready()) {
                int next = terminal.reader().read();
                if (next < 0) {
                    break;
                }
                buffer.append((char) next);
                idleDeadline = System.currentTimeMillis() + idleWaitMs;
                continue;
            }

            long now = System.currentTimeMillis();
            if (buffer.isEmpty()) {
                if (now >= firstDeadline) {
                    break;
                }
            } else if (now >= idleDeadline) {
                break;
            }

            Thread.sleep(5);
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
                "默认模式是 ReAct",
                "输入 '/clear' 清空对话历史",
                "输入 '/memory' 查看记忆状态",
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

    /**
     * 在读到 ESC 后清空终端缓冲区中的残留字节。
     *
     * 方向键（上/下/左/右）和功能键（F1-F12）都以 ESC 开头后跟多字节序列。
     * 用户可能误按方向键，此时只读到了 ESC，后续的 [A/[B 等字节还残留在缓冲区。
     * 短暂 sleep 50ms 等后续字节全部到达后全部 drain 掉，防止污染下一次读取。
     */
    private static void drainEscapeSequence(Terminal terminal) {
        try {
            // 短暂等待，让后续字节到达
            Thread.sleep(50);
            // 检查并丢弃所有待读字节（如方向键序列 [A, [B 等）
            while (terminal.reader().ready()) {
                terminal.reader().read();
            }
        } catch (Exception ignored) {
        }
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
     * <p>这样 {@link com.paicli.rag.EmbeddingClient#getEnv} 的
     * {@code System.getProperty(key)} 回退路径就能命中 .env 中的
     * {@code EMBEDDING_PROVIDER} / {@code EMBEDDING_MODEL} / {@code EMBEDDING_API_KEY}
     * 等配置，不再需要把这些变量 export 到真正的 OS 环境变量中。</p>
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
        System.out.printf("║      Memory-Enhanced Agent CLI %-8s                 ║%n", "v" + VERSION);
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
