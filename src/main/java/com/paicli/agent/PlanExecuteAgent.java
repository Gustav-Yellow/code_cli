package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.llm.GLMClient;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.*;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent —— 先规划，用户审查，再批量并行执行。
 *
 * <h3>核心流水线</h3>
 * <pre>
 * run(userInput)
 *   └─ runWithPlan(goal)
 *        ├─ Planner.createPlan(goal)              ← LLM 生成任务 DAG JSON
 *        ├─ reviewAndExecutePlan(plan)            ← 用户审查（执行/补充/取消）
 *        │    └─ PlanReviewHandler.review()       ← 依赖注入的审查回调
 *        └─ executePlan(plan)                     ← 批次并行执行
 *             ├─ while 仍有可执行任务:
 *             │    ├─ getExecutableTasksInOrder()  ← 拓扑序 + isExecutable 过滤
 *             │    └─ executeTaskBatch()           ← 单任务直接跑 / 多任务线程池并行
 *             └─ buildFinalResult()                ← 收集叶子节点结果
 * </pre>
 *
 * <h3>模式路由</h3>
 * 当前由 CLI 层（Main.java）通过 /plan 命令控制是否使用本 Agent，
 * Agent 内部不再自动判断 ReAct vs Plan。
 *
 * <h3>双层安全网</h3>
 * <ol>
 *   <li><b>拓扑排序</b>（静态）：建图时一次性确定安全执行顺序，检测环</li>
 *   <li><b>isExecutable()</b>（动态）：每轮 while 循环重新过滤，防止前置 FAILED 导致后继错误执行</li>
 * </ol>
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 单个任务的执行结果封装 —— 成功时 result 有值，失败时 error 有值。
     * 用于统一处理单任务和并行任务两种路径的返回值。
     */
    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    /**
     * 任务执行返回值 —— 携带流式输出标记，用于 executePlan 决定是否重复打印结果。
     */
    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    /**
     * 计划审查回调接口 —— 依赖反转：Agent 只定义接口，CLI 层实现终端的交互细节。
     * 默认实现：不弹审查，直接执行。
     */
    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    /** 用户对计划的三种决策 */
    public enum PlanReviewAction {
        EXECUTE,      // 执行当前计划
        SUPPLEMENT,   // 补充要求，重新规划
        CANCEL        // 取消本次计划
    }

    /** 审查决策 + 补充说明（SUPPLEMENT 时 feedback 非空） */
    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    /** 审查回调 —— 由 CLI 层注入，控制是否弹交互式审查界面 */
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    /** 会话级共享对话历史 —— 与 ReAct 共享，模式切换时上下文连续 */
    private final List<LlmClient.Message> sharedHistory;


    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. list_dir - 列出目录内容，参数：{"path": "目录路径"}
            4. execute_command - 执行命令，参数：{"command": "命令"}
            5. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}
            6. search_code - 语义检索代码库，参数：{"query": "自然语言描述", "top_k": 5}
            7. web_search - 搜索互联网获取实时信息，参数：{"query": "搜索关键词", "top_k": 5}
            8. web_fetch - 抓取已知 URL 并返回正文 Markdown，参数：{"url": "https://...", "max_chars": 8000}

            如果任务涉及理解代码库（如分析代码结构、查找实现位置），请优先使用 search_code 工具。
            如果任务需要实时互联网信息（如查询框架最新版本、官方文档），请使用 web_search 找入口，
            拿到具体 URL 后用 web_fetch 抓取全文。已经有 URL 时直接 web_fetch，不要再 web_search 一次。
            web_fetch 拿到空正文（SPA / 防爬墙）时，明确告知用户这是已知边界，不要反复重试。
            对于当前项目内的文件，请优先使用 read_file 或 list_dir，不要用 execute_command 扫描 /、~ 或整个文件系统。
            execute_command 只适合在当前项目目录执行短时命令。
            安全策略硬规则（HITL 之外的兜底，无法绕过）：read_file / write_file / list_dir / create_project 必须在项目根之内；write_file 单文件 5MB 上限；
            execute_command 禁止 sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown。
            被策略拒绝的工具调用（"🛡️ 策略拒绝" 开头）不要原样重试，改用项目内相对路径或更安全的命令。
            同一轮返回多个工具调用时，系统会并行执行这些工具；如果工具之间有依赖关系，请分多轮调用。
            如果需要同时检查多个已知且互不依赖的文件或目录（例如同时读取 pom.xml、README.md、ROADMAP.md，
            或同时列出 src/main/java、src/test/java、src/main/resources），请在同一轮返回多个 read_file/list_dir 工具调用。
            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    /**
     * 无审查构造器：生成计划后直接执行，不弹交互界面。
     */
    public PlanExecuteAgent(String apiKey) {
        this(new GLMClient(apiKey), (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, new ArrayList<>(), null, reviewHandler);
    }

    /**
     * 共享上下文构造器：注入会话级 sharedHistory 与 MemoryManager，
     * 让 Plan 与 ReAct 共享同一份对话记忆与长期记忆。
     */
    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler,
                            List<LlmClient.Message> sharedHistory, MemoryManager sharedMemory) {
        this(llmClient, new ToolRegistry(), null, sharedHistory, sharedMemory, reviewHandler);
    }

    /**
     * 共享上下文 + 自定义 ToolRegistry 构造器：允许注入 HitlToolRegistry，
     * 让 Plan 模式下的工具调用也走 HITL 审批。
     */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            PlanReviewHandler reviewHandler,
                            List<LlmClient.Message> sharedHistory, MemoryManager sharedMemory) {
        this(llmClient, toolRegistry, null, sharedHistory, sharedMemory, reviewHandler);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     List<LlmClient.Message> sharedHistory, MemoryManager memoryManager,
                     PlanReviewHandler reviewHandler) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.planner = planner != null ? planner : new Planner(llmClient);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.sharedHistory = sharedHistory;
    }

    /**
     * 运行入口：所有输入统一走 Plan 路径（模式选择由 CLI 层负责）。
     *
     * <h3>共享上下文</h3>
     * 开头压缩共享历史、取先前对话上下文供规划参考，并追加 user(goal)；
     * 结尾把计划结果作为 assistant 消息追加回共享历史，使切回 ReAct 时上下文连续。
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 压缩共享历史（与 ReAct 同一调用，作用于会话级历史）
        memoryManager.compressContextIfNeeded(sharedHistory);
        // 取先前对话上下文（在追加当前 goal 之前，避免自匹配/冗余）
        // 默认提取前 8 条，可通过参数调整
        String priorContext = buildPriorContext(sharedHistory, 8);
        // 添加本轮用户消息
        sharedHistory.add(LlmClient.Message.user(userInput));

        try {
            String result = runWithPlan(userInput, priorContext);
            if (result != null && !result.isBlank()) {
                sharedHistory.add(LlmClient.Message.assistant(result));
            }
            return result;
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            sharedHistory.add(LlmClient.Message.assistant(errorMessage));
            return errorMessage;
        }
    }

    /**
     * 从共享历史取最近 maxMessages 条（跳过 index 0 的 system prompt），
     * 格式化为 "role: content" 供规划时参考。压缩摘要若落在窗口内自然被包含。
     */
    private String buildPriorContext(List<LlmClient.Message> history, int maxMessages) {
        if (history.size() <= 1) {
            return "";
        }
        int start = Math.max(1, history.size() - maxMessages);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            LlmClient.Message m = history.get(i);
            sb.append(m.role()).append(": ").append(m.content()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 启发式判断输入复杂度 —— 当前保留但不再被 run() 调用。
     * CLI 层通过 /plan 命令显式切换模式，不依赖自动判断。
     */
    private boolean shouldPlan(String input) {
        String lower = input.toLowerCase();
        int actionCount = 0;
        String[] actionKeywords = {"创建", "写", "读", "执行", "编译", "运行", "修改", "删除", "然后", "接着", "再", "最后"};

        for (String keyword : actionKeywords) {
            if (lower.contains(keyword)) actionCount++;
        }

        return actionCount >= 3 || input.length() > 50;
    }

    /**
     * Plan 模式入口：LLM 规划（可带先前对话上下文）→ 用户审查 → 批次执行。
     */
    private String runWithPlan(String goal, String priorContext) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal, priorContext);
        return reviewAndExecutePlan(plan);
    }

    /**
     * 计划审查循环 —— 阻塞等待用户决策后才进入执行。
     *
     * <h3>三种决策路径</h3>
     * <ul>
     *   <li>EXECUTE → 直接进入 {@link #executePlan(ExecutionPlan)}</li>
     *   <li>CANCEL → 返回取消消息，不执行任何任务</li>
     *   <li>SUPPLEMENT → 将补充要求拼接到原 goal 上，
     *       调用 {@code planner.createPlan()} 重新生成计划，
     *       然后再次进入审查循环</li>
     * </ul>
     *
     * <h3>为什么是 while(true)？</h3>
     * 用户可能多次补充要求，每次都会重新规划并再次审查，
     * 直到用户满意（选 EXECUTE）或放弃（选 CANCEL）才跳出。
     */
    private String reviewAndExecutePlan(ExecutionPlan plan) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return executePlan(plan);
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return "⏹️ 已取消本次计划执行。";
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return executePlan(plan);
            }

            System.out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    /**
     * 按拓扑序 + 批次并行 DAG 中的所有 Task。
     *
     * <h3>while 循环 vs 旧版 for 循环</h3>
     * 旧版是 {@code for taskId in executionOrder} 逐个执行。
     * 新版改为 while + 每轮重新计算"当前可执行任务"：
     * <ul>
     *   <li>一轮可能同时完成多个互不依赖的任务</li>
     *   <li>下一轮才有新的任务变得可执行（其前置依赖刚完成）</li>
     *   <li>while 保证不会漏掉后续批次</li>
     * </ul>
     *
     * <h3>并行安全前提</h3>
     * 同一批次中的任务都通过了 {@link Task#isExecutable(Map)} 校验，
     * 即所有前置依赖都已完成，因此并行执行互不干扰。
     *
     * <h3>僵局检测</h3>
     * while 退出但计划未全部完成且没有失败 → 存在永远无法满足的依赖
     * （如依赖了不存在的任务）→ 标记 FAILED 并返回提示。
     *
     * <h3>重规划</h3>
     * 任务失败 + 进度 &lt; 50% → replan 并重新进入审查循环，
     * 而非直接递归 executePlan（让用户在 replan 后有机会审查新计划）。
     */
    private String executePlan(ExecutionPlan plan) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        System.out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();
        StreamState streamState = new StreamState();

        // 每次都从无依赖或者前置依赖已经完成的节点开始执行任务。
        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        System.out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        System.out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                System.out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    System.out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned);
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        // 计划执行完成（成功或部分失败）后，用本次计划的任务结果提取关键事实到长期记忆。
        // 放在此处而非 run()：plan 在作用域内，无需额外传参；replan/取消等提前返回路径不会触发。
        extractFactsFromPlan(plan);

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        // 3. 完成
        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    /**
     * 用本次计划的目标 + 各任务结果构建会话历史，提取关键事实到长期记忆。
     * 工具中间结果不纳入（事实提取的噪声），只取任务最终产出。
     */
    private void extractFactsFromPlan(ExecutionPlan plan) {
        List<LlmClient.Message> sessionHistory = new ArrayList<>();
        sessionHistory.add(LlmClient.Message.user(plan.getGoal()));
        for (Task t : plan.getAllTasks()) {
            if (t.getResult() != null && !t.getResult().isBlank()) {
                sessionHistory.add(LlmClient.Message.assistant("[" + t.getId() + "] " + t.getResult()));
            }
        }
        memoryManager.extractAndSaveFacts(sessionHistory);
    }

    /**
     * 获取当前可执行的任务，按拓扑序排列。
     *
     * <h3>为什么不直接用 getExecutableTasks()？</h3>
     * {@link ExecutionPlan#getExecutableTasks()} 返回所有 isExecutable==true 的任务，
     * 但不保证顺序。本方法先取可执行集合，再按拓扑序过滤，
     * 确保同一批次内的任务也保持依赖顺序。
     * 每次返回可以并行执行的任务节点
     */
    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    /**
     * 批量执行一组互不依赖的任务。
     *
     * <h3>单任务 vs 并行</h3>
     * 只有一个任务时直接在当前线程执行，避免线程池创建开销。
     * 多个任务时用 {@link ExecutorService} 并行执行——
     * 前提是这些任务都通过了 isExecutable 校验，互不依赖。
     *
     * <h3>错误处理</h3>
     * {@link InterruptedException}：恢复中断标记，当前线程应响应取消
     * {@link ExecutionException}：解包原始异常，保留失败原因
     * finally 中 shutdownNow() 确保线程池立即释放
     */
    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, System.out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        System.out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "paicli-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            CompletionService<TaskExecutionResult> completionService = new ExecutorCompletionService<>(executor);
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            Map<String, TaskExecutionResult> resultMap = new LinkedHashMap<>();

            for (Task task : executableTasks) {
                System.out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                completionService.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                });
            }

            // 按完成顺序收集结果，但按提交顺序 flush 输出：
            // 当某个 task 完成时，如果它前面的 task 也都已完成，则连续 flush 直到遇到未完成的。
            int nextToFlush = 0;
            Set<String> completedIds = new HashSet<>();

            for (int i = 0; i < executableTasks.size(); i++) {
                try {
                    Future<TaskExecutionResult> completed = completionService.take();
                    TaskExecutionResult result = completed.get();
                    resultMap.put(result.task().getId(), result);
                    completedIds.add(result.task().getId());

                    // 按序 flush：从 nextToFlush 开始，连续 flush 所有已完成的 task
                    while (nextToFlush < executableTasks.size()) {
                        Task pending = executableTasks.get(nextToFlush);
                        if (completedIds.contains(pending.getId())) {
                            ByteArrayOutputStream buf = buffers.get(pending.getId());
                            if (buf != null && buf.size() > 0) {
                                System.out.print(buf.toString(StandardCharsets.UTF_8));
                                System.out.flush();
                            }
                            nextToFlush++;
                        } else {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Parallel batch wait interrupted at task {}/{}", i + 1, executableTasks.size());
                } catch (ExecutionException e) {
                    log.error("Parallel task failed unexpectedly", e.getCause());
                }
            }

            // 按原始顺序构建结果列表
            List<TaskExecutionResult> results = new ArrayList<>();
            for (Task task : executableTasks) {
                TaskExecutionResult result = resultMap.get(task.getId());
                if (result != null) {
                    results.add(result);
                } else {
                    results.add(TaskExecutionResult.failure(task, new RuntimeException("任务未返回结果")));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // 最大任务迭代次数
    private static final int MAX_TASK_ITERATIONS = 5;

    /**
     * 执行单个 Task：组装 prompt → 调 LLM → 执行工具调用（支持多轮工具调用）。
     *
     * <h3>每个 Task 有独立的 LLM 调用</h3>
     * 与 {@link Agent} 的多轮对话不同，这里每个 Task 维护自己的局部 messages：
     * System prompt + User context（含依赖结果）→ LLM 回复/工具调用 → 直接返回。
     * 不维护跨 Task 的对话历史——依赖结果通过 {@link #buildTaskContext} 传递。
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        String prompt = String.format(EXECUTION_PROMPT,
                task.getType(), task.getDescription());

        // 注入长期记忆上下文（检索与任务描述相关的长期记忆）
        String memoryContext = memoryManager.buildContextForQuery(task.getDescription(), 300);
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(prompt),
                LlmClient.Message.user(taskInput)
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);

        while (iteration < MAX_TASK_ITERATIONS) {
            iteration++;

            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolRegistry.getToolDefinitions(),
                    streamRenderer
            );
            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            // 记录本次调用的 token 使用（覆盖工具调用与最终响应两条分支）
            memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());

            // 没有工具调用，返回最终结果
            if (!response.hasToolCalls()) {

                // 如果之前存在工具调用的信息，则将工具调用结果作为最终结果返回
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }

                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            printToolCalls(out, response.toolCalls());
            messages.add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(task.getId(), response.toolCalls());
            for (ToolExecutionResult toolResult : toolResults) {
                allResults.append(toolResult.result()).append("\n");
                messages.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }
        }

        String fallbackResult = allResults.toString().trim();
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    /**
     * 组装当前 Task 的执行上下文，包含目标和已完成依赖的结果。
     *
     * <h3>为什么把依赖结果拼进 user message？</h3>
     * 后续 Task 需要依赖前置 Task 的产出才能正确执行。
     * 例如 task_2（写 README）需要 task_1（列目录）的结果才能知道有哪些文件。
     * 把这些结果作为 user message 传入，让 LLM 在"已知前置结果"的前提下推理。
     */
    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    /**
     * 汇总最终结果：优先取叶子节点（没有后继依赖的 Task）的结果。
     *
     * <h3>为什么取叶子节点？</h3>
     * DAG 中叶子节点是最后执行的任务，通常包含最终产出（验证结果、分析结论、生成的文件内容）。
     * 中间节点的结果一般已经被后续节点消化了，对用户没有直接意义。
     * 如果没有叶子节点有结果（fallback），取最后一个有结果的任务。
     */
    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

    /**
     * 简单任务的快速路径——单轮 LLM + 工具调用，不走规划流程。
     * 与 {@link Agent#run(String)} 的区别：这里只做一轮（不循环），适合单步操作。
     */
    private String runSimple(String userInput) throws IOException {
        System.out.println("💡 简单任务，直接执行...\n");

        // 复用第1期的ReAct逻辑
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system("你是一个智能编程助手，可以调用工具完成任务。"));
        messages.add(LlmClient.Message.user(userInput));

        LlmClient.ChatResponse response = llmClient.chat(
                messages,
                toolRegistry.getToolDefinitions()
        );

        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                String toolResult = toolRegistry.executeTool(
                        toolCall.function().name(),
                        toolCall.function().arguments()
                );
                results.append(toolResult).append("\n");
            }

            return results.toString().trim();
        } else {
            return response.content();
        }
    }

    /**
     * 获取执行统计
     */
    public String getStats() {
        return "PlanExecuteAgent 已就绪";
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId, List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            default -> "🔧 " + toolName + " × " + count;
        };
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    /**
     * 跨任务共享的流式输出标记 —— 任何一个 task 产生了流式输出，
     * run() 就能通过它判断是否跳过最终文本打印。
     */
    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    /**
     * 单任务的流式渲染器 —— 将 LLM 返回的 reasoning/content delta
     * 实时以 Markdown 格式打印到终端，并通知 StreamState。
     */
    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        private synchronized void flushPending() {
            if (reasoningRenderer != null) {
                reasoningRenderer.flushPending();
            }
            if (contentRenderer != null) {
                contentRenderer.flushPending();
            }
            out.flush();
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }
}
