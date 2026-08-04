package com.paicli.mcp.resources;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP resource 内容项。
 *
 * 对应 resources/read 返回的 contents 数组中的单条记录。
 * text 非 null 时 isText() 为 true；blob 非 null 时表示二进制内容（base64 编码）。
 */
public record McpResourceContent(String uri, String mimeType, String text, String blob) {
    public static McpResourceContent fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String uri = node.path("uri").asText("");
        String mimeType = node.path("mimeType").asText("");
        String text = node.hasNonNull("text") ? node.path("text").asText("") : null;
        String blob = node.hasNonNull("blob") ? node.path("blob").asText("") : null;
        return new McpResourceContent(uri, mimeType, text, blob);
    }

    public boolean isText() {
        return text != null;
    }
}
