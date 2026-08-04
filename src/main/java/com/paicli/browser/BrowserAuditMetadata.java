package com.paicli.browser;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 浏览器操作的审计元数据，随 AuditEntry 一同写入审计日志。
 */
public record BrowserAuditMetadata(
        @JsonProperty("browser_mode") String browserMode,
        Boolean sensitive,
        @JsonProperty("target_url") String targetUrl
) {
    public static BrowserAuditMetadata of(BrowserMode mode, boolean sensitive, String targetUrl) {
        return new BrowserAuditMetadata(mode == null ? null : mode.name().toLowerCase(), sensitive, targetUrl);
    }
}
