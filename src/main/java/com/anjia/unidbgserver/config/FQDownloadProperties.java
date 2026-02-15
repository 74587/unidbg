package com.anjia.unidbgserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下载/上游请求相关配置
 */
@Data
@ConfigurationProperties(prefix = "fq.download")
public class FQDownloadProperties {

    /**
     * 上游接口最小请求间隔（ms），用于限制 QPS。
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
     * 章节内容缓存最大条数
     */
    private int chapterCacheMaxEntries = 100;

    /**
     * 章节缓存 TTL（ms）
     */
    private long chapterCacheTtlMs = 30 * 60 * 1000L;

    /**
     * 章节响应是否包含 rawContent（原始 HTML）。默认关闭以减少内存与传输开销。
     */
    private boolean chapterIncludeRawContent = false;

    /**
     * 目录缓存 TTL（ms）
     */
    private long directoryCacheTtlMs = 30 * 60 * 1000L;

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
}
