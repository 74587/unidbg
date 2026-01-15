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

    public void recordSuccess() {
        errorCount.set(0);
        windowStartMs = 0L;
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

        log.error("连续异常达到阈值，准备退出进程触发重启: count={}, threshold={}, reason={}", count, threshold, reason);
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(2);
        }, "auto-restart").start();
    }
}

