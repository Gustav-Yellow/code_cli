package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP 传输层抽象。
 *
 * 每种传输方式（stdio 子进程、Streamable HTTP）实现此接口，
 * 上层 {@link com.paicli.mcp.jsonrpc.JsonRpcClient} 只依赖此接口收发 JSON。
 */
public interface McpTransport extends AutoCloseable {
    /** 发送一条 JSON-RPC 消息 */
    void send(JsonNode message) throws IOException;

    /** 注册消息接收回调（transport 读到消息后通过此回调分发） */
    void onReceive(Consumer<JsonNode> listener);

    /** 返回最近 N 行 stderr（用于 /mcp logs），默认空 */
    default List<String> stderrLines() {
        return List.of();
    }

    /** 子进程 PID（stdio transport 有值），默认 null */
    default Long processId() {
        return null;
    }

    /** 传输名称，如 "stdio" / "http" */
    default String transportName() {
        return "unknown";
    }

    @Override
    void close();
}
