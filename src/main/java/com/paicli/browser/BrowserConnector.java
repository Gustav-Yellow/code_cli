package com.paicli.browser;

/**
 * Agent 可调用的浏览器连接操作接口。
 *
 * 由 Main 在启动时注入 ToolRegistry，使 Agent 可以通过
 * browser_connect / browser_disconnect / browser_status 工具
 * 自动管理浏览器连接状态。
 */
public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
