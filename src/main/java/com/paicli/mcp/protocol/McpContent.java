package com.paicli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MCP tools/call 返回的 content 数组中的单条内容项。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpContent(String type, String text) {
}
