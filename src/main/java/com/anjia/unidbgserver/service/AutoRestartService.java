package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRestartService {

    private final FQDownloadProperties downloadProperties;

    private final AtomicInteger errorCount = new AtomicInteger(0);
    private volatile long windowStartMs = 0L;
    private volatile long lastRestartAtMs = 0L;
    private volatile boolean restarting = false;

    public void recordSuccess() {
        errorCount.set(0);
        windowStartMs = 0L;
        restarting = false;
    }

    public void recordFailure(String reason) {
        if (!downloadProperties.isAutoRestartEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, downloadProperties.getAutoRestartWindowMs());
        long minIntervalMs = Math.max(0L, downloadProperties.getAutoRestartMinIntervalMs());
        int threshold = Math.max(1, downloadProperties.getAutoRestartErrorThreshold());

        long start = windowStartMs;
        if (start == 0L || now - start > windowMs) {
            windowStartMs = now;
            errorCount.set(0);
        }

        int count = errorCount.incrementAndGet();
        if (count < threshold) {
            return;
        }

        if (now - lastRestartAtMs < minIntervalMs) {
            return;
        }
        lastRestartAtMs = now;
        if (restarting) {
            return;
        }
        restarting = true;

        log.error("连续异常达到阈值，准备退出进程触发重启: count={}, threshold={}, reason={}", count, threshold, reason);
        int exitCode = 2;
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                System.exit(exitCode);
            } catch (Throwable t) {
                Runtime.getRuntime().halt(exitCode);
            }
        }, "auto-restart-exit").start();

        long forceHaltAfterMs = Math.max(0L, downloadProperties.getAutoRestartForceHaltAfterMs());
        if (forceHaltAfterMs > 0) {
            new Thread(() -> {
                try {
                    Thread.sleep(forceHaltAfterMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                // 如果 System.exit 因 shutdown hook 卡住，这里会强制结束，保证 Docker/systemd 能拉起
                log.error("System.exit 未能在期望时间内退出，强制 halt 结束进程: exitCode={}, waitedMs={}", exitCode, forceHaltAfterMs);
                Runtime.getRuntime().halt(exitCode);
            }, "auto-restart-halt").start();
        }
    }
}
