package com.paicli.plan;

import java.util.*;

/**
 * 执行计划的数据结构 —— 包含一组有依赖关系的 Task，按 DAG 拓扑序调度执行。
 *
 * <h3>核心数据结构</h3>
 * <ul>
 *   <li>{@code tasks}：{@link LinkedHashMap}，保持插入顺序（LLM 输出的 JSON 顺序），
 *       方便 {@link #visualize()} 展示时稳定排列，不随内部状态变化而跳序</li>
 *   <li>{@code executionOrder}：由 {@link #computeExecutionOrder()} 经 DFS 后序遍历
 *       生成的拓扑序列。每个节点在其所有前置依赖之后出现</li>
 * </ul>
 *
 * <h3>与 Planner / PlanExecuteAgent 的协作关系</h3>
 * <ol>
 *   <li>{@code Planner.parsePlan()} 两遍扫描 LLM JSON → 建 Task 节点 + 回填依赖 →
 *       调 {@code plan.computeExecutionOrder()} 检查环 → 返回计划</li>
 *   <li>{@code PlanExecuteAgent.executePlan()} 按 {@code executionOrder} 遍历，
 *       执行前用 {@code Task.isExecutable()} 做运行时二次校验 → 失败且进度 &lt; 50% 时触发 replan</li>
 * </ol>
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;
    /** 使用 LinkedHashMap 保持插入顺序，保证 {@link #visualize()} 输出稳定 */
    private final Map<String, Task> tasks;
    /** DFS 后序遍历结果 —— 每个节点在其所有前置依赖之后 */
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;
    private long startTime;
    private long endTime;

    public enum PlanStatus {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();  // 保持插入顺序
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    // Getters
    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setSummary(String summary) { this.summary = summary; }
    public void setStatus(PlanStatus status) { this.status = status; }

    /**
     * 将 Task 加入 DAG，同时自动回填双向依赖链。
     *
     * <h3>为什么在 add 的时候建 dependents？</h3>
     * 调用方（Planner.parsePlan）在第二遍扫描时逐个 task.addDependency()，
     * 但此时被依赖的 Task 还不知道"谁依赖了我"。
     * 这个方法在插入新 Task 时检查它的 dependencies 列表，
     * 对每个已存在的被依赖节点调用 {@code dep.addDependent(newTask.id)}，
     * 从而保证双向链完整。
     */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    /**
     * 获取任务
     */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /**
     * 获取所有任务
     */
    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 获取根任务（没有依赖其他节点的任务）
     */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    /**
     * 获取可执行的任务（正在依赖的节点状态都是完成）
     */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    /**
     * 用 DFS 后序遍历计算拓扑排序，同时检测循环依赖。
     *
     * <h3>三色标记法</h3>
     * 不用三个 enum，用两个 {@link HashSet} 模拟三种颜色：
     * <table>
     *   <tr><th>颜色</th><th>含义</th><th>Set 中的位置</th></tr>
     *   <tr><td>白色</td><td>未访问</td><td>既不在 visited 也不在 visiting</td></tr>
     *   <tr><td>灰色</td><td>正在递归栈中（后代还没处理完）</td><td>在 visiting 中</td></tr>
     *   <tr><td>黑色</td><td>已完成（自己和所有后代都已处理）</td><td>在 visited 中</td></tr>
     * </table>
     *
     * <h3>环检测原理</h3>
     * 如果 DFS 过程中遇到一个<strong>灰色</strong>节点（在 visiting 中），
     * 说明存在一条从该节点出发又回到该节点的路径 → 有环 → 返回 false。
     *
     * <h3>为什么用后序加入 executionOrder？</h3>
     * 后序遍历（递归返回后才 add）保证每个节点的所有前置依赖先于它加入列表。
     * 例如 A → B → C 的链：DFS 先深入 C（无依赖），C 先加入，回溯到 B 加入，
     * 再回溯到 A 加入，最终顺序 [C, B, A]。执行时从左到右就是安全的。
     *
     * <h3>为什么不在构造时自动调这个方法？</h3>
     * Planner.parsePlan() 分两遍解析 JSON：第一遍建节点（可能前向引用），
     * 第二遍回填依赖。只有在依赖全部就绪后拓扑排序才有意义，
     * 因此由调用方在 parsePlan 的最后一步显式调用。
     *
     * @return true 表示无环，executionOrder 已填充；false 表示存在循环依赖
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();   // 黑色节点：已完成 DFS
        Set<String> visiting = new HashSet<>();  // 灰色节点：正在递归栈中

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;  // 检测到环，终止
                }
            }
        }

        return true;
    }

    /**
     * 对单个节点递归执行三色 DFS。
     *
     * @return false 表示在子树中检测到环
     */
    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        // 灰色节点再次出现 → 后向边 → 有环
        if (visiting.contains(id)) {
            return false;
        }
        // 黑色节点 → 已经处理过，剪枝
        if (visited.contains(id)) {
            return true;
        }

        visiting.add(id);  // 入栈：标记为灰色

        // 沿 dependencies 边向下 DFS（被依赖的节点必须先处理）
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) {
                    return false;  // 子树中发现环，向上传播
                }
            }
        }

        visiting.remove(id);       // 出栈：灰色 → 黑色
        visited.add(id);
        executionOrder.add(id);    // 后序加入：所有依赖都已排在前面
        return true;
    }

    /**
     * 获取执行顺序（懒计算：首次调用时自动触发拓扑排序）。
     * 返回防御性拷贝，防止外部修改内部列表。
     */
    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    /**
     * 获取执行进度（0.0 ~ 1.0）。
     * 被 {@code PlanExecuteAgent.executePlan()} 用于判断失败时是否值得触发 replan——
     * 阈值设在 50%：进度不到一半说明大部分任务还没做，重新规划比继续执行更划算。
     */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    /**
     * 是否全部完成
     */
    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    /**
     * 是否有失败任务
     */
    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 标记完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 标记失败
     */
    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取总耗时
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 生成 ASCII 表格可视化，打印到终端。
     * 在执行开始前调用，让用户预览完整计划后再开始（相当于"执行前确认"）。
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  执行计划: %-46s║%n", goal.length() > 46 ? goal.substring(0, 43) + "..." : goal));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" :
                    String.join(",", task.getDependencies());

            sb.append(String.format("║  %d. %s %-20s ", i + 1, statusIcon, task.getId()));
            sb.append(String.format("[%-10s] 依赖: %-15s║%n",
                    task.getType(), deps));
            sb.append(String.format("║     %s%n",
                    task.getDescription().length() > 50 ?
                            task.getDescription().substring(0, 47) + "..." :
                            task.getDescription()));
        }

        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        sb.append(String.format("   进度: %.0f%% | 状态: %s%n",
                getProgress() * 100, status));

        return sb.toString();
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, goal, tasks.size(), status);
    }
}

