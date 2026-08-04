package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.jsonrpc.JsonRpcException;
import com.paicli.mcp.protocol.McpCallToolRequest;
import com.paicli.mcp.protocol.McpCallToolResult;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpSchemaSanitizer;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP 协议客户端。
 *
 * 封装 initialize → tools/list → tools/call 的标准 MCP 交互流程，
 * 以及 resources/list、resources/read、prompts/list 等高级能力。
 */
public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    /** MCP 握手：initialize → 解析 capabilities → initialized 通知 */
    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), 30);
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        return serverCapabilities.has("prompts");
    }

    /** 获取 server 提供的工具列表 */
    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
            descriptors.add(new McpToolDescriptor(
                    serverName,
                    name,
                    McpToolDescriptor.namespaced(serverName, name),
                    description,
                    schema
            ));
        }
        return descriptors;
    }

    /** 调用 MCP 工具，返回格式化为 LLM 可读的字符串 */
    public String callTool(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        String formatted = callResult.formatForLlm();
        if (callResult.isError()) {
            return "MCP 工具返回错误: " + formatted;
        }
        return formatted;
    }

    // ---- resources ----

    /** 列出 server 暴露的 resources，对不支持该方法（-32601）的 server 返回空列表 */
    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    /** 读取指定 URI 的 resource 内容 */
    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }
        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    /** 订阅 resource 变更通知 */
    public void subscribeResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        rpc.request("resources/subscribe", params, 30);
    }

    // ---- prompts ----

    /** 列出 server 暴露的 prompt 模板，对不支持该方法（-32601）的 server 返回空列表 */
    public List<String> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    // ---- notifications ----

    /** 注册被动通知监听器（server → client 的 JSON-RPC 通知） */
    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    // ---- 静态格式化方法 ----

    /** 格式化 resource 列表为人类可读的展示文本 */
    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 格式化 resource 内容为 LLM 可读的 XML 块 */
    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ---- transport 委托 ----

    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    public Long processId() {
        return transport.processId();
    }

    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        rpc.close();
    }
}
