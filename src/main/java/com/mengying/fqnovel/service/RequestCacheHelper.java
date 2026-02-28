package com.mengying.fqnovel.service;

import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.utils.ProcessLifecycle;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 通用的请求缓存辅助工具：本地缓存 + 请求去重（inflight dedup）。
 * <p>
 * 从 FQSearchService 中提取，供搜索、目录等服务共用。
 */
public final class RequestCacheHelper {

    private static final Logger log = LoggerFactory.getLogger(RequestCacheHelper.class);

    private RequestCacheHelper() {
    }

    // ── 通用响应判断 ──────────────────────────────────────────────

    public static boolean isResponseSuccess(FQNovelResponse<?> response) {
        return response != null && response.code() != null && response.code() == 0;
    }

    public static boolean isResponseSuccessWithData(FQNovelResponse<?> response) {
        return isResponseSuccess(response) && response.data() != null;
    }

    // ── 优雅退出快捷方法 ────────────────────────────────────────

    public static <T> FQNovelResponse<T> shuttingDownIfNeeded() {
        return ProcessLifecycle.isShuttingDown()
            ? FQNovelResponse.error("服务正在退出中，请稍后重试")
            : null;
    }

    public static <T> CompletableFuture<FQNovelResponse<T>> completedShuttingDownIfNeeded() {
        FQNovelResponse<T> response = shuttingDownIfNeeded();
        return response == null ? null : CompletableFuture.completedFuture(response);
    }

    // ── 带请求去重的缓存加载 ─────────────────────────────────────

    /**
     * 先查本地缓存，再做 inflight 去重，最后异步执行 loader。
     *
     * @param cacheKey           缓存 key，为 null 时跳过缓存直接加载
     * @param cache              本地 Caffeine 缓存
     * @param inflight           正在执行中的请求 map（用于去重）
     * @param loader             实际加载逻辑
     * @param cacheablePredicate 判断结果是否可缓存
     * @param cacheHitHook       缓存命中时执行的钩子（可为 null）
     * @param cacheLabel         日志标签
     * @param taskExecutor       异步执行器
     */
    public static <T> CompletableFuture<FQNovelResponse<T>> loadWithRequestCache(
        String cacheKey,
        Cache<String, T> cache,
        ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<T>>> inflight,
        Supplier<FQNovelResponse<T>> loader,
        Predicate<FQNovelResponse<T>> cacheablePredicate,
        Runnable cacheHitHook,
        String cacheLabel,
        Executor taskExecutor
    ) {
        if (cacheKey == null) {
            return CompletableFuture.supplyAsync(loader, taskExecutor);
        }

        T cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (cacheHitHook != null) {
                cacheHitHook.run();
            }
            if (log.isDebugEnabled()) {
                log.debug("{}缓存命中 - key: {}", cacheLabel, cacheKey);
            }
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        CompletableFuture<FQNovelResponse<T>> existing = inflight.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<FQNovelResponse<T>> created = new CompletableFuture<>();
        existing = inflight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return existing;
        }

        CompletableFuture.supplyAsync(loader, taskExecutor)
            .whenComplete((response, ex) -> {
                try {
                    if (ex != null) {
                        created.completeExceptionally(ex);
                        return;
                    }
                    if (cacheablePredicate != null && cacheablePredicate.test(response)) {
                        cache.put(cacheKey, response.data());
                    }
                    created.complete(response);
                } finally {
                    inflight.remove(cacheKey, created);
                }
            });

        return created;
    }

    /**
     * 异步 loader 版本：
     * loader 自身返回 CompletableFuture，可用于“先做一步，再延迟下一步”的链式流程（不阻塞工作线程）。
     */
    public static <T> CompletableFuture<FQNovelResponse<T>> loadWithRequestCacheAsync(
        String cacheKey,
        Cache<String, T> cache,
        ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<T>>> inflight,
        Supplier<CompletableFuture<FQNovelResponse<T>>> loader,
        Predicate<FQNovelResponse<T>> cacheablePredicate,
        Runnable cacheHitHook,
        String cacheLabel
    ) {
        if (cacheKey == null) {
            return startAsyncLoader(loader);
        }

        T cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (cacheHitHook != null) {
                cacheHitHook.run();
            }
            if (log.isDebugEnabled()) {
                log.debug("{}缓存命中 - key: {}", cacheLabel, cacheKey);
            }
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        CompletableFuture<FQNovelResponse<T>> existing = inflight.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<FQNovelResponse<T>> created = new CompletableFuture<>();
        existing = inflight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return existing;
        }

        startAsyncLoader(loader).whenComplete((response, ex) -> {
            try {
                if (ex != null) {
                    created.completeExceptionally(ex);
                    return;
                }
                if (response != null && cacheablePredicate != null && cacheablePredicate.test(response)) {
                    cache.put(cacheKey, response.data());
                }
                created.complete(response);
            } finally {
                inflight.remove(cacheKey, created);
            }
        });

        return created;
    }

    private static <T> CompletableFuture<FQNovelResponse<T>> startAsyncLoader(
        Supplier<CompletableFuture<FQNovelResponse<T>>> loader
    ) {
        try {
            CompletableFuture<FQNovelResponse<T>> future = loader.get();
            if (future != null) {
                return future;
            }
            return CompletableFuture.completedFuture(FQNovelResponse.error("请求处理返回空结果"));
        } catch (Exception ex) {
            CompletableFuture<FQNovelResponse<T>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
}
