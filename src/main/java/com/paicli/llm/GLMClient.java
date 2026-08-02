package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GLM 模型客户端
 * <p>
 * 封装智谱 AI（bigmodel.cn）chat completions 接口的调用逻辑，支持普通对话与工具调用（function calling）。
 * 内部使用 OkHttp 发送 HTTP 请求，使用 Jackson 进行 JSON 序列化与反序列化。
 */
public class GLMClient {
    private static final String API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String MODEL = "glm-5.2";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String apiKey;

    public GLMClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 发送聊天请求（支持工具调用 / function calling）
     * <p>
     * 将消息列表与可选的工具定义组装成请求体，调用 GLM 接口，并解析返回的文本内容、
     * 工具调用结果以及 token 用量。
     *
     * @param messages 对话消息列表，包含 system / user / assistant / tool 等角色
     * @param tools    可供模型调用的工具定义列表，为 null 或空表示不启用工具调用
     * @return 模型响应，封装了角色、文本内容、工具调用及 token 用量
     * @throws IOException 当网络请求失败或 HTTP 状态码非 2xx 时抛出
     */
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        /**
         * {
         *   "model": "glm-5.2",
         *   "messages": [
         *     {
         *       "role": "system",
         *       "content": "你是一个终端编码助手。"
         *     },
         *     {
         *       "role": "user",
         *       "content": "列出当前目录的 Java 文件"
         *     },
         *     {
         *       "role": "assistant",
         *       "content": "我来帮你查看。",
         *       "tool_calls": [
         *         {
         *           "id": "call_abc123",
         *           "type": "function",
         *           "function": {
         *             "name": "list_files",
         *             "arguments": "{\"directory\":\".\",\"extension\":\".java\"}"
         *           }
         *         }
         *       ]
         *     },
         *     {
         *       "role": "tool",
         *       "content": "GLMClient.java\nPaicliApplication.java",
         *       "tool_call_id": "call_abc123"
         *     }
         *   ],
         *   "tools": [
         *     {
         *       "type": "function",
         *       "function": {
         *         "name": "list_files",
         *         "description": "列出指定目录下的文件",
         *         "parameters": {
         *           "type": "object",
         *           "properties": {
         *             "directory": { "type": "string" },
         *             "extension": { "type": "string" }
         *           },
         *           "required": ["directory"]
         *         }
         *       }
         *     }
         *   ]
         * }
         */
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", MODEL);

