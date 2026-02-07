package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * FQNovel 小说书籍信息（精简版 - 仅 Legado 需要的字段）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQNovelBookInfoSimple {

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
     * 书籍描述
     */
    private String description;

    /**
     * 书籍封面URL
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
     * 书籍状态 (0: 连载中, 2: 已完结)
     */
    private Integer status;

    /**
     * 从完整对象转换为精简对象
     */
    public static FQNovelBookInfoSimple fromFull(FQNovelBookInfo full) {
        if (full == null) {
            return null;
        }
        
        FQNovelBookInfoSimple simple = new FQNovelBookInfoSimple();
        simple.setBookId(full.getBookId());
        simple.setBookName(full.getBookName());
        simple.setAuthor(full.getAuthor());
        simple.setDescription(full.getDescription());
        simple.setCoverUrl(full.getCoverUrl());
        simple.setTotalChapters(full.getTotalChapters());
        simple.setWordNumber(full.getWordNumber());
        simple.setLastChapterTitle(full.getLastChapterTitle());
        simple.setCategory(full.getCategory());
        simple.setStatus(full.getStatus());
        
        return simple;
    }
}
