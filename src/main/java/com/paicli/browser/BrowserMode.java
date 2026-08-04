package com.paicli.browser;

/**
 * 浏览器运行模式。
 *
 * ISOLATED — 每次启动临时 user-data-dir，无登录态；
 * SHARED — 复用用户已登录的 Chrome，带登录态。
 */
public enum BrowserMode {
    ISOLATED,
    SHARED
}
