package com.anjia.unidbgserver.dto;

import lombok.Data;

import java.util.List;

/**
 * 搜索响应 DTO（仅保留当前接口链路需要字段）
 */
@Data
public class FQSearchResponse {

    private List<BookItem> books;
    private Integer total;
    private Boolean hasMore;
    private String searchId;

    @Data
    public static class BookItem {
        private String bookId;
        private String bookName;
        private String author;
        private String description;
        private String coverUrl;
        private String lastChapterTitle;
        private String category;
        private Long wordCount;
    }
}
