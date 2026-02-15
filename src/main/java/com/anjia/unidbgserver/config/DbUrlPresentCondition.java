package com.anjia.unidbgserver.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 仅当 fq.cache.postgres.url 有效时启用 PostgreSQL 章节缓存组件。
 */
public class DbUrlPresentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (context == null || context.getEnvironment() == null) {
            return false;
        }
        String url = context.getEnvironment().getProperty("fq.cache.postgres.url");
        return StringUtils.hasText(url);
    }
}
