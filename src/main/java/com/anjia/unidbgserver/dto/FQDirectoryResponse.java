package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.anjia.unidbgserver.json.LenientIntegerDeserializer;
import lombok.Data;
import java.util.List;

/**
 * FQ书籍目录响应DTO - 基于实际API响应结构
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQDirectoryResponse {

    /**
     * 是否需要重新获取Ban状态
     */
    @JsonProperty("ban_recover")
    private Boolean banRecover;

    /**
     * 附加数据列表
     */
    @JsonProperty("additional_item_data_list")
    private JsonNode additionalItemDataList;

    /**
     * 目录数据 - 章节索引信息
     */
    @JsonProperty("catalog_data")
    private List<CatalogItem> catalogData;

    /**
     * 章节详细数据列表
     */
    @JsonProperty("item_data_list")
    private List<ItemData> itemDataList;

    /**
     * 字段缓存状态
     */
    @JsonProperty("field_cache_status")
    private FieldCacheStatus fieldCacheStatus;

    /**
     * 书籍信息
     */
    @JsonProperty("book_info")
    private FQNovelBookInfoResp bookInfo;

    /**
     * 连载数量
     */
    @JsonProperty("serial_count")
    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer serialCount;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogItem {

        /**
         * 目录ID
         */
        @JsonProperty("catalog_id")
        private String catalogId;

        /**
         * 目录标题
         */
        @JsonProperty("catalog_title")
        private String catalogTitle;

        /**
         * 章节ID
         */
        @JsonProperty("item_id")
        private String itemId;
    }

    /**
     * 章节详细数据（精简版 - 仅保留 Legado 需要的字段）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemData {

        /**
         * 章节ID（Legado 必需）
         */
        @JsonProperty("item_id")
        private String itemId;

        /**
         * 章节标题（Legado 必需）
         */
        private String title;

        /**
         * 章节序号（从1开始，由服务端计算，用于内部逻辑）
         */
        @JsonProperty("chapter_index")
        private Integer chapterIndex;

        /**
         * 是否为最新章节（由服务端计算，用于内部逻辑）
         */
        @JsonProperty("is_latest")
        private Boolean isLatest;

        /**
         * 首次通过时间（时间戳，用于内部排序）
         */
        @JsonProperty("first_pass_time")
        private Integer firstPassTime;

        /**
         * 首次通过时间（格式化字符串，由服务端计算）
         */
        @JsonProperty("first_pass_time_str")
        private String firstPassTimeStr;

        /**
         * 排序序号（由服务端计算）
         */
        @JsonProperty("sort_order")
        private Integer sortOrder;

        /**
         * 是否免费（由服务端计算）
         */
        @JsonProperty("is_free")
        private Boolean isFree;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldCacheStatus {

        /**
         * 书籍信息缓存状态
         */
        @JsonProperty("book_info")
        private CacheInfo bookInfo;

        /**
         * 章节数据列表缓存状态
         */
        @JsonProperty("item_data_list")
        private CacheInfo itemDataList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CacheInfo {

        /**
         * 是否命中缓存
         */
        private Boolean hit;

        /**
         * MD5校验值
         */
        private String md5;
    }

}
