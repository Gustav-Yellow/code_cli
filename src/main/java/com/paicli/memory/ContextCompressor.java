package com.paicli.memory;

import com.paicli.llm.GLMClient;
import com.paicli.llm.GLMClient.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 上下文压缩器 - 当对话过长时，直接压缩 conversationHistory
 *
 * 压缩策略：
 * 1. Map-Reduce：先将旧消息分片摘要（Map），再合并摘要（Reduce）
 * 2. 保留最近 N 轮完整消息（不压缩），且切分点落在 user 消息上，
 *    保证 assistant(toolCalls)→tool 配对不被切断
 * 3. 摘要作为独立 system 消息插在 history[1]，可跨轮存活并向前滚动
 *
 * 注意：本类只处理 {@link GLMClient.Message}（对话上下文）。
 * 长期记忆的 {@link MemoryEntry} 落盘由 {@link LongTermMemory} 负责，
 * 二者只在 extractFacts 处单向衔接：Message → 事实文本 → MemoryEntry。
 */
public class ContextCompressor {
    private final GLMClient llmClient;
    // 保留最近 N 轮完整消息不压缩
    private final int retainRecentRounds;

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

            各片段摘要：
            %s

            请用中文输出合并摘要，控制在300字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            请从以下对话中提取关键事实，格式为每行一条：
            - 用户偏好和习惯
            - 项目信息（名称、路径、技术栈）
            - 重要决策和约定

            对话内容：
            %s

