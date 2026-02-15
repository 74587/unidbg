package com.anjia.unidbgserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL 章节缓存配置（默认关闭）。
 */
@Data
@ConfigurationProperties(prefix = "fq.cache.postgres")
public class FQCachePostgresProperties {

    /**
     * 是否启用 PostgreSQL 章节缓存。
     */
    private boolean enabled = false;

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
}
