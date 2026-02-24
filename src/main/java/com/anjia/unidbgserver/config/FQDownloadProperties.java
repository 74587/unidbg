package com.anjia.unidbgserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下载/上游请求相关配置
 */
@ConfigurationProperties(prefix = "fq.download")
public class FQDownloadProperties {

    /**
     * 章节接口（batch_full）最小请求间隔（ms），用于限制章节请求 QPS。
     * 例如 500ms ~= 1 秒 2 次。
     */
    private long requestIntervalMs = 500;

    /**
     * 可恢复错误时的最大重试次数
     */
    private int maxRetries = 3;

    /**
     * 初始重试延迟（ms）
     */
    private long retryDelayMs = 1500;

    /**
     * 最大重试延迟（ms）
     */
    private long retryMaxDelayMs = 10000;

    /**
     * 上游连接超时（ms）
     */
    private long upstreamConnectTimeoutMs = 8000;

    /**
     * 上游读取超时（ms）
     */
    private long upstreamReadTimeoutMs = 15000;

    /**
     * 单章接口触发时的预取章节数（用于减少上游请求次数）
     */
    private int chapterPrefetchSize = 30;

    /**
     * 章节预取线程池核心线程数
     */
    private int prefetchExecutorCoreSize = 2;

    /**
     * 章节预取线程池最大线程数
     */
    private int prefetchExecutorMaxSize = 2;

    /**
     * 章节预取线程池队列容量
     */
    private int prefetchExecutorQueueCapacity = 256;

    /**
     * 章节预取线程池线程存活时间（秒）
     */
    private int prefetchExecutorKeepAliveSeconds = 60;

    /**
     * 章节内容缓存最大条数
     */
    private int chapterCacheMaxEntries = 2000;

    /**
     * 章节缓存 TTL（ms）
     */
    private long chapterCacheTtlMs = 30 * 60 * 1000L;

    /**
     * 章节失败负缓存 TTL（ms）：同一章节短时间内连续失败时，直接返回失败，避免反复回源触发风控。
     */
    private long chapterNegativeCacheTtlMs = 10 * 60 * 1000L;

    /**
     * 章节响应是否包含 rawContent（原始 HTML）。默认关闭以减少内存与传输开销。
     */
    private boolean chapterIncludeRawContent = false;

    /**
     * 搜索结果缓存最大条数（短 TTL，避免搜索页频繁回源）
     */
    private int searchCacheMaxEntries = 256;

    /**
     * 搜索结果缓存 TTL（ms）
     */
    private long searchCacheTtlMs = 45 * 1000L;

    /**
     * 目录接口缓存最大条数（/toc 与 /book 共用）
     */
    private int apiDirectoryCacheMaxEntries = 512;

    /**
     * 目录接口缓存 TTL（ms）
     */
    private long apiDirectoryCacheTtlMs = 10 * 60 * 1000L;

    /**
     * 自动重启开关：当连续异常达到阈值后，主动退出进程（由 Docker/systemd 拉起）。
     */
    private boolean autoRestartEnabled = true;

    /**
     * 触发自动重启的连续异常次数阈值
     */
    private int autoRestartErrorThreshold = 3;

    /**
     * 统计窗口（ms）：窗口外会重置计数
     */
    private long autoRestartWindowMs = 5 * 60 * 1000L;

    /**
     * 两次自动重启最小间隔（ms），避免重启风暴
     */
    private long autoRestartMinIntervalMs = 60 * 1000L;

    /**
     * 强制退出（ms）：如果 System.exit 因 shutdown hook 卡住，超过该时间后调用 Runtime.halt 直接结束进程。
     * 设为 0 可关闭（默认开启，避免容器无法重启）。
     */
    private long autoRestartForceHaltAfterMs = 10_000L;

    /**
     * 自动重启前的自愈开关：达到阈值时优先尝试重置 signer / 切换设备，避免频繁 System.exit。
     */
    private boolean autoRestartSelfHealEnabled = true;

    /**
     * 自愈冷却时间（ms）：避免异常抖动导致频繁自愈。
     */
    private long autoRestartSelfHealCooldownMs = 60 * 1000L;

    /**
     * 触发退出前的等待时间（ms）：用于给 in-flight 请求一个收尾窗口。
     */
    private long autoRestartExitDelayMs = 5_000L;

    public long getRequestIntervalMs() {
        return requestIntervalMs;
    }

