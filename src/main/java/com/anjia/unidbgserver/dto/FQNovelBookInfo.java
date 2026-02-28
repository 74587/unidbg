package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * FQNovel 书籍信息（Legado 所需最小字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FQNovelBookInfo(
    String bookId,
    String bookName,
    String author,
    String description,
    String coverUrl,
    Integer totalChapters,
    Long wordNumber,
    String lastChapterTitle,
    String category,
    Integer status
) {
    public FQNovelBookInfo withTotalChapters(Integer value) {
        return new FQNovelBookInfo(
            bookId,
            bookName,
            author,
            description,
            coverUrl,
            value,
            wordNumber,
            lastChapterTitle,
            category,
            status
        );
    }
}
