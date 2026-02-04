package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.UnidbgProperties;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.github.unidbg.worker.Worker;
import com.github.unidbg.worker.WorkerPool;
import com.github.unidbg.worker.WorkerPoolFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Service("fqEncryptWorker")
public class FQEncryptServiceWorker extends Worker {

    private static final AtomicLong RESET_EPOCH = new AtomicLong(0L);
    private static final AtomicLong LAST_RESET_REQUEST_AT_MS = new AtomicLong(0L);
    private static volatile long RESET_COOLDOWN_MS = 2000L;

    private UnidbgProperties unidbgProperties;
    private WorkerPool pool;
    private FQEncryptService fqEncryptService;
    private long localResetEpoch = 0L;

    @Autowired
    public void init(UnidbgProperties unidbgProperties) {
        this.unidbgProperties = unidbgProperties;
        if (unidbgProperties != null) {
            RESET_COOLDOWN_MS = Math.max(0L, unidbgProperties.getResetCooldownMs());
        }
    }

    public FQEncryptServiceWorker() {
        super(WorkerPoolFactory.create(FQEncryptServiceWorker::new, Runtime.getRuntime().availableProcessors()));
    }

    public FQEncryptServiceWorker(WorkerPool pool) {
        super(pool);
    }

    @Autowired
    public FQEncryptServiceWorker(UnidbgProperties unidbgProperties,
                                    @Value("${spring.task.execution.pool.core-size:4}") int poolSize) {
        super(WorkerPoolFactory.create(FQEncryptServiceWorker::new, Runtime.getRuntime().availableProcessors()));
        this.unidbgProperties = unidbgProperties;
        if (unidbgProperties != null) {
            RESET_COOLDOWN_MS = Math.max(0L, unidbgProperties.getResetCooldownMs());
        }
        if (this.unidbgProperties.isAsync()) {
            // 修复：使用配置的线程池大小，确保至少为1
            int actualPoolSize = Math.max(1, poolSize);
            pool = WorkerPoolFactory.create(pool -> new FQEncryptServiceWorker(unidbgProperties.isDynarmic(),
                unidbgProperties.isVerbose(), unidbgProperties.getApkPath(), unidbgProperties.getApkClasspath(), pool), actualPoolSize);
            log.info("FQ签名服务线程池大小为:{}", actualPoolSize);
        } else {
            this.fqEncryptService = new FQEncryptService(unidbgProperties);
        }
    }

    public FQEncryptServiceWorker(boolean dynarmic, boolean verbose, String apkPath, String apkClasspath, WorkerPool pool) {
        super(pool);
        this.unidbgProperties = new UnidbgProperties();
        unidbgProperties.setDynarmic(dynarmic);
        unidbgProperties.setVerbose(verbose);
        unidbgProperties.setApkPath(apkPath);
        unidbgProperties.setApkClasspath(apkClasspath);
        log.info("FQ签名服务 - 是否启用动态引擎:{}, 是否打印详细信息:{}", dynarmic, verbose);
        this.fqEncryptService = new FQEncryptService(unidbgProperties);
    }

    public static long requestGlobalReset(String reason) {
        if (ProcessLifecycle.isShuttingDown()) {
            return RESET_EPOCH.get();
        }

        long now = System.currentTimeMillis();
        long cooldown = RESET_COOLDOWN_MS;
        if (cooldown > 0) {
            long last = LAST_RESET_REQUEST_AT_MS.get();
            if (last > 0 && now - last < cooldown) {
                return RESET_EPOCH.get();
            }
            LAST_RESET_REQUEST_AT_MS.set(now);
        }

        long epoch = RESET_EPOCH.incrementAndGet();
        log.warn("请求重置 FQ signer（unidbg）: epoch={}, reason={}", epoch, reason);
        return epoch;
    }

    /**
     * 异步生成FQ签名headers
     *
     * @param url 请求的URL
     * @param headers 请求头信息
     * @return 包含签名信息的CompletableFuture
     */
    @SneakyThrows
    public CompletableFuture<Map<String, String>> generateSignatureHeaders(String url, String headers) {
        FQEncryptServiceWorker worker;
        Map<String, String> result;

        if (this.unidbgProperties.isAsync()) {
            // 异步模式使用工作池
            while (true) {
                if ((worker = pool.borrow(2, TimeUnit.SECONDS)) == null) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                result = worker.doWork(url, headers);
                pool.release(worker);
                break;
            }
        } else {
            // 同步模式直接使用当前实例
            synchronized (this) {
                result = this.doWork(url, headers);
            }
        }

        return CompletableFuture.completedFuture(result);
    }

    /**
     * 异步生成FQ签名headers (重载方法，支持Map格式的headers)
     *
     * @param url 请求的URL
     * @param headerMap 请求头的Map
     * @return 包含签名信息的CompletableFuture
     */
    @SneakyThrows
    public CompletableFuture<Map<String, String>> generateSignatureHeaders(String url, Map<String, String> headerMap) {
        FQEncryptServiceWorker worker;
        Map<String, String> result;

        if (this.unidbgProperties.isAsync()) {
            // 异步模式使用工作池
            while (true) {
                if ((worker = pool.borrow(2, TimeUnit.SECONDS)) == null) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                result = worker.doWorkWithMap(url, headerMap);
                pool.release(worker);
                break;
            }
        } else {
            // 同步模式直接使用当前实例
            synchronized (this) {
                result = this.doWorkWithMap(url, headerMap);
            }
        }

        return CompletableFuture.completedFuture(result);
    }

    /**
     * 执行签名生成工作 (字符串格式headers)
     */
    private Map<String, String> doWork(String url, String headers) {
        ensureResetUpToDate();
        return fqEncryptService.generateSignatureHeaders(url, headers);
    }

    /**
     * 执行签名生成工作 (Map格式headers)
     */
    private Map<String, String> doWorkWithMap(String url, Map<String, String> headerMap) {
        ensureResetUpToDate();
        return fqEncryptService.generateSignatureHeaders(url, headerMap);
    }

    private void ensureResetUpToDate() {
        if (ProcessLifecycle.isShuttingDown()) {
            return;
        }
        long epoch = RESET_EPOCH.get();
        if (epoch == localResetEpoch) {
            return;
        }
        if (fqEncryptService != null) {
            fqEncryptService.reset("RESET_EPOCH:" + epoch);
        }
        localResetEpoch = epoch;
    }

    @SneakyThrows
    @Override
    public void destroy() {
        // 修复：添加异常处理，确保资源清理的健壮性
        if (fqEncryptService != null) {
            try {
                fqEncryptService.destroy();
            } catch (Exception e) {
                log.warn("销毁FQ签名服务时发生异常", e);
            }
        }
        
        // 注意：WorkerPool 由 unidbg 框架管理，不需要手动关闭
        // 线程池会在 Worker 实例销毁时自动清理
    }
}
