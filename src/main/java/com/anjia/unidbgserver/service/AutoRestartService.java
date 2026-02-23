package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.anjia.unidbgserver.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AutoRestartService {

    private static final Logger log = LoggerFactory.getLogger(AutoRestartService.class);
    private static final String REASON_PREFIX_AUTO_RESTART = "AUTO_RESTART:";
    private static final String REASON_PREFIX_AUTO_SELF_HEAL = "AUTO_SELF_HEAL:";

    private final FQDownloadProperties downloadProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;

    public AutoRestartService(
        FQDownloadProperties downloadProperties,
        FQDeviceRotationService deviceRotationService,
        FQRegisterKeyService registerKeyService
    ) {
        this.downloadProperties = downloadProperties;
        this.deviceRotationService = deviceRotationService;
        this.registerKeyService = registerKeyService;
    }

    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicBoolean healing = new AtomicBoolean(false);
    private volatile long windowStartMs = 0L;
    private volatile long lastRestartAtMs = 0L;
    private volatile long lastSelfHealAtMs = 0L;
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public void recordSuccess() {
        errorCount.set(0);
        windowStartMs = 0L;
        restarting.set(false);
    }

    public void recordFailure(String reason) {
        if (!downloadProperties.isAutoRestartEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long windowMs = autoRestartWindowMs();
        long minIntervalMs = autoRestartMinIntervalMs();
        int threshold = autoRestartThreshold();

        long start = windowStartMs;
        if (start == 0L || now - start > windowMs) {
            windowStartMs = now;
            errorCount.set(0);
        }

        int count = errorCount.incrementAndGet();
        if (count < threshold) {
            return;
        }

        if (restarting.get()) {
            return;
        }

        if (trySelfHeal(reason, now, count, threshold)) {
            return;
        }

        if (now - lastRestartAtMs < minIntervalMs) {
            return;
        }
        lastRestartAtMs = now;

        // 使用 CAS 确保只有一个线程能进入重启流程
        if (!restarting.compareAndSet(false, true)) {
            return;
        }

        ProcessLifecycle.markShuttingDown(prefixedReason(REASON_PREFIX_AUTO_RESTART, reason));

        log.error("连续异常达到阈值，准备退出进程触发重启: count={}, threshold={}, reason={}", count, threshold, reason);
        int exitCode = 2;
        long exitDelayMs = autoRestartExitDelayMs();
        startNamedThread("auto-restart-exit", () -> {
            sleepQuietly(exitDelayMs);
            try {
                System.exit(exitCode);
            } catch (Throwable t) {
                Runtime.getRuntime().halt(exitCode);
            }
        });

        long forceHaltAfterMs = autoRestartForceHaltAfterMs();
        if (forceHaltAfterMs > 0) {
            startNamedThread("auto-restart-halt", () -> {
                sleepQuietly(forceHaltAfterMs);
                // 如果 System.exit 因 shutdown hook 卡住，这里会强制结束，保证 Docker/systemd 能拉起
                log.error("System.exit 未能在期望时间内退出，强制 halt 结束进程: exitCode={}, waitedMs={}", exitCode, forceHaltAfterMs);
                Runtime.getRuntime().halt(exitCode);
            });
        }
    }

    private boolean trySelfHeal(String reason, long now, int count, int threshold) {
        if (!downloadProperties.isAutoRestartSelfHealEnabled()) {
            return false;
        }

        if (healing.get()) {
            return true;
        }

        long cooldownMs = selfHealCooldownMs();
        if (cooldownMs > 0 && now - lastSelfHealAtMs < cooldownMs) {
            return false;
        }
        if (!healing.compareAndSet(false, true)) {
            return true;
        }
        lastSelfHealAtMs = now;

        log.warn("连续异常达到阈值，优先尝试自愈（重置 signer / 切换设备）: count={}, threshold={}, reason={}", count, threshold, reason);
        String selfHealReason = prefixedReason(REASON_PREFIX_AUTO_SELF_HEAL, reason);

        // 自愈逻辑放后台线程，避免阻塞当前业务线程；失败也不影响后续退回到 auto-restart。
        startNamedThread("auto-restart-self-heal", () -> {
            try {
                runSelfHealStep("请求重置 signer 失败", () -> FQEncryptServiceWorker.requestGlobalReset(selfHealReason));
                runSelfHealStep("失效当前 registerkey 失败", registerKeyService::invalidateCurrentKey);
                runSelfHealStep("切换设备失败", () -> deviceRotationService.forceRotate(selfHealReason));
            } finally {
                healing.set(false);
                recordSuccess();
            }
        });

        return true;
    }

    private static String prefixedReason(String prefix, String reason) {
        return prefix + Texts.nullToEmpty(reason);
    }

    private int autoRestartThreshold() {
        return Math.max(1, downloadProperties.getAutoRestartErrorThreshold());
    }

    private long autoRestartWindowMs() {
        return Math.max(1L, downloadProperties.getAutoRestartWindowMs());
    }

    private long autoRestartMinIntervalMs() {
        return Math.max(0L, downloadProperties.getAutoRestartMinIntervalMs());
    }

    private long autoRestartExitDelayMs() {
        return Math.max(0L, downloadProperties.getAutoRestartExitDelayMs());
    }

    private long autoRestartForceHaltAfterMs() {
        return Math.max(0L, downloadProperties.getAutoRestartForceHaltAfterMs());
    }

    private long selfHealCooldownMs() {
        return Math.max(0L, downloadProperties.getAutoRestartSelfHealCooldownMs());
    }

    private static void runSelfHealStep(String message, Runnable action) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            log.warn("自愈：" + message, t);
        }
    }

    private static void startNamedThread(String name, Runnable task) {
        new Thread(task, name).start();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
