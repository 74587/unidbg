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

    public String getBookId() {
        return bookId;
    }

    public String getBookName() {
        return bookName;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public Integer getTotalChapters() {
        return totalChapters;
    }

    public Long getWordNumber() {
        return wordNumber;
    }

    public String getLastChapterTitle() {
        return lastChapterTitle;
    }

    public String getCategory() {
        return category;
    }

    public Integer getStatus() {
        return status;
    }
}
