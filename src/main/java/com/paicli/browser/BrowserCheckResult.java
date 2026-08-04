package com.paicli.browser;

/**
 * BrowserGuard 的检查结果，描述一次浏览器工具调用的策略判定。
 */
public record BrowserCheckResult(
        boolean blocked,
        String reason,
        boolean requiresPerCallApproval,
        String sensitiveNotice,
        BrowserAuditMetadata metadata
) {
    public static BrowserCheckResult allow(BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(false, null, false, null, metadata);
    }

    public static BrowserCheckResult requireApproval(String notice, BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(false, null, true, notice, metadata);
    }

    public static BrowserCheckResult block(String reason, BrowserAuditMetadata metadata) {
        return new BrowserCheckResult(true, reason, false, null, metadata);
    }
}
