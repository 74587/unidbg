package com.anjia.unidbgserver.constants;

/**
 * FQNovel 相关常量定义
 */
public final class FQConstants {

    private FQConstants() {
        // 工具类，禁止实例化
    }

    /**
     * 搜索相关常量
     */
    public static final class Search {
        private Search() {}

        /**
         * 第一阶段和第二阶段搜索之间的最小延迟（毫秒）
         */
        public static final long MIN_SEARCH_DELAY_MS = 1000L;

        /**
         * 第一阶段和第二阶段搜索之间的最大延迟（毫秒）
         */
        public static final long MAX_SEARCH_DELAY_MS = 2000L;
    }
}