    public void setRequestIntervalMs(long requestIntervalMs) {
        this.requestIntervalMs = requestIntervalMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public long getRetryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public void setRetryMaxDelayMs(long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
    }

    public long getUpstreamConnectTimeoutMs() {
        return upstreamConnectTimeoutMs;
    }

    public void setUpstreamConnectTimeoutMs(long upstreamConnectTimeoutMs) {
        this.upstreamConnectTimeoutMs = upstreamConnectTimeoutMs;
    }

    public long getUpstreamReadTimeoutMs() {
        return upstreamReadTimeoutMs;
    }

    public void setUpstreamReadTimeoutMs(long upstreamReadTimeoutMs) {
        this.upstreamReadTimeoutMs = upstreamReadTimeoutMs;
    }

    public int getChapterPrefetchSize() {
        return chapterPrefetchSize;
    }

    public void setChapterPrefetchSize(int chapterPrefetchSize) {
        this.chapterPrefetchSize = chapterPrefetchSize;
    }

    public int getPrefetchExecutorCoreSize() {
        return prefetchExecutorCoreSize;
    }

    public void setPrefetchExecutorCoreSize(int prefetchExecutorCoreSize) {
        this.prefetchExecutorCoreSize = prefetchExecutorCoreSize;
    }

    public int getPrefetchExecutorMaxSize() {
        return prefetchExecutorMaxSize;
    }

    public void setPrefetchExecutorMaxSize(int prefetchExecutorMaxSize) {
        this.prefetchExecutorMaxSize = prefetchExecutorMaxSize;
    }

    public int getPrefetchExecutorQueueCapacity() {
        return prefetchExecutorQueueCapacity;
    }

    public void setPrefetchExecutorQueueCapacity(int prefetchExecutorQueueCapacity) {
        this.prefetchExecutorQueueCapacity = prefetchExecutorQueueCapacity;
    }

    public int getPrefetchExecutorKeepAliveSeconds() {
        return prefetchExecutorKeepAliveSeconds;
    }

    public void setPrefetchExecutorKeepAliveSeconds(int prefetchExecutorKeepAliveSeconds) {
        this.prefetchExecutorKeepAliveSeconds = prefetchExecutorKeepAliveSeconds;
    }

    public int getChapterCacheMaxEntries() {
        return chapterCacheMaxEntries;
    }

    public void setChapterCacheMaxEntries(int chapterCacheMaxEntries) {
        this.chapterCacheMaxEntries = chapterCacheMaxEntries;
    }

    public long getChapterCacheTtlMs() {
        return chapterCacheTtlMs;
    }

    public void setChapterCacheTtlMs(long chapterCacheTtlMs) {
        this.chapterCacheTtlMs = chapterCacheTtlMs;
    }

    public long getChapterNegativeCacheTtlMs() {
        return chapterNegativeCacheTtlMs;
    }

    public void setChapterNegativeCacheTtlMs(long chapterNegativeCacheTtlMs) {
        this.chapterNegativeCacheTtlMs = chapterNegativeCacheTtlMs;
    }

    public boolean isChapterIncludeRawContent() {
        return chapterIncludeRawContent;
    }

    public void setChapterIncludeRawContent(boolean chapterIncludeRawContent) {
        this.chapterIncludeRawContent = chapterIncludeRawContent;
    }

    public int getSearchCacheMaxEntries() {
        return searchCacheMaxEntries;
    }

    public void setSearchCacheMaxEntries(int searchCacheMaxEntries) {
        this.searchCacheMaxEntries = searchCacheMaxEntries;
    }

    public long getSearchCacheTtlMs() {
        return searchCacheTtlMs;
    }

    public void setSearchCacheTtlMs(long searchCacheTtlMs) {
        this.searchCacheTtlMs = searchCacheTtlMs;
    }

    public int getApiDirectoryCacheMaxEntries() {
        return apiDirectoryCacheMaxEntries;
    }

    public void setApiDirectoryCacheMaxEntries(int apiDirectoryCacheMaxEntries) {
        this.apiDirectoryCacheMaxEntries = apiDirectoryCacheMaxEntries;
    }

    public long getApiDirectoryCacheTtlMs() {
        return apiDirectoryCacheTtlMs;
    }

    public void setApiDirectoryCacheTtlMs(long apiDirectoryCacheTtlMs) {
        this.apiDirectoryCacheTtlMs = apiDirectoryCacheTtlMs;
    }

    public boolean isAutoRestartEnabled() {
        return autoRestartEnabled;
    }

    public void setAutoRestartEnabled(boolean autoRestartEnabled) {
        this.autoRestartEnabled = autoRestartEnabled;
    }

    public int getAutoRestartErrorThreshold() {
        return autoRestartErrorThreshold;
    }

    public void setAutoRestartErrorThreshold(int autoRestartErrorThreshold) {
        this.autoRestartErrorThreshold = autoRestartErrorThreshold;
    }

    public long getAutoRestartWindowMs() {
        return autoRestartWindowMs;
    }

    public void setAutoRestartWindowMs(long autoRestartWindowMs) {
        this.autoRestartWindowMs = autoRestartWindowMs;
    }

    public long getAutoRestartMinIntervalMs() {
        return autoRestartMinIntervalMs;
    }

    public void setAutoRestartMinIntervalMs(long autoRestartMinIntervalMs) {
        this.autoRestartMinIntervalMs = autoRestartMinIntervalMs;
    }

    public long getAutoRestartForceHaltAfterMs() {
        return autoRestartForceHaltAfterMs;
    }

    public void setAutoRestartForceHaltAfterMs(long autoRestartForceHaltAfterMs) {
        this.autoRestartForceHaltAfterMs = autoRestartForceHaltAfterMs;
    }

    public boolean isAutoRestartSelfHealEnabled() {
        return autoRestartSelfHealEnabled;
    }

    public void setAutoRestartSelfHealEnabled(boolean autoRestartSelfHealEnabled) {
        this.autoRestartSelfHealEnabled = autoRestartSelfHealEnabled;
    }

    public long getAutoRestartSelfHealCooldownMs() {
        return autoRestartSelfHealCooldownMs;
    }

    public void setAutoRestartSelfHealCooldownMs(long autoRestartSelfHealCooldownMs) {
        this.autoRestartSelfHealCooldownMs = autoRestartSelfHealCooldownMs;
    }

    public long getAutoRestartExitDelayMs() {
        return autoRestartExitDelayMs;
    }

    public void setAutoRestartExitDelayMs(long autoRestartExitDelayMs) {
        this.autoRestartExitDelayMs = autoRestartExitDelayMs;
    }
}
