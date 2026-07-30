package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.plan.*;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;

/**
 * Plan-and-Execute Agent —— 先规划后执行。
 *
 * <h3>与 {@link Agent}（纯 ReAct）的分工</h3>
 * {@link #shouldPlan(String)} 用启发式规则判断输入复杂度：
 * <ul>
 *   <li>简单任务（动作关键词 &lt; 3 且输入 ≤ 50 字）→ {@link #runSimple(String)} 直接调 LLM + 工具</li>
 *   <li>复杂任务 → {@link #runWithPlan(String)} 走完整 Plan-Execute 流水线</li>
 * </ul>
 *
 * <h3>Plan-Execute 流水线</h3>
 * <pre>
 * Planner.createPlan(goal)                    ← LLM 生成任务 DAG JSON
 *   └─ parsePlan() → ExecutionPlan
 *        └─ computeExecutionOrder() → 拓扑序 + 环检测
 *
 * plan.visualize()                            ← 打印计划让用户预览
 *
 * executePlan(goal, plan):
 *   for taskId in executionOrder:             ← 按拓扑序遍历
 *     ├─ task.isExecutable()?                 ← 运行时二次校验
 *     ├─ executeTask(goal, plan, task)        ← 每个 Task 独立调 LLM
 *     ├─ markCompleted / markFailed
 *     └─ progress < 50%? → replan            ← 失败触发重规划
 * </pre>
 *
 * <h3>双层安全网</h3>
 * <ol>
 *   <li><b>拓扑排序</b>（静态）：建图时一次性确定安全执行顺序，检测环</li>
 *   <li><b>isExecutable()</b>（动态）：执行前校验所有前置依赖确实 COMPLETED，
 *       防止因前置任务 FAILED 而执行不该执行的后继任务</li>
 * </ol>
 */
public class PlanExecuteAgent {
    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;

    // 执行提示词
    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具：
            1. read_file - 读取文件内容，参数：{"path": "文件路径"}
            2. write_file - 写入文件内容，参数：{"path": "文件路径", "content": "内容"}
            3. execute_command - 执行命令，参数：{"command": "命令"}
            4. create_project - 创建项目，参数：{"name": "名称", "type": "java|python|node"}

            如果是ANALYSIS或VERIFICATION类型任务，请直接输出分析结果，不需要调用工具。

