package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRestartService {

    private final FQDownloadProperties downloadProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;

    private final AtomicInteger errorCount = new AtomicInteger(0);
    private volatile long windowStartMs = 0L;
    private volatile long lastRestartAtMs = 0L;
    private volatile long lastSelfHealAtMs = 0L;
    private volatile boolean restarting = false;

    public void recordSuccess() {
        errorCount.set(0);
        windowStartMs = 0L;
        restarting = false;
    }

    public boolean isRestarting() {
        return restarting || ProcessLifecycle.isShuttingDown();
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

        if (restarting) {
            return;
        }

        if (trySelfHeal(reason, now, count, threshold)) {
            return;
        }

        if (now - lastRestartAtMs < minIntervalMs) {
            return;
        }
        lastRestartAtMs = now;

        restarting = true;
        ProcessLifecycle.markShuttingDown("AUTO_RESTART:" + (reason != null ? reason : ""));

        log.error("连续异常达到阈值，准备退出进程触发重启: count={}, threshold={}, reason={}", count, threshold, reason);
        int exitCode = 2;
        long exitDelayMs = Math.max(0L, downloadProperties.getAutoRestartExitDelayMs());
        new Thread(() -> {
            try {
                Thread.sleep(exitDelayMs);
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

    private boolean trySelfHeal(String reason, long now, int count, int threshold) {
        if (!downloadProperties.isAutoRestartSelfHealEnabled()) {
            return false;
        }

        long cooldownMs = Math.max(0L, downloadProperties.getAutoRestartSelfHealCooldownMs());
        if (cooldownMs > 0 && now - lastSelfHealAtMs < cooldownMs) {
            return false;
        }
        lastSelfHealAtMs = now;

        log.warn("连续异常达到阈值，优先尝试自愈（重置 signer / 切换设备）: count={}, threshold={}, reason={}", count, threshold, reason);

        // 自愈逻辑放后台线程，避免阻塞当前业务线程；失败也不影响后续退回到 auto-restart。
        new Thread(() -> {
            try {
                FQEncryptServiceWorker.requestGlobalReset("AUTO_SELF_HEAL:" + (reason != null ? reason : ""));
            } catch (Throwable t) {
                log.warn("自愈：请求重置 signer 失败", t);
            }

            try {
                registerKeyService.clearCache();
            } catch (Throwable t) {
                log.warn("自愈：清除 registerkey 缓存失败", t);
            }

            try {
                deviceRotationService.forceRotate("AUTO_SELF_HEAL:" + (reason != null ? reason : ""));
            } catch (Throwable t) {
                log.warn("自愈：切换设备失败", t);
            }
        }, "auto-restart-self-heal").start();

        recordSuccess();
        return true;
    }
}
