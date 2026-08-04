package com.paicli.hitl;

import java.util.Set;

/**
 * 危险操作识别策略 - 基于静态规则判断哪些工具调用需要人工确认
 *
 * 设计原则：
 * - 读取类操作（read_file、list_dir、search_code）不需要确认，无副作用
 * - 写入/执行类操作（write_file、execute_command）需要确认，有潜在破坏性
 * - create_project 属于写入操作，默认需要确认
 */
public class ApprovalPolicy {

    // 需要人工确认的工具集合
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    private ApprovalPolicy() {
    }

    /**
     * 判断该工具调用是否需要人工确认。
     * 内置危险工具 + 所有 mcp__ 前缀的 MCP 工具均需审批。
     */
    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    /**
     * 获取危险等级描述
     */
    public static String getDangerLevel(String toolName) {
        if (isMcpTool(toolName)) {
            return "🟡 MCP 外部工具";
        }
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file" -> "🟡 中危";
            case "create_project" -> "🟡 中危";
            default -> "🟢 安全";
        };
    }

    /**
     * 获取危险操作的风险说明
     */
    public static String getRiskDescription(String toolName) {
        if (isMcpTool(toolName)) {
            return "MCP 外部工具调用，来自第三方 MCP server，操作范围不可提前预知";
        }
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> "安全的只读操作";
        };
    }

    /**
     * 获取所有需要审批的内置工具名集合（用于测试和展示）。
     * 注意：MCP 工具（mcp__ 前缀）不在这个集合内，但 requiresApproval 也会对其返回 true。
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    private static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }
}
