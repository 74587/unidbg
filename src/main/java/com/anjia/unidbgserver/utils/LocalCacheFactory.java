package com.anjia.unidbgserver.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存构建工具：统一 LRU + TTL 策略。
 */
public final class LocalCacheFactory {

    private LocalCacheFactory() {
    }

    public static <K, V> Cache<K, V> build(long maxEntries, long ttlMs) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            .maximumSize(Math.max(1L, maxEntries));
        if (ttlMs > 0) {
            builder = builder.expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS);
        }
        return builder.build();
    }
}
