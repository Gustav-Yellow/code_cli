package com.paicli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalMarkdownRenderer;

import java.io.IOException;
import java.util.*;

/**
 * 规划器 —— 用 LLM 将复杂任务分解为结构化的 {@link ExecutionPlan}。
 *
 * <h3>在整个 Plan-and-Execute 流程中的位置</h3>
 * <pre>
 * PlanExecuteAgent.runWithPlan(goal)
 *   └─ planner.createPlan(goal)           ← 本类
 *        ├─ LLM 生成 JSON（含 task 列表 + 依赖）
 *        └─ parsePlan() 两遍解析 → ExecutionPlan → computeExecutionOrder()
 *   └─ plan.visualize()                   ← 打印计划
 *   └─ agent.executePlan(goal, plan)      ← 按拓扑序执行
 * </pre>
 *
 * <h3>为什么 LLM 输出的 JSON 需要两遍解析？</h3>
 * JSON 数组中的任务可能包含<strong>前向引用</strong>——task_3 声明
 * {@code "dependencies": ["task_5"]}，但 task_5 在后面才出现。
 * 第一遍只建节点（忽略依赖），第二遍才回填依赖关系，这样无论 LLM
 * 按什么顺序输出都不会出错。
 */
public class Planner {
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // 规划提示词
    private static final String PLANNING_PROMPT = """
            你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。

            可用任务类型：
            - FILE_READ: 读取文件内容
            - FILE_WRITE: 写入文件内容
            - COMMAND: 执行Shell命令
            - ANALYSIS: 分析结果并做出决策
            - VERIFICATION: 验证结果是否正确

            请按以下JSON格式输出执行计划：
            {
                "summary": "任务摘要",
                "tasks": [
                    {
                        "id": "task_1",
                        "description": "任务描述",
                        "type": "FILE_READ",
                        "dependencies": []
                    },
                    {
                        "id": "task_2",
                        "description": "任务描述",
                        "type": "FILE_WRITE",
                        "dependencies": ["task_1"]
                    }
                ]
            }

            规则：
            1. 每个任务必须有唯一的id（如 task_1, task_2）
            2. dependencies列出依赖的任务id
            3. 任务应该按执行顺序排列
            4. 任务描述要具体明确
            5. 简单任务（如列目录、读取单个文件、执行单条命令）允许只生成1-3个任务；不要为了凑步数引入无关步骤
            6. 复杂任务再拆分为5-10个子任务
            7. 不要为了“保存中间结果”而额外创建 FILE_WRITE / FILE_READ，除非用户明确要求落盘
            8. 如果一个任务一步就能完成，就保持最短计划

            只输出JSON，不要有其他内容。
            """;

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 调用 LLM 将自然语言目标分解为执行计划。
     *
     * <h3>流程</h3>
     * <ol>
     *   <li>组装 system prompt（PLANNING_PROMPT）+ user message（goal）</li>
     *   <li>调用 {@code llmClient.chat()}，tools=null（规划阶段不需要工具调用）</li>
     *   <li>解析 LLM 返回的 JSON → {@link ExecutionPlan}</li>
     * </ol>
     *
     * @param goal 用户的自然语言目标
     * @return 已通过拓扑排序校验的执行计划
     * @throws IOException 网络错误或 LLM 返回的计划中存在循环依赖
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        return createPlan(goal, null);
    }

    /**
     * 调用 LLM 将自然语言目标分解为执行计划，可附带先前对话上下文做上下文感知规划。
     *
     * @param goal         用户的自然语言目标
     * @param priorContext 先前对话上下文（来自共享历史），为 null 或空时退化为无上下文规划
     */
    public ExecutionPlan createPlan(String goal, String priorContext) throws IOException {
        System.out.println("📋 正在规划任务: " + goal + "\n");

        // 如果是简单的任务
        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        // 构建规划请求
        String userContent;
        if (priorContext == null || priorContext.isBlank()) {
            userContent = "请为以下任务制定执行计划：\n" + goal;
        } else {
            userContent = "【先前对话上下文】\n" + priorContext + "\n\n请参考上下文，为以下任务制定执行计划：\n" + goal;
        }

        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(PLANNING_PROMPT),
                LlmClient.Message.user(userContent)
        );

        // 调用LLM生成计划
        PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer();
        LlmClient.ChatResponse response = llmClient.chat(messages, null, streamRenderer);
        streamRenderer.finish();
        String planJson = response.content();

        // 解析JSON计划
        return parsePlan(goal, planJson);
    }

    /**
     * 两遍扫描解析 LLM 生成的计划 JSON → {@link ExecutionPlan}。
     *
     * <h3>为什么要重写 LLM 给的 id？</h3>
     * LLM 可能输出重复 id、奇怪的命名、或者 id 与实际依赖关系不一致。
     * 使用 {@code idMapping} 将所有原始 id 统一重写为 {@code task_1, task_2, ...}：
     * <ul>
     *   <li>保证 id 唯一且可预测</li>
     *   <li>dependencies 中的旧 id 也通过映射表转换</li>
     * </ul>
     *
     * <h3>两遍扫描</h3>
     * <ol>
     *   <li><b>第一遍</b>：只建 Task 节点（id / description / type），
     *       暂不处理 dependencies。因为 LLM 可能前向引用未出现的 task。</li>
     *   <li><b>第二遍</b>：遍历 dependencies 数组，通过 {@code idMapping}
     *       将原 id 转为新 id，调用 {@code task.addDependency()}。</li>
     *   <li><b>回填 dependents</b>：遍历所有 Task，对每个 dependency 调用
     *       {@code dep.addDependent(taskId)} 补全反向链。</li>
     *   <li><b>拓扑校验</b>：调 {@code plan.computeExecutionOrder()}，
     *       有环则抛 IOException。</li>
     * </ol>
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        // LLM 有时候会在 JSON 外面包 markdown 代码块，清理掉
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // ── 第一遍：建节点，不做依赖 ──
        // 第一遍：创建所有任务（不处理依赖，因为可能有前向引用）
        Map<String, String> idMapping = new HashMap<>(); // 原id → 新id 映射
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();
            Task.TaskType type = parseTaskType(typeStr);

            Task task = new Task(newId, description, type);
            plan.addTask(task);
        }

        // ── 第二遍：回填依赖关系（通过 idMapping 转换） ──
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    // 用映射表转换：找不到时保持原值（可能是 LLM 引用了不存在的 id）
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    if (plan.getTask(newDepId) != null) {
                        task.addDependency(newDepId);
                    }
                }
            }
        }

        // ── 补全双向链：对每个 dependency，在被依赖节点侧建立 dependents 关系 ──
        for (Task task : plan.getAllTasks()) {
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep != null) {
                    dep.addDependent(task.getId());
                }
            }
        }

        // ── 拓扑校验：有环则拒绝 ──
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    /**
     * 生成计划ID
     */
    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 基于失败计划重新规划。
     *
     * <h3>上下文组装策略</h3>
     * 把已完成任务的结果作为上下文传给 LLM，让新计划跳过已完成的工作。
     * 失败原因也一并传入，帮助 LLM 避开同样的问题。
     *
     * <h3>递归终止</h3>
     * 这个方法内部调 {@link #createPlan(String)}，后者可能再次失败并再次触发 replan。
     * 理论上 LLM 一直返回有环的计划会无限递归，但概率极低，当前没有加递归深度限制。
     *
     * @param failedPlan   原计划（含已完成任务及其结果）
     * @param failureReason 失败原因描述
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        System.out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(context.toString());
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
                && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    /**
     * 规划流式渲染器。
     */
    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                System.out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(System.out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            System.out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                System.out.println("\n");
            }
        }
    }
}
