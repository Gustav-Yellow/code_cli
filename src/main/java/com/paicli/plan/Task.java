package com.paicli.plan;

import java.util.*;

/**
 * 任务节点的数据结构 - 执行计划 DAG 中的最小可执行单元。
 *
 * <h3>在 DAG 中的角色</h3>
 * 每个 Task 是 DAG 中的一个顶点，边由 {@code dependencies} 和 {@code dependents} 双向维护：
 * <ul>
 *   <li>{@code dependencies}：当前节点<strong>依赖</strong>哪些节点（前置任务，必须等它们先完成）</li>
 *   <li>{@code dependents}：哪些节点<strong>依赖</strong>当前节点（后继任务，当前任务完成后它们才能开始）</li>
 * </ul>
 * 双向链是为了在运行时快速查找——拓扑排序时顺着 dependencies 向下 DFS，组装最终结果时顺着 dependents 找叶子节点。
 *
 * <h3>状态机（PENDING → RUNNING → COMPLETED / FAILED / SKIPPED）</h3>
 * <pre>
 *   PENDING ──markStarted()──► RUNNING ──markCompleted()──► COMPLETED
 *      │                         │
 *      │                         └───markFailed()─────────► FAILED
 *      └──────────────────────────────────────────────────► SKIPPED（依赖未满足时跳过）
 * </pre>
 * 状态一旦离开 PENDING 就不会再回去，保证了单次执行语义。
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile String error;
    /** 当前任务依赖的前置任务 ID（这些任务必须先完成，当前任务才能开始） */
    private final List<String> dependencies;
    /** 依赖当前任务的后继任务 ID（当前任务完成后，这些任务才可能变得可执行） */
    private final List<String> dependents;
    private volatile long startTime;
    private volatile long endTime;

    /**
     * 任务类型 —— 决定了 {@code PlanExecuteAgent.executeTask()} 中传给 LLM 的执行提示词的 tone。
     * ANALYSIS 和 VERIFICATION 类型通常不需要工具调用，LLM 直接输出文本分析即可。
     */
    public enum TaskType {
        PLANNING,
        FILE_READ,
        FILE_WRITE,
        COMMAND,
        ANALYSIS,
        VERIFICATION
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    /**
     * 带初始依赖的构造器 —— 用于 Planner 解析 LLM 输出的 JSON 时，
     * 在第二遍扫描中一次性建立依赖关系。
     */
    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    // Setters
    public void setStatus(TaskStatus status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }

    /**
     * 注册一个"谁依赖我"的关系 —— 当其他 Task 声明 {@code dependencies: [我的id]} 时调用。
     * 双向链保证了从任意节点都能沿 DAG 正向/反向遍历。
     */
    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    /**
     * 注册一个"我依赖谁"的关系 —— 从 LLM 输出的 dependencies 数组中解析而来。
     */
    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    /**
     * 标记任务开始执行
     */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 标记任务已经完成
     * @param result
     */
    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 标记任务失败
     * @param error
     */
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 标记任务被跳过
     */
    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 运行时校验：当前任务是否能立即执行。
     *
     * <h3>为什么拓扑排序之后还要做这个检查？</h3>
     * 拓扑排序是<strong>静态</strong>分析（建图时一次性完成），但运行时情况会变：
     * 某个前置任务可能 FAILED（而非 COMPLETED），此时当前任务就不该执行。
     * 这个方法是动态安全网：拓扑序保证"可以按这个顺序安全执行"，
     * isExecutable 保证"此刻真的可以执行"。
     *
     * @param allTasks 计划中所有任务（id → Task），用于查找依赖的状态
     * @return 仅当状态为 PENDING 且所有前置依赖都已 COMPLETED 时返回 true
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        // 已经执行过（或已被跳过/失败）的任务不应该再执行
        if (status != TaskStatus.PENDING) return false;

        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            // 依赖不存在，或依赖尚未完成 → 不可执行
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}

