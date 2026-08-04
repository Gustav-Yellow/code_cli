package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具描述符。
 *
 * 包含原始工具名和服务端名，以及带命名空间的工具名（mcp__{server}__{tool}）。
 */
public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema
) {
    /** 生成命名空间工具名：mcp__{serverName}__{toolName} */
    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
