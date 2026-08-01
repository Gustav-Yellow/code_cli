package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 自动去重（基于内容相似度）
 * 4. 定期持久化到磁盘
 */
public class LongTermMemory implements Memory {
    // 存储目录配置
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    // 环境变量名称
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    // 存储文件名称
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);

        // 启动时加载已有记忆
        loadFromDisk();
    }

    /**
     * 存储一条记忆条目
     * @param entry 记忆条目
     */
    @Override
    public void store(MemoryEntry entry) {
        // 去重检查：如果已存在内容完全相同的条目，跳过
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
    }

    /**
     * 获取一条记忆条目
     * @param id 记忆条目 ID
     * @return 记忆条目
     */
    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * 搜索记忆条目
     * @param query 搜索关键词
     * @param limit 返回条数限制
     * @return 匹配的记忆条目
     */
    @Override
    public List<MemoryEntry> search(String query, int limit) {
        // 调用 tokenizer 对 query 进行分词
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        // 过滤长期记忆 entries 中的条目，如果条目内容或元信息中包含 queryTokens 中的任意一个词，则保留
        return entries.values().stream()
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit) // 限制返回的条目条数
                .collect(Collectors.toList());
    }

    /**
     * 获取所有记忆条目
     * @return 所有记忆条目
     */
    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    /**
     * 删除一条记忆条目
     * @param id 记忆条目 ID
     * @return 是否成功删除
     */
    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        // 如果存在，则从 tokenCounter 中减去 tokenCount
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    /**
     * 清空所有记忆条目
     */
    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    /**
     * 获取当前 token 数量
     */
    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    /**
     * 获取当前记忆条目数量
     */
    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 将当前 entries 中的内容持久化到磁盘
     * 保存的格式是 JSON 数组，每个元素是一个 Map，包含 id、content、type、timestamp、metadata、tokenCount
     */
    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            System.err.println("⚠️ 长期记忆持久化失败: " + e.getMessage());
        }
    }

    /**
     * 解析存储目录
     */
    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            System.out.println("📂 加载了 " + entries.size() + " 条长期记忆");
        } catch (IOException e) {
            System.err.println("⚠️ 加载长期记忆失败: " + e.getMessage());
        }
    }

    /**
     * 将 MemoryEntry 转换为 Map，用于持久化。Map 的 key 为字符串存放的是 id、content、type、timestamp、metadata、tokenCount
     * @param entry 记忆条目
     * @return 转换后的 Map
     */
    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    /**
     * 将 Map 转换为 MemoryEntry。SuppressWarnings 的作用是忽略警告，因为 map 的 key 是字符串，所以需要强制转换为字符串
     * @param map 存储的 Map
     * @return 转换后的 MemoryEntry
     */
    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