        // 添加消息
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            // 创建当前请求消息的 Json 对象，最外层是两个核心字段，role 和 content
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            // 添加工具调用信息
            // 仅当角色为 assistant 发起工具调用时（且 msg.toolCalls 不为空）
            // 向 msgNode 中添加一个新的 tool_calls 数据
            /**
             *  {
             *       "role": "assistant",
             *       "content": "我来帮你查看。",
             *       "tool_calls": [
             *         {
             *           "id": "call_abc123",
             *           "type": "function",
             *           "function": {
             *             "name": "list_files",
             *             "arguments": "{\"directory\":\".\",\"extension\":\".java\"}"
             *           }
             *         }
             *       ]
             *  }
             */
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 添加工具调用结果
            /**
             *  {
             *       "role": "tool",
             *       "content": "GLMClient.java\nPaicliApplication.java",
             *       "tool_call_id": "call_abc123"
             *  }
             */
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 添加工具定义，仅当 tools 不为空时添加
        /**
         * "tools": [
         *     {
         *       "type": "function",
         *       "function": {
         *         "name": "list_files",
         *         "description": "列出指定目录下的文件",
         *         "parameters": {
         *           "type": "object",
         *           "properties": {
         *             "directory": { "type": "string" },
         *             "extension": { "type": "string" }
         *           },
         *           "required": ["directory"]
         *         }
         *       }
         *     }
         *   ]
         */
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );

        // HTTP 请求封装
        /**
         * 方法：POST
         * URL：https://open.bigmodel.cn/api/paas/v4/chat/completions
         * Headers：
         * Authorization: Bearer <apiKey>
         * Content-Type: application/json
         * Body：上面 JSON 的字符串形式（第 99 行 requestBody.toString()）
         */
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        // 执行请求，并且获取响应的结果
        // 响应的 Json 结构
        /**
         * {
         *   "choices": [
         *     {
         *       "message": {
         *         "role": "assistant",
         *         "content": "...",
         *         "tool_calls": [
         *           {
         *             "id": "...",
         *             "function": {
         *               "name": "...",
         *               "arguments": "..."
         *             }
         *           }
         *         ]
         *       }
         *     }
         *   ],
         *   "usage": {
         *     "prompt_tokens": 123,
         *     "completion_tokens": 45
         *   }
         * }
         */
        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            String responseBody = responseBodyObj.string();
            JsonNode root = mapper.readTree(responseBody);

            // 解析响应
            JsonNode choice = root.path("choices").get(0);
            JsonNode message = choice.path("message");

            String role = message.path("role").asText();
            String content = message.path("content").asText();
            String reasoningContent = message.path("reasoning_content").asText();

            // 解析工具调用
            List<ToolCall> toolCalls = null;
            if (message.has("tool_calls") && message.path("tool_calls").isArray()) {
                toolCalls = new ArrayList<>();
                for (JsonNode tc : message.path("tool_calls")) {
                    toolCalls.add(new ToolCall(
                            tc.path("id").asText(),
                            new ToolCall.Function(
                                    tc.path("function").path("name").asText(),
                                    tc.path("function").path("arguments").asText()
                            )
                    ));
                }
            }

            // 解析token使用
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt();
            int outputTokens = usage.path("completion_tokens").asInt();

            return new ChatResponse(role, content, reasoningContent, toolCalls, inputTokens, outputTokens);
        }
    }

    /**
     * 对话消息记录，对应请求体中 messages 数组的一项
     *
     * @param role        消息角色：system / user / assistant / tool
     * @param content     消息文本内容
     * @param toolCalls   assistant 触发的工具调用列表（仅 assistant 角色使用）
     * @param toolCallId  工具调用结果对应的调用 ID（仅 tool 角色使用，用于回传结果）
     */
    // 记录定义
    public record Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls, String toolCallId) {
        /**
         * 简化构造：仅包含角色与内容，不携带工具调用信息
         */
        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        /** 创建 system 角色消息（设定模型行为/人设） */
        public static Message system(String content) {
            return new Message("system", content);
        }

        /** 创建 user 角色消息（用户输入） */
        public static Message user(String content) {
            return new Message("user", content);
        }

        /** 创建 assistant 角色消息（模型纯文本回复） */
        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        /**
         * 创建 assistant 角色消息（模型纯文本回复）
         * @param reasoningContent 推理内容
         * @param content 内容
         * @return 消息
         */
        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        /** 创建 assistant 角色消息（模型发起工具调用时使用，可同时携带文本与工具调用） */
        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        /**
         * 创建 assistant 角色消息（模型发起工具调用时使用，可同时携带文本与工具调用）
         * @param reasoningContent 推理内容
         * @param content 内容
         * @param toolCalls 工具调用
         * @return 消息
         */
        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        /** 创建 tool 角色消息（回传工具调用结果，需关联对应的调用 ID） */
        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }
    }

    /**
     * 工具调用记录，表示模型决定调用某个工具
     *
     * @param id       本次调用的唯一标识，用于后续回传结果时关联
     * @param function 被调用的函数信息（名称与参数 JSON 字符串）
     */
    public record ToolCall(String id, Function function) {
        /**
         * 函数信息
         *
         * @param name      函数名称
         * @param arguments 函数参数，JSON 字符串形式
         */
        public record Function(String name, String arguments) {}
    }

    /**
     * 工具定义记录，描述可供模型调用的工具
     *
     * @param name        工具名称
     * @param description 工具功能描述，供模型判断是否调用
     * @param parameters  工具参数的 JSON Schema 定义
     */
    public record Tool(String name, String description, JsonNode parameters) {}

    /**
     * 模型响应记录，封装一次 chat 请求的返回结果
     *
     * @param role         消息角色，通常为 assistant
     * @param content      模型输出的文本内容
     * @param toolCalls    模型触发的工具调用列表，无调用时为 null
     * @param inputTokens  输入 token 用量（prompt_tokens）
     * @param outputTokens 输出 token 用量（completion_tokens）
     */
    public record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                               int inputTokens, int outputTokens) {

        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens);
        }

        /** 判断本次响应是否包含工具调用 */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

}
