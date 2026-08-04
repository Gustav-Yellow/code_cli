package com.paicli.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌 —— 线程安全的取消信号。
 *
 * 一旦 cancel() 被调用，isCancelled() 将永久返回 true。
 * 同时检查线程中断标志，以兼容 ExecutorService.shutdownNow() 等中断路径。
 */
public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
