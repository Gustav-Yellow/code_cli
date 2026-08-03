package com.paicli.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从长期记忆中检索最相关的信息
 *
 * 检索策略：
 * 1. 关键词匹配：直接匹配内容中的关键词
 * 2. 纯相关度排序：长期事实不因"老的"被降权，故不使用时间衰减
 *
 * 设计说明：短期对话上下文已由 conversationHistory 直接发给 LLM，
 * 无需在此重复检索；本类只负责跨会话的长期记忆检索。
 */
public class MemoryRetriever {
    private final LongTermMemory longTermMemory;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    /**
     * 检索与查询最相关的长期记忆
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度排序的记忆列表
     */
    public List<MemoryEntry> retrieveFromLongTerm(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        for (MemoryEntry entry : longTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        // 按分数降序排序
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文：将相关长记忆组装成文本，用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> relevant = retrieveFromLongTerm(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    /**
     * 计算记忆条目与查询的相关度分数
     */
    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 1. 精确匹配加分
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }

        // 2. 关键词匹配
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;

        // 长期记忆不做时间衰减：事实不会因为"老的"就变得不相关
        return (double) matchedWords / queryWords.size();
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
