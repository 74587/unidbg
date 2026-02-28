package com.anjia.unidbgserver.utils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的高频日志节流工具。
 * <p>
 * 同一个 key 在冷却时间内只允许日志一次，避免高频场景下日志刷屏。
 * 从 FQNovelService / FQSearchService 中提取共用。
 */
public final class ThrottledLogger {

    private final ConcurrentHashMap<String, Long> timestamps = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public ThrottledLogger(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * 判断当前是否允许打日志。
     *
     * @param key 日志场景标识
     * @return true 表示允许打日志（冷却已过或首次出现）
     */
    public boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        long[] result = {0L};
        timestamps.merge(key, now, (oldVal, newVal) -> {
            if (newVal - oldVal < cooldownMs) {
                result[0] = oldVal;
                return oldVal;
            }
            return newVal;
        });
        return result[0] == 0L || now - result[0] >= cooldownMs;
    }
}
