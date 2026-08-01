package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Agent {

    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<GLMClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private static final int MAX_ITERATIONS = 10;

    // 系统提示词，给 Agent 限定身份
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，可以帮助用户完成各种任务。

            你可以使用以下工具来完成任务：
            1. read_file - 读取文件内容
            2. write_file - 写入文件内容
            3. list_dir - 列出目录内容
            4. execute_command - 执行Shell命令
            5. create_project - 创建新项目结构

            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            
            如果提供了相关记忆，请参考其中的信息来辅助决策。

            请用中文回复用户。
            """;

    public Agent(String apiKey) {
        this(apiKey, new ArrayList<>(), new MemoryManager(new GLMClient(apiKey)));
    }

    /**
     * 共享上下文构造器：注入会话级 conversationHistory 与 MemoryManager，
     * 让 ReAct 与 Plan 模式共享同一份对话记忆与长期记忆。
     */
    public Agent(String apiKey, List<GLMClient.Message> sharedHistory, MemoryManager sharedMemory) {
        this.llmClient = new GLMClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = sharedHistory;
        this.memoryManager = sharedMemory;

        // 保证 index 0 是 system prompt（供 Plan 后续追加、压缩保留 index 0）
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(GLMClient.Message.system(SYSTEM_PROMPT));
        }
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        // 先检索相关长期记忆并注入 system prompt（检索在写入历史之前，避免自匹配）
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（保持原文，不污染 user message）
        conversationHistory.add(GLMClient.Message.user(userInput));

        System.out.println("🤔 思考中...\n");

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            try {
                // 调用 LLM 前检查并压缩历史（超预算时用摘要替换旧消息）
                memoryManager.compressContextIfNeeded(conversationHistory);

                // 调用 LLM
                GLMClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions()
                );

                // 记录本次调用的 token 使用（覆盖工具调用与最终响应两条分支）
                memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(GLMClient.Message.assistant(
                            response.content(),
                            response.toolCalls()
                    ));

                    // 执行每个工具调用
                    for (GLMClient.ToolCall toolCall : response.toolCalls()) {
                        String toolName = toolCall.function().name();
                        String toolArgs = toolCall.function().arguments();

                        System.out.println("🔧 执行工具: " + toolName);
                        System.out.println("   参数: " + toolArgs);

                        // 执行工具
                        String toolResult = toolRegistry.executeTool(toolName, toolArgs);

                        System.out.println("   结果: " + toolResult.substring(0, Math.min(200, toolResult.length()))
                                + (toolResult.length() > 200 ? "..." : "") + "\n");

                        // 添加工具结果到对话历史
                        conversationHistory.add(GLMClient.Message.tool(toolCall.id(), toolResult));
                    }

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;

                } else {
                    // 没有工具调用，直接返回结果
                    conversationHistory.add(GLMClient.Message.assistant(response.content()));

                    // 打印 token 使用情况
                    System.out.printf("📊 Token使用: 输入=%d, 输出=%d%n\n",
                            response.inputTokens(), response.outputTokens());

                    return response.content();
                }

            } catch (IOException e) {
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }

        return "❌ 达到最大迭代次数限制，任务未完成";
    }

    /**
     * 清空对话历史（保留系统提示），并提取关键事实到长期记忆
     */
    public void clearHistory() {
        // 先从当前对话历史中提取关键事实
        memoryManager.extractAndSaveFacts(conversationHistory);

        GLMClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    /**
     * 将从长期记忆检索到的 MemoryEntry 上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            // 恢复原始 system prompt
            conversationHistory.set(0, GLMClient.Message.system(SYSTEM_PROMPT));
        } else {
            String enrichedPrompt = SYSTEM_PROMPT + "\n" + memoryContext;
            conversationHistory.set(0, GLMClient.Message.system(enrichedPrompt));
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<GLMClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }
}
