package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * FQNovel 书籍信息（Legado 所需最小字段）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQNovelBookInfo {

    /**
     * 书籍ID
     */
    private String bookId;

    /**
     * 书籍名称
     */
    private String bookName;

    /**
     * 作者
     */
    private String author;

    /**
     * 书籍简介
     */
    private String description;

    /**
     * 书籍封面 URL
     */
    private String coverUrl;

    /**
     * 章节总数
     */
    private Integer totalChapters;

    /**
     * 字数
     */
    private Long wordNumber;

    /**
     * 最新章节标题
     */
    private String lastChapterTitle;

    /**
     * 分类
     */
    private String category;

    /**
     * 状态（0 连载中 / 1 已完结）
     */
    private Integer status;
}
