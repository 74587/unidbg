package com.anjia.unidbgserver.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class ProcessLifecycle {

    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);
    private static volatile String shutdownReason = "";

    private ProcessLifecycle() {
    }

    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get();
    }

    public static String getShutdownReason() {
        return shutdownReason;
    }

    public static void markShuttingDown(String reason) {
        String r = reason != null ? reason : "";
        if (SHUTTING_DOWN.compareAndSet(false, true)) {
            shutdownReason = r;
            log.warn("进程进入退出中状态: reason={}", r);
        }
    }
}