            请每行一条事实，不要多余解释。
            """;

    public ContextCompressor(GLMClient llmClient) {
        this(llmClient, 3);
    }

    /**
     * @param llmClient          LLM 客户端
     * @param retainRecentRounds 保留最近 N 轮完整消息不压缩
     */
    public ContextCompressor(GLMClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    /**
     * 压缩结果：trimmed 是压缩后的新列表，discarded 是被摘要替换掉的旧消息。
     * discarded 交由调用方在丢弃前提取事实到长期记忆，避免知识随摘要丢失。
     */
    public record CompressionResult(List<Message> trimmed, List<Message> discarded) {}

    /**
     * 压缩对话历史：保留 system + 摘要 + 最近 N 轮，返回压缩结果。
     *
     * 切分点必须落在 user 消息上：从末尾向前数第 retainRecentRounds 个 user 消息作为 recent 窗口起点。
     * 这样 recent 窗口内的 assistant(toolCalls)→tool 配对天然完整，不会被切断导致消息序列非法。
     *
     * @param history 对话历史（history[0] 为 system）
     * @return 压缩结果（含被丢弃的旧消息）；条目不足无需压缩时返回 null
     */
    public CompressionResult compressHistory(List<Message> history, int retainRecentRounds) {
        if (history.size() <= 1) {
            return null;
        }

        int startIdx = findRecentStartIndex(history, retainRecentRounds);
        // old 为空（user 轮数不足）或没有可压缩的旧消息
        if (startIdx <= 1) {
            return null;
        }

        Message systemMsg = history.get(0);
        List<Message> oldEntries = new ArrayList<>(history.subList(1, startIdx));
        List<Message> recentEntries = new ArrayList<>(history.subList(startIdx, history.size()));

        // Map 阶段：旧消息分片摘要
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (chunkSummaries.isEmpty()) {
            return null;
        }

        // Reduce 阶段：合并摘要
        String finalSummary;
        if (chunkSummaries.size() == 1) {
            finalSummary = chunkSummaries.get(0);
        } else {
            finalSummary = reducePhase(chunkSummaries);
        }

        // 组装：[system, 摘要, ...recent]
        List<Message> trimmed = new ArrayList<>();
        trimmed.add(systemMsg);
        // 摘要作为独立 system 消息插在 index 1：
        // 不合入 history[0]，因为 Agent 每轮会重置 index 0；独立消息可跨轮存活，
        // 且下次压缩时它落入 old 段被纳入 map-reduce，自然向前滚动。
        trimmed.add(Message.system("[历史对话摘要] " + finalSummary));
        trimmed.addAll(recentEntries);
        return new CompressionResult(trimmed, oldEntries);
    }

    /**
     * 从对话中提取关键事实，存入长期记忆。
     *
     * 输入是 {@link Message} 列表（对话上下文），产出的事实文本由
     * {@link LongTermMemory#store} 包成 {@link MemoryEntry} 落盘——
     * 这是 Message→MemoryEntry 唯一的衔接点。
     */
    public List<String> extractFacts(List<Message> entries, LongTermMemory longTermMemory) {
        if (entries.isEmpty()) return List.of();

        StringBuilder conversation = new StringBuilder();
        for (Message entry : entries) {
            conversation.append(entry.role()).append(": ")
                    .append(entry.content()).append("\n\n");
        }

        try {
            String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
            List<GLMClient.Message> messages = List.of(
                    GLMClient.Message.system("你是一个信息提取助手，只输出关键事实，不输出其他内容。"),
                    GLMClient.Message.user(prompt)
            );

            GLMClient.ChatResponse response = llmClient.chat(messages, null);
            String factsText = response.content();

            List<String> facts = new ArrayList<>();
            for (String line : factsText.split("\n")) {
                String fact = line.trim();
                // 去掉前缀 "- " 或 "• "
                if (fact.startsWith("- ")) fact = fact.substring(2);
                else if (fact.startsWith("• ")) fact = fact.substring(2);

                if (!fact.isEmpty() && fact.length() > 5) {
                    facts.add(fact);

                    // 存入长期记忆（Message→事实文本→MemoryEntry）
                    MemoryEntry factEntry = new MemoryEntry(
                            "fact-" + UUID.randomUUID().toString().substring(0, 8),
                            fact,
                            MemoryEntry.MemoryType.FACT,
                            null,
                            MemoryEntry.estimateTokens(fact)
                    );
                    longTermMemory.store(factEntry);
                }
            }
            return facts;
        } catch (IOException e) {
            System.err.println("⚠️ 事实提取失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Map 阶段：将旧消息分片，每片独立摘要
     */
    private List<String> mapPhase(List<Message> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5; // 每片 5 条消息
        List<List<Message>> chunks = partition(oldEntries, chunkSize);

        // 以组为单位，生成摘要
        for (List<Message> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            // 将同一组内的所有消息拼接成一个字符串，格式按照 role: content 的方式拼接
            for (Message entry : chunk) {
                chunkText.append(entry.role()).append(": ")
                        .append(entry.content()).append("\n\n");
            }

            // 将本组拼接好的分片交给 AI 生成摘要
            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<GLMClient.Message> messages = List.of(
                        GLMClient.Message.system("你是一个对话摘要助手。"),
                        GLMClient.Message.user(prompt)
                );

                GLMClient.ChatResponse response = llmClient.chat(messages, null);
                // 将本组的摘要添加到 summaries 中
                summaries.add(response.content());
            } catch (IOException e) {
                System.err.println("⚠️ 摘要生成失败: " + e.getMessage());
                // 降级：直接截取前 200 字
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }

        return summaries;
    }

    /**
     * Reduce 阶段：合并多个摘要
     */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);

        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<GLMClient.Message> messages = List.of(
                    GLMClient.Message.system("你是一个摘要合并助手。"),
                    GLMClient.Message.user(prompt)
            );

            GLMClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            System.err.println("⚠️ 摘要合并失败: " + e.getMessage());
            // 降级：直接拼接
            return String.join("；", summaries);
        }
    }

    /**
     * 定位 recent 窗口起点：从末尾向前数第 retainRecentRounds 个 user 消息的下标。
     * 若 user 轮数不足，返回 0（表示无可压缩旧消息）。
     */
    private int findRecentStartIndex(List<Message> history, int retainRecentRounds) {
        int userCount = 0;
        for (int i = history.size() - 1; i >= 1; i--) {
            if ("user".equals(history.get(i).role())) {
                userCount++;
                if (userCount == retainRecentRounds) {
                    return i;
                }
            }
        }
        return 0;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
