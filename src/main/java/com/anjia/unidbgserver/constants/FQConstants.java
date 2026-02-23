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
         * 搜索接口路径
         */
        public static final String TAB_PATH = "/reading/bookapi/search/tab/v";

        /**
         * 目录接口路径
         */
        public static final String DIRECTORY_ALL_ITEMS_PATH = "/reading/bookapi/directory/all_items/v";

        /**
         * 第一阶段固定上报的上一页搜索间隔（毫秒）
         */
        public static final int PHASE1_LAST_SEARCH_PAGE_INTERVAL = 0;

        /**
         * search_id 恢复流程中每设备最大重试次数上限
         */
        public static final int MAX_RETRIES_PER_DEVICE = 2;

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
     * 章节相关常量
     */
    public static final class Chapter {
        private Chapter() {}

        /**
         * 章节内容接口路径
         */
        public static final String BATCH_FULL_PATH = "/reading/reader/batch_full/v";
    }
}
