package com.anjia.unidbgserver.utils;

import java.util.concurrent.ThreadLocalRandom;

public final class RetryBackoff {

    private RetryBackoff() {
    }

    public static long computeDelay(
        long baseDelayMs,
        long maxDelayMs,
        int attempt,
        int maxExponent,
        long jitterMinInclusive,
        long jitterMaxExclusive
    ) {
        return computeDelay(
            baseDelayMs,
            maxDelayMs,
            attempt,
            maxExponent,
            jitterMinInclusive,
            jitterMaxExclusive,
            true
        );
    }

    public static long computeDelay(
        long baseDelayMs,
        long maxDelayMs,
        int attempt,
        int maxExponent,
        long jitterMinInclusive,
        long jitterMaxExclusive,
        boolean clampAfterJitter
    ) {
        long normalizedBase = Math.max(0L, baseDelayMs);
        long normalizedMax = Math.max(normalizedBase, maxDelayMs);
        int exponent = Math.max(0, Math.min(maxExponent, attempt - 1));

        long delay = normalizedBase == 0L ? 0L : (normalizedBase * (1L << exponent));
        delay = Math.min(delay, normalizedMax);

        long jitter = 0L;
        if (jitterMaxExclusive > jitterMinInclusive) {
            jitter = ThreadLocalRandom.current().nextLong(jitterMinInclusive, jitterMaxExclusive);
        } else if (jitterMinInclusive > 0L) {
            jitter = jitterMinInclusive;
        }

        long total = Math.max(0L, delay + jitter);
        return clampAfterJitter ? Math.min(normalizedMax, total) : total;
    }

    public static boolean sleep(long delayMs) {
        if (delayMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
