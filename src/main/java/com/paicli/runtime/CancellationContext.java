package com.paicli.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消上下文 —— 管理当前 Agent 运行的生命周期取消信号。
 *
 * 使用 InheritableThreadLocal 确保子线程（如工具执行线程池）也能感知取消。
 * 同时维护一份全局 AtomicReference 作为 fallback（线程池中 ThreadLocal 未继承时使用）。
 */
public final class CancellationContext {
    private static final AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    /** 开始一次新的 Agent run，返回 token 供取消方调用 cancel() */
    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);
        return token;
    }

    /** 获取当前线程关联的取消令牌，优先 ThreadLocal，其次全局 fallback */
    public static CancellationToken current() {
        CancellationToken token = LOCAL.get();
        return token == null ? CURRENT.get() : token;
    }

    /** 检查当前运行是否已被取消 */
    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    /** 清理取消令牌（run 结束后调用） */
    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
