package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

/**
 * LlmClient 工厂，根据配置创建对应的 LLM 客户端实例。
 * <p>
 * 支持按 provider 名称显式创建，也支持从配置中自动检测可用的 provider。
 */
public class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * 按 provider 名称创建客户端。
     *
     * @param provider provider 名称（如 "glm"、"deepseek"）
     * @param config   配置对象
     * @return 对应的 LlmClient 实例，如果 API Key 未配置则返回 null
     */
    public static LlmClient create(String provider, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = provider.toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = config.getModel(normalized);

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            default -> null;
        };
    }

    /**
     * 从配置自动创建客户端：优先使用 defaultProvider，失败则依次尝试已知 provider。
     *
     * @param config 配置对象
     * @return 第一个可用的 LlmClient 实例，如果全部不可用则返回 null
     */
    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"glm", "deepseek"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }
}
