package com.anjia.unidbgserver.constants;

/**
 * FQNovel 相关常量定义
 */
public final class FQConstants {

    private FQConstants() {
        // 工具类，禁止实例化
    }

    /**
     * 章节相关常量
     */
    public static final class Chapter {
        private Chapter() {}

        /**
         * 最大章节位置（用于判断是章节位置还是 itemId）
         */
        public static final int MAX_CHAPTER_POSITION = 10000;

        /**
         * 批量获取章节的最大数量
         */
        public static final int MAX_BATCH_SIZE = 30;

        /**
         * 批量获取章节的最小数量
         */
        public static final int MIN_BATCH_SIZE = 1;
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

    /**
     * HTTP 相关常量
     */
    public static final class Http {
        private Http() {}

        /**
         * GZIP 魔数第一个字节
         */
        public static final byte GZIP_MAGIC_BYTE_1 = (byte) 0x1f;

        /**
         * GZIP 魔数第二个字节
         */
        public static final byte GZIP_MAGIC_BYTE_2 = (byte) 0x8b;
    }

    /**
     * 缓存相关常量
     */
    public static final class Cache {
        private Cache() {}

        /**
         * 默认章节缓存最大条数
         */
        public static final int DEFAULT_CHAPTER_CACHE_MAX_ENTRIES = 500;

        /**
         * 默认章节缓存 TTL（毫秒）
         */
        public static final long DEFAULT_CHAPTER_CACHE_TTL_MS = 30 * 60 * 1000L;

        /**
         * 默认目录缓存 TTL（毫秒）
         */
        public static final long DEFAULT_DIRECTORY_CACHE_TTL_MS = 30 * 60 * 1000L;
    }
}
