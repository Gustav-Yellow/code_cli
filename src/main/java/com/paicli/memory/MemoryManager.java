package com.paicli.memory;

import com.paicli.llm.GLMClient;
import com.paicli.llm.GLMClient.Message;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理长期记忆、上下文压缩和检索，为 Agent 提供简洁的记忆存取接口。
 *
 * 设计说明：对话上下文由 Agent 自己维护的 conversationHistory（List<Message>）承担，
 * 本类不再持有短期记忆。压缩直接作用在传入的 history 上，事实提取也以 history 为输入。
 * 长期记忆仍以 {@link MemoryEntry} 形式持久化。
 */
public class MemoryManager {
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private final TokenBudget tokenBudget;

    // 保留最近 N 轮完整消息不压缩
    private static final int RETAIN_RECENT_ROUNDS = 3;

    public MemoryManager(GLMClient llmClient) {
        this(llmClient, 200000, null);
    }

    public MemoryManager(GLMClient llmClient, int contextWindow) {
        this(llmClient, contextWindow, null);
    }

    public MemoryManager(GLMClient llmClient, int contextWindow, LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextWindow);
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                null,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    /**
     * 检索与查询最相关的长期记忆上下文（用于注入 system prompt）
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    /**
     * 检查并触发对话历史压缩（由 Agent 在 LLM 调用前主动调用）。
     *
     * 直接作用在传入的 history 上：超预算时用 Map-Reduce 摘要旧消息，
     * 替换为 [system, 摘要, ...recent]。压缩结果与日志均在方法内部处理，
     * 调用方无需关心返回值。
     */
    public void compressContextIfNeeded(List<Message> history) {
        if (tokenBudget.isWithinBudget(history)) {
            return;
        }
        System.out.println("📦 上下文超预算，触发压缩...");
        ContextCompressor.CompressionResult result = compressor.compressHistory(history, RETAIN_RECENT_ROUNDS);
        if (result == null) {
            return;
        }
        // 丢弃旧消息前，先从中提取事实到长期记忆，避免知识随摘要永久丢失
        extractAndSaveFacts(result.discarded());
        // 将压缩生成的摘要本身也存入长期记忆，供后续检索（跨会话可见）
        storeCompressionSummary(result.trimmed());
        history.clear();
        history.addAll(result.trimmed());
        System.out.println("   ✓ 已压缩历史对话");
    }

    /**
     * 从对话历史中提取关键事实存入长期记忆。
     *
     * 输入是 {@link Message} 列表；过滤掉 system 角色消息后，
     * 由 {@link ContextCompressor#extractFacts} 喂给 LLM 提取事实文本，
     * 再由 {@link LongTermMemory#store} 包成 {@link MemoryEntry} 落盘。
     */
    public void extractAndSaveFacts(List<Message> history) {
        // 过滤掉 system 消息（指令不属于对话事实）
        List<Message> conversational = history.stream()
                .filter(m -> !"system".equals(m.role()))
                .collect(Collectors.toList());
        if (conversational.isEmpty()) return;

        System.out.println("🧠 提取关键事实到长期记忆...");
        List<String> facts = compressor.extractFacts(conversational, longTermMemory);
        if (!facts.isEmpty()) {
            System.out.println("   提取了 " + facts.size() + " 条事实");
        }
    }

    /**
     * 将压缩生成的摘要存入长期记忆，类型为 SUMMARY，供跨会话检索。
     * trimmed 的 index 1 为压缩生成的 Message.system("[历史对话摘要] ...")。
     */
    private void storeCompressionSummary(List<Message> trimmed) {
        if (trimmed.size() <= 1) return;
        String summaryContent = trimmed.get(1).content();
        // 去掉 "[历史对话摘要] " 前缀，只保留摘要正文
        String content = summaryContent.startsWith("[历史对话摘要] ")
                ? summaryContent.substring("[历史对话摘要] ".length())
                : summaryContent;
        if (content.isBlank()) return;

        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(content)
        );
        longTermMemory.store(summaryEntry);
        System.out.println("   已将压缩摘要存入长期记忆");
    }

    /**
     * 获取记忆系统的整体状态（含对话上下文统计）。
     *
     * @param history 当前会话的对话历史，用于计算动态预算使用率
     */
    public String getSystemStatus(List<Message> history) {
        int messageCount = history.size();
        int usedTokens = tokenBudget.estimateCurrentHistoryTokens(history);
        int maxBudget = tokenBudget.getAvailableForConversation();
        double usagePct = tokenBudget.getBudgetUsagePercent(history);

        return String.format("对话上下文: %d 条消息 | 估算占用 %,d / 最大 %,d tokens (%.1f%%) | 预算剩余 ~%,d\n",
                messageCount, usedTokens, maxBudget, usagePct, maxBudget - usedTokens) +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    /**
     * 获取记忆系统的整体状态（仅长期记忆 + Token 统计，不含对话上下文）。
     */
    public String getSystemStatus() {
        return longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    /**
     * 清空长期记忆（保留 Token 统计和压缩器状态不变）
     */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    // Getter
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
}
