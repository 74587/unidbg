package com.anjia.unidbgserver.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * PostgreSQL 章节缓存数据源配置（显式开启才生效）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fq.cache.postgres", name = "enabled", havingValue = "true")
public class FQCachePostgresConfig {

    private final FQCachePostgresProperties properties;

    @Bean(destroyMethod = "close")
    public DataSource pgCacheDataSource() {
        String rawUrl = trimToNull(properties.getUrl());
        if (rawUrl == null) {
            throw new IllegalStateException("已启用 fq.cache.postgres.enabled=true，但未配置 DB_URL");
        }

        ResolvedConnection resolved = resolveConnection(rawUrl);
        if (resolved.getUsername() == null || resolved.getUsername().trim().isEmpty()) {
            throw new IllegalStateException("DB_URL 无效：缺少用户名（格式应为 postgresql://user:pass@host:port/db）");
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("fq-pg-cache");
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(resolved.getJdbcUrl());
        hikari.setUsername(resolved.getUsername());
        hikari.setPassword(resolved.getPassword());

        int maxPoolSize = Math.max(1, properties.getMaximumPoolSize());
        int minIdle = Math.max(0, Math.min(properties.getMinimumIdle(), maxPoolSize));
        hikari.setMaximumPoolSize(maxPoolSize);
        hikari.setMinimumIdle(minIdle);
        hikari.setConnectionTimeout(Math.max(1000L, properties.getConnectionTimeoutMs()));

        log.info("PostgreSQL 章节缓存已启用");
        return new HikariDataSource(hikari);
    }

    @Bean
    public JdbcTemplate pgCacheJdbcTemplate(DataSource pgCacheDataSource) {
        return new JdbcTemplate(pgCacheDataSource);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResolvedConnection resolveConnection(String rawUrl) {
        String jdbcUrl = toJdbcUrl(rawUrl);
        String username = null;
        String password = "";

        URI uri = parseUriQuietly(rawUrl);
        if (uri != null && ("postgresql".equalsIgnoreCase(uri.getScheme()) || "postgres".equalsIgnoreCase(uri.getScheme()))) {
            String userInfo = trimToNull(uri.getRawUserInfo());
            if (userInfo != null) {
                int split = userInfo.indexOf(':');
                String userPart = split >= 0 ? userInfo.substring(0, split) : userInfo;
                String passPart = split >= 0 ? userInfo.substring(split + 1) : null;

                String uriUser = decodeUriPart(userPart);
                String uriPass = passPart == null ? null : decodeUriPart(passPart);

                username = trimToNull(uriUser);
                password = uriPass == null ? "" : uriPass;
            }
        }

        return new ResolvedConnection(jdbcUrl, username, password);
    }

    private static String toJdbcUrl(String rawUrl) {
        String value = trimToNull(rawUrl);
        if (value == null) {
            return null;
        }

        URI uri = parseUriQuietly(value);
        if (uri == null) {
            throw new IllegalStateException("DB_URL 无效：格式错误");
        }
        String scheme = trimToNull(uri.getScheme());
        if (!"postgresql".equalsIgnoreCase(scheme) && !"postgres".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("DB_URL 无效：必须以 postgresql:// 或 postgres:// 开头");
        }

        String host = trimToNull(uri.getHost());
        if (host == null) {
            throw new IllegalStateException("DB_URL 无效：缺少 host");
        }

        int port = uri.getPort();
        String path = trimToNull(uri.getRawPath());
        if (path == null || "/".equals(path)) {
            throw new IllegalStateException("DB_URL 无效：缺少数据库名");
        }

        StringBuilder sb = new StringBuilder("jdbc:postgresql://").append(host);
        if (port > 0) {
            sb.append(':').append(port);
        }
        sb.append(path);

        String query = trimToNull(uri.getRawQuery());
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    private static URI parseUriQuietly(String value) {
        try {
            return URI.create(value);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String decodeUriPart(String value) {
        if (value == null) {
            return null;
        }
        try {
            // URLDecoder 会将 '+' 解释为空格，这里先转义为 %2B，避免密码/用户名中 '+' 被误改。
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static final class ResolvedConnection {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private ResolvedConnection(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        private String getJdbcUrl() {
            return jdbcUrl;
        }

        private String getUsername() {
            return username;
        }

        private String getPassword() {
            return password;
        }
    }
}