            请用中文回复。
            """;

    public PlanExecuteAgent(String apiKey) {
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.planner = new Planner(llmClient);
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        try {
            // 判断是否需要复杂规划
            if (shouldPlan(userInput)) {
                return runWithPlan(userInput);
            } else {
                // 简单任务直接用ReAct
                return runSimple(userInput);
            }
        } catch (Exception e) {
            return "❌ 执行失败: " + e.getMessage();
        }
    }

    /**
     * 启发式判断输入是否需要走 Plan-and-Execute 模式。
     *
     * <h3>判断依据</h3>
     * 统计输入中的中文动作关键词出现次数：
     * <ul>
     *   <li>≥ 3 个动作关键词 → 复杂任务，需要规划</li>
     *   <li>输入 > 50 字 → 说明任务描述长，大概率涉及多步操作</li>
     *   <li>否则 → 简单任务，直接用 ReAct 模式</li>
     * </ul>
     * 这个启发式不完美，但足够覆盖第 2 期的常见用例。
     * 后续可以考虑让 LLM 自己判断（增加一次轻量分类调用）。
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
     * Plan 模式入口：规划 → 可视化 → 执行。
     * 将 {@code Planner}、{@code ExecutionPlan}、{@code executePlan()} 串联起来。
     */
    private String runWithPlan(String goal) throws IOException {
        // 1. 创建执行计划
        ExecutionPlan plan = planner.createPlan(goal);
        return executePlan(goal, plan);
    }

    /**
     * 按拓扑序遍历执行计划中的所有 Task。
     *
     * <h3>运行时校验（双层安全网的第二层）</h3>
     * 遍历 {@code executionOrder} 时，每个 Task 执行前再调一次 {@link Task#isExecutable(Map)}。
     * 虽然拓扑序在静态上保证了顺序安全，但运行时可能发生：
     * <ul>
     *   <li>前置任务 FAILED（而非 COMPLETED）→ 后继不应执行 → 跳过</li>
     *   <li>前置任务被 SKIPPED → 同理跳过</li>
     * </ul>
     *
     * <h3>重规划阈值 — 50%</h3>
     * 任务失败时，如果整体进度不到一半（{@code progress < 0.5}），
     * 说明大部分工作还没做，重新规划是划算的。
     * 进度过半后继续执行剩余任务（失败的任务跳过），避免重来浪费已完成的工作。
     *
     * <h3>递归 replan</h3>
     * replan 成功后通过<strong>递归调用</strong>执行新计划：
     * {@code return executePlan(goal, replanned)}。
     * 这意味着原计划的执行被新计划完全替代，不会回到旧计划继续执行。
     */
    private String executePlan(String goal, ExecutionPlan plan) throws IOException {
        // 显示计划
        System.out.println(plan.visualize());
        System.out.println("🚀 开始执行计划...\n");

        // 2. 执行计划
        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        List<String> executionOrder = plan.getExecutionOrder();
        for (String taskId : executionOrder) {
            Task task = plan.getTask(taskId);

            // 检查依赖
            if (!task.isExecutable(plan.getAllTasks().stream()
                    .collect(java.util.stream.Collectors.toMap(Task::getId, t -> t)))) {
                System.out.println("⏭️ 跳过任务（依赖未完成）: " + taskId);
                task.markSkipped();
                continue;
            }

            // 执行任务
            System.out.println("▶️ 执行任务: " + task.getDescription());
            task.markStarted();

            try {
                String result = executeTask(goal, plan, task);
                task.markCompleted(result);
                System.out.println("✅ 完成: " + result.substring(0, Math.min(100, result.length())) + "\n");

            } catch (Exception e) {
                task.markFailed(e.getMessage());
                System.out.println("❌ 失败: " + e.getMessage() + "\n");

                // 尝试重新规划
                if (plan.getProgress() < 0.5) {
                    System.out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, e.getMessage());
                    return executePlan(goal, replanned);
                } else {
                    finalResult.append("任务 ").append(taskId).append(" 失败: ").append(e.getMessage());
                }
            }
        }

        if (finalResult.isEmpty()) {
            finalResult.append(buildFinalResult(plan));
        }

        // 3. 完成
        if (plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划部分完成，有任务失败。\n" + finalResult;
        } else {
            plan.markCompleted();
            return "✅ 计划执行完成！\n" + finalResult;
        }
    }

    /**
     * 执行单个 Task：组装 prompt → 调 LLM → 执行工具调用。
     *
     * <h3>每个 Task 有独立的 LLM 调用</h3>
     * 与 {@link Agent} 的多轮对话不同，这里每个 Task 是一次性的：
     * System prompt + User context（含依赖结果）→ LLM 回复/工具调用 → 直接返回。
     * 不维护跨 Task 的对话历史——依赖结果通过 {@link #buildTaskContext} 传递。
     */
    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        // 构建执行提示
        String prompt = String.format(EXECUTION_PROMPT,
                task.getType(), task.getDescription());

        List<GLMClient.Message> messages = Arrays.asList(
                GLMClient.Message.system(prompt),
                GLMClient.Message.user(buildTaskContext(goal, plan, task))
        );

        // 调用LLM
        GLMClient.ChatResponse response = llmClient.chat(
                messages,
                toolRegistry.getToolDefinitions()
        );

        // 如果有工具调用，执行工具
        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();

                System.out.println("   🔧 调用工具: " + toolName);

                String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                results.append(toolResult).append("\n");
            }

            return results.toString().trim();
        } else {
            // 直接返回分析结果
            return response.content();
        }
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
    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
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
        List<GLMClient.Message> messages = new ArrayList<>();
        messages.add(GLMClient.Message.system("你是一个智能编程助手，可以调用工具完成任务。"));
        messages.add(GLMClient.Message.user(userInput));

        GLMClient.ChatResponse response = llmClient.chat(
                messages,
                toolRegistry.getToolDefinitions()
        );

        if (response.hasToolCalls()) {
            StringBuilder results = new StringBuilder();

            for (GLMClient.ToolCall toolCall : response.toolCalls()) {
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
}

