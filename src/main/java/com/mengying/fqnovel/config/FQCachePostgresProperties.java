package com.mengying.fqnovel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL 章节缓存配置。
 */
@ConfigurationProperties(prefix = "fq.cache.postgres")
public class FQCachePostgresProperties {

    /**
     * 数据库连接地址（DB_URL）：
     * postgresql://user:pass@127.0.0.1:5432/fqnovel
     */
    private String url;

    /**
     * 连接池最大连接数。
     */
    private int maximumPoolSize = 8;

    /**
     * 连接池最小空闲连接数。
     */
    private int minimumIdle = 2;

    /**
     * 获取连接超时时间（毫秒）。
     */
    private long connectionTimeoutMs = 5000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }
}
