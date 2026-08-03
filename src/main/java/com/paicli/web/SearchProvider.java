package com.paicli.web;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎抽象。
 *
 * <p>当前实现：
 * <ul>
 *   <li>{@link ZhipuSearchProvider}：智谱 Web Search API，复用 GLM_API_KEY，国内首选</li>
 *   <li>{@link SerpApiSearchProvider}：商业聚合 API（骨架，待配置 API Key 后启用）</li>
 *   <li>{@link SearxngSearchProvider}：开源元搜索引擎（骨架，待配置 SearXNG 实例后启用）</li>
 * </ul>
 */
public interface SearchProvider {

    /** @return provider 名称（如 "zhipu"、"serpapi"、"searxng"），用于错误信息和日志 */
    String name();

    /** @return 是否可用（如必要 API Key 已配置 / 服务地址可访问） */
    boolean isReady();

    /** @return 当 {@link #isReady()} 为 false 时给用户的提示 */
    String unavailableHint();

    /**
     * 执行搜索。
     *
     * @param query 搜索关键词，不可为 null/blank
     * @param topK  期望返回结果数量，实现可酌情截断
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
